package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 限制单个物理编组把"本车的问题"升级成"全局 STOP_FIRST 重建"的频率。
 *
 * <p>READY 之后某辆车过不了启动水合校验（迟加载、身份重复、名字对不上……），运行时的做法是关闭全局授权门并请求整网重建。
 * 如果重建本身治不好这辆车的问题，它在下一次信号检查时又会触发一遍：重建—READY—再重建，全网被这一辆车永远冻结（2026-09-30 08:37 实服循环 15
 * 秒，直到人工重载）。本类只回答一个问题：这辆车最近升级得太频繁了吗？太频繁时调用方应当只把这辆车硬停并留痕，不再拖着全网重来。
 *
 * <p>按物理编组的对象身份计数（同一逻辑名的两个编组互不影响），窗口内超过上限即拒绝；窗口滑出后自动恢复许可，因此真正被修好的车不会被永久压制。 仅在 Bukkit 主线程使用。
 */
final class StartupEscalationLimiter {

  private final int maxEscalationsPerWindow;
  private final Duration window;
  private final Map<Object, Deque<Instant>> escalationsByIdentity = new IdentityHashMap<>();

  StartupEscalationLimiter(int maxEscalationsPerWindow, Duration window) {
    if (maxEscalationsPerWindow < 1) {
      throw new IllegalArgumentException("maxEscalationsPerWindow 必须大于零");
    }
    if (window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("window 必须大于零");
    }
    this.maxEscalationsPerWindow = maxEscalationsPerWindow;
    this.window = window;
  }

  /**
   * 申请一次全局升级。
   *
   * @param identity 触发升级的物理编组身份；为空时不限流（无从区分是谁）
   * @param now 当前时刻
   * @return {@code true} 表示允许升级并已计数；{@code false} 表示该编组近期升级过多，调用方只应隔离本车
   */
  boolean tryAcquire(Object identity, Instant now) {
    if (identity == null) {
      return true;
    }
    prune(now);
    Deque<Instant> recent =
        escalationsByIdentity.computeIfAbsent(identity, key -> new ArrayDeque<>());
    if (recent.size() >= maxEscalationsPerWindow) {
      return false;
    }
    recent.addLast(now);
    return true;
  }

  /** 编组被移除后忘掉它的计数，避免 map 长期持有已失效对象。 */
  void forget(Object identity) {
    if (identity != null) {
      escalationsByIdentity.remove(identity);
    }
  }

  int trackedIdentityCount() {
    return escalationsByIdentity.size();
  }

  private void prune(Instant now) {
    Instant cutoff = now.minus(window);
    Iterator<Map.Entry<Object, Deque<Instant>>> entries =
        escalationsByIdentity.entrySet().iterator();
    while (entries.hasNext()) {
      Deque<Instant> recent = entries.next().getValue();
      while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
        recent.removeFirst();
      }
      if (recent.isEmpty()) {
        entries.remove();
      }
    }
  }
}
