package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/**
 * 晚点追赶：停站压缩与放宽线路限速。
 *
 * <p>两个手段都必须"只缩短等待"：早到或准点的车一秒都不能多停，也不能提速；判不出来的情形一律退回计划停站与原限速。 用例按这条不对称来组织。
 */
class DelayRecoveryTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:00Z");
  private static final NodeId MIDDLE = NodeId.of("OP:S:BBB:1");
  private static final UUID ROUTE = UUID.randomUUID();
  private static final StationStopCoordinator.Recovery ON =
      new StationStopCoordinator.Recovery(10, 10, 10);

  /** 开门延迟 1 秒（20 tick），与 AutoStation 中途站一致。 */
  private static final long OPEN_DELAY_TICKS = 20L;

  private static RouteDefinition route() {
    return new RouteDefinition(
        RouteId.of("OP:L1:R1"),
        List.of(NodeId.of("OP:S:AAA:1"), MIDDLE, NodeId.of("OP:S:CCC:1")),
        Optional.empty());
  }

  /** 只回答两个只读查询的计划源；出站门控的那条查询不该被停站压缩调用。 */
  private static ScheduledDeparturePlan plan(
      Function<StationStopEvent, Optional<Instant>> boundDeparture, OptionalLong delay) {
    return new ScheduledDeparturePlan() {
      @Override
      public Optional<Instant> scheduledDepartureAt(StationStopEvent event) {
        throw new AssertionError("停站压缩不得触发车次匹配");
      }

      @Override
      public Optional<Instant> boundDepartureAt(StationStopEvent event) {
        return boundDeparture.apply(event);
      }

      @Override
      public OptionalLong currentDelaySeconds(String trainName) {
        return delay;
      }
    };
  }

  private static StationStopCoordinator coordinator(
      List<String> logs, ScheduledDeparturePlan plan, StationStopCoordinator.Recovery recovery) {
    StationStopCoordinator coordinator = TestServices.minimal(logs, () -> T0).stationStops();
    coordinator.setPlan(plan);
    coordinator.setRecovery(recovery);
    return coordinator;
  }

  private static int dwellAt(StationStopCoordinator coordinator, int index) {
    return dwellAt(coordinator, index, 0);
  }

  private static int dwellAt(StationStopCoordinator coordinator, int index, int doorFloorSeconds) {
    return coordinator.dwellSecondsFor(
        "train-A",
        route(),
        Optional.of(ROUTE),
        index,
        MIDDLE,
        20,
        OPEN_DELAY_TICKS,
        doorFloorSeconds);
  }

  // ------------------------------------------------------------------ 停站压缩的算术

  @Test
  void onTimeOrEarlyTrainsKeepThePlannedDwell() {
    assertEquals(20, StationStopCoordinator.compressedDwellSeconds(20, 10, 20), "准点");
    assertEquals(20, StationStopCoordinator.compressedDwellSeconds(20, 10, 90), "早到不延长，等点归计划扣留");
  }

  @Test
  void lateTrainsShortenTheDwellDownToTheMinimum() {
    assertEquals(15, StationStopCoordinator.compressedDwellSeconds(20, 10, 15), "晚 5 秒，停 15 秒刚好赶上");
    assertEquals(10, StationStopCoordinator.compressedDwellSeconds(20, 10, 3), "晚 17 秒，封底 10 秒");
    assertEquals(10, StationStopCoordinator.compressedDwellSeconds(20, 10, -120), "早就过点");
  }

  @Test
  void compressionNeverLengthensAShortPlannedDwell() {
    assertEquals(5, StationStopCoordinator.compressedDwellSeconds(5, 10, -60));
    assertEquals(20, StationStopCoordinator.compressedDwellSeconds(20, 0, -60), "最小停站 0 表示关闭");
  }

  // ------------------------------------------------------------------ 停站压缩的判定分支

  /** 到站时已过计划发车：压到最小停站，并留一行必留审计。 */
  @Test
  void lateArrivalIsCompressedAndAudited() {
    List<String> logs = new ArrayList<>();
    StationStopCoordinator coordinator =
        coordinator(logs, plan(event -> Optional.of(T0), OptionalLong.empty()), ON);

    assertEquals(10, dwellAt(coordinator, 1));
    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.startsWith("SCHEDULED_DWELL_COMPRESSED ")
                        && line.contains("plannedDwellSeconds=20")
                        && line.contains("dwellSeconds=10")),
        logs::toString);
  }

  /** 开门时刻离计划发车还有 21 秒：计划停站 20 秒就赶得上，不压缩也不留痕。 */
  @Test
  void onTimeArrivalIsNotTouched() {
    List<String> logs = new ArrayList<>();
    StationStopCoordinator coordinator =
        coordinator(logs, plan(event -> Optional.of(T0.plusSeconds(21)), OptionalLong.empty()), ON);

    assertEquals(20, dwellAt(coordinator, 1));
    assertTrue(logs.stream().noneMatch(line -> line.startsWith("SCHEDULED_DWELL_COMPRESSED")));
  }

  /** 出库后第一站：车门要 warm-up、开门有重试窗口，不压缩。 */
  @Test
  void firstStopAfterDepotIsNotCompressed() {
    StationStopCoordinator coordinator =
        coordinator(new ArrayList<>(), plan(event -> Optional.of(T0), OptionalLong.empty()), ON);

    assertEquals(
        20,
        coordinator.dwellSecondsFor(
            (com.bergerkiller.bukkit.tc.controller.MinecartGroup) null,
            null,
            20,
            true,
            OPEN_DELAY_TICKS,
            0),
        "首站在解析列车之前就返回计划停站");
  }

  /** 车门开关过程比配置的最小停站还长（长 legacy 关门动画）：压到车门下限为止，审计记的是实际停站。 */
  @Test
  void doorFloorRaisesTheCompressedDwell() {
    List<String> logs = new ArrayList<>();
    StationStopCoordinator coordinator =
        coordinator(logs, plan(event -> Optional.of(T0), OptionalLong.empty()), ON);

    assertEquals(12, dwellAt(coordinator, 1, 12));
    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.startsWith("SCHEDULED_DWELL_COMPRESSED ")
                        && line.contains("dwellSeconds=12")
                        && line.contains("floorSeconds=12")),
        logs::toString);
    assertEquals(20, dwellAt(coordinator, 1, 30), "车门下限高于计划停站时也不延长");
  }

  /** 交路起点与终点不压缩：起点发车归票据与扣留，终点停站就是折返。 */
  @Test
  void originAndTerminalAreNotCompressed() {
    StationStopCoordinator coordinator =
        coordinator(new ArrayList<>(), plan(event -> Optional.of(T0), OptionalLong.empty()), ON);

    assertEquals(20, dwellAt(coordinator, 0));
    assertEquals(20, dwellAt(coordinator, 2));
  }

  /** 没绑车次（计划源答不上来）：按计划停站。 */
  @Test
  void unboundTrainKeepsThePlannedDwell() {
    StationStopCoordinator coordinator =
        coordinator(new ArrayList<>(), plan(event -> Optional.empty(), OptionalLong.empty()), ON);

    assertEquals(20, dwellAt(coordinator, 1));
  }

  /** 没开晚点追赶、或没有计划源：完全透明。 */
  @Test
  void disabledRecoveryOrMissingPlanIsTransparent() {
    StationStopCoordinator disabled =
        coordinator(
            new ArrayList<>(),
            plan(event -> Optional.of(T0), OptionalLong.empty()),
            StationStopCoordinator.Recovery.DISABLED);
    assertEquals(20, dwellAt(disabled, 1));

    StationStopCoordinator noPlan =
        TestServices.minimal(new ArrayList<>(), () -> T0).stationStops();
    noPlan.setRecovery(ON);
    assertEquals(20, dwellAt(noPlan, 1));
  }

  /** 计划源自己抛异常：吞掉、留痕、按计划停站。 */
  @Test
  void planFailureKeepsThePlannedDwellAndIsAudited() {
    List<String> logs = new ArrayList<>();
    StationStopCoordinator coordinator =
        coordinator(
            logs,
            plan(
                event -> {
                  throw new IllegalStateException("boom");
                },
                OptionalLong.empty()),
            ON);

    assertEquals(20, dwellAt(coordinator, 1));
    assertTrue(
        logs.stream().anyMatch(line -> line.startsWith("SCHEDULED_DWELL_PLAN_FAILED ")),
        logs::toString);
  }

  // ------------------------------------------------------------------ 放宽线路限速

  @Test
  void lateTrainGetsTheOverspeedFactorOnceAudited() {
    List<String> logs = new ArrayList<>();
    StationStopCoordinator coordinator =
        coordinator(logs, plan(event -> Optional.empty(), OptionalLong.of(30)), ON);

    assertEquals(1.1, coordinator.lineSpeedFactor("train-A"), 1.0e-9);
    assertEquals(1.1, coordinator.lineSpeedFactor("TRAIN-A"), 1.0e-9);
    assertEquals(
        1L,
        logs.stream()
            .filter(line -> line.startsWith("SCHEDULED_RECOVERY_OVERSPEED "))
            .filter(line -> line.contains("state=engaged"))
            .count(),
        () -> "每 tick 都会问，审计只在进入时留一行：" + logs);
  }

  @Test
  void factorIsReleasedOnceTheDelayIsBelowTheThreshold() {
    List<String> logs = new ArrayList<>();
    OptionalLong[] delay = {OptionalLong.of(30)};
    StationStopCoordinator coordinator = TestServices.minimal(logs, () -> T0).stationStops();
    coordinator.setPlan(
        new ScheduledDeparturePlan() {
          @Override
          public Optional<Instant> scheduledDepartureAt(StationStopEvent event) {
            return Optional.empty();
          }

          @Override
          public OptionalLong currentDelaySeconds(String trainName) {
            return delay[0];
          }
        });
    coordinator.setRecovery(ON);

    assertEquals(1.1, coordinator.lineSpeedFactor("train-A"), 1.0e-9);
    delay[0] = OptionalLong.of(9);
    assertEquals(1.0, coordinator.lineSpeedFactor("train-A"), 1.0e-9, "追到阈值以内即恢复原限速");
    delay[0] = OptionalLong.empty();
    assertEquals(1.0, coordinator.lineSpeedFactor("train-A"), 1.0e-9);
    assertEquals(
        1L, logs.stream().filter(line -> line.contains("state=released")).count(), logs::toString);
  }

  @Test
  void onTimeOrUnboundTrainsKeepTheLineSpeed() {
    assertEquals(
        1.0,
        coordinator(new ArrayList<>(), plan(event -> Optional.empty(), OptionalLong.of(0)), ON)
            .lineSpeedFactor("train-A"),
        1.0e-9);
    assertEquals(
        1.0,
        coordinator(new ArrayList<>(), plan(event -> Optional.empty(), OptionalLong.of(-40)), ON)
            .lineSpeedFactor("train-A"),
        1.0e-9,
        "早到不提速");
    assertEquals(
        1.0,
        coordinator(new ArrayList<>(), plan(event -> Optional.empty(), OptionalLong.empty()), ON)
            .lineSpeedFactor("train-A"),
        1.0e-9);
  }

  @Test
  void overspeedCanBeDisabledSeparately() {
    StationStopCoordinator coordinator =
        coordinator(
            new ArrayList<>(),
            plan(event -> Optional.empty(), OptionalLong.of(300)),
            new StationStopCoordinator.Recovery(10, 0, 10));

    assertEquals(1.0, coordinator.lineSpeedFactor("train-A"), 1.0e-9);
  }

  /** 放宽中途关掉晚点追赶（重载、关按表运行）：下一次查倍率即恢复，并补一行 released，审计成对。 */
  @Test
  void disablingRecoveryReleasesEngagedTrainsWithAnAuditLine() {
    List<String> logs = new ArrayList<>();
    StationStopCoordinator coordinator =
        coordinator(logs, plan(event -> Optional.empty(), OptionalLong.of(30)), ON);
    coordinator.lineSpeedFactor("train-A");

    coordinator.setPlan(null);

    assertEquals(1.0, coordinator.lineSpeedFactor("train-A"), 1.0e-9);
    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.contains("state=released") && line.contains("reason=recovery-disabled")),
        logs::toString);
  }

  /** 列车离开运行时管辖后状态清掉：同名新车再晚点，重新留一行进入审计。 */
  @Test
  void releasedTrainStartsFresh() {
    List<String> logs = new ArrayList<>();
    StationStopCoordinator coordinator =
        coordinator(logs, plan(event -> Optional.empty(), OptionalLong.of(30)), ON);

    coordinator.lineSpeedFactor("train-A");
    coordinator.notifyReleased("train-A", "destroyed");
    coordinator.lineSpeedFactor("train-A");

    assertEquals(
        2L, logs.stream().filter(line -> line.contains("state=engaged")).count(), logs::toString);
  }
}
