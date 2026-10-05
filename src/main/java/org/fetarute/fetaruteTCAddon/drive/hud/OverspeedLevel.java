package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.Locale;

/** 车速相对限速的状态，决定动作栏上速度与限速的颜色。 */
public enum OverspeedLevel {
  /** 没有限速信息。 */
  NONE,
  /** 未超速。 */
  OK,
  /** 超速但超出比例小于红线（标黄）。 */
  WARN,
  /** 超出比例达到红线（标红）。 */
  OVER;

  /** 超出限速不到该比例视为没有超速（浮点与四舍五入的余量）。 */
  private static final double LIMIT_TOLERANCE = 0.005;

  /**
   * 判定车速相对限速的状态。
   *
   * @param speedBps 当前车速（格/秒）
   * @param limitBps 限速（格/秒）；不是正的有限数视为没有限速信息
   * @param redRatio 超出限速的比例达到它时标红
   */
  public static OverspeedLevel classify(double speedBps, double limitBps, double redRatio) {
    if (!Double.isFinite(limitBps) || limitBps <= 0.0) {
      return NONE;
    }
    double excess = speedBps / limitBps - 1.0;
    if (excess <= LIMIT_TOLERANCE) {
      return OK;
    }
    return excess >= redRatio ? OVER : WARN;
  }

  /** 语言键后缀，如 {@code ok}。 */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
