package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 等驾驶员回一短的发车信号：simulation 级人工驾驶的驾驶员收到车掌的发车信号后，要在时限内按一下铃表示收到，到时没回记一次漏确认。本类不依赖服务器对象，只在服务器主线程使用。 */
final class PendingAcks {

  private final Map<UUID, Long> deadlines = new HashMap<>();

  /**
   * 回一短的期限：时限之外再留出认出一短要等的时间（一短要等两短的间隔过了才认得出），时限最后一刻按下的照样算收到。
   *
   * @param nowTick 发出发车信号的 tick
   */
  static long deadline(long nowTick, GuardConfig guard) {
    return nowTick
        + guard.ackSeconds() * 20L
        + Math.max(guard.buzzerDoubleTicks(), BuzzerPress.RELEASE_GAP_TICKS)
        + 1L;
  }

  /** 开始等这名驾驶员回一短（再发一次信号时重新计时）。 */
  void expect(UUID driverId, long deadlineTick) {
    deadlines.put(driverId, deadlineTick);
  }

  /**
   * 驾驶员按了一短。
   *
   * @return 是否正在等他回：是的话这次算收到
   */
  boolean acknowledge(UUID driverId) {
    return deadlines.remove(driverId) != null;
  }

  /** 到时还没回的驾驶员（取走）。 */
  List<UUID> expired(long nowTick) {
    List<UUID> late = new ArrayList<>();
    deadlines
        .entrySet()
        .removeIf(
            entry -> {
              if (nowTick < entry.getValue()) {
                return false;
              }
              late.add(entry.getKey());
              return true;
            });
    return late;
  }

  /** 不再等这名驾驶员（下车、换了列车）。 */
  void forget(UUID driverId) {
    deadlines.remove(driverId);
  }

  boolean isEmpty() {
    return deadlines.isEmpty();
  }
}
