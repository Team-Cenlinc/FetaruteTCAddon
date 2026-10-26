package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/**
 * 合成的地铁线：双线、每站两端渡线、站咽喉、区间点、中段一截单线、西端车库、东端尽头折返线。
 *
 * <p>用于展开计划缓存的等价性与计时：路径要足够长（几百个图节点）、要有单线走廊与 section、要有道岔与咽喉， 还要有一条在尽头线上原路折返的交路，触发重复反向区间的切分。
 */
final class SyntheticMetroLine {

  final SimpleRailGraph graph;
  final int stations;
  final int singleTrackSegment;

  /** 上行（1 道）各站站台。 */
  final List<NodeId> upRoute;

  /** 下行（2 道）各站站台。 */
  final List<NodeId> downRoute;

  /** 上行到终点后进尽头折返线，再从下行开回：尽头线区间被正反各走一次。 */
  final List<NodeId> turnbackRoute;

  /** 从车库出发的上行交路：车库 → 车库咽喉 → 各站 1 道。 */
  final List<NodeId> depotRoute;

  private final Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
  private final Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
  private final SplittableRandom random;

  private SyntheticMetroLine(int stations, int intervalNodes, long seed) {
    this.stations = stations;
    this.singleTrackSegment = stations / 2;
    this.random = new SplittableRandom(seed);
    for (int s = 0; s < stations; s++) {
      double x = s * 2000.0;
      addNode(switcher(xwu(s), x));
      addNode(switcher(xwd(s), x));
      addNode(throat(twu(s), s, 1, x + 10));
      addNode(throat(twd(s), s, 2, x + 10));
      addNode(station(p1(s), s, 1, x + 40));
      addNode(station(p2(s), s, 2, x + 40));
      addNode(switcher(xeu(s), x + 80));
      addNode(switcher(xed(s), x + 80));
      link(xwu(s), twu(s), 8);
      link(twu(s), p1(s), 30);
      link(p1(s), xeu(s), 40);
      link(xwd(s), twd(s), 8);
      link(twd(s), p2(s), 30);
      link(p2(s), xed(s), 40);
      link(xwu(s), xwd(s), 12);
      link(xeu(s), xed(s), 12);
    }
    for (int s = 0; s + 1 < stations; s++) {
      double x = s * 2000.0 + 100;
      if (s == singleTrackSegment) {
        NodeId west = NodeId.of("SYN:MERGE:" + s + ":W");
        NodeId east = NodeId.of("SYN:MERGE:" + s + ":E");
        addNode(switcher(west, x));
        addNode(switcher(east, x + 1800));
        link(xeu(s), west, 16);
        link(xed(s), west, 16);
        NodeId previous = west;
        for (int k = 1; k <= intervalNodes; k++) {
          NodeId node = NodeId.of("SYN:S" + s + ":S" + (s + 1) + ":1:" + k);
          addNode(interval(node, s, s + 1, 1, k, x + k * 150));
          link(previous, node, 100 + random.nextInt(40));
          previous = node;
        }
        link(previous, east, 16);
        link(east, xwu(s + 1), 16);
        link(east, xwd(s + 1), 16);
        continue;
      }
      NodeId upPrevious = xeu(s);
      NodeId downPrevious = xed(s);
      for (int k = 1; k <= intervalNodes; k++) {
        NodeId up = NodeId.of("SYN:S" + s + ":S" + (s + 1) + ":1:" + k);
        NodeId down = NodeId.of("SYN:S" + (s + 1) + ":S" + s + ":2:" + (intervalNodes + 1 - k));
        addNode(interval(up, s, s + 1, 1, k, x + k * 150));
        addNode(interval(down, s + 1, s, 2, intervalNodes + 1 - k, x + k * 150));
        int length = 100 + random.nextInt(40);
        link(upPrevious, up, length);
        link(downPrevious, down, length);
        upPrevious = up;
        downPrevious = down;
      }
      link(upPrevious, xwu(s + 1), 16);
      link(downPrevious, xwd(s + 1), 16);
    }
    NodeId depotThroat = NodeId.of("SYN:D:DEP:1:THROAT");
    NodeId depot = NodeId.of("SYN:D:DEP:1");
    addNode(
        new SignRailNode(
            depotThroat,
            NodeType.WAYPOINT,
            new Vector(-40, 64, 10),
            Optional.empty(),
            Optional.of(WaypointMetadata.depotThroat("SYN", "DEP", 1, "01"))));
    addNode(
        new SignRailNode(
            depot,
            NodeType.DEPOT,
            new Vector(-80, 64, 10),
            Optional.empty(),
            Optional.of(WaypointMetadata.depot("SYN", "DEP", 1))));
    link(xwd(0), depotThroat, 10);
    link(depotThroat, depot, 60);
    NodeId stub = NodeId.of("SYN:TB");
    addNode(
        new SignRailNode(
            stub,
            NodeType.WAYPOINT,
            new Vector((stations - 1) * 2000.0 + 160, 64, 0),
            Optional.empty(),
            Optional.empty()));
    link(xeu(stations - 1), stub, 70);
    this.graph = new SimpleRailGraph(nodes, edges, Set.of());

    List<NodeId> up = new ArrayList<>();
    List<NodeId> down = new ArrayList<>();
    for (int s = 0; s < stations; s++) {
      up.add(p1(s));
      down.add(p2(stations - 1 - s));
    }
    this.upRoute = List.copyOf(up);
    this.downRoute = List.copyOf(down);
    List<NodeId> turnback = new ArrayList<>(up);
    turnback.add(stub);
    turnback.addAll(down);
    this.turnbackRoute = List.copyOf(turnback);
    List<NodeId> fromDepot = new ArrayList<>();
    fromDepot.add(depot);
    fromDepot.add(depotThroat);
    fromDepot.addAll(up);
    this.depotRoute = List.copyOf(fromDepot);
  }

