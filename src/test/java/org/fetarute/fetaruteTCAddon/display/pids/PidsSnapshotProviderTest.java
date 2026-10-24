package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.api.eta.EtaApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.junit.jupiter.api.Test;

/** 站台屏快照：站牌行与时刻表取消行合并、标出末班车、按车站缓存、失败时沿用旧数据。 */
class PidsSnapshotProviderTest {

  private static final Instant T0 = Instant.parse("2026-09-30T12:00:00Z");
  private static final PidsStationKey CCC = new PidsStationKey("SURN", "CCC");
  private static final Duration HORIZON =
      Duration.ofMinutes(PidsSettings.RenderSettings.DEFAULT.horizonMinutes());
  private static final Duration TTL =
      Duration.ofSeconds(PidsSettings.RenderSettings.DEFAULT.snapshotTtlSeconds());
  private static final UUID ROUTE = UUID.randomUUID();

  private final EtaApi eta = mock(EtaApi.class);
  private final TimetableApi timetables = mock(TimetableApi.class);
  private final RouteApi routes = mock(RouteApi.class);
  private final AtomicReference<Instant> now = new AtomicReference<>(T0);
  private final List<String> logs = new ArrayList<>();
  private final PidsSnapshotProvider provider =
      new PidsSnapshotProvider(
          eta, timetables, routes, PidsSettings::defaults, now::get, logs::add);

  @Test
  void boardAndCancelledRowsAreMergedInTimeOrder() {
    board(
        boardRow(EtaApi.BoardPhase.EN_ROUTE, T0.plusSeconds(120), Optional.of("t1")),
        boardRow(EtaApi.BoardPhase.FORECAST, T0.plusSeconds(600), Optional.empty()));
    departures(
        departure(true, "SURN:S:CCC:2", T0.plusSeconds(300)),
        departure(false, "SURN:S:CCC:1", T0.plusSeconds(400)),
        departure(true, "OTHER:S:CCC:1", T0.plusSeconds(350)));
    when(routes.getRoute(ROUTE)).thenReturn(Optional.of(routeChangingToL2AtCcc()));

    List<PidsRow> rows = provider.snapshot(CCC).rows();

    assertEquals(
        List.of(PidsRow.Status.EN_ROUTE, PidsRow.Status.CANCELLED, PidsRow.Status.PLANNED),
        rows.stream().map(PidsRow::status).toList(),
        "未取消的计划由站牌行给出；别的运营商的同码车站不算");
    assertEquals(Optional.of("t1"), rows.get(0).trainName());
    assertEquals(OptionalLong.of(45L), rows.get(0).delaySeconds());
    PidsRow cancelled = rows.get(1);
    assertEquals("L2", cancelled.lineName(), "按本站所属线路（直通换线后）");
    assertEquals("SURN:L1:R1", cancelled.routeId());
    assertEquals("DDD", cancelled.destination());
    assertEquals(Optional.of("SURN:DDD"), cancelled.destinationId());
    assertEquals("2", cancelled.platform());
    assertEquals(T0.plusSeconds(270), cancelled.expectedAt(), "取消行按计划到达，与运行中列车的到站时刻同一口径");
    assertEquals(1, cancelled.stopSequence());
    assertTrue(cancelled.delaySeconds().isEmpty());
  }

  @Test
  void queriesUseTheConfiguredHorizonAndAWholeDayOfTimetable() {
    board();
    departures();

    provider.snapshot(CCC);

    verify(eta).getBoard("SURN", "CCC", null, HORIZON);
    verify(timetables)
        .departuresAt(
            isNull(),
            eq("CCC"),
            eq(T0.minus(PidsSnapshotProvider.PLANNED_LOOKBACK)),
            eq(PidsSnapshotProvider.TIMETABLE_WINDOW),
            eq(PidsSnapshotProvider.DEPARTURE_LOOKUP_LIMIT));
  }

  @Test
  // 计划到发取一整天，取消行只列显示窗口里的。
  void cancelledRowsBeyondTheHorizonAreNotShown() {
    board();
    departures(
        departure(true, "SURN:S:CCC:2", T0.plusSeconds(300)),
        departure(true, "SURN:S:CCC:2", T0.plus(HORIZON).plusSeconds(60)));
    when(routes.getRoute(ROUTE)).thenReturn(Optional.of(routeChangingToL2AtCcc()));

    assertEquals(1, provider.snapshot(CCC).rows().size());
  }

