package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.Optional;

/**
 * 发车铃的按法：一长、一短、两短（呼叫）。
 *
 * <p>按住右键时客户端每 {@value #USE_REPEAT_TICKS} tick 发一次“使用”，相隔超过 {@value #RELEASE_GAP_TICKS} tick 算松开；
 * 松开时按按住的时长判一长或一短。一短之后在 {@code doubleTicks} 内又按一下（短按）算呼叫，否则等时限过了才认作一短。驾驶员用丢弃键（每按一下一次）同样走这里，按一下就是一短。
 * 只在服务器主线程使用。
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

  private final long longTicks;
  private final long doubleTicks;

  private long holdStart = -1L;
  private long lastPress = -1L;

  /** 等着看是不是两短的那一短是何时按下的；没有时为 -1。 */
  private long pendingShortAt = -1L;

  /**
   * @param longTicks 按住多久算一长
   * @param doubleTicks 两短相隔多久以内算呼叫
   */
  public BuzzerPress(long longTicks, long doubleTicks) {
    this.longTicks = Math.max(USE_REPEAT_TICKS, longTicks);
    this.doubleTicks = Math.max(USE_REPEAT_TICKS, doubleTicks);
  }

  /** 按下（或按住时的一次重发）。 */
  public void press(long tick) {
    if (holdStart < 0L) {
      holdStart = tick;
    }
    lastPress = tick;
  }

  /** 正按着（铃正在响）。 */
  public boolean ringing(long tick) {
    return holdStart >= 0L && tick - lastPress <= RELEASE_GAP_TICKS;
  }

  /**
   * 每 tick 调用：松开时认出按法。
   *
   * @return 这一拍认出的按法；没有时为空
   */
  public Optional<Kind> tick(long now) {
    if (holdStart >= 0L && now - lastPress > RELEASE_GAP_TICKS) {
      long held = lastPress - holdStart + USE_REPEAT_TICKS;
      long pressedAt = holdStart;
      holdStart = -1L;
      lastPress = -1L;
      if (held >= longTicks) {
        pendingShortAt = -1L;
        return Optional.of(Kind.LONG);
      }
      if (pendingShortAt >= 0L && pressedAt - pendingShortAt <= doubleTicks) {
        pendingShortAt = -1L;
        return Optional.of(Kind.CALL);
      }
      pendingShortAt = pressedAt;
      return Optional.empty();
    }
    if (holdStart < 0L && pendingShortAt >= 0L && now - pendingShortAt > doubleTicks) {
      pendingShortAt = -1L;
      return Optional.of(Kind.SHORT);
    }
    return Optional.empty();
  }
}
