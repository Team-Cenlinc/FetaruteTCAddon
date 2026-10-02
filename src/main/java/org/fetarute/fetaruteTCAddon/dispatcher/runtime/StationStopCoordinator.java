package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;

/**
 * 车站停靠的外围事：把停靠事实播给观察者，按发车计划决定要不要扣住早到的车，以及晚点的车怎么追——停站压缩到多少、线路限速放宽多少。
 *
 * <p>它被单独拆出来而不是写进 {@code RuntimeDispatchService}，有一个非常具体的理由：SpotBugs 对单个类的方法数有上限， 超过之后整个类会被标记
 * {@code SKIPPED_CLASS_TOO_BIG} 并<b>完全跳过分析</b>。{@code RuntimeDispatchService} 在本次改动前 是 993
 * 个方法，离那条线只剩几个名额——而它恰恰是全项目最需要被静态分析覆盖的类。所以这里只在它上面留下一个访问器，
 * 其余全部落在本类。功能上这两件事也确实不属于调度本身：它们不读占用、不改授权、不碰信号。
 *
 * <p>本类只做判断与广播，从不修改列车状态。列车身份与交路的解析仍由 {@code RuntimeDispatchService} 提供， 通过构造期传入的方法引用复用，避免同一套 tag
 * 解析出现第二份实现。
 */
public final class StationStopCoordinator {

  /**
   * 计划扣留的硬上限。
   *
   * <p>发车门锁本身 180 秒就会被回收；扣留必须先于门锁到期结束，否则列车会进入 “自己不动、也不再为别人排队”的状态——那是最难从现场归因的一类停滞。配置写多大都不会越过它。
   */
  public static final Duration HOLD_CEILING = Duration.ofSeconds(150);

  private final Consumer<String> debugLogger;
  private final Supplier<Instant> clock;
  private final RouteDefinitionCache routeDefinitions;
  private final Predicate<TrainProperties> managedTrains;
  private final Function<TrainProperties, Optional<String>> trainNames;
  private final Function<TrainProperties, Optional<RouteDefinition>> routes;
  private final Function<TrainProperties, Optional<UUID>> routeUuids;

  private volatile StationStopObserver observer;
  private volatile ScheduledDeparturePlan plan;

  /** 选台器：站牌经本类读暂定站台，站台落定事件经本类读原计划。调度服务构造时接上。 */
  private volatile DynamicPlatformAllocator platforms;

  private volatile Duration maxHold = Duration.ZERO;

  private volatile Recovery recovery = Recovery.DISABLED;

  /** 正在按表扣留的车 → 扣到几点。只用来回答健康检查"它是不是在等点"，不参与任何放行判定。 */
  private final ConcurrentMap<String, Instant> scheduledHolds = new ConcurrentHashMap<>();

  /** 当前放宽了线路限速的车。只用来在进入/退出时各留一行审计，不参与判定。 */
  private final ConcurrentMap<String, Boolean> overspeedEngaged = new ConcurrentHashMap<>();

  /**
   * 晚点追赶参数。
   *
   * <p>两个手段都只在列车绑定了表定车次、且确实晚点时生效，都只会缩短等待：停站压缩不会让早到的车停得更久，放宽限速不碰进站、临时限速与信号速度。
   *
   * @param minDwellSeconds 晚点车中途站最少停多少秒；非正值表示不压缩停站
   * @param overspeedPercent 晚点车线路限速放宽的百分比；非正值表示不放宽
   * @param engageDelaySeconds 晚点达到多少秒才放宽线路限速
   */
  public record Recovery(int minDwellSeconds, int overspeedPercent, int engageDelaySeconds) {

    /** 全部关闭。 */
    public static final Recovery DISABLED = new Recovery(0, 0, 0);

    public Recovery {
      minDwellSeconds = Math.max(0, minDwellSeconds);
      overspeedPercent = Math.max(0, overspeedPercent);
      engageDelaySeconds = Math.max(0, engageDelaySeconds);
    }
  }

  StationStopCoordinator(
      Consumer<String> debugLogger,
      Supplier<Instant> clock,
      RouteDefinitionCache routeDefinitions,
      Predicate<TrainProperties> managedTrains,
      Function<TrainProperties, Optional<String>> trainNames,
      Function<TrainProperties, Optional<RouteDefinition>> routes,
      Function<TrainProperties, Optional<UUID>> routeUuids) {
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
    this.clock = clock == null ? Instant::now : clock;
    this.routeDefinitions = routeDefinitions;
    this.managedTrains = managedTrains;
    this.trainNames = trainNames;
    this.routes = routes;
    this.routeUuids = routeUuids;
  }

