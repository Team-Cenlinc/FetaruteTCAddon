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

  /** 一条东西向的直线上三个站：ABC 1 道 ─ 区间点 IB（ABC→DEF）─ DEF 1 道 ─ 区间点 IC（DEF→GHI）─ GHI 1 道。 */
  public static SimpleRailGraph corridorOfThree() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, IB, NodeType.WAYPOINT, 100, 0, false);
    node(nodes, Q1, NodeType.STATION, 200, 0, false);
    node(nodes, IC, NodeType.WAYPOINT, 300, 0, false);
    node(nodes, G1, NodeType.STATION, 400, 0, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, IB, straight(0, 100, 0), false);
    edge(edges, footprints, Set.of(), IB, Q1, straight(100, 200, 0), false);
    edge(edges, footprints, Set.of(), Q1, IC, straight(200, 300, 0), false);
    edge(edges, footprints, Set.of(), IC, G1, straight(300, 400, 0), false);
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

  public static final NodeId G1 = NodeId.of("SURC:S:GHI:1");

  /**
   * 复线 ABC—DEF：1 道（z=0）直着往东，接着单线到 GHI；2 道（z=4）在 x=200 到 300 之间往南绕一大圈（轨道长两倍多，垂足却只前移 100 格），绕出来以后又与 1
   * 道平行。ABC 东头一组渡线（1 道 x=40、2 道 x=60 的道岔），两站之间每 100 格一个区间点。
   *
   * <pre>
   *  P1(ABC 1道) ─ SW ─ 01 ─ 02 ─ 03 ─ 04 ─ Q1(DEF 1道) ─ 01 ─ G1(GHI 1道)
   *                  ╲
   *  P2(ABC 2道) ──── SW ─ 01 ─ 02 ╲__╱ 03 ─ 04 ─ Q2(DEF 2道)
   * </pre>
   */
  public static SimpleRailGraph doubleTrackLoop() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    for (int track = 1; track <= 2; track++) {
      int z = track == 1 ? 0 : 4;
      NodeId previous = track == 1 ? P1 : P2;
      node(nodes, previous, NodeType.STATION, 0, z, false);
      for (int i = 1; i <= 5; i++) {
        NodeId next =
            i == 5 ? (track == 1 ? Q1 : Q2) : NodeId.of("SURC:ABC:DEF:" + track + ":0" + i);
        node(nodes, next, i == 5 ? NodeType.STATION : NodeType.WAYPOINT, 100 * i, z, false);
        List<int[]> cells;
        if (i == 1) {
          int switchX = track == 1 ? 40 : 60;
          NodeId crossover = NodeId.of("SWITCHER:world:" + switchX + ":64:" + z);
          node(nodes, crossover, NodeType.SWITCHER, switchX, z, false);
          edge(edges, footprints, Set.of(), previous, crossover, straight(0, switchX, z), false);
          previous = crossover;
          cells = straight(switchX, 100, z);
        } else if (track == 2 && i == 3) {
          cells = new ArrayList<>(line(200, z, 250, 100));
          cells.addAll(line(250, 100, 300, z));
        } else {
          cells = straight(100 * (i - 1), 100 * i, z);
        }
        edge(edges, footprints, Set.of(), previous, next, cells, false);
        previous = next;
      }
    }
    edge(
        edges,
        footprints,
        Set.of(),
        NodeId.of("SWITCHER:world:40:64:0"),
        NodeId.of("SWITCHER:world:60:64:4"),
        line(40, 0, 60, 4),
        false);
    NodeId toGhi = NodeId.of("SURC:DEF:GHI:1:01");
    node(nodes, toGhi, NodeType.WAYPOINT, 700, 0, false);
    node(nodes, G1, NodeType.STATION, 900, 0, false);
    edge(edges, footprints, Set.of(), Q1, toGhi, straight(500, 700, 0), false);
    edge(edges, footprints, Set.of(), toGhi, G1, straight(700, 900, 0), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId TURNOUT = NodeId.of("SWITCHER:world:80:64:0");

  /**
   * ABC 1 道出站后的区间点 01 经一组道岔分成两股：直着往东的是 DEF 2 道那条区间的 02，往东南斜出的才是 1 道这条区间的 02，分别进 DEF 的 2、1 道。
   *
   * <pre>
   *  P1(ABC 1道) ─ ABC:DEF:1:01 ─ TURNOUT ─────── ABC:DEF:2:02 ─ Q2(DEF 2道)
   *                                       ╲
   *                                        ABC:DEF:1:02 ─ Q1(DEF 1道)
   * </pre>
   */
  public static SimpleRailGraph intervalTurnout() {
    return intervalTurnout(false);
  }

  /**
   * 同 {@link #intervalTurnout()}。
   *
   * @param gap 1 道的序号跳了一个：岔出去的是 03（02 的牌子拆了），2 道的 02 照旧
   */
  public static SimpleRailGraph intervalTurnout(boolean gap) {
    NodeId first = NodeId.of("SURC:ABC:DEF:1:01");
    NodeId straightOn = NodeId.of("SURC:ABC:DEF:2:02");
    NodeId diverging = NodeId.of(gap ? "SURC:ABC:DEF:1:03" : "SURC:ABC:DEF:1:02");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, first, NodeType.WAYPOINT, 40, 0, false);
    node(nodes, TURNOUT, NodeType.SWITCHER, 80, 0, false);
    node(nodes, straightOn, NodeType.WAYPOINT, 140, 0, false);
    node(nodes, Q2, NodeType.STATION, 200, 0, false);
    node(nodes, diverging, NodeType.WAYPOINT, 140, 12, false);
    node(nodes, Q1, NodeType.STATION, 200, 12, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, first, straight(0, 40, 0), false);
    edge(edges, footprints, Set.of(), first, TURNOUT, straight(40, 80, 0), false);
    edge(edges, footprints, Set.of(), TURNOUT, straightOn, straight(80, 140, 0), false);
    edge(edges, footprints, Set.of(), straightOn, Q2, straight(140, 200, 0), false);
    List<int[]> turnout = new ArrayList<>(line(80, 0, 92, 12));
    turnout.addAll(straight(93, 140, 12));
    edge(edges, footprints, Set.of(), TURNOUT, diverging, turnout, false);
    edge(edges, footprints, Set.of(), diverging, Q1, straight(140, 200, 12), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId SPUR_END = NodeId.of("END-SPUR");

  /**
   * ABC 1 道往东经一组道岔，往南岔出一条垂直的尽头线。尽头线的轨道方块在中途有一对横向并排、离两头一样远的方块（沿轨道排序时打平），
   * 两格谁先谁后，量出的轨道长度就差两格多；尽头线和脊线垂直、按轨道长度排横坐标，所以方块次序一变画面就跟着变。
   */
  public static SimpleRailGraph tiedSpur() {
    NodeId turnout = NodeId.of("SWITCHER:world:40:64:0");
    NodeId east = NodeId.of("END-EAST");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, turnout, NodeType.SWITCHER, 40, 0, false);
    node(nodes, east, NodeType.DESTINATION, 100, 0, false);
    node(nodes, SPUR_END, NodeType.DESTINATION, 40, 30, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, turnout, straight(0, 40, 0), false);
    edge(edges, footprints, Set.of(), turnout, east, straight(40, 100, 0), false);
    List<int[]> spur = new ArrayList<>();
    for (int z = 0; z <= 8; z++) {
      spur.add(new int[] {40, z});
    }
    spur.add(new int[] {39, 9});
    spur.add(new int[] {39, 10});
    spur.add(new int[] {41, 10});
    spur.add(new int[] {41, 11});
    for (int z = 12; z <= 30; z++) {
      spur.add(new int[] {40, z});
    }
    edge(edges, footprints, Set.of(), turnout, SPUR_END, spur, false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId PAST_EXIT = NodeId.of("SWITCHER:world:240:64:20");

  /**
   * 同 {@link #fork}（两股对称斜出，三站都在控制区里），只是去 XYZ 的一股先经一组道岔 PAST_EXIT：那里往回岔出一条出区的线（区间点
   * XYZ:QQQ:1:01），沿轨道比 XYZ 站近，但要在道岔处折返才走得到。
   *
   * <pre>
   *                                              ╱── FORK_TO_DEF ── Q1(DEF 1道)
   *  P1(ABC 1道) ── FORK_STEM ── FORK ──────────<
   *                                              ╲── PAST_EXIT ── X1(XYZ 1道)
   *                                       XYZ:QQQ:1:01 ╯
   * </pre>
   */
  public static SimpleRailGraph forkPastExit() {
    NodeId exit = NodeId.of("SURC:XYZ:QQQ:1:01");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, FORK_STEM, NodeType.WAYPOINT, 100, 0, false);
    node(nodes, FORK, NodeType.SWITCHER, 200, 0, false);
    node(nodes, FORK_TO_DEF, NodeType.WAYPOINT, 240, -20, false);
    node(nodes, Q1, NodeType.STATION, 300, -40, false);
    node(nodes, PAST_EXIT, NodeType.SWITCHER, 240, 20, false);
    node(nodes, X1, NodeType.STATION, 300, 40, false);
    node(nodes, exit, NodeType.WAYPOINT, 215, 30, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, FORK_STEM, straight(0, 100, 0), false);
    edge(edges, footprints, Set.of(), FORK_STEM, FORK, straight(100, 200, 0), false);
    edge(edges, footprints, Set.of(), FORK, FORK_TO_DEF, line(200, 0, 240, -20), false);
    edge(edges, footprints, Set.of(), FORK_TO_DEF, Q1, line(240, -20, 300, -40), false);
    edge(edges, footprints, Set.of(), FORK, PAST_EXIT, line(200, 0, 240, 20), false);
    edge(edges, footprints, Set.of(), PAST_EXIT, X1, line(240, 20, 300, 40), false);
    edge(edges, footprints, Set.of(), PAST_EXIT, exit, line(240, 20, 215, 30), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /**
   * DEF、XYZ 两站在道岔 FORK 处分开，来路是区外（往 QQQ 的区间点）：控制区只含岔出去的两股。
   *
   * <pre>
   *                                    ╱── Q1(DEF 1道)
   *  ABC:QQQ:1:01 ──── FORK ──────────<
   *                                    ╲── X1(XYZ 1道)
   * </pre>
   *
   * @param straightOn 去 DEF 的一股顺着来路直着往东（否则往东北斜出，与去 XYZ 的一股对称）
   */
  public static SimpleRailGraph forkFromOutside(boolean straightOn) {
    int defZ = straightOn ? 0 : -30;
    NodeId stem = NodeId.of("SURC:ABC:QQQ:1:01");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, stem, NodeType.WAYPOINT, 140, 0, false);
    node(nodes, FORK, NodeType.SWITCHER, 200, 0, false);
    node(nodes, Q1, NodeType.STATION, 260, defZ, false);
    node(nodes, X1, NodeType.STATION, 260, 30, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), stem, FORK, straight(140, 200, 0), false);
    edge(edges, footprints, Set.of(), FORK, Q1, line(200, 0, 260, defZ), false);
    edge(edges, footprints, Set.of(), FORK, X1, line(200, 0, 260, 30), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId THROAT_SWITCH = NodeId.of("SWITCHER:world:-60:64:10");

  /**
   * ABC 两条股道：2 道（z=20）是一条长直线（整片的脊线）；1 道站台在 z=0，出站往西收拢到 z=10，在道岔 THROAT_SWITCH 处往西北岔出一条去 XYZ 的出口；
   * 东头一组渡线连起两条股道。出口末端在 1 道外侧（北），但按整片的横向位置量，它落在 1 道站台与 2 道之间。
   *
   * <pre>
   *            XYZ:1:01 ╲
   *  END-W1 ── THROAT_SWITCH ──╮
   *                             ╰── P1(ABC 1道) ── SW ── END-E1
   *                                                  ╲
   *  END-W2 ──────────────────────── P2(ABC 2道) ──── SW ── END-E2
   * </pre>
   */
  public static SimpleRailGraph throatExit() {
    NodeId westOne = NodeId.of("END-W1");
    NodeId eastOne = NodeId.of("END-E1");
    NodeId westTwo = NodeId.of("END-W2");
    NodeId eastTwo = NodeId.of("END-E2");
    NodeId exit = NodeId.of("SURC:ABC:XYZ:1:01");
    NodeId crossOne = NodeId.of("SWITCHER:world:40:64:0");
    NodeId crossTwo = NodeId.of("SWITCHER:world:60:64:20");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, westTwo, NodeType.DESTINATION, -200, 20, false);
    node(nodes, P2, NodeType.STATION, 0, 20, false);
    node(nodes, crossTwo, NodeType.SWITCHER, 60, 20, false);
    node(nodes, eastTwo, NodeType.DESTINATION, 100, 20, false);
    node(nodes, crossOne, NodeType.SWITCHER, 40, 0, false);
    node(nodes, westOne, NodeType.DESTINATION, -120, 10, false);
    node(nodes, THROAT_SWITCH, NodeType.SWITCHER, -60, 10, false);
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, eastOne, NodeType.DESTINATION, 60, 0, false);
    node(nodes, exit, NodeType.WAYPOINT, -100, 0, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), westTwo, P2, straight(-200, 0, 20), false);
    edge(edges, footprints, Set.of(), P2, crossTwo, straight(0, 60, 20), false);
    edge(edges, footprints, Set.of(), crossTwo, eastTwo, straight(60, 100, 20), false);
    edge(edges, footprints, Set.of(), westOne, THROAT_SWITCH, straight(-120, -60, 10), false);
    List<int[]> converging = new ArrayList<>(straight(-60, -40, 10));
    converging.addAll(line(-39, 9, -30, 0));
    converging.addAll(straight(-29, 0, 0));
    edge(edges, footprints, Set.of(), THROAT_SWITCH, P1, converging, false);
    edge(edges, footprints, Set.of(), P1, crossOne, straight(0, 40, 0), false);
    edge(edges, footprints, Set.of(), crossOne, eastOne, straight(40, 60, 0), false);
    edge(edges, footprints, Set.of(), crossOne, crossTwo, line(40, 0, 60, 20), false);
    edge(edges, footprints, Set.of(), THROAT_SWITCH, exit, line(-60, 10, -100, 0), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId SIDING_LEFT = NodeId.of("END-T");
  public static final NodeId SIDING_UNDER_LEFT = NodeId.of("END-S");
  public static final NodeId SIDING_RIGHT = NodeId.of("END-X");

  /**
   * ABC 1 道所在的正线（z=0）上岔出几条尽头侧线：西头一条（z=4）、它下面再岔一条（z=8），东头一条（z=4），
   * 还有一条从东头往回、在最下面横贯全站的长侧线（z=12）。东头那条侧线只连着正线，应紧贴正线、与西头那条同一层。
   *
   * <pre>
   *  ─────────── SWT ── P1(ABC 1道) ── SWX ───── SWY ─────
   *   END-T ─ SWS ╯                      ╰─ END-X     │
   *   END-S ─╯                                        │
   *   END-Y ──────────────────────────────────────────╯
   * </pre>
   */
  public static SimpleRailGraph sidings() {
    NodeId west = NodeId.of("END-MW");
    NodeId east = NodeId.of("END-ME");
    NodeId leftSwitch = NodeId.of("SWITCHER:world:36:64:0");
    NodeId underSwitch = NodeId.of("SWITCHER:world:20:64:4");
    NodeId rightSwitch = NodeId.of("SWITCHER:world:60:64:0");
    NodeId longSwitch = NodeId.of("SWITCHER:world:90:64:0");
    NodeId longEnd = NodeId.of("END-Y");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, west, NodeType.DESTINATION, -10, 0, false);
    node(nodes, leftSwitch, NodeType.SWITCHER, 36, 0, false);
    node(nodes, P1, NodeType.STATION, 50, 0, false);
    node(nodes, rightSwitch, NodeType.SWITCHER, 60, 0, false);
    node(nodes, longSwitch, NodeType.SWITCHER, 90, 0, false);
    node(nodes, east, NodeType.DESTINATION, 110, 0, false);
    node(nodes, underSwitch, NodeType.SWITCHER, 20, 4, false);
    node(nodes, SIDING_LEFT, NodeType.DESTINATION, 0, 4, false);
    node(nodes, SIDING_UNDER_LEFT, NodeType.DESTINATION, 0, 8, false);
    node(nodes, SIDING_RIGHT, NodeType.DESTINATION, 100, 4, false);
    node(nodes, longEnd, NodeType.DESTINATION, -10, 12, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), west, leftSwitch, straight(-10, 36, 0), false);
    edge(edges, footprints, Set.of(), leftSwitch, P1, straight(36, 50, 0), false);
    edge(edges, footprints, Set.of(), P1, rightSwitch, straight(50, 60, 0), false);
    edge(edges, footprints, Set.of(), rightSwitch, longSwitch, straight(60, 90, 0), false);
    edge(edges, footprints, Set.of(), longSwitch, east, straight(90, 110, 0), false);
    List<int[]> left = new ArrayList<>(line(36, 0, 32, 4));
    left.addAll(straight(20, 31, 4));
    edge(edges, footprints, Set.of(), leftSwitch, underSwitch, left, false);
    edge(edges, footprints, Set.of(), underSwitch, SIDING_LEFT, straight(0, 20, 4), false);
    List<int[]> under = new ArrayList<>(line(20, 4, 16, 8));
    under.addAll(straight(0, 15, 8));
    edge(edges, footprints, Set.of(), underSwitch, SIDING_UNDER_LEFT, under, false);
    List<int[]> right = new ArrayList<>(line(60, 0, 64, 4));
    right.addAll(straight(65, 100, 4));
    edge(edges, footprints, Set.of(), rightSwitch, SIDING_RIGHT, right, false);
    List<int[]> along = new ArrayList<>(line(90, 0, 78, 12));
    along.addAll(straight(-10, 77, 12));
    edge(edges, footprints, Set.of(), longSwitch, longEnd, along, false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId CROSS_TWO = NodeId.of("SWITCHER:world:114:64:16");
  public static final NodeId CROSSOVER_MERGE = NodeId.of("SWITCHER:world:230:64:0");

  /**
   * 复线过一组渡线（1 道这一股另岔出一条出口）后，在群外一段汇成单线去 DEF。2 道这一股绕远路才汇进来，从 2 道经渡线换到 1 道再去汇合点反而更近； 渡线在 2 道道岔处也比 2
   * 道的去路更直。两股走到的群外那一头是同一个汇合道岔。
   *
   * <pre>
   *                        出口(ABC→XYZ)
   *                          │
   *  P1(ABC 1道) ─────────── SWE ─ SW1 ──────── CROSSOVER_MERGE ── Q1(DEF 1道)
   *                               ╱                ╱
   *  P2(ABC 2道) ──── CROSS_TWO ─╯                ╱
   *                        ╰─────────────────────╯
   * </pre>
   */
  public static SimpleRailGraph crossoverBeforeMerge() {
    NodeId exit = NodeId.of("SURC:ABC:XYZ:1:01");
    NodeId throat = NodeId.of("SWITCHER:world:126:64:0");
    NodeId crossOne = NodeId.of("SWITCHER:world:130:64:0");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, P2, NodeType.STATION, 0, 16, false);
    node(nodes, CROSS_TWO, NodeType.SWITCHER, 114, 16, false);
    node(nodes, throat, NodeType.SWITCHER, 126, 0, false);
    node(nodes, crossOne, NodeType.SWITCHER, 130, 0, false);
    node(nodes, CROSSOVER_MERGE, NodeType.SWITCHER, 230, 0, false);
    node(nodes, Q1, NodeType.STATION, 290, 0, false);
    node(nodes, exit, NodeType.WAYPOINT, 126, -24, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, throat, straight(0, 126, 0), false);
    edge(edges, footprints, Set.of(), throat, crossOne, straight(126, 130, 0), false);
    edge(edges, footprints, Set.of(), throat, exit, line(126, 0, 126, -24), false);
    edge(edges, footprints, Set.of(), P2, CROSS_TWO, straight(0, 114, 16), false);
    edge(edges, footprints, Set.of(), CROSS_TWO, crossOne, line(114, 16, 130, 0), false);
    edge(edges, footprints, Set.of(), crossOne, CROSSOVER_MERGE, straight(130, 230, 0), false);
    List<int[]> around = new ArrayList<>(line(114, 16, 122, 40));
    around.addAll(straight(123, 199, 40));
    around.addAll(line(200, 40, 230, 0));
    edge(edges, footprints, Set.of(), CROSS_TWO, CROSSOVER_MERGE, around, false);
    edge(edges, footprints, Set.of(), CROSSOVER_MERGE, Q1, straight(230, 290, 0), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  /**
   * 正线出了道岔就往南弯去 DEF 1 道；道岔从北面岔出一条很缓的线去 DEF 2 道，出岔后几乎与来路平行。按道岔附近十几格的重心看，缓的那条比弯走的正线更“直”；
   * 轨道方块上正线从东面接出去、与来路（西面）相对，岔线从北面接出去。
   *
   * <pre>
   *                            ┌──────────── Q2(DEF 2道)
   *  P1(ABC 1道) ──────────── 道岔
   *                              ╲
   *                                ╲──── Q1(DEF 1道)
   * </pre>
   */
  public static SimpleRailGraph curvingMainLine() {
    NodeId junction = NodeId.of("SWITCHER:world:100:64:0");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, junction, NodeType.SWITCHER, 100, 0, false);
    node(nodes, Q1, NodeType.STATION, 130, 16, false);
    node(nodes, Q2, NodeType.STATION, 130, -3, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, junction, straight(0, 100, 0), false);
    List<int[]> main = new ArrayList<>(straight(100, 103, 0));
    main.addAll(line(104, 1, 130, 16));
    edge(edges, footprints, Set.of(), junction, Q1, main, false);
    List<int[]> branch = new ArrayList<>(List.of(new int[] {100, 0}, new int[] {100, -1}));
    branch.addAll(line(101, -1, 130, -3));
    edge(edges, footprints, Set.of(), junction, Q2, branch, false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId THROAT_INTERVAL = NodeId.of("SURC:ABC:XYZ:1:01");
  public static final NodeId THROAT_ONE = NodeId.of("SWITCHER:world:60:64:0");
  public static final NodeId THROAT_TWO = NodeId.of("SWITCHER:world:76:64:16");

  /**
   * ABC 的 1、2 道往东经一组渡线出站，复线的两条出口同去 XYZ。1 道站台与渡线道岔之间隔着一个区间点（ABC→XYZ 1 道头一个），2 道站台直接连着道岔。
   *
   * <pre>
   *  P1(ABC 1道) ── THROAT_INTERVAL ── THROAT_ONE ───── ABC→XYZ 1 道
   *                                        ╲
   *  P2(ABC 2道) ──────────────────────── THROAT_TWO ── ABC→XYZ 2 道
   * </pre>
   */
  public static SimpleRailGraph doubleTrackThroat() {
    NodeId exitOne = NodeId.of("SURC:ABC:XYZ:1:02");
    NodeId exitTwo = NodeId.of("SURC:ABC:XYZ:2:01");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, P2, NodeType.STATION, 0, 16, false);
    node(nodes, THROAT_INTERVAL, NodeType.WAYPOINT, 30, 0, false);
    node(nodes, THROAT_ONE, NodeType.SWITCHER, 60, 0, false);
    node(nodes, THROAT_TWO, NodeType.SWITCHER, 76, 16, false);
    node(nodes, exitOne, NodeType.WAYPOINT, 160, 0, false);
    node(nodes, exitTwo, NodeType.WAYPOINT, 160, 16, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, THROAT_INTERVAL, straight(0, 30, 0), false);
    edge(edges, footprints, Set.of(), THROAT_INTERVAL, THROAT_ONE, straight(30, 60, 0), false);
    edge(edges, footprints, Set.of(), THROAT_ONE, exitOne, straight(60, 160, 0), false);
    edge(edges, footprints, Set.of(), P2, THROAT_TWO, straight(0, 76, 16), false);
    edge(edges, footprints, Set.of(), THROAT_TWO, exitTwo, straight(76, 160, 16), false);
    edge(edges, footprints, Set.of(), THROAT_ONE, THROAT_TWO, line(60, 0, 76, 16), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId WINDING_LEFT = NodeId.of("SWITCHER:world:100:64:0");
  public static final NodeId WINDING_CROSSING = NodeId.of("SWITCHER:world:110:64:8");
  public static final NodeId WINDING_RIGHT = NodeId.of("SWITCHER:world:120:64:0");

  /**
   * ABC 与 DEF 之间的复线上一组剪式渡线：中间的交叉点连着两头四个道岔。左边两条进交叉点的区间由弯轨拼成，先拐出去再绕回来，轨道长度是两头直线距离的两倍多（游戏里交叉多由弯轨拼成）。
   *
   * <pre>
   *  P1(ABC 1道) ── WINDING_LEFT ─────────── WINDING_RIGHT ── Q1(DEF 1道)
   *                    (绕)  ╲             ╱
   *                           WINDING_CROSSING
   *                    (绕)  ╱             ╲
   *  P2(ABC 2道) ── 左下道岔 ─────────────── 右下道岔 ── Q2(DEF 2道)
   * </pre>
   */
  public static SimpleRailGraph windingCrossover() {
    NodeId lowerLeft = NodeId.of("SWITCHER:world:100:64:16");
    NodeId lowerRight = NodeId.of("SWITCHER:world:120:64:16");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, P2, NodeType.STATION, 0, 16, false);
    node(nodes, WINDING_LEFT, NodeType.SWITCHER, 100, 0, false);
    node(nodes, lowerLeft, NodeType.SWITCHER, 100, 16, false);
    node(nodes, WINDING_CROSSING, NodeType.SWITCHER, 110, 8, false);
    node(nodes, WINDING_RIGHT, NodeType.SWITCHER, 120, 0, false);
    node(nodes, lowerRight, NodeType.SWITCHER, 120, 16, false);
    node(nodes, Q1, NodeType.STATION, 220, 0, false);
    node(nodes, Q2, NodeType.STATION, 220, 16, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    List<int[]> fromAbove = new ArrayList<>(line(100, 0, 100, 12));
    fromAbove.addAll(straight(101, 106, 12));
    fromAbove.addAll(line(106, 11, 106, 8));
    fromAbove.addAll(straight(107, 110, 8));
    edge(edges, footprints, Set.of(), WINDING_LEFT, WINDING_CROSSING, fromAbove, false);
    List<int[]> fromBelow = new ArrayList<>(line(100, 16, 100, 4));
    fromBelow.addAll(straight(101, 106, 4));
    fromBelow.addAll(line(106, 5, 106, 8));
    fromBelow.addAll(straight(107, 110, 8));
    edge(edges, footprints, Set.of(), lowerLeft, WINDING_CROSSING, fromBelow, false);
    edge(edges, footprints, Set.of(), P1, WINDING_LEFT, straight(0, 100, 0), false);
    edge(edges, footprints, Set.of(), WINDING_LEFT, WINDING_RIGHT, straight(100, 120, 0), false);
    edge(edges, footprints, Set.of(), WINDING_RIGHT, Q1, straight(120, 220, 0), false);
    edge(edges, footprints, Set.of(), P2, lowerLeft, straight(0, 100, 16), false);
    edge(edges, footprints, Set.of(), lowerLeft, lowerRight, straight(100, 120, 16), false);
    edge(edges, footprints, Set.of(), lowerRight, Q2, straight(120, 220, 16), false);
    edge(edges, footprints, Set.of(), WINDING_CROSSING, WINDING_RIGHT, line(110, 8, 120, 0), false);
    edge(edges, footprints, Set.of(), WINDING_CROSSING, lowerRight, line(110, 8, 120, 16), false);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(WORLD, edges.keySet(), footprints));
  }

  public static final NodeId OFFSET_CROSSING = NodeId.of("SWITCHER:world:108:64:1");
  public static final NodeId OFFSET_CROSSING_NEXT = NodeId.of("SWITCHER:world:114:64:3");

  /**
   * ABC 与 DEF 之间复线上的一组剪式渡线，交叉由两个节点组成，实地都紧挨 1 道：两条股道相隔 8 格，交叉的两个节点离 1 道只有 1 格、3 格。
   *
   * <pre>
   *  P1(ABC 1道) ── 上道岔1 ──────────────────── 下道岔1 ── Q1(DEF 1道)
   *                     ╲ OFFSET_CROSSING ── OFFSET_CROSSING_NEXT ╱╲
   *                     ╱                                           ╲
   *  P2(ABC 2道) ── 上道岔2 ──────────────────── 下道岔2 ── Q2(DEF 2道)
   * </pre>
   */
  public static SimpleRailGraph offsetScissors() {
    NodeId upperOne = NodeId.of("SWITCHER:world:100:64:0");
    NodeId upperTwo = NodeId.of("SWITCHER:world:100:64:8");
    NodeId lowerOne = NodeId.of("SWITCHER:world:130:64:0");
    NodeId lowerTwo = NodeId.of("SWITCHER:world:130:64:8");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, P1, NodeType.STATION, 0, 0, false);
    node(nodes, P2, NodeType.STATION, 0, 8, false);
    node(nodes, upperOne, NodeType.SWITCHER, 100, 0, false);
    node(nodes, upperTwo, NodeType.SWITCHER, 100, 8, false);
    node(nodes, OFFSET_CROSSING, NodeType.SWITCHER, 108, 1, false);
    node(nodes, OFFSET_CROSSING_NEXT, NodeType.SWITCHER, 114, 3, false);
    node(nodes, lowerOne, NodeType.SWITCHER, 130, 0, false);
    node(nodes, lowerTwo, NodeType.SWITCHER, 130, 8, false);
    node(nodes, Q1, NodeType.STATION, 200, 0, false);
    node(nodes, Q2, NodeType.STATION, 200, 8, false);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, Set.of(), P1, upperOne, straight(0, 100, 0), false);
    edge(edges, footprints, Set.of(), upperOne, lowerOne, straight(100, 130, 0), false);
    edge(edges, footprints, Set.of(), lowerOne, Q1, straight(130, 200, 0), false);
    edge(edges, footprints, Set.of(), P2, upperTwo, straight(0, 100, 8), false);
    edge(edges, footprints, Set.of(), upperTwo, lowerTwo, straight(100, 130, 8), false);
    edge(edges, footprints, Set.of(), lowerTwo, Q2, straight(130, 200, 8), false);
    edge(edges, footprints, Set.of(), upperOne, OFFSET_CROSSING, line(100, 0, 108, 1), false);
    edge(edges, footprints, Set.of(), upperTwo, OFFSET_CROSSING, line(100, 8, 108, 1), false);
    edge(
        edges,
        footprints,
        Set.of(),
        OFFSET_CROSSING,
        OFFSET_CROSSING_NEXT,
        line(108, 1, 114, 3),
        false);
    edge(edges, footprints, Set.of(), OFFSET_CROSSING, lowerOne, line(108, 1, 130, 0), false);
    edge(edges, footprints, Set.of(), OFFSET_CROSSING_NEXT, lowerTwo, line(114, 3, 130, 8), false);
    edge(edges, footprints, Set.of(), OFFSET_CROSSING_NEXT, lowerOne, line(114, 3, 130, 0), false);
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
