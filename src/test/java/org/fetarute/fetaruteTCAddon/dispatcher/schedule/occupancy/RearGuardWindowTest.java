package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntUnaryOperator;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/** 尾部保护的范围：先覆盖车长，车尾所在的边整条算车身，再多留几条边；运行时与编表共用。 */
class RearGuardWindowTest {

  private static final IntUnaryOperator HUNDRED = back -> 100;

  @Test
  void theTailEdgeCountsAsBodyThenTheGuardEdgesFollow() {
    assertEquals(2, RearGuardWindow.retainedEdges(5, HUNDRED, 30L, 1));
    assertEquals(2, RearGuardWindow.retainedEdges(5, HUNDRED, 100L, 1), "恰好一条边长仍是一条");
    assertEquals(3, RearGuardWindow.retainedEdges(5, HUNDRED, 101L, 1));
    assertEquals(1, RearGuardWindow.retainedEdges(5, HUNDRED, 30L, 0));
  }

  @Test
  void edgesAreMeasuredBackFromTheHead() {
    IntUnaryOperator shortThenLong = back -> back == 0 ? 10 : 100;

    assertEquals(3, RearGuardWindow.retainedEdges(5, shortThenLong, 30L, 1));
  }

  @Test
  void notEnoughTrackOrAnUnknownLengthKeepsEverythingAvailable() {
    assertEquals(2, RearGuardWindow.retainedEdges(2, HUNDRED, 500L, 1));
    assertEquals(4, RearGuardWindow.retainedEdges(4, HUNDRED, Long.MAX_VALUE, 0));
    assertEquals(3, RearGuardWindow.retainedEdges(3, back -> -5, 30L, 0), "负长按 0 算，量不满就全留");
  }

  @Test
  void nothingToKeep() {
    assertEquals(0, RearGuardWindow.retainedEdges(0, HUNDRED, 30L, 1));
    assertEquals(0, RearGuardWindow.retainedEdges(5, HUNDRED, 0L, 0));
    assertEquals(1, RearGuardWindow.retainedEdges(5, HUNDRED, 0L, 1));
  }

  @Test
  void onlyWaypointsAndSwitchersAreTrackedBetweenRouteNodes() {
    assertTrue(RearGuardWindow.tracksIntermediateNode(NodeType.WAYPOINT));
    assertTrue(RearGuardWindow.tracksIntermediateNode(NodeType.SWITCHER));
    assertFalse(RearGuardWindow.tracksIntermediateNode(NodeType.STATION));
    assertFalse(RearGuardWindow.tracksIntermediateNode(NodeType.DEPOT));
    assertFalse(RearGuardWindow.tracksIntermediateNode(null));
  }
}
