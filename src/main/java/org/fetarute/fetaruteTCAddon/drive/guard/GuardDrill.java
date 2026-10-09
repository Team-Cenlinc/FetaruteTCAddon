package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.Objects;

/** 车掌考试的夹人夹物演练：车掌按下关门后站台报告夹人夹物，要在时限内再开车门并报告“夹人夹物”（先后不限）。本类不依赖服务器对象，只在服务器主线程使用。 */
final class GuardDrill {

  /** 提示里已做的一项。 */
  static final String DONE = "✔";

  /** 提示里还没做的一项。 */
  static final String PENDING = "☐";

  private final String station;
  private final long startTick;
  private final long deadlineTick;
  private boolean reopened;
  private boolean reported;

  /**
   * @param station 演练所在的车站
   * @param startTick 报告夹人夹物的 tick
   * @param seconds 处置时限（秒）
   */
  GuardDrill(String station, long startTick, int seconds) {
    this.station = Objects.requireNonNullElse(station, "");
    this.startTick = startTick;
    this.deadlineTick = startTick + Math.max(1, seconds) * 20L;
  }

  String station() {
    return station;
  }

  /** 车掌再开了车门。 */
  void noteReopened() {
    reopened = true;
  }

  /** 车掌报告了夹人夹物。 */
  void noteReported() {
    reported = true;
  }

  boolean reopened() {
    return reopened;
  }

  boolean reported() {
    return reported;
  }

  /** 两项都做了。 */
  boolean handled() {
    return reopened && reported;
  }

  /** 过了时限还没处置完。 */
  boolean expired(long nowTick) {
    return !handled() && nowTick >= deadlineTick;
  }

  /** 还剩几秒（向上取整，不小于 0）。 */
  long secondsLeft(long nowTick) {
    return Math.max(0L, (deadlineTick - nowTick + 19L) / 20L);
  }

  /** 从报告到此刻用了几秒。 */
  double seconds(long nowTick) {
    return Math.max(0L, nowTick - startTick) / 20.0;
  }
}
