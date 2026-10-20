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
   * @param atLastStation 是否停在终点站（EOP）
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
      boolean atLastStation,
      boolean terminalArriving,
      long nowMillis) {
    if (layover) {
      updateMotion(trainName, moving, nowMillis);
      return HudState.ON_LAYOVER;
    }
    if (stop && atLastStation) {
      updateMotion(trainName, moving, nowMillis);
      return HudState.AT_LAST_STATION;
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

  /**
   * 回库车越过运营终点后改用 {@link HudState#OUT_OF_SERVICE}。
   *
   * <p>折返待命（{@link HudState#ON_LAYOVER}）仍优先。模板没写 {@code OUT_OF_SERVICE} 时保持原状态——旧模板里没有这一状态， 不能让它回退到
   * {@code DEFAULT} 而丢掉原本的运行、临时停车显示。
   *
   * @param resolved {@link #resolve} 的结果
   * @param outOfService 是否已越过运营终点
   * @param templateDefinesOutOfService 模板是否写了 {@code OUT_OF_SERVICE}
   * @return 实际使用的状态
   */
  public static HudState applyOutOfService(
      HudState resolved, boolean outOfService, boolean templateDefinesOutOfService) {
    if (!outOfService || !templateDefinesOutOfService || resolved == HudState.ON_LAYOVER) {
      return resolved;
    }
    return HudState.OUT_OF_SERVICE;
  }

  /**
   * 回程支线的车到联挂站：挂上主干后接着走，终点站的“请全体乘客下车”换成联挂的说明。模板没写对应状态时保持原状态（旧模板里没有它们）。
   *
   * @param resolved 上一步的结果
   * @param joining 列车运营终点是联挂站、联挂后接着跑主干
   * @param definesArriving 模板写了 {@link HudState#JOIN_ARRIVING}
   * @param definesAtStation 模板写了 {@link HudState#AT_JOIN_STATION}
   * @return 实际使用的状态
   */
  public static HudState applyJoin(
      HudState resolved, boolean joining, boolean definesArriving, boolean definesAtStation) {
    if (!joining) {
      return resolved;
    }
    if (resolved == HudState.TERM_ARRIVING && definesArriving) {
      return HudState.JOIN_ARRIVING;
    }
    if (resolved == HudState.AT_LAST_STATION && definesAtStation) {
      return HudState.AT_JOIN_STATION;
    }
    return resolved;
  }

  /**
   * 列车在运营终点分行、乘客所在的这节车厢分行后接着开（解编 GOTO 到载客交路）：终点站的“请全体乘客下车”换成分行的说明。 按玩家所在车厢的占位符判断（{@code
   * car_destination}），模板没写对应状态时保持原状态。
   *
   * @param resolved 上一步的结果
   * @param placeholders 玩家的占位符（含分行与站点占位符）
   * @param definesArriving 模板写了 {@link HudState#DIVIDE_ARRIVING}
   * @param definesAtStation 模板写了 {@link HudState#AT_DIVIDE_STATION}
   * @return 实际使用的状态
   */
  public static HudState applyDivision(
      HudState resolved,
      Map<String, String> placeholders,
      boolean definesArriving,
      boolean definesAtStation) {
    String division = placeholders.get("division_station_code");
    String car = placeholders.get("car_destination");
    if (division == null || car == null || "-".equals(division) || "-".equals(car)) {
      return resolved;
    }
    if (resolved == HudState.TERM_ARRIVING
        && definesArriving
        && division.equalsIgnoreCase(placeholders.get("next_station_code"))) {
      return HudState.DIVIDE_ARRIVING;
    }
    if (resolved == HudState.AT_LAST_STATION
        && definesAtStation
        && division.equalsIgnoreCase(placeholders.get("current_station_code"))) {
      return HudState.AT_DIVIDE_STATION;
    }
    return resolved;
  }

  /**
   * 乘客所在的这节车厢那一段分行后不再载客（{@code car_terminates} 有值）：分行站是这节车厢的终点，到站与停站时报“本节车厢在该站止”与换乘另一段的提示。
   * 分行站在途中（{@link HudState#ARRIVING}、{@link HudState#AT_STATION}）与在运营终点（{@link
   * HudState#TERM_ARRIVING}、{@link HudState#AT_LAST_STATION}）都换；模板没写对应状态时保持原状态。
   *
   * @param resolved 上一步的结果
   * @param placeholders 玩家的占位符（含分行与站点占位符）
   * @param definesArriving 模板写了 {@link HudState#CAR_ENDS_ARRIVING}
   * @param definesAtStation 模板写了 {@link HudState#AT_CAR_END}
   * @return 实际使用的状态
   */
  public static HudState applyCarEnd(
      HudState resolved,
      Map<String, String> placeholders,
      boolean definesArriving,
      boolean definesAtStation) {
    String division = placeholders.get("division_station_code");
    String ends = placeholders.get("car_terminates");
    if (division == null || ends == null || "-".equals(division) || "-".equals(ends)) {
      return resolved;
    }
    if ((resolved == HudState.ARRIVING || resolved == HudState.TERM_ARRIVING)
        && definesArriving
        && division.equalsIgnoreCase(placeholders.get("next_station_code"))) {
      return HudState.CAR_ENDS_ARRIVING;
    }
    if ((resolved == HudState.AT_STATION || resolved == HudState.AT_LAST_STATION)
        && definesAtStation
        && division.equalsIgnoreCase(placeholders.get("current_station_code"))) {
      return HudState.AT_CAR_END;
    }
    return resolved;
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
