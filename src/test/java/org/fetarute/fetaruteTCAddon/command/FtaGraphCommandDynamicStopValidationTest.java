package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.junit.jupiter.api.Test;

/**
 * 建图后的路线校验必须认得 DYNAMIC 停靠点。
 *
 * <p>DYNAMIC 停靠点**不解析到单一节点**——运行时才在若干站台里选一个——所以 {@code RouteStopResolver.resolveNodeId} 对它返回空。此前
 * {@code FtaGraphCommand} 的建图后校验 直接把"解析不出节点"报成 {@code node-missing}，于是每跑一次 {@code /fta graph
 * build}， 凡是带 DYNAMIC 的路线都会刷出一串"第 N 个停靠点缺少节点"——**全是误报**。
 *
 * <p>而 route 包里本就有共享的 {@link DynamicStopMatcher}，{@code FtaRouteCommand} 定义路线时 也确实做了 DYNAMIC
 * 校验并有专用文案（{@code dynamic-no-valid-tracks} / {@code dynamic-partial-tracks}）；只有建图后这条路径两者都没用上。
 * **同一件事两处各写各的，正是它们分叉的原因。**
 */
final class FtaGraphCommandDynamicStopValidationTest {

  private static final java.util.UUID ROUTE_ID = java.util.UUID.randomUUID();

  /** 先钉住解析本身：真实形状的 DYNAMIC 停靠点必须被认出来。 */
  @Test
  void dynamicDirectiveInNotesIsRecognised() {
    assertTrue(
        DynamicStopMatcher.isDynamicStop(dynamicStop(1, "DYNAMIC:SURC:S:PPK")),
        "DYNAMIC 指令必须被识别，否则校验只会把它当成缺节点");
    assertFalse(
        DynamicStopMatcher.isDynamicStop(
            new RouteStop(
                ROUTE_ID,
                2,
                Optional.empty(),
                Optional.of("SURC:S:PPK:1"),
                Optional.empty(),
                RouteStopPassType.STOP,
                Optional.empty())),
        "普通停靠点不得被误判成 DYNAMIC");
  }

  /**
   * 判别核心：图里**有**匹配站台时必须找得到候选（⇒ 不报缺节点）， 图里**没有**时必须返回空（⇒ 才该报 DYNAMIC 专用问题）。
   *
   * <p>两者必须相反——只要这一条成立，这次修复就确实在区分它该区分的东西。 旧实现里这条查询**根本不存在**，DYNAMIC 一律落进 node-missing。
   */
  @Test
  void dynamicCandidatesAreFoundOnlyWhenMatchingPlatformsExistInGraph() throws Exception {
    RouteStop stop = dynamicStop(1, "DYNAMIC:SURC:S:PPK");

    List<NodeId> withPlatforms =
        dynamicGraphCandidates(graphWith("SURC:S:PPK:1", "SURC:S:PPK:2", "SURC:S:OTHER:1"), stop);
    assertEquals(
        Set.of(NodeId.of("SURC:S:PPK:1"), NodeId.of("SURC:S:PPK:2")),
        Set.copyOf(withPlatforms),
        "图里存在的站台必须全部成为候选，且不得把别的站点算进来");

    List<NodeId> withoutPlatforms = dynamicGraphCandidates(graphWith("SURC:S:OTHER:1"), stop);
    assertTrue(withoutPlatforms.isEmpty(), "没有任何匹配站台时才该判为问题");

    assertFalse(
        withPlatforms.isEmpty() == withoutPlatforms.isEmpty(), "有站台与没站台必须得到相反的结果，否则这条查询什么都没区分");
  }

  private static RouteStop dynamicStop(int sequence, String directive) {
    return new RouteStop(
        ROUTE_ID,
        sequence,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.of(directive));
  }

  private static RailGraph graphWith(String... nodeIds) {
    java.util.Map<NodeId, RailNode> nodes = new java.util.LinkedHashMap<>();
    for (String id : nodeIds) {
      NodeId nodeId = NodeId.of(id);
      nodes.put(
          nodeId,
          new SignRailNode(
              nodeId, NodeType.STATION, new Vector(0, 64, 0), Optional.empty(), Optional.empty()));
    }
    return new SimpleRailGraph(nodes, Map.<EdgeId, RailEdge>of(), Set.of());
  }

  @SuppressWarnings("unchecked")
  private static List<NodeId> dynamicGraphCandidates(RailGraph graph, RouteStop stop)
      throws Exception {
    Method method =
        FtaGraphCommand.class.getDeclaredMethod(
            "dynamicGraphCandidates", RailGraph.class, RouteStop.class);
    method.setAccessible(true);
    return (List<NodeId>) method.invoke(null, graph, stop);
  }
}
