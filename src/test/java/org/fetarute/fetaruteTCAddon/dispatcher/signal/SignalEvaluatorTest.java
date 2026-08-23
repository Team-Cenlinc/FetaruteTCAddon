package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyAcquiredEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyQueueChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyReleasedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 占用事件信号重评估触发器测试。 */
class SignalEvaluatorTest {

  private SignalEventBus eventBus;
  private MockRequestProvider requestProvider;
  private List<String> reevaluationRequests;
  private List<String> failClosedRequests;
  private List<SignalChangedEvent> signalEvents;
  private SignalEvaluator evaluator;

  @BeforeEach
  void setUp() {
    eventBus = new SignalEventBus();
    requestProvider = new MockRequestProvider();
    reevaluationRequests = new ArrayList<>();
    failClosedRequests = new ArrayList<>();
    signalEvents = new ArrayList<>();
    evaluator =
        new SignalEvaluator(
            eventBus,
            requestProvider,
            reevaluationRequests::add,
            (trainName, error) -> failClosedRequests.add(trainName),
            message -> {});
    eventBus.subscribe(SignalChangedEvent.class, signalEvents::add);
  }

  @Test
  void startSubscribesOnlyToEligibilityChangingFacts() {
    evaluator.start();

    assertEquals(0, eventBus.subscriberCount(OccupancyAcquiredEvent.class));
    assertEquals(1, eventBus.subscriberCount(OccupancyReleasedEvent.class));
    assertEquals(1, eventBus.subscriberCount(OccupancyQueueChangedEvent.class));
  }

  @Test
  void stopUnsubscribesFromOccupancyFacts() {
    evaluator.start();
    evaluator.stop();

    assertEquals(0, eventBus.subscriberCount(OccupancyAcquiredEvent.class));
    assertEquals(0, eventBus.subscriberCount(OccupancyReleasedEvent.class));
    assertEquals(0, eventBus.subscriberCount(OccupancyQueueChangedEvent.class));
  }

  @Test
  void acquiredEventDoesNotWakeAnyTrain() {
    evaluator.start();

    eventBus.publish(
        new OccupancyAcquiredEvent(
            Instant.parse("2026-01-01T00:00:00Z"),
            "owner",
            List.of(OccupancyResource.forNode(NodeId.of("node-1"))),
            List.of("train-B", "train-C")));

    assertTrue(reevaluationRequests.isEmpty());
    assertTrue(signalEvents.isEmpty());
  }

  @Test
  void releasedEventResolvesWaitingTrainsAndOnlyRequestsFullReevaluation() {
    evaluator.start();
    OccupancyResource released = OccupancyResource.forNode(NodeId.of("node-2"));
    requestProvider.waitingTrains = List.of("train-C", "train-D");

    eventBus.publish(
        new OccupancyReleasedEvent(
            Instant.parse("2026-01-01T00:00:00Z"), "owner", List.of(released)));

    assertEquals(List.of(released), requestProvider.lastReleasedResources);
    assertEquals(List.of("train-C", "train-D"), reevaluationRequests);
    assertTrue(signalEvents.isEmpty());
  }

  @Test
  void releasedEventNeverReschedulesTheReleasingTrain() {
    evaluator.start();
    OccupancyResource released = OccupancyResource.forNode(NodeId.of("jbs-crossing"));
    requestProvider.waitingTrains = List.of("JBS-DS", "JBS-MT");

    eventBus.publish(
        new OccupancyReleasedEvent(
            Instant.parse("2026-01-01T00:00:00Z"), "JBS-DS", List.of(released)));

    assertEquals(List.of("JBS-MT"), reevaluationRequests);
  }

  @Test
  void queueHeadEligibilityChangeWakesOnlyRecordedNewHead() {
    evaluator.start();
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:queue-wakeup");

    eventBus.publish(
        new OccupancyQueueChangedEvent(
            Instant.parse("2026-01-01T00:00:00Z"),
            "yield-train",
            List.of(conflict),
            List.of("winner-train")));

    assertTrue(requestProvider.lastReleasedResources.isEmpty());
    assertEquals(List.of("winner-train"), reevaluationRequests);
  }

