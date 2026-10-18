package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;

/**
 * 测试用的小站 ABC：两股道，两头各一组道岔，西头有一段咽喉，东西各接一个区间点；2 道另有一条伸向 400 格外道岔的支线（没有足迹）。
 *
 * <pre>
 *  IA(XYZ→ABC) ── SW1 ── T1 ── P1(1道) ── SW2 ── IB(ABC→DEF)
 *                    ╲                  ╱
 *                     ╲──── P2(2道) ───╱
 *                              │
 *                             SW3（400 格外）
 * </pre>
 *
 * <p>坐标：东西向沿 x（1 道在 z=0、2 道在 z=4），高度 64；{@code northSouth} 时把 x 与 z 对调。
 */
public final class YardFixtures {

  public static final UUID WORLD = UUID.fromString("11111111-2222-3333-4444-555555555555");
  public static final NodeId IA = NodeId.of("SURC:XYZ:ABC:1:01");
  public static final NodeId SW1 = NodeId.of("SWITCHER:world:-40:64:0");
  public static final NodeId T1 = NodeId.of("SURC:S:ABC:1:01");
  public static final NodeId P1 = NodeId.of("SURC:S:ABC:1");
  public static final NodeId P2 = NodeId.of("SURC:S:ABC:2");
  public static final NodeId SW2 = NodeId.of("SWITCHER:world:40:64:0");
  public static final NodeId IB = NodeId.of("SURC:ABC:DEF:1:01");
  public static final NodeId SW3 = NodeId.of("SWITCHER:world:0:64:400");

  private YardFixtures() {}

  /** 东西向的站，不带支线。 */
  public static SimpleRailGraph graph() {
    return graph(false, false, Set.of());
  }

