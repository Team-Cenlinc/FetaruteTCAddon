package org.fetarute.fetaruteTCAddon.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.Bukkit;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.OnDemandTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SimpleTicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * 加开（表外加开一班）：从车库出票、到计划时刻才出车；派出的车带加开标记，不算进线路的叫车车数；重启后按原编号、原计划时刻找回；可撤销；过了计划时刻 30 分钟还没出车就撤。
 *
 * <p>夹具：线路 WS（没开叫车），交路 DEP（车库，CRET）→ PPK → NTA。
 */
class CallExtraTest {

  private static final Instant T = Instant.parse("2026-10-10T12:00:00Z");
  private static final PidsStationKey PPK = new PidsStationKey("SURC", "PPK");

  private final UUID routeId = UUID.randomUUID();
  private final FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
  private final PendingCallRepository stored = mock(PendingCallRepository.class);
  private final TicketAssigner assigner = mock(TicketAssigner.class);
  private final SpawnManager spawnManager = mock(SpawnManager.class);
  private final List<PendingCallRecord> rows = new ArrayList<>();
  private final RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
  private Line line;
  private MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setUp() {
    bukkit = mockStatic(Bukkit.class);
    line =
        new Line(
            UUID.randomUUID(),
            "WS",
            UUID.randomUUID(),
            "WS",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.empty(),
            Map.of(),
            T,
            T);
    Operator operator =
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

  /** 指定了时刻：票到那时才出车，存库的叫车时刻一栏是计划发车时刻，方向一栏写着加开的交路与车库。 */
  @Test
  void anExtraWaitsForItsDepartureTime() {
    CallService service = new CallService(plugin);
    UUID issuer = UUID.randomUUID();
    Instant at = Instant.now().plus(Duration.ofHours(1));

    CallService.ExtraResult result = service.addExtra(routeId, "SURC:D:DEP:1", at, issuer);

    assertEquals(CallService.ExtraOutcome.ISSUED, result.outcome());
    ArgumentCaptor<SpawnTicket> ticket = ArgumentCaptor.forClass(SpawnTicket.class);
    verify(spawnManager).requeue(ticket.capture());
    assertEquals(result.id().orElseThrow(), ticket.getValue().id());
    assertEquals(TripSource.ON_DEMAND, ticket.getValue().source());
    assertEquals(at, ticket.getValue().notBefore(), "不到计划时刻不出车");
    assertEquals(at, ticket.getValue().dueAt());
    assertEquals(
        Optional.of(new CallTag(result.id().orElseThrow(), PPK).format()),
        OnDemandTrip.callTagOf(ticket.getValue().serviceTripId()),
        "车站一栏是交路第一个停车的车站");
    ArgumentCaptor<PendingCallRecord> saved = ArgumentCaptor.forClass(PendingCallRecord.class);
    verify(stored).save(saved.capture());
    assertEquals(at, saved.getValue().createdAt());
    assertEquals(issuer, saved.getValue().playerId());
    assertEquals(
        new ExtraTrips.Key(routeId, "SURC:D:DEP:1").format(), saved.getValue().directionKey());
    assertEquals(1, service.pendingExtras().size());
    assertEquals(at, service.pendingExtras().get(0).departAt());
  }

  /** 时刻已过或不写：尽快出车。 */
  @Test
  void anExtraWithoutATimeLeavesNow() {
    CallService service = new CallService(plugin);
    Instant before = Instant.now();

    service.addExtra(routeId, "SURC:D:DEP:1", before.minusSeconds(60), CallService.CONSOLE);

    ArgumentCaptor<SpawnTicket> ticket = ArgumentCaptor.forClass(SpawnTicket.class);
    verify(spawnManager).requeue(ticket.capture());
    assertFalse(ticket.getValue().notBefore().isBefore(before));
    assertFalse(ticket.getValue().notBefore().isAfter(Instant.now()));
  }

  /** 车库要对得上交路出库的车库（不看股道）；交路不在缓存里时拒绝。 */
  @Test
  void theDepotMustBeTheRoutesOwn() {
    CallService service = new CallService(plugin);

    CallService.ExtraResult wrong =
        service.addExtra(routeId, "SURC:D:OTHER:1", null, CallService.CONSOLE);
    assertEquals(CallService.ExtraOutcome.WRONG_DEPOT, wrong.outcome());
    assertEquals(Optional.of("SURC:D:DEP:1"), wrong.routeDepot());
    assertEquals(
        CallService.ExtraOutcome.ISSUED,
        service.addExtra(routeId, "SURC:D:DEP:2", null, CallService.CONSOLE).outcome(),
        "同一车库的另一条股道照样认");
    assertEquals(
        CallService.ExtraOutcome.NO_ROUTE,
        service.addExtra(UUID.randomUUID(), "SURC:D:DEP:1", null, CallService.CONSOLE).outcome());
  }

  /** 加开不是玩家叫的车：还没派出的、在路上的都不占线路的叫车车数。 */
  @Test
  void extrasDoNotCountAsPlayerCalls() {
    CallService service = new CallService(plugin);
    service.addExtra(routeId, "SURC:D:DEP:1", null, CallService.CONSOLE);
    assertTrue(service.activeCallsByLine().isEmpty());

    TrainProperties extra = extraTrain("Extra-1", UUID.randomUUID());
    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(extra));
      service.sweep(Instant.now());
    }

