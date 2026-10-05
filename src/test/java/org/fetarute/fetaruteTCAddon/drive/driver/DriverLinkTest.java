package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StopControlMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Decision;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Intervention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DriverLink 控制链路")
class DriverLinkTest {

  private final AtomicLong clock = new AtomicLong(100L);
  private final double[] odometer = {0.0};
  private final DriverLink link =
      new DriverLink(UUID.randomUUID(), "T-1", null, () -> odometer[0], clock::get);

  private static DriverDirective directive(SignalAspect aspect) {
    return new DriverDirective(
        aspect,
        StopControlMode.BRAKING_TO_PLANNED_STOP,
        10.0,
        10.0,
        true,
        OptionalLong.empty(),
        null);
  }

  @Test
  @DisplayName("指令的时间与里程从收到时起算")
  void directiveAgeAndDistance() {
    assertEquals(Long.MAX_VALUE, link.ticksSinceDirective());
    odometer[0] = 5.0;
    link.acceptDirective(directive(SignalAspect.PROCEED));
    clock.addAndGet(7L);
    odometer[0] = 8.5;
    assertEquals(7L, link.ticksSinceDirective());
    assertEquals(3.5, link.travelledSinceDirective(), 1.0e-9);
  }

  @Test
  @DisplayName("调度要求停车：下一条非停车指令解除；已请求交还时不解除")
  void serviceStopReleaseRules() {
    link.requestServiceStop();
    link.acceptDirective(directive(SignalAspect.STOP));
    assertTrue(link.serviceStopRequested());
    link.acceptDirective(directive(SignalAspect.PROCEED));
    assertFalse(link.serviceStopRequested());

    link.requestHandback("deadlock");
    link.acceptDirective(directive(SignalAspect.PROCEED));
    assertTrue(link.serviceStopRequested(), "请求交还后必须停车，新的行车许可不能解除");
    assertEquals("deadlock", link.handbackReason());
    link.requestHandback("admin");
    assertEquals("deadlock", link.handbackReason(), "保留第一次交还的原因");
  }

  @Test
  @DisplayName("介入计数只在介入加重时增加")
  void interventionCounting() {
    link.recordDecision(new Decision(Intervention.SERVICE, 5.0, true, false));
    link.recordDecision(new Decision(Intervention.SERVICE, 5.0, true, false));
    link.recordDecision(new Decision(Intervention.EMERGENCY, 5.0, true, false));
    link.recordDecision(new Decision(Intervention.NONE, 5.0, false, false));
    link.recordDecision(new Decision(Intervention.CLAMP, 0.0, true, false));
    assertEquals(1, link.serviceInterventions());
    assertEquals(1, link.emergencyInterventions());
    assertEquals(1, link.forcedStops());
  }

  @Test
  @DisplayName("ATO 下不由驾驶员物理控车")
  void atoDoesNotControlPhysically() {
    assertTrue(link.controlsPhysically());
    link.setMode(DrivingMode.ATO);
    assertFalse(link.controlsPhysically());
  }

  @Test
  @DisplayName("进站估计：按采样后走过的里程推算；已停过的站在采样刷新前不再当作前方")
  void approachEstimate() {
    org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId node =
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("OP:S:STA:1");
    link.updateApproach(
        node, "station", java.util.OptionalDouble.of(50.0), java.time.Instant.EPOCH, 8.0);
    odometer[0] = 20.0;
    DriverLink.StationTarget target = link.stationTarget().orElseThrow();
    assertEquals(38.0, target.remainingBlocks(), 1.0e-9);
    assertFalse(target.precise());

    link.updateApproach(
        node, "depot", java.util.OptionalDouble.of(50.0), java.time.Instant.ofEpochSecond(1), 8.0);
    assertTrue(link.stationTarget().isEmpty(), "只认车站与区间停车点");

    org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop stop =
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop(
            node, "站", UUID.randomUUID(), new org.bukkit.util.Vector(), null, false, true);
    link.beginStationStop(stop);
    stop.updateOffset(-3.0);
    DriverLink.StationTarget precise = link.stationTarget().orElseThrow();
    assertEquals(3.0, precise.remainingBlocks(), 1.0e-9);
    assertTrue(precise.precise());
    stop.markStopped();
    assertTrue(link.stationTarget().isEmpty(), "停妥后不再有进站目标");
    stop.end();
    link.updateApproach(
        node, "station", java.util.OptionalDouble.of(0.0), java.time.Instant.ofEpochSecond(2), 8.0);
    assertTrue(link.stationTarget().isEmpty(), "刚停过的站在调度采样刷新前不能又变成前方停车点");
    link.updateApproach(
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("OP:S:NXT:1"),
        "station",
        java.util.OptionalDouble.of(400.0),
        java.time.Instant.ofEpochSecond(3),
        8.0);
    assertEquals(408.0, link.stationTarget().orElseThrow().remainingBlocks(), 1.0e-9);
  }

  @Test
  @DisplayName("转人工：清掉 ATO 前的旧许可与等着的确认；转 ATO：清掉防护结论")
  void modeSwitchClearsStaleState() {
    link.acceptDirective(directive(SignalAspect.STOP));
    link.recordDecision(new Decision(Intervention.SERVICE, 0.0, true, false));
    link.setMode(DrivingMode.ATO);
    link.enterManual();
    assertTrue(link.directive() == null, "旧许可的距离与包络早已过时");
    assertTrue(link.lastDecision() == null);
    assertFalse(link.signalConfirm().pending());
    assertTrue(link.controlsPhysically());

    link.recordDecision(new Decision(Intervention.EMERGENCY, 0.0, true, false));
    link.enterAto();
    assertTrue(link.lastDecision() == null);
    assertFalse(link.controlsPhysically());
  }

