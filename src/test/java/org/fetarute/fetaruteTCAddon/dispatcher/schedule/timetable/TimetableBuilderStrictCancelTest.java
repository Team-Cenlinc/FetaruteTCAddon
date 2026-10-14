package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;
import org.junit.jupiter.api.Test;

/**
 * 严格模式取消让不掉的班次（{@code --max-cancel}）。
 *
 * <p>路网直链 DEP–A–B–C，我的 RA 每 600 秒一班跑 A→C；已发布邻表在第 2、3、4 个周期里每 60 秒跑一趟 A→B，
 * 无论相位怎么选，这三个周期里的那一班都撞上邻表——邻表挪不动，让车上限为 0，三处都让不掉。取消这三班之后表就干净了。
 */
class TimetableBuilderStrictCancelTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String NEIGHBOR = "CO/OP/L2/L2-TT";

  private final RailGraph graph =
      TimetableTestFixtures.chain(
          List.of(DEP, A, B, C), new int[] {50, 100, 100}, new double[] {10.0, 10.0, 10.0});

  /** 上限 10：取消被挡的三班，间隔不变，表干净，报告逐班列出车次、时刻与原因。 */
  @Test
  void cancelsTheBlockedTripsWithinTheLimit() {
    TimetableBuildResult result = build(true, 10);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertFalse(result.headwayRelaxed());
    assertTrue(result.unabsorbable().isEmpty());
    List<TimetableBuildResult.DroppedTrip> cancelled =
        result.droppedTrips().stream()
            .filter(trip -> trip.reason() == VehicleDutyPlanner.UnassignedReason.CONFLICT_CANCELLED)
            .toList();
    assertEquals(3, cancelled.size(), () -> result.droppedTrips().toString());
    String report = String.join("\n", result.warnings());
    assertTrue(report.contains("取消 3 班让不掉的班次"), report);
    List<String> lines =
        result.warnings().stream().filter(text -> text.startsWith("  · 车次 ")).toList();
    assertEquals(3, lines.size(), report);
    // 车次号取目标间隔原表里的编号：每取消一班会重新编号，按取消时的编号三行会是同一个车次号。
    assertEquals(
        3, lines.stream().map(text -> text.substring(0, text.indexOf('（'))).distinct().count());
    for (TimetableBuildResult.DroppedTrip trip : cancelled) {
      assertTrue(report.contains("route RA 名义 " + trip.departureText()), report);
    }
    assertTrue(report.contains("[邻表]"), report);
  }

  /** 上限 2：取消两班之后还剩一处，失败文案写明"取消 N 班后仍有 M 处"并列出取消之后还剩的；失败结果里的冲突与残余仍是目标间隔原表的（与不取消时相同）， 不混进取消之后的那份。 */
  @Test
  void failsWhenTheLimitIsNotEnough() {
    TimetableBuildResult result = build(true, 2);
    TimetableBuildResult uncancelled = build(true, 0);

    assertFalse(result.success());
    String reason = String.join("\n", result.warnings());
    assertTrue(reason.contains("取消 2 班后仍有 "), reason);
    assertTrue(reason.contains("取消之后仍让不掉的："), reason);
    assertTrue(reason.contains("--max-cancel 2"), reason);
    assertEquals(uncancelled.unabsorbable(), result.unabsorbable());
    assertEquals(uncancelled.conflictsAtTarget(), result.conflictsAtTarget());
  }

  /** 不给 --max-cancel：上限按运营班次的 1% 算、至少 1 班；这张表一小时 7 班，只许取消 1 班，失败文案写明上限是怎么来的。 */
  @Test
  void theDefaultLimitIsAShareOfTheOperatingTrips() {
    TimetableBuildResult result = build(true, TimetableBuildOptions.DEFAULT_MAX_CANCEL_TRIPS);

    assertFalse(result.success());
    String reason = String.join("\n", result.warnings());
    assertTrue(reason.contains("取消 1 班后仍有 "), reason);
    assertTrue(reason.contains("上限 1 班，即运营班次 "), reason);
    assertTrue(reason.contains("的 1%"), reason);
  }

  /** 上限 0：旧行为，不取消、直接失败，文案里没有取消。 */
  @Test
  void zeroMeansTheOldStrictBehaviour() {
    TimetableBuildResult result = build(true, 0);

    assertFalse(result.success());
    String reason = String.join("\n", result.warnings());
    assertTrue(reason.contains("严格模式下不放宽间隔"), reason);
    assertFalse(reason.contains("取消"), reason);
    assertTrue(result.unabsorbable().size() >= 3, reason);
  }

  /** 非严格模式不取消：上限不影响结果。 */
  @Test
  void lenientModeIgnoresTheLimit() {
    TimetableBuildResult withLimit = build(false, 10);
    TimetableBuildResult without = build(false, 0);

    assertEquals(without.timetable(), withLimit.timetable());
    assertTrue(
        withLimit.droppedTrips().stream()
            .noneMatch(
                trip -> trip.reason() == VehicleDutyPlanner.UnassignedReason.CONFLICT_CANCELLED));
  }

  /** 进度按用途分开数完整构建：取消三班就有三次严格取消重排，第三层完整候选另记，没有错峰候选。 */
  @Test
  void progressCountsTheCancelRetries() {
    TimetableBuildProgress progress = TimetableBuildProgress.untracked();
    build(new TimetableBuilder(progress), true, 10);

    TimetableBuildProgress.Snapshot snapshot = progress.snapshot();
    assertEquals(3, snapshot.trials(TimetableBuildProgress.Trial.CANCEL_RETRY));
    assertEquals(0, snapshot.trials(TimetableBuildProgress.Trial.STAGGER_CANDIDATE));
    assertTrue(
        snapshot.fullBuilds()
            >= snapshot.trials(TimetableBuildProgress.Trial.CANCEL_RETRY)
                + snapshot.trials(TimetableBuildProgress.Trial.PHASE_CANDIDATE),
        snapshot::toString);
  }

  /** 串行与并行编出同一张表、同一份取消。 */
  @Test
  void cancellationIsTheSameWithThreads() {
    TimetableBuildResult serial = build(new TimetableBuilder(), true, 10);
    TimetableBuildResult parallel =
        build(new TimetableBuilder(TimetableBuildProgress.untracked(), 4), true, 10);

    assertEquals(serial.timetable(), parallel.timetable());
    assertEquals(serial.droppedTrips(), parallel.droppedTrips());
    assertEquals(serial.warnings(), parallel.warnings());
  }

  // ------------------------------------------------------------------ 夹具

  /** 邻表 NB（A→B）在第 2、3、4 个 600 秒周期里每 60 秒一趟。 */
  private NeighborTimetable neighbor() {
    UUID nb = TimetableTestFixtures.routeId("NB");
    RouteDefinition definition = TimetableTestFixtures.route("NB", List.of(A, B));
    List<RouteStop> stops = TimetableTestFixtures.stops(nb, 2, 0);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(
                graph, TimetableTestFixtures.perEdgeSpeedModel(), definition, stops, Duration.ZERO);
    assertTrue(timing.ok());
    TimetableConflictChecker.RouteProfile profile =
        new TimetableConflictChecker.RouteProfile(
            nb,
            "NB",
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(timing.stops(), stops, definition.waypoints()));
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
    int n = 0;
    for (int cycle = 1; cycle <= 3; cycle++) {
      for (int k = 0; k < 10; k++) {
        n++;
        movements.add(
            new TimetableConflictChecker.Movement(
                String.format("NB-%03d", n), nb, 600 * cycle + 60 * k, Optional.of(NEIGHBOR)));
      }
    }
    return new NeighborTimetable(
        UUID.nameUUIDFromBytes("L2-TT".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        NEIGHBOR,
        Instant.parse("2026-02-28T12:00:00Z"),
        ZONE,
        3,
        false,
        false,
        Map.of(nb, profile),
        movements,
        List.of(),
        List.of());
  }

  private TimetableBuildResult build(boolean strict, int maxCancel) {
    return build(new TimetableBuilder(), strict, maxCancel);
  }

  private TimetableBuildResult build(TimetableBuilder builder, boolean strict, int maxCancel) {
    UUID ra = TimetableTestFixtures.routeId("RA");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID ret = TimetableTestFixtures.routeId("RET");
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(
        new TimetableBuilder.RouteInput(
            ra,
            "RA",
            1,
            TimetableTestFixtures.route("RA", List.of(A, B, C)),
            TimetableTestFixtures.stops(ra, 3, 0),
            Optional.empty()));
    routes.add(
        new TimetableBuilder.RouteInput(
            crt,
            "CRT",
            RouteOperationType.CREATE,
            0,
            TimetableTestFixtures.route("CRT", List.of(DEP, A)),
            TimetableTestFixtures.createStops(crt, 2, DEP),
            Optional.empty()));
    routes.add(
        new TimetableBuilder.RouteInput(
            ret,
            "RET",
            RouteOperationType.RETURN,
            0,
            TimetableTestFixtures.route("RET", List.of(C, DEP)),
            TimetableTestFixtures.returnStops(ret, 2, DEP),
            Optional.empty()));
    TimetableBuildOptions options =
        new TimetableBuildOptions(
                5 * 3600,
                5 * 3600 + 3600,
                Duration.ofSeconds(600),
                Duration.ofSeconds(0),
                new VehicleDutyPlanner.Limits(1, 5400, 60),
                "",
                ZONE,
                Duration.ofSeconds(30),
                strict,
                Map.of(),
                TimetableBuildOptions.Repair.none())
            .withMaxCancelTrips(maxCancel);
    return builder.build(
        new TimetableBuilder.BuildInput(
            UUID.nameUUIDFromBytes("TT".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("CO".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("OP".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("LN".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "TT1",
            "测试表",
            routes,
            graph,
            TimetableTestFixtures.perEdgeSpeedModel(),
            Optional.empty(),
            List.of(neighbor())),
        options,
        BUILT_AT);
  }
}
