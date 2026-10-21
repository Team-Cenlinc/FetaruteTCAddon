package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherController;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartWaitForPlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 规划编排接进 {@link RuntimeDispatchService} 之后的接线：空图不走规划器，列车状态只为等待边上的车采集，后台执行器把结论推迟一轮。 */
class SmartPlannerWiringTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final OccupancyResource CONTESTED =
      OccupancyResource.forConflict("single:wiring-test");

  @Test
  void noBlockedTrainNeverReachesThePlanner() throws Exception {
    List<String> debug = new ArrayList<>();
    RuntimeDispatchService service = service(debug);
    SmartDispatcherController controller = replaceController(service);

    service.traceSmartDispatchGlobalSnapshot(Set.of("X-1", "X-2"), NOW);
    service.traceSmartDispatchGlobalSnapshot(Set.of("X-1", "X-2"), NOW.plusSeconds(3));

    verify(controller, never()).planMinimalForwardUnlock(any());
    verify(controller, times(1))
        .traceMinimalForwardPlan(SmartWaitForPlanner.emptyGraphResult(true));
  }

  @Test
  void plannerInputCarriesStatesOnlyForTrainsOnTheWaitGraph() throws Exception {
    List<String> debug = new ArrayList<>();
    RuntimeDispatchService service = service(debug);
    SmartDispatcherController controller = replaceController(service);
    service.updateBlockerSnapshot("W-BLOCKED", List.of(claim("W-BLOCKER")), null, NOW, "test");

    service.traceSmartDispatchGlobalSnapshot(
        new LinkedHashSet<>(List.of("X-1", "W-BLOCKED", "X-2", "W-BLOCKER")), NOW);

    ArgumentCaptor<SmartWaitForPlanner.PlannerInput> input =
        ArgumentCaptor.forClass(SmartWaitForPlanner.PlannerInput.class);
    verify(controller).planMinimalForwardUnlock(input.capture());
    assertEquals(1, input.getValue().edges().size());
    assertEquals(Set.of("W-BLOCKED", "W-BLOCKER"), input.getValue().trainStates().keySet());
    assertTrue(input.getValue().collectTraces());
  }

  @Test
  void backgroundExecutorDefersTheConclusionByOneRound() throws Exception {
    List<String> debug = new ArrayList<>();
    RuntimeDispatchService service = service(debug);
    SmartDispatcherController controller = replaceController(service);
    service.updateBlockerSnapshot("W-BLOCKED", List.of(claim("W-BLOCKER")), null, NOW, "test");
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      service.smartPlanner().useExecutor(worker);
      service.traceSmartDispatchGlobalSnapshot(Set.of("W-BLOCKED", "W-BLOCKER"), NOW);
      verify(controller, never()).traceMinimalForwardPlan(any());
      assertTrue(service.smartPlanner().hasPendingPlan());

      worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
      service.traceSmartDispatchGlobalSnapshot(
          Set.of("W-BLOCKED", "W-BLOCKER"), NOW.plusSeconds(3));
      verify(controller, times(1)).traceMinimalForwardPlan(any());
      // 第二轮回收第一份结果之后，按本轮现场又提交了一份；它在后台线程上跑，先等它跑完再数。
      worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
      verify(controller, times(2)).planMinimalForwardUnlock(any());
      assertTrue(service.smartPlanner().hasPendingPlan());
    } finally {
      service.smartPlanner().shutdown();
      worker.shutdownNow();
    }
  }

  private static RuntimeDispatchService service(List<String> debug) {
    return TestServices.minimal(
        debug,
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
        SmartDispatcherMode.ENFORCE,
        () -> NOW);
  }

  private static SmartDispatcherController replaceController(RuntimeDispatchService service)
      throws ReflectiveOperationException {
    SmartDispatcherController controller = mock(SmartDispatcherController.class);
    SmartWaitForPlanner planner = new SmartWaitForPlanner();
    when(controller.planMinimalForwardUnlock(any()))
        .thenAnswer(invocation -> planner.plan(invocation.getArgument(0)));
    java.lang.reflect.Field field =
        RuntimeDispatchService.class.getDeclaredField("smartDispatcherController");
    field.setAccessible(true);
    field.set(service, controller);
    return controller;
  }

  private static OccupancyClaim claim(String train) {
    return new OccupancyClaim(
        CONTESTED,
        train,
        Optional.empty(),
        NOW,
        Duration.ZERO,
        Optional.empty(),
        ClaimRole.MOVEMENT_REQUIRED);
  }
}