  @Test
  void ordinaryQueueMaintenanceDoesNotWakeAnyTrain() {
    evaluator.start();
    requestProvider.waitingTrains = List.of("unrelated-train");

    eventBus.publish(
        new OccupancyQueueChangedEvent(
            Instant.parse("2026-01-01T00:00:00Z"),
            "refreshing-train",
            List.of(OccupancyResource.forConflict("switcher:queue-maintenance"))));

    assertTrue(requestProvider.lastReleasedResources.isEmpty());
    assertTrue(reevaluationRequests.isEmpty());
  }

  @Test
  void queueEligibilityChangeNeverReschedulesItsSource() {
    evaluator.start();
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:self-source");

    eventBus.publish(
        new OccupancyQueueChangedEvent(
            Instant.parse("2026-01-01T00:00:00Z"),
            "source-train",
            List.of(conflict),
            List.of("source-train", "new-winner")));

    assertEquals(List.of("new-winner"), reevaluationRequests);
  }

  @Test
  void releasedJbsQueueWinnerReachesFullReevaluationOnlyOnNextTick() {
    List<Runnable> nextTickTasks = new ArrayList<>();
    List<String> fullyReevaluated = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        new RuntimeSignalReevaluationScheduler(nextTickTasks::add, fullyReevaluated::add);
    SignalEvaluator bridge =
        new SignalEvaluator(eventBus, requestProvider, scheduler::request, message -> {});
    requestProvider.waitingTrains = List.of("JBS-DS", "JBS-MT");
    bridge.start();

    eventBus.publish(
        new OccupancyReleasedEvent(
            Instant.parse("2026-01-01T00:00:00Z"),
            "JBS-DS",
            List.of(OccupancyResource.forNode(NodeId.of("jbs-crossing")))));

    assertTrue(fullyReevaluated.isEmpty());
    assertEquals(1, nextTickTasks.size());
    nextTickTasks.remove(0).run();
    assertEquals(List.of("JBS-MT"), fullyReevaluated);
  }

  @Test
  void releasedEventQueryFailureCannotEscapeTheSynchronousEventBus() {
    evaluator.start();
    requestProvider.waitingQueryFailure = new LinkageError("provider unavailable");

    assertDoesNotThrow(
        () ->
            eventBus.publish(
                new OccupancyReleasedEvent(
                    Instant.parse("2026-01-01T00:00:00Z"),
                    "JBS-DS",
                    List.of(OccupancyResource.forNode(NodeId.of("jbs-crossing"))))));
    assertTrue(reevaluationRequests.isEmpty());
    assertEquals(List.of("JBS-DS"), failClosedRequests);
  }

  @Test
  void invalidHintsAreIgnored() {
    evaluator.start();
    requestProvider.waitingTrains = List.of("", "   ", "train-E");

    eventBus.publish(
        new OccupancyAcquiredEvent(
            Instant.now(), "owner", List.of(), List.of("", "train-A", "   ")));
    eventBus.publish(
        new OccupancyReleasedEvent(
            Instant.now(), "owner", List.of(OccupancyResource.forNode(NodeId.of("node-3")))));

    assertEquals(List.of("train-E"), reevaluationRequests);
  }

  @Test
  void signalEvaluatorDoesNotOwnAuthorityOrTrainControlDependencies() {
    Set<String> forbiddenDependencies =
        Set.of(
            "OccupancyManager",
            "OccupancyRequest",
            "TrainProperties",
            "RuntimeTrainController",
            "TrainLaunchManager",
            "TrainCarts");

    for (Field field : SignalEvaluator.class.getDeclaredFields()) {
      String fieldType = field.getType().getSimpleName();
      for (String dependency : forbiddenDependencies) {
        assertFalse(
            fieldType.contains(dependency),
            "SignalEvaluator must only translate facts into wake-ups: " + fieldType);
      }
    }
  }

  private static final class MockRequestProvider implements SignalEvaluator.WaitingTrainProvider {

    private List<String> waitingTrains = List.of();
    private List<OccupancyResource> lastReleasedResources = List.of();
    private LinkageError waitingQueryFailure;

    @Override
    public List<String> trainsWaitingFor(List<OccupancyResource> resources) {
      if (waitingQueryFailure != null) {
        throw waitingQueryFailure;
      }
      lastReleasedResources = List.copyOf(resources);
      return waitingTrains;
    }
  }
}