  /** 注册停靠事件观察者；{@code null} 表示不再观察，调度行为在两种情况下完全一致。 */
  public void setObserver(StationStopObserver next) {
    this.observer = next;
  }

  /** 注册发车计划源；{@code null} 会立刻停止一切计划扣留，不需要等任何超时。 */
  public void setPlan(ScheduledDeparturePlan next) {
    this.plan = next;
  }

  /**
   * 计划站台（{@link DynamicPlatformAllocator.PlatformPreference}）：按当前计划源查这辆车在该停靠点排定的股道。 没有计划源或没有计划时为空。
   */
  Optional<NodeId> plannedPlatform(String trainName, RouteDefinition route, int stopIndex) {
    ScheduledDeparturePlan current = plan;
    if (current == null || route == null || routeDefinitions == null) {
      return Optional.empty();
    }
    return routeDefinitions
        .findUuid(route.id())
        .flatMap(routeId -> current.plannedPlatformOf(trainName, routeId, stopIndex))
        .map(NodeId::of);
  }

  /** 接上选台器（调度服务构造时）。 */
  void attachPlatforms(DynamicPlatformAllocator allocator) {
    this.platforms = allocator;
  }

  /**
   * 站牌用：运行中列车在某个尚未选台的 DYNAMIC 停靠显示哪条站台。时刻表排定的计划站台优先；没有时读调度这边已定的暂定站台（{@link
   * DynamicPlatformAllocator#refreshTentative}）。只读，可在任意线程调用。
   * 选台偏好读的是同一份，站牌显示的就是车会去的那条（被占时改选，并发站台变更）。
   *
   * @param trainName 列车名
   * @param route 列车当前交路
   * @param stopIndex 停靠下标
   * @return 计划或暂定股道
   */
  public Optional<NodeId> displayPlatform(String trainName, RouteDefinition route, int stopIndex) {
    Optional<NodeId> planned = plannedPlatform(trainName, route, stopIndex);
    DynamicPlatformAllocator allocator = platforms;
    return planned.isPresent() || allocator == null
        ? planned
        : allocator.heldTentative(trainName, route, stopIndex);
  }

  /** 设置晚点追赶参数；{@code null} 等同全部关闭。 */
  public void setRecovery(Recovery next) {
    // 已放宽的车不在这里清：下一次查倍率时逐车恢复并各留一行 released，审计才成对。
    this.recovery = next == null ? Recovery.DISABLED : next;
  }

  /** 设置扣留上限。入参只能让扣留更短：超过 {@link #HOLD_CEILING} 的部分会被截断，非正值表示禁用。 */
  public void setMaxHold(Duration next) {
    if (next == null || next.isNegative() || next.isZero()) {
      this.maxHold = Duration.ZERO;
      return;
    }
    this.maxHold = next.compareTo(HOLD_CEILING) > 0 ? HOLD_CEILING : next;
  }

  /**
   * 判断是否应当为了等待计划发车时刻而扣留列车。
   *
   * <p>只有一条路径返回 {@code true}：有计划源、有计划时刻、列车确实早到、且早到幅度在上限之内。 其余每一条分支都返回 {@code
   * false}，退回“按信号发车”的现状。这个不对称是刻意的—— 返回 false 的代价是这趟车没按表走，返回 true 的代价是这趟车停在站里不动，并顺带堵住它身后的所有车。
   *
   * <p>比较的两个量都是调度层时钟给出的 {@link Instant}：{@code now} 由调用方从同一个时钟取，计划时刻由实现方 按同一个 {@code now}
   * 换算。绝不能在这里引入 {@code System.currentTimeMillis()}。
   *
   * @param trainName 规范列车名
   * @param route 已解析的交路
   * @param routeUuid 交路 UUID
   * @param currentIndex 当前停靠索引
   * @param nodeId 当前节点
   * @param now 调度层当前时间
   * @return 需要继续扣留时返回 true
   */
  public boolean holdsDeparture(
      String trainName,
      RouteDefinition route,
      Optional<UUID> routeUuid,
      int currentIndex,
      NodeId nodeId,
      Instant now) {
    Optional<Instant> heldUntil =
        scheduledHoldTarget(trainName, route, routeUuid, currentIndex, nodeId, now);
    String key = holdKey(trainName);
    if (key != null) {
      heldUntil.ifPresentOrElse(
          until -> scheduledHolds.put(key, until), () -> scheduledHolds.remove(key));
    }
    return heldUntil.isPresent();
  }

