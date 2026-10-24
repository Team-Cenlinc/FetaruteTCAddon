package org.fetarute.fetaruteTCAddon.display.pids.yard;

import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.D1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.DEF_TO_XYZ;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.IA;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.IB;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.IC;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.JUNCTION;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P2;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.Q1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW2;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW3;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.T1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.TO_XYZ;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.TO_XYZ_FAR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.junit.jupiter.api.Test;

/** 站场推导：从本站站台与咽喉出发，经道岔展开，碰到区间点、别的车站与远处道岔就停；控制区的几个成员经别的区间连成一片。 */
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
        Set.of(new YardArea.Track(P1, "1", "ABC"), new YardArea.Track(P2, "2", "ABC")),
        Set.copyOf(area.tracks()));
  }

  @Test
  void unknownStationsHaveNoYard() {
    assertTrue(YardArea.of(YardFixtures.graph(), "SURC", "XYZ").isEmpty(), "XYZ 只出现在区间点里");
    assertTrue(YardArea.of(YardFixtures.graph(), "OTHER", "ABC").isEmpty(), "别的运营商的同名站");
  }

  /** 控制区：两端都是成员车站的区间点算区内，几个车站连成一片；不是成员的车库仍是出口。 */
  @Test
  void zonesJoinMemberStationsThroughTheirIntervals() {
    YardArea area =
        YardArea.of(
                YardFixtures.corridor(),
                "SURC",
                List.of(YardArea.Member.station("abc"), YardArea.Member.station("DEF")))
            .orElseThrow();

    assertEquals(Set.of(P1, IB, Q1), area.nodes());
    assertEquals(
        Set.of(new YardArea.Track(P1, "1", "ABC"), new YardArea.Track(Q1, "1", "DEF")),
        Set.copyOf(area.tracks()));
    assertEquals(
        Map.of(
            EdgeId.undirected(D1, P1), Optional.of("DEP"),
            EdgeId.undirected(Q1, IC), Optional.of("GHI")),
        area.exits().stream()
            .collect(Collectors.toMap(YardArea.Exit::edge, YardArea.Exit::towards)));
  }

  /** 车库成员把车库股道带进区内；另一端不是成员车站的区间点仍在区外。 */
  @Test
  void depotMembersBringTheirTracksIn() {
    YardArea area =
        YardArea.of(
                YardFixtures.corridor(),
                "SURC",
                List.of(YardArea.Member.station("ABC"), YardArea.Member.depot("dep")))
            .orElseThrow();

    assertEquals(Set.of(D1, P1), area.nodes());
    assertEquals(
        Map.of(EdgeId.undirected(P1, IB), Optional.of("DEF")),
        area.exits().stream()
            .collect(Collectors.toMap(YardArea.Exit::edge, YardArea.Exit::towards)));
    assertEquals(List.of(new YardArea.Track(P1, "1", "ABC")), area.tracks(), "车库股道不画站台");
    assertEquals(List.of(new YardArea.Track(D1, "1", "DEP")), area.depotTracks(), "库线另列");
  }

  /** 一条线在另一条线的区间中途岔出：经别的区间与道岔把两站连起来，伸向 XYZ 的那段走不回区内，剪掉、记为出口。 */
  @Test
  void zonesBridgeMembersThroughAJunctionOnAnotherInterval() {
    YardArea area =
        YardArea.of(
                YardFixtures.junction(),
                "SURC",
                List.of(YardArea.Member.station("ABC"), YardArea.Member.station("DEF")))
            .orElseThrow();

    assertEquals(Set.of(P1, TO_XYZ, JUNCTION, DEF_TO_XYZ, Q1), area.nodes());
    assertEquals(
        Map.of(EdgeId.undirected(JUNCTION, TO_XYZ_FAR), Optional.of("XYZ")),
        area.exits().stream()
            .collect(Collectors.toMap(YardArea.Exit::edge, YardArea.Exit::towards)));
  }

  /** 只有一个成员时不往别的区间里伸：中途岔出的道岔不算本站。 */
  @Test
  void singleStationsDoNotReachIntoIntervals() {
    YardArea area = YardArea.of(YardFixtures.junction(), "SURC", "ABC").orElseThrow();

    assertEquals(Set.of(P1), area.nodes());
    assertEquals(
        Map.of(EdgeId.undirected(P1, TO_XYZ), Optional.of("XYZ")),
        area.exits().stream()
            .collect(Collectors.toMap(YardArea.Exit::edge, YardArea.Exit::towards)));
  }

  /** 两条线汇合后同去一站：从一站到另一站要在道岔后折返，不算相通，两站各自成片。 */
  @Test
  void mergingLinesDoNotBridgeTheirStations() {
    YardArea area =
        YardArea.of(
                YardFixtures.merge(),
                "SURC",
                List.of(YardArea.Member.station("ABC"), YardArea.Member.station("DEF")))
            .orElseThrow();

    assertEquals(Set.of(P1, Q1), area.nodes());
    assertTrue(area.edges().isEmpty());
  }

  @Test
  void zonesWithoutMembersInTheGraphHaveNoYard() {
    assertTrue(
        YardArea.of(YardFixtures.corridor(), "SURC", List.of(YardArea.Member.depot("NOPE")))
            .isEmpty());
    assertTrue(YardArea.of(YardFixtures.corridor(), "SURC", List.of()).isEmpty());
  }
}
