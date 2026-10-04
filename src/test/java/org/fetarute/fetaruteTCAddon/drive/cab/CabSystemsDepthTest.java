package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.junit.jupiter.api.Test;

/** 电空复合制动、机车制动管与车上故障在 {@link CabSystems} 里的综合效果。 */
class CabSystemsDepthTest {

  private static final CabConfig CONFIG = CabConfig.defaults();
  private static final double DT = 0.05;
  private static final double KMH = 1.0 / 3.6;
  private static final double FAST = 60 * KMH;
  private static final double SLOW = 10 * KMH;

  private static final CabVehicle EMU = new CabVehicle(DriveMode.MU, true, 6, 40 * KMH);
  private static final CabVehicle DMU = new CabVehicle(DriveMode.MU, false, 3, 30 * KMH);
  private static final CabVehicle LOCO = new CabVehicle(DriveMode.LOCO, false, 6, 25 * KMH);

  private long now;

  private CabTick service(double demand, double speed, boolean mainCircuit, boolean stopped) {
    return new CabTick(true, mainCircuit, demand, false, false, speed, stopped, true, true);
  }

  private void run(CabSystems cab, double seconds, CabTick in) {
    for (int i = 0; i < Math.round(seconds / DT); i++) {
      cab.tick(++now, DT, in);
    }
  }

  @Test
  void aboveTheExitSpeedTheElectricBrakeTakesItsShareAndTheCylinderOnlyTheRest() {
    CabSystems cab = CabSystems.simulation(CONFIG, EMU, 800, false, 0);

    run(cab, 1.0, service(1.0, FAST, true, false));

    assertEquals(0.7, cab.electricBrake(), 1e-9);
    assertTrue(cab.regenerating());
    assertEquals(0.3 / 0.85 * 350, cab.air().brakeCylinderKpa(), 1e-6);
    assertEquals(1.0, cab.serviceBrakeScale(1.0, FAST, true), 1e-9, "电空合起来满足全制动");
  }

  @Test
  void withoutTheElectricBrakeTheAirBrakeAloneGivesSlightlyLess() {
    CabSystems unpowered = CabSystems.simulation(CONFIG, EMU, 800, false, 0);
    run(unpowered, 1.0, service(1.0, FAST, false, false));
    assertFalse(unpowered.regenerating(), "主电路没电就没有电制动");
    assertEquals(350.0, unpowered.air().brakeCylinderKpa(), 1e-6);
    assertEquals(0.85, unpowered.serviceBrakeScale(1.0, FAST, false), 1e-9);

    CabSystems slow = CabSystems.simulation(CONFIG, EMU, 800, false, 0);
    run(slow, 1.0, service(1.0, SLOW, true, false));
    assertFalse(slow.regenerating(), "低于退出速度电制动退出");
    assertEquals(0.85, slow.serviceBrakeScale(1.0, SLOW, true), 1e-9);

    CabSystems diesel = CabSystems.simulation(CONFIG, DMU, 800, false, 0);
    run(diesel, 1.0, service(1.0, FAST, true, false));
    assertFalse(diesel.regenerating(), "内燃车没有电制动");
  }

  @Test
  void regenerativeBrakingSavesMainReservoirAir() {
    CabSystems blended = CabSystems.simulation(CONFIG, EMU, 800, false, 0);
    CabSystems airOnly = CabSystems.simulation(CONFIG, EMU, 800, false, 0);

    run(blended, 1.0, service(1.0, FAST, true, false));
    run(airOnly, 1.0, service(1.0, SLOW, true, false));

    double blendedUse = 800 - blended.air().mainReservoirKpa();
    double airUse = 800 - airOnly.air().mainReservoirKpa();
    assertEquals(0.3 / 0.85 * 350 * 0.1, blendedUse, 1e-6, "只有空气制动部分耗风");
    assertEquals(35.0, airUse, 1e-6);
  }

  @Test
  void failSafeBrakesAreAllAirAndTheEmergencyBrakeGoesBeyondFullService() {
    CabSystems cab = CabSystems.simulation(CONFIG, EMU, 900, false, 0);

    run(cab, 0.5, new CabTick(true, true, 1.4, true, true, FAST, false, true, true));

    assertFalse(cab.regenerating());
    assertEquals(490.0, cab.air().brakeCylinderKpa(), 1e-6);
  }

