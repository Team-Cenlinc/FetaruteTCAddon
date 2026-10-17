package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.Optional;

/**
 * 发车铃的按法：一长、一短、两短（呼叫）。
 *
 * <p>按住右键时客户端每 {@value #USE_REPEAT_TICKS} tick 发一次“使用”，相隔超过 {@value #RELEASE_GAP_TICKS} tick 算松开；
 * 松开时按按住的时长判一长或一短。一短之后在 {@code doubleTicks} 内又按一下（短按）算呼叫，否则等时限过了才认作一短。驾驶员用丢弃键（每按一下一次）同样走这里，按一下就是一短。
 *
 * <p>计时单位由构造方式定：{@link #BuzzerPress(long, long)} 按服务器 tick；{@link #inMillis} 按真实时间（{@link
 * #clockMillis()}），给车掌按住右键用——客户端按真实时间重发，服务器卡顿后连跑几拍补进度时 tick 会一下子跳过好几格，按 tick 量会把按住误判成松开。
 * 按下的时刻取收到数据包的那一刻（网络线程上记下、传给主线程），方法都加了锁。
 */
public final class BuzzerPress {

  /** 认出的按法。 */
  public enum Kind {
    /** 一长：发车信号。 */
    LONG,
    /** 一短：收到。 */
    SHORT,
    /** 两短：呼叫对方。 */
    CALL
  }

  /** 按住时客户端重发“使用”的间隔。 */
  static final long USE_REPEAT_TICKS = 4L;

  /** 两次“使用”相隔超过这么久算松开。 */
  static final long RELEASE_GAP_TICKS = 6L;

  /** 一 tick 合多少毫秒（按真实时间计时时换算阈值）。 */
  public static final long MILLIS_PER_TICK = 50L;

  private final long repeat;
  private final long releaseGap;
  private final long longHold;
  private final long doubleGap;

  private long holdStart = -1L;
  private long lastPress = -1L;

  /** 等着看是不是两短的那一短是何时按下的；没有时为 -1。 */
  private long pendingShortAt = -1L;

  /**
   * 按服务器 tick 计时。
   *
   * @param longTicks 按住多久算一长
   * @param doubleTicks 两短相隔多久以内算呼叫
   */
  public BuzzerPress(long longTicks, long doubleTicks) {
    this(longTicks, doubleTicks, 1L);
  }

  private BuzzerPress(long longTicks, long doubleTicks, long unit) {
    this.repeat = USE_REPEAT_TICKS * unit;
    this.releaseGap = RELEASE_GAP_TICKS * unit;
    this.longHold = Math.max(USE_REPEAT_TICKS, longTicks) * unit;
    this.doubleGap = Math.max(USE_REPEAT_TICKS, doubleTicks) * unit;
  }

  /** 按真实时间计时（时刻取 {@link #clockMillis()}）；阈值仍按 tick 给，换算成毫秒。 */
  public static BuzzerPress inMillis(long longTicks, long doubleTicks) {
    return new BuzzerPress(longTicks, doubleTicks, MILLIS_PER_TICK);
  }

  private static final long ORIGIN_NANOS = System.nanoTime();

  /** 按真实时间计时用的时钟（毫秒，单调递增，从 1 起：-1 留作“没有”）。 */
  public static long clockMillis() {
    return (System.nanoTime() - ORIGIN_NANOS) / 1_000_000L + 1L;
  }

  /** 按下（或按住时的一次重发）。 */
  public synchronized void press(long at) {
    if (holdStart < 0L) {
      holdStart = at;
    }
    lastPress = Math.max(lastPress, at);
  }

  /** 忘掉按到一半的按法（这段时间的按键不作数）。 */
  public synchronized void reset() {
    holdStart = -1L;
    lastPress = -1L;
    pendingShortAt = -1L;
  }

  /** 正按着（铃正在响）。 */
  public synchronized boolean ringing(long now) {
    return holdStart >= 0L && now - lastPress <= releaseGap;
  }

  /**
   * 定时调用：松开时认出按法。
   *
   * @return 这一次认出的按法；没有时为空
   */
  public synchronized Optional<Kind> tick(long now) {
    if (holdStart >= 0L && now - lastPress > releaseGap) {
      long held = lastPress - holdStart + repeat;
      long pressedAt = holdStart;
      holdStart = -1L;
      lastPress = -1L;
      if (held >= longHold) {
        pendingShortAt = -1L;
        return Optional.of(Kind.LONG);
      }
      if (pendingShortAt >= 0L && pressedAt - pendingShortAt <= doubleGap) {
        pendingShortAt = -1L;
        return Optional.of(Kind.CALL);
      }
      pendingShortAt = pressedAt;
      return Optional.empty();
    }
    if (holdStart < 0L && pendingShortAt >= 0L && now - pendingShortAt > doubleGap) {
      pendingShortAt = -1L;
      return Optional.of(Kind.SHORT);
    }
    return Optional.empty();
  }
}
