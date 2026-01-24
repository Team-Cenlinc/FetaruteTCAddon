package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BossBarHudStateTracker {

  private final long departingWindowMillis;
  private final Map<String, MotionState> states = new ConcurrentHashMap<>();

  public BossBarHudStateTracker(long departingWindowMillis) {
    this.departingWindowMillis = Math.max(0L, departingWindowMillis);
  }

  public BossBarHudState resolve(
      String trainName,
      boolean moving,
      boolean arriving,
      boolean layover,
      boolean stop,
      long nowMillis) {
    if (layover) {
      updateMotion(trainName, moving, nowMillis);
      return BossBarHudState.ON_LAYOVER;
    }
    if (stop) {
      updateMotion(trainName, moving, nowMillis);
      return BossBarHudState.AT_STATION;
    }
    if (arriving) {
      updateMotion(trainName, moving, nowMillis);
      return BossBarHudState.ARRIVING;
    }
    if (!moving) {
      updateMotion(trainName, false, nowMillis);
      return BossBarHudState.IDLE;
    }
    MotionState state = updateMotion(trainName, true, nowMillis);
    if (state != null && nowMillis <= state.departUntilMillis()) {
      return BossBarHudState.DEPARTING;
    }
    return BossBarHudState.IN_TRIP;
  }

  public void retain(Set<String> activeTrainNames) {
    if (activeTrainNames == null || activeTrainNames.isEmpty()) {
      states.clear();
      return;
    }
    states.keySet().retainAll(activeTrainNames);
  }

  public void clear() {
    states.clear();
  }

  private MotionState updateMotion(String trainName, boolean moving, long nowMillis) {
    if (trainName == null || trainName.isBlank()) {
      return null;
    }
    return states.compute(
        trainName,
        (key, existing) -> {
          MotionState current = existing == null ? MotionState.initial() : existing;
          if (current.moving() == moving) {
            return current;
          }
          if (moving) {
            long until = nowMillis + departingWindowMillis;
            return new MotionState(true, until);
          }
          return new MotionState(false, 0L);
        });
  }

  private record MotionState(boolean moving, long departUntilMillis) {
    private static MotionState initial() {
      return new MotionState(false, 0L);
    }
  }
}
