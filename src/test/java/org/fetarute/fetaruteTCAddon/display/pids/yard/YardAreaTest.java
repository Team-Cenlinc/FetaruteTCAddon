package org.fetarute.fetaruteTCAddon.display.pids.yard;

import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.IA;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.IB;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P2;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW2;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW3;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.T1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.junit.jupiter.api.Test;

/** 站场推导：从本站站台与咽喉出发，经道岔展开，碰到区间点、别的车站与远处道岔就停。 */
class YardAreaTest {

  @Test
  void collectsPlatformsThroatsAndNearbySwitchers() {
    YardArea area = YardArea.of(YardFixtures.graph(), "surc", "abc").orElseThrow();

    assertEquals(Set.of(P1, P2, T1, SW1, SW2), area.nodes(), "站码与运营商不分大小写");
    assertEquals(
        Set.of(
            EdgeId.undirected(SW1, T1),
            EdgeId.undirected(T1, P1),
            EdgeId.undirected(SW1, P2),
            EdgeId.undirected(P1, SW2),
            EdgeId.undirected(P2, SW2)),
        area.edges());
  }

  @Test
  void stopsAtIntervalWaypointsAndNamesTheFarStation() {
    YardArea area = YardArea.of(YardFixtures.graph(), "SURC", "ABC").orElseThrow();

    Map<EdgeId, Optional<String>> exits =
        area.exits().stream()
            .collect(Collectors.toMap(YardArea.Exit::edge, YardArea.Exit::towards));
    assertEquals(
        Map.of(
            EdgeId.undirected(IA, SW1), Optional.of("XYZ"),
            EdgeId.undirected(SW2, IB), Optional.of("DEF")),
        exits,
        "区间点取不是本站的那一端");
  }

  @Test
  void switchersBeyondTheRadiusStayOutside() {
    YardArea area =
        YardArea.of(YardFixtures.graph(false, true, Set.of()), "SURC", "ABC").orElseThrow();

    assertTrue(!area.nodes().contains(SW3), "400 格外的道岔不算站场");
    assertTrue(
        area.exits().stream()
            .anyMatch(
                exit -> exit.edge().equals(EdgeId.undirected(P2, SW3)) && exit.towards().isEmpty()),
        "通向它的区间记为出口，没有去向");
  }

  @Test
  void listsPlatformTracksButNotThroats() {
    YardArea area = YardArea.of(YardFixtures.graph(), "SURC", "ABC").orElseThrow();

    assertEquals(
        Set.of(new YardArea.Track(P1, "1"), new YardArea.Track(P2, "2")),
        Set.copyOf(area.tracks()));
  }

  @Test
  void unknownStationsHaveNoYard() {
    assertTrue(YardArea.of(YardFixtures.graph(), "SURC", "XYZ").isEmpty(), "XYZ 只出现在区间点里");
    assertTrue(YardArea.of(YardFixtures.graph(), "OTHER", "ABC").isEmpty(), "别的运营商的同名站");
  }
}
