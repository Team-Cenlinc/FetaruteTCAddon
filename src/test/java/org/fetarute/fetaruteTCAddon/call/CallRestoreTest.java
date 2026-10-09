package org.fetarute.fetaruteTCAddon.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.call.repository.PendingCallRepository;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.OnDemandTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.display.pids.PidsService;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * 重启后找回还没派出的叫车：票不在发车侧了就按原编号重新排车出票；票还在（重载）不动；方向没了、超时的作废并从库里删掉。
 *
 * <p>夹具：线路 WS 开了叫车，交路 DEP（车库，CRET）→ PPK → NTA，在 PPK 叫开往 NTA 的车（从车库出车）。
 */
class CallRestoreTest {

  private static final Instant T = Instant.parse("2026-10-08T12:00:00Z");
  private static final PidsStationKey PPK = new PidsStationKey("SURC", "PPK");

  private final UUID routeId = UUID.randomUUID();
  private final FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
  private final PendingCallRepository stored = mock(PendingCallRepository.class);
  private final TicketAssigner assigner = mock(TicketAssigner.class);
  private final SpawnManager spawnManager = mock(SpawnManager.class);
  private final List<PendingCallRecord> rows = new ArrayList<>();
  private final RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
  private Line line;
  private Operator operator;
  private MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setUp() {
    bukkit = mockStatic(Bukkit.class);
    line = line(Map.of("allow_player_call", true));
    operator =
        new Operator(
            UUID.randomUUID(),
            "SURC",
            UUID.randomUUID(),
            "SURC",
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Map.of(),
            T,
            T);
    List<NodeId> nodes =
        List.of(NodeId.of("SURC:D:DEP:1"), NodeId.of("SURC:S:PPK:1"), NodeId.of("SURC:S:NTA:1"));
    RouteDefinition definition =
        new RouteDefinition(new RouteId("SURC:WS:WS-N"), nodes, Optional.empty());
    List<RouteStop> stops =
        List.of(
            stop(0, "SURC:D:DEP:1", RouteStopPassType.PASS, "CRET SURC:D:DEP:1"),
            stop(1, "SURC:S:PPK:1", RouteStopPassType.STOP, null),
            stop(2, "SURC:S:NTA:1", RouteStopPassType.TERMINATE, null));
    Route route =
        new Route(
            routeId,
            "WS-N",
            line.id(),
            "WS-N",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            T,
            T);
    RouteDefinitionCache.RouteRecord record =
        new RouteDefinitionCache.RouteRecord(operator, line, route);
    when(cache.entries())
        .thenReturn(
            List.of(new RouteDefinitionCache.RouteEntry(routeId, definition, record, stops)));
    when(cache.findById(routeId)).thenReturn(Optional.of(definition));
    when(cache.listStops(definition.id())).thenReturn(stops);
    when(cache.findRecord(routeId)).thenReturn(Optional.of(record));
    when(plugin.getRouteDefinitionCache()).thenReturn(Optional.of(cache));
    when(plugin.getStationDirectory()).thenReturn(Optional.empty());
    when(plugin.getTimetableService()).thenReturn(Optional.empty());
    when(plugin.getLayoverRegistry()).thenReturn(Optional.empty());
    when(plugin.getPidsService()).thenReturn(Optional.empty());

    StorageProvider provider = mock(StorageProvider.class);
    LineRepository lines = mock(LineRepository.class);
    when(lines.listAll()).thenAnswer(invocation -> List.of(line));
    when(provider.lines()).thenReturn(lines);
    when(provider.pendingCalls()).thenReturn(stored);
    when(stored.listAll()).thenAnswer(invocation -> List.copyOf(rows));
    StorageManager storage = mock(StorageManager.class);
    when(storage.provider()).thenReturn(Optional.of(provider));
    when(plugin.getStorageManager()).thenReturn(storage);

    when(plugin.getSpawnTicketAssigner()).thenReturn(Optional.of(assigner));
    when(plugin.getSpawnManager()).thenReturn(Optional.of(spawnManager));
    when(spawnManager.snapshotPlan())
        .thenReturn(
            new SpawnPlan(
                T,
                List.of(
                    new SpawnService(
                        new SpawnServiceKey(routeId),
                        UUID.randomUUID(),
                        "SURC",
                        operator.id(),
                        "SURC",
                        line.id(),
                        "WS",
                        routeId,
                        "WS-N",
                        Duration.ofMinutes(10),
                        "SURC:D:DEP:1"))));
  }