  /**
   * 这辆车此刻是否正被按表扣在站里。
   *
   * <p>给健康检查用：扣留期间静止、进度不变都是计划内的，和 dwell 一样不能当成停滞去"恢复"—— 恢复动作一路升级到强制重发，会把等点的车提前放走。
   * 最近一次门控放行（到点、晚点或改了计划）即失效，最迟到计划发车时刻自动失效。
   *
   * @param trainName 列车名（大小写不敏感）
   * @return 正在按表扣留时返回 true
   */
  public boolean holdingForSchedule(String trainName) {
    String key = holdKey(trainName);
    Instant until = key == null ? null : scheduledHolds.get(key);
    if (until == null) {
      return false;
    }
    if (clock.get().isBefore(until)) {
      return true;
    }
    scheduledHolds.remove(key, until);
    return false;
  }

  /** 扣留判定本体：要扣就返回扣到几点。 */
  private Optional<Instant> scheduledHoldTarget(
      String trainName,
      RouteDefinition route,
      Optional<UUID> routeUuid,
      int currentIndex,
      NodeId nodeId,
      Instant now) {
    ScheduledDeparturePlan current = this.plan;
    Duration cap = this.maxHold;
    if (current == null || cap.isZero() || route == null || nodeId == null || now == null) {
      return Optional.empty();
    }
    Optional<Instant> scheduled;
    try {
      scheduled =
          current.scheduledDepartureAt(
              event(trainName, route, routeUuid, currentIndex, nodeId, now));
    } catch (RuntimeException ex) {
      // 计划源自己出错时绝不能把车留在站里：吞掉异常、记一条审计、按现状放行。
      debugLogger.accept("SCHEDULED_DEPARTURE_PLAN_FAILED train=" + trainName + " error=" + ex);
      return Optional.empty();
    }
    if (scheduled == null || scheduled.isEmpty()) {
      return Optional.empty();
    }
    Instant target = scheduled.get();
    if (!now.isBefore(target)) {
      // 已到点或已晚点：立刻放行。时刻表不负责让晚点的车更晚。
      return Optional.empty();
    }
    Duration early = Duration.between(now, target);
    if (early.compareTo(cap) > 0) {
      // 早得超出上限说明匹配到了错误的车次或表本身有问题，此时扣留没有意义，只会制造一辆假死车。
      debugLogger.accept(
          "SCHEDULED_DEPARTURE_HOLD_SKIPPED train="
              + trainName
              + " node="
              + nodeId.value()
              + " earlySeconds="
              + early.toSeconds()
              + " maxHoldSeconds="
              + cap.toSeconds());
      return Optional.empty();
    }
    debugLogger.accept(
        "SCHEDULED_DEPARTURE_HOLD train="
            + trainName
            + " node="
            + nodeId.value()
            + " index="
            + currentIndex
            + " plannedDeparture="
            + target
            + " remainingSeconds="
            + early.toSeconds());
    return Optional.of(target);
  }

  /**
   * 记录一次已放行的车站发车。
   *
   * <p>由 AutoStation 在拿到发车许可、松开门锁的同一时刻调用。它不参与控车，只把“这趟车几点几分从这站开出”
   * 这个事实交给观察者——录制时刻表需要的正是这个时刻，而不是“门控什么时候允许它开”。
   *
   * <p>之所以不把通知塞进 {@code checkDeparture} 的返回路径：那个方法有多条 {@code return true}，
   * 其中不少属于“不做门控”而非“真的发车”（终到、缺失证据回退等）。 在唯一真正对应物理发车的调用点上报， 才不会把没开走的车录成开走了。
   *
   * @param group TrainCarts 列车组
   * @param definition 发车站点的节点定义
   */
  public void handleDeparture(MinecartGroup group, SignNodeDefinition definition) {
    if (observer == null) {
      return;
    }
    resolveStop(group, definition)
        .ifPresent(
            stop ->
                notifyStop(
                    false,
                    stop.trainName(),
                    stop.route(),
                    stop.routeUuid(),
                    stop.index(),
                    definition.nodeId(),
                    clock.get()));
  }

