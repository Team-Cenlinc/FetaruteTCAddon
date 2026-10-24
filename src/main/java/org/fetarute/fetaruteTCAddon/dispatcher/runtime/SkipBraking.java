package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnostics;

/** 越站的刹车锁定（{@link StopSkips.Braking} 的调度侧实现）：车已过为那一站刹车的起点时锁定；取不到运动时按车在不在动判（失效关闭）。只在主线程调用。 */
final class SkipBraking implements StopSkips.Braking {

  /**
   * 列车此刻的运动，取自调度控车诊断。
   *
   * @param speedBps 当前速度（格/秒）
   * @param currentNode 车所在（最后经过）的图节点
   * @param stopNode 调度此刻的停车点
   * @param distanceToStop 车头到停车点的距离（格）
   */
  record Motion(
      double speedBps,
      NodeId currentNode,
      Optional<NodeId> stopNode,
      OptionalDouble distanceToStop) {
    Motion {
      Objects.requireNonNull(currentNode, "currentNode");
      stopNode = stopNode == null ? Optional.empty() : stopNode;
      distanceToStop = distanceToStop == null ? OptionalDouble.empty() : distanceToStop;
    }
  }

  /** 一张图上两点之间的最短路程。 */
  @FunctionalInterface
  interface GraphDistance {
    OptionalLong of(RailGraph graph, NodeId from, NodeId to);
  }

  /** 从控车诊断取列车的运动。 */
  static Function<String, Optional<Motion>> motions(
      Function<String, Optional<ControlDiagnostics>> diagnostics) {
    return name ->
        diagnostics
            .apply(name)
            .filter(found -> found.currentNode() != null)
            .map(
                found ->
                    new Motion(
                        found.currentSpeedBps(),
                        found.currentNode(),
                        Optional.ofNullable(found.stopNode()),
                        found.distanceToStopNode()));
  }

  /** 列车在不在动（按 TrainCarts 列车名找编组）；找不到车时当作在动，状态不明按已锁定算。 */
  static Predicate<String> movingTrains() {
    return name -> {
      if (name == null
          || !com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore.exists(name)) {
        return true;
      }
      com.bergerkiller.bukkit.tc.properties.TrainProperties properties =
          com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore.get(name);
      com.bergerkiller.bukkit.tc.controller.MinecartGroup group =
          properties == null ? null : properties.getHolder();
      return group == null || group.isMoving();
    };
  }

  /** 两个图节点之间的路程：取同时含有两点的那张调度图。 */
  static BiFunction<NodeId, NodeId, OptionalLong> distances(
      RailGraphService graphs, GraphDistance distance) {
    return (from, to) ->
        graphs.snapshotAll().values().stream()
            .map(RailGraphService.RailGraphSnapshot::graph)
            .filter(graph -> graph.findNode(from).isPresent() && graph.findNode(to).isPresent())
            .findFirst()
            .map(graph -> distance.of(graph, from, to))
            .orElse(OptionalLong.empty());
  }

  private final Function<String, Optional<Motion>> motions;
  private final Function<UUID, Optional<RouteDefinition>> routes;
  private final BiFunction<NodeId, NodeId, OptionalLong> distance;
  private final Predicate<String> moving;

  /**
   * @param motions 列车此刻的运动；没有诊断时为空
   * @param routes 交路
   * @param distance 两个图节点之间的最短路程（格）；走不到时为空
   * @param moving 取不到运动时，车在不在动（状态不明时应答在动）
   */
  SkipBraking(
      Function<String, Optional<Motion>> motions,
      Function<UUID, Optional<RouteDefinition>> routes,
      BiFunction<NodeId, NodeId, OptionalLong> distance,
      Predicate<String> moving) {
    this.motions = Objects.requireNonNull(motions, "motions");
    this.routes = Objects.requireNonNull(routes, "routes");
    this.distance = Objects.requireNonNull(distance, "distance");
    this.moving = Objects.requireNonNull(moving, "moving");
  }

  @Override
  public boolean started(String trainName, UUID routeUuid, int stopIndex) {
    Optional<Motion> motion = motions.apply(trainName);
    Optional<NodeId> station =
        routes
            .apply(routeUuid)
            .filter(route -> stopIndex >= 0 && stopIndex < route.waypoints().size())
            .map(route -> route.waypoints().get(stopIndex));
    if (station.isEmpty()) {
      return false;
    }
    if (motion.isEmpty()) {
      // 控车诊断过期（卡顿、硬停分支没记）：停着的车不可能在为前方的站刹车，在动或不明的按已锁定算
      return moving.test(trainName);
    }
    Motion now = motion.get();
    if (sameStation(now.stopNode(), station.get()) && now.distanceToStop().isPresent()) {
      return StopSkips.pastBrakingPoint(now.speedBps(), now.distanceToStop().getAsDouble());
    }
    // 停车点已不是这一站（已令越过，调度改以后面的站为停车点）：按车到这一站的路程算
    OptionalDouble toStation = distanceToStation(now, station.get());
    return toStation.isPresent()
        && StopSkips.pastBrakingPoint(now.speedBps(), toStation.getAsDouble());
  }

  /** 两个图节点之间的路程误差容许（格）：站在通往停车点的最短路上时，两段之和与全程应当相等。 */
  private static final long ON_PATH_TOLERANCE = 2L;

  /**
   * 车头到这一站的路程，取能算的里面最小的（偏小只会锁得早）：
   *
   * <ul>
   *   <li>最后经过的图节点到这一站：多算了车头在当前这条边上已走过的路；
   *   <li>这一站在通往停车点的路上时，到停车点的距离减去站到停车点的路程：用的是控车量出的精确距离。
   * </ul>
   */
  private OptionalDouble distanceToStation(Motion now, NodeId station) {
    OptionalLong fromNode = distance.apply(now.currentNode(), station);
    double best = fromNode.isPresent() ? fromNode.getAsLong() : Double.NaN;
    if (fromNode.isPresent() && now.stopNode().isPresent() && now.distanceToStop().isPresent()) {
      OptionalLong stationToStop = distance.apply(station, now.stopNode().get());
      OptionalLong nodeToStop = distance.apply(now.currentNode(), now.stopNode().get());
      boolean onPath =
          stationToStop.isPresent()
              && nodeToStop.isPresent()
              && Math.abs(
                      nodeToStop.getAsLong() - (fromNode.getAsLong() + stationToStop.getAsLong()))
                  <= ON_PATH_TOLERANCE;
      if (onPath) {
        double viaStop = now.distanceToStop().getAsDouble() - stationToStop.getAsLong();
        best = Math.min(best, Math.max(0.0, viaStop));
      }
    }
    return Double.isNaN(best) ? OptionalDouble.empty() : OptionalDouble.of(best);
  }

  /** 调度的停车点就是这一站（{@code DYNAMIC:} 停站按站码认，停车点是选定的股道）。 */
  private static boolean sameStation(Optional<NodeId> stopNode, NodeId station) {
    if (stopNode.isEmpty()) {
      return false;
    }
    if (stopNode.get().equals(station)) {
      return true;
    }
    Optional<String> code = RouteTerminals.stationCodeOf(station.value());
    return code.isPresent() && code.equals(RouteTerminals.stationCodeOf(stopNode.get().value()));
  }
}
