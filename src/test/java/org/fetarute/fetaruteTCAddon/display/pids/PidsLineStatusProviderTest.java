package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.event.TimetableTripCancelledEvent;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.display.pids.PidsLineStatusProvider.LineFacts;
import org.fetarute.fetaruteTCAddon.display.pids.PidsLineStatusProvider.ServiceWindow;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus.Condition;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus.Detail;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.junit.jupiter.api.Test;

/** 线路运行状况：判定的先后与阈值、运营时段，以及按列车与车次汇总。 */
class PidsLineStatusProviderTest {

  private static final Instant NOW = Instant.parse("2026-10-02T13:40:00Z");
  private static final UUID MT_ID = UUID.randomUUID();
  private static final PidsDirectory.OperatorLine MT = line(LineApi.LineStatus.ACTIVE);
  private static final UUID ROUTE = UUID.randomUUID();

  private final TrainApi trains = mock(TrainApi.class);
  private final TimetableApi timetables = mock(TimetableApi.class);
  private final PidsLineStatusProvider provider =
      new PidsLineStatusProvider(trains, timetables, new Directory(), null);

  @Test
  void maintenanceOutranksEverythingThenBlockedSections() {
    PidsDirectory.Section section = new PidsDirectory.Section("SURC:SPB", "SURC:HHU");
    PidsDirectory.OperatorLine blocked =
        new PidsDirectory.OperatorLine(
            MT_ID, "SURC", MT.chip(), LineApi.LineStatus.ACTIVE, Optional.of(section));
    PidsDirectory.OperatorLine both =
        new PidsDirectory.OperatorLine(
            MT_ID, "SURC", MT.chip(), LineApi.LineStatus.MAINTENANCE, Optional.of(section));

    assertEquals(
        PidsLineStatus.of(Condition.SUSPENDED),
        classify(both, 3, List.of(900L), 2, List.of()),
        "检修压过一切");
    assertEquals(
        new PidsLineStatus(Condition.PART_SUSPENDED, new Detail.Closed(section)),
        classify(blocked, 3, List.of(900L), 2, List.of()),
        "部分停运压过延误与取消");
  }

  @Test
  void delaysAreGradedByTheWorstTrainAndByHowManyAreLate() {
    assertEquals(
        new PidsLineStatus(Condition.SEVERE_DELAYS, new Detail.Late(10)),
        classify(MT, 4, List.of(0L, 600L, 0L, 0L), 0, List.of()),
        "最晚的车晚 10 分钟");
    assertEquals(
        new PidsLineStatus(Condition.MINOR_DELAYS, new Detail.Late(9)),
        classify(MT, 4, List.of(599L, 0L, 0L, 0L), 0, List.of()),
        "差 1 秒到 10 分钟仍是轻微");
    assertEquals(
        new PidsLineStatus(Condition.SEVERE_DELAYS, new Detail.Late(6)),
        classify(MT, 3, List.of(300L, 360L, 0L), 0, List.of()),
        "过半的车晚 5 分钟以上");
    assertEquals(
        new PidsLineStatus(Condition.MINOR_DELAYS, new Detail.Late(6)),
        classify(MT, 1, List.of(360L), 0, List.of()),
        "只有一辆车晚点不算“过半”");
    assertEquals(
        new PidsLineStatus(Condition.MINOR_DELAYS, new Detail.Late(6)),
        classify(MT, 6, List.of(300L, 360L), 0, List.of()),
        "过半按在途列车算：6 辆里 2 辆晚点，没绑车次的 4 辆算准点");
    assertEquals(
        new PidsLineStatus(Condition.MINOR_DELAYS, new Detail.Late(5)),
        classify(MT, 4, List.of(300L, 300L, 0L, 0L), 0, List.of()),
        "正好一半不算过半");
    assertEquals(
        PidsLineStatus.of(Condition.GOOD),
        classify(MT, 2, List.of(299L, 0L), 0, List.of()),
        "不到 5 分钟不算延误");
  }

