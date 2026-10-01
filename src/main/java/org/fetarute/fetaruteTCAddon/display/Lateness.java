package org.fetarute.fetaruteTCAddon.display;

/**
 * 乘客信息里的晚点分级：站台屏、车内 HUD 与后续站点对话框共用同一把尺子。
 *
 * <p>偏差是按表运行的列车相对时刻表的秒数，正数为晚点；不按表运行的列车没有偏差，也就不谈准点与晚点。
 */
public enum Lateness {
  /** 偏差不足 {@link #LATE_SECONDS}（含早到）。 */
  ON_TIME,
  /** 晚点不足 {@link #SEVERELY_LATE_SECONDS}。 */
  LATE,
  /** 严重晚点。 */
  SEVERELY_LATE;

  /** 晚点达到这个秒数算晚点。 */
  public static final long LATE_SECONDS = 60L;

  /** 晚点达到这个秒数算严重晚点。 */
  public static final long SEVERELY_LATE_SECONDS = 300L;

  /**
   * 按偏差秒数分级。
   *
   * @param delaySeconds 相对计划的偏差，正数为晚点
   */
  public static Lateness of(long delaySeconds) {
    if (delaySeconds < LATE_SECONDS) {
      return ON_TIME;
    }
    return delaySeconds < SEVERELY_LATE_SECONDS ? LATE : SEVERELY_LATE;
  }

  /** 显示用的晚点分钟数（向下取整：晚 1 分 59 秒显示 1 分）。 */
  public static long minutes(long delaySeconds) {
    return Math.max(0L, delaySeconds) / 60L;
  }
}
