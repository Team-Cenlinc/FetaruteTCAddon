package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupTimings;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.junit.jupiter.api.Test;

class DriveSessionTest {

  private final DriveConfig config = DriveConfig.defaults();

  private DriveSession newSession() {
    return newSession(new SeatBinding("T1", 0, 0));
  }

  private DriveSession newSession(SeatBinding binding) {
    return newSession(binding, config);
  }

  private DriveSession newSession(SeatBinding binding, DriveConfig sessionConfig) {
    List<ItemStack> hotbar = new ArrayList<>();
    for (int i = 0; i < Notch.SLOT_COUNT; i++) {
      hotbar.add(mock(ItemStack.class));
    }
    return new DriveSession(
        UUID.randomUUID(),
        "Steve",
        binding,
        new DriveParams(DriveMode.MU, 1.0, 1.0, 22.0, 0.5),
        sessionConfig,
        hotbar);
  }

  @Test
  void startsActiveAtCoastWithoutAnEndReason() {
    DriveSession session = newSession();

    assertEquals(DriveSession.Phase.ACTIVE, session.phase());
    assertEquals(Notch.N, session.notch());
    assertNull(session.endReason());
    assertEquals("T1", session.trainName());
  }

  @Test
  void leavingTheSeatMovesThroughBrakingToEnded() {
    DriveSession session = newSession();

    assertTrue(session.beginStopping(DriveSession.EndReason.LEFT_SEAT));
    assertEquals(DriveSession.Phase.STOPPING, session.phase());
    assertEquals(DriveSession.EndReason.LEFT_SEAT, session.endReason());
    assertFalse(session.beginStopping(DriveSession.EndReason.SEAT_LOST), "已在制动停车，不能重复转入");

    session.finish(DriveSession.EndReason.SEAT_LOST);

    assertEquals(DriveSession.Phase.ENDED, session.phase());
    assertEquals(DriveSession.EndReason.LEFT_SEAT, session.endReason(), "先到的原因保留");
  }

  @Test
  void finishingDirectlyRecordsTheGivenReasonAndZeroesTheSpeed() {
    DriveSession session = newSession();
    session.resetSpeed(12.0);

    session.finish(DriveSession.EndReason.DISABLED);

    assertEquals(DriveSession.Phase.ENDED, session.phase());
    assertEquals(DriveSession.EndReason.DISABLED, session.endReason());
    assertEquals(0.0, session.speedBps(), 0.0);
    assertFalse(session.beginStopping(DriveSession.EndReason.LEFT_SEAT));
  }

  @Test
  void anUnattendedTrainBrakesWithAFixedServiceNotchWhateverTheHandleSays() {
    DriveSession session = newSession();
    session.selector().force(Notch.P3);
    assertEquals(Notch.P3, session.notch());

    session.beginStopping(DriveSession.EndReason.LEFT_SEAT);

    assertEquals(Notch.B3, session.notch());
  }

  @Test
  void forceIsNeverNegativeWhateverTheReverserSays() {
    DriveSession session = newSession(new SeatBinding("T1", 5, 0));
    session.resetSpeed(20.0);

    // 20 格/秒 = 1 格/tick，分 4 个物理小步。
    assertEquals(0.25, session.forcePerStep(4), 1e-12);

    session.setReverser(ReverserPosition.REVERSE);
    assertEquals(0.25, session.forcePerStep(4), 1e-12, "朝车尾方向开要靠调头，不靠负力");
  }

  @Test
  void theReverserStartsInForward() {
    assertEquals(ReverserPosition.FORWARD, newSession().reverser());
  }

  @Test
  void neutralNeedsNoTurnaroundWhicheverEndTheCabIsAt() {
    DriveSession rear = newSession(new SeatBinding("T1", 5, 0));
    rear.setReverser(ReverserPosition.NEUTRAL);

    assertTrue(rear.travelsTowardHead(6));
  }

  @Test
  void neutralBlocksTractionButNotTheBrakes() {
    DriveSession session = newSession();
    session.setReverser(ReverserPosition.NEUTRAL);

    session.selector().force(Notch.P3);
    assertTrue(session.tractionBlocked());
    assertEquals(Notch.N, session.notch());

    session.selector().force(Notch.B2);
    assertEquals(Notch.B2, session.notch());

    session.selector().force(Notch.EB);
    assertEquals(Notch.EB, session.notch());
  }

  @Test
  void anOpenDoorBlocksTractionUnderTheStandardLevelUntilItIsClosed() {
    DriveSession session = newSession();
    session.selector().force(Notch.P2);
    assertFalse(session.tractionBlocked());

    session.setDoorOpen(true, true);
    assertTrue(session.anyDoorOpen());
    assertTrue(session.isLeftDoorOpen());
    assertFalse(session.isRightDoorOpen());
    assertEquals(Notch.N, session.notch());

    session.setDoorOpen(true, false);
    assertEquals(Notch.P2, session.notch());
  }

