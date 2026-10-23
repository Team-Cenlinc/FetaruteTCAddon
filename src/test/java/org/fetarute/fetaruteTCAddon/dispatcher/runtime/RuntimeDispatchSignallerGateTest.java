package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerGate;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * 信号员进路许可闸在调度内核里的插入点：没接闸时与现状逐项一致；闸放行照常；闸拦下就停在控制区外，停因写“等信号员”，不进控制区资源的排队，
 * 不把自己记成别人的阻挡；内核先拦下的不问闸；恢复层与折返接续同样过闸；改名、销毁随车迁移或清掉许可。
 *
 * <p>夹具：{@code 3:004 —40— 道岔 —40— 3:006 —20— 3:007 —30— S:WSD:3 —30— 站后 —100— S:SCC:3}，道岔有一条侧线。
 *
 * <p>独立成类：{@code RuntimeDispatchServiceTest} 已贴着 SpotBugs 单类 1000 方法的上限。
 */
class RuntimeDispatchSignallerGateTest {

  private static final String TRAIN = "SURC-MT-LH-2689";
  private static final String OTHER = "SURC-DS-LH-3597";
  private static final NodeId P4 = NodeId.of("SURC:SPB:WSD:3:004");
  private static final NodeId SWITCH = NodeId.of("SWITCHER:Towny:-400:74:1130");
  private static final NodeId BRANCH = NodeId.of("SURC:SPB:WSD:2:005");
  private static final NodeId P6 = NodeId.of("SURC:SPB:WSD:3:006");
  private static final NodeId P7 = NodeId.of("SURC:SPB:WSD:3:007");
  private static final NodeId WSD = NodeId.of("SURC:S:WSD:3");
  private static final NodeId BEYOND = NodeId.of("SURC:WSD:SCC:3:001");
  private static final NodeId SCC = NodeId.of("SURC:S:SCC:3");
  private static final RouteId ROUTE_ID = RouteId.of("MT-LH");
  private static final OccupancyResource SWITCH_CONFLICT =
      OccupancyResource.forConflict("switcher:" + SWITCH.value());
  private static final String BOX = "SURC:SPB";

  /** 记下每次问闸；按需放行或拦下。 */
  private static final class RecordingGate implements SignallerGate {
    final List<String> asked = new ArrayList<>();
    final List<List<OccupancyResource>> requested = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    boolean block;
    boolean waiting;
    boolean active = true;

    @Override
    public boolean active() {
      return active;
    }

    @Override
    public Optional<Hold> admit(
        String trainName, Collection<OccupancyResource> resources, Instant now) {
      asked.add(trainName);
      requested.add(List.copyOf(resources));
      if (!block) {
        return Optional.empty();
      }
      waiting = true;
      return Optional.of(new Hold(BOX, now.minusSeconds(3)));
    }

    @Override
    public boolean waiting(String trainName) {
      return waiting;
    }

    @Override
    public boolean renamed(String from, String to) {
      calls.add("renamed:" + from + "->" + to);
      return true;
    }

    @Override
    public void forget(String trainName) {
      calls.add("forget:" + trainName);
    }
  }

  private static final class Scenario {
    final UUID worldId = UUID.randomUUID();
    final SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    final TagStore tags;
    final RouteProgressRegistry registry = new RouteProgressRegistry();
    final List<String> debug = new ArrayList<>();
    final List<String> woken = new ArrayList<>();
    final RuntimeDispatchService service;

    Scenario(int routeIndex) {
      tags =
          new TagStore(
              TRAIN,
              "FTA_OPERATOR_CODE=op",
              "FTA_LINE_CODE=l1",
              "FTA_ROUTE_CODE=r1",
              "FTA_ROUTE_INDEX=" + routeIndex);
      RouteDefinition route =
          new RouteDefinition(
              ROUTE_ID, List.of(P4, SWITCH, P6, P7, WSD, BEYOND, SCC), Optional.empty());
      registry.initFromTags(TRAIN, tags.properties(), route);
      service = service(manager, worldId, route, registry, debug);
      service.setSignalReevaluationRequester(woken::add);
    }

