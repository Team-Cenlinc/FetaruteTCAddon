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
    return twoPlatforms(gap, 0, Optional.empty(), Optional.empty(), false);
  }

  /**
   * 同 {@link #twoPlatforms(int)}，另带牌子位置与开门方向。
   *
   * @param signOffset 牌子比轨道向外偏几格（1 道的往北、2 道的往南）；节点坐标取牌子的位置
   * @param door1 1 道 AutoStation 牌子写的开门方向
   * @param door2 2 道的开门方向
   * @param northSouth 南北向（x、z 对调：股道沿 z，1 道在西、2 道在东）
   */
  public static SimpleRailGraph twoPlatforms(
      int gap, int signOffset, Optional<String> door1, Optional<String> door2, boolean northSouth) {
    NodeId end1 = NodeId.of("END-1");
    NodeId end2 = NodeId.of("END-2");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, -signOffset, northSouth, door1);
    node(nodes, P2, NodeType.STATION, 0, gap + signOffset, northSouth, door2);
    node(nodes, end1, NodeType.DESTINATION, 40, 0, northSouth);
    node(nodes, end2, NodeType.DESTINATION, 40, gap, northSouth);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, end1, straight(0, 40, 0), northSouth);
    edge(edges, footprints, Set.of(), P2, end2, straight(0, 40, gap), northSouth);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /** 同 {@link #twoPlatforms(int)}，两条站台股道正中间另有一条不进站的正线穿过（与站场不相连）。 */
  public static SimpleRailGraph twoPlatformsAroundAThroughTrack(int gap) {
    NodeId end1 = NodeId.of("END-1");
    NodeId end2 = NodeId.of("END-2");
    NodeId west = NodeId.of("THROUGH-W");
    NodeId east = NodeId.of("THROUGH-E");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, P2, NodeType.STATION, 0, gap, false);
    node(nodes, end1, NodeType.DESTINATION, 40, 0, false);
    node(nodes, end2, NodeType.DESTINATION, 40, gap, false);
    node(nodes, west, NodeType.DESTINATION, -600, gap / 2, false);
    node(nodes, east, NodeType.DESTINATION, 600, gap / 2, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, end1, straight(0, 40, 0), false);
    edge(edges, footprints, Set.of(), P2, end2, straight(0, 40, gap), false);
    edge(edges, footprints, Set.of(), west, east, straight(-600, 600, gap / 2), false);
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

  public static final NodeId TO_XYZ = NodeId.of("SURC:ABC:XYZ:1:01");
  public static final NodeId TO_XYZ_FAR = NodeId.of("SURC:ABC:XYZ:1:02");
  public static final NodeId DEF_TO_XYZ = NodeId.of("SURC:DEF:XYZ:1:01");
  public static final NodeId JUNCTION = NodeId.of("SWITCHER:world:100:64:0");
  public static final NodeId X1 = NodeId.of("SURC:S:XYZ:1");

  /**
   * DEF 线在 ABC→XYZ 的区间中途岔出：ABC 与 DEF 之间没有两端都是它们的区间点，要经过两条通往 XYZ 的区间与一组道岔才连得上。
   *
   * <pre>
   *  P1(ABC 1道) ── TO_XYZ(ABC→XYZ) ── JUNCTION ── TO_XYZ_FAR(ABC→XYZ) ── X1(XYZ 1道)
   *                                        ╲
   *                                         DEF_TO_XYZ(DEF→XYZ) ── Q1(DEF 1道)
   * </pre>
   */
  public static SimpleRailGraph junction() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, TO_XYZ, NodeType.WAYPOINT, 60, 0, false);
    node(nodes, JUNCTION, NodeType.SWITCHER, 100, 0, false);
    node(nodes, TO_XYZ_FAR, NodeType.WAYPOINT, 160, 0, false);
    node(nodes, X1, NodeType.STATION, 220, 0, false);
    node(nodes, DEF_TO_XYZ, NodeType.WAYPOINT, 140, 20, false);
    node(nodes, Q1, NodeType.STATION, 200, 40, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, TO_XYZ, straight(0, 60, 0), false);
    edge(edges, footprints, Set.of(), TO_XYZ, JUNCTION, straight(60, 100, 0), false);
    edge(edges, footprints, Set.of(), JUNCTION, TO_XYZ_FAR, straight(100, 160, 0), false);
    edge(edges, footprints, Set.of(), TO_XYZ_FAR, X1, straight(160, 220, 0), false);
    edge(edges, footprints, Set.of(), JUNCTION, DEF_TO_XYZ, line(100, 0, 140, 20), false);
    edge(edges, footprints, Set.of(), DEF_TO_XYZ, Q1, line(140, 20, 200, 40), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId MERGE = NodeId.of("SWITCHER:world:100:64:10");

  /**
   * ABC 与 DEF 两条线在 MERGE 汇合后同去 XYZ：从 ABC 到 DEF 要在道岔后折返，两站并不相通。
   *
   * <pre>
   *  P1(ABC 1道) ── TO_XYZ(ABC→XYZ) ───╮
   *                                    MERGE ── TO_XYZ_FAR(ABC→XYZ) ── X1(XYZ 1道)
   *  Q1(DEF 1道) ── DEF_TO_XYZ(DEF→XYZ) ╯
   * </pre>
   */
  public static SimpleRailGraph merge() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, TO_XYZ, NodeType.WAYPOINT, 60, 0, false);
    node(nodes, Q1, NodeType.STATION, 0, 20, false);
    node(nodes, DEF_TO_XYZ, NodeType.WAYPOINT, 60, 20, false);
    node(nodes, MERGE, NodeType.SWITCHER, 100, 10, false);
    node(nodes, TO_XYZ_FAR, NodeType.WAYPOINT, 160, 10, false);
    node(nodes, X1, NodeType.STATION, 220, 10, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, TO_XYZ, straight(0, 60, 0), false);
    edge(edges, footprints, Set.of(), TO_XYZ, MERGE, line(60, 0, 100, 10), false);
    edge(edges, footprints, Set.of(), Q1, DEF_TO_XYZ, straight(0, 60, 20), false);
    edge(edges, footprints, Set.of(), DEF_TO_XYZ, MERGE, line(60, 20, 100, 10), false);
    edge(edges, footprints, Set.of(), MERGE, TO_XYZ_FAR, straight(100, 160, 10), false);
    edge(edges, footprints, Set.of(), TO_XYZ_FAR, X1, straight(160, 220, 10), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /** 两点之间逐格连成的一条斜线（含两端）。 */
  static List<int[]> line(int fromX, int fromZ, int toX, int toZ) {
    int steps = Math.max(Math.abs(toX - fromX), Math.abs(toZ - fromZ));
    List<int[]> cells = new ArrayList<>();
    for (int i = 0; i <= steps; i++) {
      double t = (double) i / steps;
      cells.add(
          new int[] {
            (int) Math.round(fromX + t * (toX - fromX)), (int) Math.round(fromZ + t * (toZ - fromZ))
          });
    }
    return cells;
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
    node(nodes, id, type, x, z, northSouth, Optional.empty());
  }

  private static void node(
      Map<NodeId, RailNode> nodes,
      NodeId id,
      NodeType type,
      int x,
      int z,
      boolean northSouth,
      Optional<String> door) {
    Vector at = northSouth ? new Vector(z, 64, x) : new Vector(x, 64, z);
    Optional<SignNodeDefinition> parsed =
        type == NodeType.SWITCHER
            ? Optional.empty()
            : SignTextParser.parseWaypointLike(id.value(), NodeType.WAYPOINT);
    nodes.put(
        id,
        new SignRailNode(
            id,
            type,
            at,
            Optional.empty(),
            parsed.flatMap(SignNodeDefinition::waypointMetadata),
            door));
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
