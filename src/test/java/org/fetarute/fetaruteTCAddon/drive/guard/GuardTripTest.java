package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.junit.jupiter.api.Test;

/** 车掌值乘按车次分趟：车次换了才结算，结算时的成绩包括离站后才采完的出站监视。 */
class GuardTripTest {

  private static final TaskKey TRIP =
      new TaskKey(UUID.randomUUID(), "1023", LocalDate.of(2026, 10, 9));

  @Test
  void aTripEndsWhenTheTrainRunsAnotherTrip() {
    GuardTrip trip = new GuardTrip(TRIP, "L1-A", Instant.EPOCH);
    assertFalse(trip.endedBy(null), "没做过作业的不结算");
    trip.addStop("A", new GuardStopWork(GuardConfig.defaults()));
    assertFalse(trip.endedBy(TRIP));
    assertTrue(trip.endedBy(new TaskKey(TRIP.timetableId(), "1025", TRIP.serviceDate())));
    assertTrue(trip.endedBy(null), "列车不再担当车次（终到收车）");
    GuardTrip untracked = new GuardTrip(null, "", Instant.EPOCH);
    untracked.addStop("A", new GuardStopWork(GuardConfig.defaults()));
    assertFalse(untracked.endedBy(null));
    assertEquals(Optional.empty(), untracked.key());
  }

  /** 出站监视在离站后还要采样：结算时才折成成绩，采样不合格照样扣分。 */
  @Test
  void theScoreIsReadAtSettlement() {
    GuardTrip trip = new GuardTrip(TRIP, "L1-A", Instant.EPOCH);
    GuardStopWork work = new GuardStopWork(GuardConfig.defaults());
    trip.addStop("A", work);
    assertEquals(100, trip.score().evaluate(true).points());
    work.sampleDeparture(false);
    assertEquals(98, trip.score().evaluate(true).points());
  }

  @Test
  void distanceIgnoresJumps() {
    GuardTrip trip = new GuardTrip(TRIP, "", Instant.EPOCH);
    trip.addBlocks(2.5);
    trip.addBlocks(-1.0);
    trip.addBlocks(Double.NaN);
    assertEquals(2.5, trip.blocks(), 1e-9);
  }

  /** 结束原因：中途离开为放弃、被撤下为中断（都按做过的站发），连续超时、漏乘、换端没坐进车尾为未完成（不发）。 */
  @Test
  void endReasonsMapToTaskStates() {
    assertEquals(DriverTask.State.ABANDONED, GuardTrip.stateFor(GuardSession.EndReason.COMMAND));
    assertEquals(DriverTask.State.ABANDONED, GuardTrip.stateFor(GuardSession.EndReason.OFFLINE));
    assertEquals(DriverTask.State.INTERRUPTED, GuardTrip.stateFor(GuardSession.EndReason.ADMIN));
    assertEquals(
        DriverTask.State.INTERRUPTED, GuardTrip.stateFor(GuardSession.EndReason.TRAIN_GONE));
    assertEquals(DriverTask.State.FAILED, GuardTrip.stateFor(GuardSession.EndReason.TIMEOUTS));
    assertEquals(DriverTask.State.FAILED, GuardTrip.stateFor(GuardSession.EndReason.LEFT_BEHIND));
    assertEquals(DriverTask.State.FAILED, GuardTrip.stateFor(GuardSession.EndReason.CAB_CHANGE));
    assertEquals(
        DriverTask.State.INTERRUPTED,
        GuardTrip.stateFor(GuardSession.EndReason.EXAM),
        "考试没过被撤下：考试中的这一趟本来就不发");
    assertTrue(GuardTrip.rewarded(DriverTask.State.COMPLETED));
    assertTrue(GuardTrip.rewarded(DriverTask.State.ABANDONED));
    assertTrue(GuardTrip.rewarded(DriverTask.State.INTERRUPTED));
    assertFalse(GuardTrip.rewarded(DriverTask.State.FAILED));
  }

  /** 站结算时进队列，同一拍里下一站开始也不会丢。 */
  @Test
  void settledStopsQueueUntilDrained() {
    GuardLink link = new GuardLink(UUID.randomUUID(), "T", null, GuardConfig.defaults(), () -> 0L);
    DriverStationStop first = mock(DriverStationStop.class);
    DriverStationStop second = mock(DriverStationStop.class);
    link.beginStationStop(first);
    link.beginStationStop(second);
    List<GuardLink.Settled> drained = link.drainSettled();
    assertEquals(1, drained.size());
    assertEquals(first, drained.get(0).stop());
    assertTrue(link.drainSettled().isEmpty());
    link.settle();
    assertEquals(second, link.drainSettled().get(0).stop());
  }

  /** 越站、开门前就结束的停站不算做过作业；车门放行过、或站台代开过的才算。 */
  @Test
  void onlyWorkedStopsCount() {
    DriverStationStop stop = mock(DriverStationStop.class);
    GuardStopWork work = new GuardStopWork(GuardConfig.defaults());
    work.tick(DriverStationStop.Phase.APPROACH);
    work.tick(DriverStationStop.Phase.OPEN_DOORS);
    assertFalse(new GuardLink.Settled(stop, work).worked(), "开门前就结束");
    work.tick(DriverStationStop.Phase.DWELL);
    assertTrue(new GuardLink.Settled(stop, work).worked());
    when(stop.skipped()).thenReturn(true);
    assertFalse(new GuardLink.Settled(stop, work).worked(), "越站");
    GuardStopWork forced = new GuardStopWork(GuardConfig.defaults());
    for (long i = 0; i <= GuardConfig.defaults().openDoorsTicks(); i++) {
      forced.tick(DriverStationStop.Phase.OPEN_DOORS);
    }
    assertTrue(new GuardLink.Settled(mock(DriverStationStop.class), forced).worked(), "站台代开也算一站");
  }

  @Test
  void theRecordDetailListsEachStop() {
    GuardStopWork work = new GuardStopWork(GuardConfig.defaults());
    work.markWrongDoor();
    work.sampleClosing(true);
    GuardScore score = new GuardScore();
    score.add(GuardScore.Stop.of("A", work));
    JsonObject json =
        JsonParser.parseString(GuardRecordCodec.encode(score, 1234.56)).getAsJsonObject();
    assertEquals(GuardRecordCodec.FORMAT_VERSION, json.get("formatVersion").getAsInt());
    assertEquals(1234.6, json.get("blocks").getAsDouble(), 1e-9);
    JsonObject stop = json.getAsJsonArray("stops").get(0).getAsJsonObject();
    assertEquals("A", stop.get("station").getAsString());
    assertTrue(stop.get("wrongDoor").getAsBoolean());
    assertTrue(stop.get("closingWatch").getAsBoolean());
    assertFalse(stop.has("departureWatch"), "没采样不写");
  }
}