  @Test
  void cancellationsComeAfterDelaysAndBeforeGoodService() {
    assertEquals(
        new PidsLineStatus(Condition.MINOR_DELAYS, new Detail.Late(5)),
        classify(MT, 2, List.of(300L), 3, List.of()));
    assertEquals(
        new PidsLineStatus(Condition.CANCELLATIONS, new Detail.Cancelled(3)),
        classify(MT, 0, List.of(), 3, List.of()),
        "没有在途列车也照写取消");
  }

  @Test
  void withoutTrainsTheTimetableWindowDecidesBetweenEndedAndNoTrains() {
    ServiceWindow day = new ServiceWindow(ZoneOffset.UTC, 5 * 3600 + 30 * 60, 23 * 3600);
    ServiceWindow evening = new ServiceWindow(ZoneOffset.UTC, 14 * 3600, 23 * 3600);

    assertEquals(
        PidsLineStatus.of(Condition.NO_TRAINS),
        classify(MT, 0, List.of(), 0, List.of(day)),
        "运营时段内没有车");
    assertEquals(
        new PidsLineStatus(
            Condition.ENDED, new Detail.FirstTrain(Instant.parse("2026-10-02T14:00:00Z"))),
        classify(MT, 0, List.of(), 0, List.of(evening)),
        "当天还没开始");
    assertEquals(
        PidsLineStatus.of(Condition.NO_TRAINS),
        classify(MT, 0, List.of(), 0, List.of(day, evening)),
        "几张时刻表有一张在运营时段内就不算结束");
    assertEquals(
        PidsLineStatus.of(Condition.NO_TRAINS), classify(MT, 0, List.of(), 0, List.of()), "没有时刻表");
    assertEquals(
        PidsLineStatus.of(Condition.GOOD),
        classify(MT, 1, List.of(), 0, List.of(evening)),
        "时段外还有车在跑");
  }

  @Test
  void depotBoundTrainsDoNotOpenTheLineOutsideTheServiceWindow() {
    ServiceWindow evening = new ServiceWindow(ZoneOffset.UTC, 14 * 3600, 23 * 3600);
    ServiceWindow day = new ServiceWindow(ZoneOffset.UTC, 5 * 3600, 23 * 3600);

    assertEquals(
        new PidsLineStatus(
            Condition.ENDED, new Detail.FirstTrain(Instant.parse("2026-10-02T14:00:00Z"))),
        PidsLineStatusProvider.classify(
            MT, new LineFacts(2, 2, List.of(), 0, List.of(evening)), NOW),
        "运营前出库往首站开的车不算在运营");
    assertEquals(
        PidsLineStatus.of(Condition.GOOD),
        PidsLineStatusProvider.classify(MT, new LineFacts(2, 2, List.of(), 0, List.of(day)), NOW),
        "运营时段内出库的车照常算");
    assertEquals(
        PidsLineStatus.of(Condition.GOOD),
        PidsLineStatusProvider.classify(
            MT, new LineFacts(3, 2, List.of(), 0, List.of(evening)), NOW),
        "时段外还有载客的车");
  }

  @Test
  void trainsOnDepotRoutesAreCountedAsPositioning() {
    when(trains.listAllActiveTrains(false))
        .thenReturn(
            List.of(
                train("d1", "SURC:MT:DEP", Optional.of("SURC"), Optional.of("MT"), false),
                train("d2", "SURC:MT:DEP", Optional.empty(), Optional.empty(), false)));
    when(timetables.enabled()).thenReturn(true);
    when(timetables.listPublished())
        .thenReturn(
            List.of(
                new TimetableApi.TimetableInfo(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    MT_ID,
                    "WD",
                    "平日",
                    "UTC",
                    14 * 3600,
                    23 * 3600,
                    List.of(),
                    0,
                    0,
                    NOW)));

    assertEquals(
        new PidsLineStatus(
            Condition.ENDED, new Detail.FirstTrain(Instant.parse("2026-10-02T14:00:00Z"))),
        provider.statusOf(MT, NOW));
  }