  /**
   * 本站实际停站多少秒：晚点的车压缩到刚好赶上计划发车，但不少于最小停站，也不超过计划停站。
   *
   * <p>由 AutoStation 在列车停稳、运行时到站进度已提交之后、排开关门时序之前调用——关门时刻按这个数排，事后再改就来不及了。
   * 只缩不延：早到的车照旧按计划停站，多出来的由出站门控的计划扣留去等。
   *
   * <p>以下情形一律按计划停站，退回现状：没开晚点追赶；出库后第一站（开门延迟更长，还有重试窗口）；交路起点与终点 （终点停站就是折返，另算）；列车没绑车次，或绑定的车次不在这条交路上。
   *
   * @param group TrainCarts 列车组
   * @param definition 停靠站点的节点定义
   * @param plannedDwellSeconds 交路定义里的停站秒数
   * @param firstStop 是否出库后的第一站
   * @param doorOpenDelayTicks 停稳到开门的 tick 数；停站从开门起算
   * @param doorFloorSeconds 本车车门走完一个开关过程至少要多少秒；与配置的最小停站取大
   * @return 本站实际停站秒数
   */
  public int dwellSecondsFor(
      MinecartGroup group,
      SignNodeDefinition definition,
      int plannedDwellSeconds,
      boolean firstStop,
      long doorOpenDelayTicks,
      int doorFloorSeconds) {
    if (firstStop) {
      return plannedDwellSeconds;
    }
    return resolveStop(group, definition)
        .map(
            stop ->
                dwellSecondsFor(
                    stop.trainName(),
                    stop.route(),
                    stop.routeUuid(),
                    stop.index(),
                    definition.nodeId(),
                    plannedDwellSeconds,
                    doorOpenDelayTicks,
                    doorFloorSeconds))
        .orElse(plannedDwellSeconds);
  }

  /**
   * 同 {@link #dwellSecondsFor(MinecartGroup, SignNodeDefinition, int, boolean, long,
   * int)}，列车身份与进度已解析、且不是出库后第一站。
   *
   * @param trainName 规范列车名
   * @param route 已解析的交路
   * @param routeUuid 交路 UUID
   * @param index 当前停靠索引
   * @param nodeId 当前节点
   * @param plannedDwellSeconds 交路定义里的停站秒数
   * @param doorOpenDelayTicks 停稳到开门的 tick 数
   * @param doorFloorSeconds 车门开关过程的最短秒数
   * @return 本站实际停站秒数
   */
  int dwellSecondsFor(
      String trainName,
      RouteDefinition route,
      Optional<UUID> routeUuid,
      int index,
      NodeId nodeId,
      int plannedDwellSeconds,
      long doorOpenDelayTicks,
      int doorFloorSeconds) {
    Recovery current = this.recovery;
    ScheduledDeparturePlan source = this.plan;
    if (source == null
        || current.minDwellSeconds() <= 0
        || route == null
        || nodeId == null
        || index <= 0) {
      return plannedDwellSeconds;
    }
    boolean terminal =
        index >= route.waypoints().size() - 1
            || (routeDefinitions != null
                && routeDefinitions
                    .findStop(route.id(), index)
                    .map(routeStop -> routeStop.passType() == RouteStopPassType.TERMINATE)
                    .orElse(false));
    if (terminal) {
      return plannedDwellSeconds;
    }
    Instant now = clock.get();
    Optional<Instant> planned;
    try {
      planned = source.boundDepartureAt(event(trainName, route, routeUuid, index, nodeId, now));
    } catch (RuntimeException ex) {
      debugLogger.accept("SCHEDULED_DWELL_PLAN_FAILED train=" + trainName + " error=" + ex);
      return plannedDwellSeconds;
    }
    if (planned == null || planned.isEmpty()) {
      return plannedDwellSeconds;
    }
    Instant doorOpenAt = now.plusMillis(Math.max(0L, doorOpenDelayTicks) * 50L);
    long secondsToPlannedDeparture = Duration.between(doorOpenAt, planned.get()).getSeconds();
    int floor = Math.max(current.minDwellSeconds(), Math.max(0, doorFloorSeconds));
    int dwell = compressedDwellSeconds(plannedDwellSeconds, floor, secondsToPlannedDeparture);
    if (dwell < plannedDwellSeconds) {
      debugLogger.accept(
          "SCHEDULED_DWELL_COMPRESSED train="
              + trainName
              + " node="
              + nodeId.value()
              + " index="
              + index
              + " plannedDwellSeconds="
              + plannedDwellSeconds
              + " dwellSeconds="
              + dwell
              + " floorSeconds="
              + floor
              + " lateSeconds="
              + Math.max(0L, plannedDwellSeconds - secondsToPlannedDeparture));
    }
    return dwell;
  }

