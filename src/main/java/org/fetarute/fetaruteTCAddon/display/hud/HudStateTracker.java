package org.fetarute.fetaruteTCAddon.display.hud;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HUD 状态跟踪器：根据运行时快照判定 IDLE/AT_STATION/IN_TRIP 等状态。
 *
 * <p>通过“移动 → DEPARTING 窗口”实现离站短暂提示，窗口期内优先返回 DEPARTING。
 */
public final class HudStateTracker {

  private final long departingWindowMillis;
  private final Map<String, MotionState> states = new ConcurrentHashMap<>();

  public HudStateTracker(long departingWindowMillis) {
    this.departingWindowMillis = Math.max(0L, departingWindowMillis);
  }

  /**
   * 解析列车 HUD 状态。
   *
   * @param trainName 列车名（用于缓存运动状态）
   * @param moving 是否移动中
   * @param arriving 是否即将到站（ETA）
   * @param layover 是否处于待命
   * @param stop 是否停站（dwellRemainingSec > 0）
   * @param terminalArriving 是否终点到站
   * @param nowMillis 当前时间戳（毫秒）
   * @return HUD 状态
   */
  public HudState resolve(
      String trainName,
      boolean moving,
      boolean arriving,
      boolean layover,
      boolean stop,
      boolean terminalArriving,
      long nowMillis) {
    if (layover) {
      updateMotion(trainName, moving, nowMillis);
      return HudState.ON_LAYOVER;
    }
    if (stop) {
      updateMotion(trainName, moving, nowMillis);
      return HudState.AT_STATION;
    }
    if (terminalArriving) {
      updateMotion(trainName, moving, nowMillis);
      return HudState.TERM_ARRIVING;
    }
    if (arriving) {
      updateMotion(trainName, moving, nowMillis);
      return HudState.ARRIVING;
    }
    if (!moving) {
      updateMotion(trainName, false, nowMillis);
      return HudState.IDLE;
    }
    MotionState state = updateMotion(trainName, true, nowMillis);
    if (state != null && nowMillis <= state.departUntilMillis()) {
      return HudState.DEPARTING;
    }
    return HudState.IN_TRIP;
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