  @Test
  void serviceWindowsMayRunPastMidnight() {
    ServiceWindow late = new ServiceWindow(ZoneOffset.UTC, 6 * 3600, 25 * 3600);

    assertEquals(true, late.contains(Instant.parse("2026-10-02T00:30:00Z")), "前一天的运营延到 1 点");
    assertEquals(false, late.contains(Instant.parse("2026-10-02T02:00:00Z")));
    assertEquals(
        Instant.parse("2026-10-02T06:00:00Z"),
        late.nextStart(Instant.parse("2026-10-02T02:00:00Z")));
    assertEquals(
        Instant.parse("2026-10-03T06:00:00Z"),
        late.nextStart(Instant.parse("2026-10-02T06:00:00Z")));
    assertEquals(true, new ServiceWindow(ZoneOffset.UTC, 0, 0).contains(NOW), "时段为空不判结束");
  }

  @Test
  void trainsCountForTheLineTheyShowAndAssignmentsJoinByTrainName() {
    when(trains.listAllActiveTrains(false))
        .thenReturn(
            List.of(
                train("t1", "SURC:MT:R1", Optional.of("surc"), Optional.of("mt"), false),
                train("t2", "SURC:WS:W1", Optional.of("SURC"), Optional.of("MT"), false),
                train("t3", "SURC:MT:R1", Optional.empty(), Optional.empty(), false),
                train("t4", "SURC:MT:R1", Optional.of("SURC"), Optional.of("MT"), true),
                train("t5", "SURC:MT:R1", Optional.of("SURC"), Optional.of("WS"), false)));
    when(timetables.listAssignments())
        .thenReturn(
            List.of(
                assignment("T1", OptionalLong.of(400), OptionalLong.of(100)),
                assignment("t3", OptionalLong.empty(), OptionalLong.of(-50)),
                assignment("t4", OptionalLong.of(900), OptionalLong.empty()),
                assignment("t5", OptionalLong.of(900), OptionalLong.empty())));

    assertEquals(
        new PidsLineStatus(Condition.MINOR_DELAYS, new Detail.Late(6)),
        provider.statusOf(MT, NOW),
        "t1 预计晚 400 秒；直通换成 WS 的 t5 与回库的 t4 不算");
  }

  @Test
  void factsAreReusedForAMinuteAndCancellationsRefreshWhenInvalidated() {
    when(trains.listAllActiveTrains(false)).thenReturn(List.of());
    when(timetables.listAssignments()).thenReturn(List.of());
    when(timetables.cancellations(any(), any())).thenReturn(List.of());

    assertEquals(PidsLineStatus.of(Condition.NO_TRAINS), provider.statusOf(MT, NOW));
    when(timetables.cancellations(any(), any()))
        .thenReturn(List.of(cancelled(ROUTE), cancelled(UUID.randomUUID())));
    assertEquals(
        PidsLineStatus.of(Condition.NO_TRAINS),
        provider.statusOf(MT, NOW.plusSeconds(10)),
        "取消也缓存");
    provider.invalidateCancellations();
    assertEquals(
        new PidsLineStatus(Condition.CANCELLATIONS, new Detail.Cancelled(1)),
        provider.statusOf(MT, NOW.plusSeconds(30)),
        "作废后即时生效；别的线路的交路不算");
    verify(timetables)
        .cancellations(
            NOW.plusSeconds(30).minus(PidsLineStatusProvider.CANCELLED_WINDOW),
            NOW.plusSeconds(30));
    provider.statusOf(MT, NOW.plusSeconds(59));
    verify(trains, times(1)).listAllActiveTrains(false);
    provider.statusOf(MT, NOW.plusSeconds(60));
    verify(trains, times(2)).listAllActiveTrains(false);
  }

  @Test
  void aFailedRefreshKeepsThePreviousFacts() {
    when(trains.listAllActiveTrains(false))
        .thenReturn(List.of(train("t1", "SURC:MT:R1", Optional.empty(), Optional.empty(), false)));
    when(timetables.listAssignments()).thenReturn(List.of());
    assertEquals(PidsLineStatus.of(Condition.GOOD), provider.statusOf(MT, NOW));

    when(trains.listAllActiveTrains(false)).thenThrow(new IllegalStateException("运行时未就绪"));

    assertEquals(PidsLineStatus.of(Condition.GOOD), provider.statusOf(MT, NOW.plusSeconds(61)));
  }

