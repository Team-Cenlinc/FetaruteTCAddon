package org.fetarute.fetaruteTCAddon.display.hud;

import java.util.Locale;
import java.util.Optional;

/**
 * HUD 状态枚举：用于 BossBar/ActionBar 模板按状态分支。
 *
 * <p>解析时兼容旧前缀（STOP/LAYOVER/TERMINAL_ARRIVING）。
 */
public enum HudState {
  DEFAULT,
  IDLE,
  AT_LAST_STATION,
  AT_STATION,
  ON_LAYOVER,
  DEPARTING,
  ARRIVING,
  TERM_ARRIVING,
  IN_TRIP,
  /**
   * 回库车已越过运营终点（「回库 / Not in Service」）。只在模板写了这一状态时使用，见 {@link
   * HudStateTracker#applyOutOfService}；否则仍按停站、运行等状态显示。
   */
  OUT_OF_SERVICE,
  /**
   * 回程支线的车即将到达联挂站：在那里挂上主干后接着开往主干的终点，不是终点站。只在模板写了这一状态时代替 {@link #TERM_ARRIVING}，见 {@link
   * HudStateTracker#applyJoin}。
   */
  JOIN_ARRIVING,
  /**
   * 回程支线的车停在联挂站、等着挂上主干。只在模板写了这一状态时代替 {@link #AT_LAST_STATION}，见 {@link HudStateTracker#applyJoin}。
   */
  AT_JOIN_STATION,
  /**
   * 列车即将到达运营终点、在那里分行，乘客所在的这节车厢分行后接着载客：不是这节车厢的终点。只在模板写了这一状态时代替 {@link #TERM_ARRIVING}，见 {@link
   * HudStateTracker#applyDivision}。
   */
  DIVIDE_ARRIVING,
  /** 列车停在运营终点、要在这里分行，乘客所在的这节车厢分行后接着载客。只在模板写了这一状态时代替 {@link #AT_LAST_STATION}。 */
  AT_DIVIDE_STATION,
  /**
   * 列车即将到达分行站，乘客所在的这节车厢分行后不再载客：这一站是这节车厢的终点，去别处的乘客要在这里换到另一段。 只在模板写了这一状态时代替 {@link #ARRIVING} 与
   * {@link #TERM_ARRIVING}，见 {@link HudStateTracker#applyCarEnd}。
   */
  CAR_ENDS_ARRIVING,
  /**
   * 列车停在分行站、还没拆，乘客所在的这节车厢分行后不再载客。只在模板写了这一状态时代替 {@link #AT_STATION} 与 {@link #AT_LAST_STATION}，见
   * {@link HudStateTracker#applyCarEnd}。
   */
  AT_CAR_END;

  /** 解析模板中的状态 key（大小写不敏感）。 */
  public static Optional<HudState> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    if ("STOP".equals(normalized)) {
      return Optional.of(HudState.AT_STATION);
    }
    if ("LAST_STATION".equals(normalized)) {
      return Optional.of(HudState.AT_LAST_STATION);
    }
    if ("LAYOVER".equals(normalized)) {
      return Optional.of(HudState.ON_LAYOVER);
    }
    if ("TERMINAL_ARRIVING".equals(normalized)) {
      return Optional.of(HudState.TERM_ARRIVING);
    }
    try {
      return Optional.of(HudState.valueOf(normalized));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }
}
