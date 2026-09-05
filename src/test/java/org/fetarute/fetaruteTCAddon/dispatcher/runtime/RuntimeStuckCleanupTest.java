package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherController;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.RuntimeDispatchRequestProvider;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.RuntimeSignalReevaluationScheduler;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalEvaluator;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** 普通长时间停滞 cleanup 的运行时边界与恢复链集成测试。 */
class RuntimeStuckCleanupTest {

  @Test
  void recentGateQueueEntryReadsFreshQueueMembershipDirectly() {
    OccupancyManager occupancyManager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(OccupancyQueueSupport.class));
    OccupancyQueueSupport queueSupport = (OccupancyQueueSupport) occupancyManager;
    Instant now = Instant.now();
    when(queueSupport.snapshotQueues())
        .thenReturn(
            List.of(
                new OccupancyQueueSnapshot(
                    OccupancyResource.forConflict("single:test:depot-a~depot-b"),
                    Optional.of(CorridorDirection.A_TO_B),
                    1,
                    List.of(
                        new OccupancyQueueEntry(
                            "waiting-train",
                            CorridorDirection.B_TO_A,
                            now.minusSeconds(2),
                            now,
                            20,
                            0)))));
    RuntimeDispatchService service = createService(occupancyManager, new RouteProgressRegistry());

    assertTrue(service.hasRecentGateQueueEntry("WAITING-TRAIN", Duration.ofSeconds(20)));

    when(queueSupport.snapshotQueues()).thenReturn(List.of());
    assertFalse(service.hasRecentGateQueueEntry("waiting-train", Duration.ofSeconds(20)));
  }

  @Test
  void handleTrainRemovedReleaseWakesQueuedFollowerOnNextTick() {
    SignalEventBus eventBus = new SignalEventBus();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy(), eventBus);
    OccupancyResource corridor = OccupancyResource.forConflict("single:test:depot-a~depot-b");
    Instant now = Instant.now();
    assertTrue(
        occupancyManager
            .acquire(
                new OccupancyRequest(
                    "stuck-owner",
                    Optional.empty(),
                    now,
                    List.of(corridor),
                    Map.of(corridor.key(), CorridorDirection.A_TO_B),
                    20))
            .allowed());
    assertFalse(
        occupancyManager
            .acquire(
                new OccupancyRequest(
                    "following-train",
                    Optional.empty(),
                    now,
                    List.of(corridor),
                    Map.of(corridor.key(), CorridorDirection.B_TO_A),
                    20))
            .allowed());
    RuntimeDispatchService service = createService(occupancyManager, new RouteProgressRegistry());
    List<Runnable> nextTickTasks = new ArrayList<>();
    List<String> reevaluated = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        new RuntimeSignalReevaluationScheduler(nextTickTasks::add, reevaluated::add);
    SignalEvaluator bridge =
        new SignalEvaluator(
            eventBus,
            new RuntimeDispatchRequestProvider(occupancyManager),
            scheduler::request,
            message -> {});
    bridge.start();

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      service.handleTrainRemoved("stuck-owner");
    }

    assertTrue(reevaluated.isEmpty());
    assertEquals(1, nextTickTasks.size());
    nextTickTasks.remove(0).run();
    assertEquals(List.of("following-train"), reevaluated);
  }

  @Test
  void stuckCleanupDestroyUsesDedicatedTelemetry() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createService(mock(OccupancyManager.class), new RouteProgressRegistry(), debugMessages);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of());

      assertFalse(service.destroyTrainByName("missing-train", "health-stuck-cleanup-timeout"));
    }

    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("STUCK_CLEANUP_DESTROY_ATTEMPTED")
                        && message.contains("source=HEALTH_MONITOR_STUCK_CLEANUP")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("STUCK_CLEANUP_DESTROY_RESULT")
                        && message.contains("failureReason=RESOLVE_FAILED")));
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("DEADLOCK_DESTROY_ATTEMPTED")));
  }

  @Test
  void stuckCleanupRuntimeReviewFailsClosedWhenTargetCannotBeResolved() {
    RuntimeDispatchService service =
        createService(mock(OccupancyManager.class), new RouteProgressRegistry());

    SmartDispatcherController.StuckCleanupReview review;
    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of());

      review =
          service.reviewStuckCleanupCandidate(
              "missing-train",
              3,
              true,
              Duration.ofMinutes(20),
              Duration.ofMinutes(10),
              Duration.ofMinutes(30));
    }

    assertFalse(review.allowed());
    assertEquals("target-runtime-group-unresolved", review.reason());
  }

  private static RuntimeDispatchService createService(
      OccupancyManager occupancyManager, RouteProgressRegistry progressRegistry) {
    return createService(occupancyManager, progressRegistry, new ArrayList<>());
  }

  private static RuntimeDispatchService createService(
      OccupancyManager occupancyManager,
      RouteProgressRegistry progressRegistry,
      List<String> debugMessages) {
    return new RuntimeDispatchService(
        occupancyManager,
        mock(RailGraphService.class),
        mock(RouteDefinitionCache.class),
        progressRegistry,
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        mock(ConfigManager.class),
        null,
        new TrainConfigResolver(),
        debugMessages::add);
  }
}
