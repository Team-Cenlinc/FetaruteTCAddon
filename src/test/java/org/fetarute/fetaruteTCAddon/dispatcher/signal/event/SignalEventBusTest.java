package org.fetarute.fetaruteTCAddon.dispatcher.signal.event;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SignalEventBus 测试")
class SignalEventBusTest {

  private SignalEventBus bus;
  private List<String> debugLogs;

  @BeforeEach
  void setUp() {
    debugLogs = new ArrayList<>();
    bus = new SignalEventBus(debugLogs::add);
  }

  @Nested
  @DisplayName("订阅与发布")
  class SubscribeAndPublish {

    @Test
    @DisplayName("订阅特定类型事件")
    void subscribeSpecificType() {
      List<SignalChangedEvent> received = new ArrayList<>();
      bus.subscribe(SignalChangedEvent.class, received::add);

      SignalChangedEvent event =
          new SignalChangedEvent(Instant.now(), "train-1", SignalAspect.STOP, SignalAspect.PROCEED);
      bus.publish(event);

      assertEquals(1, received.size());
      assertEquals("train-1", received.get(0).trainName());
      assertEquals(SignalAspect.PROCEED, received.get(0).newSignal());
    }

    @Test
    @DisplayName("不同类型事件不触发订阅")
    void differentTypeNotTriggered() {
      List<SignalChangedEvent> signalEvents = new ArrayList<>();
      bus.subscribe(SignalChangedEvent.class, signalEvents::add);

      OccupancyAcquiredEvent event =
          new OccupancyAcquiredEvent(Instant.now(), "train-1", List.of(), List.of());
      bus.publish(event);

      assertTrue(signalEvents.isEmpty());
    }

    @Test
    @DisplayName("全局订阅接收所有事件")
    void globalSubscription() {
      List<SignalEvent> allEvents = new ArrayList<>();
      bus.subscribeAll(allEvents::add);

      bus.publish(
          new SignalChangedEvent(
              Instant.now(), "train-1", SignalAspect.STOP, SignalAspect.PROCEED));
      bus.publish(new OccupancyAcquiredEvent(Instant.now(), "train-2", List.of(), List.of()));

      assertEquals(2, allEvents.size());
    }

    @Test
    @DisplayName("多个订阅者按顺序接收")
    void multipleSubscribersInOrder() {
      List<Integer> order = new ArrayList<>();
      bus.subscribe(SignalChangedEvent.class, e -> order.add(1));
      bus.subscribe(SignalChangedEvent.class, e -> order.add(2));
      bus.subscribe(SignalChangedEvent.class, e -> order.add(3));

      bus.publish(
          new SignalChangedEvent(
              Instant.now(), "train-1", SignalAspect.STOP, SignalAspect.PROCEED));

      assertEquals(List.of(1, 2, 3), order);
    }
  }

  @Nested
  @DisplayName("取消订阅")
  class Unsubscribe {

    @Test
    @DisplayName("取消订阅后不再接收事件")
    void unsubscribeStopsReceiving() {
      AtomicInteger count = new AtomicInteger(0);
      SignalEventBus.Subscription sub =
          bus.subscribe(SignalChangedEvent.class, e -> count.incrementAndGet());

      bus.publish(
          new SignalChangedEvent(
              Instant.now(), "train-1", SignalAspect.STOP, SignalAspect.PROCEED));
      assertEquals(1, count.get());

      sub.unsubscribe();

      bus.publish(
          new SignalChangedEvent(
              Instant.now(), "train-2", SignalAspect.STOP, SignalAspect.PROCEED));
      assertEquals(1, count.get()); // 仍然是 1
    }

    @Test
    @DisplayName("取消全局订阅")
    void unsubscribeGlobal() {
      AtomicInteger count = new AtomicInteger(0);
      SignalEventBus.Subscription sub = bus.subscribeAll(e -> count.incrementAndGet());

      bus.publish(
          new SignalChangedEvent(
              Instant.now(), "train-1", SignalAspect.STOP, SignalAspect.PROCEED));
      assertEquals(1, count.get());

      sub.unsubscribe();

      bus.publish(
          new SignalChangedEvent(
              Instant.now(), "train-2", SignalAspect.STOP, SignalAspect.PROCEED));
      assertEquals(1, count.get());
    }
  }

