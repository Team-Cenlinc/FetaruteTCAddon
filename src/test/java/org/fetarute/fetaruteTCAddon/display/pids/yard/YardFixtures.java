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

  /**
   * ABC 站几条平行的站台股道，各自通向东头的终点，都没写开门方向：按给定的南北坐标从北往南排，股道号依次为 1、2、3……
   *
   * @param zs 各条股道的南北坐标
   */
  public static SimpleRailGraph stationTracks(int... zs) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    for (int i = 0; i < zs.length; i++) {
      NodeId track = NodeId.of("SURC:S:ABC:" + (i + 1));
      NodeId end = NodeId.of("END-" + (i + 1));
      node(nodes, track, NodeType.STATION, 0, zs[i], false);
      node(nodes, end, NodeType.DESTINATION, 40, zs[i], false);
      edge(edges, footprints, Set.of(), track, end, straight(0, 40, zs[i]), false);
    }
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /** 一条东西向的线：ABC 1 道与 DEF 1 道只隔 30 格，XYZ 1 道在 400 格外（按里程分宽度时两站分到的很少）。 */
  public static SimpleRailGraph closeStations() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, Q1, NodeType.STATION, 30, 0, false);
    node(nodes, X1, NodeType.STATION, 430, 0, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, Q1, straight(0, 30, 0), false);
    edge(edges, footprints, Set.of(), Q1, X1, straight(30, 430, 0), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /**
   * 两条平行的线上挨着的两个车站，都是岛式站台（两条股道相隔 10 格），站台节点在两条线上错开 6 格：ABC 1 道 x=0、2 道 x=6，DEF 1 道 x=30、2 道 x=36；1
   * 道一线再往东 400 格到 XYZ（按里程分宽度时两站分到的很少），2 道一线两头各伸到站外。
   */
  public static SimpleRailGraph closeIslands() {
    NodeId abc2 = NodeId.of("SURC:S:ABC:2");
    NodeId def2 = NodeId.of("SURC:S:DEF:2");
    NodeId west2 = NodeId.of("WEST-2");
    NodeId end2 = NodeId.of("END-2");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, abc2, NodeType.STATION, 6, 10, false);
    node(nodes, Q1, NodeType.STATION, 30, 0, false);
    node(nodes, def2, NodeType.STATION, 36, 10, false);
    node(nodes, X1, NodeType.STATION, 430, 0, false);
    node(nodes, west2, NodeType.DESTINATION, -40, 10, false);
    node(nodes, end2, NodeType.DESTINATION, 80, 10, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, Q1, straight(0, 30, 0), false);
    edge(edges, footprints, Set.of(), Q1, X1, straight(30, 430, 0), false);
    edge(edges, footprints, Set.of(), west2, abc2, straight(-40, 6, 10), false);
    edge(edges, footprints, Set.of(), abc2, def2, straight(6, 36, 10), false);
    edge(edges, footprints, Set.of(), def2, end2, straight(36, 80, 10), false);
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

  public static final NodeId FORK = NodeId.of("SWITCHER:world:200:64:0");
  public static final NodeId FORK_STEM = NodeId.of("SURC:ABC:DEF:1:02");
  public static final NodeId FORK_TO_DEF = NodeId.of("SURC:ABC:DEF:1:03");

  /**
   * ABC 往东到 FORK 分成两股，一股去 DEF、一股去 XYZ，三站都在控制区里。
   *
   * <pre>
   *                                              ╱── FORK_TO_DEF ── Q1(DEF 1道)
   *  P1(ABC 1道) ── FORK_STEM ── FORK ──────────<
   *                                              ╲── TO_XYZ ── X1(XYZ 1道)
   * </pre>
   *
   * @param straightOn 去 DEF 的一股顺着来路直着往东（否则往东北斜出，与去 XYZ 的一股对称）
   */
  public static SimpleRailGraph fork(boolean straightOn) {
    int defZ = straightOn ? 0 : -20;
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, FORK_STEM, NodeType.WAYPOINT, 100, 0, false);
    node(nodes, FORK, NodeType.SWITCHER, 200, 0, false);
    node(nodes, FORK_TO_DEF, NodeType.WAYPOINT, 240, defZ, false);
    node(nodes, Q1, NodeType.STATION, 300, 2 * defZ, false);
    node(nodes, TO_XYZ, NodeType.WAYPOINT, 240, 20, false);
    node(nodes, X1, NodeType.STATION, 300, 40, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, FORK_STEM, straight(0, 100, 0), false);
    edge(edges, footprints, Set.of(), FORK_STEM, FORK, straight(100, 200, 0), false);
    edge(edges, footprints, Set.of(), FORK, FORK_TO_DEF, line(200, 0, 240, defZ), false);
    edge(edges, footprints, Set.of(), FORK_TO_DEF, Q1, line(240, defZ, 300, 2 * defZ), false);
    edge(edges, footprints, Set.of(), FORK, TO_XYZ, line(200, 0, 240, 20), false);
    edge(edges, footprints, Set.of(), TO_XYZ, X1, line(240, 20, 300, 40), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId Q2 = NodeId.of("SURC:S:DEF:2");
  public static final NodeId X2 = NodeId.of("SURC:S:XYZ:2");
  public static final NodeId DEF_EAST = NodeId.of("SWITCHER:world:400:64:0");
  public static final NodeId XYZ_NORTH = NodeId.of("SWITCHER:world:250:64:-280");
  public static final NodeId TO_XYZ_2 = NodeId.of("SURC:ABC:XYZ:1:02");
  public static final NodeId CHORD_1 = NodeId.of("SURC:DEF:XYZ:1:01");
  public static final NodeId CHORD_2 = NodeId.of("SURC:DEF:XYZ:1:02");

  /**
   * 三角线：ABC 往东到 FORK 分成两股，一股直着往东到 DEF，一股往北拐到 XYZ（XYZ 是南北向的站，和来路垂直）；DEF 2 道西头与 XYZ 2 道南头之间有一条联络线，
   * 两站的 2 道另一头各在站外的道岔并入 1 道。
   *
   * <pre>
   *                       XYZ_NORTH（往北出站）
   *                        │      ╲
   *                       X1      X2
   *                        │        ╲
   *                    TO_XYZ_2     CHORD_2
   *                       ╱            │
   *                  TO_XYZ         CHORD_1
   *                   ╱                 ╲
   *  P1 ── FORK_STEM ── FORK ── FORK_TO_DEF ── Q1 ── DEF_EAST（往东出站）
   *                                            Q2 ─╯
   * </pre>
   */
  public static SimpleRailGraph triangle() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, FORK_STEM, NodeType.WAYPOINT, 100, 0, false);
    node(nodes, FORK, NodeType.SWITCHER, 200, 0, false);
    node(nodes, FORK_TO_DEF, NodeType.WAYPOINT, 260, 0, false);
    node(nodes, Q1, NodeType.STATION, 340, 0, false);
    node(nodes, Q2, NodeType.STATION, 340, -4, false);
    node(nodes, DEF_EAST, NodeType.SWITCHER, 400, 0, false);
    node(nodes, TO_GHI, NodeType.WAYPOINT, 460, 0, false);
    node(nodes, TO_XYZ, NodeType.WAYPOINT, 230, -60, false);
    node(nodes, TO_XYZ_2, NodeType.WAYPOINT, 250, -150, false);
    node(nodes, X1, NodeType.STATION, 250, -220, false);
    node(nodes, X2, NodeType.STATION, 254, -220, false);
    node(nodes, XYZ_NORTH, NodeType.SWITCHER, 250, -280, false);
    node(nodes, TO_JKL, NodeType.WAYPOINT, 250, -340, false);
    node(nodes, CHORD_1, NodeType.WAYPOINT, 300, -40, false);
    node(nodes, CHORD_2, NodeType.WAYPOINT, 280, -150, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, FORK_STEM, straight(0, 100, 0), false);
    edge(edges, footprints, Set.of(), FORK_STEM, FORK, straight(100, 200, 0), false);
    edge(edges, footprints, Set.of(), FORK, FORK_TO_DEF, straight(200, 260, 0), false);
    edge(edges, footprints, Set.of(), FORK_TO_DEF, Q1, straight(260, 340, 0), false);
    edge(edges, footprints, Set.of(), Q1, DEF_EAST, straight(340, 400, 0), false);
    edge(edges, footprints, Set.of(), DEF_EAST, TO_GHI, straight(400, 460, 0), false);
    List<int[]> defTwo = new ArrayList<>(straight(340, 396, -4));
    defTwo.addAll(line(396, -4, 400, 0));
    edge(edges, footprints, Set.of(), Q2, DEF_EAST, defTwo, false);
    edge(edges, footprints, Set.of(), FORK, TO_XYZ, line(200, 0, 230, -60), false);
    edge(edges, footprints, Set.of(), TO_XYZ, TO_XYZ_2, line(230, -60, 250, -150), false);
    edge(edges, footprints, Set.of(), TO_XYZ_2, X1, line(250, -150, 250, -220), false);
    edge(edges, footprints, Set.of(), X1, XYZ_NORTH, line(250, -220, 250, -280), false);
    edge(edges, footprints, Set.of(), XYZ_NORTH, TO_JKL, line(250, -280, 250, -340), false);
    List<int[]> xyzTwo = new ArrayList<>(line(254, -220, 254, -276));
    xyzTwo.addAll(line(254, -276, 250, -280));
    edge(edges, footprints, Set.of(), X2, XYZ_NORTH, xyzTwo, false);
    edge(edges, footprints, Set.of(), Q2, CHORD_1, line(340, -4, 300, -40), false);
    edge(edges, footprints, Set.of(), CHORD_1, CHORD_2, line(300, -40, 280, -150), false);
    edge(edges, footprints, Set.of(), CHORD_2, X2, line(280, -150, 254, -220), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId TO_GHI = NodeId.of("SURC:DEF:GHI:1:01");
  public static final NodeId TO_JKL = NodeId.of("SURC:XYZ:JKL:1:01");

  public static final NodeId DETOUR_WEST = NodeId.of("SWITCHER:world:120:64:0");
  public static final NodeId DETOUR_EAST = NodeId.of("SWITCHER:world:280:64:0");
  public static final NodeId DETOUR_1 = NodeId.of("SURC:ABC:DEF:2:01");
  public static final NodeId DETOUR_2 = NodeId.of("SURC:ABC:DEF:2:02");
  public static final NodeId DETOUR_3 = NodeId.of("SURC:ABC:DEF:2:03");

  /**
   * ABC 与 DEF 之间的正线上两组道岔，中间另有一条往北绕出 100 格的迂回线：沿轨道比两组道岔之间的正线长得多，最后一段很短。
   *
   * <pre>
   *            DETOUR_1 ─────────── DETOUR_2
   *           ╱                          │
   *          ╱                       DETOUR_3
   *  P1 ── DETOUR_WEST ─────────── DETOUR_EAST ── Q1
   * </pre>
   */
  public static SimpleRailGraph detour() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, DETOUR_WEST, NodeType.SWITCHER, 120, 0, false);
    node(nodes, DETOUR_EAST, NodeType.SWITCHER, 280, 0, false);
    node(nodes, Q1, NodeType.STATION, 400, 0, false);
    node(nodes, DETOUR_1, NodeType.WAYPOINT, 140, -100, false);
    node(nodes, DETOUR_2, NodeType.WAYPOINT, 260, -100, false);
    node(nodes, DETOUR_3, NodeType.WAYPOINT, 280, -10, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, DETOUR_WEST, straight(0, 120, 0), false);
    edge(edges, footprints, Set.of(), DETOUR_WEST, DETOUR_EAST, straight(120, 280, 0), false);
    edge(edges, footprints, Set.of(), DETOUR_EAST, Q1, straight(280, 400, 0), false);
    edge(edges, footprints, Set.of(), DETOUR_WEST, DETOUR_1, line(120, 0, 140, -100), false);
    edge(edges, footprints, Set.of(), DETOUR_1, DETOUR_2, straight(140, 260, -100), false);
    edge(edges, footprints, Set.of(), DETOUR_2, DETOUR_3, line(260, -100, 280, -10), false);
    edge(edges, footprints, Set.of(), DETOUR_3, DETOUR_EAST, line(280, -10, 280, 0), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /**
   * 车库 DEP：ABC 1 道往东接一条出库线，第 k 组道岔（x = 60 + 6k）岔出第 k 条朝南的库线（与正线几乎垂直，40 格，尽头是车挡）；
   * 最后一条库线是最后一组道岔直股的尽头，朝东。
   *
   * @param tracks 库线条数（至少 2）
   */
  public static SimpleRailGraph depot(int tracks) {
    return depot(tracks, false);
  }

  /**
   * 同 {@link #depot(int)}；{@code through} 时最后一条库线不是尽头，接着往东通到 XYZ 1 道（两头都通库外的车库）。
   *
   * @param tracks 库线条数（至少 2）
   * @param through 最后一条库线往东接到 XYZ
   */
  public static SimpleRailGraph depot(int tracks, boolean through) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    NodeId previous = P1;
    int previousX = 0;
    for (int k = 1; k < tracks; k++) {
      int x = 60 + 6 * k;
      NodeId fork = depotSwitch(k);
      node(nodes, fork, NodeType.SWITCHER, x, 0, false);
      edge(edges, footprints, Set.of(), previous, fork, straight(previousX, x, 0), false);
      NodeId end = depotTrack(k);
      node(nodes, end, NodeType.DEPOT, x + 3, 40, false);
      List<int[]> cells = new ArrayList<>(line(x, 0, x + 3, 3));
      for (int z = 4; z <= 40; z++) {
        cells.add(new int[] {x + 3, z});
      }
      edge(edges, footprints, Set.of(), fork, end, cells, false);
      previous = fork;
      previousX = x;
    }
    NodeId last = depotTrack(tracks);
    node(nodes, last, NodeType.DEPOT, previousX + 40, 0, false);
    edge(
        edges, footprints, Set.of(), previous, last, straight(previousX, previousX + 40, 0), false);
    if (through) {
      node(nodes, X1, NodeType.STATION, previousX + 120, 0, false);
      edge(
          edges,
          footprints,
          Set.of(),
          last,
          X1,
          straight(previousX + 40, previousX + 120, 0),
          false);
    }
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId MAIN_SWITCH = NodeId.of("SWITCHER:world:50:64:0");
  public static final NodeId SIDE_SWITCH = NodeId.of("SWITCHER:world:50:64:12");

  /**
   * 两条平行线由一条垂直的联络线相连：ABC 1 道（x=0）经 MAIN_SWITCH（x=50）向东到无名终点；北边 12 格另有一条线，中间的 SIDE_SWITCH
   * 经联络线（与两条线都垂直）接到 MAIN_SWITCH。另一条线从联络线接进来时分不出顺逆，要按脊线走向排。
   */
  public static SimpleRailGraph perpendicularLink() {
    NodeId east = NodeId.of("END-E1");
    NodeId westSide = NodeId.of("END-W2");
    NodeId eastSide = NodeId.of("END-E2");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, MAIN_SWITCH, NodeType.SWITCHER, 50, 0, false);
    node(nodes, east, NodeType.DESTINATION, 100, 0, false);
    node(nodes, westSide, NodeType.DESTINATION, 0, 12, false);
    node(nodes, SIDE_SWITCH, NodeType.SWITCHER, 50, 12, false);
    node(nodes, eastSide, NodeType.DESTINATION, 100, 12, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, MAIN_SWITCH, straight(0, 50, 0), false);
    edge(edges, footprints, Set.of(), MAIN_SWITCH, east, straight(50, 100, 0), false);
    edge(edges, footprints, Set.of(), westSide, SIDE_SWITCH, straight(0, 50, 12), false);
    edge(edges, footprints, Set.of(), SIDE_SWITCH, eastSide, straight(50, 100, 12), false);
    edge(edges, footprints, Set.of(), MAIN_SWITCH, SIDE_SWITCH, line(50, 0, 50, 12), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /**
   * ABC 的 1、2 道（z=0、z=4）往东经一组剪式渡线：两条线的道岔 A（x=40）各斜着接到中心道岔 C1（x=52），C1 与 C2（x=68）之间一段共用线，C2
   * 再斜着分回两条线的道岔 B（x=80）。C1、C2 的两条斜线都朝同一边离开（V 形），在它那里汇成 Y 形。
   */
  public static SimpleRailGraph scissors() {
    NodeId a1 = NodeId.of("SWITCHER:world:40:64:0");
    NodeId a2 = NodeId.of("SWITCHER:world:40:64:4");
    NodeId b1 = NodeId.of("SWITCHER:world:80:64:0");
    NodeId b2 = NodeId.of("SWITCHER:world:80:64:4");
    NodeId c1 = NodeId.of("SWITCHER:world:52:64:2");
    NodeId c2 = NodeId.of("SWITCHER:world:68:64:2");
    NodeId end1 = NodeId.of("END-1");
    NodeId end2 = NodeId.of("END-2");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, P2, NodeType.STATION, 0, 4, false);
    node(nodes, a1, NodeType.SWITCHER, 40, 0, false);
    node(nodes, a2, NodeType.SWITCHER, 40, 4, false);
    node(nodes, c1, NodeType.SWITCHER, 52, 2, false);
    node(nodes, c2, NodeType.SWITCHER, 68, 2, false);
    node(nodes, b1, NodeType.SWITCHER, 80, 0, false);
    node(nodes, b2, NodeType.SWITCHER, 80, 4, false);
    node(nodes, end1, NodeType.DESTINATION, 120, 0, false);
    node(nodes, end2, NodeType.DESTINATION, 120, 4, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, a1, straight(0, 40, 0), false);
    edge(edges, footprints, Set.of(), P2, a2, straight(0, 40, 4), false);
    edge(edges, footprints, Set.of(), a1, b1, straight(40, 80, 0), false);
    edge(edges, footprints, Set.of(), a2, b2, straight(40, 80, 4), false);
    edge(edges, footprints, Set.of(), b1, end1, straight(80, 120, 0), false);
    edge(edges, footprints, Set.of(), b2, end2, straight(80, 120, 4), false);
    edge(edges, footprints, Set.of(), a1, c1, line(40, 0, 52, 2), false);
    edge(edges, footprints, Set.of(), a2, c1, line(40, 4, 52, 2), false);
    edge(edges, footprints, Set.of(), c1, c2, straight(52, 68, 2), false);
    edge(edges, footprints, Set.of(), c2, b1, line(68, 2, 80, 0), false);
    edge(edges, footprints, Set.of(), c2, b2, line(68, 2, 80, 4), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /** 车库 DEP 的第 {@code k} 条库线。 */
  public static NodeId depotTrack(int k) {
    return NodeId.of("SURC:D:DEP:" + k);
  }

  /** 车库 DEP 岔出第 {@code k} 条库线的道岔。 */
  public static NodeId depotSwitch(int k) {
    return NodeId.of("SWITCHER:world:" + (60 + 6 * k) + ":64:0");
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
