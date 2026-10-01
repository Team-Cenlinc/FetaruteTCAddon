package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** 站台屏快照：站牌行与时刻表取消行合并、按车站缓存、失败时沿用旧数据。 */
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
    assertEquals(T0.plusSeconds(270), cancelled.expectedAt(), "取消行按计划到达");
    assertEquals(1, cancelled.stopSequence());
    assertTrue(cancelled.delaySeconds().isEmpty());
  }

  @Test
  void queriesUseTheConfiguredHorizonAndLookBackForCancellations() {
    board();
    departures();

    provider.snapshot(CCC);

    verify(eta).getBoard("SURN", "CCC", null, HORIZON);
    verify(timetables)
        .departuresAt(
            isNull(),
            eq("CCC"),
            eq(T0.minus(PidsSnapshotProvider.CANCELLED_LOOKBACK)),
            eq(HORIZON.plus(PidsSnapshotProvider.CANCELLED_LOOKBACK)),
            anyInt());
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

    now.set(T0.plus(TTL).plus(TTL).plus(PidsSnapshotProvider.CANCELLED_REFRESH));
    provider.snapshot(CCC);
    verify(timetables, times(3)).departuresAt(any(), any(), any(), any(), anyInt());
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
        false,
        false,
        false,
        trainName,
        trainName.isPresent() ? OptionalLong.of(45L) : OptionalLong.empty());
  }

  private static TimetableApi.Departure departure(
      boolean cancelled, String nodeId, Instant plannedDeparture) {
    return new TimetableApi.Departure(
        UUID.randomUUID(),
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
