package org.fetarute.fetaruteTCAddon.signaller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures;
import org.fetarute.fetaruteTCAddon.drive.crew.CrewDuty;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;
import org.fetarute.fetaruteTCAddon.signaller.console.ConsoleTarget;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 信号员核心：闸只把放行变成暂不放行、已在区内不拦、代排、自动通过、许可锁闭与作废、改名、下岗回自动、扣车与呼叫。 */
class SignallerServiceTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID DRIVER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
  private static final UUID ROUTE = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
  private static final SignalBox.ConsoleSpot CONSOLE =
      new SignalBox.ConsoleSpot(YardFixtures.WORLD, 0, 64, 10);

  private FakeWorld world;
  private Instant now;
  private long tick;
  private List<String> messages;
  private List<SignallerShift> shifts;
  private SignallerService service;

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    now = Instant.parse("2026-10-20T08:00:00Z");
    tick = 0;
    messages = new ArrayList<>();
    shifts = new ArrayList<>();
    service =
        new SignallerService(
            world,
            SignallerConfig::defaults,
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

    press(new ConsoleTarget.Press(SignalboxState.Button.RELEASE));
    assertTrue(service.holdTarget("T1").isEmpty());
  }

  @Test
  void driverCallReachesTheSignallerAndTheReplyGoesBack() {
    assertFalse(service.call(DRIVER, "T1"));
    goOnDuty();

    assertTrue(service.call(DRIVER, "T1"));
    SignalboxState state = service.state("SURC", "ABC").orElseThrow();
    assertEquals(Optional.of(new SignalboxState.Call("T1", "Drv")), state.call());

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

  // ─── 夹具 ──────────────────────────────────────────────────────────────────

  private void goOnDuty() {
    press(new ConsoleTarget.Train("T1"));
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

  private void press(ConsoleTarget target) {
    service.press(PLAYER, "Sig", true, CONSOLE, "SURC", "ABC", target);
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
  }

  private static final class FakeWorld implements SignallerWorld {
    final RailGraph graph = YardFixtures.graph();
    final List<OccupancyClaim> claims = new ArrayList<>();
    final Map<String, RouteProgressRegistry.RouteProgressEntry> progress = new HashMap<>();
    final List<String> reevaluated = new ArrayList<>();
    final RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURC:L1:R1"),
            List.of(YardFixtures.IA, YardFixtures.P1, YardFixtures.IB),
            Optional.empty());
    boolean dwelling;
    boolean zoneExists = true;
    PlayerSpot spot = new PlayerSpot("Sig", YardFixtures.WORLD, 0, 64, 12, true);

    @Override
    public Optional<Zone> zone(String operator, String zone) {
      return zoneExists && "SURC".equalsIgnoreCase(operator) && "ABC".equalsIgnoreCase(zone)
          ? Optional.of(new Zone("SURC", "ABC", "示例站", List.of(YardArea.Member.station("ABC"))))
          : Optional.empty();
    }

    @Override
    public Map<UUID, RailGraph> graphs() {
      return Map.of(YardFixtures.WORLD, graph);
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
      return ROUTE.equals(routeUuid) ? Optional.of(route) : Optional.empty();
    }

    @Override
    public boolean dwelling(String trainName) {
      return dwelling;
    }

    @Override
    public TrainInfo info(String trainName) {
      return new TrainInfo(trainName, Optional.empty(), OptionalLong.empty());
    }

    @Override
    public void reevaluate(String trainName) {
      reevaluated.add(trainName);
    }

    @Override
    public SignallerPlatformDesk platforms() {
      return SignallerPlatformDesk.NONE;
    }

    @Override
    public Optional<PlayerSpot> player(UUID player) {
      if (DRIVER.equals(player)) {
        return Optional.of(new PlayerSpot("Drv", YardFixtures.WORLD, 500, 64, 0, true));
      }
      return PLAYER.equals(player) ? Optional.of(spot) : Optional.empty();
    }
  }
}