    assertTrue(service.activeCallsByLine().isEmpty());
    assertEquals(java.util.Set.of("Extra-1"), service.extraTrainNames());
    assertTrue(service.isCalledTrain("Extra-1"), "照样按叫来的车管：不进时刻表、跑完回库");
  }

  /** 派出时写上加开标记，加开的人收到通知；之后被玩家叫车接走跑的那一趟摘掉加开标记。 */
  @Test
  void dispatchMarksTheTrainAsAnExtra() {
    CallService service = new CallService(plugin);
    service.addExtra(routeId, "SURC:D:DEP:1", null, CallService.CONSOLE);
    ArgumentCaptor<SpawnTicket> ticket = ArgumentCaptor.forClass(SpawnTicket.class);
    verify(spawnManager).requeue(ticket.capture());
    TrainProperties properties = mock(TrainProperties.class);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.exists("Train-1")).thenReturn(true);
      store.when(() -> TrainPropertiesStore.get("Train-1")).thenReturn(properties);
      service.onDispatched(ticket.getValue(), "Train-1");
    }

    UUID id = ticket.getValue().id();
    verify(properties).addTags(CallService.TAG_CALL_EXTRA + "=" + id);
    assertTrue(service.pendingExtras().isEmpty());
    assertEquals(java.util.Set.of("Train-1"), service.extraTrainNames());
    verify(stored).delete(id);
  }

  /** 重启后票不在发车侧：按原编号、原计划时刻重新出票。 */
  @Test
  void aStoredExtraIsReissuedForItsPlannedTime() {
    Instant at = Instant.now().plus(Duration.ofMinutes(40));
    PendingCallRecord record =
        new PendingCallRecord(
            UUID.randomUUID(),
            CallService.CONSOLE,
            PPK,
            java.util.Set.of(),
            new ExtraTrips.Key(routeId, "SURC:D:DEP:1").format(),
            line.id(),
            at,
            OptionalInt.empty());
    rows.add(record);
    CallService service = new CallService(plugin);

    service.restorePending(Instant.now());

    ArgumentCaptor<SpawnTicket> ticket = ArgumentCaptor.forClass(SpawnTicket.class);
    verify(spawnManager).requeue(ticket.capture());
    assertEquals(record.id(), ticket.getValue().id());
    assertEquals(at, ticket.getValue().notBefore());
    verify(stored, never()).delete(any());
  }

  /** 撤销还没派出的加开：撤票、从库里删掉。 */
  @Test
  void aPendingExtraCanBeCancelled() {
    CallService service = new CallService(plugin);
    UUID id =
        service
            .addExtra(routeId, "SURC:D:DEP:1", Instant.now().plusSeconds(600), CallService.CONSOLE)
            .id()
            .orElseThrow();
    when(assigner.withdraw(id)).thenReturn(true);

    assertEquals(CallService.CancelOutcome.CANCELLED, service.cancelExtra(id));
    assertTrue(service.pendingExtras().isEmpty());
    verify(stored).delete(id);
    assertEquals(CallService.CancelOutcome.NOT_FOUND, service.cancelExtra(id));
  }

  /** 过了计划时刻 30 分钟还没出车（车库一直出不了车）就撤票。 */
  @Test
  void anExtraThatNeverLeavesTimesOut() {
    CallService service = new CallService(plugin);
    Instant at = Instant.now().plusSeconds(60);
    UUID id = service.addExtra(routeId, "SURC:D:DEP:1", at, CallService.CONSOLE).id().orElseThrow();
    when(assigner.isTicketLive(id)).thenReturn(true);
    when(assigner.withdraw(id)).thenReturn(true);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of());
      service.sweep(at.plus(ExtraTrips.TIMEOUT).minusSeconds(1));
      assertEquals(1, service.pendingExtras().size());
      service.sweep(at.plus(ExtraTrips.TIMEOUT).plusSeconds(1));
    }

    assertTrue(service.pendingExtras().isEmpty());
    verify(assigner).withdraw(id);
    verify(stored).delete(id);
  }

  @Test
  void keysClocksAndDepartureTimes() {
    ExtraTrips.Key key = new ExtraTrips.Key(routeId, "SURC:D:DEP:1");
    assertEquals(Optional.of(key), ExtraTrips.Key.parse(key.format()));
    assertTrue(ExtraTrips.isExtra(key.format()));
    assertFalse(ExtraTrips.isExtra("WS|NTA|1"));
    assertEquals(Optional.of(LocalTime.of(7, 5)), ExtraTrips.parseClock("07:05"));
    assertTrue(ExtraTrips.parseClock("24:00").isEmpty());
    assertTrue(ExtraTrips.parseClock("7").isEmpty());

    ZonedDateTime now = ZonedDateTime.of(2026, 10, 10, 18, 30, 0, 0, ZoneOffset.UTC);
    assertEquals(
        Instant.parse("2026-10-10T19:00:00Z"), ExtraTrips.departAt(LocalTime.of(19, 0), now));
    assertEquals(
        Instant.parse("2026-10-11T00:05:00Z"),
        ExtraTrips.departAt(LocalTime.of(0, 5), now),
        "已过的时刻是次日");
    assertTrue(CallService.sameYard("DYNAMIC:SURC:D:DEP", "SURC:D:DEP:3"));
    assertFalse(CallService.sameYard("SURC:D:DEP:1", "SURC:D:OFL:1"));
  }

  /** 一辆加开的车：车上带着叫车标签与加开标记。 */
  private static TrainProperties extraTrain(String name, UUID id) {
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(name);
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags())
        .thenReturn(
            List.of(
                SimpleTicketAssigner.TAG_CALLED_TRAIN + "=" + new CallTag(id, PPK).format(),
                CallService.TAG_CALL_EXTRA + "=" + id));
    return properties;
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
