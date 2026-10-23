package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.allowProceed;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.graphWithSingleEdge;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.mockOccupancyManager;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.registryWithStation;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.withRuntimeSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 车站停车由进站控制沿自动运行曲线接管时，移动授权与信号怎么对待这个计划停车点；关闭速度曲线时照旧交给移动授权。
 *
 * <p>独立成类：{@code RuntimeDispatchServiceTest} 已贴着 SpotBugs 单类 1000 方法的上限，再加会整类跳过静态分析。
 */
class RuntimeDispatchStationCurveTest {

  /** 计划停车点前一拍：车速 10、离车站 25 格；进站限速为 0（车库与区间停车点才用它）。 */
  private record RouteStopTick(
      RouteProgressRegistry registry,
      FakeTrain train,
      TagStore tags,
      java.util.concurrent.atomic.AtomicReference<String> destination,
      List<String> debugMessages,
      String station) {}

  private RouteStopTick routeStopTick(boolean speedCurve) {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId station = NodeId.of("OP:S:CENTRAL:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, station), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    java.util.concurrent.atomic.AtomicReference<String> destination =
        new java.util.concurrent.atomic.AtomicReference<>(station.value());
    when(tags.properties().getDestination()).thenAnswer(inv -> destination.get());
    org.mockito.Mockito.doAnswer(
            inv -> {
              destination.set("");
              return null;
            })
        .when(tags.properties())
        .clearDestination();
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(1, 40.0);
    when(configManager.current())
        .thenReturn(
            speedCurve ? base : withRuntimeSettings(base, runtimeWithSpeedCurve(base, false)));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(a, station, 25), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(40.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(routeStop(1, station, RouteStopPassType.STOP)));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    RouteProgressRegistry registry = new RouteProgressRegistry();
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            registry,
            registryWithStation(worldId, station),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            debugMessages::add);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.5);
    service.handleSignalTick(train, true);
    return new RouteStopTick(registry, train, tags, destination, debugMessages, station.value());
  }

  /** 关闭速度曲线时车站没有曲线可接、进站限速又为 0：计划停车点交给移动授权，发“注意”收紧，不硬停、不清目的地、不扣车。 */
  @Test
  void routeStopPublishesCautionWithoutRecoverableHold() {
    RouteStopTick tick = routeStopTick(false);
    List<String> debugMessages = tick.debugMessages();

    assertEquals(
        SignalAspect.PROCEED_WITH_CAUTION,
        tick.registry().get("train-1").orElseThrow().lastSignal());
    assertEquals(0, tick.train().hardStopCalls);
    verify(tick.tags().properties(), never()).clearDestination();
    assertEquals(tick.station(), tick.destination().get());
    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tick.tags().properties()).setSpeedLimit(speedCaptor.capture());
    assertTrue(speedCaptor.getValue() > 0.0);
    assertFalse(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DISPATCH_RECOVERABLE_HOLD")
                        && message.contains("riskSource=ROUTE_STOP_OR_TERMINAL")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("MOVEMENT_AUTHORITY_PLANNED_STOP_ADVISORY")
                        && message.contains("reason=ROUTE_STOP_OR_TERMINAL")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DISPATCH_ACTION_OBSERVED")
                        && message.contains("effectClass=SIGNAL_ADVISORY")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_AUTHORITY_WINDOW_EXCEEDED_REAL")));
  }

  /** 速度曲线开启时车站由天花板沿自动运行曲线刹到停车点：移动授权不再把车站当“刹到 0”的终点，信号保持通行；同样不硬停、不清目的地、不扣车。 */
  @Test
  void stationStopGovernedByItsCurveStaysProceedWithoutRecoverableHold() {
    RouteStopTick tick = routeStopTick(true);
    List<String> debugMessages = tick.debugMessages();

    assertEquals(SignalAspect.PROCEED, tick.registry().get("train-1").orElseThrow().lastSignal());
    assertEquals(0, tick.train().hardStopCalls);
    verify(tick.tags().properties(), never()).clearDestination();
    assertEquals(tick.station(), tick.destination().get());
    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tick.tags().properties()).setSpeedLimit(speedCaptor.capture());
    assertTrue(speedCaptor.getValue() > 0.0);
    assertTrue(speedCaptor.getValue() * 20.0 < 10.0, "离站 25 格已在刹车曲线上");
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_DISPATCH_RECOVERABLE_HOLD")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("MOVEMENT_AUTHORITY_PLANNED_STOP_ADVISORY")));
  }

  private static ConfigManager.RuntimeSettings runtimeWithSpeedCurve(
      ConfigManager.ConfigView base, boolean speedCurveEnabled) {
    ConfigManager.RuntimeSettings runtime = base.runtimeSettings();
    return new ConfigManager.RuntimeSettings(
        runtime.dispatchTickIntervalTicks(),
        runtime.launchCooldownTicks(),
        runtime.lookaheadEdges(),
        runtime.minClearEdges(),
        runtime.rearGuardEdges(),
        runtime.switcherZoneEdges(),
        runtime.approachSpeedBps(),
        runtime.cautionSpeedBps(),
        runtime.approachDepotSpeedBps(),
        speedCurveEnabled,
        runtime.speedCurveType(),
        runtime.speedCurveFactor(),
        runtime.speedCurveEarlyBrakeBlocks(),
        runtime.failoverStallSpeedBps(),
        runtime.failoverStallTicks(),
        runtime.failoverUnreachableStop(),
        runtime.movementAuthorityEnabled(),
        runtime.movementAuthorityStopMarginBlocks(),
        runtime.movementAuthorityCautionMarginBlocks(),
        runtime.speedCommandHysteresisBps(),
        runtime.speedCommandAccelFactor(),
        runtime.speedCommandDecelFactor(),
        runtime.distanceCacheRefreshSeconds(),
        runtime.hudBossBarEnabled(),
        runtime.hudBossBarTickIntervalTicks(),
        runtime.hudBossBarTemplate(),
        runtime.hudActionBarEnabled(),
        runtime.hudActionBarTickIntervalTicks(),
        runtime.hudActionBarTemplate(),
        runtime.hudPlayerDisplayEnabled(),
        runtime.hudPlayerDisplayTickIntervalTicks(),
        runtime.hudPlayerDisplayTemplate());
  }
}
