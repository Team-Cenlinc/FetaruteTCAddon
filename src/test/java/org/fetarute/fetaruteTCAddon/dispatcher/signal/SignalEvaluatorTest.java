package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyAcquiredEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyReleasedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SignalEvaluator 单元测试。 */
class SignalEvaluatorTest {

  private SignalEventBus eventBus;
  private MockOccupancyManager mockOccupancy;
  private MockRequestProvider mockProvider;
  private SignalEvaluator evaluator;
  private List<SignalChangedEvent> receivedEvents;

  @BeforeEach
  void setUp() {
    eventBus = new SignalEventBus();
    mockOccupancy = new MockOccupancyManager();
    mockProvider = new MockRequestProvider();
    evaluator = new SignalEvaluator(eventBus, mockOccupancy, mockProvider);
    receivedEvents = new ArrayList<>();
    eventBus.subscribe(SignalChangedEvent.class, receivedEvents::add);
  }

  @Test
  void startSubscribesToEvents() {
    evaluator.start();
    assertEquals(1, eventBus.subscriberCount(OccupancyAcquiredEvent.class));
    assertEquals(1, eventBus.subscriberCount(OccupancyReleasedEvent.class));
  }

  @Test
  void stopUnsubscribes() {
    evaluator.start();
    evaluator.stop();
    assertEquals(0, eventBus.subscriberCount(OccupancyAcquiredEvent.class));
    assertEquals(0, eventBus.subscriberCount(OccupancyReleasedEvent.class));
  }

  @Test
  void acquiredEventTriggersReevaluation() {
    evaluator.start();
    Instant now = Instant.now();

    // 配置 mock：列车 B 等待资源，信号为 PROCEED
    mockProvider.registerTrain("train-B", SignalAspect.PROCEED);
    mockOccupancy.setDecision("train-B", true, SignalAspect.PROCEED);

    // 发布 acquired 事件，受影响列车包含 train-B
    OccupancyResource resource = OccupancyResource.forNode(new NodeId("node-1"));
    OccupancyAcquiredEvent event =
        new OccupancyAcquiredEvent(now, "train-A", List.of(resource), List.of("train-B"));
    eventBus.publish(event);

    // train-B 应该被重新评估并发布信号变化事件
    assertEquals(1, receivedEvents.size());
    SignalChangedEvent change = receivedEvents.get(0);
    assertEquals("train-B", change.trainName());
    assertEquals(SignalAspect.PROCEED, change.newSignal());
  }

  @Test
  void releasedEventTriggersReevaluation() {
    evaluator.start();
    Instant now = Instant.now();

    // 配置 mock：train-C 等待资源释放
    OccupancyResource resource = OccupancyResource.forNode(new NodeId("node-2"));
    mockProvider.registerTrain("train-C", SignalAspect.PROCEED);
    mockProvider.registerWaiting(resource, "train-C");
    mockOccupancy.setDecision("train-C", true, SignalAspect.PROCEED);

    // 发布释放事件
    OccupancyReleasedEvent event = new OccupancyReleasedEvent(now, "train-A", List.of(resource));
    eventBus.publish(event);

    // train-C 应该被重新评估
    assertEquals(1, receivedEvents.size());
    assertEquals("train-C", receivedEvents.get(0).trainName());
  }

  @Test
  void noEventIfSignalUnchanged() {
    evaluator.start();
    Instant now = Instant.now();

    // 预设 train-D 的信号为 STOP
    mockProvider.registerTrain("train-D", SignalAspect.STOP);
    mockOccupancy.setDecision("train-D", false, SignalAspect.STOP);
    evaluator.updateCache("train-D", SignalAspect.STOP);

    // 发布 acquired 事件
    OccupancyAcquiredEvent event =
        new OccupancyAcquiredEvent(
            now,
            "train-A",
            List.of(OccupancyResource.forNode(new NodeId("n1"))),
            List.of("train-D"));
    eventBus.publish(event);

    // 信号未变化，不应发布事件
    assertTrue(receivedEvents.isEmpty());
  }

  @Test
  void signalChangeFromStopToProceed() {
    evaluator.start();
    Instant now = Instant.now();

    // 预设 train-E 的信号为 STOP
    mockProvider.registerTrain("train-E", SignalAspect.PROCEED);
    mockOccupancy.setDecision("train-E", true, SignalAspect.PROCEED);
    evaluator.updateCache("train-E", SignalAspect.STOP);

    // 发布 acquired 事件
    OccupancyAcquiredEvent event =
        new OccupancyAcquiredEvent(
            now,
            "train-X",
            List.of(OccupancyResource.forNode(new NodeId("n1"))),
            List.of("train-E"));
    eventBus.publish(event);

    // 信号从 STOP 变为 PROCEED
    assertEquals(1, receivedEvents.size());
    SignalChangedEvent change = receivedEvents.get(0);
    assertEquals(SignalAspect.STOP, change.previousSignal());
    assertEquals(SignalAspect.PROCEED, change.newSignal());
    assertTrue(change.isUnblocked());
    assertFalse(change.isBlocked());
  }

  // ========== Mock 实现 ==========

  private static class MockOccupancyManager implements OccupancyManager {
    private final java.util.Map<String, OccupancyDecision> decisions =
        new java.util.concurrent.ConcurrentHashMap<>();

    void setDecision(String trainName, boolean allowed, SignalAspect signal) {
      decisions.put(
          trainName.toLowerCase(),
          new OccupancyDecision(allowed, Instant.now(), signal, List.of()));
    }

    @Override
    public OccupancyDecision canEnter(OccupancyRequest request) {
      return decisions.getOrDefault(
          request.trainName().toLowerCase(),
          new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of()));
    }

    @Override
    public OccupancyDecision acquire(OccupancyRequest request) {
      return canEnter(request);
    }

    @Override
    public Optional<OccupancyClaim> getClaim(OccupancyResource resource) {
      return Optional.empty();
    }

    @Override
    public List<OccupancyClaim> snapshotClaims() {
      return List.of();
    }

    @Override
    public int releaseByTrain(String trainName) {
      return 0;
    }

    @Override
    public boolean releaseResource(OccupancyResource resource, Optional<String> trainName) {
      return false;
    }

    @Override
    public boolean shouldYield(OccupancyRequest request) {
      return false;
    }
  }

  private static class MockRequestProvider implements SignalEvaluator.TrainRequestProvider {
    private final java.util.Map<String, SignalAspect> trains =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, List<String>> waitingMap =
        new java.util.concurrent.ConcurrentHashMap<>();

    void registerTrain(String name, SignalAspect signal) {
      trains.put(name.toLowerCase(), signal);
    }

    void registerWaiting(OccupancyResource resource, String trainName) {
      waitingMap.computeIfAbsent(resource.key(), k -> new ArrayList<>()).add(trainName);
    }

    @Override
    public Optional<OccupancyRequest> buildRequest(String trainName, Instant now) {
      if (!trains.containsKey(trainName.toLowerCase())) {
        return Optional.empty();
      }
      return Optional.of(
          new OccupancyRequest(trainName, Optional.empty(), now, List.of(), Map.of(), 0));
    }

    @Override
    public List<String> trainsWaitingFor(List<OccupancyResource> resources) {
      List<String> result = new ArrayList<>();
      for (OccupancyResource res : resources) {
        List<String> waiting = waitingMap.get(res.key());
        if (waiting != null) {
          result.addAll(waiting);
        }
      }
      return result;
    }
  }
}