  /**
   * 停站压缩的算术：刚好赶上计划发车需要停多久，夹在 {@code [最小停站, 计划停站]} 之间。
   *
   * <p>计划停站本来就不长于最小停站时原样返回——压缩只缩不延。
   *
   * @param plannedDwellSeconds 计划停站
   * @param minDwellSeconds 最小停站；非正值表示不压缩
   * @param secondsToPlannedDeparture 开门时刻到计划发车还有多少秒，晚点时为负
   * @return 实际停站秒数
   */
  static int compressedDwellSeconds(
      int plannedDwellSeconds, int minDwellSeconds, long secondsToPlannedDeparture) {
    if (minDwellSeconds <= 0 || plannedDwellSeconds <= minDwellSeconds) {
      return plannedDwellSeconds;
    }
    long wanted = Math.min(plannedDwellSeconds, secondsToPlannedDeparture);
    return (int) Math.max(minDwellSeconds, wanted);
  }

  /**
   * 线路限速倍率：本车次最近一次到发晚点达到阈值时放宽线路限速，赶上计划（或早于阈值）即恢复。
   *
   * <p>控车每个信号 tick 都会问。倍率只作用于写明的线路限速（见 {@code
   * RailGraphService#effectiveSpeedLimitBlocksPerSecond(UUID, RailEdge, Instant, double, double)}），
   * 进站限速、临时限速、CAUTION 与信号速度都不受影响；制动距离与移动授权按实际车速算，跑得快只会刹得早。 晚点只在到发时更新，所以同一区间内倍率不会来回跳。
   *
   * @param trainName 列车名
   * @return 倍率；不放宽时为 1
   */
  public double lineSpeedFactor(String trainName) {
    Recovery current = this.recovery;
    ScheduledDeparturePlan source = this.plan;
    String key = holdKey(trainName);
    if (key == null || source == null || current.overspeedPercent() <= 0) {
      if (key != null && overspeedEngaged.remove(key) != null) {
        debugLogger.accept(
            "SCHEDULED_RECOVERY_OVERSPEED train="
                + trainName
                + " state=released delaySeconds=- reason=recovery-disabled");
      }
      return 1.0;
    }
    OptionalLong delay;
    try {
      delay = source.currentDelaySeconds(trainName);
    } catch (RuntimeException ex) {
      debugLogger.accept("SCHEDULED_DELAY_READ_FAILED train=" + trainName + " error=" + ex);
      delay = OptionalLong.empty();
    }
    boolean engage =
        delay != null
            && delay.isPresent()
            && delay.getAsLong() >= Math.max(1, current.engageDelaySeconds());
    boolean wasEngaged =
        engage
            ? overspeedEngaged.putIfAbsent(key, Boolean.TRUE) != null
            : overspeedEngaged.remove(key) != null;
    if (engage != wasEngaged) {
      debugLogger.accept(
          "SCHEDULED_RECOVERY_OVERSPEED train="
              + trainName
              + " state="
              + (engage ? "engaged" : "released")
              + " delaySeconds="
              + (delay != null && delay.isPresent() ? delay.getAsLong() : "-")
              + " overspeedPercent="
              + current.overspeedPercent());
    }
    return engage ? 1.0 + current.overspeedPercent() / 100.0 : 1.0;
  }

  /** 播报一次已确认的停靠事件；观察者异常不得影响调度。 */
  public void notifyStop(
      boolean arrival,
      String trainName,
      RouteDefinition route,
      Optional<UUID> routeUuid,
      int currentIndex,
      NodeId nodeId,
      Instant now) {
    StationStopObserver current = this.observer;
    if (current == null || route == null || nodeId == null || now == null) {
      return;
    }
    StationStopEvent stopEvent = event(trainName, route, routeUuid, currentIndex, nodeId, now);
    try {
      if (arrival) {
        current.onStationArrival(stopEvent);
      } else {
        current.onStationDeparture(stopEvent);
      }
    } catch (RuntimeException ex) {
      debugLogger.accept("STATION_STOP_OBSERVER_FAILED train=" + trainName + " error=" + ex);
    }
  }

