package org.fetarute.fetaruteTCAddon.stationmaster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.signaller.TestTimetables;
import org.junit.jupiter.api.Test;

/** 站长分股道：按时刻表的工作量与停站时段重叠，从别人手里分走相连的一段。 */
class TrackSplitTest {

  private static final UUID WORLD = UUID.randomUUID();
  private static final StationMasterWorld.Station STATION =
      new StationMasterWorld.Station("OP", "AAA", "甲站");
  private static final Instant FROM = Instant.parse("2026-03-02T08:00:00Z");

  private static List<StationMasterWorld.Track> tracks(int count) {
    return IntStream.rangeClosed(1, count)
        .mapToObj(
            number ->
                new StationMasterWorld.Track(
                    NodeId.of("OP:S:AAA:" + number), number, WORLD, number * 5.0, 64.0, 0.0))
        .toList();
  }

  /** 08:00 起的车次：在 {@code node} 停 {@code arrival}–{@code departure}（相对发车的秒数）。 */
  private static Timetable calls(String node, int arrival, int departure, int... starts) {
    return TestTimetables.timetable(
        List.of(
            TestTimetables.stop(0, "ZZZ", "OP:S:ZZZ:1", 0, 0, RouteStopPassType.STOP),
            TestTimetables.stop(1, "AAA", node, arrival, departure, RouteStopPassType.STOP),
            TestTimetables.stop(2, "YYY", "OP:S:YYY:1", 600, 600, RouteStopPassType.TERMINATE)),
        starts);
  }

  private static TrackWorkload workload(List<Timetable> timetables, int count) {
    return TrackWorkload.of(tracks(count), STATION, timetables, FROM, Duration.ofMinutes(60));
  }

  @Test
  void loadsCountTheTimetabledCallsOfEachTrack() {
    TrackWorkload workload =
        workload(
            List.of(
                calls("OP:S:AAA:1", 60, 90, 8 * 3600, 8 * 3600 + 600, 8 * 3600 + 1200),
                calls("OP:S:AAA:2", 60, 90, 8 * 3600 + 300)),
            3);
    assertEquals(4.0, workload.load(0));
    assertEquals(2.0, workload.load(1));
    assertEquals(1.0, workload.load(2), "没有表定停站的股道也按 1 算");
    assertEquals(7.0, workload.total());
  }

  @Test
  void dynamicCallsAreSharedAmongTheirCandidateTracks() {
    TrackWorkload workload =
        workload(List.of(calls("DYNAMIC:OP:S:AAA:[1:2]", 60, 90, 8 * 3600)), 3);
    assertEquals(1.5, workload.load(0));
    assertEquals(1.5, workload.load(1));
    assertEquals(1.0, workload.load(2));
  }

  @Test
  void withoutTimetabledCallsEveryTrackCountsOnce() {
    TrackWorkload workload = workload(List.of(), 3);
    assertEquals(3.0, workload.total());
    assertEquals(0, workload.overlap(0, 1));
  }

  @Test
  void overlappingDwellsAreCounted() {
    TrackWorkload workload =
        workload(
            List.of(
                calls("OP:S:AAA:1", 60, 120, 8 * 3600),
                calls("OP:S:AAA:2", 90, 150, 8 * 3600),
                calls("OP:S:AAA:3", 200, 260, 8 * 3600)),
            3);
    assertEquals(1, workload.overlap(0, 1));
    assertEquals(1, workload.overlap(1, 0));
    assertEquals(0, workload.overlap(0, 2));
  }

  @Test
  void theSplitTakesAContiguousRunCloseToTheTarget() {
    TrackWorkload workload =
        workload(
            List.of(
                calls("OP:S:AAA:1", 60, 90, 8 * 3600, 8 * 3600 + 900),
                calls("OP:S:AAA:2", 60, 90, 8 * 3600 + 300, 8 * 3600 + 1200),
                calls("OP:S:AAA:3", 60, 90, 8 * 3600 + 600),
                calls("OP:S:AAA:4", 60, 90, 8 * 3600 + 1500)),
            4);
    Optional<List<Integer>> piece =
        TrackSplitter.split(List.of(0, 1, 2, 3), workload, workload.total() / 2);
    assertEquals(Optional.of(List.of(1, 2)), piece, "2、3 道各算 1 再加共 3 列，正好全站的一半");
  }

  @Test
  void theSplitAvoidsGivingOnePersonOverlappingTracks() {
    // 1、2 道同时停车，3 道错开：分成 {1,2}/{3} 会让一人顾不上，分成 {1}/{2,3} 不会
    TrackWorkload workload =
        workload(
            List.of(
                calls("OP:S:AAA:1", 60, 120, 8 * 3600),
                calls("OP:S:AAA:2", 90, 150, 8 * 3600),
                calls("OP:S:AAA:3", 300, 360, 8 * 3600)),
            3);
    Optional<List<Integer>> piece = TrackSplitter.split(List.of(0, 1, 2), workload, 1.5);
    assertTrue(piece.isPresent());
    assertTrue(
        piece.get().equals(List.of(0)) || piece.get().equals(List.of(1, 2)),
        "1、2 道不分给同一人：" + piece.get());
  }

  @Test
  void aSingleTrackCannotBeSplit() {
    assertEquals(Optional.empty(), TrackSplitter.split(List.of(0), workload(List.of(), 1), 0.5));
  }
}
