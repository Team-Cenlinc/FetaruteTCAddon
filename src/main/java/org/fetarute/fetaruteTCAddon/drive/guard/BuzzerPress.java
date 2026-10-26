package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.ArrayDeque;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.inventory.ClientClock;

/**
 * 发车铃的按法：一长、一短、两短（呼叫）。
 *
 * <p>按住的时长有两种量法。车掌手里的发车铃是一件“可使用”的物品：准星没对着方块或实体时，客户端按下右键只发一次“使用”，之后一直处于使用状态，
 * 松开时另发一个包，按住多久就是多久（{@link #press(long, boolean)} 传 {@code true}，松开调 {@link
 * #release(long)}）。其余情况客户端按住时每 {@value #USE_REPEAT_TICKS} tick 重发一次“使用”，相隔超过 {@value
 * #RELEASE_GAP_TICKS} tick 算松开。松开时按按住的时长判一长或一短。一短之后在 {@code doubleTicks} 内又按一下（短按）算呼叫，否则等时限过了才认作一短。
 *
 * <p>驾驶员的铃是丢弃键（{@link #forKey}）：按一下发一个包，按住时系统先隔一段初始延迟（约 0.25–1 秒）再按键重复、密集地连发。 两个包相隔超过 {@value
 * #KEY_REPEAT_TICKS} tick 就是又按了一下，快速连按两下也认得出两短；“单独一个包、隔一会儿、再一串密集的包”是按住了一次， 不当两短。 手快的连按两下可能只隔
 * {@value #KEY_REPEAT_TICKS} tick、看着像按键重复，但前面没有单独的一个包、只有挨着的两个：也认作两短。
 *
 * <p>时刻一律是 {@link ClientClock} 的读数（毫秒）：按下与松开在收到数据包时读，定时调用时读此刻的。客户端时钟数的是客户端自己的 tick，重发间隔恰好 4 tick，
 * 网络抖动、丢包重传、服务器卡顿都不会把按住拆开或把两下并成一下；阈值按 tick 给，换算成毫秒。方法都加了锁（网络线程与主线程都会调用）。
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

  /** 丢弃键按住时系统按键重复的间隔上限：相隔不超过这么久的两个包是同一次按住。 */
  static final long KEY_REPEAT_TICKS = 2L;

  /** 按一下发一个包的键（丢弃键）。 */
  private final boolean key;

  private final long repeat;
  private final long releaseGap;
  private final long longHold;
  private final long doubleGap;
  private final long maxHeld;

  private long holdStart = -1L;
  private long lastPress = -1L;

  /** 这一次按住由客户端报着（处于使用状态，松开时另发包），不按重发间隔判松开。 */
  private boolean clientHeld;

  /** 这一次按住收到了几个包。 */
  private int packets;

  /** 已认出、等 {@link #tick} 交出去的按法。 */
  private final ArrayDeque<Kind> ready = new ArrayDeque<>();

  /** 等着看是不是两短的那一短是何时按下的；没有时为 -1。 */
  private long pendingShortAt = -1L;

  /**
   * @param longTicks 按住多久算一长
   * @param doubleTicks 两短相隔多久以内算呼叫
   */
  public BuzzerPress(long longTicks, long doubleTicks) {
    this(longTicks, doubleTicks, false);
  }

  private BuzzerPress(long longTicks, long doubleTicks, boolean key) {
    long unit = ClientClock.MILLIS_PER_TICK;
    this.key = key;
    this.repeat = (key ? KEY_REPEAT_TICKS : USE_REPEAT_TICKS) * unit;
    this.releaseGap = (key ? KEY_REPEAT_TICKS : RELEASE_GAP_TICKS) * unit;
    this.longHold = Math.max(USE_REPEAT_TICKS, longTicks) * unit;
    this.doubleGap = Math.max(USE_REPEAT_TICKS, doubleTicks) * unit;
    this.maxHeld = MAX_HELD_TICKS * unit;
  }

  /**
   * 按一下发一个包的键（驾驶员的丢弃键），见类说明。
   *
   * @param longTicks 按住多久算一长
   * @param doubleTicks 两短相隔多久以内算呼叫
   */
  public static BuzzerPress forKey(long longTicks, long doubleTicks) {
    return new BuzzerPress(longTicks, doubleTicks, true);
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
    settle(at);
    if (holdStart < 0L) {
      holdStart = at;
      lastPress = at;
      packets = 1;
      this.clientHeld = clientHeld;
      return true;
    }
    this.clientHeld = false;
    lastPress = Math.max(lastPress, at);
    packets++;
    return false;
  }

  /** 松开（客户端发来的松开，或手离开了发车铃这一格）：按到这一刻为止的时长认按法。没有按着时什么也不做。 */
  public synchronized void release(long at) {
    settle(at);
    if (holdStart >= 0L) {
      finish(Math.max(at, lastPress) - holdStart);
    }
  }

  /** 忘掉按到一半的按法（这段时间的按键不作数）。 */
  public synchronized void reset() {
    holdStart = -1L;
    lastPress = -1L;
    clientHeld = false;
    packets = 0;
    ready.clear();
    pendingShortAt = -1L;
  }

  /** 正按着（铃正在响）。 */
  public synchronized boolean ringing(long now) {
    return holdStart >= 0L && (clientHeld || now - lastPress <= releaseGap);
  }

  /**
   * 定时调用：交出认出的按法。
   *
   * @return 认出的按法（一次交一个）；没有时为空
   */
  public synchronized Optional<Kind> tick(long now) {
    if (holdStart >= 0L && clientHeld && now - holdStart > maxHeld) {
      holdStart = -1L;
      lastPress = -1L;
      clientHeld = false;
    }
    settle(now);
    return Optional.ofNullable(ready.poll());
  }

  /**
   * 按 {@code at} 这一刻的读数，把在它之前就该结束的认掉：重发停了超过门槛的那一下已经松开，等两短的那一短过了时限就是一短。
   *
   * <p>收到包时先做一遍：两下的包挤在一起到、主线程来不及在中间检查时，也不会把后一下当成前一下的重发。
   */
  private void settle(long at) {
    if (holdStart >= 0L && !clientHeld && at - lastPress > releaseGap) {
      finish(lastPress - holdStart + repeat);
    }
    if (holdStart < 0L && pendingShortAt >= 0L && at - pendingShortAt > doubleGap) {
      pendingShortAt = -1L;
      ready.add(Kind.SHORT);
    }
  }

  /** 结束这一次按住：按住够久是一长；短按要等等看是不是两短。 */
  private void finish(long held) {
    long pressedAt = holdStart;
    int count = packets;
    holdStart = -1L;
    lastPress = -1L;
    clientHeld = false;
    packets = 0;
    if (key && count > 1 && pendingShortAt >= 0L && pressedAt - pendingShortAt <= doubleGap) {
      // 丢弃键按住不放：先是单独一个包，隔着系统的初始延迟才连发，前后是同一次按住
      held += pressedAt - pendingShortAt;
      pressedAt = pendingShortAt;
      pendingShortAt = -1L;
    } else if (key && count == 2 && held < longHold) {
      // 前面没有单独的一个包、只有挨着的两个：是快速连按两下（按住时一定先只有一个包，隔着初始延迟才连发，见上一条）
      pendingShortAt = -1L;
      ready.add(Kind.CALL);
      return;
    }
    if (held >= longHold) {
      pendingShortAt = -1L;
      ready.add(Kind.LONG);
      return;
    }
    if (pendingShortAt >= 0L && pressedAt - pendingShortAt <= doubleGap) {
      pendingShortAt = -1L;
      ready.add(Kind.CALL);
      return;
    }
    pendingShortAt = pressedAt;
  }
}
