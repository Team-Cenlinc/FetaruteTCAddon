package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerGate;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 信号员进路许可闸的调度侧协作者：持有当前的闸，替各放行入口问闸，并把被拦的车按“等信号员”停住。
 *
 * <p>单独成类的原因与 {@link StationStopCoordinator} 相同：{@code RuntimeDispatchService} 的方法数贴着 SpotBugs
 * 单类分析上限，主类上只留调用点与一个访问器，判定与停车的辅助全部放在这里。
 *
 * <p>调用约定：各入口先照常算完本入口在申请占用之前的内核判定（Smart 准入等），内核本来会放行时才问闸；闸放行就照常往下走，闸拦下就停在控制区外。
 * 闸因此只能把放行变成暂不放行，不会把内核的拒绝变成放行。没有接上信号员模块（{@link SignallerGate#NONE}）或此刻没有任何人工控制区（{@link
 * SignallerGate#active()} 为 false）时，所有入口直接放行，不整理申请、不分配、不记日志。
 *
 * <p>被拦的车不进控制区资源的排队：拦下当拍的停车保持只借这次申请的行车计划（{@link #withoutQueuePosition}），之后它等信号员的每一拍停车保持都不再刷新前方排队位次
 * （{@link #waiting}），它此前在本控制区资源上留下的排队项当场撤掉（{@link
 * #withdrawQueuedEntries}）。否则拿到许可的车会排在它后面。拒绝判定不带阻挡者， 等待图与互卡检测看不到它；健康层只经 {@link #waiting}
 * 把它当作计划内的静止。
 */
public final class SignallerGateCoordinator {

  /** 被信号员拦住时写入停车状态的停因代码。 */
  public static final String STOP_REASON = "WAITING_FOR_SIGNALLER";

  /** 被信号员拦住时自建的拒绝判定原因。 */
  public static final String DECISION_REASON = "waiting-for-signaller";

  private static final Consumer<String> NO_WAKE = trainName -> {};

  /**
   * 控制区的资源归属，由信号员模块提供：资源是否属于这片控制区。撤被拦车的区内排队项按它过滤，只撤区内的。
   *
   * <p>只读、廉价，只在主线程调用；实现不得抛异常。
   */
  @FunctionalInterface
  public interface ZoneMembership {

    /** 没有接上：任何资源都不算区内，被拦时不撤排队项（旧排队项最多留到排队过期）。 */
    ZoneMembership NONE = (box, resource) -> false;

    /**
     * @param box 控制区（{@code 运营商:控制区代码}，即 {@link SignallerGate.Hold#box()}）
     * @param resource 占用资源
     * @return 资源属于这片控制区
     */
    boolean contains(String box, OccupancyResource resource);
  }

  private final Consumer<String> debugLogger;

  /** 撤排队项用的占用账本；不支持排队的实现（或为空）时不撤。 */
  private final OccupancyManager occupancy;

  private volatile SignallerGate gate = SignallerGate.NONE;

  private volatile ZoneMembership zones = ZoneMembership.NONE;

  /** 下一 tick 完整重评估的请求器；调度服务注册重评估入口时同步过来，没注册时不唤醒（周期信号 tick 照样会重评）。 */
  private volatile Consumer<String> wakeRequester = NO_WAKE;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "占用账本是运行时共享服务句柄；撤排队项必须作用在同一份账本上，不能复制。")
  SignallerGateCoordinator(Consumer<String> debugLogger, OccupancyManager occupancy) {
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
    this.occupancy = occupancy;
  }

  /**
   * 接上信号员的闸。
   *
   * @param next 闸；{@code null} 恢复为 {@link SignallerGate#NONE}（一律放行）
   */
  public void setGate(SignallerGate next) {
    this.gate = next == null ? SignallerGate.NONE : next;
  }

  /** 当前接着的闸。 */
  public SignallerGate gate() {
    return gate;
  }

  /**
   * 接上控制区的资源归属。
   *
   * @param next 归属；{@code null} 恢复为 {@link ZoneMembership#NONE}
   */
  public void setZoneMembership(ZoneMembership next) {
    this.zones = next == null ? ZoneMembership.NONE : next;
  }

  /** 是否接上了信号员的闸、且此刻有人工控制区（闸不空闲）。 */
  public boolean active() {
    return engaged(gate);
  }

  /** 闸不空闲：接上了，且此刻有人工控制区。{@link SignallerGate#active()} 出错时按空闲处理（放行即退回自动）。 */
  private boolean engaged(SignallerGate current) {
    if (current == SignallerGate.NONE) {
      return false;
    }
    try {
      return current.active();
    } catch (RuntimeException ex) {
      debugLogger.accept("SIGNALLER_GATE_ACTIVE_FAILED error=" + ex);
      return false;
    }
  }

  /**
   * 请求下一 tick 对这列车做一次完整信号重评估（与占用事件唤醒同一入口）。信号员发许可、改停股道后调用，免得车要等到下一次周期巡检。
   *
   * <p>只排队、不在当拍重算，可以在任何调度回调里安全调用；重评估入口还没接上时什么都不做。
   *
   * @param trainName 列车名
   */
  public void wake(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    try {
      wakeRequester.accept(trainName);
    } catch (RuntimeException ex) {
      debugLogger.accept("SIGNALLER_WAKE_FAILED train=" + trainName + " error=" + ex);
    }
  }

  /** 同步调度服务注册的下一 tick 重评估入口；{@code null} 表示撤销。 */
  void setWakeRequester(Consumer<String> requester) {
    this.wakeRequester = requester == null ? NO_WAKE : requester;
  }

  /**
   * 问闸：内核本来会放行这次申请时调用。
   *
   * <p>闸空闲时直接放行，连申请资源都不整理。交给闸的是这次申请里的硬授权资源（{@link ResourceIntent#hardAuthority()}），按申请里的顺序。
   * 闸抛异常时按放行处理并留一行日志：闸只管次序，安全由内核负责，放行等于退回自动。
   *
   * @param trainName 列车名
   * @param request 这次要申请的占用请求
   * @param now 调度层时钟
   * @return 被拦时的拦截明细；放行时为空
   */
  public Optional<SignallerGate.Hold> admit(
      String trainName, OccupancyRequest request, Instant now) {
    SignallerGate current = gate;
    if (!engaged(current)
        || request == null
        || trainName == null
        || trainName.isBlank()
        || now == null) {
      return Optional.empty();
    }
    List<OccupancyResource> requested = new ArrayList<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource != null && request.intentFor(resource).hardAuthority()) {
        requested.add(resource);
      }
    }
    if (requested.isEmpty()) {
      return Optional.empty();
    }
    try {
      Optional<SignallerGate.Hold> hold = current.admit(trainName, List.copyOf(requested), now);
      return hold == null ? Optional.empty() : hold;
    } catch (RuntimeException ex) {
      debugLogger.accept("SIGNALLER_GATE_FAILED train=" + trainName + " error=" + ex);
      return Optional.empty();
    }
  }

  /**
   * 这列车此刻是否停在控制区入口等信号员。健康层、恢复层与停车保持据此把它当作计划内的静止，停车保持不替它刷新前方排队位次。闸空闲时一律为 false。
   *
   * @param trainName 列车名
   */
  public boolean waiting(String trainName) {
    SignallerGate current = gate;
    if (trainName == null || trainName.isBlank() || !engaged(current)) {
      return false;
    }
    try {
      return current.waiting(trainName);
    } catch (RuntimeException ex) {
      debugLogger.accept("SIGNALLER_GATE_WAITING_FAILED train=" + trainName + " error=" + ex);
      return false;
    }
  }

  /**
   * 撤掉这列车在拦下它的那片控制区资源上的排队项：转人工前已经排上的、许可被撤回之前排上的，都不能留在区内挡拿到许可的车。
   *
   * <p>只撤 {@link ZoneMembership} 认定属于 {@code hold.box()}
   * 的资源，区外的排队不动；只删排队，不释放任何占用。撤过之后它等信号员期间不再刷新排队， 下一拍不会再有可撤的项。没有接上资源归属或账本不支持排队时什么都不做。
   *
   * @param trainName 列车名
   * @param hold 这次拦截
   * @return 撤掉的排队项数
   */
  int withdrawQueuedEntries(String trainName, SignallerGate.Hold hold) {
    ZoneMembership membership = zones;
    if (membership == ZoneMembership.NONE
        || hold == null
        || trainName == null
        || trainName.isBlank()
        || !(occupancy instanceof OccupancyQueueSupport queueSupport)) {
      return 0;
    }
    try {
      Set<OccupancyResource> inZone = new LinkedHashSet<>();
      for (OccupancyQueueSnapshot snapshot : queueSupport.snapshotQueues()) {
        if (snapshot == null
            || snapshot.resource() == null
            || !queuedHere(snapshot, trainName)
            || !membership.contains(hold.box(), snapshot.resource())) {
          continue;
        }
        inZone.add(snapshot.resource());
      }
      if (inZone.isEmpty()) {
        return 0;
      }
      int removed = queueSupport.removeQueueEntries(trainName, List.copyOf(inZone));
      if (removed > 0) {
        debugLogger.accept(
            "SIGNALLER_QUEUE_WITHDRAWN train="
                + trainName
                + " box="
                + hold.box()
                + " removed="
                + removed
                + " resources="
                + inZone);
      }
      return removed;
    } catch (RuntimeException ex) {
      debugLogger.accept("SIGNALLER_QUEUE_WITHDRAW_FAILED train=" + trainName + " error=" + ex);
      return 0;
    }
  }

  private static boolean queuedHere(OccupancyQueueSnapshot snapshot, String trainName) {
    for (OccupancyQueueEntry entry : snapshot.entries()) {
      if (entry != null && TrainNameNormalizer.sameLogicalTrain(trainName, entry.trainName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * 列车改名：许可、等待与扣车随车迁到新名下；确有东西迁过去时唤醒新名重评估。
   *
   * @param from 旧名
   * @param to 新名
   */
  void renamed(String from, String to) {
    SignallerGate current = gate;
    if (current == SignallerGate.NONE
        || from == null
        || from.isBlank()
        || to == null
        || to.isBlank()) {
      return;
    }
    boolean moved;
    try {
      moved = current.renamed(from, to);
    } catch (RuntimeException ex) {
      debugLogger.accept(
          "SIGNALLER_GATE_RENAME_FAILED from=" + from + " to=" + to + " error=" + ex);
      return;
    }
    if (moved) {
      wake(to);
    }
  }

  /**
   * 列车已销毁或已不归调度管：丢掉它的许可与等待状态。
   *
   * @param trainName 列车名
   */
  void forget(String trainName) {
    SignallerGate current = gate;
    if (current == SignallerGate.NONE || trainName == null || trainName.isBlank()) {
      return;
    }
    try {
      current.forget(trainName);
    } catch (RuntimeException ex) {
      debugLogger.accept("SIGNALLER_GATE_FORGET_FAILED train=" + trainName + " error=" + ex);
    }
  }

  /**
   * 被信号员拦下时的拒绝判定：STOP、没有阻挡者。没有阻挡者的停车不进等待图，也不会被当成互卡。
   *
   * @param now 调度层时钟
   */
  static OccupancyDecision blockedDecision(Instant now) {
    return new OccupancyDecision(false, now, SignalAspect.STOP, List.of(), false, DECISION_REASON);
  }

  /**
   * 停车保持用的行车计划：保留这次申请的有向计划（路径、单线方向、道岔签名），去掉全部资源。
   *
   * <p>停车保持拿“行车计划”有两个用途：给停车保持请求补方向证据，以及在前方冲突区刷新排队位次。被信号员拦住的车只要前者——它进不了控制区，
   * 不能替它在区内排队。资源全去掉后排队刷新没有可刷的资源，方向证据仍随有向上下文保留。
   *
   * @param request 这次被拦的申请
   * @return 只带有向计划的空请求
   */
  static OccupancyRequest withoutQueuePosition(OccupancyRequest request) {
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource != null) {
        intents.put(resource, ResourceIntent.LOOKAHEAD_PREVIEW);
      }
    }
    return request.withResourceIntents(intents).withoutLookaheadPreviewResources();
  }
}
