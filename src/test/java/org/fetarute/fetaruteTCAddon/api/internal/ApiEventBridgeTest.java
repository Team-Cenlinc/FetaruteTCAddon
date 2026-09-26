package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.event.Event;
import org.fetarute.fetaruteTCAddon.api.event.TimetableTripAssignedEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainArriveStationEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainHealthAlertEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainHoldEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainHoldReleasedEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainReleasedEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainSignalChangeEvent;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.TrainHold;
import org.fetarute.fetaruteTCAddon.dispatcher.health.HealthAlert;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableAssignment;
import org.junit.jupiter.api.Test;

class ApiEventBridgeTest {

  private static final String TRAIN = "SURC-WS-LC-1037";
  private final List<Event> fired = new ArrayList<>();
  private final List<String> logs = new ArrayList<>();
  private final Map<String, TrainHold> holds = new HashMap<>();
  private final Map<String, SignalAspect> signals = new HashMap<>();
  private final Map<String, TimetableAssignment> assignments = new HashMap<>();
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-09-24T10:00:00Z"));

  private ApiEventBridge bridge(boolean listening) {
    return new ApiEventBridge(
        fired::add,
        handlers -> listening,
        () -> List.of(TRAIN),
        name -> Optional.ofNullable(holds.get(name)),
        name -> Optional.ofNullable(signals.get(name)),
        name -> Optional.ofNullable(assignments.get(name)),
        now::get,
        logs::add);
  }

  private static void ticks(ApiEventBridge bridge, int count) {
    for (int i = 0; i < count; i++) {
      bridge.tick();
    }
  }

  private TrainHold hold(String reason, Instant since) {
    return new TrainHold(
        since,
        reason,
        "detail",
        List.of(
            new RuntimeStopState.Blocker(
                "NODE:SURC:S:CHT:3", "SURC-WS-LC-6219", "PROTECTIVE_RETAIN")),
        false);
  }

  @Test
  void stationEventsAreDeferredToNextTick() {
    ApiEventBridge bridge = bridge(true);
    bridge.onStationArrival(
        new StationStopEvent(
            TRAIN, Optional.empty(), "SURC:WS:WS-2C_FullR", 3, 19, "SURC:S:PHI:1", now.get()));
    assertTrue(fired.isEmpty(), "调度路径回调里不能直接调用外部监听器");
    bridge.tick();
    TrainArriveStationEvent event = assertInstanceOf(TrainArriveStationEvent.class, fired.get(0));
    assertEquals("SURC:S:PHI:1", event.getNodeId());
    assertEquals(3, event.getStopIndex());
  }

  @Test
  void nothingIsCreatedWithoutListeners() {
    ApiEventBridge bridge = bridge(false);
    bridge.onTrainReleased(TRAIN, "DSTY");
    holds.put(TRAIN, hold("HARD_BLOCKER_STOP", now.get()));
    ticks(bridge, 3);
    assertTrue(fired.isEmpty());
  }

  @Test
  void holdAndReleaseArePairedAcrossStopStateReplacement() {
    ApiEventBridge bridge = bridge(true);
    Instant heldAt = now.get();
    holds.put(TRAIN, hold("HARD_BLOCKER_STOP", heldAt));
    bridge.tick();
    bridge.tick();
    assertEquals(1, fired.size(), "同一次扣停只发一次");
    TrainHoldEvent hold = assertInstanceOf(TrainHoldEvent.class, fired.get(0));
    assertEquals("HARD_BLOCKER_STOP", hold.getReasonCode());
    assertEquals("SURC-WS-LC-6219", hold.getBlockers().get(0).owner());

    // 运行时停车状态换了原因（enteredAt 会重置），但扣停起点由采样器跨替换保持：
    // 再发一次扣停、不发解除。修复前这个分支永远走不到，发出的是“解除 + 扣停”且时长只算最后一段。
    now.set(heldAt.plusSeconds(10));
    holds.put(TRAIN, hold("AUTHORIZATION_FAILURE", heldAt));
    bridge.tick();
    assertEquals(2, fired.size());
    assertInstanceOf(TrainHoldEvent.class, fired.get(1));

    now.set(heldAt.plusSeconds(42));
    holds.remove(TRAIN);
    bridge.tick();
    TrainHoldReleasedEvent released = assertInstanceOf(TrainHoldReleasedEvent.class, fired.get(2));
    assertEquals(Duration.ofSeconds(42), released.getHeld(), "时长覆盖整次扣停");
    assertEquals("AUTHORIZATION_FAILURE", released.getReasonCode());
  }

