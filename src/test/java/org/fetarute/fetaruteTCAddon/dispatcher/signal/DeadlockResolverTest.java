package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.DeadlockDetectedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.DeadlockResolvedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyAcquiredEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DeadlockResolver 单元测试。 */
class DeadlockResolverTest {

  private SignalEventBus eventBus;
  private DeadlockResolver resolver;

  @BeforeEach
  void setUp() {
    eventBus = new SignalEventBus();
    resolver = new DeadlockResolver(eventBus);
  }

  @Test
  void startSubscribesToAcquiredEvent() {
    resolver.start();
    assertEquals(1, eventBus.subscriberCount(OccupancyAcquiredEvent.class));
  }

  @Test
  void stopUnsubscribes() {
    resolver.start();
    resolver.stop();
    assertEquals(0, eventBus.subscriberCount(OccupancyAcquiredEvent.class));
  }

  @Test
  void grantLockSucceeds() {
    Instant now = Instant.now();
    assertTrue(resolver.grantLock("train-A", "conflict-1", now));
    assertTrue(resolver.holdsLock("train-A", "conflict-1"));
  }

  @Test
  void grantLockBlockedByOtherTrain() {
    Instant now = Instant.now();
    assertTrue(resolver.grantLock("train-A", "conflict-1", now));
    assertFalse(resolver.grantLock("train-B", "conflict-1", now));
    assertTrue(resolver.holdsLock("train-A", "conflict-1"));
    assertFalse(resolver.holdsLock("train-B", "conflict-1"));
  }

  @Test
  void sameTrainCanRenewLock() {
    Instant now = Instant.now();
    assertTrue(resolver.grantLock("train-A", "conflict-1", now));
    assertTrue(resolver.grantLock("train-A", "conflict-1", now.plusSeconds(1)));
    assertTrue(resolver.holdsLock("train-A", "conflict-1"));
  }

  @Test
  void lockExpiresAfterTtl() {
    Instant now = Instant.now();
    assertTrue(resolver.grantLock("train-A", "conflict-1", now));

    // 锁在创建时有效
    assertTrue(resolver.holdsLock("train-A", "conflict-1"));

    // 锁应该在 8 秒后过期，清理后其他列车可以获取
    Instant afterExpiry = now.plusSeconds(10);
    resolver.purgeExpiredLocks(afterExpiry);

    // 清理后锁已被移除
    assertFalse(resolver.holdsLock("train-A", "conflict-1"));

    // 其他列车现在可以获取锁
    assertTrue(resolver.grantLock("train-B", "conflict-1", afterExpiry));
  }

  @Test
  void releaseLocksRemovesTrainLocks() {
    Instant now = Instant.now();
    resolver.grantLock("train-A", "conflict-1", now);
    resolver.grantLock("train-A", "conflict-2", now);
    assertEquals(2, resolver.lockCount());

    resolver.releaseLocks("train-A");
    assertEquals(0, resolver.lockCount());
  }

  @Test
  void publishDetectedCreatesEvent() {
    resolver.start();
    List<DeadlockDetectedEvent> events = new ArrayList<>();
    eventBus.subscribe(DeadlockDetectedEvent.class, events::add);

    Instant now = Instant.now();
    OccupancyResource resource = OccupancyResource.forConflict("single:test");
    resolver.publishDetected(resource, List.of("train-A", "train-B"), "Test deadlock", now);

    assertEquals(1, events.size());
    DeadlockDetectedEvent event = events.get(0);
    assertEquals(List.of("train-A", "train-B"), event.involvedTrains());
    assertEquals("single:test", event.conflictResource().key());
  }

  @Test
  void publishResolvedCreatesSignalChangedEvent() {
    resolver.start();
    List<DeadlockResolvedEvent> resolvedEvents = new ArrayList<>();
    List<SignalChangedEvent> signalEvents = new ArrayList<>();
    eventBus.subscribe(DeadlockResolvedEvent.class, resolvedEvents::add);
    eventBus.subscribe(SignalChangedEvent.class, signalEvents::add);

    Instant now = Instant.now();
    OccupancyResource resource = OccupancyResource.forConflict("single:test");
    resolver.publishResolved(resource, "train-A", Duration.ofSeconds(8), now);

    assertEquals(1, resolvedEvents.size());
    assertEquals("train-A", resolvedEvents.get(0).releasedTrain());

    assertEquals(1, signalEvents.size());
    assertEquals("train-A", signalEvents.get(0).trainName());
    assertTrue(signalEvents.get(0).isUnblocked());
  }
}
