package org.fetarute.fetaruteTCAddon.signaller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerGate;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerPlatformDesk;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerTrainDesk;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures;
import org.fetarute.fetaruteTCAddon.drive.crew.CallReason;
import org.fetarute.fetaruteTCAddon.drive.crew.CallResult;
import org.fetarute.fetaruteTCAddon.drive.crew.CallerRole;
import org.fetarute.fetaruteTCAddon.drive.crew.CrewDuty;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;
import org.fetarute.fetaruteTCAddon.signaller.console.ConsoleTarget;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 信号员核心：闸只把放行变成暂不放行、已在区内不拦、代排、自动通过、许可锁闭与作废、改名、下岗回自动、扣车与呼叫；控制台按不了时说明原因，被动的事有提醒， 没人管时退回自动。 */
class SignallerServiceTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID DRIVER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
  private static final UUID OTHER_DRIVER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
  private static final UUID GUARD = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
  private static final UUID ROUTE = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
  private static final UUID ROUTE_WEST = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final SignalBox.ConsoleSpot CONSOLE =
      new SignalBox.ConsoleSpot(YardFixtures.WORLD, 0, 64, 10);

  private FakeWorld world;
  private Instant now;
  private long tick;
  private List<String> messages;

  /** 各语言键最近一次提醒带的占位符。 */
  private Map<String, Map<String, String>> alerted;

  private List<SignallerShift> shifts;
  private SignallerConfig settings;
  private SignallerService service;

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    now = Instant.parse("2026-10-20T08:00:00Z");
    tick = 0;
    messages = new ArrayList<>();
    alerted = new HashMap<>();
    shifts = new ArrayList<>();
    settings = SignallerConfig.defaults();
    service =
        new SignallerService(
            world,
            () -> settings,
            () -> now,
            () -> tick,
            new SignallerMessenger() {
              @Override
              public void send(UUID player, String key, Map<String, String> values) {
                messages.add(player + " " + key);
              }

              @Override
              public void alert(UUID player, String key, Map<String, String> values) {
                messages.add(player + " " + key);
                alerted.put(key, values);
              }

              @Override
              public String text(String key) {
                return key.substring(key.lastIndexOf('.') + 1);
              }
            },
            message -> {});
    service.setSettlement(shifts::add);
    world.progress.put("T1", progress("T1", YardFixtures.IA));
  }

  @Test
  void admitsEverythingWhileNobodyIsOnDuty() {
    assertTrue(service.admit("T1", entering(), now).isEmpty());
    assertFalse(service.waiting("T1"));
  }

  @Test
  void blocksATrainEnteringAManualZoneWithoutPermit() {
    goOnDuty();

    Optional<SignallerGate.Hold> hold = service.admit("T1", entering(), now);

    assertTrue(hold.isPresent());
    assertEquals("SURC:ABC", hold.get().box());
    assertTrue(messages.contains(PLAYER + " signaller.cue.waiting"), "到入口等候响一声");
    assertTrue(hold.get().detail(now.plusSeconds(4)).endsWith("since=2026-10-20T08:00:00Z"));
    assertTrue(service.waiting("T1"));
  }

  @Test
  void neverBlocksRequestsOutsideTheZoneOrTrainsAlreadyInside() {
    goOnDuty();

    assertTrue(
        service
            .admit("T1", List.of(OccupancyResource.forNode(NodeId.of("SURC:XYZ:ABC:1:02"))), now)
            .isEmpty());
    world.claims.add(claim("T2", OccupancyResource.forNode(YardFixtures.P2)));
    tick++;
    assertTrue(service.admit("T2", entering(), now).isEmpty());
  }

  @Test
  void holdingTheEntrySwitchOrTailProtectionDoesNotCountAsInside() {
    goOnDuty();
    // 停在出入口外端：停车保持带上了区内那一头道岔的冲突资源
    world.claims.add(
        claim(
            "T1",
            OccupancyResource.forConflict("switcher:" + YardFixtures.SW1.value()),
            ClaimRole.HOLD_ONLY));
    // 刚出区的车尾防护
    world.claims.add(
        claim(
            "T1",
            OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.SW1, YardFixtures.T1)),
            ClaimRole.PROTECTIVE_RETAIN));
    tick++;

    assertTrue(service.admit("T1", entering(), now).isPresent());
  }

  @Test
  void cancellingKeepsTheOriginalWaitingStartSoAutoRouteStillComes() {
    settings = new SignallerConfig(true, 20, 32, 30, 150, 60, 3, 0);
    goOnDuty();
    service.admit("T1", entering(), now);
    now = now.plusSeconds(15);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    press(new ConsoleTarget.Press(SignalboxState.Button.CANCEL_ROUTE));

    assertTrue(service.admit("T1", entering(), now).isPresent());
    now = now.plusSeconds(5);
    assertTrue(service.admit("T1", entering(), now).isEmpty(), "代排按最初开始等的时刻算");
  }

  @Test
  void everyEntryCountsAsHandledAndOnlyRoutedTrainsCountForRewards() {
    goOnDuty();
    for (int round = 0; round < 2; round++) {
      service.admit("T1", entering(), now);
      press(new ConsoleTarget.Train("T1"));
      press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
      world.claims.add(
          claim(
              "T1",
              OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.SW1, YardFixtures.T1))));
      tick++;
      service.tick();
      world.claims.clear();
      tick++;
      service.tick();
    }

    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);
    assertEquals(2, shifts.get(0).trainsHandled());
    assertEquals(2, shifts.get(0).routesSet());
    assertEquals(0, shifts.get(0).autoRouted());
  }

  @Test
  void aVanishedZoneEndsTheDuty() {
    goOnDuty();
    world.zoneExists = false;

    service.tick();

    assertFalse(service.onDuty(PLAYER));
    assertEquals(SignallerShift.EndReason.REVOKED, shifts.get(0).endReason());
    assertTrue(service.admit("T1", entering(), now).isEmpty());
  }

  @Test
  void staleConsoleClicksDoNothing() {
    goOnDuty();
    // 没选车时排列进路是灰的：点了也不发许可
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    // 没有呼叫时点回复不会发出去
    press(new ConsoleTarget.Answer(SignalboxState.Reply.ACK));

    assertTrue(service.admit("T1", entering(), now).isPresent());
    assertFalse(messages.stream().anyMatch(m -> m.contains("signaller.reply")));
  }

  @Test
  void permitLetsTheTrainInAndLocksOnceItHoldsZoneResources() {
    goOnDuty();
    service.admit("T1", entering(), now);

    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));

    assertTrue(service.admit("T1", entering(), now).isEmpty());
    assertEquals(List.of("T1"), world.reevaluated);
    world.claims.add(
        claim(
            "T1", OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.SW1, YardFixtures.T1))));
    tick++;
    service.tick();
    SignalBox box = service.boxFor("SURC", "ABC").orElseThrow();
    assertEquals(SignalBox.PermitState.LOCKED, box.permits.get("t1").state);

    // 锁闭后不能取消
    press(new ConsoleTarget.Press(SignalboxState.Button.CANCEL_ROUTE));
    assertTrue(box.permits.containsKey("t1"));

    // 车出清控制区后许可作废
    world.claims.clear();
    tick++;
    service.tick();
    assertFalse(box.permits.containsKey("t1"));
  }

  @Test
  void pendingPermitCanBeCancelledAndTheTrainIsBlockedAgain() {
    goOnDuty();
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    press(new ConsoleTarget.Press(SignalboxState.Button.CANCEL_ROUTE));

    assertTrue(service.admit("T1", entering(), now).isPresent());
  }

  @Test
  void autoRoutesAfterTheConfiguredWait() {
    goOnDuty();
    assertTrue(service.admit("T1", entering(), now).isPresent());

    now = now.plusSeconds(19);
    assertTrue(service.admit("T1", entering(), now).isPresent());
    now = now.plusSeconds(1);
    assertTrue(service.admit("T1", entering(), now).isEmpty());
    enterZone("T1");

    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);
    assertEquals(1, shifts.get(0).autoRouted());
    assertEquals(1, shifts.get(0).trainsHandled());
  }

  @Test
  void autoRoutesOnTheSecondTickWhenTheTrainStillAsksRecently() {
    goOnDuty();
    service.admit("T1", entering(), now);
    now = now.plusSeconds(17);
    service.admit("T1", entering(), now);

    now = now.plusSeconds(3);
    tick++;
    service.tick();

    SignalBox box = service.boxFor("SURC", "ABC").orElseThrow();
    assertEquals(SignalBox.Source.TIMEOUT, box.permits.get("t1").source);
    assertEquals(List.of("T1"), world.reevaluated);
    assertFalse(service.waiting("T1"));
  }

  @Test
  void onlyWakesATrainThatStoppedAskingInsteadOfAutoRoutingIt() {
    goOnDuty();
    service.admit("T1", entering(), now);

    // 好一阵没来问闸：多半是内核自己在拦，不代排、不记到信号员头上，只唤醒它
    now = now.plusSeconds(20);
    tick++;
    service.tick();

    SignalBox box = service.boxFor("SURC", "ABC").orElseThrow();
    assertFalse(box.permits.containsKey("t1"));
    assertEquals(List.of("T1"), world.reevaluated);
    // 它再来问闸时当场代排
    assertTrue(service.admit("T1", entering(), now).isEmpty());
  }

  @Test
  void autoPassLetsLaterTrainsOnTheSameRouteThroughAtOnce() {
    goOnDuty();
    service.admit("T1", entering(), now);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.AUTO_PASS));
    // 设自动通过时正在等的车立刻放
    assertTrue(service.admit("T1", entering(), now).isEmpty());

    world.progress.put("T2", progress("T2", YardFixtures.IA));
    assertTrue(service.admit("T2", entering(), now).isEmpty());
    enterZone("T1", "T2");

    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);
    assertEquals(2, shifts.get(0).autoPassed());
    assertEquals(2, shifts.get(0).trainsHandled());
  }

  @Test
  void permitFollowsARename() {
    goOnDuty();
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));

    service.renamed("T1", "T9");

    assertTrue(service.admit("T9", entering(), now).isEmpty());
    world.progress.put("T1", progress("T1", YardFixtures.IA));
    assertTrue(service.admit("T1", entering(), now).isPresent());
  }

  @Test
  void goingOffDutyReturnsTheZoneToAutoAndSettlesTheShift() {
    goOnDuty();
    service.admit("T1", entering(), now);

    assertTrue(service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY));

    assertTrue(service.admit("T1", entering(), now).isEmpty());
    assertFalse(service.waiting("T1"));
    assertEquals(1, shifts.size());
    assertEquals(SignallerShift.EndReason.OFF_DUTY, shifts.get(0).endReason());
  }

  @Test
  void leavingTheConsoleEndsTheDutyAfterTheGracePeriod() {
    goOnDuty();
    world.spot = new SignallerWorld.PlayerSpot("Sig", YardFixtures.WORLD, 200, 64, 10, true);

    service.tick();
    now = now.plusSeconds(29);
    service.tick();
    assertTrue(service.onDuty(PLAYER));
    now = now.plusSeconds(1);
    service.tick();

    assertFalse(service.onDuty(PLAYER));
    assertEquals(SignallerShift.EndReason.LEFT_CONSOLE, shifts.get(0).endReason());
  }

  @Test
  void refusesDutyWhileServingAnotherRole() {
    service.setCrewBusy(player -> Optional.of(CrewDuty.Role.DRIVER));
    press(new ConsoleTarget.Train("T1"));

    assertEquals(SignallerService.DutyStart.BUSY_DRIVER, service.startDuty(PLAYER, "Sig", true));
    assertFalse(service.onDuty(PLAYER));
  }

  @Test
  void holdsOnlyADwellingTrainInsideTheZone() {
    goOnDuty();
    world.claims.add(claim("T1", OccupancyResource.forNode(YardFixtures.P1)));
    world.progress.put("T1", progress("T1", YardFixtures.P1));
    tick++;
    press(new ConsoleTarget.Train("T1"));

    press(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    assertTrue(service.holdTarget("T1").isEmpty());

    world.dwelling = true;
    press(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    assertEquals(Optional.of(now.plus(Duration.ofSeconds(150))), service.holdTarget("T1"));

    now = now.plusSeconds(1);
    press(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    assertTrue(service.holdTarget("T1").isEmpty());
  }

  @Test
  void trainOperationsOpenASecondPageThatBackCloses() {
    goOnDuty();
    world.claims.add(claim("T1", OccupancyResource.forNode(YardFixtures.P1)));
    world.progress.put("T1", progress("T1", YardFixtures.P1));
    tick++;
    press(new ConsoleTarget.Train("T1"));

    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    assertTrue(state().trainOps().isPresent());

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.BACK));
    assertFalse(state().trainOps().isPresent());
  }

  @Test
  void choosingAnotherTrainLeavesTheTrainOperations() {
    goOnDuty();
    world.claims.add(claim("T1", OccupancyResource.forNode(YardFixtures.P1)));
    world.progress.put("T1", progress("T1", YardFixtures.P1));
    service.admit("T2", entering(), now);
    tick++;
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Train("T2"));

    assertEquals(Optional.of("T2"), state().selected());
    assertFalse(state().trainOps().isPresent());
  }

  @Test
  void aClickOnEmptySpaceLeavesTheTrainOperationsButKeepsTheSelection() {
    goOnDuty();
    world.claims.add(claim("T1", OccupancyResource.forNode(YardFixtures.P1)));
    world.progress.put("T1", progress("T1", YardFixtures.P1));
    tick++;
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Deselect());

    assertFalse(state().trainOps().isPresent());
    assertEquals(Optional.of("T1"), state().selected());
  }

  @Test
  void theCallBadgeTakesOverFromTheTrainOperations() {
    goOnDuty();
    world.claims.add(claim("T1", OccupancyResource.forNode(YardFixtures.P1)));
    world.progress.put("T1", progress("T1", YardFixtures.P1));
    tick++;
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    service.call(DRIVER, CallerRole.DRIVER, "T1", CallReason.DOORS);

    press(new ConsoleTarget.CallBadge());

    assertTrue(state().replying());
    assertFalse(state().trainOps().isPresent());
  }

  @Test
  void theFirstCancelPressOnlyArmsTheCancellation() {
    goOnDuty();
    dwellAtPlatform("T1");
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertTrue(state().trainOps().orElseThrow().cancelArmed());
    assertTrue(world.trains.cancelled.isEmpty());
  }

  @Test
  void aSecondCancelPressConfirmsAndCancelsAnEmptyTrain() {
    goOnDuty();
    dwellAtPlatform("T1");
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    now = now.plusSeconds(2);
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertEquals(List.of("T1"), world.trains.cancelled);
  }

  @Test
  void aRepeatedCancelPressFromAHeldButtonDoesNotConfirm() {
    goOnDuty();
    dwellAtPlatform("T1");
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    // 按住右键时客户端每 0.2 秒连发一次：按多久都只算第一下，不会自己确认
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    for (int i = 0; i < 6; i++) {
      now = now.plusMillis(200);
      pressNow(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    }

    assertTrue(world.trains.cancelled.isEmpty());
    assertTrue(state().trainOps().orElseThrow().cancelArmed());

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    assertEquals(List.of("T1"), world.trains.cancelled, "松开再按一下才确认");
  }

  /** 按住“列车操作”翻到第二页后，连发落到同一位置的“取消车次”上：不算。 */
  @Test
  void aHeldPressCarriedOverToTheSecondPageDoesNothing() {
    goOnDuty();
    dwellAtPlatform("T1");
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    for (int i = 0; i < 6; i++) {
      now = now.plusMillis(200);
      pressNow(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    }

    assertFalse(state().trainOps().orElseThrow().cancelArmed());
    assertTrue(world.trains.cancelled.isEmpty());
  }

  /** 第二页没在显示时，旧画面上的点击不做第二页的操作。 */
  @Test
  void trainActionsNeedTheSecondPageShown() {
    goOnDuty();
    dwellAtPlatform("T1");
    press(new ConsoleTarget.Train("T1"));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertTrue(state().trainOps().isEmpty());
    assertNull(service.boxFor("SURC", "ABC").orElseThrow().cancelArm);
  }

  @Test
  void anArmedCancellationLapsesAfterFiveSeconds() {
    goOnDuty();
    dwellAtPlatform("T1");
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    now = now.plusSeconds(6);
    assertFalse(state().trainOps().orElseThrow().cancelArmed());
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertTrue(world.trains.cancelled.isEmpty());
    assertTrue(state().trainOps().orElseThrow().cancelArmed());
  }

  @Test
  void withPlayersAboardTheTrainIsHeldAnnouncedAndCancelledFiveSecondsLater() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.trains.scenes.put("T1", new SignallerTrainDesk.Scene(true, true, false));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    now = now.plusSeconds(2);
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertEquals(List.of("T1"), world.trains.announced);
    assertTrue(world.trains.cancelled.isEmpty());
    assertTrue(service.holdTarget("T1").isPresent());

    now = now.plusSeconds(5);
    tick++;
    service.tick();
    assertEquals(List.of("T1"), world.trains.cancelled);
  }

  /** 已确认取消、车上有人：一列车走到确认那一步。 */
  private void confirmCancelWithPlayersAboard(String train) {
    goOnDuty();
    dwellAtPlatform(train);
    world.trains.scenes.put(train, new SignallerTrainDesk.Scene(true, true, false));
    press(new ConsoleTarget.Train(train));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
  }

  /** 停运倒计时中：扣车键放不走车，再按取消不算第二次；确认后回到第一页并提示已取消。 */
  @Test
  void duringTheCountdownTheTrainCannotBeReleasedOrCancelledTwice() {
    confirmCancelWithPlayersAboard("T1");
    assertEquals(Optional.of(SignalboxState.NoticeKind.TRIP_CANCELLED), noticeKind());
    assertTrue(state().trainOps().isEmpty(), "确认后回到第一页");

    press(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    assertTrue(service.holdTarget("T1").isPresent());
    assertEquals(Optional.of(SignalboxState.NoticeKind.CANCEL_PENDING), noticeKind());

    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    assertEquals(Optional.of(SignalboxState.NoticeKind.CANCEL_PENDING), noticeKind());
    assertEquals(List.of("T1"), world.trains.announced, "停运只播一次");
  }

  /** 倒计时中车被改名：倒计时随车走，到点照样删。 */
  @Test
  void theCountdownFollowsARename() {
    confirmCancelWithPlayersAboard("T1");

    service.renamed("T1", "T9");
    world.trains.scenes.put("T9", new SignallerTrainDesk.Scene(true, true, false));
    now = now.plusSeconds(5);
    tick++;
    service.tick();

    assertEquals(List.of("T9"), world.trains.cancelled);
    assertTrue(service.holdTarget("T9").isEmpty());
  }

  /** 倒计时中下岗：扣车随下岗解除，已告知停运的车不能带着乘客开走，当场办完。 */
  @Test
  void goingOffDuringTheCountdownCarriesTheCancelOut() {
    confirmCancelWithPlayersAboard("T1");

    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);

    assertEquals(List.of("T1"), world.trains.cancelled);
  }

  @Test
  void aTrainWithPlayersAboardOutsideAPlatformCannotBeCancelled() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.trains.scenes.put("T1", new SignallerTrainDesk.Scene(true, false, false));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertEquals(Optional.of(SignalboxState.NoticeKind.CANCEL_OCCUPIED), noticeKind());
    assertFalse(state().trainOps().orElseThrow().cancelArmed());
    now = now.plusSeconds(2);
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    assertTrue(world.trains.announced.isEmpty());
    assertTrue(world.trains.cancelled.isEmpty());
  }

  @Test
  void theConfirmingPressChecksAgainThatAnOccupiedTrainIsStillAtThePlatform() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.trains.scenes.put("T1", new SignallerTrainDesk.Scene(true, true, false));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    world.trains.scenes.put("T1", new SignallerTrainDesk.Scene(true, false, false));
    now = now.plusSeconds(2);
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertEquals(Optional.of(SignalboxState.NoticeKind.CANCEL_OCCUPIED), noticeKind());
    assertTrue(world.trains.announced.isEmpty());
    assertTrue(world.trains.cancelled.isEmpty());
  }

  /** 往后没有停车站（或没接上调度）：说清楚是没有可越的站，不说成终点或不归本区。 */
  @Test
  void withNoStopAheadTheNoticeSaysSo() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.delays.put("T1", 260L);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));

    assertEquals(Optional.of(SignalboxState.NoticeKind.SKIP_NO_STOP), noticeKind());
    assertTrue(world.trains.ordered.isEmpty());
  }

  /** 联挂中的一体车带着两条交路：不能越站。 */
  @Test
  void aCoupledTrainCannotSkip() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.delays.put("T1", 260L);
    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, false, Optional.empty()));
    world.trains.scenes.put("T1", new SignallerTrainDesk.Scene(false, true, true));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));

    assertEquals(Optional.of(SignalboxState.NoticeKind.SKIP_COUPLED), noticeKind());
    assertTrue(world.trains.ordered.isEmpty());
  }

  @Test
  void aCoupledTrainCannotBeCancelled() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.trains.scenes.put("T1", new SignallerTrainDesk.Scene(false, true, true));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    now = now.plusSeconds(2);
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertEquals(Optional.of(SignalboxState.NoticeKind.CANCEL_COUPLED), noticeKind());
    assertTrue(world.trains.cancelled.isEmpty());
  }

  @Test
  void cancellingNeedsItsOwnPermission() {
    goOnDuty();
    dwellAtPlatform("T1");
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    pressWithoutCancelPermission(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    now = now.plusSeconds(2);
    pressWithoutCancelPermission(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertEquals(Optional.of(SignalboxState.NoticeKind.CANCEL_NO_PERMISSION), noticeKind());
    assertTrue(world.trains.cancelled.isEmpty());
  }

  @Test
  void noTripIsCancelledDuringAnExam() {
    service.setExaminer(
        new SignallerExaminer() {
          @Override
          public boolean examining(UUID player) {
            return true;
          }

          @Override
          public void onDutyStarted(
              UUID player, String zoneName, boolean manual, boolean stations) {}

          @Override
          public void onEvent(UUID player, Event event) {}

          @Override
          public void onDutyEnded(UUID player, SignallerShift.EndReason reason) {}
        });
    goOnDuty();
    dwellAtPlatform("T1");
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    now = now.plusSeconds(2);
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertEquals(Optional.of(SignalboxState.NoticeKind.CANCEL_IN_EXAM), noticeKind());
    assertTrue(world.trains.cancelled.isEmpty());
  }

  /** 在入口等许可的车还停在区外（多半是邻区的站台上）：不归本楼取消。 */
  @Test
  void aTrainWaitingAtTheEntryIsNotYetOursToCancel() {
    goOnDuty();
    service.admit("T1", entering(), now);
    press(new ConsoleTarget.Train("T1"));
    assertEquals(SignalboxState.RowStatus.WAITING, state().rows().get(0).status());
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertEquals(Optional.of(SignalboxState.NoticeKind.CANCEL_OUT_OF_ZONE), noticeKind());
    assertFalse(state().trainOps().orElseThrow().cancelArmed());
  }

  @Test
  void anApproachingTrainIsNotYetOursToCancel() {
    goOnDuty();
    tick++;
    press(new ConsoleTarget.Train("T1"));
    assertEquals(SignalboxState.RowStatus.APPROACHING, state().rows().get(0).status());
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    now = now.plusSeconds(2);
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertEquals(Optional.of(SignalboxState.NoticeKind.CANCEL_OUT_OF_ZONE), noticeKind());
    assertTrue(world.trains.cancelled.isEmpty());
  }

  @Test
  void aCancelledTripIsCountedInTheShiftAndToldToTheExaminer() {
    List<String> heard = new ArrayList<>();
    service.setExaminer(recording(heard));
    goOnDuty();
    dwellAtPlatform("T1");
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    now = now.plusSeconds(2);
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    assertTrue(heard.contains("TRIP_CANCELLED"));
    assertTrue(service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY));
    assertEquals(1, shifts.get(0).tripsCancelled());
  }

  @Test
  void aCountdownIsCalledOffWhenTheOccupiedTrainHasLeftThePlatform() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.trains.scenes.put("T1", new SignallerTrainDesk.Scene(true, true, false));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));
    now = now.plusSeconds(2);
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL));

    world.trains.scenes.put("T1", new SignallerTrainDesk.Scene(true, false, false));
    now = now.plusSeconds(5);
    tick++;
    service.tick();

    assertTrue(world.trains.cancelled.isEmpty());
    assertTrue(service.holdTarget("T1").isEmpty());
  }

  @Test
  void aTrainNotLateEnoughIsNotSkipped() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.delays.put("T1", 80L);
    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, false, Optional.empty()));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));

    assertEquals(Optional.of(SignalboxState.NoticeKind.SKIP_NOT_LATE), noticeKind());
    assertTrue(world.trains.ordered.isEmpty());
  }

  @Test
  void aLateTrainIsOrderedToSkipTheNextStopInTheZone() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.delays.put("T1", 260L);
    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, false, Optional.empty()));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));

    assertEquals(List.of("T1"), world.trains.ordered);
  }

  @Test
  void theNextStopMustBeInThisZoneToBeSkipped() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.delays.put("T1", 260L);
    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(NodeId.of("SURC:S:XYZ:1"), false, Optional.empty()));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));

    assertEquals(Optional.of(SignalboxState.NoticeKind.SKIP_OUT_OF_ZONE), noticeKind());
    assertTrue(world.trains.ordered.isEmpty());
  }

  @Test
  void pressingSkipAgainRevokesTheOrder() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.delays.put("T1", 260L);
    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, true, Optional.empty()));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));

    assertEquals(List.of("T1"), world.trains.revoked);
    assertTrue(world.trains.ordered.isEmpty());
    assertEquals(Optional.of(SignalboxState.NoticeKind.SKIP_REVOKED), noticeKind());

    world.trains.revokeRefusal = Optional.of(SignallerTrainDesk.SkipRefusal.BRAKING);
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));
    assertEquals(Optional.of(SignalboxState.NoticeKind.SKIP_BRAKING), noticeKind());
  }

  @Test
  void aStopThatMustNotBeSkippedSaysWhy() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.delays.put("T1", 260L);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    Map<SignallerTrainDesk.SkipRefusal, SignalboxState.NoticeKind> expected =
        Map.of(
            SignallerTrainDesk.SkipRefusal.NO_STOP, SignalboxState.NoticeKind.SKIP_NO_STOP,
            SignallerTrainDesk.SkipRefusal.TERMINAL, SignalboxState.NoticeKind.SKIP_TERMINAL,
            SignallerTrainDesk.SkipRefusal.DIRECTIVE, SignalboxState.NoticeKind.SKIP_DIRECTIVE,
            SignallerTrainDesk.SkipRefusal.BRAKING, SignalboxState.NoticeKind.SKIP_BRAKING,
            SignallerTrainDesk.SkipRefusal.CALL_DESTINATION,
                SignalboxState.NoticeKind.SKIP_CALL_DESTINATION,
            SignallerTrainDesk.SkipRefusal.CREW_BOARDING,
                SignalboxState.NoticeKind.SKIP_CREW_BOARDING);
    assertEquals(SignallerTrainDesk.SkipRefusal.values().length, expected.size());

    for (Map.Entry<SignallerTrainDesk.SkipRefusal, SignalboxState.NoticeKind> entry :
        expected.entrySet()) {
      world.trains.skips.put(
          "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, false, Optional.of(entry.getKey())));
      press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));
      assertEquals(Optional.of(entry.getValue()), noticeKind(), entry.getKey().name());
    }
    assertTrue(world.trains.ordered.isEmpty());
  }

  @Test
  void anOrderedSkipIsAnnouncedCountedAndToldToTheExaminer() {
    List<String> heard = new ArrayList<>();
    service.setExaminer(recording(heard));
    goOnDuty();
    dwellAtPlatform("T1");
    world.delays.put("T1", 260L);
    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, false, Optional.empty()));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));

    assertEquals(Optional.of(SignalboxState.NoticeKind.SKIP_ORDERED), noticeKind());
    assertTrue(heard.contains("SKIP_ORDERED"));
    assertTrue(service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY));
    assertEquals(1, shifts.get(0).skipsOrdered());
  }

  @Test
  void skippingIsLeftOutOfExamsButAllowedInPractice() {
    boolean[] practicing = {false};
    service.setExaminer(
        new SignallerExaminer() {
          @Override
          public boolean examining(UUID player) {
            return true;
          }

          @Override
          public boolean practicing(UUID player) {
            return practicing[0];
          }

          @Override
          public void onDutyStarted(
              UUID player, String zoneName, boolean manual, boolean stations) {}

          @Override
          public void onEvent(UUID player, Event event) {}

          @Override
          public void onDutyEnded(UUID player, SignallerShift.EndReason reason) {}
        });
    goOnDuty();
    dwellAtPlatform("T1");
    world.delays.put("T1", 260L);
    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, false, Optional.empty()));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));
    assertEquals(Optional.of(SignalboxState.NoticeKind.SKIP_IN_EXAM), noticeKind());
    assertTrue(world.trains.ordered.isEmpty());

    practicing[0] = true;
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));
    assertEquals(List.of("T1"), world.trains.ordered);
  }

  @Test
  void theTrainOperationsShowWhichStationWouldBeSkipped() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, false, Optional.empty()));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));

    SignalboxState.TrainOps ops = state().trainOps().orElseThrow();
    assertEquals(Optional.of("ABC"), ops.skipStation());
    assertFalse(ops.skipOrdered());

    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, true, Optional.empty()));
    tick++;
    assertTrue(state().trainOps().orElseThrow().skipOrdered());
  }

  @Test
  void aTrainOrderedToSkipSaysSoInTheTrainList() {
    goOnDuty();
    dwellAtPlatform("T1");
    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, false, Optional.empty()));
    assertEquals(Optional.empty(), row("T1").skipping());

    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, true, Optional.empty()));
    tick++;

    assertEquals(Optional.of("ABC"), row("T1").skipping());
  }

  private SignalboxState.TrainRow row(String train) {
    return state().rows().stream()
        .filter(row -> row.trainName().equals(train))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void theHoldKeyReleasesTheTrainItHolds() {
    goOnDuty();
    world.claims.add(claim("T1", OccupancyResource.forNode(YardFixtures.P1)));
    world.progress.put("T1", progress("T1", YardFixtures.P1));
    world.dwelling = true;
    tick++;
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    assertTrue(service.holdTarget("T1").isPresent());
    assertTrue(state().enabled(SignalboxState.Button.HOLD));

    now = now.plusSeconds(1);
    press(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    assertTrue(service.holdTarget("T1").isEmpty());
  }

  @Test
  void aRepeatedPressRightAfterHoldingKeepsTheTrainHeld() {
    goOnDuty();
    world.claims.add(claim("T1", OccupancyResource.forNode(YardFixtures.P1)));
    world.progress.put("T1", progress("T1", YardFixtures.P1));
    world.dwelling = true;
    tick++;
    press(new ConsoleTarget.Train("T1"));

    // 按住右键时客户端会连发
    press(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    for (int i = 0; i < 6; i++) {
      now = now.plusMillis(200);
      pressNow(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    }

    assertTrue(service.holdTarget("T1").isPresent(), "按住多久都不会把刚扣的车放走");
  }

  @Test
  void driverCallReachesTheSignallerAndTheReplyGoesBack() {
    assertEquals(CallResult.Status.NO_SIGNALLER, driverCall("T1"));
    goOnDuty();

    assertEquals(
        new CallResult(CallResult.Status.CALLED, List.of("示例站")),
        service.call(DRIVER, CallerRole.DRIVER, "T1", CallReason.GENERAL));
    SignalboxState state = service.state("SURC", "ABC").orElseThrow();
    assertEquals(
        Optional.of(new SignalboxState.Call("T1", "Drv", CallerRole.DRIVER, CallReason.GENERAL)),
        state.call());
    assertEquals("driver Drv", alerted.get("signaller.call.incoming-general").get("caller"));

    press(new ConsoleTarget.CallBadge());
    press(new ConsoleTarget.Answer(SignalboxState.Reply.WAIT));

    assertTrue(messages.contains(DRIVER + " signaller.reply.wait"));
    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);
    assertEquals(1, shifts.get(0).callsAnswered());
  }

  @Test
  void consoleListsTheWaitingTrainWithAStopSignalAtItsEntry() {
    goOnDuty();
    service.admit("T1", entering(), now);
    now = now.plusSeconds(5);
    tick++;

    SignalboxState state = service.state("SURC", "ABC").orElseThrow();

    assertEquals(SignalboxState.Mode.MANUAL, state.mode());
    assertEquals(SignalboxState.RowStatus.WAITING, state.rows().get(0).status());
    assertEquals(5, state.rows().get(0).seconds());
    EdgeId entry = EdgeId.undirected(YardFixtures.IA, YardFixtures.SW1);
    SignalboxState.EntrySignal signal =
        state.signals().stream().filter(s -> s.exit().equals(entry)).findFirst().orElseThrow();
    assertEquals(SignalboxState.Aspect.STOP, signal.aspect());
    assertEquals(Optional.of("T1"), signal.waitingTrain());
  }

  @Test
  void aFreshServiceIsAlwaysAuto() {
    goOnDuty();
    SignallerService restarted =
        new SignallerService(
            world, SignallerConfig::defaults, () -> now, () -> tick, new NoMessages(), m -> {});

    assertTrue(restarted.admit("T1", entering(), now).isEmpty());
  }

  @Test
  void aReplyNeverGoesToTheNextDriverAfterTheFirstCallTimesOut() {
    goOnDuty();
    world.progress.put("T2", progress("T2", YardFixtures.IA));
    driverCall("T1");
    now = now.plusSeconds(30);
    service.call(OTHER_DRIVER, CallerRole.DRIVER, "T2", CallReason.GENERAL);
    press(new ConsoleTarget.CallBadge());

    // 第一个呼叫超时出队：回复面板退回按钮，画面上那条回复不能落到 T2 的驾驶员头上
    now = now.plusSeconds(31);
    service.tick();
    press(new ConsoleTarget.Answer(SignalboxState.Reply.WAIT));

    assertFalse(messages.stream().anyMatch(m -> m.contains("signaller.reply")));
    assertEquals(1, service.boxFor("SURC", "ABC").orElseThrow().calls.size());
  }

  /** 车掌呼叫：控制台写明是车掌在叫，回复回给车掌，不回给同车的驾驶员。 */
  @Test
  void aGuardCallShowsTheGuardOnTheConsoleAndTheReplyGoesToTheGuard() {
    goOnDuty();

    assertEquals(
        CallResult.Status.CALLED,
        service.call(GUARD, CallerRole.GUARD, "T1", CallReason.DOORS).status());
    assertEquals(
        Optional.of(new SignalboxState.Call("T1", "Grd", CallerRole.GUARD, CallReason.DOORS)),
        state().call());
    assertEquals("guard Grd", alerted.get("signaller.call.incoming-doors").get("caller"));

    press(new ConsoleTarget.CallBadge());
    press(new ConsoleTarget.Answer(SignalboxState.Reply.ACK));

    assertTrue(messages.contains(GUARD + " signaller.reply.ack"));
    assertFalse(messages.contains(DRIVER + " signaller.reply.ack"));
    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);
    assertEquals(1, shifts.get(0).callsAnswered());
    assertEquals(0, shifts.get(0).callsWrong());
  }

  /** 同一列车的驾驶员与车掌各排各的：各自重复呼叫只算一条，各自写明的新原因只换自己那条。超时未回复的告诉各自。 */
  @Test
  void theDriverAndTheGuardOfOneTrainQueueSeparately() {
    goOnDuty();
    service.admit("T1", entering(), now);

    assertEquals(CallResult.Status.CALLED, driverCall("T1"));
    assertEquals(
        CallResult.Status.CALLED,
        service.call(GUARD, CallerRole.GUARD, "T1", CallReason.DOORS).status());
    assertEquals(CallResult.Status.PENDING, driverCall("T1"));
    assertEquals(
        CallResult.Status.CALLED,
        service.call(GUARD, CallerRole.GUARD, "T1", CallReason.OBSTRUCTION).status());

    SignalBox box = service.boxFor("SURC", "ABC").orElseThrow();
    assertEquals(2, box.calls.size());
    assertEquals(CallerRole.DRIVER, box.calls.peekFirst().role());
    assertEquals(CallReason.SIGNAL, box.calls.peekFirst().reason());
    assertEquals(CallerRole.GUARD, box.calls.peekLast().role());
    assertEquals(CallReason.OBSTRUCTION, box.calls.peekLast().reason());

    now = now.plusSeconds(61);
    service.tick();
    assertTrue(messages.contains(DRIVER + " signaller.call.unanswered"));
    assertTrue(messages.contains(GUARD + " signaller.call.unanswered"));
  }

  /** 叫不到时说明原因：功能关着、没人值守、有人值守但列车不在区内也没接近（认不出交路时不列前方的控制区）。 */
  @Test
  void aCallThatReachesNobodySaysWhy() {
    assertEquals(CallResult.Status.NO_SIGNALLER, driverCall("T1"));
    goOnDuty();

    assertEquals(
        CallResult.of(CallResult.Status.OUT_OF_ZONE),
        service.call(DRIVER, CallerRole.DRIVER, "T5", CallReason.GENERAL),
        "T5 不在区内、没接近、没在入口等，也没有交路进度");
    assertTrue(service.boxFor("SURC", "ABC").orElseThrow().calls.isEmpty());

    settings = new SignallerConfig(false, 20, 32, 30, 150, 60, 3, 0);
    assertEquals(CallResult.Status.UNAVAILABLE, driverCall("T1"));
  }

  /** 叫不到时列出列车沿交路往后会经过的有人值守的控制区：还要先在区外停站的车叫不到，但知道接近哪片再叫；不在途经点上、只是路过的控制区也算。 */
  @Test
  void aCallFromOutsideNamesTheStaffedZonesAhead() {
    useCorridorWithZoneDef(
        List.of(
            YardFixtures.D1, YardFixtures.P1, YardFixtures.IB, YardFixtures.Q1, YardFixtures.IC));
    world.progress.put("T6", at("T6", 0, YardFixtures.D1, YardFixtures.P1));
    onDuty(PLAYER, "DEF");

    assertEquals(
        new CallResult(CallResult.Status.OUT_OF_ZONE, List.of("DEF 站")),
        service.call(DRIVER, CallerRole.DRIVER, "T6", CallReason.GENERAL),
        "T6 还要先在 ABC 停；ABC 没人值守，不列");
    assertTrue(service.boxFor("SURC", "DEF").orElseThrow().calls.isEmpty());

    world.route =
        new RouteDefinition(
            RouteId.of("SURC:L1:R1"),
            List.of(YardFixtures.D1, YardFixtures.P1, YardFixtures.IC),
            Optional.empty());
    assertEquals(List.of("DEF 站"), service.probe(DRIVER, "T6").zones(), "途经点不在区内，沿图展开会穿过 DEF");
  }

  /** 弹框前先问能不能叫到：不排队；叫得到时写明哪片控制区，已在等回复时为“已呼叫”，叫不到时与真呼叫同样说明原因。 */
  @Test
  void aProbeSaysWhereACallWouldGoWithoutQueueing() {
    assertEquals(CallResult.Status.NO_SIGNALLER, service.probe(DRIVER, "T1").status());
    goOnDuty();

    assertEquals(
        new CallResult(CallResult.Status.CALLED, List.of("示例站")), service.probe(DRIVER, "T1"));
    SignalBox box = service.boxFor("SURC", "ABC").orElseThrow();
    assertTrue(box.calls.isEmpty(), "只是问一下，不排队");
    assertTrue(messages.stream().noneMatch(m -> m.contains("signaller.call.incoming")));

    driverCall("T1");
    assertEquals(CallResult.Status.PENDING, service.probe(DRIVER, "T1").status());
    assertEquals(CallResult.Status.CALLED, service.probe(GUARD, "T1").status(), "车掌那条还没排");
    assertEquals(CallResult.Status.OUT_OF_ZONE, service.probe(DRIVER, "T5").status());

    settings = new SignallerConfig(false, 20, 32, 30, 150, 60, 3, 0);
    assertEquals(CallResult.Status.UNAVAILABLE, service.probe(DRIVER, "T1").status());
  }

  @Test
  void theCallBadgeSelectsTheCallingTrain() {
    goOnDuty();
    driverCall("T1");

    press(new ConsoleTarget.CallBadge());

    assertEquals(Optional.of("T1"), state().selected());
  }

  @Test
  void aTrainThatStoppedAskingIsWokenAtMostEveryTenSeconds() {
    goOnDuty();
    service.admit("T1", entering(), now);

    now = now.plusSeconds(20);
    service.tick();
    now = now.plusSeconds(1);
    service.tick();
    assertEquals(List.of("T1"), world.reevaluated);

    now = now.plusSeconds(9);
    service.tick();
    assertEquals(List.of("T1", "T1"), world.reevaluated);
  }

  @Test
  void holdingTheToggleButtonsDownCountsAsOnePress() {
    goOnDuty();

    press(new ConsoleTarget.Press(SignalboxState.Button.MODE));
    // 按住右键时客户端连发：紧接着的重复按下不算，不会刚转自动又转回人工
    now = now.plusMillis(200);
    pressNow(new ConsoleTarget.Press(SignalboxState.Button.MODE));
    assertEquals(SignalboxState.Mode.AUTO, state().mode());

    press(new ConsoleTarget.Press(SignalboxState.Button.MODE));
    assertEquals(SignalboxState.Mode.MANUAL, state().mode());
  }

  @Test
  void autoPassSurvivesASwitchToAutoAndBack() {
    goOnDuty();
    service.admit("T1", entering(), now);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.AUTO_PASS));

    now = now.plusSeconds(1);
    press(new ConsoleTarget.Press(SignalboxState.Button.MODE));
    now = now.plusSeconds(1);
    press(new ConsoleTarget.Press(SignalboxState.Button.MODE));

    world.progress.put("T2", progress("T2", YardFixtures.IA));
    assertTrue(service.admit("T2", entering(), now).isEmpty());
  }

  @Test
  void refusedPressesSayWhy() {
    goOnDuty();
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    assertEquals(Optional.of(SignalboxState.NoticeKind.NO_SELECTION), noticeKind());

    service.admit("T1", entering(), now);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    assertEquals(Optional.of(SignalboxState.NoticeKind.NOT_DWELLING), noticeKind());
    press(new ConsoleTarget.Press(SignalboxState.Button.CANCEL_ROUTE));
    assertEquals(Optional.of(SignalboxState.NoticeKind.NO_PERMIT), noticeKind());

    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    assertEquals(Optional.of(SignalboxState.NoticeKind.ALREADY_ROUTED), noticeKind());
  }

  @Test
  void settingARouteMovesTheSelectionToTheNextWaitingTrain() {
    goOnDuty();
    world.progress.put("T2", progress("T2", YardFixtures.IA));
    world.progress.put("T3", progress("T3", YardFixtures.IA));
    service.admit("T1", entering(), now);
    now = now.plusSeconds(2);
    service.admit("T3", entering(), now);
    now = now.plusSeconds(2);
    service.admit("T2", entering(), now);

    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));

    assertEquals(Optional.of("T3"), state().selected(), "按等候先后选下一列");
  }

  @Test
  void anUnreadyGraphShowsAutoWithAStandingNotice() {
    goOnDuty();
    world.graphReady = false;
    tick++;

    SignalboxState state = state();

    assertEquals(SignalboxState.Mode.AUTO, state.mode());
    assertEquals(
        Optional.of(SignalboxState.NoticeKind.GRAPH_NOT_READY_AUTO),
        state.notice().map(SignalboxState.Notice::kind));
    assertTrue(service.admit("T1", entering(), now).isEmpty(), "图没就绪时闸本来就放行");
  }

  @Test
  void threeAutoRoutesWithoutInputSwitchTheZoneToAuto() {
    goOnDuty();
    List<String> trains = List.of("T1", "T2", "T3");
    for (String train : trains) {
      world.progress.put(train, progress(train, YardFixtures.IA));
      service.admit(train, entering(), now);
    }
    now = now.plusSeconds(20);
    for (String train : trains) {
      assertTrue(service.admit(train, entering(), now).isEmpty());
    }

    service.tick();

    assertEquals(SignalboxState.Mode.AUTO, state().mode());
    assertTrue(service.onDuty(PLAYER), "值守不变，可再转回人工");
    assertTrue(messages.contains(PLAYER + " signaller.duty.idle-auto"));
    assertTrue(messages.contains(PLAYER + " signaller.alert.auto-routed"));
  }

  @Test
  void anyInputBetweenAutoRoutesKeepsTheZoneManual() {
    goOnDuty();
    List<String> trains = List.of("T1", "T2", "T3");
    for (String train : trains) {
      world.progress.put(train, progress(train, YardFixtures.IA));
      service.admit(train, entering(), now);
    }
    now = now.plusSeconds(20);
    service.admit("T1", entering(), now);
    service.admit("T2", entering(), now);
    press(new ConsoleTarget.Train("T3"));
    service.admit("T3", entering(), now);

    service.tick();

    assertEquals(SignalboxState.Mode.MANUAL, state().mode());
  }

  @Test
  void theSelectionIsClearedOnceTheTrainHasLeft() {
    goOnDuty();
    press(new ConsoleTarget.Train("T1"));
    world.progress.remove("T1");

    service.tick();
    now = now.plusSeconds(4);
    service.tick();
    SignalBox box = service.boxFor("SURC", "ABC").orElseThrow();
    assertEquals("t1", box.selected);

    now = now.plusSeconds(1);
    service.tick();
    assertEquals(null, box.selected);
  }

  @Test
  void cancellingPastTheAutoRouteDeadlineWarnsItComesAtOnce() {
    settings = new SignallerConfig(true, 20, 32, 30, 150, 60, 3, 0);
    goOnDuty();
    service.admit("T1", entering(), now);
    now = now.plusSeconds(25);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));

    press(new ConsoleTarget.Press(SignalboxState.Button.CANCEL_ROUTE));

    assertEquals(Optional.of(SignalboxState.NoticeKind.AUTO_ROUTE_DUE), noticeKind());
  }

  @Test
  void clickingAnAutoPassSignalWithNoTrainWaitingClearsIt() {
    goOnDuty();
    service.admit("T1", entering(), now);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.AUTO_PASS));
    SignalBox box = service.boxFor("SURC", "ABC").orElseThrow();
    assertEquals(1, box.autoPasses.size());

    // 区间两端先后与入口记的相反也认
    press(new ConsoleTarget.Signal(new EdgeId(YardFixtures.SW1, YardFixtures.IA)));

    assertTrue(box.autoPasses.isEmpty());
    assertEquals(Optional.of(SignalboxState.NoticeKind.AUTO_PASS_OFF), noticeKind());
  }

  @Test
  void dynamicStopsCountAsOneRouteWhicheverPlatformIsTentative() {
    world.desk = new TentativePlatforms(Map.of("t1", YardFixtures.P2, "t2", YardFixtures.P1));
    goOnDuty();
    service.admit("T1", entering(), now);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.AUTO_PASS));

    world.progress.put("T2", progress("T2", YardFixtures.IA));

    assertTrue(service.admit("T2", entering(), now).isEmpty());
  }

  @Test
  void dutyIsAnnouncedAndTheBoxesArePublishedForOtherThreads() {
    List<String> events = new ArrayList<>();
    service.setDutyEvents(
        new SignallerService.DutyEvents() {
          @Override
          public void started(
              UUID player, String operator, String zone, String zoneName, boolean manual) {
            events.add("start " + operator + ":" + zone + " " + zoneName + " manual=" + manual);
            // 发出前已发布：监听方读到的是值守之后的概况
            events.add("signaller=" + service.published().get(0).signallerId().orElseThrow());
          }

          @Override
          public void ended(SignallerShift shift, String zoneName) {
            events.add("end " + shift.endReason() + " routes=" + shift.routesSet());
          }
        });
    goOnDuty();
    service.admit("T1", entering(), now);
    now = now.plusSeconds(2);
    service.tick();

    SignallerService.BoxStatus box = service.published().get(0);
    assertEquals(SignalboxState.Mode.MANUAL, box.mode());
    assertEquals(Optional.of(now.minusSeconds(2)), box.onDutySince());
    assertEquals(List.of("T1"), box.waitingTrains());

    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);

    assertEquals(
        List.of("start SURC:ABC 示例站 manual=true", "signaller=" + PLAYER, "end OFF_DUTY routes=0"),
        events);
    assertEquals(SignalboxState.Mode.AUTO, service.published().get(0).mode());
    assertTrue(service.published().get(0).signallerId().isEmpty());
  }

  /** 考官（操作许可证）听到上岗、控制台上的每件事与下岗；考试、练习的这一班结算时带着标记。 */
  @Test
  void theExaminerHearsEveryActionAndTheExamShiftIsMarked() {
    List<String> heard = new ArrayList<>();
    boolean[] practice = {false};
    service.setExaminer(
        new SignallerExaminer() {
          @Override
          public boolean examining(UUID player) {
            return PLAYER.equals(player);
          }

          @Override
          public boolean practicing(UUID player) {
            return practice[0];
          }

          @Override
          public void onDutyStarted(
              UUID player, String zoneName, boolean manual, boolean stations) {
            heard.add("start " + zoneName + " manual=" + manual + " stations=" + stations);
          }

          @Override
          public void onEvent(UUID player, Event event) {
            heard.add(event.name());
          }

          @Override
          public void onDutyEnded(UUID player, SignallerShift.EndReason reason) {
            heard.add("end " + reason);
          }
        });
    goOnDuty();
    service.admit("T1", entering(), now);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    enterZone("T1");
    world.progress.put("T2", progress("T2", YardFixtures.IA));
    service.admit("T2", entering(), now);
    now = now.plusSeconds(20);
    assertTrue(service.admit("T2", entering(), now).isEmpty(), "等满时限由系统代排");
    enterZone("T2");
    assertEquals(
        CallResult.Status.CALLED,
        service.call(DRIVER, CallerRole.DRIVER, "T2", CallReason.GENERAL).status());
    now = now.plusSeconds(61);
    service.tick();
    press(new ConsoleTarget.Press(SignalboxState.Button.MODE));
    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);

    assertEquals(
        List.of(
            "start 示例站 manual=true stations=true",
            "SELECTED",
            "ROUTE_SET",
            "ENTERED_MANUAL",
            "ENTERED_AUTO_ROUTED",
            "CALL_INCOMING",
            "CALL_MISSED",
            "AUTO",
            "end OFF_DUTY"),
        heard);
    assertTrue(shifts.get(0).examined());
    assertFalse(shifts.get(0).practiced());

    practice[0] = true;
    goOnDuty();
    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);
    assertTrue(shifts.get(1).practiced());
  }

  /**
   * 区外车站与控制区之间隔着区间点：车停在那一站、或从那一站开出来就算接近，不必等它开过最后一个区间点（那时往往已在入口被拦下）；还要先在区外别的站停的车不算。
   * 新接近的车响一声，上岗时已在接近的不响，一列车只响一次。
   */
  @Test
  void aTrainLeavingTheLastStationBeforeTheZoneIsApproaching() {
    world.graph = YardFixtures.corridor();
    world.route =
        new RouteDefinition(
            RouteId.of("SURC:L1:R1"),
            List.of(
                YardFixtures.D1,
                YardFixtures.P1,
                YardFixtures.IB,
                YardFixtures.Q1,
                YardFixtures.IC),
            Optional.empty());
    world.zones.put(
        "DEF",
        new SignallerWorld.Zone("SURC", "DEF", "DEF 站", List.of(YardArea.Member.station("DEF"))));
    world.progress.clear();
    world.progress.put("T5", at("T5", 1, YardFixtures.P1, YardFixtures.IB));
    world.progress.put("T6", at("T6", 0, YardFixtures.D1, YardFixtures.P1));
    onDuty(PLAYER, "DEF");
    service.tick();

    SignalboxState console = service.state("SURC", "DEF").orElseThrow();
    assertEquals(
        List.of("T5"),
        console.rows().stream().map(SignalboxState.TrainRow::trainName).toList(),
        "T6 还要先在 ABC 停");
    assertEquals(SignalboxState.RowStatus.APPROACHING, console.rows().get(0).status());
    assertFalse(messages.contains(PLAYER + " signaller.cue.approaching"), "上岗时已在接近的不响");

    world.progress.put("T7", at("T7", 1, YardFixtures.P1, YardFixtures.IB));
    tick++;
    service.tick();
    tick++;
    service.tick();
    assertEquals(
        1L,
        messages.stream().filter(m -> m.equals(PLAYER + " signaller.cue.approaching")).count(),
        "新接近的 T7 响一次");
  }

  /** 交路的途经点一个都不在区内（直通、不在区内的站停），但路上要穿过控制区：沿图展开也认得出是在接近。 */
  @Test
  void aTrainPassingThroughWithoutStoppingIsApproaching() {
    useCorridorWithZoneDef(List.of(YardFixtures.D1, YardFixtures.P1, YardFixtures.IC));
    world.progress.put("T5", at("T5", 1, YardFixtures.P1, YardFixtures.IC));
    onDuty(PLAYER, "DEF");

    SignalboxState console = service.state("SURC", "DEF").orElseThrow();
    assertEquals(
        List.of("T5"), console.rows().stream().map(SignalboxState.TrainRow::trainName).toList());
    assertEquals(SignalboxState.RowStatus.APPROACHING, console.rows().get(0).status());
  }

  /** 车还停在区外上一站时取消提前排的进路不锁；开出来以后再取消才锁。 */
  @Test
  void cancellingForATrainStillAtThePreviousStationDoesNotLock() {
    useCorridorWithZoneDef(
        List.of(
            YardFixtures.D1, YardFixtures.P1, YardFixtures.IB, YardFixtures.Q1, YardFixtures.IC));
    world.progress.put("T5", at("T5", 1, YardFixtures.P1, YardFixtures.IB));
    onDuty(PLAYER, "DEF");
    SignalBox box = service.boxFor("SURC", "DEF").orElseThrow();
    world.dwelling = true;
    service.press(PLAYER, "Sig", true, CONSOLE, "SURC", "DEF", new ConsoleTarget.Train("T5"));
    now = now.plusSeconds(1);
    service.press(
        PLAYER,
        "Sig",
        true,
        CONSOLE,
        "SURC",
        "DEF",
        new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    now = now.plusSeconds(1);
    service.press(
        PLAYER,
        "Sig",
        true,
        CONSOLE,
        "SURC",
        "DEF",
        new ConsoleTarget.Press(SignalboxState.Button.CANCEL_ROUTE));
    assertTrue(box.approachLocks.isEmpty(), "还在上一站停着");

    world.dwelling = false;
    tick++;
    now = now.plusSeconds(1);
    service.press(
        PLAYER,
        "Sig",
        true,
        CONSOLE,
        "SURC",
        "DEF",
        new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    now = now.plusSeconds(1);
    service.press(
        PLAYER,
        "Sig",
        true,
        CONSOLE,
        "SURC",
        "DEF",
        new ConsoleTarget.Press(SignalboxState.Button.CANCEL_ROUTE));
    assertEquals(1, box.approachLocks.size(), "开出来了");
  }

  private void useCorridorWithZoneDef(List<NodeId> waypoints) {
    world.graph = YardFixtures.corridor();
    world.route = new RouteDefinition(RouteId.of("SURC:L1:R1"), waypoints, Optional.empty());
    world.zones.put(
        "DEF",
        new SignallerWorld.Zone("SURC", "DEF", "DEF 站", List.of(YardArea.Member.station("DEF"))));
    world.progress.clear();
  }

  private static RouteProgressRegistry.RouteProgressEntry at(
      String train, int index, NodeId last, NodeId next) {
    return new RouteProgressRegistry.RouteProgressEntry(
        train,
        ROUTE,
        RouteId.of("SURC:L1:R1"),
        index,
        Optional.of(next),
        Optional.of(last),
        SignalAspect.PROCEED,
        Instant.EPOCH);
  }

  /** 呼叫带原因：没写原因、车又在入口等候时按“信号前等候”算；同一列车后来写明的原因替换先前的。回复要配得上原因，不符的计数、告诉信号员该怎么回，回复照样发给驾驶员。 */
  @Test
  void repliesMustFitTheReasonOfTheCall() {
    List<String> heard = new ArrayList<>();
    service.setExaminer(recording(heard));
    goOnDuty();
    service.admit("T1", entering(), now);

    assertEquals(CallResult.Status.CALLED, driverCall("T1"));
    assertEquals(CallReason.SIGNAL, state().call().orElseThrow().reason(), "在入口等着：信号前等候");
    assertEquals(CallResult.Status.PENDING, driverCall("T1"), "同一名驾驶员再叫：仍在等回复");
    assertEquals(
        CallResult.Status.CALLED,
        service.call(DRIVER, CallerRole.DRIVER, "T1", CallReason.DOORS).status());
    assertEquals(CallReason.DOORS, state().call().orElseThrow().reason(), "写明的原因替换先前的");
    assertEquals(1, service.boxFor("SURC", "ABC").orElseThrow().calls.size(), "不重复排队");

    press(new ConsoleTarget.CallBadge());
    press(new ConsoleTarget.Answer(SignalboxState.Reply.WAIT));

    assertTrue(messages.contains(DRIVER + " signaller.reply.wait"), "回错也照样回给驾驶员");
    assertTrue(messages.contains(PLAYER + " signaller.call.wrong-doors"));
    assertEquals(Optional.of(SignalboxState.NoticeKind.REPLY_WRONG), noticeKind());
    assertTrue(heard.contains("CALL_WRONG"));
    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);
    assertEquals(1, shifts.get(0).callsAnswered());
    assertEquals(1, shifts.get(0).callsWrong());
  }

  /** 模拟呼叫：以列车表里的车的名义（在入口等着的按“信号前等候”），回复与超时都不发给任何驾驶员，考官听到的是模拟呼叫的结果。已有待回复的呼叫时不来。 */
  @Test
  void drillCallsUseATrainOnTheListAndReachNoDriver() {
    List<String> heard = new ArrayList<>();
    service.setExaminer(recording(heard));
    assertTrue(service.drillCall(PLAYER, bound -> 0).isEmpty(), "没在值守");
    goOnDuty();
    service.admit("T1", entering(), now);

    assertEquals(Optional.of("T1"), service.drillCall(PLAYER, bound -> 0));
    assertEquals(CallReason.SIGNAL, state().call().orElseThrow().reason());
    assertTrue(service.drillCall(PLAYER, bound -> 0).isEmpty(), "已有待回复的呼叫");
    press(new ConsoleTarget.CallBadge());
    press(new ConsoleTarget.Answer(SignalboxState.Reply.ROUTE_SETTING));
    assertTrue(heard.contains("DRILL_ANSWERED"));
    assertFalse(messages.stream().anyMatch(m -> m.contains("signaller.reply.")), "不发给任何驾驶员");

    service.drillCall(PLAYER, bound -> 0);
    now = now.plusSeconds(61);
    service.tick();
    assertTrue(heard.contains("DRILL_MISSED"));
    assertFalse(messages.stream().anyMatch(m -> m.contains("signaller.call.unanswered")));
  }

  /** 接近锁闭：车已停在入口时取消它的进路，这个入口锁 30 秒：其间排不了，到了代排时限也不代排；锁一过照常代排。 */
  @Test
  void cancellingARouteWithATrainOnApproachLocksTheEntry() {
    goOnDuty();
    service.admit("T1", entering(), now);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    press(new ConsoleTarget.Press(SignalboxState.Button.CANCEL_ROUTE));
    assertEquals(Optional.of(SignalboxState.NoticeKind.APPROACH_LOCKING), noticeKind());

    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    assertEquals(Optional.of(SignalboxState.NoticeKind.APPROACH_LOCKED), noticeKind());
    assertTrue(service.boxFor("SURC", "ABC").orElseThrow().permits.isEmpty());

    now = now.plusSeconds(25);
    assertTrue(service.admit("T1", entering(), now).isPresent(), "过了代排时限，但入口还锁着");
    now = now.plusSeconds(6);
    tick++;
    service.tick();
    assertTrue(service.admit("T1", entering(), now).isEmpty(), "锁一过就放行");
    enterZone("T1");
    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);
    assertEquals(0, shifts.get(0).autoRouted(), "代排时限在锁闭期间到的：不算代排");
    assertFalse(messages.contains(PLAYER + " signaller.alert.auto-routed"));
  }

  /** 入口锁着时设了自动通过：锁一过，在等的车走自动通过，不是等到代排。 */
  @Test
  void autoPassTakesOverOnceTheApproachLockEnds() {
    settings = new SignallerConfig(true, 120, 32, 30, 150, 60, 3, 30);
    goOnDuty();
    service.admit("T1", entering(), now);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    press(new ConsoleTarget.Press(SignalboxState.Button.CANCEL_ROUTE));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.AUTO_PASS));
    assertTrue(service.admit("T1", entering(), now).isPresent(), "锁着");

    now = now.plusSeconds(31);
    tick++;
    service.tick();

    SignalBox box = service.boxFor("SURC", "ABC").orElseThrow();
    assertEquals(SignalBox.Source.AUTO_PASS, box.permits.get("t1").source);
  }

  /** 改名、收车都不弄丢模拟呼叫：改名时原因与“模拟”标记跟着走；用的车不在了就告诉考官再来一次。真驾驶员的呼叫不并进模拟呼叫。 */
  @Test
  void drillCallsSurviveRenamesAndAreReplacedWhenTheirTrainGoes() {
    List<String> heard = new ArrayList<>();
    service.setExaminer(recording(heard));
    goOnDuty();
    service.admit("T1", entering(), now);
    service.drillCall(PLAYER, bound -> 0);

    assertEquals(
        CallResult.Status.CALLED,
        service.call(DRIVER, CallerRole.DRIVER, "T1", CallReason.DOORS).status());
    SignalBox box = service.boxFor("SURC", "ABC").orElseThrow();
    assertEquals(2, box.calls.size(), "真呼叫另排一条");
    assertTrue(box.calls.peekFirst().drill());
    assertEquals(CallReason.SIGNAL, box.calls.peekFirst().reason());

    service.renamed("T1", "T9");
    assertTrue(box.calls.peekFirst().drill(), "改名后还是模拟呼叫");
    assertEquals(CallReason.SIGNAL, box.calls.peekFirst().reason());
    assertEquals(CallReason.DOORS, box.calls.peekLast().reason());

    service.forget("T9");
    assertTrue(heard.contains("DRILL_CANCELLED"));
    assertTrue(box.calls.isEmpty());
  }

  /** 扣车扣的秒数不算在区内丢的时间；进出区之间没有新的到发记录（读数没更新）时不比。 */
  @Test
  void holdsAreNotLostTimeAndStaleReadingsAreNotCompared() {
    goOnDuty();
    world.delays.put("T1", 0L);
    world.stamps.put("T1", "t1#1");
    service.admit("T1", entering(), now);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    enterZone("T1");
    world.claims.add(claim("T1", OccupancyResource.forNode(YardFixtures.P1)));
    world.progress.put("T1", progress("T1", YardFixtures.P1));
    world.dwelling = true;
    tick++;
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    now = now.plusSeconds(100);
    press(new ConsoleTarget.Press(SignalboxState.Button.HOLD));
    world.delays.put("T1", 110L);
    world.stamps.put("T1", "t1#3");
    world.claims.clear();
    tick++;
    service.tick();

    world.progress.put("T2", progress("T2", YardFixtures.IA));
    world.delays.put("T2", 0L);
    world.stamps.put("T2", "t2#1");
    service.admit("T2", entering(), now);
    press(new ConsoleTarget.Train("T2"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    enterZone("T2");
    world.delays.put("T2", 300L);
    world.claims.clear();
    tick++;
    service.tick();
    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);

    SignallerShift shift = shifts.get(0);
    assertEquals(1, shift.keptTime(), "T1 多出 110 秒，其中 100 秒是扣车");
    assertEquals(0, shift.lostTime(), "T2 读数没更新，不比");
  }

  /**
   * 进区与出区时的晚点相比：亲手排进来、没多出 30 秒以上的计正点放行；多出 60 秒以上的计在区内丢了时间。两个入口同时有车在等、区内要走的路有交汇时，信号员先放的那列与另一列成一对，
   * 两车都出区后按合计多出的晚点计交汇排序。
   */
  @Test
  void punctualityAndJunctionOrderAreScoredWhenTrainsLeaveTheZone() {
    goOnDuty();
    world.progress.put(
        "T2",
        new RouteProgressRegistry.RouteProgressEntry(
            "T2",
            ROUTE_WEST,
            RouteId.of("SURC:L1:R2"),
            0,
            Optional.of(YardFixtures.P1),
            Optional.of(YardFixtures.IB),
            SignalAspect.PROCEED,
            Instant.EPOCH));
    world.delays.put("T1", 0L);
    world.delays.put("T2", 0L);
    world.stamps.put("T1", "t1#1");
    world.stamps.put("T2", "t2#1");
    service.admit("T1", entering(), now);
    service.admit("T2", enteringFromEast(), now);

    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    assertEquals(1, service.boxFor("SURC", "ABC").orElseThrow().pairs.size(), "两个入口抢同一片道岔");
    press(new ConsoleTarget.Train("T2"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    enterZone("T1", "T2");

    world.delays.put("T1", 10L);
    world.delays.put("T2", 200L);
    world.stamps.put("T1", "t1#3");
    world.stamps.put("T2", "t2#3");
    world.claims.clear();
    tick++;
    service.tick();
    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);

    SignallerShift shift = shifts.get(0);
    assertEquals(1, shift.keptTime(), "T1 多出 10 秒");
    assertEquals(1, shift.lostTime(), "T2 多出 200 秒");
    assertEquals(0, shift.conflictsGood());
    assertEquals(1, shift.conflictsBad(), "两车合计多出 210 秒");
  }

  @Test
  void aTrainOrderedToSkipIsLeftOutOfPunctualityAndJunctionScores() {
    goOnDuty();
    world.progress.put(
        "T2",
        new RouteProgressRegistry.RouteProgressEntry(
            "T2",
            ROUTE_WEST,
            RouteId.of("SURC:L1:R2"),
            0,
            Optional.of(YardFixtures.P1),
            Optional.of(YardFixtures.IB),
            SignalAspect.PROCEED,
            Instant.EPOCH));
    world.delays.put("T1", 0L);
    world.delays.put("T2", 0L);
    world.stamps.put("T1", "t1#1");
    world.stamps.put("T2", "t2#1");
    service.admit("T1", entering(), now);
    service.admit("T2", enteringFromEast(), now);
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    press(new ConsoleTarget.Train("T2"));
    press(new ConsoleTarget.Press(SignalboxState.Button.SET_ROUTE));
    enterZone("T1", "T2");

    // 区内令 T1 越站
    world.delays.put("T1", 260L);
    world.trains.skips.put(
        "T1", new SignallerTrainDesk.Skip(YardFixtures.P2, false, Optional.empty()));
    press(new ConsoleTarget.Train("T1"));
    press(new ConsoleTarget.Press(SignalboxState.Button.TRAIN_OPS));
    press(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP));
    assertEquals(List.of("T1"), world.trains.ordered);

    world.delays.put("T1", 10L);
    world.delays.put("T2", 200L);
    world.stamps.put("T1", "t1#3");
    world.stamps.put("T2", "t2#3");
    world.claims.clear();
    tick++;
    service.tick();
    service.endDuty(PLAYER, SignallerShift.EndReason.OFF_DUTY);

    SignallerShift shift = shifts.get(0);
    assertEquals(0, shift.keptTime(), "越过站的车省下的时间不算正点放行");
    assertEquals(1, shift.lostTime(), "T2 照算");
    assertEquals(0, shift.conflictsGood());
    assertEquals(0, shift.conflictsBad(), "有越站车的交汇不评");
  }

  /** 从 IB 进站到 1 道的申请。 */
  private static List<OccupancyResource> enteringFromEast() {
    return List.of(
        OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.IB, YardFixtures.SW2)),
        OccupancyResource.forNode(YardFixtures.SW2),
        OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.SW2, YardFixtures.P1)),
        OccupancyResource.forNode(YardFixtures.P1));
  }

  private static SignallerExaminer recording(List<String> heard) {
    return new SignallerExaminer() {
      @Override
      public boolean examining(UUID player) {
        return false;
      }

      @Override
      public void onDutyStarted(UUID player, String zoneName, boolean manual, boolean stations) {}

      @Override
      public void onEvent(UUID player, Event event) {
        heard.add(event.name());
      }

      @Override
      public void onDutyEnded(UUID player, SignallerShift.EndReason reason) {}
    };
  }

  // ─── 夹具 ──────────────────────────────────────────────────────────────────

  private SignalboxState state() {
    return service.state("SURC", "ABC").orElseThrow();
  }

  private Optional<SignalboxState.NoticeKind> noticeKind() {
    return state().notice().map(SignalboxState.Notice::kind);
  }

  /**
   * 嵌套的控制区：大区（ABC、DEF 两站连同中间的线）里套着小区（只有 DEF 站）。小区有人值守时由小区管、大区让出这一片： 从大区往 DEF 开、从区外直接进 DEF 都只等小区；从
   * DEF 出来进大区的其余部分，在两区分界等大区。小区下岗后大区照管全部。
   */
  @Test
  void aMannedNestedZoneTakesOverItsAreaFromTheZoneAroundIt() {
    world.graph = YardFixtures.corridor();
    world.zones.put(
        "BIG",
        new SignallerWorld.Zone(
            "SURC",
            "BIG",
            "大区",
            List.of(YardArea.Member.station("ABC"), YardArea.Member.station("DEF"))));
    world.zones.put(
        "DEF",
        new SignallerWorld.Zone("SURC", "DEF", "DEF 站", List.of(YardArea.Member.station("DEF"))));
    onDuty(PLAYER, "BIG");
    onDuty(SECOND, "DEF");
    OccupancyResource def = OccupancyResource.forNode(YardFixtures.Q1);
    List<OccupancyResource> abcToDef =
        List.of(
            OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.P1, YardFixtures.IB)),
            OccupancyResource.forNode(YardFixtures.IB),
            OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.IB, YardFixtures.Q1)),
            def);
    List<OccupancyResource> defToAbc =
        List.of(
            OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.Q1, YardFixtures.IB)),
            OccupancyResource.forNode(YardFixtures.IB),
            OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.IB, YardFixtures.P1)),
            OccupancyResource.forNode(YardFixtures.P1));
    List<OccupancyResource> fromOutsideIntoDef =
        List.of(
            OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.Q1, YardFixtures.IC)), def);
    world.claims.add(claim("T1", OccupancyResource.forNode(YardFixtures.P1)));
    world.claims.add(claim("T2", def));
    tick++;

    assertEquals(
        Optional.of("SURC:DEF"),
        service.admit("T1", abcToDef, now).map(SignallerGate.Hold::box),
        "在大区里往 DEF 开：只等小区");
    assertEquals(
        Optional.of("SURC:BIG"),
        service.admit("T2", defToAbc, now).map(SignallerGate.Hold::box),
        "从 DEF 出来进大区：在分界等大区");
    assertEquals(
        Optional.of("SURC:DEF"),
        service.admit("T3", fromOutsideIntoDef, now).map(SignallerGate.Hold::box),
        "从区外直接进 DEF：只等小区");
    SignalboxState bigConsole = service.state("SURC", "BIG").orElseThrow();
    assertEquals(
        List.of("DEF 站"),
        bigConsole.delegated().stream().map(SignalboxState.Delegated::zone).toList(),
        "大区控制台围出让给小区的那一片");
    assertTrue(bigConsole.delegated().get(0).nodes().contains(YardFixtures.Q1));
    assertTrue(service.state("SURC", "DEF").orElseThrow().delegated().isEmpty());

    assertTrue(service.endDuty(SECOND, SignallerShift.EndReason.OFF_DUTY));
    tick++;
    assertTrue(service.admit("T2", defToAbc, now).isEmpty(), "小区没人：车在 DEF 已在大区里");
    assertEquals(
        Optional.of("SURC:BIG"),
        service.admit("T3", fromOutsideIntoDef, now).map(SignallerGate.Hold::box),
        "小区没人：从区外进 DEF 等大区");
  }

  /** 驾驶员 {@link #DRIVER} 不写原因呼叫。 */
  private CallResult.Status driverCall(String train) {
    return service.call(DRIVER, CallerRole.DRIVER, train, CallReason.GENERAL).status();
  }

  private void onDuty(UUID player, String zone) {
    service.press(player, "Sig", true, CONSOLE, "SURC", zone, new ConsoleTarget.Deselect());
    assertEquals(SignallerService.DutyStart.STARTED, service.startDuty(player, "Sig", true));
  }

  /** 列车停在 1 道站台上。 */
  private void dwellAtPlatform(String train) {
    world.claims.add(claim(train, OccupancyResource.forNode(YardFixtures.P1)));
    world.progress.put(train, progress(train, YardFixtures.P1));
    world.dwelling = true;
    tick++;
  }

  private void goOnDuty() {
    pressNow(new ConsoleTarget.Train("T1"));
    assertEquals(SignallerService.DutyStart.STARTED, service.startDuty(PLAYER, "Sig", true));
  }

  /** 这几列车拿到区内的行车占用（驶入），巡检一次让许可锁闭。 */
  private void enterZone(String... trains) {
    for (String train : trains) {
      world.claims.add(
          claim(
              train,
              OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.SW1, YardFixtures.T1))));
    }
    tick++;
    service.tick();
  }

  /** 点一下：与上一下隔开一秒（紧跟上一下的点击算按住右键的连发）。 */
  private void press(ConsoleTarget target) {
    now = now.plusSeconds(1);
    pressNow(target);
  }

  /** 此刻就点，不往前拨时钟：测按住右键的连发。 */
  private void pressNow(ConsoleTarget target) {
    service.press(PLAYER, "Sig", true, CONSOLE, "SURC", "ABC", target);
  }

  private void pressWithoutCancelPermission(ConsoleTarget target) {
    now = now.plusSeconds(1);
    service.press(PLAYER, "Sig", true, false, CONSOLE, "SURC", "ABC", target);
  }

  /** 从 IA 进站到 1 道的申请。 */
  private static List<OccupancyResource> entering() {
    return List.of(
        OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.IA, YardFixtures.SW1)),
        OccupancyResource.forNode(YardFixtures.SW1),
        OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.SW1, YardFixtures.T1)),
        OccupancyResource.forNode(YardFixtures.T1));
  }

  private static RouteProgressRegistry.RouteProgressEntry progress(String train, NodeId last) {
    int index = last.equals(YardFixtures.IA) ? 0 : 1;
    return new RouteProgressRegistry.RouteProgressEntry(
        train,
        ROUTE,
        RouteId.of("SURC:L1:R1"),
        index,
        Optional.of(index == 0 ? YardFixtures.P1 : YardFixtures.IB),
        Optional.of(last),
        SignalAspect.PROCEED,
        Instant.EPOCH);
  }

  private OccupancyClaim claim(String train, OccupancyResource resource) {
    return claim(train, resource, ClaimRole.MOVEMENT_REQUIRED);
  }

  private OccupancyClaim claim(String train, OccupancyResource resource, ClaimRole role) {
    return new OccupancyClaim(
        resource, train, Optional.empty(), now, Duration.ZERO, Optional.empty(), role);
  }

  private static final class NoMessages implements SignallerMessenger {
    @Override
    public void send(UUID player, String key, Map<String, String> values) {}

    @Override
    public void alert(UUID player, String key, Map<String, String> values) {}

    @Override
    public String text(String key) {
      return key;
    }
  }

  /** 两列车在同一个动态停站上暂定的股道不同（候选为 ABC 1、2 道）。 */
  private record TentativePlatforms(Map<String, NodeId> current) implements SignallerPlatformDesk {
    @Override
    public Optional<Options> options(String trainName) {
      NodeId tentative = current.get(SignallerService.key(trainName));
      return tentative == null
          ? Optional.empty()
          : Optional.of(
              new Options(
                  1,
                  List.of(YardFixtures.P1, YardFixtures.P2),
                  Optional.of(tentative),
                  Optional.empty()));
    }

    @Override
    public Choice choose(String trainName, NodeId platform) {
      return Choice.UNAVAILABLE;
    }

    @Override
    public void forget(String trainName) {}
  }

  private static final class FakeWorld implements SignallerWorld {
    RailGraph graph = YardFixtures.graph();
    final Map<String, Zone> zones = new HashMap<>();
    final FakeTrainDesk trains = new FakeTrainDesk();
    final List<OccupancyClaim> claims = new ArrayList<>();
    final Map<String, RouteProgressRegistry.RouteProgressEntry> progress = new HashMap<>();
    final List<String> reevaluated = new ArrayList<>();

    /** 各列车此刻的晚点（秒）；没有的按没有时刻表。 */
    final Map<String, Long> delays = new HashMap<>();

    /** 各列车晚点读数的版本（到发记录变了才换）。 */
    final Map<String, String> stamps = new HashMap<>();

    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURC:L1:R1"),
            List.of(YardFixtures.IA, YardFixtures.P1, YardFixtures.IB),
            Optional.empty());
    boolean dwelling;
    boolean zoneExists = true;
    boolean graphReady = true;
    SignallerPlatformDesk desk = SignallerPlatformDesk.NONE;
    PlayerSpot spot = new PlayerSpot("Sig", YardFixtures.WORLD, 0, 64, 12, true);

    @Override
    public Optional<Zone> zone(String operator, String zone) {
      Zone extra = zones.get(zone.toUpperCase(java.util.Locale.ROOT));
      if (extra != null && "SURC".equalsIgnoreCase(operator)) {
        return Optional.of(extra);
      }
      return zoneExists && "SURC".equalsIgnoreCase(operator) && "ABC".equalsIgnoreCase(zone)
          ? Optional.of(new Zone("SURC", "ABC", "示例站", List.of(YardArea.Member.station("ABC"))))
          : Optional.empty();
    }

    @Override
    public Map<UUID, RailGraph> graphs() {
      return graphReady ? Map.of(YardFixtures.WORLD, graph) : Map.of();
    }

    @Override
    public List<OccupancyClaim> claims() {
      return List.copyOf(claims);
    }

    @Override
    public Map<String, RouteProgressRegistry.RouteProgressEntry> progress() {
      return Map.copyOf(progress);
    }

    @Override
    public Optional<RouteDefinition> route(UUID routeUuid) {
      if (ROUTE_WEST.equals(routeUuid)) {
        return Optional.of(
            new RouteDefinition(
                RouteId.of("SURC:L1:R2"),
                List.of(YardFixtures.IB, YardFixtures.P1, YardFixtures.IA),
                Optional.empty()));
      }
      return ROUTE.equals(routeUuid) ? Optional.of(route) : Optional.empty();
    }

    @Override
    public boolean dwelling(String trainName) {
      return dwelling;
    }

    @Override
    public TrainInfo info(String trainName) {
      Long delay = delays.get(trainName);
      return new TrainInfo(
          trainName,
          Optional.empty(),
          delay == null ? OptionalLong.empty() : OptionalLong.of(delay),
          Optional.ofNullable(stamps.get(trainName)));
    }

    @Override
    public void reevaluate(String trainName) {
      reevaluated.add(trainName);
    }

    @Override
    public SignallerTrainDesk trains() {
      return trains;
    }

    @Override
    public SignallerPlatformDesk platforms() {
      return desk;
    }

    @Override
    public Optional<PlayerSpot> player(UUID player) {
      if (DRIVER.equals(player)) {
        return Optional.of(new PlayerSpot("Drv", YardFixtures.WORLD, 500, 64, 0, true));
      }
      if (GUARD.equals(player)) {
        return Optional.of(new PlayerSpot("Grd", YardFixtures.WORLD, 520, 64, 0, true));
      }
      return PLAYER.equals(player) || SECOND.equals(player) ? Optional.of(spot) : Optional.empty();
    }
  }

  /** 记下信号员对列车做了什么。 */
  private static final class FakeTrainDesk implements SignallerTrainDesk {
    final List<String> cancelled = new ArrayList<>();
    final List<String> announced = new ArrayList<>();
    final List<String> ordered = new ArrayList<>();
    final List<String> revoked = new ArrayList<>();

    /** 各列车的下一个停车站；没写的车没有。 */
    final Map<String, Skip> skips = new HashMap<>();

    @Override
    public Optional<Skip> skip(String trainName) {
      return Optional.ofNullable(skips.get(trainName));
    }

    @Override
    public Optional<SkipRefusal> orderSkip(String trainName) {
      ordered.add(trainName);
      return Optional.empty();
    }

    /** 撤销时回的原因；空为撤了。 */
    Optional<SkipRefusal> revokeRefusal = Optional.empty();

    @Override
    public Optional<SkipRefusal> revokeSkip(String trainName) {
      revoked.add(trainName);
      return revokeRefusal;
    }

    /** 各列车的现场；没写的车：车上没有玩家、停在站台、不是联挂车。 */
    final Map<String, Scene> scenes = new HashMap<>();

    @Override
    public Scene scene(String trainName) {
      return scenes.getOrDefault(trainName, new Scene(false, true, false));
    }

    @Override
    public void announceCancel(String trainName) {
      announced.add(trainName);
    }

    @Override
    public void cancel(String trainName) {
      cancelled.add(trainName);
    }
  }
}