  @Test
  void serviceWindowsComeFromPublishedTimetablesOnlyWhenTimetablesRun() {
    when(trains.listAllActiveTrains(false)).thenReturn(List.of());
    when(timetables.listAssignments()).thenReturn(List.of());
    when(timetables.listPublished())
        .thenReturn(
            List.of(
                new TimetableApi.TimetableInfo(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    MT_ID,
                    "WD",
                    "平日",
                    "UTC",
                    14 * 3600,
                    23 * 3600,
                    List.of(),
                    0,
                    0,
                    NOW)));

    assertEquals(PidsLineStatus.of(Condition.NO_TRAINS), provider.statusOf(MT, NOW), "未按表运行");

    when(timetables.enabled()).thenReturn(true);
    assertEquals(
        new PidsLineStatus(
            Condition.ENDED, new Detail.FirstTrain(Instant.parse("2026-10-02T14:00:00Z"))),
        provider.statusOf(MT, NOW.plusSeconds(60)));
  }

  private static PidsLineStatus classify(
      PidsDirectory.OperatorLine line,
      int running,
      List<Long> delays,
      int cancelled,
      List<ServiceWindow> windows) {
    return PidsLineStatusProvider.classify(
        line, new LineFacts(running, 0, delays, cancelled, windows), NOW);
  }

  private static TimetableApi.CancelledTrip cancelled(UUID route) {
    return new TimetableApi.CancelledTrip(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "1037",
        route,
        LocalDate.of(2026, 10, 2),
        NOW.minusSeconds(600),
        TimetableTripCancelledEvent.Scope.FULL,
        0,
        TimetableTripCancelledEvent.Reason.NOT_DISPATCHED,
        Optional.empty());
  }

  private static PidsDirectory.OperatorLine line(LineApi.LineStatus status) {
    return new PidsDirectory.OperatorLine(
        MT_ID,
        "SURC",
        new PidsView.LineChip("MT", 0xD920D9, new Names("大都会线", "Metropolitan Line")),
        status,
        Optional.empty());
  }

  private static TrainApi.TrainSnapshot train(
      String name,
      String routeId,
      Optional<String> operator,
      Optional<String> line,
      boolean outOfService) {
    return new TrainApi.TrainSnapshot(
        name,
        UUID.randomUUID(),
        routeId,
        Optional.of(routeId),
        Optional.empty(),
        Optional.empty(),
        0,
        TrainApi.Signal.PROCEED,
        0,
        NOW,
        Optional.empty(),
        operator,
        line,
        outOfService);
  }

  private static TimetableApi.TrainAssignment assignment(
      String train, OptionalLong projected, OptionalLong current) {
    return new TimetableApi.TrainAssignment(
        train,
        UUID.randomUUID(),
        "1037",
        ROUTE,
        Optional.empty(),
        LocalDate.of(2026, 10, 2),
        NOW,
        0,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        current,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        projected);
  }

  /** 只认交路所属线路。 */
  private static final class Directory implements PidsDirectory {
    @Override
    public Optional<Names> stationName(String stationId) {
      return Optional.empty();
    }

    @Override
    public Optional<LineStyle> line(String operatorCode, String lineCode) {
      return Optional.empty();
    }

    @Override
    public Optional<RouteApi.OperationType> serviceType(String routeId) {
      return Optional.empty();
    }

    @Override
    public List<PidsView.LineChip> linesServing(PidsStationKey station) {
      return List.of();
    }

    @Override
    public List<PidsView.LineChip> linesServingPlatform(PidsStationKey station, String platform) {
      return List.of();
    }

    @Override
    public Optional<RouteApi.RouteStage> routeStage(String routeId) {
      return Optional.of(
          routeId.endsWith(":DEP") ? RouteApi.RouteStage.CREATE : RouteApi.RouteStage.OPERATION);
    }

    @Override
    public Optional<RouteApi.LineRef> lineOfRoute(UUID routeId) {
      return ROUTE.equals(routeId)
          ? Optional.of(new RouteApi.LineRef("surc", "mt"))
          : Optional.empty();
    }
  }
}
