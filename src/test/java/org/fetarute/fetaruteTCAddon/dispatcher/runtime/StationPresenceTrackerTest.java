package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StationPresenceTrackerTest {

  private static final String ROUTE = "SURC:DS:DS-1F_Full";

  private static StationStopEvent event(String train, String route, int index) {
    return new StationStopEvent(
        train, Optional.empty(), route, index, 18, "SURC:S:HHU:1", Instant.now());
  }

  @Test
  void presentFromArrivalUntilDeparture() {
    StationPresenceTracker tracker = new StationPresenceTracker();
    // 实服 13:33:00 进度推进（到站事件）→ 13:33:03 才开始停站计时：这 3 秒里 HUD 必须仍认为在站。
    tracker.onStationArrival(event("SURC-DS-LW-7264", ROUTE, 1));
    assertTrue(tracker.isAtStation("surc-ds-lw-7264", ROUTE, 1), "列车名大小写不敏感");

    tracker.onStationDeparture(event("SURC-DS-LW-7264", ROUTE, 1));
    assertFalse(tracker.isAtStation("SURC-DS-LW-7264", ROUTE, 1));
  }

  @Test
  void staleRecordExpiresWhenIndexOrRouteChanges() {
    StationPresenceTracker tracker = new StationPresenceTracker();
    tracker.onStationArrival(event("T1", ROUTE, 5));
    // 漏了发车事件也不能一直挂着：进度推进到下一站即失效。
    assertFalse(tracker.isAtStation("T1", ROUTE, 6));
    // 折返复用换交路不经过 AutoStation 发车；新交路的索引恰好相同也不能算在站。
    assertFalse(tracker.isAtStation("T1", "SURC:DS:DS-1W_FullD", 5));
    assertTrue(tracker.isAtStation("T1", ROUTE, 5));
  }

  @Test
  void releaseAndRetainClearRecords() {
    StationPresenceTracker tracker = new StationPresenceTracker();
    tracker.onStationArrival(event("T1", ROUTE, 1));
    tracker.onStationArrival(event("T2", ROUTE, 1));
    tracker.onTrainReleased("T1", "destroyed");
    assertFalse(tracker.isAtStation("T1", ROUTE, 1));
    tracker.retain(Set.of("t3"));
    assertFalse(tracker.isAtStation("T2", ROUTE, 1));
  }

  @Test
  void hubIsolatesFailingObserversAndReplacesByName() {
    List<String> log = new ArrayList<>();
    StationStopObserverHub hub = new StationStopObserverHub(log::add);
    List<String> seen = new ArrayList<>();
    hub.register(
        "broken",
        new StationStopObserver() {
          @Override
          public void onStationArrival(StationStopEvent event) {
            throw new IllegalStateException("boom");
          }

          @Override
          public void onStationDeparture(StationStopEvent event) {}
        });
    hub.register(
        "ok",
        new StationStopObserver() {
          @Override
          public void onStationArrival(StationStopEvent event) {
            seen.add("first");
          }

          @Override
          public void onStationDeparture(StationStopEvent event) {}
        });
    hub.register(
        "ok",
        new StationStopObserver() {
          @Override
          public void onStationArrival(StationStopEvent event) {
            seen.add("second");
          }

          @Override
          public void onStationDeparture(StationStopEvent event) {}
        });
    hub.onStationArrival(event("T1", ROUTE, 1));
    assertEquals(List.of("second"), seen, "同名注册应替换，reload 不能越挂越多");
    assertEquals(1, log.size());
    assertTrue(log.get(0).contains("observer=broken"));
  }
}
