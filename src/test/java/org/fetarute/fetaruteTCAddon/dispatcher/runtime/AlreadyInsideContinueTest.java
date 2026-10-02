package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;

/**
 * 已在单线区内、出口已找到但落在硬授权窗口之外时，必须放行续行。
 *
 * <p>成因：{@code evaluateSmartSingleCorridorAdmission} 的"已在区内"分支 （{@code
 * hasClaimByTrain}）先做了对向屏障检查——真正的安全性质在那里守住—— 然后又因为"出口不在硬授权窗口内"把车拒掉，于是把它锁死在区内。
 *
 * <p>实服代价（同一形态、三轮、三辆不同的车）：
 *
 * <ul>
 *   <li>第十二轮 {@code SURC-WS-LC-7203} 整轮 74 分钟、到站 0 次
 *   <li>第十六轮 {@code SURC-WS-LC-6212} 449 秒
 *   <li>第十七轮 {@code SURC-WS-LC-6650} <b>3260 秒（54 分钟）</b>， {@code blockedBy=[]} 没有任何车挡它，整条 WS
 *       线在它之后到站归零
 * </ul>
 *
 * <p>为什么不是"把窗口延到出口"：{@code buildHardAuthorityContext} 的契约明写方向性 {@code single:*}
 * <b>不能扩张为整段单线独占</b>，那会毁掉长单线区的通过能力。 单线区本来就靠"持方向锁 + 局部窗口逐段推进"通过。
 */
class AlreadyInsideContinueTest {

  /**
   * 判别核心：**"出口在窗口外"与"证不出有出口"必须得到相反的结果**。
   *
   * <p>只断言前者放行是不够的——那证明不了 fail-closed 那一半还在，而那一半才是这道门存在的理由。
   */
  @Test
  void exitOutsideWindowIsAllowedButUnprovenExitStillFailsClosed() {
    // 出口找到了，只是在硬窗口之外 —— 放行。
    assertTrue(
        RuntimeDispatchService.exitFoundOutsideHardAuthority(
            result("exit-visible-outside-hard-authority", 4)),
        "出口已找到、仅在窗口外，必须放行");
    assertTrue(
        RuntimeDispatchService.exitFoundOutsideHardAuthority(
            result("target-boundary-outside-hard-authority", 6)),
        "目标边界已找到、仅在窗口外，同样放行");

    // 扩展之后仍然找不到出口 —— 这是真的证不出，必须继续 fail-closed。
    assertFalse(
        RuntimeDispatchService.exitFoundOutsideHardAuthority(
            result("exit-not-visible-after-extension", -1)),
        "证不出路径会离开该区时不得放行");
    assertFalse(
        RuntimeDispatchService.exitFoundOutsideHardAuthority(result("missing-plan", -1)),
        "缺计划时不得放行");
  }

  /**
   * 原因字符串与出口下标必须**同时**成立。
   *
   * <p>只信其中一个，将来任何一边的语义被悄悄改掉都会变成静默放行——而这道门放宽错了， 后果是把车放进它证明不了能离开的单线区。
   */
  @Test
  void reasonStringAloneIsNotEnough() {
    assertFalse(
        RuntimeDispatchService.exitFoundOutsideHardAuthority(
            result("exit-visible-outside-hard-authority", -1)),
        "原因说在窗口外、却没带回出口下标 —— 不得放行");
  }

  @Test
  void nullResultFailsClosed() {
    assertFalse(RuntimeDispatchService.exitFoundOutsideHardAuthority(null));
  }

  // ---------- 接线 ----------

  /**
   * 判据对了不等于接对了。
   *
   * <p>上面三条只钓住了纯函数；若那个 {@code if} 的条件写反、或放错分支（例如加在“正在进入” 而非“已在区内”那一支），它们仍然全绿。本用例从 {@code
   * smartDepotAdmissionAllowsSpawn} 打进去， 把两个方向一起钓住：出口在窗口外→放行，出口真的不存在→仍然 fail-closed。
   *
   * <p>两个场景只差一件事：最后一条边在不在单线区内。其余（自持方向锁、无外部屏障、 硬授权窗口只盖一条边）完全相同——所以结果的差异只能来自这条分支。
   */
  @Test
  void admissionAllowsContinueWhenExitIsOnlyOutsideTheWindow() {
    assertTrue(admissionAllows(/* lastEdgeLeavesRegion= */ true), "已在区内、出口只是落在硬授权窗口外 —— 必须放行续行");
    assertFalse(
        admissionAllows(/* lastEdgeLeavesRegion= */ false), "扩展之后仍然证不出出口 —— 必须继续 fail-closed");
  }

