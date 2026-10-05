package org.fetarute.fetaruteTCAddon.drive.cab;

/**
 * 警惕装置：列车行驶中一段时间没有任何操作就报警，报警后仍不确认就施加紧急制动。
 *
 * <p>换档、右键都算确认。紧急制动施加后，确认不能撤销，列车停稳后装置自动复位。本类不依赖任何服务器对象，时间用服务器 tick。
 */
public final class Vigilance {

  /** 一个 tick 里发生的事。 */
  public enum Event {
    NONE,
    /** 开始报警。 */
    WARNING_STARTED,
    /** 报警中又过了一秒（用于提示音）。 */
    WARNING_SECOND,
    /** 超时未确认，施加紧急制动。 */
    TRIPPED
  }

  private static final long TICKS_PER_SECOND = 20L;

  private final long intervalTicks;
  private final long warningTicks;
  private long lastAckTick;
  private long lastSecondAnnounced = -1;
  private boolean tripped;

  public Vigilance(long intervalTicks, long warningTicks, long nowTick) {
    this.intervalTicks = Math.max(1L, intervalTicks);
    this.warningTicks = Math.max(1L, warningTicks);
    this.lastAckTick = nowTick;
  }

  /** 驾驶员有操作：重新计时。已施加的紧急制动不会因此撤销。 */
  public void acknowledge(long nowTick) {
    lastAckTick = nowTick;
    lastSecondAnnounced = -1;
  }

  /**
   * 推进一 tick。
   *
   * @param moving 列车是否在行驶；停稳时不计时，并复位已施加的紧急制动
   */
  public Event tick(long nowTick, boolean moving) {
    if (!moving) {
      lastAckTick = nowTick;
      lastSecondAnnounced = -1;
      tripped = false;
      return Event.NONE;
    }
    if (tripped) {
      return Event.NONE;
    }
    long idle = nowTick - lastAckTick;
    if (idle >= intervalTicks + warningTicks) {
      tripped = true;
      return Event.TRIPPED;
    }
    if (idle < intervalTicks) {
      return Event.NONE;
    }
    long second = (idle - intervalTicks) / TICKS_PER_SECOND;
    if (second == lastSecondAnnounced) {
      return Event.NONE;
    }
    boolean first = lastSecondAnnounced < 0;
    lastSecondAnnounced = second;
    return first ? Event.WARNING_STARTED : Event.WARNING_SECOND;
  }

  /** 是否正在报警（尚未施加紧急制动）。 */
  public boolean warning(long nowTick) {
    return !tripped && lastSecondAnnounced >= 0 && nowTick - lastAckTick >= intervalTicks;
  }

  /** 报警中距离施加紧急制动还剩的秒数（向上取整）。 */
  public long warningRemainingSeconds(long nowTick) {
    long remaining = intervalTicks + warningTicks - (nowTick - lastAckTick);
    return Math.max(0L, (remaining + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
  }

  /** 距离开始报警还剩的秒数（向上取整）；已在报警或已施加紧急制动时为 0。 */
  public long secondsUntilWarning(long nowTick) {
    long remaining = intervalTicks - (nowTick - lastAckTick);
    return Math.max(0L, (remaining + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
  }

  /** 是否已因超时施加紧急制动（停稳前保持）。 */
  public boolean tripped() {
    return tripped;
  }
}
