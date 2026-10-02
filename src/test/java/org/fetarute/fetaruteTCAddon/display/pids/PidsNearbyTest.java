package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.junit.jupiter.api.Test;

/** 附近站台：只认站台节点，由近到远，超出半径不算；站台号按数值排序。 */
class PidsNearbyTest {

  private static final PidsScreen.Position SCREEN = new PidsScreen.Position(0, 64, 0);

  private static final List<GraphApi.ApiNode> NODES =
      List.of(
          node("SURC:S:HHU:3", 4, 64, 0),
          node("SURC:S:HHU:10", 8, 64, 0),
          node("SURC:S:HHU:2", 30, 64, 0),
          node("SURC:S:HHU:3:01", 1, 64, 0),
          node("SURC:D:LWN:1", 1, 64, 1),
          node("SURC:S:TPC:1", 20, 64, 0),
          node("SURC:S:FAR:1", 200, 64, 0));

  @Test
  void nearestPlatformIgnoresThroatsDepotsAndFarNodes() {
    PidsNearby nearby = PidsNearby.of(NODES, SCREEN, PidsNearby.RADIUS);

    assertEquals(
        Optional.of(new PidsPlatformNode(new PidsStationKey("SURC", "HHU"), "3")),
        nearby.nearest());
    assertEquals(
        List.of(new PidsStationKey("SURC", "HHU"), new PidsStationKey("SURC", "TPC")),
        nearby.stations(),
        "由近到远、去重，200 格外的不算");
  }

  @Test
  void platformsOfAStationSortNumerically() {
    assertEquals(
        List.of("2", "3", "10"), PidsNearby.platformsOf(NODES, new PidsStationKey("surc", "hhu")));
  }

  @Test
  void emptyWhenNothingIsInRange() {
    List<GraphApi.ApiNode> nodes = new ArrayList<>(List.of(node("SURC:S:FAR:1", 500, 64, 0)));

    assertTrue(PidsNearby.of(nodes, SCREEN, PidsNearby.RADIUS).nearest().isEmpty());
  }

  @Test
  void platformNodesParseOnlyFourSegmentStations() {
    assertEquals(
        Optional.of(new PidsPlatformNode(new PidsStationKey("SURC", "HHU"), "3")),
        PidsPlatformNode.parse("surc:s:hhu:3"));
    assertEquals(
        Optional.of(new PidsPlatformNode(new PidsStationKey("SURC", "HHU"), "1")),
        PidsPlatformNode.parse("SURC:S:HHU:01"),
        "股道号按数值，与到发行里的站台号一致");
    assertEquals("1", PidsPlatformNode.normalize("+1"));
    assertEquals("A", PidsPlatformNode.normalize(" A "));
    assertTrue(PidsPlatformNode.parse("SURC:S:HHU:3:01").isEmpty());
    assertTrue(PidsPlatformNode.parse("SURC:D:LWN:1").isEmpty());
    assertTrue(PidsPlatformNode.parse("SURC:S::1").isEmpty());
    assertEquals(
        List.of("1", "2", "10", "A"),
        new ArrayList<>(List.of("10", "A", "2", "1"))
            .stream().sorted(PidsPlatformNode.PLATFORM_ORDER).toList());
  }

  private static GraphApi.ApiNode node(String id, double x, double y, double z) {
    return new GraphApi.ApiNode(
        id, GraphApi.NodeType.STATION, new GraphApi.Position(x, y, z), Optional.empty());
  }
}