  @Nested
  @DisplayName("异常处理")
  class ErrorHandling {

    @Test
    @DisplayName("订阅者异常不影响其他订阅者")
    void subscriberExceptionIsolated() {
      List<String> received = new ArrayList<>();

      bus.subscribe(
          SignalChangedEvent.class,
          e -> {
            throw new RuntimeException("模拟异常");
          });
      bus.subscribe(SignalChangedEvent.class, e -> received.add(e.trainName()));

      bus.publish(
          new SignalChangedEvent(
              Instant.now(), "train-1", SignalAspect.STOP, SignalAspect.PROCEED));

      assertEquals(1, received.size());
      assertEquals("train-1", received.get(0));
      assertTrue(debugLogs.stream().anyMatch(log -> log.contains("subscriber error")));
    }

    @Test
    @DisplayName("发布 null 事件不抛异常")
    void publishNullSafe() {
      assertDoesNotThrow(() -> bus.publish(null));
    }
  }

  @Nested
  @DisplayName("诊断功能")
  class Diagnostics {

    @Test
    @DisplayName("订阅者计数")
    void subscriberCount() {
      assertEquals(0, bus.subscriberCount(SignalChangedEvent.class));
      assertEquals(0, bus.globalSubscriberCount());

      bus.subscribe(SignalChangedEvent.class, e -> {});
      bus.subscribe(SignalChangedEvent.class, e -> {});
      bus.subscribeAll(e -> {});

      assertEquals(2, bus.subscriberCount(SignalChangedEvent.class));
      assertEquals(1, bus.globalSubscriberCount());
      assertEquals(3, bus.totalSubscriberCount());
    }

    @Test
    @DisplayName("清除所有订阅")
    void clearAll() {
      bus.subscribe(SignalChangedEvent.class, e -> {});
      bus.subscribeAll(e -> {});

      bus.clear();

      assertEquals(0, bus.totalSubscriberCount());
    }
  }

  @Nested
  @DisplayName("事件类型测试")
  class EventTypes {

    @Test
    @DisplayName("SignalChangedEvent 状态判断")
    void signalChangedEventStates() {
      SignalChangedEvent unblocked =
          new SignalChangedEvent(Instant.now(), "train", SignalAspect.STOP, SignalAspect.PROCEED);
      assertTrue(unblocked.isUnblocked());
      assertFalse(unblocked.isBlocked());

      SignalChangedEvent blocked =
          new SignalChangedEvent(Instant.now(), "train", SignalAspect.PROCEED, SignalAspect.STOP);
      assertFalse(blocked.isUnblocked());
      assertTrue(blocked.isBlocked());
    }

    @Test
    @DisplayName("DeadlockDetectedEvent 创建")
    void deadlockDetectedEvent() {
      OccupancyResource conflict = OccupancyResource.forConflict("test-conflict");
      DeadlockDetectedEvent event =
          new DeadlockDetectedEvent(
              Instant.now(), List.of("train-A", "train-B"), conflict, "A→B→A 循环依赖");

      assertEquals("DEADLOCK_DETECTED", event.eventType());
      assertEquals(2, event.involvedTrains().size());
      assertEquals(conflict, event.conflictResource());
    }

    @Test
    @DisplayName("DeadlockResolvedEvent 创建")
    void deadlockResolvedEvent() {
      OccupancyResource conflict = OccupancyResource.forConflict("test-conflict");
      DeadlockResolvedEvent event =
          new DeadlockResolvedEvent(Instant.now(), "train-A", conflict, Duration.ofSeconds(8));

      assertEquals("DEADLOCK_RESOLVED", event.eventType());
      assertEquals("train-A", event.releasedTrain());
      assertEquals(Duration.ofSeconds(8), event.lockDuration());
    }
  }
}
