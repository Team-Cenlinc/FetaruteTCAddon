package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;

/**
 * 车站停靠的两件外围事：把停靠事实播给观察者，以及按发车计划决定要不要扣住早到的车。
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
  private volatile Duration maxHold = Duration.ZERO;

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
    ScheduledDeparturePlan current = this.plan;
    Duration cap = this.maxHold;
    if (current == null || cap.isZero() || route == null || nodeId == null || now == null) {
      return false;
    }
    Optional<Instant> scheduled;
    try {
      scheduled =
          current.scheduledDepartureAt(
              event(trainName, route, routeUuid, currentIndex, nodeId, now));
    } catch (RuntimeException ex) {
      // 计划源自己出错时绝不能把车留在站里：吞掉异常、记一条审计、按现状放行。
      debugLogger.accept("SCHEDULED_DEPARTURE_PLAN_FAILED train=" + trainName + " error=" + ex);
      return false;
    }
    if (scheduled == null || scheduled.isEmpty()) {
      return false;
    }
    Instant target = scheduled.get();
    if (!now.isBefore(target)) {
      // 已到点或已晚点：立刻放行。时刻表不负责让晚点的车更晚。
      return false;
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
      return false;
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
    return true;
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
    if (group == null || definition == null || observer == null) {
      return;
    }
    TrainProperties properties = group.getProperties();
    if (properties == null || managedTrains == null || !managedTrains.test(properties)) {
      return;
    }
    String trainName = trainNames.apply(properties).orElse(null);
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    Optional<RouteDefinition> routeOpt = routes.apply(properties);
    if (routeOpt.isEmpty()) {
      return;
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
      return;
    }
    notifyStop(
        false,
        trainName,
        route,
        routeUuids.apply(properties),
        currentIndex,
        definition.nodeId(),
        clock.get());
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

  /** 播报某辆车已离开运行时管辖。 */
  public void notifyReleased(String trainName, String reason) {
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
