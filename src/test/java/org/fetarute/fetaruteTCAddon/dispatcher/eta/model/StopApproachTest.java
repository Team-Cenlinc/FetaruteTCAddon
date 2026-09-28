package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTestFixtures;
import org.junit.jupiter.api.Test;

/**
 * 进站触发规则与运行时同一口径：同站同股道的本体与咽喉、与它们直接相连的道岔都算"已经在进站"；别的股道、区间点与不相连的道岔不算。
 *
 * <p>夹具节点不带元数据，全部靠节点 ID 解析——编表拿到的图在生产上带元数据，这里顺带覆盖了退回解析的那条路径。
 *
 * <p>限速区划分（{@link StopApproach#zones}）由编表运行曲线与运行时控车共用，也在这里验证。
 */
class StopApproachTest {

  private static final String STATION = "OP:S:BBB:1";
  private static final String THROAT = "OP:S:BBB:1:001";
  private static final String OTHER_TRACK_THROAT = "OP:S:BBB:2:001";
  private static final String NEAR_SWITCH = "SWITCHER:Towny:1:64:0";
  private static final String FAR_SWITCH = "SWITCHER:Towny:9:64:0";
  private static final String INTERVAL = "OP:AAA:BBB:1:005";
  private static final String DEPOT = "OP:D:DEP:1";
  private static final String DEPOT_THROAT = "OP:D:DEP:1:001";

  private static RailGraph graph() {
    Map<String, NodeType> nodes = new LinkedHashMap<>();
    nodes.put(FAR_SWITCH, NodeType.SWITCHER);
    nodes.put(INTERVAL, NodeType.WAYPOINT);
    nodes.put(NEAR_SWITCH, NodeType.SWITCHER);
    nodes.put(THROAT, NodeType.WAYPOINT);
    nodes.put(STATION, NodeType.STATION);
    nodes.put(OTHER_TRACK_THROAT, NodeType.WAYPOINT);
    nodes.put(DEPOT_THROAT, NodeType.WAYPOINT);
    nodes.put(DEPOT, NodeType.DEPOT);
    return TimetableTestFixtures.graph(
        nodes,
        List.of(
            new TimetableTestFixtures.Edge(FAR_SWITCH, INTERVAL, 50, 10.0),
            new TimetableTestFixtures.Edge(INTERVAL, NEAR_SWITCH, 50, 10.0),
            new TimetableTestFixtures.Edge(NEAR_SWITCH, THROAT, 10, 10.0),
            new TimetableTestFixtures.Edge(NEAR_SWITCH, OTHER_TRACK_THROAT, 10, 10.0),
            new TimetableTestFixtures.Edge(THROAT, STATION, 10, 10.0),
            new TimetableTestFixtures.Edge(FAR_SWITCH, DEPOT_THROAT, 30, 10.0),
            new TimetableTestFixtures.Edge(DEPOT_THROAT, DEPOT, 10, 10.0)));
  }

  private static final StopApproach.Rule WINDOW_96 = new StopApproach.Rule(96.0, 0, 10.0, 5.0);

  @Test
  void zoneStartsAtTheFirstNodeWithinTheWindow() {
    // A —150— B —60— C —90— 站：B 离站 150 > 96，C 离站 90 ≤ 96，限速区从 C 起。
    List<StopApproach.Zone> zones =
        StopApproach.zones(
            new double[] {0.0, 150.0, 210.0, 300.0},
            new boolean[] {false, false, false, true},
            WINDOW_96);

    assertEquals(List.of(new StopApproach.Zone(210.0, 300.0)), zones);
  }

  @Test
  void eachNodeLooksAtItsNextTriggerAndAdjacentZonesMerge() {
    // 咽喉（触发点）在 250，站在 300：B 离咽喉 50、咽喉离站 50，两段相接合并。起点即使离咽喉很近也不算触发点。
    List<StopApproach.Zone> zones =
        StopApproach.zones(
            new double[] {0.0, 200.0, 250.0, 300.0},
            new boolean[] {true, false, true, true},
            WINDOW_96);

    assertEquals(List.of(new StopApproach.Zone(200.0, 300.0)), zones);
  }

  @Test
  void edgeRuleCoversTheLastEdgesRegardlessOfLength() {
    StopApproach.Rule lastEdge = new StopApproach.Rule(0.0, 1, 10.0, 5.0);

    List<StopApproach.Zone> zones =
        StopApproach.zones(
            new double[] {0.0, 100.0, 400.0}, new boolean[] {false, false, true}, lastEdge);

    assertEquals(List.of(new StopApproach.Zone(100.0, 400.0)), zones);
  }

  /** 车站本体与站咽喉都是车站，车库与车库咽喉是车库，其余（区间点、图里没有的节点）是区间停车点。 */
  @Test
  void classifiesStopNodes() {
    RailGraph graph = graph();

    assertEquals(StopApproach.Kind.STATION, StopApproach.targetOf(graph, id(STATION)).kind());
    assertEquals(StopApproach.Kind.STATION, StopApproach.targetOf(graph, id(THROAT)).kind());
    assertEquals(StopApproach.Kind.DEPOT, StopApproach.targetOf(graph, id(DEPOT)).kind());
    assertEquals(StopApproach.Kind.DEPOT, StopApproach.targetOf(graph, id(DEPOT_THROAT)).kind());
    assertEquals(StopApproach.Kind.WAYPOINT, StopApproach.targetOf(graph, id(INTERVAL)).kind());
    assertEquals(
        StopApproach.Kind.WAYPOINT, StopApproach.targetOf(graph, id("NOT:IN:GRAPH")).kind());
  }

  /** 车站的触发节点：本体、同股道咽喉、与之直接相连的道岔；另一股道的咽喉、区间点、不相连的道岔都不算。 */
  @Test
  void stationIsTriggeredByItsTrackThroatsAndAdjacentSwitches() {
    RailGraph graph = graph();
    StopApproach.Target target = StopApproach.targetOf(graph, id(STATION));

    assertTrue(target.triggeredBy(graph, id(STATION)));
    assertTrue(target.triggeredBy(graph, id(THROAT)));
    assertTrue(target.triggeredBy(graph, id(NEAR_SWITCH)), "与本股道咽喉相连的道岔");
    assertFalse(target.triggeredBy(graph, id(OTHER_TRACK_THROAT)), "另一股道");
    assertFalse(target.triggeredBy(graph, id(FAR_SWITCH)), "不相连的道岔");
    assertFalse(target.triggeredBy(graph, id(INTERVAL)), "区间点");
  }

  /** 车库同理：车库咽喉触发，车站咽喉不触发。 */
  @Test
  void depotIsTriggeredByItsOwnThroat() {
    RailGraph graph = graph();
    StopApproach.Target target = StopApproach.targetOf(graph, id(DEPOT));

    assertTrue(target.triggeredBy(graph, id(DEPOT_THROAT)));
    assertFalse(target.triggeredBy(graph, id(THROAT)));
  }

  /** 区间停车点只认它自己：它没有站点键，也就没有"咽喉"与"相邻道岔"。 */
  @Test
  void waypointStopIsTriggeredOnlyByItself() {
    RailGraph graph = graph();
    StopApproach.Target target = StopApproach.targetOf(graph, id(INTERVAL));

    assertTrue(target.triggeredBy(graph, id(INTERVAL)));
    assertFalse(target.triggeredBy(graph, id(NEAR_SWITCH)));
    assertFalse(target.triggeredBy(graph, id(FAR_SWITCH)));
  }

  private static NodeId id(String value) {
    return NodeId.of(value);
  }
}
