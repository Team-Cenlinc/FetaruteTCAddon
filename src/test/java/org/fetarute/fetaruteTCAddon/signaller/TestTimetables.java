package org.fetarute.fetaruteTCAddon.signaller;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;

/** 估工作量用的最小时刻表：一条交路、若干班次，时区 UTC。 */
final class TestTimetables {

  private static final UUID ROUTE = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

  private TestTimetables() {}

  /** 一个停靠点。 */
  static TimetableStop stop(
      int sequence,
      String station,
      String node,
      int arrival,
      int departure,
      RouteStopPassType type) {
    return new TimetableStop(
        sequence, Optional.ofNullable(station), Optional.of(node), arrival, departure, type);
  }

  /**
   * 一份已发布的时刻表。
   *
   * @param stops 交路上的停靠点
   * @param departures 各班次的起点发车时刻（当日秒数）
   */
  static Timetable timetable(List<TimetableStop> stops, int... departures) {
    UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    TimetableRoutePlan plan =
        new TimetableRoutePlan(
            ROUTE,
            "R1",
            RouteOperationType.OPERATION,
            1,
            stops,
            stops.get(0).nodeId().orElseThrow(),
            stops.get(stops.size() - 1).nodeId().orElseThrow(),
            Optional.empty(),
            Optional.empty(),
            false);
    List<TimetableTrip> trips = new ArrayList<>();
    for (int i = 0; i < departures.length; i++) {
      trips.add(
          new TimetableTrip(
              UUID.nameUUIDFromBytes(("trip" + i).getBytes(StandardCharsets.UTF_8)),
              id,
              ROUTE,
              i,
              "T" + i,
              departures[i],
              Optional.empty()));
    }
    return new Timetable(
        id,
        UUID.fromString("00000000-0000-0000-0000-0000000000f2"),
        UUID.fromString("00000000-0000-0000-0000-0000000000f3"),
        UUID.fromString("00000000-0000-0000-0000-0000000000f4"),
        "TT",
        "TT",
        TimetableStatus.PUBLISHED,
        ZoneId.of("UTC"),
        0,
        86_400 + 7200,
        List.of(plan),
        trips,
        List.of(),
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH);
  }
}