  @Test
  // 取消行只在计划到达之前显示：查询往前多看的那几分钟只为末班比对晚点车，已过计划到达的取消班次不列。
  void cancelledRowsDisappearOnceTheirPlannedArrivalHasPassed() {
    board();
    departures(
        departure(true, "SURN:S:CCC:2", T0.minusSeconds(60)),
        departure(true, "SURN:S:CCC:2", T0),
        departure(true, "SURN:S:CCC:2", T0.plusSeconds(300)));
    when(routes.getRoute(ROUTE)).thenReturn(Optional.of(routeChangingToL2AtCcc()));

    assertEquals(
        List.of(T0.plusSeconds(270)),
        provider.snapshot(CCC).rows().stream().map(PidsRow::expectedAt).toList(),
        "计划到达已过的不列（计划到达比计划发车早 30 秒）");

    now.set(T0.plusSeconds(270));
    assertTrue(provider.snapshot(CCC).rows().isEmpty(), "计划到发缓存未过期，取快照时照样按此刻筛掉");
  }

  @Test
  void theLastDepartureOfTheServiceDayIsMarkedAsLastTrain() {
    when(timetables.enabled()).thenReturn(true);
    board(
        boardRow(EtaApi.BoardPhase.FORECAST, T0.plusSeconds(270), Optional.empty()),
        boardRow(EtaApi.BoardPhase.FORECAST, T0.plusSeconds(870), Optional.empty()));
    departures(
        departure(false, "SURN:S:CCC:2", T0.plusSeconds(300)),
        departure(false, "SURN:S:CCC:2", T0.plusSeconds(900)),
        departure(true, "SURN:S:CCC:2", T0.plusSeconds(1500)));
    when(routes.getRoute(ROUTE)).thenReturn(Optional.of(routeChangingToL2AtCcc()));

    List<PidsRow> rows = provider.snapshot(CCC).rows();

    assertEquals(
        List.of(false, true, false),
        rows.stream().map(PidsRow::lastTrain).toList(),
        "取消的那班不算，最后一班开出的是 15 分钟后那班");
  }

  @Test
  void lastTrainsAreNotMarkedWhenTimetableRunsAreOff() {
    when(timetables.enabled()).thenReturn(false);
    board(boardRow(EtaApi.BoardPhase.FORECAST, T0.plusSeconds(270), Optional.empty()));
    departures(departure(false, "SURN:S:CCC:2", T0.plusSeconds(300)));
    when(routes.getRoute(ROUTE)).thenReturn(Optional.of(routeChangingToL2AtCcc()));

    assertFalse(provider.snapshot(CCC).rows().get(0).lastTrain());
  }

  @Test
  // 同一车站的多块屏在有效期内共用一次查询。
  void snapshotIsSharedWithinTtl() {
    board();
    departures();

    PidsSnapshot first = provider.snapshot(CCC);
    now.set(T0.plus(TTL).minusMillis(1));
    PidsSnapshot second = provider.snapshot(CCC);
    now.set(T0.plus(TTL));
    PidsSnapshot third = provider.snapshot(CCC);

    assertTrue(first == second);
    assertEquals(T0.plus(TTL), third.takenAt());
    verify(eta, times(2)).getBoard(any(), any(), any(), any());
  }

  @Test
  void stationsAreCachedSeparately() {
    board();
    departures();

    provider.snapshot(CCC);
    provider.snapshot(new PidsStationKey("SURN", "BBB"));

    verify(eta).getBoard("SURN", "CCC", null, HORIZON);
    verify(eta).getBoard("SURN", "BBB", null, HORIZON);
  }

  @Test
  // 查询失败沿用旧行、时间戳记为本次：下一个有效期再重试，不会每块屏每次刷新都重试。
  void failureKeepsThePreviousRowsUntilTheNextTtl() {
    board(boardRow(EtaApi.BoardPhase.EN_ROUTE, T0.plusSeconds(120), Optional.of("t1")));
    departures();
    List<PidsRow> before = provider.snapshot(CCC).rows();
    when(eta.getBoard(any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));

    now.set(T0.plus(TTL));
    PidsSnapshot failed = provider.snapshot(CCC);
    provider.snapshot(CCC);

    assertEquals(before, failed.rows());
    assertEquals(T0.plus(TTL), failed.takenAt());
    assertTrue(
        logs.stream().anyMatch(line -> line.startsWith("PIDS_SNAPSHOT_FAILED station=SURN:CCC")));
    verify(eta, times(2)).getBoard(any(), any(), any(), any());
  }

  @Test
  // 找取消行要扫全部时刻表：站牌每个有效期重取，取消行只在缓存过期或被作废时重扫。
  void cancelledRowsAreRescannedOnlyAfterTheirRefreshOrAnInvalidation() {
    board();
    departures();
    provider.snapshot(CCC);

    now.set(T0.plus(TTL));
    provider.snapshot(CCC);
    verify(eta, times(2)).getBoard(any(), any(), any(), any());
    verify(timetables, times(1)).departuresAt(any(), any(), any(), any(), anyInt());

    provider.invalidateCancellations();
    now.set(T0.plus(TTL).plus(TTL));
    provider.snapshot(CCC);
    verify(timetables, times(2)).departuresAt(any(), any(), any(), any(), anyInt());

    now.set(T0.plus(TTL).plus(TTL).plus(PidsSnapshotProvider.TIMETABLE_REFRESH));
    provider.snapshot(CCC);
    verify(timetables, times(3)).departuresAt(any(), any(), any(), any(), anyInt());
  }

