package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
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
   * @return 已选择的 DYNAMIC effective node；empty 表示当前无需或无法 materialize
   */
  Optional<ResolvedDynamicDestination> resolveSignalTickDestination(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      UUID worldId,
      NodeId currentNode,
      Optional<BlockFace> forwardDirection) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || worldId == null
        || currentNode == null) {
      return Optional.empty();
    }
    Optional<RailGraph> graphOpt =
        railGraphService.getSnapshot(worldId).map(snapshot -> snapshot.graph());
    if (graphOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<DynamicPlatformAllocator.AllocationResult> resultOpt =
        allocator.tryAllocate(
            trainName,
            route,
            currentIndex,
            graphOpt.get(),
            currentNode,
            forwardDirection == null ? Optional.empty() : forwardDirection);
    if (resultOpt.isEmpty()) {
      return Optional.empty();
    }
    DynamicPlatformAllocator.AllocationResult result = resultOpt.get();
    debugLogger.accept(
        "DYNAMIC effective node 解析: train="
            + trainName
            + ", node="
            + result.allocatedNode().value()
            + ", idx="
            + result.stopIndex());
    return Optional.of(
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