  @AfterEach
  void tearDown() {
    bukkit.close();
  }

  /** 票不在发车侧（重启）：按原编号重新出票，叫车标签与超时起点照旧，库里那一行重新写入。 */
  @Test
  void aStoredCallWhoseTicketIsGoneIsReissuedUnderItsOwnId() {
    PendingCallRecord call = storedCall(T.minusSeconds(30));
    CallService service = new CallService(plugin);

    service.restorePending(T);

    ArgumentCaptor<SpawnTicket> ticket = ArgumentCaptor.forClass(SpawnTicket.class);
    verify(spawnManager).requeue(ticket.capture());
    assertEquals(call.id(), ticket.getValue().id());
    assertEquals(TripSource.ON_DEMAND, ticket.getValue().source());
    assertEquals(
        Optional.of(new CallTag(call.id(), PPK).format()),
        OnDemandTrip.callTagOf(ticket.getValue().serviceTripId()));
    ArgumentCaptor<PendingCallRecord> saved = ArgumentCaptor.forClass(PendingCallRecord.class);
    verify(stored).save(saved.capture());
    assertEquals(call.id(), saved.getValue().id());
    assertEquals(call.createdAt(), saved.getValue().createdAt(), "超时仍从原叫车时刻算");
    verify(stored, never()).delete(any());
  }

  /** 票还在（重载）：不重复出票。 */
  @Test
  void aStoredCallWhoseTicketIsLiveIsLeftAlone() {
    PendingCallRecord call = storedCall(T.minusSeconds(30));
    when(assigner.isTicketLive(call.id())).thenReturn(true);
    CallService service = new CallService(plugin);

    service.restorePending(T);

    verify(spawnManager, never()).requeue(any());
    verify(stored, never()).delete(any());
  }

  /** 线路关了叫车：方向没了，作废并从库里删掉。 */
  @Test
  void aStoredCallWhoseDirectionIsGoneIsDropped() {
    PendingCallRecord call = storedCall(T.minusSeconds(30));
    line = line(Map.of());
    CallService service = new CallService(plugin);

    service.restorePending(T);

    verify(spawnManager, never()).requeue(any());
    verify(stored).delete(call.id());
  }

  /** 找回时已经等过了超时：作废，不再出票。 */
  @Test
  void aStoredCallThatTimedOutIsDropped() {
    PendingCallRecord call = storedCall(T.minus(Duration.ofMinutes(11)));
    CallService service = new CallService(plugin);

    service.restorePending(T);

    verify(spawnManager, never()).requeue(any());
    verify(stored).delete(call.id());
  }

  /** 读库只在第一次启动时做：之后再启动（重载）不会把已删掉的叫车读回来。 */
  @Test
  void storedCallsAreReadOnlyOnce() {
    storedCall(T.minusSeconds(30));
    CallService service = new CallService(plugin);
    service.restorePending(T);
    rows.add(storedCallRecord(T.minusSeconds(5)));

    service.restorePending(T.plusSeconds(1));

    verify(stored, org.mockito.Mockito.times(1)).listAll();
  }

  /** 找回推迟到启动后 {@link CallService#RESTORE_DELAY}：现场占用重建、待命池登记做完再排车；这之前也不撤票。 */
  @Test
  void restoreWaitsForTheStartupDelay() {
    bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
    storedCall(Instant.now().minusSeconds(30));
    CallService service = new CallService(plugin);
    service.start();
    Instant now = Instant.now();

    service.sweep(now);
    verify(spawnManager, never()).requeue(any());
    verify(stored, never()).delete(any());

    service.sweep(now.plus(CallService.RESTORE_DELAY).plusSeconds(1));
    verify(spawnManager).requeue(any());
  }