  @Test
  @DisplayName("站台刚交来停站、偏移还没量出时沿用进站估计；停站结束后不再把这一站当作前方")
  void unmeasuredOffsetFallsBackToEstimate() {
    org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId node =
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("OP:S:STA:1");
    link.updateApproach(
        node, "station", java.util.OptionalDouble.of(20.0), java.time.Instant.EPOCH, 5.0);
    org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop stop =
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop(
            node, "站", UUID.randomUUID(), new org.bukkit.util.Vector(), null, false, true);
    link.beginStationStop(stop);
    DriverLink.StationTarget estimate = link.stationTarget().orElseThrow();
    assertFalse(estimate.precise());
    assertEquals(25.0, estimate.remainingBlocks(), 1.0e-9);

    stop.updateOffset(-2.0);
    assertTrue(link.stationTarget().orElseThrow().precise());

    stop.markStopped();
    stop.end();
    assertTrue(link.stationTarget().isEmpty(), "停过的站在调度采样刷新前就不再是前方停车点");
  }

  @Test
  @DisplayName("停车窗口随链路交给每一次停站")
  void stopWindowFlowsToStationStop() {
    org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopWindow window =
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopWindow(1.0, 3.0, 6.0);
    link.setStopWindow(window);
    org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop stop =
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop(
            org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("OP:S:STA:1"),
            "站",
            UUID.randomUUID(),
            new org.bukkit.util.Vector(),
            null,
            false,
            true);
    link.beginStationStop(stop);
    assertEquals(window, stop.window());
  }

  @Test
  @DisplayName("行车许可还能走多远：没有指令为 0，非停车信号无限，停车信号按授权末端减去已走里程")
  void authorityAhead() {
    assertEquals(0.0, link.authorityAheadBlocks(), 1.0e-9);
    link.acceptDirective(directive(SignalAspect.PROCEED));
    assertTrue(Double.isInfinite(link.authorityAheadBlocks()));
    link.acceptDirective(
        new DriverDirective(
            SignalAspect.STOP,
            StopControlMode.BRAKING_TO_PLANNED_STOP,
            0.0,
            5.0,
            false,
            OptionalLong.of(30L),
            null));
    odometer[0] = 12.0;
    assertEquals(18.0, link.authorityAheadBlocks(), 1.0e-9);
  }

  @Test
  @DisplayName("停车位置标：按车头对准，距离为车头到车站牌子再加标志在牌子前方的距离")
  void headReferencedApproach() {
    org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId node =
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("OP:S:STA:1");
    link.updateApproach(
        node,
        "station",
        java.util.OptionalDouble.of(40.0),
        java.time.Instant.EPOCH,
        12.0,
        org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment.Reference.HEAD);
    odometer[0] = 10.0;
    DriverLink.StationTarget target = link.stationTarget().orElseThrow();
    assertEquals(42.0, target.remainingBlocks(), 1.0e-9);
    assertEquals(
        org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment.Reference.HEAD,
        target.reference());

    link.updateApproach(
        node,
        "station",
        java.util.OptionalDouble.of(40.0),
        java.time.Instant.ofEpochSecond(1),
        6.0);
    assertEquals(
        org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment.Reference.CENTER,
        link.stationTarget().orElseThrow().reference(),
        "没有标志时按列车中心对准");
  }

  @Test
  @DisplayName("越站：停站结束时记一次越站，提示只取一次")
  void skippedStationNoticeIsTakenOnce() {
    org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop stop =
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop(
            org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("OP:S:STA:1"),
            "测试站",
            UUID.randomUUID(),
            new org.bukkit.util.Vector(),
            null,
            false,
            true);
    link.beginStationStop(stop);
    stop.updateOffset(15.0);
    stop.markSkipped();
    assertTrue(link.stationStop().isEmpty());
    assertEquals(java.util.Optional.of("测试站"), link.takeSkippedStation());
    assertTrue(link.takeSkippedStation().isEmpty());
    assertEquals(
        org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment.Window.SKIPPED,
        link.score().stops().get(0).window());
  }

  @Test
  @DisplayName("停在站内结束驾驶：已停妥的这一站在评分时记下，只记一次；还在进站的不记")
  void finalizeRecordsTheStopInProgress() {
    org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop stop =
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop(
            org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("OP:S:STA:1"),
            "终点站",
            UUID.randomUUID(),
            new org.bukkit.util.Vector(),
            null,
            false,
            true);
    link.beginStationStop(stop);
    stop.updateOffset(0.5);
    stop.markStopped();

    assertEquals(1, link.finalizeScore().stopCount());
    assertEquals("终点站", link.score().stops().get(0).station());
    stop.end();
    assertTrue(link.stationStop().isEmpty());
    assertEquals(1, link.finalizeScore().stopCount(), "站台随后收尾不再重复记");

    DriverLink approaching = new DriverLink(UUID.randomUUID(), "T-2", null, () -> 0.0, clock::get);
    approaching.beginStationStop(
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop(
            org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("OP:S:STA:2"),
            "进站中",
            UUID.randomUUID(),
            new org.bukkit.util.Vector(),
            null,
            false,
            true));
    assertEquals(0, approaching.finalizeScore().stopCount());
  }
}