  @Test
  void lineVoltageLossCutsTractionTheElectricBrakeAndTheCompressor() {
    CabSystems cab = CabSystems.hotHandover(CONFIG, EMU, 0);
    cab.air().consume(300);
    cab.faults().inject(CabFault.LINE_LOSS, now);

    run(cab, 1.0, service(1.0, FAST, true, false));

    assertEquals(Optional.of(CabSystems.TractionBlock.LINE_LOSS), cab.tractionBlock());
    assertFalse(cab.regenerating());
    assertEquals(0.0, cab.electricCapacity(FAST, true));
    assertFalse(cab.air().compressorRunning(), "失电时压缩机也没有电");
  }

  @Test
  void faultsComeFirstInTheTractionBlockChain() {
    CabSystems cab = CabSystems.simulation(CONFIG, EMU, 900, false, 0);
    assertEquals(Optional.of(CabSystems.TractionBlock.PARKING_BRAKE), cab.tractionBlock());

    cab.faults().inject(CabFault.DOOR, 0);
    assertEquals(Optional.of(CabSystems.TractionBlock.DOOR_CIRCUIT), cab.tractionBlock());
    cab.faults().inject(CabFault.BREAKER_TRIP, 0);
    assertEquals(Optional.of(CabSystems.TractionBlock.BREAKER_TRIPPED), cab.tractionBlock());

    cab.faults().clearAll();
    cab.faults().inject(CabFault.DOOR, 0);
    cab.faults().toggleDoorBypass();
    assertEquals(
        Optional.of(CabSystems.TractionBlock.PARKING_BRAKE), cab.tractionBlock(), "旁路后门回路不再封锁");
  }

  @Test
  void aFailedCompressorStopsRechargingTheMainReservoir() {
    CabSystems cab = CabSystems.simulation(CONFIG, EMU, 600, false, 0);
    cab.faults().inject(CabFault.COMPRESSOR, 0);

    run(cab, 10.0, service(0.0, 0.0, true, true));

    assertEquals(600.0, cab.air().mainReservoirKpa(), 1e-9);
    assertFalse(cab.air().compressorRunning());
    assertTrue(cab.air().compressorFailed());

    cab.faults().clearAll();
    run(cab, 1.0, service(0.0, 0.0, true, true));
    assertTrue(cab.air().compressorRunning(), "清除后恢复");
  }

  @Test
  void aLeakingBrakeCylinderCannotHoldTheBrake() {
    CabSystems cab = CabSystems.simulation(CONFIG, EMU, 900, false, 0);
    run(cab, 0.5, service(1.0, 0.0, true, true));
    assertEquals(350.0, cab.air().brakeCylinderKpa(), 1e-6);

    cab.faults().inject(CabFault.BRAKE_LEAK, now);
    run(cab, 10.0, service(1.0, 0.0, true, true));

    assertEquals(350.0 - 6.0 * 10.0, cab.air().brakeCylinderKpa(), 0.5, "每秒漏 6 kPa");
    assertTrue(cab.serviceBrakeScale(1.0, 0.0, true) < 0.85 * 0.85, "制动力随制动缸下降");
  }

  @Test
  void aLocomotiveChargesItsBrakePipeAndRunsTheFullBrakeTest() {
    CabSystems cab = CabSystems.simulation(CONFIG, LOCO, 900, true, 0);
    cab.air().toggleParking();
    assertEquals(
        Optional.of(CabSystems.TractionBlock.BRAKE_PIPE), cab.tractionBlock(), "上车时制动管未充风");
    assertTrue(cab.brakeTest().start());
    assertEquals(BrakeTest.Stage.CHARGE, cab.brakeTest().stage());

    run(cab, 40.0, service(0.0, 0.0, true, true));
    assertEquals(500.0, cab.brakePipe().orElseThrow().pressureKpa(), 1e-6);
    assertEquals(BrakeTest.Stage.APPLY, cab.brakeTest().stage());
    assertEquals(Optional.of(CabSystems.TractionBlock.BRAKE_TEST), cab.tractionBlock());

    run(cab, 5.0, service(1.0, 0.0, true, true));
    assertEquals(350.0, cab.brakePipe().orElseThrow().pressureKpa(), 1e-6);
    assertEquals(350.0, cab.air().brakeCylinderKpa(), 1e-6, "全制动减压对应制动缸全压");
    assertEquals(BrakeTest.Stage.HOLD, cab.brakeTest().stage());

    run(cab, 10.5, service(1.0, 0.0, true, true));
    assertEquals(BrakeTest.Stage.RELEASE, cab.brakeTest().stage());

    run(cab, 15.0, service(0.0, 0.0, true, true));
    assertTrue(cab.brakeTest().passed());
    assertTrue(cab.tractionBlock().isEmpty());
  }

