package org.fetarute.fetaruteTCAddon.drive.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrainSetupTest {

  /** 钥匙 1、受电 4、主断 2、辅助 3 tick，便于数时间。 */
  private static final SetupTimings TIMINGS = new SetupTimings(1, 4, 2, 6, 2, 3);

  private static TrainSetup electric() {
    return new TrainSetup(PowerSupply.PTG5, TIMINGS);
  }

  @Test
  void electricTrainsGoKeyPowerBreakerAux() {
    assertEquals(
        List.of(SetupSystem.KEY, SetupSystem.POWER, SetupSystem.BREAKER, SetupSystem.AUX),
        electric().sequence());
  }

  @Test
  void dieselTrainsHaveNoBreaker() {
    TrainSetup diesel = new TrainSetup(PowerSupply.DIESEL, TIMINGS);

    assertEquals(List.of(SetupSystem.KEY, SetupSystem.POWER, SetupSystem.AUX), diesel.sequence());
    assertFalse(diesel.applies(SetupSystem.BREAKER));
    assertEquals(TrainSetup.ToggleResult.NOT_APPLICABLE, diesel.toggle(SetupSystem.BREAKER, 0));
  }

  @Test
  void aColdTrainIsNotReady() {
    TrainSetup setup = electric();

    assertFalse(setup.ready());
    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.POWER));
  }

  @Test
  void aStepNeedsThePreviousOneToBeOn() {
    TrainSetup setup = electric();

    assertEquals(TrainSetup.ToggleResult.NEEDS_PREREQUISITE, setup.toggle(SetupSystem.POWER, 0));
    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.POWER));
  }

  @Test
  void aStepTakesItsTimeAndOnlyOneRunsAtOnce() {
    TrainSetup setup = electric();
    setup.toggle(SetupSystem.KEY, 0);
    setup.tick(1);

    assertEquals(TrainSetup.ToggleResult.STARTING, setup.toggle(SetupSystem.POWER, 1));
    assertEquals(TrainSetup.State.STARTING, setup.state(SetupSystem.POWER));
    assertEquals(TrainSetup.ToggleResult.BUSY, setup.toggle(SetupSystem.KEY, 2));
    assertEquals(new TrainSetup.Progress(SetupSystem.POWER, 2), setup.progress(3).orElseThrow());

    assertFalse(setup.tick(4));
    assertTrue(setup.tick(5));
    assertEquals(TrainSetup.State.ON, setup.state(SetupSystem.POWER));
    assertTrue(setup.progress(5).isEmpty());
  }

  @Test
  void startAllRunsTheWholeSequenceInOrder() {
    TrainSetup setup = electric();

    setup.startAll(0);
    long now = 0;
    while (!setup.ready() && now < 100) {
      now++;
      setup.tick(now);
    }

    assertTrue(setup.ready());
    assertFalse(setup.busy());
    assertEquals(1 + 4 + 2 + 3, now, "各步耗时首尾相接");
  }

  @Test
  void cannotLowerThePantographWhileTheBreakerIsClosed() {
    TrainSetup setup = ready(electric());

    assertEquals(TrainSetup.ToggleResult.INTERLOCKED, setup.toggle(SetupSystem.POWER, 50));
    assertEquals(TrainSetup.ToggleResult.INTERLOCKED, setup.toggle(SetupSystem.BREAKER, 50));
    assertEquals(TrainSetup.ToggleResult.SWITCHED_OFF, setup.toggle(SetupSystem.AUX, 50));
    assertEquals(TrainSetup.ToggleResult.SWITCHED_OFF, setup.toggle(SetupSystem.BREAKER, 50));
    assertEquals(TrainSetup.ToggleResult.SWITCHED_OFF, setup.toggle(SetupSystem.POWER, 50));
  }

  @Test
  void removingTheKeyKeepsTheTrainPoweredForAChangeOfEnds() {
    TrainSetup setup = ready(electric());

    assertEquals(TrainSetup.ToggleResult.SWITCHED_OFF, setup.toggle(SetupSystem.KEY, 50));

    assertFalse(setup.ready());
    assertEquals(
        EnumSet.of(SetupSystem.POWER, SetupSystem.BREAKER, SetupSystem.AUX), setup.persistentOn());
  }

  @Test
  void leavingTheCabDropsTheKeyAndAbortsAStepInProgress() {
    TrainSetup setup = electric();
    setup.startAll(0);
    setup.tick(1);
    assertEquals(TrainSetup.State.STARTING, setup.state(SetupSystem.POWER));

    setup.cabDeactivated();

    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.KEY));
    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.POWER));
    assertFalse(setup.busy());
    setup.tick(100);
    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.POWER), "作废的接通不会再完成");
  }

  @Test
  void shutdownSwitchesEverythingOff() {
    TrainSetup setup = ready(electric());

    setup.shutdown();

    for (SetupSystem system : setup.sequence()) {
      assertEquals(TrainSetup.State.OFF, setup.state(system));
    }
    assertTrue(setup.persistentOn().isEmpty());
  }

  @Test
  void aPoweredTrainOnlyNeedsTheKeyAfterRestore() {
    TrainSetup setup = electric();
    setup.restore(Set.of(SetupSystem.POWER, SetupSystem.BREAKER, SetupSystem.AUX));

    setup.startAll(0);
    setup.tick(1);

    assertTrue(setup.ready());
  }

  @Test
  void restoreOnlyAcceptsAnUnbrokenPrefixOfTheSequence() {
    TrainSetup setup = electric();

    setup.restore(Set.of(SetupSystem.POWER, SetupSystem.AUX));

    assertEquals(TrainSetup.State.ON, setup.state(SetupSystem.POWER));
    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.AUX), "主断没合时辅助电源不可能接通");
  }

  @Test
  void restoreNeverTurnsTheKeyOn() {
    TrainSetup setup = electric();

    setup.restore(EnumSet.allOf(SetupSystem.class));

    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.KEY));
  }

  @Test
  void zeroDurationStepsFinishImmediately() {
    TrainSetup setup = new TrainSetup(PowerSupply.SHOE, new SetupTimings(0, 0, 0, 0, 0, 0));

    setup.startAll(0);

    assertTrue(setup.ready());
  }

  @Test
  void alwaysReadyNeedsNothing() {
    assertTrue(TrainSetup.alwaysReady(PowerSupply.DIESEL, TIMINGS).ready());
  }

  @Test
  void shutdownCancelsAOneClickStart() {
    TrainSetup setup = electric();
    setup.startAll(0);
    setup.tick(1);
    assertTrue(setup.busy());

    setup.shutdown();
    setup.tick(100);

    assertFalse(setup.busy());
    assertFalse(setup.ready());
    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.POWER));
  }

  @Test
  void switchesCannotBeTouchedWhileAStepIsRunning() {
    TrainSetup setup = electric();
    setup.startAll(0);

    assertEquals(TrainSetup.ToggleResult.BUSY, setup.toggle(SetupSystem.AUX, 0));
    assertTrue(setup.autoStarting(), "被拒绝的操作不打断一键启动");
  }

  private static TrainSetup ready(TrainSetup setup) {
    setup.startAll(0);
    for (long now = 1; now < 100 && !setup.ready(); now++) {
      setup.tick(now);
    }
    assertTrue(setup.ready());
    return setup;
  }

  @Test
  void theNextStepIsTheFirstOneNotYetOn() {
    TrainSetup setup = electric();
    assertEquals(Optional.of(SetupSystem.KEY), setup.nextStep());

    setup.restore(Set.of(SetupSystem.POWER));
    setup.toggle(SetupSystem.KEY, 0);
    setup.tick(1);

    assertEquals(Optional.of(SetupSystem.BREAKER), setup.nextStep());
    assertTrue(TrainSetup.alwaysReady(PowerSupply.PTG5, TIMINGS).nextStep().isEmpty());
  }

  private static TrainSetup ready(PowerSupply supply) {
    TrainSetup setup = new TrainSetup(supply, TIMINGS);
    setup.startAll(0);
    for (long tick = 0; tick < 100 && !setup.ready(); tick++) {
      setup.tick(tick);
    }
    assertTrue(setup.ready());
    return setup;
  }

  /** 冷车换受电方式：只换方式与启动顺序，什么也没接通。 */
  @Test
  void aColdTrainOnlyChangesItsSupply() {
    TrainSetup setup = electric();

    assertTrue(setup.switchSupply(PowerSupply.DIESEL, true, 0));

    assertEquals(PowerSupply.DIESEL, setup.supply());
    assertFalse(setup.applies(SetupSystem.BREAKER), "内燃没有主断");
    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.POWER));
    assertFalse(setup.busy());
  }

  /** 跟着调度换模（standard 级）：系统保持接通；换成内燃去掉主断，换回电力主断随辅助电源接通。 */
  @Test
  void anUntimedSwitchKeepsTheTrainRunning() {
    TrainSetup setup = ready(PowerSupply.PTG5);

    assertTrue(setup.switchSupply(PowerSupply.PTG6, false, 50));
    assertTrue(setup.ready());
    assertEquals(PowerSupply.PTG6, setup.supply());

    assertTrue(setup.switchSupply(PowerSupply.DIESEL, false, 51));
    assertTrue(setup.ready());
    assertFalse(setup.applies(SetupSystem.BREAKER));

    assertTrue(setup.switchSupply(PowerSupply.PTG5, false, 52));
    assertTrue(setup.ready());
    assertEquals(TrainSetup.State.ON, setup.state(SetupSystem.BREAKER));
  }

  /** 驾驶员换模（simulation 级）：受电按新方式重新接通，耗时同受电一步，期间主电路断电；接通后照常可以牵引。 */
  @Test
  void aTimedSwitchRestartsThePowerStep() {
    TrainSetup setup = ready(PowerSupply.PTG5);

    assertTrue(setup.switchSupply(PowerSupply.DIESEL, true, 100));

    assertEquals(TrainSetup.State.STARTING, setup.state(SetupSystem.POWER));
    assertFalse(setup.ready());
    assertFalse(setup.mainCircuitPowered());
    assertEquals(new TrainSetup.Progress(SetupSystem.POWER, 6), setup.progress(100).orElseThrow());
    assertFalse(setup.switchSupply(PowerSupply.PTG6, true, 101), "正在接通时不能再换");
    setup.tick(106);
    assertTrue(setup.ready());
  }

  /** 已是这种方式时不换。 */
  @Test
  void switchingToTheSameSupplyDoesNothing() {
    TrainSetup setup = ready(PowerSupply.PTG5);

    assertFalse(setup.switchSupply(PowerSupply.PTG5, true, 10));
    assertTrue(setup.ready());
  }

  /** 调度换模不等正在接通的系统：那一步作废，换完一键启动接着进行。 */
  @Test
  void anUntimedSwitchDoesNotWaitForAStepInProgress() {
    TrainSetup setup = electric();
    setup.startAll(0);
    setup.tick(1);
    assertTrue(setup.busy());

    assertTrue(setup.switchSupply(PowerSupply.DIESEL, false, 2));

    assertEquals(PowerSupply.DIESEL, setup.supply());
    assertTrue(setup.autoStarting(), "一键启动接着进行");
    for (long tick = 2; tick < 100 && !setup.ready(); tick++) {
      setup.tick(tick);
    }
    assertTrue(setup.ready());

    TrainSetup starting = electric();
    starting.startAll(0);
    assertFalse(starting.switchSupply(PowerSupply.DIESEL, true, 0), "驾驶员换模要等正在接通的系统");
    assertEquals(PowerSupply.PTG5, starting.supply());
  }

  /** 驾驶员换模重新受电时离座：受电作废，其后的主断与辅助电源一并断开，不留下违反顺序的状态。 */
  @Test
  void leavingTheCabDuringATimedSwitchDropsTheLaterSystems() {
    TrainSetup setup = ready(PowerSupply.PTG5);
    setup.switchSupply(PowerSupply.PTG6, true, 100);

    setup.cabDeactivated();

    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.POWER));
    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.BREAKER));
    assertEquals(TrainSetup.State.OFF, setup.state(SetupSystem.AUX));
    assertEquals(Set.of(), setup.persistentOn());
  }
}
