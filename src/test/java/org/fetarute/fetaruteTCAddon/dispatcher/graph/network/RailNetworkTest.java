package org.fetarute.fetaruteTCAddon.dispatcher.graph.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLink;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("跨世界路网")
class RailNetworkTest {

  private static final UUID OVER = UUID.randomUUID();
  private static final UUID NETHER = UUID.randomUUID();
  private static final NodeId A = NodeId.of("OP:S:AAA:1");
  private static final NodeId PA = NodeId.of("PORTAL:over:10:64:0");
  private static final NodeId PB = NodeId.of("PORTAL:nether:1:64:0");
  private static final NodeId B = NodeId.of("OP:S:BBB:1");

  private static RailNode node(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(NodeId a, NodeId b, int length) {
    return new RailEdge(EdgeId.undirected(a, b), a, b, length, -1.0, true, Optional.empty());
  }

  private static SimpleRailGraph world(RailNode first, RailNode second, int length) {
    return new SimpleRailGraph(
        Map.of(first.id(), first, second.id(), second),
        Map.of(EdgeId.undirected(first.id(), second.id()), edge(first.id(), second.id(), length)),
        Set.of());
  }

  private static final SimpleRailGraph OVERWORLD =
      world(node(A, NodeType.STATION, 0), node(PA, NodeType.PORTAL, 10), 10);
  private static final SimpleRailGraph NETHERWORLD =
      world(node(PB, NodeType.PORTAL, 1), node(B, NodeType.STATION, 30), 29);

  private static PortalLink link(NodeId from, NodeId to, UUID fromWorld, UUID toWorld) {
    return new PortalLink(
        fromWorld, from, toWorld, to, PortalLink.Source.MANUAL, 4.0, Instant.EPOCH);
  }

  @Test
  @DisplayName("拼接后能跨世界找路，连接边有独立冲突键、不受封锁")
  void pathAcrossPortal() {
    RailNetwork network =
        RailNetwork.build(
            Map.of(OVER, OVERWORLD, NETHER, NETHERWORLD),
            List.of(link(PA, PB, OVER, NETHER), link(PB, PA, NETHER, OVER)));
    assertTrue(network.hasPortalEdges());
    assertTrue(network.connected(A, B));
    assertEquals(Optional.of(NETHER), network.worldOf(B));

    RailGraph view = network.view(OVER);
    assertEquals(4, view.nodes().size());
    assertEquals(3, view.edges().size(), "两个方向的连接合成一条边");
    EdgeId portal = EdgeId.undirected(PA, PB);
    assertFalse(view.isBlocked(portal));
    assertTrue(
        ((RailGraphConflictSupport) view)
            .conflictKeyForEdge(portal)
            .orElseThrow()
            .startsWith(RailNetwork.PORTAL_CONFLICT_PREFIX));
    var path =
        new RailGraphPathFinder()
            .shortestPath(view, A, B, RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();
    assertEquals(List.of(A, PA, PB, B), path.nodes());
    assertEquals(
        10 + RailNetwork.PORTAL_EDGE_BLOCKS + 29,
        path.totalLengthBlocks(),
        "过门按 1 格计，门后被占时停车点落在门前");
  }

  @Test
  @DisplayName("同 ID 节点优先取视图所在世界的；没有传送门的世界不换成路网")
  void ownWorldFirstAndPortalWorlds() {
    UUID third = UUID.randomUUID();
    SimpleRailGraph thirdWorld =
        world(
            node(A, NodeType.STATION, 500),
            node(NodeId.of("OP:S:CCC:1"), NodeType.STATION, 520),
            20);
    RailNetwork network =
        RailNetwork.build(
            Map.of(OVER, OVERWORLD, NETHER, NETHERWORLD, third, thirdWorld),
            List.of(link(PA, PB, OVER, NETHER)));
    assertEquals(500.0, network.view(third).findNode(A).orElseThrow().worldPosition().getX(), 1e-9);
    assertTrue(network.hasPortalIn(OVER));
    assertFalse(network.hasPortalIn(third));
    assertSame(network.view(OVER), network.view(OVER), "同一个世界总是同一个视图（最短路记忆按视图）");
  }

  @Test
  @DisplayName("两端不在已加载的图里、或在同一个世界的连接不成边")
  void invalidLinksIgnored() {
    RailNetwork network =
        RailNetwork.build(
            Map.of(OVER, OVERWORLD), List.of(link(PA, PB, OVER, NETHER), link(A, PA, OVER, OVER)));
    assertFalse(network.hasPortalEdges());
    assertFalse(network.connected(A, B));
  }

  @Test
  @DisplayName("图服务：未开启时运行时图就是本世界的图；开启后可跨世界找路")
  void serviceIntegration() {
    RailGraphService service = new RailGraphService(world -> OVERWORLD);
    World over = mock(World.class);
    when(over.getUID()).thenReturn(OVER);
    when(over.getName()).thenReturn("over");
    World nether = mock(World.class);
    when(nether.getUID()).thenReturn(NETHER);
    when(nether.getName()).thenReturn("nether");
    service.putSnapshot(over, OVERWORLD, Instant.now());
    service.putSnapshot(nether, NETHERWORLD, Instant.now());
    PortalLinkRegistry links = new PortalLinkRegistry();
    links.put(link(PA, PB, OVER, NETHER));

    service.configureCrossWorld(false, links);
    assertSame(OVERWORLD, RailGraphService.runtimeGraph(service, OVER, OVERWORLD));
    assertTrue(service.findNetworkWorldForPath(List.of(A, B)).isEmpty());

    service.configureCrossWorld(true, links);
    assertEquals(Optional.of(OVER), service.findNetworkWorldForPath(List.of(A, B)));
    RailGraph runtime = RailGraphService.runtimeGraph(service, OVER, OVERWORLD);
    assertTrue(runtime.findNode(B).isPresent());
    assertSame(
        OVERWORLD.interlockingState(),
        ((org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport) runtime)
            .interlockingState(),
        "物理联锁仍是本世界的");

    links.remove(PA);
    assertSame(OVERWORLD, RailGraphService.runtimeGraph(service, OVER, OVERWORLD), "连接删掉后退回本世界");
  }
}