  /**
   * @param northSouth 南北向（x、z 对调）
   * @param branch 带上 2 道伸向 SW3 的支线
   * @param withoutFootprint 这些区间不带足迹
   */
  public static SimpleRailGraph graph(
      boolean northSouth, boolean branch, Set<EdgeId> withoutFootprint) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, IA, NodeType.WAYPOINT, -100, 0, northSouth);
    node(nodes, SW1, NodeType.SWITCHER, -40, 0, northSouth);
    node(nodes, T1, NodeType.WAYPOINT, -20, 0, northSouth);
    node(nodes, P1, NodeType.STATION, 0, 0, northSouth);
    node(nodes, P2, NodeType.STATION, 0, 4, northSouth);
    node(nodes, SW2, NodeType.SWITCHER, 40, 0, northSouth);
    node(nodes, IB, NodeType.WAYPOINT, 100, 0, northSouth);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, withoutFootprint, IA, SW1, straight(-100, -40, 0), northSouth);
    edge(edges, footprints, withoutFootprint, SW1, T1, straight(-40, -20, 0), northSouth);
    edge(edges, footprints, withoutFootprint, T1, P1, straight(-20, 0, 0), northSouth);
    edge(edges, footprints, withoutFootprint, SW1, P2, westTurnout(), northSouth);
    edge(edges, footprints, withoutFootprint, P1, SW2, straight(0, 40, 0), northSouth);
    edge(edges, footprints, withoutFootprint, P2, SW2, eastTurnout(), northSouth);
    edge(edges, footprints, withoutFootprint, SW2, IB, straight(40, 100, 0), northSouth);
    if (branch) {
      nodes.put(
          SW3,
          new SignRailNode(
              SW3, NodeType.SWITCHER, new Vector(0, 64, 400), Optional.empty(), Optional.empty()));
      EdgeId id = EdgeId.undirected(P2, SW3);
      edges.put(id, new RailEdge(id, P2, SW3, 400, 8.0, true, Optional.empty()));
    }
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId SW_FAR = NodeId.of("SWITCHER:world:30:64:10");

  /** 1 道向东偏南拐出一条原版铁轨的斜线：每走 3 格横移 1 格，拐角处是直角台阶。 */
  public static SimpleRailGraph staircase() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, SW_FAR, NodeType.SWITCHER, 30, 10, false);
    List<int[]> cells = new ArrayList<>();
    for (int x = 0; x <= 30; x++) {
      if (x > 0 && x % 3 == 0) {
        cells.add(new int[] {x, x / 3 - 1});
      }
      cells.add(new int[] {x, x / 3});
    }
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, SW_FAR, cells, false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /** 两条平行的站台股道，各向东接一段 40 格的直线到无名目的地节点；两股道真实间距为 {@code gap} 格（1 道在北）。 */
  public static SimpleRailGraph twoPlatforms(int gap) {
    NodeId end1 = NodeId.of("END-1");
    NodeId end2 = NodeId.of("END-2");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, P2, NodeType.STATION, 0, gap, false);
    node(nodes, end1, NodeType.DESTINATION, 40, 0, false);
    node(nodes, end2, NodeType.DESTINATION, 40, gap, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, end1, straight(0, 40, 0), false);
    edge(edges, footprints, Set.of(), P2, end2, straight(0, 40, gap), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId D1 = NodeId.of("SURC:D:DEP:1");
  public static final NodeId Q1 = NodeId.of("SURC:S:DEF:1");
  public static final NodeId IC = NodeId.of("SURC:DEF:GHI:1:01");

  /** 一条东西向的直线：车库 DEP 1 道 ─ ABC 1 道 ─ 区间点 IB（ABC→DEF）─ DEF 1 道 ─ 区间点 IC（DEF→GHI）。 */
  public static SimpleRailGraph corridor() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, D1, NodeType.DEPOT, -60, 0, false);
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, IB, NodeType.WAYPOINT, 100, 0, false);
    node(nodes, Q1, NodeType.STATION, 200, 0, false);
    node(nodes, IC, NodeType.WAYPOINT, 300, 0, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), D1, P1, straight(-60, 0, 0), false);
    edge(edges, footprints, Set.of(), P1, IB, straight(0, 100, 0), false);
    edge(edges, footprints, Set.of(), IB, Q1, straight(100, 200, 0), false);
    edge(edges, footprints, Set.of(), Q1, IC, straight(200, 300, 0), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /** 东西向时 SW1–P2 的轨道方块（含斜向台阶）。 */
  static List<int[]> westTurnout() {
    List<int[]> cells = new ArrayList<>();
    cells.add(new int[] {-40, 0});
    cells.add(new int[] {-39, 1});
    cells.add(new int[] {-38, 2});
    cells.add(new int[] {-37, 3});
    cells.addAll(straight(-36, 0, 4));
    return cells;
  }

  private static List<int[]> eastTurnout() {
    List<int[]> cells = new ArrayList<>(straight(0, 36, 4));
    cells.add(new int[] {37, 3});
    cells.add(new int[] {38, 2});
    cells.add(new int[] {39, 1});
    cells.add(new int[] {40, 0});
    return cells;
  }

  static List<int[]> straight(int fromX, int toX, int z) {
    List<int[]> cells = new ArrayList<>();
    for (int x = fromX; x <= toX; x++) {
      cells.add(new int[] {x, z});
    }
    return cells;
  }

  /** 东西向坐标 → 方块（南北向时对调）。 */
  public static RailFootprintCell cell(int x, int z, boolean northSouth) {
    return northSouth ? new RailFootprintCell(z, 64, x) : new RailFootprintCell(x, 64, z);
  }

  private static void node(
      Map<NodeId, RailNode> nodes, NodeId id, NodeType type, int x, int z, boolean northSouth) {
    Vector at = northSouth ? new Vector(z, 64, x) : new Vector(x, 64, z);
    Optional<SignNodeDefinition> parsed =
        type == NodeType.SWITCHER
            ? Optional.empty()
            : SignTextParser.parseWaypointLike(id.value(), NodeType.WAYPOINT);
    nodes.put(
        id,
        new SignRailNode(
            id, type, at, Optional.empty(), parsed.flatMap(SignNodeDefinition::waypointMetadata)));
  }

  private static void edge(
      Map<EdgeId, RailEdge> edges,
      Map<EdgeId, RailEdgeFootprint> footprints,
      Set<EdgeId> withoutFootprint,
      NodeId from,
      NodeId to,
      List<int[]> cells,
      boolean northSouth) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, cells.size(), 8.0, true, Optional.empty()));
    if (withoutFootprint.contains(id)) {
      return;
    }
    Set<RailFootprintCell> set = new LinkedHashSet<>();
    for (int[] c : cells) {
      set.add(cell(c[0], c[1], northSouth));
    }
    footprints.put(id, new RailEdgeFootprint(RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, set));
  }
}