    Scenario gate(SignallerGate gate) {
      service.signallerGates().setGate(gate);
      return this;
    }

    /** 车头在 3:004 后方、停着的一拍信号 tick。 */
    void tick() {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);
      train.estimatedTrainLengthBlocks = OptionalDouble.of(34.0);
      RailState railState = mock(RailState.class);
      when(railState.positionLocation()).thenReturn(new Location(null, 5.0, 64.0, 0.0));
      train.railState = Optional.of(railState);
      service.handleSignalTick(train, false);
    }

    /** 车头驶过 {@code 3:004} 推进点。 */
    void progressTrigger() {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.3);
      train.estimatedTrainLengthBlocks = OptionalDouble.of(34.0);
      com.bergerkiller.bukkit.tc.events.SignActionEvent event =
          mock(com.bergerkiller.bukkit.tc.events.SignActionEvent.class);
      org.bukkit.World world = mock(org.bukkit.World.class);
      when(world.getUID()).thenReturn(worldId);
      when(event.getWorld()).thenReturn(world);
      service.handleProgressTrigger(
          train,
          event,
          new SignNodeDefinition(P4, NodeType.WAYPOINT, Optional.empty(), Optional.empty()));
    }

    /** 停在站台 {@code S:WSD:3} 上的一次发车门控轮询。 */
    boolean checkDeparture() {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);
      train.estimatedTrainLengthBlocks = OptionalDouble.of(34.0);
      return service.checkDeparture(
          train, new SignNodeDefinition(WSD, NodeType.STATION, Optional.empty(), Optional.empty()));
    }

    boolean queued(String trainName) {
      return manager.snapshotQueues().stream()
          .anyMatch(
              snapshot ->
                  snapshot.entries().stream()
                      .anyMatch(entry -> trainName.equals(entry.trainName())));
    }

    List<String> claims() {
      return manager.snapshotClaims().stream()
          .map(claim -> claim.trainName() + "|" + claim.resource() + "@" + claim.role())
          .sorted()
          .toList();
    }

    List<String> queues() {
      return manager.snapshotQueues().stream()
          .flatMap(
              snapshot ->
                  snapshot.entries().stream()
                      .map(entry -> snapshot.resource() + "<" + entry.trainName()))
          .sorted()
          .toList();
    }

    String stopReason() {
      return service.getActiveStopState(TRAIN).map(RuntimeStopState::reasonCode).orElse("-");
    }

    SignalAspect lastSignal() {
      return registry.get(TRAIN).orElseThrow().lastSignal();
    }

    boolean holdsMovement(OccupancyResource resource) {
      return manager
          .getClaim(resource)
          .filter(claim -> TRAIN.equals(claim.trainName()))
          .filter(claim -> claim.role() == ClaimRole.MOVEMENT_REQUIRED)
          .isPresent();
    }

    /** 另一列车经侧线过道岔的申请：它拿到许可时不应被等信号员的车挡在排队里。 */
    boolean otherCanTakeTheSwitch() {
      return manager
          .canEnter(
              new OccupancyRequest(
                  OTHER,
                  Optional.empty(),
                  Instant.now(),
                  List.of(SWITCH_CONFLICT, OccupancyResource.forNode(BRANCH)),
                  Map.of(),
                  Map.of(),
                  0,
                  AuthorizationPurpose.RUNTIME_MOVE,
                  Map.of(),
                  Map.of(
                      SWITCH_CONFLICT,
                      ResourceIntent.MOVEMENT_REQUIRED,
                      OccupancyResource.forNode(BRANCH),
                      ResourceIntent.MOVEMENT_REQUIRED)))
          .allowed();
    }

    void occupy(String owner, List<OccupancyResource> resources) {
      Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
      resources.forEach(resource -> intents.put(resource, ResourceIntent.MOVEMENT_REQUIRED));
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      owner,
                      Optional.empty(),
                      Instant.now(),
                      resources,
                      Map.of(),
                      Map.of(),
                      0,
                      AuthorizationPurpose.RUNTIME_MOVE,
                      Map.of(),
                      intents))
              .allowed());
    }
  }

  @Test
  void withoutAGateAndWithAPassingGateTheSignalTickIsIdentical() {
    Scenario plain = new Scenario(0);
    RecordingGate passing = new RecordingGate();
    Scenario gated = new Scenario(0).gate(passing);

    plain.tick();
    gated.tick();

    assertEquals(SignalAspect.PROCEED, plain.lastSignal(), "对照：放行的一拍，否则本用例是空的");
    assertEquals(plain.lastSignal(), gated.lastSignal());
    assertEquals(plain.claims(), gated.claims());
    assertEquals(plain.queues(), gated.queues());
    assertEquals(plain.stopReason(), gated.stopReason());
    assertEquals(List.of(TRAIN), passing.asked, "内核放行时问了一次闸");
    assertTrue(passing.requested.get(0).contains(SWITCH_CONFLICT), "交给闸的是这次要申请的进路资源");
    assertTrue(plain.woken.isEmpty() && gated.woken.isEmpty());
  }

  @Test
  void withoutAGateAndWithAPassingGateTheDepartureGateIsIdentical() {
    Scenario plain = new Scenario(4);
    RecordingGate passing = new RecordingGate();
    Scenario gated = new Scenario(4).gate(passing);

    boolean plainDeparts = plain.checkDeparture();
    boolean gatedDeparts = gated.checkDeparture();

    assertTrue(plainDeparts, "对照：站后空闲时放行");
    assertEquals(plainDeparts, gatedDeparts);
    assertEquals(plain.claims(), gated.claims());
    assertEquals(plain.queues(), gated.queues());
    assertEquals(List.of(TRAIN), passing.asked);
  }

  @Test
  void blockedEntryStopsOutsideTheBoxWithoutQueuingOrBlockingOthers() {
    RecordingGate blocking = new RecordingGate();
    blocking.block = true;
    Scenario scenario = new Scenario(0).gate(blocking);

    scenario.tick();

    assertEquals(SignalAspect.STOP, scenario.lastSignal());
    RuntimeStopState stop = scenario.service.getActiveStopState(TRAIN).orElseThrow();
    assertEquals(SignallerGateCoordinator.STOP_REASON, stop.reasonCode());
    // 停车状态的明细把空白规整成下划线（RuntimeStopState），明细本体是 Hold#detail 的 box=… since=<开始等的时刻>
    assertTrue(stop.detail().startsWith("box=" + BOX + "_since="), stop.detail());
    assertTrue(stop.blockers().isEmpty(), "没有阻挡者：不进等待图与互卡检测");
    assertFalse(stop.invalidatesAuthority(), "普通占用等待：不作废授权");
    assertTrue(
        scenario.service.recentBlockerTrains(TRAIN, Duration.ofSeconds(30)).isEmpty(),
        "不把别的车记成阻挡者");
    assertFalse(scenario.holdsMovement(SWITCH_CONFLICT), "没拿到控制区的进路");
    assertFalse(scenario.queued(TRAIN), "不进控制区资源的排队：" + scenario.queues());
    assertTrue(scenario.otherCanTakeTheSwitch(), "拿到许可的车不被等信号员的车挡在排队里");

    scenario.tick();
    assertFalse(scenario.queued(TRAIN), "再等一拍也不排队");

    blocking.block = false;
    blocking.waiting = false;
    scenario.tick();
    assertEquals(SignalAspect.STOP, scenario.lastSignal(), "等信号员期间不计资历：先排上队的车在前");
    assertEquals(
        List.of(OTHER),
        scenario.service.getActiveStopState(TRAIN).orElseThrow().blockers().stream()
            .map(RuntimeStopState.Blocker::owner)
            .toList());

    scenario.manager.removeQueueEntries(OTHER, List.of(SWITCH_CONFLICT));
    scenario.tick();
    assertEquals(
        SignalAspect.PROCEED,
        scenario.lastSignal(),
        () -> "闸放行后照常开放; stop=" + scenario.service.getActiveStopState(TRAIN));
  }

  /** 推进点被拦：按普通占用等待停车（不作废授权、不走硬停车），同样不排队。闸放行时推进点照常授权。 */
  @Test
  void progressTriggerStopsWithoutInvalidatingAuthority() {
    RecordingGate passing = new RecordingGate();
    Scenario control = new Scenario(0).gate(passing);
    control.progressTrigger();
    assertEquals(List.of(TRAIN), passing.asked);
    assertTrue(control.holdsMovement(SWITCH_CONFLICT), "对照：闸放行时推进点照常拿到进路");

    RecordingGate blocking = new RecordingGate();
    blocking.block = true;
    Scenario scenario = new Scenario(0).gate(blocking);

    scenario.progressTrigger();

    RuntimeStopState stop = scenario.service.getActiveStopState(TRAIN).orElseThrow();
    assertEquals(SignallerGateCoordinator.STOP_REASON, stop.reasonCode());
    assertFalse(stop.invalidatesAuthority(), "不走会作废令牌的硬停车");
    assertTrue(stop.blockers().isEmpty());
    assertFalse(scenario.holdsMovement(SWITCH_CONFLICT));
    assertFalse(scenario.queued(TRAIN), scenario.queues().toString());
  }

  /** 闸空闲（此刻没有人工控制区）：哪怕它会拦、会说在等，也与没接闸逐项一致——信号 tick、出站门控与恢复层都不问它。 */
  @Test
  void anIdleGateBehavesExactlyLikeNoGate() {
    RecordingGate idle = new RecordingGate();
    idle.active = false;
    idle.block = true;
    idle.waiting = true;
    Scenario plain = new Scenario(0);
    Scenario gated = new Scenario(0).gate(idle);

    plain.tick();
    gated.tick();

    assertEquals(SignalAspect.PROCEED, plain.lastSignal());
    assertEquals(plain.lastSignal(), gated.lastSignal());
    assertEquals(plain.claims(), gated.claims());
    assertEquals(plain.queues(), gated.queues());
    assertEquals(plain.stopReason(), gated.stopReason());

    Scenario plainStation = new Scenario(4);
    Scenario gatedStation = new Scenario(4).gate(idle);
    assertEquals(plainStation.checkDeparture(), gatedStation.checkDeparture());
    assertEquals(plainStation.claims(), gatedStation.claims());
    assertTrue(idle.asked.isEmpty(), "闸空闲：不整理申请、不问闸");
    assertFalse(gated.service.signallerGates().waiting(TRAIN), "闸空闲：不认等待");
  }

  /** 区外站台的车出站被闸拦下之后，周期 tick 按发车门锁停车保持：它在等信号员，停车保持不再替它刷新区内冲突的排队位次。对照：没在等的车照常刷新（道岔排上了）。 */
  @Test
  void aTrainWaitingAtTheDepartureGateDoesNotRefreshItsQueuePosition() {
    // 道岔被别的车占着：本车占不到它，停车保持的前瞻只能替本车在它上面排队。
    Scenario control = new Scenario(0);
    control.occupy(OTHER, List.of(SWITCH_CONFLICT));
    control.service.acquireDepartureGate(TRAIN, "stop-1", "autostation_dwell");
    control.tick();
    assertEquals("DEPARTURE_GATE_HOLD", control.stopReason());
    assertTrue(control.queued(TRAIN), "对照：发车门锁停车保持照常刷新前方排队，否则本用例是空的");

    RecordingGate gate = new RecordingGate();
    gate.waiting = true;
    Scenario waiting = new Scenario(0).gate(gate);
    waiting.occupy(OTHER, List.of(SWITCH_CONFLICT));
    waiting.service.acquireDepartureGate(TRAIN, "stop-1", "autostation_dwell");
    waiting.tick();
    assertEquals("DEPARTURE_GATE_HOLD", waiting.stopReason());
    assertFalse(waiting.queued(TRAIN), "等信号员：停车保持不刷新区内排队 " + waiting.queues());
  }

  /** 转人工前已经排上道岔队列的车，被闸拦下当拍撤掉区内的排队项；没接资源归属时不撤（只等排队过期）。 */
  @Test
  void aStaleQueueEntryInsideTheBoxIsWithdrawnWhenTheTrainIsBlocked() {
    Scenario scenario = new Scenario(0);
    scenario.occupy(OTHER, List.of(SWITCH_CONFLICT));
    scenario.tick();
    assertTrue(scenario.queued(TRAIN), "夹具：转人工前已排上道岔队列");

    RecordingGate blocking = new RecordingGate();
    blocking.block = true;
    scenario.gate(blocking);
    scenario.tick();
    assertTrue(scenario.queued(TRAIN), "没接资源归属：不撤，只等排队过期");

    scenario
        .service
        .signallerGates()
        .setZoneMembership((box, resource) -> BOX.equals(box) && SWITCH_CONFLICT.equals(resource));
    scenario.tick();
    assertFalse(scenario.queued(TRAIN), "被拦当拍撤掉区内排队项：" + scenario.queues());
    assertEquals(SignallerGateCoordinator.STOP_REASON, scenario.stopReason());
  }

  /** 对照：被别的车挡住的车照常排进道岔队列——说明上一个用例里“不排队”确实来自闸的停车保持。 */
  @Test
  void anOccupancyBlockedTrainStillQueuesForTheSwitch() {
    Scenario scenario = new Scenario(0);
    scenario.occupy(OTHER, List.of(SWITCH_CONFLICT));

    scenario.tick();

    assertTrue(scenario.queued(TRAIN), scenario.queues().toString());
  }

  @Test
  void blockedDepartureStaysAtThePlatformWithSignallerStopReason() {
    RecordingGate blocking = new RecordingGate();
    blocking.block = true;
    Scenario scenario = new Scenario(4).gate(blocking);

    assertFalse(scenario.checkDeparture());

    assertEquals(SignallerGateCoordinator.STOP_REASON, scenario.stopReason());
    assertFalse(scenario.holdsMovement(OccupancyResource.forNode(BEYOND)), "出站进路没有排出来");
    assertFalse(scenario.queued(TRAIN));

    blocking.block = false;
    assertTrue(scenario.checkDeparture(), "信号员给了许可：下一次门控检查即放行");
  }

  @Test
  void theGateIsNotAskedWhileTheKernelHoldsTheDepartureFirst() {
    RecordingGate gate = new RecordingGate();
    Scenario scenario = new Scenario(4).gate(gate);
    Instant later = Instant.now().plusSeconds(60);
    scenario.service.stationStops().setSignallerHold(train -> Optional.of(later));

    assertFalse(scenario.checkDeparture(), "信号员扣车");
    assertTrue(gate.asked.isEmpty(), "扣着的车不申请进路，也就不问闸");
    assertTrue(scenario.service.stationStops().holdingForSchedule(TRAIN), "扣车期间健康层豁免");

    scenario.service.stationStops().setSignallerHold(train -> Optional.empty());
    assertTrue(scenario.checkDeparture(), "放行后下一次门控检查即放行");
    assertEquals(List.of(TRAIN), gate.asked);
  }

  @Test
  void aTrainWaitingForTheSignallerIsAManualHoldForHealthCleanup() {
    RecordingGate gate = new RecordingGate();
    Scenario scenario = new Scenario(0).gate(gate);
    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store
          .when(() -> TrainPropertiesStore.get(anyString()))
          .thenReturn(scenario.tags.properties());

      assertFalse(scenario.service.deadlockTrainContext(TRAIN).orElseThrow().manualHold());
      gate.waiting = true;
      assertTrue(
          scenario.service.deadlockTrainContext(TRAIN).orElseThrow().manualHold(),
          "等信号员与人工扣车同样对待：受控停车、卡住清理与阻挡状态显示");
    }
  }

  @Test
  void recoveryLayerDoesNotRelaunchOrReissueATrainWaitingForTheSignaller() {
    RecordingGate gate = new RecordingGate();
    Scenario scenario = new Scenario(0).gate(gate);
    gate.waiting = true;
    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store
          .when(() -> TrainPropertiesStore.get(anyString()))
          .thenReturn(scenario.tags.properties());

      assertFalse(scenario.service.forceRelaunchByName(TRAIN));
      assertFalse(scenario.service.reissueDestinationByName(TRAIN));
      store.verify(() -> TrainPropertiesStore.get(anyString()), never());
    }

    for (String entry : List.of("forceRelaunch blocked", "reissueDestination blocked")) {
      assertTrue(
          scenario.debug.stream()
              .anyMatch(
                  line ->
                      line.contains(entry)
                          && line.contains(SignallerGateCoordinator.DECISION_REASON)),
          entry + "\n" + String.join("\n", scenario.debug));
    }
    assertFalse(scenario.holdsMovement(SWITCH_CONFLICT), "恢复层没有绕过闸取得进路");
  }

  @Test
  void aManualRenameMigratesTheGateStateAndWakesTheNewName() {
    RecordingGate gate = new RecordingGate();
    Scenario scenario = new Scenario(0).gate(gate);
    String renamed = "SURC-MT-LH-9999";
    when(scenario.tags.properties().getTrainName()).thenReturn(renamed);

    assertEquals(renamed, scenario.service.handleRenameIfNeeded(scenario.tags.properties()));

    String from = TrainNameNormalizer.normalizeKey(TRAIN);
    String to = TrainNameNormalizer.normalizeKey(renamed);
    assertEquals(List.of("renamed:" + from + "->" + to), gate.calls);
    assertEquals(List.of(to), scenario.woken, "迁完唤醒新名重评估");
  }

  @Test
  void aRemovedTrainIsForgotten() {
    RecordingGate gate = new RecordingGate();
    Scenario scenario = new Scenario(0).gate(gate);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      scenario.service.handleTrainRemoved(TRAIN);
    }

    assertTrue(gate.calls.contains("forget:" + TRAIN), gate.calls.toString());
  }

  @Test
  void withoutAGateRenameAndRemovalWakeNothing() {
    Scenario scenario = new Scenario(0);
    when(scenario.tags.properties().getTrainName()).thenReturn("SURC-MT-LH-9999");

    scenario.service.handleRenameIfNeeded(scenario.tags.properties());

    assertTrue(scenario.woken.isEmpty());
  }

  // ---------------------------------------------------------------- 折返接续

  private static final String PREVIOUS = "turning-train";
  private static final NodeId APPROACH = NodeId.of("SURC:TERM:APPROACH:1");
  private static final NodeId TERMINAL = NodeId.of("SURC:S:TERM:1");
  private static final NodeId THROAT = NodeId.of("SURC:S:TERM:1:001");
  private static final NodeId CLEAR = NodeId.of("SURC:TERM:NEXT:1:001");

  /** 终点待命车的折返接续现场（照 {@code LayoverReuseHydrationOwnerTest}）。 */
  private static final class LayoverScene {
    final UUID worldId = UUID.randomUUID();
    final UUID arrivalRouteId = UUID.randomUUID();
    final UUID ticketRouteId = UUID.randomUUID();
    final AtomicReference<String> trainCartsName = new AtomicReference<>(PREVIOUS);
    final LayoverRegistry layoverRegistry = new LayoverRegistry();
    final List<String> debug = new ArrayList<>();
    final List<String> woken = new ArrayList<>();
    final RuntimeDispatchService service;
    final TagStore tags;
    final FakeTrain train;

    LayoverScene() {
      RailGraph graph = terminalGraph(worldId);
      ConfigManager configManager = mock(ConfigManager.class);
      when(configManager.current()).thenReturn(testConfigView(1, 20.0));
      RailGraphService railGraphService = mock(RailGraphService.class);
      when(railGraphService.getSnapshot(worldId))
          .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
      when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
              any(), any(), any(), anyDouble(), anyDouble()))
          .thenReturn(20.0);
      RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
      when(routeDefinitions.findById(arrivalRouteId))
          .thenReturn(
              Optional.of(
                  new RouteDefinition(
                      RouteId.of("arrival"), List.of(APPROACH, TERMINAL), Optional.empty())));
      when(routeDefinitions.findById(ticketRouteId))
          .thenReturn(
              Optional.of(
                  new RouteDefinition(
                      RouteId.of("turnback"), List.of(TERMINAL, THROAT, CLEAR), Optional.empty())));
      service =
          new RuntimeDispatchService(
              new SimpleOccupancyManager(
                  (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
              railGraphService,
              routeDefinitions,
              new RouteProgressRegistry(),
              mock(SignNodeRegistry.class),
              layoverRegistry,
              new DwellRegistry(),
              configManager,
              null,
              new TrainConfigResolver(),
              debug::add);
      service.setSignalReevaluationRequester(woken::add);
      tags = new TagStore(PREVIOUS, "FTA_ROUTE_ID=" + arrivalRouteId, "FTA_ROUTE_INDEX=1");
      when(tags.properties().getTrainName()).thenAnswer(inv -> trainCartsName.get());
      org.mockito.Mockito.doAnswer(
              inv -> {
                trainCartsName.set(inv.getArgument(0));
                return null;
              })
          .when(tags.properties())
          .setTrainName(anyString());
      train = new FakeTrain(worldId, tags.properties(), false);
      assertTrue(
          service.rebuildOccupancySnapshot(List.of(train)),
          "启动水合没有完成：\n  " + String.join("\n  ", debug));
      layoverRegistry.register(
          PREVIOUS,
          TerminalKeyResolver.toTerminalKey(TERMINAL),
          TERMINAL,
          Instant.now().minusSeconds(1),
          Map.of());
    }

    LayoverDispatchResult reuse() {
      LayoverRegistry.LayoverCandidate candidate = layoverRegistry.get(PREVIOUS).orElseThrow();
      ServiceTicket ticket =
          new ServiceTicket(
              "ticket-signaller",
              Instant.now(),
              ticketRouteId,
              candidate.terminalKey(),
              10,
              ServiceTicket.TicketMode.OPERATION);
      return service.dispatchLayover(candidate, ticket, tags.properties(), train);
    }
  }

  @Test
  void aBlockedTurnbackFailsForRetryWithoutClaimingTheTicket() {
    LayoverScene scene = new LayoverScene();
    RecordingGate gate = new RecordingGate();
    gate.block = true;
    scene.service.signallerGates().setGate(gate);

    LayoverDispatchResult result = scene.reuse();

    assertFalse(result.dispatched());
    assertEquals(SignallerGateCoordinator.DECISION_REASON, result.reason());
    assertTrue(
        scene.layoverRegistry.get(PREVIOUS).orElseThrow().dispatchAttempt().isEmpty(),
        "票据没被认领，候选原样留着等重试");
    assertEquals(PREVIOUS, scene.trainCartsName.get(), "没有改名");
    assertEquals(List.of(PREVIOUS), gate.asked);

    gate.block = false;
    gate.waiting = false;
    LayoverDispatchResult retried = scene.reuse();
    assertTrue(retried.dispatched(), retried.reason());
    String renamed = retried.trainName().orElseThrow();
    assertEquals(
        List.of(
            "renamed:"
                + TrainNameNormalizer.normalizeKey(PREVIOUS)
                + "->"
                + TrainNameNormalizer.normalizeKey(renamed)),
        gate.calls,
        "折返改名：许可随车迁到新名");
    assertTrue(scene.woken.contains(TrainNameNormalizer.normalizeKey(renamed)));
  }

  // ---------------------------------------------------------------- 夹具

  private static RuntimeDispatchService service(
      SimpleOccupancyManager manager,
      UUID worldId,
      RouteDefinition route,
      RouteProgressRegistry registry,
      List<String> debug) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0, 3, 2));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    sectionlessGraph(chain(worldId)), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    List<NodeId> waypoints = route.waypoints();
    for (int index = 0; index < waypoints.size(); index++) {
      NodeId node = waypoints.get(index);
      RouteStopPassType passType =
          node.equals(WSD) || node.equals(SCC) ? RouteStopPassType.STOP : RouteStopPassType.PASS;
      when(routeDefinitions.findStop(eq(ROUTE_ID), eq(index)))
          .thenReturn(Optional.of(routeStop(index, node, passType)));
    }
    return new RuntimeDispatchService(
        manager,
        railGraphService,
        routeDefinitions,
        registry,
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        new TrainConfigResolver(),
        debug::add);
  }

  private static SimpleRailGraph chain(UUID worldId) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(P4, railNode(P4, NodeType.WAYPOINT, 0.0, 0.0));
    nodes.put(SWITCH, railNode(SWITCH, NodeType.SWITCHER, 40.0, 0.0));
    nodes.put(BRANCH, railNode(BRANCH, NodeType.WAYPOINT, 40.0, 30.0));
    nodes.put(P6, railNode(P6, NodeType.WAYPOINT, 80.0, 0.0));
    nodes.put(P7, railNode(P7, NodeType.WAYPOINT, 100.0, 0.0));
    nodes.put(WSD, railNode(WSD, NodeType.STATION, 130.0, 0.0));
    nodes.put(BEYOND, railNode(BEYOND, NodeType.WAYPOINT, 160.0, 0.0));
    nodes.put(SCC, railNode(SCC, NodeType.STATION, 260.0, 0.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, P4, SWITCH, 40);
    putEdge(edges, SWITCH, BRANCH, 30);
    putEdge(edges, SWITCH, P6, 40);
    putEdge(edges, P6, P7, 20);
    putEdge(edges, P7, WSD, 30);
    putEdge(edges, WSD, BEYOND, 30);
    putEdge(edges, BEYOND, SCC, 100);
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    int z = 0;
    for (EdgeId id : edges.keySet()) {
      footprints.put(
          id,
          new RailEdgeFootprint(
              RailEdgeFootprint.CURRENT_FORMAT_VERSION,
              true,
              Set.of(new RailFootprintCell(0, 64, 100 + z++))));
    }
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(worldId, edges.keySet(), footprints));
  }

  private static RailGraph terminalGraph(UUID worldId) {
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, APPROACH, TERMINAL, 10);
    putEdge(edges, TERMINAL, THROAT, 10);
    putEdge(edges, THROAT, CLEAR, 10);
    Map<NodeId, RailNode> nodes =
        Map.of(
            APPROACH, new RailNodeTest(APPROACH),
            TERMINAL, new RailNodeTest(TERMINAL),
            THROAT, new RailNodeTest(THROAT),
            CLEAR, new RailNodeTest(CLEAR));
    return new SimpleRailGraph(
        nodes,
        edges,
        Set.of(),
        RailInterlockingState.fromSnapshot(
            worldId,
            edges.keySet(),
            new RailInterlockingCoverage(edges.size(), edges.size(), true),
            Map.of()));
  }

  private static RailNode railNode(NodeId id, NodeType type, double x, double z) {
    return new SignRailNode(id, type, new Vector(x, 64.0, z), Optional.empty(), Optional.empty());
  }

  private static void putEdge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int length) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, length, -1.0, true, Optional.empty()));
  }
}