  @Test
  void newHoldEpisodeReleasesThePreviousOne() {
    ApiEventBridge bridge = bridge(true);
    Instant first = now.get();
    holds.put(TRAIN, hold("HARD_BLOCKER_STOP", first));
    bridge.tick();
    now.set(first.plusSeconds(30));
    holds.put(TRAIN, hold("HARD_BLOCKER_STOP", first.plusSeconds(25)));
    bridge.tick();
    assertEquals(3, fired.size());
    assertEquals(
        Duration.ofSeconds(30),
        assertInstanceOf(TrainHoldReleasedEvent.class, fired.get(1)).getHeld());
    assertInstanceOf(TrainHoldEvent.class, fired.get(2));
  }

  @Test
  void signalChangeSkipsFirstObservation() {
    ApiEventBridge bridge = bridge(true);
    signals.put(TRAIN, SignalAspect.PROCEED);
    bridge.tick();
    signals.put(TRAIN, SignalAspect.STOP);
    bridge.tick();
    bridge.tick();
    assertEquals(1, fired.size());
    TrainSignalChangeEvent change = assertInstanceOf(TrainSignalChangeEvent.class, fired.get(0));
    assertEquals(TrainApi.Signal.PROCEED, change.getPrevious());
    assertEquals(TrainApi.Signal.STOP, change.getCurrent());
  }

  @Test
  void tripAssignmentFiresOncePerTrip() {
    ApiEventBridge bridge = bridge(true);
    UUID trip = UUID.randomUUID();
    assignments.put(TRAIN, assignment(trip, "R1-001"));
    bridge.tick();
    bridge.tick();
    assignments.put(TRAIN, assignment(UUID.randomUUID(), "R1-002"));
    bridge.tick();
    assertEquals(
        List.of("R1-001", "R1-002"),
        fired.stream().map(e -> ((TimetableTripAssignedEvent) e).getTripCode()).toList());
  }

  @Test
  void failingListenerDoesNotStopOtherEvents() {
    List<Event> delivered = new ArrayList<>();
    ApiEventBridge bridge =
        new ApiEventBridge(
            event -> {
              if (event instanceof TrainReleasedEvent) {
                throw new IllegalStateException("listener bug");
              }
              delivered.add(event);
            },
            handlers -> true,
            List::of,
            null,
            null,
            null,
            now::get,
            logs::add);
    bridge.onTrainReleased(TRAIN, "DSTY");
    bridge.accept(HealthAlert.of(HealthAlert.AlertType.STALL, TRAIN, "stalled"));
    bridge.tick();
    assertInstanceOf(TrainHealthAlertEvent.class, delivered.get(0));
    assertTrue(logs.get(0).contains("API_EVENT_LISTENER_FAILED"));
  }

  @Test
  void deliveryIsBoundedPerTickAndQueueIsBounded() {
    ApiEventBridge bridge = bridge(true);
    for (int i = 0; i < ApiEventBridge.MAX_EVENTS_PER_TICK + 10; i++) {
      bridge.onTrainReleased("T" + i, "x");
    }
    bridge.tick();
    assertEquals(ApiEventBridge.MAX_EVENTS_PER_TICK, fired.size());
    bridge.tick();
    assertEquals(ApiEventBridge.MAX_EVENTS_PER_TICK + 10, fired.size());

    for (int i = 0; i < ApiEventBridge.MAX_QUEUED_EVENTS + 5; i++) {
      bridge.onTrainReleased("T" + i, "x");
    }
    assertEquals(5L, bridge.droppedEvents());
  }

  private static TimetableAssignment assignment(UUID trip, String code) {
    return new TimetableAssignment(
        TRAIN,
        UUID.randomUUID(),
        trip,
        code,
        UUID.randomUUID(),
        Optional.empty(),
        LocalDate.of(2026, 9, 24),
        Instant.parse("2026-09-24T10:00:00Z"),
        0,
        12L);
  }
}
