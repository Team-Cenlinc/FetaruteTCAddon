package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.junit.jupiter.api.Test;

/** 车掌一站的作业与时限：开门、关门、发车铃三步各自计时，只累计出站放行着的时间，报告延长当前这一步。 */
class GuardStopWorkTest {

  private final GuardConfig config = GuardConfig.defaults();

  private static void run(GuardStopWork work, Phase phase, long ticks) {
    for (long i = 0; i < ticks; i++) {
      work.tick(phase);
    }
  }

  /** 停妥后 30 秒不开门：代开一次，记超时；之后不再重复。 */
  @Test
  void openingTooLateIsDoneByTheStation() {
    GuardStopWork work = new GuardStopWork(config);
    run(work, Phase.OPEN_DOORS, config.openDoorsTicks());
    assertFalse(work.timedOut());

    assertEquals(GuardStopWork.Action.FORCE_OPEN, work.tick(Phase.OPEN_DOORS));
    assertTrue(work.timedOut());
    assertEquals(GuardStopWork.Action.NONE, work.tick(Phase.OPEN_DOORS));
  }

  /** 停站时间到后 15 秒不关门：代关一次。停站期间不计关门时间。 */
  @Test
  void closingTooLateIsDoneByTheStation() {
    GuardStopWork work = new GuardStopWork(config);
    run(work, Phase.OPEN_DOORS, 100);
    run(work, Phase.DWELL, 2000);
    run(work, Phase.CLOSE_DOORS, config.closeDoorsTicks());
    assertFalse(work.timedOut(), "开门及时、停站多久都不算");

    assertEquals(GuardStopWork.Action.FORCE_CLOSE, work.tick(Phase.CLOSE_DOORS));
    assertTrue(work.forcedClose());
  }

  /** 异常报告：当前这一步延长 30 秒，每站最多两次。 */
  @Test
  void reportsExtendTheCurrentStepTwice() {
    GuardStopWork work = new GuardStopWork(config);
    run(work, Phase.OPEN_DOORS, 10);
    assertTrue(work.report());
    assertTrue(work.report());
    assertFalse(work.report(), "每站最多两次");
    assertFalse(work.canReport());

    run(work, Phase.OPEN_DOORS, config.openDoorsTicks() + 2 * config.incidentExtensionTicks() - 10);
    assertFalse(work.timedOut());
    assertEquals(GuardStopWork.Action.FORCE_OPEN, work.tick(Phase.OPEN_DOORS));
  }

  /** 报告只延长当时那一步：换到下一步后时限照旧。 */
  @Test
  void anExtensionDoesNotCarryIntoTheNextStep() {
    GuardStopWork work = new GuardStopWork(config);
    work.tick(Phase.OPEN_DOORS);
    work.report();
    run(work, Phase.CLOSE_DOORS, config.closeDoorsTicks());
    assertEquals(GuardStopWork.Action.FORCE_CLOSE, work.tick(Phase.CLOSE_DOORS));
  }

  /** 演练占用的时间：本站余下各步都加上，换步不清零、不占报告次数。 */
  @Test
  void aDrillCreditCoversTheRestOfTheStop() {
    GuardStopWork work = new GuardStopWork(config);
    run(work, Phase.CLOSE_DOORS, 10);
    work.credit(300L);
    assertTrue(work.canReport(), "不占报告次数");
    run(work, Phase.CLOSE_DOORS, config.closeDoorsTicks() + 300L - 10);
    assertFalse(work.timedOut());
    assertEquals(GuardStopWork.Action.FORCE_CLOSE, work.tick(Phase.CLOSE_DOORS));

    GuardStopWork departing = new GuardStopWork(config);
    departing.tick(Phase.CLOSE_DOORS);
    departing.credit(300L);
    departing.tick(Phase.WAIT_DEPARTURE);
    assertEquals(config.departSignalTicks() + 300L, departing.remainingTicks(Phase.WAIT_DEPARTURE));
    long now = 1000L;
    assertTrue(departing.holdDeparture(true, now));
    for (int i = 0; i < 29; i++) {
      now += 20;
      assertTrue(departing.holdDeparture(true, now), "换到等发车这一步仍有演练的时间");
    }
    now += 20;
    assertFalse(departing.holdDeparture(true, now), "放行着累计满 15 + 15 秒");
    assertTrue(departing.forcedSignal());
  }