  /** 线路开关还没读到（后台读还没完成或读失败）时不找回：读不到就当方向没了，叫车会被作废。 */
  @Test
  void restoreWaitsForTheCallLines() {
    when(plugin.isEnabled()).thenReturn(true);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    List<Runnable> queued = new ArrayList<>();
    when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              queued.add(invocation.getArgument(1));
              return null;
            });
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    storedCall(Instant.now().minusSeconds(30));
    CallService service = new CallService(plugin);
    service.start();
    Instant due = Instant.now().plus(CallService.RESTORE_DELAY).plusSeconds(1);

    service.sweep(due);
    verify(spawnManager, never()).requeue(any());
    verify(stored, never()).delete(any());

    queued.remove(0).run();
    service.sweep(due.plusSeconds(1));
    verify(spawnManager).requeue(any());
  }

  /** 按表运行打开时找回不接待命车：刚重启时账本是空的，首站那辆车看着没绑交路，其实多半还担着时刻表的班。 */
  @Test
  void restoreDoesNotTakeAStandbyTrainWhenTheTimetableIsOn() {
    PendingCallRecord call = standbyOnlyRoute();
    TimetableService timetable = mock(TimetableService.class);
    when(timetable.settings())
        .thenReturn(
            new TimetableService.Settings(
                true, true, Duration.ZERO, Duration.ofSeconds(300), Duration.ZERO));
    when(timetable.dutyBindingOf(any())).thenReturn(Optional.empty());
    when(plugin.getTimetableService()).thenReturn(Optional.of(timetable));

    new CallService(plugin).restorePending(T);

    verify(spawnManager, never()).requeue(any());
    verify(stored).delete(call.id());
  }

  /** 不按表运行的线路：首站的待命车照常可接（对照）。 */
  @Test
  void withoutATimetableRestoreMayUseTheStandbyTrain() {
    PendingCallRecord call = standbyOnlyRoute();

    new CallService(plugin).restorePending(T);

    ArgumentCaptor<SpawnTicket> ticket = ArgumentCaptor.forClass(SpawnTicket.class);
    verify(spawnManager).requeue(ticket.capture());
    assertEquals(call.id(), ticket.getValue().id());
  }

  /** 读库失败（存储一时不可用）不算读过：下一次找回再读。 */
  @Test
  void aFailedReadIsRetriedOnTheNextRestore() {
    storedCall(T.minusSeconds(30));
    when(stored.listAll())
        .thenThrow(new StorageException("db down"))
        .thenAnswer(invocation -> List.copyOf(rows));
    CallService service = new CallService(plugin);

    service.restorePending(T);
    verify(spawnManager, never()).requeue(any());

    service.restorePending(T.plusSeconds(1));
    verify(spawnManager).requeue(any());
  }

  /** 找回改了叫车（重新出票或作废）：站台屏快照作废，立刻按新的叫车显示。 */
  @Test
  void restoreInvalidatesTheBoards() {
    PidsService pids = mock(PidsService.class);
    when(plugin.getPidsService()).thenReturn(Optional.of(pids));
    storedCall(T.minusSeconds(30));

    new CallService(plugin).restorePending(T);

    verify(pids).invalidateSnapshots();
  }

  /** 插件运行时存库交给后台、按提交顺序执行，不占主线程。 */
  @Test
  void storageWritesRunInTheBackground() {
    CallService service = new CallService(plugin);
    // 线路开关先读好（插件未启用时当场读），下面只排存库这一件后台任务。
    service.callableAt(PPK, Set.of(), Set.of());
    when(plugin.isEnabled()).thenReturn(true);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    List<Runnable> queued = new ArrayList<>();
    when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              queued.add(invocation.getArgument(1));
              return null;
            });
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    storedCall(T.minusSeconds(30));

    service.restorePending(T);

    verify(stored, never()).save(any());
    assertEquals(1, queued.size());
    queued.remove(0).run();
    verify(stored).save(any());
  }

  /** 派出时把指定的站台写进列车标签；查指定站台不分列车名大小写。 */
  @Test
  void dispatchWritesThePinTag() {
    useDynamicPpk();
    storedCall(T.minusSeconds(30), Set.of("2"));
    CallService service = new CallService(plugin);
    service.restorePending(T);
    ArgumentCaptor<SpawnTicket> ticket = ArgumentCaptor.forClass(SpawnTicket.class);
    verify(spawnManager).requeue(ticket.capture());
    TrainProperties properties = mock(TrainProperties.class);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.exists("Train-1")).thenReturn(true);
      store.when(() -> TrainPropertiesStore.get("Train-1")).thenReturn(properties);
      service.onDispatched(ticket.getValue(), "Train-1");
    }

    verify(properties).addTags(CallService.TAG_CALL_PLATFORM + "=" + routeId + "|1|SURC:S:PPK:2");
    assertEquals(Optional.of("SURC:S:PPK:2"), service.pinnedPlatformOf("train-1", routeId, 1));
  }

  /** 接着跑一趟没有指定站台的叫车：上一趟留下的站台标签摘掉。 */
  @Test
  void dispatchWithoutAPinRemovesTheOldTag() {
    storedCall(T.minusSeconds(30));
    CallService service = new CallService(plugin);
    service.restorePending(T);
    ArgumentCaptor<SpawnTicket> ticket = ArgumentCaptor.forClass(SpawnTicket.class);
    verify(spawnManager).requeue(ticket.capture());
    String old = CallService.TAG_CALL_PLATFORM + "=" + UUID.randomUUID() + "|1|SURC:S:PPK:1";
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags()).thenReturn(List.of(old));

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.exists("Train-1")).thenReturn(true);
      store.when(() -> TrainPropertiesStore.get("Train-1")).thenReturn(properties);
      service.onDispatched(ticket.getValue(), "Train-1");
    }

    verify(properties).removeTags(old);
    assertTrue(service.pinnedPlatformOf("Train-1", routeId, 1).isEmpty());
  }

  /** 重启后按列车标签认出叫来的车，指定的站台一并读回。 */
  @Test
  void theRestartReadsThePinBackFromTheTag() {
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags())
        .thenReturn(
            List.of(
                "FTA_CALL=" + new CallTag(UUID.randomUUID(), PPK).format(),
                CallService.TAG_CALL_PLATFORM + "=" + routeId + "|1|SURC:S:PPK:2"));
    when(properties.getTrainName()).thenReturn("Train-9");
    CallService service = new CallService(plugin);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(properties));
      service.restorePending(T);
    }

    assertEquals(Optional.of("SURC:S:PPK:2"), service.pinnedPlatformOf("Train-9", routeId, 1));
  }

  /** 交路改成首站 AAA（不是车库），首站有一辆待命车；返回这一方向的叫车。 */
  private PendingCallRecord standbyOnlyRoute() {
    useRoute(
        List.of(
            stop(0, "SURC:S:AAA:1", RouteStopPassType.STOP, null),
            stop(1, "SURC:S:PPK:1", RouteStopPassType.STOP, null),
            stop(2, "SURC:S:NTA:1", RouteStopPassType.TERMINATE, null)));
    LayoverRegistry registry = new LayoverRegistry();
    registry.register(
        "standby-1", "surc:s:aaa:1", NodeId.of("SURC:S:AAA:1"), T.minusSeconds(60), Map.of());
    when(plugin.getLayoverRegistry()).thenReturn(Optional.of(registry));
    return storedCall(T.minusSeconds(30));
  }

  /** PPK 改成 DYNAMIC 停靠（1、2 道）。 */
  private void useDynamicPpk() {
    useRoute(
        List.of(
            stop(0, "SURC:D:DEP:1", RouteStopPassType.PASS, "CRET SURC:D:DEP:1"),
            stop(1, "SURC:S:PPK:1", RouteStopPassType.STOP, "DYNAMIC:SURC:S:PPK:[1:2]"),
            stop(2, "SURC:S:NTA:1", RouteStopPassType.TERMINATE, null)));
  }

  private void useRoute(List<RouteStop> stops) {
    List<NodeId> nodes =
        stops.stream().map(stop -> NodeId.of(stop.waypointNodeId().orElseThrow())).toList();
    RouteDefinition definition =
        new RouteDefinition(new RouteId("SURC:WS:WS-N"), nodes, Optional.empty());
    RouteDefinitionCache.RouteRecord record = cache.findRecord(routeId).orElseThrow();
    when(cache.entries())
        .thenReturn(
            List.of(new RouteDefinitionCache.RouteEntry(routeId, definition, record, stops)));
    when(cache.findById(routeId)).thenReturn(Optional.of(definition));
    when(cache.listStops(definition.id())).thenReturn(stops);
  }

  /** 首站是车库：有车源，不报。 */
  @Test
  void aDepotRouteHasASource() {
    assertTrue(new CallService(plugin).unsourcedDirections(line.id()).isEmpty());
  }

  /** 首站不是车库、没有交路在首站终到、查不到调度图（没有区间点可生成）：首站与途中站的这个方向都报；补上一条在首站终到并留在待命池的交路就不报。 */
  @Test
  void aDirectionWithoutAnySourceIsReported() {
    RouteDefinitionCache.RouteEntry plain =
        entry(
            UUID.randomUUID(),
            org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode.DESTROY_AFTER_TERM,
            RouteOperationType.OPERATION,
            "SURC:S:AAA:1",
            "SURC:S:PPK:1",
            "SURC:S:NTA:1");
    when(cache.entries()).thenReturn(List.of(plain));

    List<CallService.UnsourcedDirection> unsourced =
        new CallService(plugin).unsourcedDirections(line.id());

    assertEquals(
        List.of(new PidsStationKey("SURC", "AAA"), PPK),
        unsourced.stream().map(CallService.UnsourcedDirection::station).toList(),
        "首站与途中站开往 NTA 的方向都没有车源");
    assertTrue(
        unsourced.stream()
            .allMatch(d -> d.direction().destination().equals(new PidsStationKey("SURC", "NTA"))));

    RouteDefinitionCache.RouteEntry endsAtAaa =
        entry(
            UUID.randomUUID(),
            org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode.REUSE_AT_TERM,
            RouteOperationType.OPERATION,
            "SURC:S:NTA:1",
            "SURC:S:PPK:2",
            "SURC:S:AAA:2");
    when(cache.entries()).thenReturn(List.of(plain, endsAtAaa));

    List<CallService.UnsourcedDirection> after =
        new CallService(plugin).unsourcedDirections(line.id());
    assertTrue(
        after.stream()
            .noneMatch(d -> d.direction().destination().equals(new PidsStationKey("SURC", "NTA"))),
        "首站 AAA 有交路终到并留在待命池: " + after);
  }

  private RouteDefinitionCache.RouteEntry entry(
      UUID id,
      org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode lifecycle,
      RouteOperationType type,
      String... nodes) {
    List<RouteStop> stops = new ArrayList<>();
    for (int i = 0; i < nodes.length; i++) {
      stops.add(
          new RouteStop(
              id,
              i,
              Optional.empty(),
              Optional.of(nodes[i]),
              Optional.empty(),
              i == nodes.length - 1 ? RouteStopPassType.TERMINATE : RouteStopPassType.STOP,
              Optional.empty()));
    }
    Route route =
        new Route(
            id,
            "R-" + id,
            line.id(),
            "R",
            Optional.empty(),
            RoutePatternType.LOCAL,
            type,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            T,
            T);
    return new RouteDefinitionCache.RouteEntry(
        id,
        new RouteDefinition(
            new RouteId("SURC:WS:R-" + id),
            java.util.Arrays.stream(nodes).map(NodeId::of).toList(),
            Optional.empty(),
            lifecycle),
        new RouteDefinitionCache.RouteRecord(operator, line, route),
        stops);
  }

  private PendingCallRecord storedCall(Instant createdAt) {
    return storedCall(createdAt, Set.of());
  }

  private PendingCallRecord storedCall(Instant createdAt, Set<String> screenPlatforms) {
    PendingCallRecord call = storedCallRecord(createdAt, screenPlatforms);
    rows.add(call);
    return call;
  }

  private PendingCallRecord storedCallRecord(Instant createdAt) {
    return storedCallRecord(createdAt, Set.of());
  }

  private PendingCallRecord storedCallRecord(Instant createdAt, Set<String> screenPlatforms) {
    String key =
        CallCatalog.directions(
                List.of(plugin.getRouteDefinitionCache().orElseThrow().entries().iterator().next()),
                null,
                candidate -> true,
                PPK,
                screenPlatforms)
            .get(0)
            .key();
    return new PendingCallRecord(
        UUID.randomUUID(),
        UUID.randomUUID(),
        PPK,
        screenPlatforms,
        key,
        line.id(),
        createdAt,
        OptionalInt.empty());
  }

  private Line line(Map<String, Object> metadata) {
    UUID id = line == null ? UUID.randomUUID() : line.id();
    return new Line(
        id,
        "WS",
        UUID.randomUUID(),
        "WS",
        Optional.empty(),
        LineServiceType.METRO,
        Optional.empty(),
        LineStatus.ACTIVE,
        Optional.empty(),
        metadata,
        T,
        T);
  }

  private RouteStop stop(int sequence, String node, RouteStopPassType type, String notes) {
    return new RouteStop(
        routeId,
        sequence,
        Optional.empty(),
        Optional.of(node),
        Optional.empty(),
        type,
        Optional.ofNullable(notes));
  }
}
