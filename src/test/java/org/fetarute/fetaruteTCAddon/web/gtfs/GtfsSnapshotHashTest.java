package org.fetarute.fetaruteTCAddon.web.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GTFS 输入快照的指纹")
class GtfsSnapshotHashTest {

  private static GtfsSnapshot copy(
      GtfsSnapshot snapshot,
      java.time.Instant generatedAt,
      boolean timetableEnabled,
      long platformPlansVersion,
      List<org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable> timetables) {
    return new GtfsSnapshot(
        snapshot.serverId(),
        snapshot.zone(),
        snapshot.publisherName(),
        snapshot.publisherUrl(),
        generatedAt,
        timetableEnabled,
        snapshot.operators(),
        snapshot.lines(),
        snapshot.stations(),
        snapshot.groups(),
        snapshot.platforms(),
        timetables,
        snapshot.routes(),
        snapshot.plannedPlatforms(),
        snapshot.stationsOfPublishedLines(),
        platformPlansVersion);
  }

  @Test
  @DisplayName("同一份内容返回同一个指纹；日期之外的生成时刻不影响")
  void hashIsStableWithinTheSameDay() {
    GtfsSnapshot snapshot = GtfsTestFixtures.snapshot();

    assertEquals(
        GtfsSnapshotHash.of(snapshot),
        GtfsSnapshotHash.of(
            copy(snapshot, snapshot.generatedAt(), true, 0, snapshot.timetables())));
    assertEquals(
        GtfsSnapshotHash.of(snapshot),
        GtfsSnapshotHash.of(
            copy(
                snapshot,
                snapshot.generatedAt().plus(Duration.ofHours(1)),
                true,
                0,
                snapshot.timetables())));
  }

  @Test
  @DisplayName("跨零点、计划股道版本、时刻表启用状态与内容变化都会换指纹")
  void hashReflectsEveryInputThatChangesTheFeed() {
    GtfsSnapshot snapshot = GtfsTestFixtures.snapshot();
    long base = GtfsSnapshotHash.of(snapshot);

    assertNotEquals(
        base,
        GtfsSnapshotHash.of(
            copy(
                snapshot,
                snapshot.generatedAt().plus(Duration.ofDays(1)),
                true,
                0,
                snapshot.timetables())));
    assertNotEquals(
        base,
        GtfsSnapshotHash.of(
            copy(snapshot, snapshot.generatedAt(), true, 1, snapshot.timetables())));
    assertNotEquals(
        base,
        GtfsSnapshotHash.of(
            copy(snapshot, snapshot.generatedAt(), false, 0, snapshot.timetables())));
    assertNotEquals(
        base, GtfsSnapshotHash.of(copy(snapshot, snapshot.generatedAt(), true, 0, List.of())));
  }
}
