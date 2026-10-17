package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.Optional;

/**
 * 发车铃的按法：一长、一短、两短（呼叫）。
 *
 * <p>按住的时长有两种量法。车掌手里的发车铃是一件“可使用”的物品：准星没对着方块或实体时，客户端按下右键只发一次“使用”，之后一直处于使用状态，
 * 松开时另发一个包，按住多久就是多久（{@link #press(long, boolean)} 传 {@code true}，松开调 {@link
 * #release(long)}）。其余情况客户端按住时每 {@value #USE_REPEAT_TICKS} tick 重发一次“使用”，相隔超过 {@value
 * #RELEASE_GAP_TICKS} tick 算松开：重发间隔只比松开门槛短一点，网络或客户端卡一下就会把按住切断，只作退路。 松开时按按住的时长判一长或一短。一短之后在 {@code
 * doubleTicks} 内又按一下（短按）算呼叫，否则等时限过了才认作一短。驾驶员用丢弃键（每按一下一次）同样走这里，按一下就是一短。
 *
 * <p>计时单位由构造方式定：{@link #BuzzerPress(long, long)} 按服务器 tick；{@link #inMillis} 按真实时间（{@link
 * #clockMillis()}），给车掌按住右键用——客户端按真实时间重发，服务器卡顿后连跑几拍补进度时 tick 会一下子跳过好几格，按 tick 量会把按住误判成松开。
 * 按下与松开的时刻取收到数据包的那一刻（网络线程上记下、传给主线程），方法都加了锁。
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

  /** 客户端报着按住、却一直没等到松开，按住这么久就作废（多半是客户端其实没在使用、松开的包不会来）。 */
  static final long MAX_HELD_TICKS = 200L;

  /** 一 tick 合多少毫秒（按真实时间计时时换算阈值）。 */
  public static final long MILLIS_PER_TICK = 50L;

  private final long repeat;
  private final long releaseGap;
  private final long longHold;
  private final long doubleGap;
  private final long maxHeld;

  private long holdStart = -1L;
  private long lastPress = -1L;

  /** 这一次按住由客户端报着（处于使用状态，松开时另发包），不按重发间隔判松开。 */
  private boolean clientHeld;

  /** 松开时已认出、等下一次 {@link #tick} 交出去的按法。 */
  private Kind ready;

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
    this.maxHeld = MAX_HELD_TICKS * unit;
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
  public void press(long at) {
    press(at, false);
  }

  /**
   * 按下，或按住时的一次重发。
   *
   * @param clientHeld 客户端会一直报着按住、松开时另发包（{@link #release}）；按住期间又收到包说明客户端在重发，改按重发间隔判松开
   * @return 这一下是新按下的（之前没有按着）
   */
  public synchronized boolean press(long at, boolean clientHeld) {
    if (holdStart < 0L) {
      holdStart = at;
      lastPress = at;
      this.clientHeld = clientHeld;
      return true;
    }
    this.clientHeld = false;
    lastPress = Math.max(lastPress, at);
    return false;
  }

  /** 松开（客户端发来的松开，或手离开了发车铃这一格）：按到这一刻为止的时长认按法。没有按着时什么也不做。 */
  public synchronized void release(long at) {
    if (holdStart < 0L) {
      return;
    }
    ready = finish(Math.max(at, lastPress) - holdStart).orElse(null);
  }

  /** 忘掉按到一半的按法（这段时间的按键不作数）。 */
  public synchronized void reset() {
    holdStart = -1L;
    lastPress = -1L;
    clientHeld = false;
    ready = null;
    pendingShortAt = -1L;
  }

  /** 正按着（铃正在响）。 */
  public synchronized boolean ringing(long now) {
    return holdStart >= 0L && (clientHeld || now - lastPress <= releaseGap);
  }

  /**
   * 定时调用：松开时认出按法。
   *
   * @return 这一次认出的按法；没有时为空
   */
  public synchronized Optional<Kind> tick(long now) {
    if (ready != null) {
      Kind kind = ready;
      ready = null;
      return Optional.of(kind);
    }
    if (holdStart >= 0L && clientHeld) {
      if (now - holdStart > maxHeld) {
        reset();
      }
      return Optional.empty();
    }
    if (holdStart >= 0L && now - lastPress > releaseGap) {
      return finish(lastPress - holdStart + repeat);
    }
    if (holdStart < 0L && pendingShortAt >= 0L && now - pendingShortAt > doubleGap) {
      pendingShortAt = -1L;
      return Optional.of(Kind.SHORT);
    }
    return Optional.empty();
  }

  /** 结束这一次按住：按住够久是一长；短按要等等看是不是两短。 */
  private Optional<Kind> finish(long held) {
    long pressedAt = holdStart;
    holdStart = -1L;
    lastPress = -1L;
    clientHeld = false;
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
}