  /** 信号员令越过本站的车：站牌给的是通过行，快照按越站令标出它本应停车。 */
  @Test
  void aPassingRowTheSignallerOrderedIsMarkedSkipped() {
    board(boardRow(EtaApi.BoardPhase.EN_ROUTE, T0.plusSeconds(120), Optional.of("t1"), true));
    assertFalse(provider.snapshot(CCC).rows().get(0).skipped(), "没有越站令：交路里本就通过");

    provider.setSkippedRows(
        (train, routeId, stopSequence) ->
            "t1".equals(train) && "SURN:L1:R1".equals(routeId) && stopSequence == 1);
    provider.invalidateSnapshots();

    PidsRow row = provider.snapshot(CCC).rows().get(0);
    assertTrue(row.passing());
    assertTrue(row.skipped());
  }

  /** 站台屏行上只有交路 ID，越站令按交路 UUID 记：先换成 UUID 再问；查不到交路时不算越站。 */
  @Test
  void skippedRowsLookUpTheRouteUuidByItsId() {
    PidsSnapshotProvider.SkippedRows rows =
        PidsSnapshotProvider.SkippedRows.byRouteUuid(
            routeId -> "SURN:L1:R1".equals(routeId) ? Optional.of(ROUTE) : Optional.empty(),
            (train, routeUuid, index) ->
                "t1".equals(train) && ROUTE.equals(routeUuid) && index == 1);

    assertTrue(rows.skipped("t1", "SURN:L1:R1", 1));
    assertFalse(rows.skipped("t1", "SURN:L1:R1", 2));
    assertFalse(rows.skipped("t1", "SURN:L1:R9", 1));
  }

  /** 信号员取消的晚点车：本站计划到达已过，取消行按取消时的晚点再多显示 5 分钟，候车的人才看得到。 */
  @Test
  void aTripTheSignallerCancelsLateStaysUntilWellAfterItWouldHaveArrived() {
    UUID timetable = UUID.randomUUID();
    // 本站计划到达 T0-60，取消时预计晚点 240 秒：显示到 T0-60+240+300 = T0+480
    board();
    departures(departure(timetable, true, "SURN:S:CCC:2", T0.minusSeconds(30)));
    when(routes.getRoute(ROUTE)).thenReturn(Optional.of(routeChangingToL2AtCcc()));
    assertTrue(provider.snapshot(CCC).rows().isEmpty(), "没有登记：计划到达已过就不列");

    when(timetables.getAssignment("t1")).thenReturn(Optional.of(assignment(timetable, 240)));
    provider.holdCancelled("t1");

    assertEquals(
        List.of(PidsRow.Status.CANCELLED),
        provider.snapshot(CCC).rows().stream().map(PidsRow::status).toList());
    now.set(T0.plusSeconds(479));
    assertEquals(1, provider.snapshot(CCC).rows().size());
    now.set(T0.plusSeconds(490));
    assertTrue(provider.snapshot(CCC).rows().isEmpty());
  }