  @Test
  void eitherDoorBlocksTractionAndBothMustBeClosedToClearIt() {
    DriveSession session = newSession();
    session.selector().force(Notch.P1);

    session.setDoorOpen(true, true);
    session.setDoorOpen(false, true);
    session.setDoorOpen(true, false);

    assertTrue(session.tractionBlocked());

    session.setDoorOpen(false, false);
    assertFalse(session.tractionBlocked());
  }

  @Test
  void theMenuWindowSizeIsNeverNegative() {
    DriveSession session = newSession();
    assertEquals(0, session.menuTopSize());

    session.setMenuTopSize(9);
    assertEquals(9, session.menuTopSize());

    session.setMenuTopSize(-3);
    assertEquals(0, session.menuTopSize());
  }

  @Test
  void aFrontCabDrivingForwardAlreadyFacesTheHead() {
    DriveSession session = newSession(new SeatBinding("T1", 0, 0));

    assertTrue(session.cabAtHead(6));
    assertTrue(session.travelsTowardHead(6));
  }

  @Test
  void aRearCabDrivingForwardNeedsTheTrainTurnedAround() {
    DriveSession session = newSession(new SeatBinding("T1", 5, 0));

    assertFalse(session.cabAtHead(6));
    assertFalse(session.travelsTowardHead(6));
  }

  @Test
  void reverseGearFlipsWhichEndTheTrainMustLead() {
    DriveSession front = newSession(new SeatBinding("T1", 0, 0));
    DriveSession rear = newSession(new SeatBinding("T1", 5, 0));
    front.setReverser(ReverserPosition.REVERSE);
    rear.setReverser(ReverserPosition.REVERSE);

    assertFalse(front.travelsTowardHead(6), "前端驾驶室挂后退挡：要朝车尾走");
    assertTrue(rear.travelsTowardHead(6), "后端驾驶室挂后退挡：车头本来就朝那边");
  }

  @Test
  void turningTheTrainAroundFlipsTheSeatIndexAndSoTheAnswer() {
    DriveSession session = newSession(new SeatBinding("T1", 5, 0));
    assertFalse(session.travelsTowardHead(6));

    // 整列调头后，同一个座位的车厢序号从 5 变成 0。
    session.rebind(new SeatBinding("T1", 0, 0));

    assertTrue(session.travelsTowardHead(6));
  }

  @Test
  void aSingleCarTracksOurOwnTurnaroundsBecauseItHasNoFrontOrBack() {
    DriveSession session = newSession(new SeatBinding("T1", 0, 0));
    assertTrue(session.travelsTowardHead(1));

    session.setReverser(ReverserPosition.REVERSE);
    assertFalse(session.travelsTowardHead(1));

    session.noteReversedByUs(1, 100);
    assertTrue(session.travelsTowardHead(1), "调头一次后，后退挡正好朝车头方向");
  }

  @Test
  void theMiddleCarOfAnOddTrainHasNoFrontEitherSoItCountsOurTurnarounds() {
    DriveSession session = newSession(new SeatBinding("T1", 2, 0));
    session.setReverser(ReverserPosition.REVERSE);
    assertFalse(session.travelsTowardHead(5));

    // 调头后正中间那节车的序号不变；若仍按序号判断，就会每隔几个 tick 调一次头。
    session.noteReversedByUs(5, 100);
    session.rebind(new SeatBinding("T1", 2, 0));

    assertTrue(session.travelsTowardHead(5));
  }

  @Test
  void movingToAnotherCarForgetsTheTurnaroundsCountedForTheMiddleCar() {
    DriveSession session = newSession(new SeatBinding("T1", 2, 0));
    session.noteReversedByUs(5, 100);
    assertFalse(session.cabAtHead(5));

    session.rebind(new SeatBinding("T1", 0, 0));

    assertTrue(session.cabAtHead(5));
  }

  @Test
  void aTurnaroundNeverTogglesTheStateOfAMultiCarTrainByItself() {
    DriveSession session = newSession(new SeatBinding("T1", 5, 0));

    session.noteReversedByUs(6, 100);

    assertFalse(session.cabAtHead(6), "多节编组只认座位序号，序号由 rebind 更新");
  }

  @Test
  void turningAroundNeedsAStandstillAndRespectsTheMinimumInterval() {
    DriveSession session = newSession();
    assertTrue(session.mayReverse(1000));

    session.noteReversedByUs(6, 1000);
    assertFalse(session.mayReverse(1005));
    assertTrue(session.mayReverse(1010));

    session.resetSpeed(10.0);
    assertFalse(session.mayReverse(5000), "没停稳不能调头");
  }

  @Test
  void rebindingToAnotherTrainIsRejected() {
    DriveSession session = newSession();

    assertThrows(IllegalArgumentException.class, () -> session.rebind(new SeatBinding("T2", 0, 0)));

    session.rebind(new SeatBinding("T1", 3, 1));
    assertEquals(3, session.binding().memberIndex());
  }