  @Test
  void aLeakingCylinderFailsTheLocomotiveHoldTest() {
    CabSystems cab = CabSystems.hotHandover(CONFIG, LOCO, 0);
    CabSystems fresh = CabSystems.simulation(CONFIG, LOCO, 900, true, 0);
    fresh.faults().inject(CabFault.BRAKE_LEAK, 0);
    fresh.brakeTest().start();

    run(fresh, 40.0, service(0.0, 0.0, true, true));
    run(fresh, 8.0, service(1.0, 0.0, true, true));

    assertEquals(BrakeTest.Stage.FAILED, fresh.brakeTest().stage());
    assertEquals(Optional.of(BrakeTest.Stage.FAILED), lastChange(fresh));
    assertTrue(cab.brakeTest().passed(), "热车交接视为已做试验");
  }

  private static Optional<BrakeTest.Stage> lastChange(CabSystems cab) {
    return cab.takeBrakeTestChange();
  }

  @Test
  void theLocomotiveAirBrakeBuildsUpAsThePipeIsReduced() {
    CabSystems cab = CabSystems.hotHandover(CONFIG, LOCO, 0);

    run(cab, DT, service(1.0, FAST, true, false));
    double early = cab.serviceBrakeScale(1.0, FAST, true);
    run(cab, 5.0, service(1.0, FAST, true, false));
    double late = cab.serviceBrakeScale(1.0, FAST, true);

    assertTrue(early < 0.1, "制动管刚开始减压，制动缸几乎没有压力");
    assertEquals(0.85, late, 1e-9);
    assertTrue(cab.serviceApplyLagSeconds() > 0.0);
    assertEquals(0.0, CabSystems.hotHandover(CONFIG, EMU, 0).serviceApplyLagSeconds());
  }

  @Test
  void losingThePipeRequestsTheEmergencyBrakeButTheDriversOwnEmergencyBrakeDoesNot() {
    CabSystems vented = CabSystems.hotHandover(CONFIG, LOCO, 0);
    run(vented, 3.0, new CabTick(true, true, 1.4, true, true, FAST, false, true, true));
    assertEquals(0.0, vented.brakePipe().orElseThrow().pressureKpa(), 1e-6);
    assertFalse(vented.takeEmergencyRequest(), "驾驶员自己拉的紧急制动不算失压");
    assertEquals(490.0, vented.air().brakeCylinderKpa(), 1e-6);

    CabSystems leaking = CabSystems.hotHandover(CONFIG, LOCO, 0);
    leaking.air().consume(700);
    run(leaking, 10.0, service(0.0, FAST, true, false));

    assertTrue(leaking.takeEmergencyRequest(), "主风缸失风拖低制动管，自动紧急制动");
    assertFalse(leaking.takeEmergencyRequest());
    assertTrue(leaking.takeBrakePipeLoss());
    assertTrue(leaking.brakePipe().orElseThrow().belowFullService(), "制动管充回之前不能牵引");
  }

  @Test
  void theStandardLevelKeepsEveryScaleAtOne() {
    CabSystems cab = CabSystems.disabled();

    assertEquals(1.0, cab.serviceBrakeScale(1.0, FAST, true));
    assertEquals(1.0, cab.tractionScale(FAST));
    assertEquals(0.0, cab.electricCapacity(FAST, true));
    assertEquals(0.0, cab.serviceApplyLagSeconds());
    assertEquals(Vigilance.Event.NONE, cab.tick(1, DT, service(1.0, FAST, true, false)));
    assertFalse(cab.regenerating());
  }

  @Test
  void theProtectionAssumesTheAirBrakeAloneInSimulation() {
    assertEquals(0.85, CabSystems.hotHandover(CONFIG, EMU, 0).brakeScale(), 1e-9);
    assertEquals(1.0, CabSystems.disabled().brakeScale());
  }
}
