package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteStopDirectives;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 信号员令晚点列车越过下一个停车站。
 *
 * <p>只认按交路进度往后的第一个停车站（{@link RouteStopPassType#STOP}）。越站令按规范车名记在内存里，重启即失效。只在主线程写；读（各读点）可在任意线程。
 */
public final class StopSkips {

  /** 不能越的原因。 */
  public enum Refusal {
    /** 终点：{@code TERMINATE} 停站或交路里最后一个停车站，也包括别的交路在这里终到的车站（短交路终点、折返站）。 */
    TERMINAL,
    /** 这一站有动作指令（换线、加挂、拆开、换模、折返、销毁等），作业离不开停车。 */
    DIRECTIVE,
    /** 交路在这一站换向（图上从哪来往哪回，或判不了，见 {@link StopReversal}）：越过去车就冲出折返点。 */
    REVERSAL,
    /** 车已开始为这一站刹车：这时改成通过会让车在刹车途中重新加速，或停不准。 */
    BRAKING,
    /** 有人叫了这列车，目的站就是这一站。 */
    CALL_DESTINATION,
    /** 有乘务员在这一站等着上车接班。 */
    CREW_BOARDING
  }

  /** 别的模块不许越的站（叫车目的站、乘务员接班站）。 */
  @FunctionalInterface
  public interface Guard {
    Optional<Refusal> refuse(String trainName, NodeId station);

    /**
     * 同一列车的几个名字（调度逻辑名、TrainCarts 车名等）逐个问，任一个被拦就拦：别的模块各按自己的车名认车，改名过渡期名字可能对不上。
     *
     * @param names 列车的各个名字（含传进来的这个）
     * @param guard 按一个名字问的拦截
     */
    static Guard acrossNames(
        java.util.function.Function<String, ? extends java.util.Collection<String>> names,
        Guard guard) {
      return (trainName, station) -> {
        for (String name : names.apply(trainName)) {
          Optional<Refusal> refusal = guard.refuse(name, station);
          if (refusal.isPresent()) {
            return refusal;
          }
        }
        return Optional.empty();
      };
    }
  }

  /** 交路在某一站是不是直通（不换向）；判不了按换向算。 */
  @FunctionalInterface
  interface Reversal {
    boolean through(String trainName, RouteDefinition route, int stopIndex);
  }

  /** 车是不是已开始为某一站刹车。 */
  @FunctionalInterface
  interface Braking {
    boolean started(String trainName, UUID routeUuid, int stopIndex);
  }

  /** 下令、撤销的结果。 */
  public enum Outcome {
    /** 已令越站。 */
    ORDERED,
    /** 已撤销越站。 */
    REVOKED,
    /** 不能越（或不能撤），原因见 {@link Result#refusal()}。 */
    REFUSED
  }

  /**
   * 下令、撤销的结果。
   *
   * @param outcome 结果
   * @param refusal 不能越（或不能撤）的原因；没有下一个停车站时为空
   */
  public record Result(Outcome outcome, Optional<Refusal> refusal) {
    public Result {
      Objects.requireNonNull(outcome, "outcome");
      refusal = refusal == null ? Optional.empty() : refusal;
    }
  }

  /** 算刹车起点用的舒适减速度（格/秒²）：约合 1 米/秒²。 */
  static final double COMFORT_DECEL_BPS2 = 1.0;

  /** 刹车起点外再留的余量：舒适制动距离的一半，至少这么多格。 */
  static final double MIN_LOCK_MARGIN_BLOCKS = 16.0;

  /** 一列车已令越过的那一站。 */
  private record Order(UUID routeUuid, int stopIndex) {}

  /**
   * 下一个停车站。
   *
   * @param routeUuid 交路
   * @param stopIndex 停站下标（与交路进度同一口径）
   * @param node 这一站的节点
   * @param ordered 已令越过这一站
   * @param refusal 不能越时的原因
   */
  public record Candidate(
      UUID routeUuid, int stopIndex, NodeId node, boolean ordered, Optional<Refusal> refusal) {
    public Candidate {
      Objects.requireNonNull(routeUuid, "routeUuid");
      Objects.requireNonNull(node, "node");
      refusal = refusal == null ? Optional.empty() : refusal;
    }
  }

  private final RouteProgressRegistry progress;
  private final RouteDefinitionCache routes;
  private final Braking braking;
  private final Reversal reversal;
  private volatile Guard guard;

  /** 各交路终到的车站（运营商:站码，大写）；交路变了作废，下次用时重算。 */
  private volatile Set<String> termini;

  /** 已下的越站令：规范车名 → 那一站。读（各读点）可在任意线程。 */
  private final ConcurrentMap<String, Order> orders = new ConcurrentHashMap<>();

  StopSkips(
      RouteProgressRegistry progress, RouteDefinitionCache routes, Braking braking, Guard guard) {
    this(progress, routes, braking, guard, null);
  }

  /**
   * @param reversal 图上判换向；为空时只认节点序列上的原路折返
   */
  StopSkips(
      RouteProgressRegistry progress,
      RouteDefinitionCache routes,
      Braking braking,
      Guard guard,
      Reversal reversal) {
    this.progress = progress;
    this.routes = routes;
    this.braking = braking == null ? (train, route, index) -> false : braking;
    this.reversal = reversal == null ? (train, route, index) -> true : reversal;
    this.guard = guard == null ? (train, station) -> Optional.empty() : guard;
    if (routes != null) {
      routes.addChangeListener(() -> termini = null);
    }
  }

  /**
   * 接上别的模块不许越的站（叫车、乘务员这些模块晚于调度服务就绪）。
   *
   * @param next 传 null 表示不再拦
   */
  public void setGuard(Guard next) {
    this.guard = next == null ? (train, station) -> Optional.empty() : next;
  }

  /** 没有接上调度运行时的实例：没有下一个停车站，越站令一律不成。 */
  static StopSkips detached() {
    return new StopSkips(null, null, null, null);
  }

  /**
   * 这列车的下一个停车站；没有进度、交路或往后已无停车站时为空。
   *
   * @param trainName 列车名
   */
  public Optional<Candidate> candidate(String trainName) {
    if (progress == null || routes == null) {
      return Optional.empty();
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entry = progress.get(trainName);
    if (entry.isEmpty()) {
      return Optional.empty();
    }
    UUID routeUuid = entry.get().routeUuid();
    Optional<RouteDefinition> route = routes.findById(routeUuid);
    if (route.isEmpty()) {
      return Optional.empty();
    }
    int size = route.get().waypoints().size();
    for (int i = entry.get().currentIndex() + 1; i < size; i++) {
      Optional<RouteStop> stop = routes.findStop(route.get().id(), i);
      if (stop.isEmpty() || stop.get().passType() == RouteStopPassType.PASS) {
        continue;
      }
      boolean terminal =
          stop.get().passType() == RouteStopPassType.TERMINATE || !stopsLater(route.get(), i + 1);
      NodeId node = route.get().waypoints().get(i);
      Optional<Refusal> refusal;
      if (terminal || terminus(node)) {
        refusal = Optional.of(Refusal.TERMINAL);
      } else if (RouteStopDirectives.hasAction(stop.get())) {
        refusal = Optional.of(Refusal.DIRECTIVE);
      } else if (reversesAt(route.get().waypoints(), i)
          || !reversal.through(trainName, route.get(), i)) {
        refusal = Optional.of(Refusal.REVERSAL);
      } else {
        refusal = guard.refuse(trainName, node);
        if (refusal.isEmpty() && braking.started(trainName, routeUuid, i)) {
          refusal = Optional.of(Refusal.BRAKING);
        }
      }
      boolean ordered = live(trainName).filter(new Order(routeUuid, i)::equals).isPresent();
      return Optional.of(new Candidate(routeUuid, i, node, ordered, refusal));
    }
    return Optional.empty();
  }

  /**
   * 令这列车越过下一个停车站。
   *
   * @param trainName 列车名
   */
  public Result order(String trainName) {
    Optional<Candidate> candidate = candidate(trainName);
    if (candidate.isEmpty() || candidate.get().refusal().isPresent()) {
      return new Result(Outcome.REFUSED, candidate.flatMap(Candidate::refusal));
    }
    orders.put(key(trainName), new Order(candidate.get().routeUuid(), candidate.get().stopIndex()));
    return new Result(Outcome.ORDERED, Optional.empty());
  }

  /**
   * 撤销这列车的越站令：车还没开始为那一站刹车时才能撤。
   *
   * @param trainName 列车名
   */
  public Result revoke(String trainName) {
    Optional<Order> live = live(trainName);
    if (live.isEmpty()) {
      return new Result(Outcome.REFUSED, Optional.empty());
    }
    Order order = live.get();
    if (braking.started(trainName, order.routeUuid(), order.stopIndex())) {
      return new Result(Outcome.REFUSED, Optional.of(Refusal.BRAKING));
    }
    orders.remove(key(trainName), order);
    return new Result(Outcome.REVOKED, Optional.empty());
  }

  /**
   * 这列车在这条交路的这一站是否已令越过（各读点据此把这一站当通过）。
   *
   * @param trainName 列车名
   * @param routeUuid 交路
   * @param stopIndex 停站下标
   */
  public boolean skipped(String trainName, UUID routeUuid, int stopIndex) {
    return live(trainName).filter(new Order(routeUuid, stopIndex)::equals).isPresent();
  }

  /**
   * 这列车已令越过的那一站；只读，不做越站判断（控制台列车表每行都要问）。
   *
   * @param trainName 列车名
   * @return 没有有效的越站令时为空
   */
  public Optional<NodeId> orderedStation(String trainName) {
    if (routes == null) {
      return Optional.empty();
    }
    return live(trainName)
        .flatMap(
            order ->
                routes
                    .findById(order.routeUuid())
                    .map(RouteDefinition::waypoints)
                    .filter(nodes -> order.stopIndex() < nodes.size())
                    .map(nodes -> nodes.get(order.stopIndex())));
  }

  /**
   * 列车压上一块车站牌子时，这一站是不是已令越过。牌子上的交路进度标签多半还停在上一个节点（停车站的进度要到站后才推进）， 所以按节点从标签往后找出本站的下标再比，与到站登记同一认法。
   *
   * @param trainName 列车名
   * @param routeUuid 交路
   * @param tagIndex 列车标签上的交路进度
   * @param node 牌子的节点
   */
  public boolean skippedAt(String trainName, UUID routeUuid, OptionalInt tagIndex, NodeId node) {
    if (routes == null || routeUuid == null || node == null) {
      return false;
    }
    Optional<RouteDefinition> route = routes.findById(routeUuid);
    if (route.isEmpty()) {
      return false;
    }
    int index =
        RouteIndexResolver.resolveCurrentIndexWithDynamic(route.get(), routes, tagIndex, node);
    return index >= 0 && skipped(trainName, routeUuid, index);
  }

  /**
   * 这列车还有效的越站令：车已换了交路或开过了那一站（进度下标超过它）就清掉。各读点每拍都会问，过站后很快就被清掉。
   *
   * <p>查不到进度时只当没有令、不删：改名时进度先迁到新名、越站令随后才迁，站牌与站台屏可能在别的线程里按旧名来问。
   */
  private Optional<Order> live(String trainName) {
    String key = key(trainName);
    Order order = orders.get(key);
    if (order == null || progress == null) {
      return Optional.empty();
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entry = progress.get(trainName);
    if (entry.isEmpty()) {
      return Optional.empty();
    }
    boolean past =
        !entry.get().routeUuid().equals(order.routeUuid())
            || entry.get().currentIndex() > order.stopIndex();
    if (past) {
      orders.remove(key, order);
      return Optional.empty();
    }
    return Optional.of(order);
  }

  /** 有交路在这一站终到：短交路的终点、折返站一律不越（中途折返没有指令可认，折返多半就在这些站）。 */
  private boolean terminus(NodeId node) {
    if (routes == null) {
      return false;
    }
    Optional<String> key = stationKey(node);
    if (key.isEmpty()) {
      return false;
    }
    Set<String> known = termini;
    if (known == null) {
      known = collectTermini(routes.entries());
      termini = known;
    }
    return known.contains(key.get());
  }

  /** 各交路终到的车站：写了 {@code TERMINATE} 的站，加上每条交路最后一个停车的车站（回库交路就是它退出运营的那一站）。 */
  private static Set<String> collectTermini(
      java.util.Collection<RouteDefinitionCache.RouteEntry> entries) {
    Set<String> out = new java.util.HashSet<>();
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      String last = null;
      for (RouteStop stop : entry.stops()) {
        if (stop == null || !stop.stops() || stop.waypointNodeId().isEmpty()) {
          continue;
        }
        Optional<String> key = stationKey(NodeId.of(stop.waypointNodeId().get()));
        if (key.isEmpty()) {
          continue;
        }
        if (stop.passType() == RouteStopPassType.TERMINATE) {
          out.add(key.get());
        }
        last = key.get();
      }
      if (last != null) {
        out.add(last);
      }
    }
    return Set.copyOf(out);
  }

  /** 车站认法：运营商与站码（同站不同股道算同一站）；不是车站节点时为空。 */
  private static Optional<String> stationKey(NodeId node) {
    return RouteTerminals.stationIdentityOfNode(node.value())
        .map(
            ref ->
                (ref.operatorCode() + ":" + ref.stationCode()).toUpperCase(java.util.Locale.ROOT));
  }

  /** 交路在这一下标原路折返：前后两个节点是同一个。换向没有指令可认，只能从节点序列上看。 */
  private static boolean reversesAt(List<NodeId> nodes, int index) {
    return index > 0
        && index + 1 < nodes.size()
        && nodes.get(index - 1) != null
        && nodes.get(index - 1).equals(nodes.get(index + 1));
  }

  /** 车被删除：越站令一并作废。 */
  public void forget(String trainName) {
    orders.remove(key(trainName));
  }

  /** 车改名：越站令随车迁移。 */
  public void rename(String from, String to) {
    String fromKey = key(from);
    String toKey = key(to);
    if (fromKey.isEmpty() || toKey.isEmpty() || fromKey.equals(toKey)) {
      return;
    }
    Order order = orders.remove(fromKey);
    if (order != null) {
      orders.put(toKey, order);
    }
  }

  private static String key(String trainName) {
    return TrainNameNormalizer.normalizeKey(trainName);
  }

  /**
   * 车离停车点已经不比“舒适制动距离加余量”远：从这里起改成通过，车要么在刹车途中重新加速，要么停不准，越站与撤销都锁定。
   *
   * @param speedBps 当前速度（格/秒）
   * @param distanceBlocks 车头到停车点的距离（格）
   */
  static boolean pastBrakingPoint(double speedBps, double distanceBlocks) {
    double speed = Double.isFinite(speedBps) ? Math.max(0.0, speedBps) : 0.0;
    double braking = speed * speed / (2.0 * COMFORT_DECEL_BPS2);
    return distanceBlocks <= braking + Math.max(MIN_LOCK_MARGIN_BLOCKS, braking / 2.0);
  }

  /** 从 {@code from} 起往后还有没有停车（含终点）的站。 */
  private boolean stopsLater(RouteDefinition route, int from) {
    for (int i = from; i < route.waypoints().size(); i++) {
      Optional<RouteStop> stop = routes.findStop(route.id(), i);
      if (stop.isPresent() && stop.get().passType() != RouteStopPassType.PASS) {
        return true;
      }
    }
    return false;
  }
}
