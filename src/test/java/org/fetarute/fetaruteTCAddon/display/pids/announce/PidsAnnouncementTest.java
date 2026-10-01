package org.fetarute.fetaruteTCAddon.display.pids.announce;

import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.NOW;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.TPC;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.cancelled;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.row;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.running;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSettings.BroadcastSettings;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSettings.BroadcastTriggers;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
import org.junit.jupiter.api.Test;

class PidsAnnouncementTest {

  private static List<PidsAnnouncement.Kind> kinds(BroadcastSettings settings, PidsRow... rows) {
    return PidsAnnouncement.active(new PidsSnapshot(TPC, NOW, List.of(rows)), NOW, settings)
        .stream()
        .map(PidsAnnouncement::kind)
        .toList();
  }

  private static List<PidsAnnouncement.Kind> kinds(PidsRow... rows) {
    return kinds(BroadcastSettings.DEFAULT, rows);
  }

  @Test
  void arrivingStatusOrWithinTheLeadIsAnnounced() {
    assertEquals(
        List.of(PidsAnnouncement.Kind.ARRIVING),
        kinds(running(PidsRow.Status.ARRIVING, "0366", 45)),
        "进站状态随时播报");
    assertEquals(
        List.of(PidsAnnouncement.Kind.ARRIVING),
        kinds(running(PidsRow.Status.EN_ROUTE, "0366", 30)),
        "预计 30 秒内到达");
    assertEquals(List.of(), kinds(running(PidsRow.Status.EN_ROUTE, "0366", 31)));
    assertEquals(List.of(), kinds(running(PidsRow.Status.BOARDING, "0366", 0)), "已经停在站台上不再播报进站");
    assertEquals(List.of(), kinds(running(PidsRow.Status.PENDING, null, 10)), "票据没有列车名，身份不稳定，不播报");
  }

  @Test
  void passingTrainIsAWarningNotAnArrival() {
    assertEquals(
        List.of(PidsAnnouncement.Kind.PASSING),
        kinds(row(PidsRow.Status.ARRIVING, "0471", 20, 400, true, false, false)),
        "通过车不播晚点");
  }

  @Test
  void cancelledAndSeverelyLateGoToChat() {
    List<PidsAnnouncement.Kind> kinds =
        kinds(
            cancelled(-60),
            row(PidsRow.Status.EN_ROUTE, "0366", 600, 320, false, false, false),
            row(PidsRow.Status.EN_ROUTE, "0412", 900, 120, false, false, false));

    assertEquals(List.of(PidsAnnouncement.Kind.CANCELLED, PidsAnnouncement.Kind.DELAYED), kinds);
    kinds.forEach(kind -> assertEquals(PidsAnnouncement.Channel.CHAT, kind.channel(), kind.name()));
  }

  private static PidsRow trip(
      PidsRow.Status status, String train, String routeId, int stopSequence, String platform) {
    return new PidsRow(
        status,
        "MT",
        routeId,
        "HHU",
        Optional.of("SURC:HHU"),
        platform,
        NOW.plusSeconds(600),
        OptionalLong.of(400),
        stopSequence,
        false,
        false,
        false,
        Optional.ofNullable(train));
  }

  private static List<String> keys(PidsRow... rows) {
    return PidsAnnouncement.active(
            new PidsSnapshot(TPC, NOW, List.of(rows)), NOW, BroadcastSettings.DEFAULT)
        .stream()
        .map(PidsAnnouncement::key)
        .toList();
  }

  @Test
  void keysTellApartTrainsRoutesStopsAndCancelledTrips() {
    List<String> keys =
        keys(
            trip(PidsRow.Status.ARRIVING, "0366", "SURC:MT:MT-3N", 4, "2"),
            trip(PidsRow.Status.ARRIVING, "0366", "SURC:MT:MT-3S", 4, "2"),
            trip(PidsRow.Status.ARRIVING, "0366", "SURC:MT:MT-3N", 7, "2"),
            trip(PidsRow.Status.ARRIVING, "0412", "SURC:MT:MT-3N", 4, "2"),
            cancelled(60),
            cancelled(360));

    // 前四行各有进站与晚点两条，取消两条：同一列车换交路（往返对称时停靠序号相同）、环线第二次停靠都不能共用一个键
    assertEquals(10, keys.size());
    assertEquals(10, keys.stream().distinct().count());
  }

  @Test
  void platformChangeRepeatsTheArrivalButNotTheDelay() {
    List<String> before = keys(trip(PidsRow.Status.ARRIVING, "0366", "SURC:MT:MT-3N", 4, "1"));
    List<String> after = keys(trip(PidsRow.Status.ARRIVING, "0366", "SURC:MT:MT-3N", 4, "3"));

    assertNotEquals(before.get(0), after.get(0), "动态站台选台后改了站台，进站广播重播一次");
    assertEquals(before.get(1), after.get(1), "晚点只播一次");
  }

  @Test
  void cancelledKeyDoesNotDependOnWhetherTheRouteWasFound() {
    PidsRow found = cancelled(60);
    PidsRow notFound =
        new PidsRow(
            PidsRow.Status.CANCELLED,
            "-",
            "MT-3N",
            "-",
            Optional.empty(),
            "2",
            found.expectedAt(),
            OptionalLong.empty(),
            found.stopSequence(),
            false,
            false,
            false,
            Optional.empty());

    assertEquals(keys(found), keys(notFound));
  }

  @Test
  void outOfServiceTrainsAreNotAnnouncedAsLate() {
    assertEquals(
        List.of(), kinds(row(PidsRow.Status.EN_ROUTE, "0366", 600, 900, false, false, true)));
  }

  @Test
  void disabledTriggersAreSkipped() {
    BroadcastSettings quiet =
        new BroadcastSettings(
            true, 32, 600, 30, new BroadcastTriggers(false, true, false, true), true, true);

    assertEquals(
        List.of(), kinds(quiet, running(PidsRow.Status.ARRIVING, "0366", 20), cancelled(60)));
  }
}
