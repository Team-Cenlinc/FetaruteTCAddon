package org.fetarute.fetaruteTCAddon.drive.inventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每名玩家的客户端时钟：数客户端每结束一 tick 发来的包（1.21.2 起每 tick 一个）。
 *
 * <p>按键包之间夹着几个这种包，就是客户端上隔了几 tick。只看客户端自己的节奏：同一条连接上的包按发出的顺序到达，网络延迟、抖动、丢包重传都只让包晚到，不改变它排在第几个 tick
 * 后面；服务器卡顿也不影响。同一 tick 里发的几个包读数相同。
 *
 * <p>读数以毫秒计（客户端 tick × 50），与按真实时间给的阈值通用。没收到过这种包的客户端（旧版本经代理接入）退回按服务器收到包的真实时间。收到第一个包时以当时的真实时间为起点往上数，
 * 前后读数接得上；所有玩家从进服起就计数。网络线程上写，任意线程读。
 */
public final class ClientClock {

  /** 客户端一 tick 合多少毫秒。 */
  public static final long MILLIS_PER_TICK = 50L;

  private static final long ORIGIN_NANOS = System.nanoTime();

  private final Map<UUID, Ticks> ticks = new ConcurrentHashMap<>();

  /** 一名玩家的计数：从收到第一个 tick 结束包时的真实时间起。 */
  private static final class Ticks {
    private final long base;
    private final AtomicLong count = new AtomicLong();

    private Ticks(long base) {
      this.base = base;
    }
  }

  /** 客户端结束了一 tick（收到它的 tick 结束包）。 */
  public void tickEnded(UUID playerId) {
    ticks.computeIfAbsent(playerId, id -> new Ticks(realMillis())).count.incrementAndGet();
  }

  /** 此刻的读数（毫秒，单调递增）：按键包在收到时读一次，就是它在客户端上是哪一 tick 发的。 */
  public long now(UUID playerId) {
    Ticks counted = ticks.get(playerId);
    return counted == null ? realMillis() : counted.base + counted.count.get() * MILLIS_PER_TICK;
  }

  /** 玩家下线：下次进服重新计数。 */
  public void forget(UUID playerId) {
    ticks.remove(playerId);
  }

  /** 服务器的真实时间（毫秒，单调递增，从 1 起）。 */
  static long realMillis() {
    return (System.nanoTime() - ORIGIN_NANOS) / 1_000_000L + 1L;
  }
}