  static SyntheticMetroLine build(int stations, int intervalNodes, long seed) {
    if (stations < 3 || intervalNodes < 1) {
      throw new IllegalArgumentException("stations >= 3, intervalNodes >= 1");
    }
    return new SyntheticMetroLine(stations, intervalNodes, seed);
  }

  /** 上行区间 {@code s → s+1} 的第 {@code k} 个区间点（单线段同样适用）。 */
  NodeId upInterval(int s, int k) {
    return NodeId.of("SYN:S" + s + ":S" + (s + 1) + ":1:" + k);
  }

  static NodeId p1(int s) {
    return NodeId.of("SYN:S:S" + s + ":1");
  }

  static NodeId p2(int s) {
    return NodeId.of("SYN:S:S" + s + ":2");
  }

  private static NodeId xwu(int s) {
    return NodeId.of("SYN:X:" + s + ":WU");
  }

  private static NodeId xwd(int s) {
    return NodeId.of("SYN:X:" + s + ":WD");
  }

  private static NodeId xeu(int s) {
    return NodeId.of("SYN:X:" + s + ":EU");
  }

  private static NodeId xed(int s) {
    return NodeId.of("SYN:X:" + s + ":ED");
  }

  private static NodeId twu(int s) {
    return NodeId.of("SYN:S:S" + s + ":1:THROAT");
  }

  private static NodeId twd(int s) {
    return NodeId.of("SYN:S:S" + s + ":2:THROAT");
  }

  private void addNode(RailNode node) {
    nodes.put(node.id(), node);
  }

  private void link(NodeId from, NodeId to, int length) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, length, 8.0, true, Optional.empty()));
  }

  private static RailNode switcher(NodeId id, double x) {
    return new SignRailNode(
        id, NodeType.SWITCHER, new Vector(x, 64, 0), Optional.empty(), Optional.empty());
  }

  private static RailNode station(NodeId id, int s, int track, double x) {
    return new SignRailNode(
        id,
        NodeType.STATION,
        new Vector(x, 64, track * 4.0),
        Optional.empty(),
        Optional.of(WaypointMetadata.station("SYN", "S" + s, track)));
  }

  private static RailNode throat(NodeId id, int s, int track, double x) {
    return new SignRailNode(
        id,
        NodeType.WAYPOINT,
        new Vector(x, 64, track * 4.0),
        Optional.empty(),
        Optional.of(WaypointMetadata.stationThroat("SYN", "S" + s, track, "01")));
  }

  private static RailNode interval(NodeId id, int from, int to, int track, int seq, double x) {
    return new SignRailNode(
        id,
        NodeType.WAYPOINT,
        new Vector(x, 64, track * 4.0),
        Optional.empty(),
        Optional.of(
            WaypointMetadata.interval(
                "SYN", "S" + from, "S" + to, track, String.format("%03d", seq))));
  }
}