  @Test
  void aDriverWhoIsOutOfTheSeatGetsAServiceBrakeInsteadOfTheHandle() {
    DriveSession session = newSession();
    session.selector().force(Notch.P3);
    assertEquals(Notch.P3, session.notch());

    session.markSeatLost(100);
    assertEquals(Notch.B3, session.notch());

    session.markSeated();
    assertEquals(Notch.P3, session.notch());
  }

  @Test
  void theOriginalHotbarSlotIsRememberedForRestoring() {
    DriveSession session = newSession();
    assertEquals(-1, session.originalHeldSlot());

    session.setOriginalHeldSlot(6);

    assertEquals(6, session.originalHeldSlot());
  }

  @Test
  void aStepCountOfZeroDoesNotDivideByZero() {
    DriveSession session = newSession();
    session.resetSpeed(20.0);

    assertEquals(1.0, session.forcePerStep(0), 1e-12);
  }

  @Test
  void stopIsJudgedAgainstTheConfiguredThreshold() {
    DriveSession session = newSession();

    assertTrue(session.isStopped());
    session.resetSpeed(config.stoppedSpeedBps() * 2);
    assertFalse(session.isStopped());
  }

  @Test
  void sneakCountsAsDeliberateOnlyWithinTheWindow() {
    DriveSession session = newSession();
    session.noteSneak(1000);

    assertTrue(session.sneakedRecently(1000));
    assertTrue(session.sneakedRecently(1000 + config.exitSneakWindowTicks()));
    assertFalse(session.sneakedRecently(1000 + config.exitSneakWindowTicks() + 1));
  }

  @Test
  void neverHavingSneakedIsNotARecentSneak() {
    assertFalse(newSession().sneakedRecently(0));
  }

  @Test
  void seatLossIsTimedFromTheFirstMissingTickAndResetsWhenSeated() {
    DriveSession session = newSession();

    assertEquals(0, session.markSeatLost(500));
    assertEquals(30, session.markSeatLost(530));

    session.markSeated();

    assertEquals(0, session.markSeatLost(900));
  }

  @Test
  void aMissingGroupIsTimedTheSameWay() {
    DriveSession session = newSession();

    assertEquals(0, session.markGroupMissing(10));
    assertEquals(40, session.markGroupMissing(50));

    session.markGroupFound();

    assertEquals(0, session.markGroupMissing(200));
  }

  @Test
  void eachNewActionGenerationSupersedesTheOlderOnes() {
    DriveSession session = newSession();

    int first = session.nextActionGeneration();
    int second = session.nextActionGeneration();

    assertTrue(second > first);
    assertEquals(second, session.actionGeneration());
  }

  @Test
  void touchMarksTheActionAsJustAttached() {
    DriveSession session = newSession();

    session.touch(777);

    assertEquals(777, session.lastAdvanceTick());
  }

  @Test
  void tractionIsBlockedUntilTheTrainHasBeenStarted() {
    List<ItemStack> hotbar = new ArrayList<>();
    for (int i = 0; i < Notch.SLOT_COUNT; i++) {
      hotbar.add(mock(ItemStack.class));
    }
    TrainSetup setup = new TrainSetup(PowerSupply.PTG5, new SetupTimings(0, 0, 0, 0, 0, 0));
    DriveSession session =
        new DriveSession(
            UUID.randomUUID(),
            "Steve",
            new SeatBinding("T1", 0, 0),
            new DriveParams(DriveMode.MU, 1.0, 1.0, 22.0, 0.5),
            config,
            hotbar,
            setup,
            CabSystems.disabled());

    assertTrue(session.tractionBlocked());
    setup.startAll(0);
    assertFalse(session.tractionBlocked());
  }

  @Test
  void simulationSystemsBlockTractionUntilTheParkingBrakeIsReleasedAndTheBrakeTestDone() {
    List<ItemStack> hotbar = new ArrayList<>();
    for (int i = 0; i < Notch.SLOT_COUNT; i++) {
      hotbar.add(mock(ItemStack.class));
    }
    CabSystems cab = CabSystems.simulation(CabConfig.defaults(), false, 900, false, 0);
    DriveSession session =
        new DriveSession(
            UUID.randomUUID(),
            "Steve",
            new SeatBinding("T1", 0, 0),
            new DriveParams(DriveMode.MU, 1.0, 1.0, 22.0, 0.5),
            config,
            hotbar,
            TrainSetup.alwaysReady(PowerSupply.PTG5, SetupTimings.defaults()),
            cab);
    session.selector().force(Notch.P3);

    assertEquals(Notch.N, session.notch(), "停放制动未缓解，牵引档按惰行");
    cab.air().toggleParking();
    assertEquals(Notch.N, session.notch(), "没做制动试验");
    cab.brakeTest().start();
    cab.tick(1, 0.05, true, 1.0, true, true);
    cab.tick(2, 0.05, true, 0.0, true, true);
    assertEquals(Notch.P3, session.notch());
  }
}