  /** 跑一次完整的单线区准入；唯一变量是最后一条边会不会离开单线区。 */
  private static boolean admissionAllows(boolean lastEdgeLeavesRegion) {
    NodeId a = NodeId.of("OP:W:ALFA:1");
    NodeId b = NodeId.of("OP:W:BRAVO:1");
    NodeId c = NodeId.of("OP:W:CHARLIE:1");
    NodeId d = NodeId.of("OP:W:DELTA:1");
    String zoneKey = "single:test:ALFA~DELTA";
    OccupancyResource conflict = OccupancyResource.forConflict(zoneKey);
    RailEdge ab = edge(a, b);
    RailEdge bc = edge(b, c);
    RailEdge cd = edge(c, d);
    Map<EdgeId, String> conflicts = new LinkedHashMap<>();
    conflicts.put(ab.id(), zoneKey);
    conflicts.put(bc.id(), zoneKey);
    if (!lastEdgeLeavesRegion) {
      conflicts.put(cd.id(), zoneKey);
    }
    SingleRegionGraph graph =
        new SingleRegionGraph(List.of(a, b, c, d), List.of(ab, bc, cd), conflicts);

    String train = "SURC-WS-LC-6650";
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    // 已在区内：车自己持着这个单线区的方向锁。没有任何其他车 —— 外部屏障为空。
    OccupancyRequestContext context =
        singleRegionContext(train, conflict, List.of(a, b, c, d), List.of(ab, bc, cd));
    assertTrue(manager.acquire(context.request()).allowed(), "前置：车应当先拿到单线区");

    RuntimeDispatchService service =
        TestServices.minimal(
            new ArrayList<>(),
            manager,
            SmartDispatcherMode.ENFORCE,
            () -> Instant.parse("2026-01-01T00:00:02Z"));
    // 硬授权窗口只盖到第一条边，而行车计划有三条 —— 这正是“出口在窗口外”的成因。
    OccupancyRequestContext windowed =
        new OccupancyRequestContext(context.request(), List.of(a, b), List.of(ab));
    return service.smartDepotAdmissionAllowsSpawn(train, graph, windowed);
  }

  private static RailEdge edge(NodeId from, NodeId to) {
    return new RailEdge(EdgeId.undirected(from, to), from, to, 10, -1.0, true, Optional.empty());
  }

  private static OccupancyRequestContext singleRegionContext(
      String trainName, OccupancyResource conflict, List<NodeId> nodes, List<RailEdge> edges) {
    List<DirectedTraversalContext.DirectedEdge> directedEdges = new ArrayList<>();
    for (int i = 0; i < edges.size(); i++) {
      directedEdges.add(
          new DirectedTraversalContext.DirectedEdge(
              edges.get(i).id(), nodes.get(i), nodes.get(i + 1)));
    }
    DirectedTraversalContext directedContext =
        new DirectedTraversalContext(
            trainName,
            Optional.empty(),
            0,
            Optional.of(nodes.get(0)),
            Optional.of(nodes.get(0)),
            Optional.of(nodes.get(0)),
            Optional.of(nodes.get(1)),
            nodes,
            directedEdges,
            Map.of(conflict.key(), CorridorDirection.A_TO_B),
            Map.of(),
            AuthorizationPurpose.RUNTIME_MOVE.name(),
            0L,
            0L,
            "test-already-inside",
            Optional.empty());
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:01Z"),
            List.of(conflict),
            Map.of(conflict.key(), CorridorDirection.A_TO_B),
            Map.of(conflict.key(), 0),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(),
            Optional.of(directedContext));
    return new OccupancyRequestContext(request, nodes, edges);
  }

  /** 一条链形单线区；哪几条边属于该区由用例指定。 */
  private static final class SingleRegionGraph implements RailGraph, RailGraphConflictSupport {
    private final Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    private final Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    private final Map<EdgeId, String> conflicts;

    private SingleRegionGraph(
        List<NodeId> nodeIds, List<RailEdge> railEdges, Map<EdgeId, String> conflicts) {
      for (NodeId id : nodeIds) {
        this.nodes.put(id, waypoint(id));
      }
      for (RailEdge edge : railEdges) {
        this.edges.put(edge.id(), edge);
      }
      this.conflicts = Map.copyOf(conflicts);
    }

    @Override
    public Collection<RailNode> nodes() {
      return nodes.values();
    }

    @Override
    public Collection<RailEdge> edges() {
      return edges.values();
    }

    @Override
    public Optional<RailNode> findNode(NodeId id) {
      return Optional.ofNullable(nodes.get(id));
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      Set<RailEdge> result = new LinkedHashSet<>();
      for (RailEdge edge : edges.values()) {
        if (edge.from().equals(id) || edge.to().equals(id)) {
          result.add(edge);
        }
      }
      return result;
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return false;
    }

    @Override
    public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
      return Optional.ofNullable(conflicts.get(edgeId));
    }
  }

  private static RailNode waypoint(NodeId id) {
    return new RailNode() {
      @Override
      public NodeId id() {
        return id;
      }

      @Override
      public NodeType type() {
        return NodeType.WAYPOINT;
      }

      @Override
      public org.bukkit.util.Vector worldPosition() {
        return new org.bukkit.util.Vector(0, 64, 0);
      }

      @Override
      public Optional<String> trainCartsDestination() {
        return Optional.empty();
      }
    };
  }

  private static EntryLookaheadEvaluator.Result result(String reason, int exitAfter) {
    return new EntryLookaheadEvaluator.Result(
        true, 8, "single:zone", 0, -1, true, exitAfter, false, reason);
  }
}