  /** 出站不放行时一直扣着且不计时；放行着累计满 15 秒才代发发车信号。 */
  @Test
  void onlyTimeWithTheExitOpenCountsTowardTheSignalLimit() {
    GuardStopWork work = new GuardStopWork(config);
    work.tick(Phase.WAIT_DEPARTURE);
    long now = 1000L;
    for (int i = 0; i < 60; i++, now += 20) {
      assertTrue(work.holdDeparture(false, now), "出站不放行：扣着");
    }
    assertFalse(work.timedOut(), "不放行的一分钟不计");
    assertTrue(work.holdDeparture(true, now));
    for (int i = 0; i < 14; i++) {
      now += 20;
      assertTrue(work.holdDeparture(true, now));
    }
    // 中途关上一阵：那一段不算
    now += 200;
    assertTrue(work.holdDeparture(false, now));
    now += 20;
    assertTrue(work.holdDeparture(true, now), "关上之后第一次放行不计入");
    now += 20;
    assertFalse(work.holdDeparture(true, now), "放行着累计满 300 tick");
    assertTrue(work.forcedSignal());
    assertTrue(work.timedOut());
    assertTrue(work.released());
  }

  /** 出发确认要在等发车那一步、出站开放时；确认后关上再开放也有效。 */
  @Test
  void theDepartureCheckNeedsAClearExit() {
    GuardStopWork work = new GuardStopWork(config);
    assertEquals(GuardStopWork.Confirm.DOORS_OPEN, work.confirm(Phase.CLOSE_DOORS));
    work.tick(Phase.WAIT_DEPARTURE);
    work.holdDeparture(false, 100L);
    assertEquals(GuardStopWork.Confirm.EXIT_CLOSED, work.confirm(Phase.WAIT_DEPARTURE));
    work.holdDeparture(true, 120L);
    assertEquals(GuardStopWork.Confirm.CONFIRMED, work.confirm(Phase.WAIT_DEPARTURE));
    assertEquals(GuardStopWork.Confirm.ALREADY, work.confirm(Phase.WAIT_DEPARTURE));
    work.holdDeparture(false, 140L);
    assertTrue(work.confirmed(), "关上后确认仍有效");
  }

  /** 一长发车铃：门全关、回座、已确认才算；发了之后出站一放行就放行。 */
  @Test
  void theSignalNeedsClosedDoorsSeatAndCheck() {
    GuardStopWork work = new GuardStopWork(config);
    assertEquals(GuardStopWork.Signal.DOORS_OPEN, work.signal(Phase.CLOSE_DOORS, true));
    work.tick(Phase.WAIT_DEPARTURE);
    work.holdDeparture(true, 100L);
    assertEquals(GuardStopWork.Signal.NOT_SEATED, work.signal(Phase.WAIT_DEPARTURE, false));
    assertEquals(GuardStopWork.Signal.NOT_CONFIRMED, work.signal(Phase.WAIT_DEPARTURE, true));
    work.confirm(Phase.WAIT_DEPARTURE);
    assertEquals(GuardStopWork.Signal.GIVEN, work.signal(Phase.WAIT_DEPARTURE, true));
    assertEquals(GuardStopWork.Signal.ALREADY, work.signal(Phase.WAIT_DEPARTURE, true));

    assertTrue(work.holdDeparture(false, 120L), "发了信号但出站又关上：等");
    assertFalse(work.holdDeparture(true, 140L), "出站放行即放行");
    assertTrue(work.released());
    assertFalse(work.forcedSignal());
    assertFalse(work.timedOut());
    assertFalse(work.report(), "放行后不能再报告");
  }

  /** 剩余时间：按当前这一步，含延长；不计时的阶段为 -1。 */
  @Test
  void remainingTicksFollowTheCurrentStep() {
    GuardStopWork work = new GuardStopWork(config);
    assertEquals(-1L, work.remainingTicks(Phase.OPEN_DOORS), "还没开始计时");
    run(work, Phase.OPEN_DOORS, 100);
    assertEquals(config.openDoorsTicks() - 100, work.remainingTicks(Phase.OPEN_DOORS));
    work.report();
    assertEquals(
        config.openDoorsTicks() + config.incidentExtensionTicks() - 100,
        work.remainingTicks(Phase.OPEN_DOORS));
    run(work, Phase.DWELL, 5);
    assertEquals(-1L, work.remainingTicks(Phase.DWELL));
  }
}