  /** 取数失败沿用旧行：顺延中的取消行按顺延后的时刻留，不按已过的计划到达丢掉。 */
  @Test
  void aHeldCancelledRowSurvivesAFailedRefresh() {
    board();
    UUID timetable = UUID.randomUUID();
    departures(departure(timetable, true, "SURN:S:CCC:2", T0.minusSeconds(30)));
    when(routes.getRoute(ROUTE)).thenReturn(Optional.of(routeChangingToL2AtCcc()));
    when(timetables.getAssignment("t1")).thenReturn(Optional.of(assignment(timetable, 240)));
    provider.holdCancelled("t1");
    assertEquals(1, provider.snapshot(CCC).rows().size());

    when(eta.getBoard(any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
    now.set(T0.plus(TTL));

    assertEquals(
        List.of(PidsRow.Status.CANCELLED),
        provider.snapshot(CCC).rows().stream().map(PidsRow::status).toList());
  }

  /** 回查窗口按还在保留期的取消登记放宽；登记过了保留期就丢，窗口回到平时。 */
  @Test
  void theLookbackWidensOnlyWhileACancelIsHeld() {
    board();
    UUID timetable = UUID.randomUUID();
    when(timetables.getAssignment("t1")).thenReturn(Optional.of(assignment(timetable, 240)));
    provider.holdCancelled("t1");

    provider.snapshot(CCC);
    verify(timetables)
        .departuresAt(isNull(), eq("CCC"), eq(T0.minusSeconds(240 + 300)), any(), anyInt());

    now.set(T0.plus(PidsSnapshotProvider.CANCELLED_HOLD_RETAIN).plusSeconds(1));
    provider.snapshot(CCC);
    verify(timetables)
        .departuresAt(
            isNull(),
            eq("CCC"),
            eq(now.get().minus(PidsSnapshotProvider.PLANNED_LOOKBACK)),
            any(),
            anyInt());
  }

  @Test
  void stationKeyIsNormalized() {
    assertEquals(CCC, new PidsStationKey(" surn ", "ccc"));
    assertThrows(IllegalArgumentException.class, () -> new PidsStationKey("SURN", " "));
  }

  private void board(EtaApi.BoardRow... rows) {
    when(eta.getBoard(any(), any(), any(), any()))
        .thenReturn(new EtaApi.BoardResult(List.of(rows)));
  }

  private void departures(TimetableApi.Departure... departures) {
    when(timetables.departuresAt(any(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(departures));
  }

  private static EtaApi.BoardRow boardRow(
      EtaApi.BoardPhase phase, Instant at, Optional<String> trainName) {
    return boardRow(phase, at, trainName, false);
  }

  private static EtaApi.BoardRow boardRow(
      EtaApi.BoardPhase phase, Instant at, Optional<String> trainName, boolean passing) {
    return new EtaApi.BoardRow(
        "L1",
        "SURN:L1:R1",
        "DDD",
        Optional.of("SURN:DDD"),
        "DDD",
        Optional.of("SURN:DDD"),
        "DDD",
        Optional.of("SURN:DDD"),
        "1",
        "2m",
        List.of(),
        at.toEpochMilli(),
        phase,
        1,
        passing,
        false,
        false,
        trainName,
        trainName.isPresent() ? OptionalLong.of(45L) : OptionalLong.empty(),
        false,
        List.of(),
        false,
        List.of());
  }

  private static TimetableApi.Departure departure(
      boolean cancelled, String nodeId, Instant plannedDeparture) {
    return departure(UUID.randomUUID(), cancelled, nodeId, plannedDeparture);
  }

  private static TimetableApi.Departure departure(
      UUID timetableId, boolean cancelled, String nodeId, Instant plannedDeparture) {
    return new TimetableApi.Departure(
        timetableId,
        UUID.randomUUID(),
        ROUTE,
        "R1",
        "1001",
        1,
        Optional.of(nodeId),
        plannedDeparture.minusSeconds(30),
        plannedDeparture,
        false,
        LocalDate.of(2026, 9, 30),
        cancelled);
  }

  /** 列车 t1 跑的是 departure() 那一班（车次 1001），预计到下一站晚点若干秒。 */
  private static TimetableApi.TrainAssignment assignment(UUID timetableId, long delaySeconds) {
    return new TimetableApi.TrainAssignment(
        "t1",
        timetableId,
        "1001",
        ROUTE,
        Optional.empty(),
        LocalDate.of(2026, 9, 30),
        T0.minusSeconds(1800),
        0L,
        Optional.of(0),
        Optional.empty(),
        Optional.empty(),
        OptionalLong.of(delaySeconds - 30),
        Optional.of(1),
        Optional.empty(),
        Optional.of("CCC"),
        OptionalLong.of(delaySeconds),
        Optional.empty());
  }

  /** AAA →（本站起换 L2）CCC → DDD 终到。 */
  private static RouteApi.RouteDetail routeChangingToL2AtCcc() {
    return new RouteApi.RouteDetail(
        new RouteApi.RouteInfo(
            ROUTE,
            "SURN:L1:R1",
            "SURN",
            "L1",
            "R1",
            Optional.empty(),
            RouteApi.OperationType.LOCAL),
        List.of("SURN:S:AAA:1", "SURN:S:CCC:2", "SURN:S:DDD:1"),
        List.of(
            stop(0, "SURN:S:AAA:1", Optional.empty()),
            stop(1, "SURN:S:CCC:2", Optional.of(new RouteApi.LineRef("SURN", "L2"))),
            stop(2, "SURN:S:DDD:1", Optional.empty())),
        new RouteApi.TerminalInfo(
            "SURN:S:DDD:1", Optional.of("DDD"), "SURN:S:DDD:1", Optional.of("DDD")),
        120);
  }

  private static RouteApi.StopInfo stop(
      int sequence, String nodeId, Optional<RouteApi.LineRef> lineChange) {
    return new RouteApi.StopInfo(
        sequence,
        nodeId,
        Optional.empty(),
        20,
        RouteApi.PassType.STOP,
        false,
        Optional.empty(),
        Optional.empty(),
        lineChange);
  }
}
