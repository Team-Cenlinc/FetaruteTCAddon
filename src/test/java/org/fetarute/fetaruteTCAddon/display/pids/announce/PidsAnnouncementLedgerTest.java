package org.fetarute.fetaruteTCAddon.display.pids.announce;

import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.NOW;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.TPC;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.cancelled;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.running;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSettings.BroadcastSettings;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
import org.junit.jupiter.api.Test;

class PidsAnnouncementLedgerTest {

  private static final Duration REMEMBER = Duration.ofMinutes(10);
  private final PidsAnnouncementLedger ledger = new PidsAnnouncementLedger();
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  private static List<PidsAnnouncement> active(PidsRow... rows) {
    return PidsAnnouncement.active(
        new PidsSnapshot(TPC, NOW, List.of(rows)), NOW, BroadcastSettings.DEFAULT);
  }

  private static Instant at(int seconds) {
    return NOW.plusSeconds(seconds);
  }

  private static Optional<String> train(PidsAnnouncementLedger.Delivery delivery) {
    return delivery.actionBar().flatMap(announcement -> announcement.row().trainName());
  }

  @Test
  void eachPlayerHearsAnAnnouncementOnce() {
    List<PidsAnnouncement> arriving = active(running(PidsRow.Status.ARRIVING, "0366", 20));

    assertEquals(Optional.of("0366"), train(ledger.deliver(alice, arriving, at(0), REMEMBER)));
    assertTrue(ledger.deliver(alice, arriving, at(5), REMEMBER).isEmpty());
    assertEquals(
        Optional.of("0366"),
        train(ledger.deliver(bob, arriving, at(5), REMEMBER)),
        "后到站的玩家也会听到仍有效的广播");
  }

  @Test
  void actionBarShowsOneAtATimeAndDropsWhatIsNoLongerActive() {
    List<PidsAnnouncement> three =
        active(
            running(PidsRow.Status.ARRIVING, "0366", 10),
            running(PidsRow.Status.ARRIVING, "0412", 20),
            running(PidsRow.Status.ARRIVING, "0505", 25));

    assertEquals(Optional.of("0366"), train(ledger.deliver(alice, three, at(0), REMEMBER)));
    assertTrue(ledger.deliver(alice, three, at(1), REMEMBER).actionBar().isEmpty(), "上一条还在显示");

    List<PidsAnnouncement> withoutSecond =
        active(
            running(PidsRow.Status.ARRIVING, "0366", 10),
            running(PidsRow.Status.ARRIVING, "0505", 25));
    assertEquals(
        Optional.of("0505"),
        train(ledger.deliver(alice, withoutSecond, at(3), REMEMBER)),
        "0412 轮到时已经不在进站状态，跳过");
  }

  @Test
  void chatIsCappedAndTheRestIsSummarised() {
    List<PidsRow> rows = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      rows.add(cancelled(60 * (i + 1)));
    }
    PidsAnnouncementLedger.Delivery delivery =
        ledger.deliver(alice, active(rows.toArray(PidsRow[]::new)), at(0), REMEMBER);

    assertEquals(PidsAnnouncementLedger.CHAT_LIMIT, delivery.chat().size());
    assertEquals(2, delivery.overflow());
  }

  @Test
  void stillActiveAnnouncementsAreNotRepeatedHoweverShortTheMemory() {
    List<PidsAnnouncement> cancellation = active(cancelled(3600));
    ledger.deliver(alice, cancellation, at(0), Duration.ZERO);

    for (int halfSeconds = 1; halfSeconds <= 20; halfSeconds++) {
      assertTrue(
          ledger
              .deliver(alice, cancellation, NOW.plusMillis(500L * halfSeconds), Duration.ZERO)
              .isEmpty(),
          "一直在站内、广播一直有效：dedupe-seconds 为 0 也不能每半秒重播");
    }
    assertTrue(
        ledger.deliver(alice, cancellation, at(120), Duration.ZERO).isEmpty(),
        "服务器卡顿、两次检查隔得比记忆时长还久，也算连续有效");
  }

  @Test
  void leavingTheStationLetsTheMemoryRunOut() {
    List<PidsAnnouncement> cancellation = active(cancelled(3600));
    ledger.deliver(alice, cancellation, at(0), REMEMBER);
    ledger.deliver(alice, List.of(), at(1), REMEMBER);

    assertTrue(ledger.deliver(alice, cancellation, at(300), REMEMBER).isEmpty(), "离站不足记忆时长，回来不重播");
    ledger.deliver(alice, List.of(), at(301), REMEMBER);
    assertEquals(
        1,
        ledger.deliver(alice, cancellation, at(301 + 601), REMEMBER).chat().size(),
        "离站超过记忆时长后回来，重新播报");
  }

  @Test
  void arrivalsAreForgottenSoonAfterTheTrainHasArrived() {
    List<PidsAnnouncement> arriving = active(running(PidsRow.Status.ARRIVING, "0366", 20));
    ledger.deliver(alice, arriving, at(0), REMEMBER);
    ledger.deliver(alice, List.of(), at(30), REMEMBER);

    assertTrue(ledger.deliver(alice, arriving, at(60), REMEMBER).isEmpty(), "进站状态的抖动不重播");
    ledger.deliver(alice, List.of(), at(61), REMEMBER);
    assertEquals(
        Optional.of("0366"),
        train(
            ledger.deliver(
                alice,
                arriving,
                at(61).plus(PidsAnnouncementLedger.ACTION_BAR_MEMORY).plusSeconds(1),
                REMEMBER)),
        "循环运行的同一列车几分钟后再到，照常播报（不受 600 秒的 dedupe-seconds 约束）");
  }

  @Test
  void soundVolumeGrowsWithDistanceSoTheWholeStationCanHearIt() {
    assertEquals(0.8f, PidsAnnouncer.soundVolume(4));
    assertEquals(3.0f, PidsAnnouncer.soundVolume(24), 1e-6);
  }
}
