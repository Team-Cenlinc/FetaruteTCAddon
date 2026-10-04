package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StopControlMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Decision;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Input;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Intervention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DriverProtection 保护包络")
class DriverProtectionTest {

  private static final DriverConfig CONFIG = DriverConfig.defaults();
  private static final double SERVICE = 1.0;
  private static final double EMERGENCY = 1.4;
  private static final double REACTION = 0.3;

  private static DriverDirective proceed(double permitted) {
    return new DriverDirective(
        SignalAspect.PROCEED,
        StopControlMode.BRAKING_TO_PLANNED_STOP,
        permitted,
        permitted,
        true,
        OptionalLong.empty(),
        null);
  }

  /** 停车信号；执行层给出的容许速度：硬停为 0，其余取一个宽松值，让保护自己的制动曲线起作用。 */
  private static DriverDirective stop(long distance, StopControlMode mode) {
    double permitted = mode == StopControlMode.HARD_STOP ? 0.0 : 30.0;
    return new DriverDirective(
        SignalAspect.STOP, mode, 0.0, permitted, false, OptionalLong.of(distance), null);
  }

  private static Decision eval(double speed, DriverDirective directive) {
    return eval(speed, directive, 0L, 0.0, false, false);
  }

  private static Decision eval(
      double speed,
      DriverDirective directive,
      long ticksSince,
      double travelled,
      boolean serviceStop,
      boolean latched) {
    return DriverProtection.evaluate(
        new Input(
            speed,
            speed <= 0.05,
            directive,
            ticksSince,
            travelled,
            SERVICE,
            EMERGENCY,
            REACTION,
            serviceStop,
            latched),
        CONFIG);
  }

  @Test
  @DisplayName("还没收到指令：停着不许起步，动着按限制速度")
  void noDirective() {
    Decision stopped = eval(0.0, null);
    assertEquals(0.0, stopped.permittedBps(), 1.0e-9);
    assertTrue(stopped.tractionInhibited());
    assertEquals(Intervention.NONE, stopped.intervention());

    Decision rolling =
        eval(CONFIG.restrictedSpeedBps() + CONFIG.overspeedToleranceBps() + 0.5, null);
    assertEquals(Intervention.SERVICE, rolling.intervention());
  }

  @Test
  @DisplayName("超速分级：容差内不介入、超过容差常用制动、超出比例紧急制动")
  void overspeedLevels() {
    DriverDirective d = proceed(10.0);
    assertEquals(Intervention.NONE, eval(10.5, d).intervention());
    assertEquals(Intervention.SERVICE, eval(11.5, d).intervention());
    assertEquals(Intervention.EMERGENCY, eval(13.0, d).intervention());
  }

  @Test
  @DisplayName("常用制动带回差：降到容许速度以下一段才松开")
  void serviceHysteresis() {
    DriverDirective d = proceed(10.0);
    assertEquals(Intervention.SERVICE, eval(9.8, d, 0L, 0.0, false, true).intervention());
    assertEquals(Intervention.NONE, eval(9.4, d, 0L, 0.0, false, true).intervention());
  }

  @Test
  @DisplayName("到了容许速度切除牵引，低于时允许牵引")
  void tractionCutOffAtPermitted() {
    DriverDirective d = proceed(10.0);
    assertTrue(eval(10.0, d).tractionInhibited());
    assertFalse(eval(9.0, d).tractionInhibited());
  }

  @Test
  @DisplayName("停车信号：按到停车点（减余量）的常用制动曲线限速")
  void stopCurveLimitsPermitted() {
    Decision far = eval(5.0, stop(100L, StopControlMode.BRAKING_TO_PLANNED_STOP));
    double expected =
        DriverProtection.brakingCurveBps(100.0 - CONFIG.stopMarginBlocks(), SERVICE, REACTION);
    assertEquals(expected, far.permittedBps(), 1.0e-9);
    assertEquals(Intervention.NONE, far.intervention());
  }

  @Test
  @DisplayName("停车信号下紧急制动也停不住或已越过停车点：立即停住")
  void clampWhenEmergencyCannotStopInTime() {
    assertEquals(
        Intervention.CLAMP,
        eval(12.0, stop(10L, StopControlMode.BRAKING_TO_PLANNED_STOP)).intervention());
    assertEquals(
        Intervention.CLAMP,
        eval(1.0, stop(3L, StopControlMode.BRAKING_TO_PLANNED_STOP), 0L, 3.5, false, false)
            .intervention());
  }

  @Test
  @DisplayName("闭塞硬停：还来得及停时紧急制动")
  void hardStopIsEmergency() {
    assertEquals(
        Intervention.EMERGENCY, eval(3.0, stop(200L, StopControlMode.HARD_STOP)).intervention());
  }

  @Test
  @DisplayName("停稳时停车信号切除牵引、不介入")
  void stoppedAtStopSignal() {
    Decision decision = eval(0.0, stop(0L, StopControlMode.HARD_STOP));
    assertEquals(Intervention.NONE, decision.intervention());
    assertTrue(decision.tractionInhibited());
  }

