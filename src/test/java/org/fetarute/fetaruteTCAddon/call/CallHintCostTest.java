package org.fetarute.fetaruteTCAddon.call;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
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
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsService;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
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
 * 站台屏叫车提示在各种情况下做多少事：排车源（每条交路查一次交路定义）、读线路开关（查库）各几次。
 *
 * <p>夹具：线路 WS 开了叫车，PPK 有两个方向（开往 NTA、开往 AAA），都从车库出车；线路 MT 不开叫车。
 */
class CallHintCostTest {

  private static final Instant T = Instant.parse("2026-10-09T12:00:00Z");
  private static final PidsStationKey PPK = new PidsStationKey("SURC", "PPK");

  private final FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
  private final RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
  private final LineRepository lineRepository = mock(LineRepository.class);
  private final List<UUID> routeIds = new ArrayList<>();
  private Operator operator;
  private Line ws;
  private MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setUp() {
    bukkit = mockStatic(Bukkit.class);
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
    ws = line("WS", Map.of("allow_player_call", true));
    List<RouteDefinitionCache.RouteEntry> entries = new ArrayList<>();
    entries.add(depotRoute("WS-N", "SURC:S:NTA:1"));
    entries.add(depotRoute("WS-A", "SURC:S:AAA:1"));
    when(cache.entries()).thenReturn(entries);
    when(plugin.getRouteDefinitionCache()).thenReturn(Optional.of(cache));
    when(plugin.getStationDirectory()).thenReturn(Optional.empty());
    when(plugin.getTimetableService()).thenReturn(Optional.empty());
    when(plugin.getLayoverRegistry()).thenReturn(Optional.empty());
    when(plugin.getPidsService()).thenReturn(Optional.empty());

    when(lineRepository.listAll()).thenAnswer(invocation -> List.of(ws));
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.lines()).thenReturn(lineRepository);
    StorageManager storage = mock(StorageManager.class);
    when(storage.provider()).thenReturn(Optional.of(provider));
    when(plugin.getStorageManager()).thenReturn(storage);
  }

  @AfterEach
  void tearDown() {
    bukkit.close();
  }

  /** 绝大多数车站：线路没开叫车。不排车源，线路开关一段时间只读一次。 */
  @Test
  void aStationWithoutCallLinesNeverPlans() {
    ws = line("WS", Map.of());
    CallService service = new CallService(plugin);

    assertFalse(service.callableAt(PPK, Set.of(), Set.of()));
    assertFalse(service.callableAt(PPK, Set.of("1"), Set.of()));
    assertFalse(service.callableAt(PPK, Set.of("2"), Set.of()));

    verify(cache, never()).findById(any());
    verify(lineRepository, times(1)).listAll();
  }

  /** 两个方向都能叫：排到第一个有车源的方向就停，不把另一个方向也排一遍。 */
  @Test
  void theHintStopsAtTheFirstCallableDirection() {
    CallService service = new CallService(plugin);

    assertTrue(service.callableAt(PPK, Set.of(), Set.of()));

    verify(cache, times(1)).findById(any());
  }

  /** 屏幕只显示别的线路：先按线路筛掉，不排车源。 */
  @Test
  void aScreenShowingOtherLinesDoesNotPlan() {
    CallService service = new CallService(plugin);

    assertFalse(service.callableAt(PPK, Set.of(), Set.of("MT")));
    assertTrue(service.callableAt(PPK, Set.of(), Set.of("ws")), "线路代码不分大小写");

    verify(cache, times(1)).findById(any());
  }

  /** 两个方向的下一班都快到了：除车源以外的条件就不过，不排车源。 */
  @Test
  void directionsWithATrainSoonAreNotPlanned() {
    PidsService pids = mock(PidsService.class);
    when(pids.snapshot(PPK))
        .thenReturn(
            new PidsSnapshot(
                PPK,
                Instant.now(),
                List.of(
                    row("SURC:NTA", Instant.now().plusSeconds(120)),
                    row("SURC:AAA", Instant.now().plusSeconds(90)))));
    when(plugin.getPidsService()).thenReturn(Optional.of(pids));
    CallService service = new CallService(plugin);

    assertFalse(service.callableAt(PPK, Set.of(), Set.of()));

    verify(cache, never()).findById(any());
  }

  /** 同一块屏幕几秒内再问：用缓存，不再排车源。 */
  @Test
  void theHintIsReusedForAFewSeconds() {
    CallService service = new CallService(plugin);

    assertTrue(service.callableAt(PPK, Set.of(), Set.of()));
    assertTrue(service.callableAt(PPK, Set.of(), Set.of()));

    verify(cache, times(1)).findById(any());
  }

  /** 插件运行时线路开关在后台读：站台屏不在主线程等数据库，读完之前按上一份（一开始没有）判定，读完后提示重新判定。 */
  @Test
  void callLinesAreReadInTheBackground() {
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    when(plugin.isEnabled()).thenReturn(true);
    CallService service = new CallService(plugin);

    assertFalse(service.callableAt(PPK, Set.of(), Set.of()), "还没读到线路开关");
    verify(lineRepository, never()).listAll();
    ArgumentCaptor<Runnable> read = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).runTaskAsynchronously(eq(plugin), read.capture());

    read.getValue().run();

    verify(lineRepository, times(1)).listAll();
    assertTrue(service.callableAt(PPK, Set.of(), Set.of()), "读到之后提示不再用旧的缓存");
    verify(scheduler, times(1)).runTaskAsynchronously(eq(plugin), any(Runnable.class));
  }

  /** 读线路开关失败（数据库断开）：沿用上一份，过一阵再读，不是每次都重试。 */
  @Test
  void aFailedLineReadIsNotRetriedOnEveryRefresh() {
    when(lineRepository.listAll()).thenThrow(new StorageException("down"));
    CallService service = new CallService(plugin);

    assertFalse(service.callableAt(PPK, Set.of(), Set.of()));
    assertFalse(service.callableAt(PPK, Set.of("1"), Set.of()));
    assertFalse(service.callableAt(PPK, Set.of("2"), Set.of()));

    verify(lineRepository, times(1)).listAll();
  }

  /** 交路或线路改了：线路开关重读。 */
  @Test
  void invalidateRereadsTheLines() {
    CallService service = new CallService(plugin);
    assertTrue(service.callableAt(PPK, Set.of(), Set.of()));

    ws = line("WS", Map.of());
    service.invalidate();

    assertFalse(service.callableAt(PPK, Set.of(), Set.of()));
    verify(lineRepository, times(2)).listAll();
  }

  /** 按住右键：同一玩家一秒内只开一次对话框，别的玩家不受影响。 */
  @Test
  void theDialogOpensAtMostOncePerSecondPerPlayer() {
    CallService service = new CallService(plugin);
    UUID player = UUID.randomUUID();

    assertTrue(service.dialogAllowed(player, T));
    assertFalse(service.dialogAllowed(player, T.plusMillis(200)));
    assertFalse(service.dialogAllowed(player, T.plusMillis(999)));
    assertTrue(service.dialogAllowed(UUID.randomUUID(), T.plusMillis(500)));
    assertTrue(service.dialogAllowed(player, T.plus(CallService.DIALOG_INTERVAL)));
  }

  /** 车长按交路记住；写了编组却量不出来（TrainCarts 没读入存档车）时不记，下次再量；交路改了重量。 */
  @Test
  void trainLengthsAreRememberedPerRoute() {
    CallPlanner planner = new CallPlanner(plugin);
    UUID plain = routeIds.get(0);

    planner.trainLengthBlocks(plain);
    planner.trainLengthBlocks(plain);
    verify(cache, times(1)).findRecord(plain);

    planner.clearTimings();
    planner.trainLengthBlocks(plain);
    verify(cache, times(2)).findRecord(plain);

    UUID patterned = UUID.randomUUID();
    RouteDefinitionCache.RouteRecord record =
        new RouteDefinitionCache.RouteRecord(
            operator, ws, route(patterned, "WS-P", Map.of("spawn_train_pattern", "metro6")));
    when(cache.findRecord(patterned)).thenReturn(Optional.of(record));
    planner.trainLengthBlocks(patterned);
    planner.trainLengthBlocks(patterned);
    verify(cache, times(2)).findRecord(patterned);
  }

  private RouteDefinitionCache.RouteEntry depotRoute(String code, String terminus) {
    UUID id = UUID.randomUUID();
    routeIds.add(id);
    List<NodeId> nodes =
        List.of(NodeId.of("SURC:D:DEP:1"), NodeId.of("SURC:S:PPK:1"), NodeId.of(terminus));
    RouteDefinition definition =
        new RouteDefinition(new RouteId("SURC:WS:" + code), nodes, Optional.empty());
    List<RouteStop> stops =
        List.of(
            stop(id, 0, "SURC:D:DEP:1", RouteStopPassType.PASS, "CRET SURC:D:DEP:1"),
            stop(id, 1, "SURC:S:PPK:1", RouteStopPassType.STOP, null),
            stop(id, 2, terminus, RouteStopPassType.TERMINATE, null));
    RouteDefinitionCache.RouteRecord record =
        new RouteDefinitionCache.RouteRecord(operator, ws, route(id, code, Map.of()));
    when(cache.findById(id)).thenReturn(Optional.of(definition));
    when(cache.listStops(definition.id())).thenReturn(stops);
    when(cache.findRecord(id)).thenReturn(Optional.of(record));
    return new RouteDefinitionCache.RouteEntry(id, definition, record, stops);
  }

  private Route route(UUID id, String code, Map<String, Object> metadata) {
    return new Route(
        id,
        code,
        ws.id(),
        code,
        Optional.empty(),
        RoutePatternType.LOCAL,
        RouteOperationType.OPERATION,
        Optional.empty(),
        Optional.empty(),
        metadata,
        T,
        T);
  }

  private Line line(String code, Map<String, Object> metadata) {
    UUID id = ws == null ? UUID.randomUUID() : ws.id();
    return new Line(
        id,
        code,
        UUID.randomUUID(),
        code,
        Optional.empty(),
        LineServiceType.METRO,
        Optional.empty(),
        LineStatus.ACTIVE,
        Optional.empty(),
        metadata,
        T,
        T);
  }

  private static RouteStop stop(
      UUID routeId, int sequence, String node, RouteStopPassType type, String notes) {
    return new RouteStop(
        routeId,
        sequence,
        Optional.empty(),
        Optional.of(node),
        Optional.empty(),
        type,
        Optional.ofNullable(notes));
  }

  private static PidsRow row(String destinationId, Instant at) {
    return new PidsRow(
        PidsRow.Status.EN_ROUTE,
        "WS",
        "SURC:WS:R",
        destinationId,
        Optional.of(destinationId),
        "1",
        at,
        OptionalLong.of(0),
        1,
        false,
        false,
        false,
        Optional.of("Train-" + destinationId));
  }
}
