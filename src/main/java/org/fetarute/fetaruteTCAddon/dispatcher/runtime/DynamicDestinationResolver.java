package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;

/**
 * DYNAMIC 目的地解析器。
 *
 * <p>该组件只负责把“当前 route 上即将到达的 DYNAMIC stop”解析为实际 NodeId。它可以读取 RailGraph 快照并调用
 * DynamicPlatformAllocator，但不写 TrainCarts destination，不申请占用，不控车，也不推进 routeIndex。
 */
final class DynamicDestinationResolver {

  private final DynamicPlatformAllocator allocator;
  private final RailGraphService railGraphService;
  private final Consumer<String> debugLogger;

  DynamicDestinationResolver(
      DynamicPlatformAllocator allocator,
      RailGraphService railGraphService,
      Consumer<String> debugLogger) {
    this.allocator = Objects.requireNonNull(allocator, "allocator");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /**
   * 解析信号 tick 中可 materialize 的 DYNAMIC effective node。
   *
   * @return DYNAMIC 三态结果；只有 {@code NOT_APPLICABLE} 可继续使用普通 route，{@code BLOCKED} 必须保持停车且禁止回退声明节点
   */
  DynamicResolution<ResolvedDynamicDestination> resolveSignalTickDestination(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      UUID worldId,
      NodeId currentNode,
      Optional<BlockFace> forwardDirection) {
    return resolveSignalTickDestination(
        trainName, route, currentIndex, worldId, currentNode, forwardDirection, Instant.now());
  }

  DynamicResolution<ResolvedDynamicDestination> resolveSignalTickDestination(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      UUID worldId,
      NodeId currentNode,
      Optional<BlockFace> forwardDirection,
      Instant now) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || worldId == null
        || currentNode == null) {
      return DynamicResolution.notApplicable("invalid-signal-tick-context");
    }
    Optional<RailGraph> graphOpt =
        railGraphService
            .getSnapshot(worldId)
            .map(
                snapshot -> {
                  RailGraph graph =
                      RailGraphService.runtimeGraph(railGraphService, worldId, snapshot.graph());
                  var overrides = railGraphService.edgeOverrides(worldId);
                  if (overrides.isEmpty()) {
                    return graph;
                  }
                  Instant snapshotTime = now != null ? now : Instant.now();
                  return new EdgeOverrideRailGraph(graph, overrides, snapshotTime);
                });
    if (graphOpt.isEmpty()) {
      return allocator.hasDynamicStopInAllocationWindow(route, currentIndex)
          ? DynamicResolution.blocked("graph-snapshot-missing")
          : DynamicResolution.notApplicable("graph-snapshot-missing");
    }
    DynamicResolution<DynamicPlatformAllocator.AllocationResult> allocation =
        allocator.resolveAllocation(
            trainName,
            route,
            currentIndex,
            graphOpt.get(),
            currentNode,
            forwardDirection == null ? Optional.empty() : forwardDirection);
    if (!allocation.isSelected()) {
      if (!allocation.isBlocked()) {
        // 还没进选台窗口：给下一个停车站现定或沿用暂定站台，站牌与选台偏好都读它。
        allocator.refreshTentative(
            trainName, route, currentIndex, graphOpt.get(), now != null ? now : Instant.now());
      }
      return allocation.isBlocked()
          ? DynamicResolution.blocked(allocation.reason(), allocation.blockedStopIndex())
          : DynamicResolution.notApplicable(allocation.reason());
    }
    DynamicPlatformAllocator.AllocationResult result = allocation.selected().orElseThrow();
    debugLogger.accept(
        "DYNAMIC effective node 解析: train="
            + trainName
            + ", node="
            + result.allocatedNode().value()
            + ", idx="
            + result.stopIndex());
    return DynamicResolution.selected(
        new ResolvedDynamicDestination(
            result.stopIndex(), result.allocatedNode(), "dynamic-allocator"));
  }

  /** DYNAMIC 选择结果。 */
  record ResolvedDynamicDestination(int stopIndex, NodeId node, String reason) {
    ResolvedDynamicDestination {
      Objects.requireNonNull(node, "node");
      reason = reason == null || reason.isBlank() ? "dynamic-allocator" : reason.trim();
    }
  }
}