  @Test
  @DisplayName("指令过期：先按限制速度，再请求交还")
  void staleDirective() {
    DriverDirective d = proceed(20.0);
    Decision restricted = eval(4.0, d, CONFIG.directiveStaleTicks() + 1L, 0.0, false, false);
    assertEquals(CONFIG.restrictedSpeedBps(), restricted.permittedBps(), 1.0e-9);
    assertFalse(restricted.handbackRequested());

    Decision handback = eval(4.0, d, CONFIG.staleHandbackTicks() + 1L, 0.0, false, false);
    assertTrue(handback.handbackRequested());
    assertFalse(
        eval(0.0, d, CONFIG.staleHandbackTicks() + 1L, 0.0, false, false).handbackRequested(),
        "停着时指令不来是正常的（驻站），不请求交还");
  }

  @Test
  @DisplayName("调度要求停车：容许速度为 0，动着就制动")
  void serviceStopRequest() {
    Decision decision = eval(3.0, proceed(20.0), 0L, 0.0, true, false);
    assertEquals(0.0, decision.permittedBps(), 1.0e-9);
    assertEquals(Intervention.SERVICE, decision.intervention());
    assertTrue(eval(0.5, proceed(20.0), 0L, 0.0, true, false).tractionInhibited());
  }

  @Test
  @DisplayName("制动曲线满足 v·t + v²/(2a) = s")
  void brakingCurveSolvesStoppingDistance() {
    double v = DriverProtection.brakingCurveBps(50.0, 1.2, 0.4);
    assertEquals(50.0, v * 0.4 + v * v / (2.0 * 1.2), 1.0e-9);
    assertEquals(0.0, DriverProtection.brakingCurveBps(-1.0, 1.2, 0.4), 1.0e-9);
  }

  private static Decision evalStation(double speed, double remaining, boolean precise) {
    return evalStation(speed, remaining, precise, false);
  }

  private static Decision evalStation(
      double speed, double remaining, boolean precise, boolean terminal) {
    return DriverProtection.evaluate(
        new Input(
            speed,
            speed <= 0.05,
            proceed(20.0),
            0L,
            0.0,
            SERVICE,
            EMERGENCY,
            REACTION,
            false,
            false,
            remaining,
            precise,
            terminal),
        CONFIG);
  }

  @Test
  @DisplayName("进站曲线：最远停到停车窗口末端")
  void stationCurve() {
    Decision decision = evalStation(3.0, 20.0, false);
    double expected =
        DriverProtection.brakingCurveBps(20.0 + CONFIG.stopSkipBlocks(), SERVICE, REACTION);
    assertEquals(expected, decision.permittedBps(), 1.0e-9);
    assertEquals(Intervention.NONE, decision.intervention());
    assertEquals(Intervention.SERVICE, evalStation(expected + 1.5, 20.0, false).intervention());
  }

  @Test
  @DisplayName("停过头不强制停车：进站曲线最远只许冲到越站阈值，超过曲线用常用制动")
  void stationOverrun() {
    double beyond = -(CONFIG.stopAcceptBlocks() + 1.0);
    assertEquals(
        Intervention.NONE, evalStation(1.0, beyond, true).intervention(), "越过可开门范围但还没到越站阈值");
    double overCurve =
        DriverProtection.brakingCurveBps(CONFIG.stopSkipBlocks() + beyond, SERVICE, REACTION) + 1.5;
    assertEquals(Intervention.SERVICE, evalStation(overCurve, beyond, true).intervention());
    assertEquals(Intervention.NONE, evalStation(0.0, beyond, true).intervention(), "停稳后不再介入");
  }

  @Test
  @DisplayName("越过停车点后余量随之缩短：冲到越站阈值处容许速度为 0，不会低速一直溜下去")
  void overrunAllowanceShrinksPastTheStopPoint() {
    double past = -(CONFIG.stopSkipBlocks() - 2.0);
    assertEquals(
        DriverProtection.brakingCurveBps(2.0, SERVICE, REACTION),
        evalStation(1.0, past, true).permittedBps(),
        1.0e-9);
    assertEquals(0.0, evalStation(1.0, -CONFIG.stopSkipBlocks(), true).permittedBps(), 1.0e-9);
  }

  @Test
  @DisplayName("终点站：最远只许冲到可开门范围，越过即介入")
  void terminalStopsWithinTheAcceptWindow() {
    double expected =
        DriverProtection.brakingCurveBps(20.0 + CONFIG.stopAcceptBlocks(), SERVICE, REACTION);
    assertEquals(expected, evalStation(3.0, 20.0, true, true).permittedBps(), 1.0e-9);
    double past = -CONFIG.stopAcceptBlocks();
    assertEquals(0.0, evalStation(1.0, past, true, true).permittedBps(), 1.0e-9);
    assertTrue(
        evalStation(1.0, past, true, true).intervention() != Intervention.NONE, "冲到可开门范围末端还在动");
  }
}
