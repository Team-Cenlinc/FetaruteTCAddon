package org.fetarute.fetaruteTCAddon.call;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 叫车服务单测的共用夹具：线路 WS，交路 DEP（车库，CRET）→ PPK → NTA；插件、交路缓存、存储（未派出的叫车表）、发车侧都是桩。
 *
 * <p>{@link #line} 可以换：线路表的桩每次读都取当前这一份。
 */
final class CallFixture {

  final Instant now;
  final UUID routeId = UUID.randomUUID();
  final FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
  final PendingCallRepository stored = mock(PendingCallRepository.class);
  final TicketAssigner assigner = mock(TicketAssigner.class);
  final SpawnManager spawnManager = mock(SpawnManager.class);
  final List<PendingCallRecord> rows = new ArrayList<>();
  final RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
  final Operator operator;
  Line line;

  /**
   * @param now 夹具里各记录的时刻
   * @param lineMetadata 线路元数据（例如是否开了叫车）
   */
  CallFixture(Instant now, Map<String, Object> lineMetadata) {
    this.now = now;
    line = line(lineMetadata);
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
            now,
            now);
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
            now,
            now);
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
                now,
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

  /** 线路 WS（换元数据时沿用同一个 id）。 */
  Line line(Map<String, Object> metadata) {
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
        now,
        now);
  }

  /** 本夹具交路上的一个停靠。 */
  RouteStop stop(int sequence, String node, RouteStopPassType type, String notes) {
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
