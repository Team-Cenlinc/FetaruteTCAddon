package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/**
 * 入库走行证明：{@code *D} 路线在 TERMINATE 之后那段全 PASS 的进段场走行。
 *
 * <p>实服 MT-2O_ShortD 的尾部是 {@code 12 TERMINATE S:HHU:4 → 13 PASS D:HHU:1:001 → 14 PASS D:HHU:1}。
 * 原判据只认「当前索引之后存在非 PASS RouteStop」，这一段一个都没有，于是 {@code plannedRouteStopProven} 为假、{@code
 * shouldHardStop} 为真，车被 {@code inside-stop-distance} 停在离段场一个节点处。 第十五轮 3 辆车中招，最长 172 秒，而它们 {@code
 * holds=[] blockedBy=[]}。
 *
 * <p>本用例钉住的判别核心是**两个方向**：段场收尾要认，而任何一个条件不满足都必须退回原 fail-closed 分支。 只断言"能放行"证明不了后一半，而后一半才是这条
 * fail-closed 存在的理由。
 */
class DepotRunInProofTest {

  private static final NodeId PLATFORM = NodeId.of("SURC:S:HHU:4");
  private static final NodeId APPROACH = NodeId.of("SURC:D:HHU:1:001");
  private static final NodeId DEPOT = NodeId.of("SURC:D:HHU:1");

  @Test
  void allPassTailEndingAtDepotIsProven() {
    RuntimeDispatchService service = service(allPassTail());
    assertTrue(
        service.hasProvenDepotRunIn(route(), 12, graphWith(DEPOT, NodeType.DEPOT)),
        "TERMINATE 之后的全 PASS 入库走行应当被证明");
  }

  @Test
  void tailEndingAtNonDepotIsNotProven() {
    // 末端不是段场 ⇒ 仍是"裸 route 终点"，正是原分支要拦的东西。
    RuntimeDispatchService service = service(allPassTail());
    assertFalse(
        service.hasProvenDepotRunIn(route(), 12, graphWith(DEPOT, NodeType.WAYPOINT)),
        "末端不是 DEPOT 时不得放行");
  }

  @Test
  void tailContainingAnotherPlannedStopIsNotProven() {
    // 剩余段里还有非 PASS 停靠 ⇒ 是普通计划停靠场景，该走原来的 RouteStop 证明，不是这条。
    List<RouteStop> stops =
        List.of(stop(13, RouteStopPassType.STOP), stop(14, RouteStopPassType.PASS));
    RuntimeDispatchService service = service(stops);
    assertFalse(
        service.hasProvenDepotRunIn(route(), 12, graphWith(DEPOT, NodeType.DEPOT)),
        "剩余段还有计划停靠时不得走入库证明");
  }

  @Test
  void terminusMissingFromGraphIsNotProven() {
    // 图里查不到末端 ⇒ 无从判断 ⇒ 不得当成段场。缺证据不是证据。
    RuntimeDispatchService service = service(allPassTail());
    assertFalse(
        service.hasProvenDepotRunIn(route(), 12, graphWith(NodeId.of("OTHER"), NodeType.DEPOT)),
        "图里没有该节点时不得放行");
  }

  @Test
  void alreadyAtTerminusIsNotProven() {
    RuntimeDispatchService service = service(allPassTail());
    RailGraph graph = graphWith(DEPOT, NodeType.DEPOT);
    assertFalse(service.hasProvenDepotRunIn(route(), 14, graph), "已在末端就没有'剩余走行'可言");
    assertFalse(service.hasProvenDepotRunIn(route(), 99, graph), "越界索引不得放行");
    assertFalse(service.hasProvenDepotRunIn(route(), -1, graph), "负索引不得放行");
  }

  @Test
  void nullInputsAreNotProven() {
    RuntimeDispatchService service = service(allPassTail());
    assertFalse(service.hasProvenDepotRunIn(null, 12, graphWith(DEPOT, NodeType.DEPOT)));
    assertFalse(service.hasProvenDepotRunIn(route(), 12, null));
  }

  // ---------- 脚手架 ----------

  private static final UUID ROUTE_UUID = UUID.randomUUID();
  private static final RouteId ROUTE_ID = RouteId.of(ROUTE_UUID.toString());

  /**
   * 复刻实服 MT-2O_ShortD 的索引布局：waypoint 下标与 RouteStop sequence 是**同一套编号**，
   * 12=站台(TERMINATE)、13=入库进路、14=段场。用短列表会让 currentIndex 与末端下标错位， 那样测的就不是被测逻辑了。
   */
  private static RouteDefinition route() {
    List<NodeId> waypoints = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      waypoints.add(NodeId.of("SURC:W:FILLER:" + i));
    }
    waypoints.add(PLATFORM); // 12
    waypoints.add(APPROACH); // 13
    waypoints.add(DEPOT); // 14
    return new RouteDefinition(ROUTE_ID, List.copyOf(waypoints), Optional.empty());
  }

  private static List<RouteStop> allPassTail() {
    return List.of(stop(13, RouteStopPassType.PASS), stop(14, RouteStopPassType.PASS));
  }

  private static RouteStop stop(int sequence, RouteStopPassType passType) {
    return new RouteStop(
        ROUTE_UUID,
        sequence,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        passType,
        Optional.empty());
  }

  private static RuntimeDispatchService service(List<RouteStop> stops) {
    RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
    when(cache.listStops(ROUTE_ID)).thenReturn(stops);
    return TestServices.minimal(new ArrayList<>(), cache);
  }

  private static RailGraph graphWith(NodeId terminusId, NodeType terminusType) {
    EdgeId edgeId = EdgeId.undirected(APPROACH, terminusId);
    RailEdge edge = new RailEdge(edgeId, APPROACH, terminusId, 10, 0.0, true, Optional.empty());
    return new SimpleRailGraph(
        Map.of(
            APPROACH,
            node(APPROACH, NodeType.WAYPOINT),
            terminusId,
            node(terminusId, terminusType)),
        Map.of(edgeId, edge),
        Set.of());
  }

  private static RailNode node(NodeId id, NodeType type) {
    return new RailNode() {
      @Override
      public NodeId id() {
        return id;
      }

      @Override
      public NodeType type() {
        return type;
      }

      @Override
      public Vector worldPosition() {
        return new Vector(0, 64, 0);
      }

      @Override
      public Optional<String> trainCartsDestination() {
        return Optional.empty();
      }
    };
  }
}