  /**
   * 某辆车在某个停靠下标的实际股道写定了：选台、到站观测、折返交接写有效节点都经过这里。
   *
   * <p>与上一次相同不发（信号 tick 每 tick 都会重写同一个选台结果，先比这个，不必每次查停靠配置）；第一次定下时， 只有 DYNAMIC
   * 停靠、或实际股道不是声明节点才发——固定站台停在声明的股道上不是新信息。原计划：DYNAMIC 取选台偏好（时刻表的计划站台或暂定站台）， 固定站台就是声明的股道，停到别的股道算偏离计划。
   *
   * @param previous 同一交路上一次写定的股道；没有、或上一次属于别的交路定义时为空
   * @param node 现在写定的股道
   */
  void platformRecorded(
      String trainName, RouteDefinition route, int index, Optional<NodeId> previous, NodeId node) {
    if (route == null
        || node == null
        || index < 0
        || index >= route.waypoints().size()
        || previous.filter(node::equals).isPresent()) {
      return;
    }
    NodeId declared = route.waypoints().get(index);
    boolean dynamic =
        routeDefinitions != null
            && routeDefinitions
                .findStop(route.id(), index)
                .map(DynamicStopMatcher::isDynamicStop)
                .orElse(false);
    if (previous.isEmpty() && !dynamic && node.equals(declared)) {
      return;
    }
    DynamicPlatformAllocator allocator = platforms;
    Optional<NodeId> planned =
        !dynamic
            ? Optional.of(declared)
            : allocator != null
                ? allocator.preferredPlatform(trainName, route, index)
                : Optional.empty();
    notifyPlatform(
        new PlatformResolution(
            trainName,
            route.id().value(),
            index,
            declared,
            previous,
            node,
            planned,
            dynamic,
            clock.get()));
  }

  /** 播报某辆车在某个停靠下标的实际股道定下来或变了；观察者异常不得影响调度。 */
  public void notifyPlatform(PlatformResolution resolution) {
    StationStopObserver current = this.observer;
    if (current == null || resolution == null) {
      return;
    }
    try {
      current.onPlatformResolved(resolution);
    } catch (RuntimeException ex) {
      debugLogger.accept(
          "STATION_STOP_OBSERVER_FAILED train=" + resolution.trainName() + " error=" + ex);
    }
  }

  /** 播报某辆车已离开运行时管辖。 */
  public void notifyReleased(String trainName, String reason) {
    String key = holdKey(trainName);
    if (key != null) {
      scheduledHolds.remove(key);
      overspeedEngaged.remove(key);
    }
    StationStopObserver current = this.observer;
    if (current == null || trainName == null || trainName.isBlank()) {
      return;
    }
    try {
      current.onTrainReleased(trainName, reason);
    } catch (RuntimeException ex) {
      debugLogger.accept("STATION_STOP_OBSERVER_FAILED train=" + trainName + " error=" + ex);
    }
  }

  /** 停站的列车身份、交路与进度索引，与出站门控同一套解析。 */
  private record ResolvedStop(
      String trainName, RouteDefinition route, Optional<UUID> routeUuid, int index) {}

  private Optional<ResolvedStop> resolveStop(MinecartGroup group, SignNodeDefinition definition) {
    if (group == null || definition == null) {
      return Optional.empty();
    }
    TrainProperties properties = group.getProperties();
    if (properties == null || managedTrains == null || !managedTrains.test(properties)) {
      return Optional.empty();
    }
    String trainName = trainNames.apply(properties).orElse(null);
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    Optional<RouteDefinition> routeOpt = routes.apply(properties);
    if (routeOpt.isEmpty()) {
      return Optional.empty();
    }
    RouteDefinition route = routeOpt.get();
    OptionalInt tagIndex =
        TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX)
            .map(OptionalInt::of)
            .orElse(OptionalInt.empty());
    int currentIndex =
        RouteIndexResolver.resolveCurrentIndexWithDynamic(
            route, routeDefinitions, tagIndex, definition.nodeId());
    if (currentIndex < 0) {
      return Optional.empty();
    }
    return Optional.of(
        new ResolvedStop(trainName, route, routeUuids.apply(properties), currentIndex));
  }

  private static String holdKey(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return null;
    }
    return trainName.trim().toLowerCase(Locale.ROOT);
  }

  private static StationStopEvent event(
      String trainName,
      RouteDefinition route,
      Optional<UUID> routeUuid,
      int currentIndex,
      NodeId nodeId,
      Instant now) {
    return new StationStopEvent(
        trainName,
        routeUuid,
        route.id().value(),
        currentIndex,
        route.waypoints().size(),
        nodeId.value(),
        now);
  }
}
