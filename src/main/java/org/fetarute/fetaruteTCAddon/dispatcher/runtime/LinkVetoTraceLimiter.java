package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * 联挂否决证据的按对限流器。
 *
 * <p>被否决的联挂不会“完成”，叠在一起的两个编组每个物理 tick 都会再撞一次；同一对列车在窗口内只放行一条证据，窗口过后放行下一条并带上期间被压掉的次数；持续叠放时窗口每次放大 4
 * 倍直到上限。 键数量有上限，按最近最少使用淘汰，避免高基数的列车名无限占用内存。
 *
 * <p>只在服务器主线程使用，不同步。
 */
final class LinkVetoTraceLimiter {

  /** 每次放行后窗口放大的倍数。 */
  private static final long BACKOFF_FACTOR = 4L;

  private final long baseWindowMillis;
  private final long maxWindowMillis;
  private final Map<String, Entry> entries;

  LinkVetoTraceLimiter(long baseWindowMillis, long maxWindowMillis, int maxKeys) {
    if (baseWindowMillis <= 0L) {
      throw new IllegalArgumentException("baseWindowMillis 必须大于零");
    }
    if (maxWindowMillis < baseWindowMillis) {
      throw new IllegalArgumentException("maxWindowMillis 不能小于 baseWindowMillis");
    }
    if (maxKeys < 1) {
      throw new IllegalArgumentException("maxKeys 必须大于零");
    }
    this.baseWindowMillis = baseWindowMillis;
    this.maxWindowMillis = maxWindowMillis;
    this.entries = new BoundedLru(maxKeys);
  }

  /**
   * 判断这一次是否该留下证据。
   *
   * @param pairKey 列车对的稳定键
   * @param nowMillis 当前时间
   * @return 放行时为窗口内被压掉的次数；被压掉时为空
   */
  OptionalInt admit(String pairKey, long nowMillis) {
    Entry entry = entries.get(pairKey);
    if (entry == null) {
      entries.put(pairKey, new Entry(nowMillis, baseWindowMillis));
      return OptionalInt.of(0);
    }
    long elapsed = nowMillis - entry.windowStartMillis;
    // 墙钟回拨（elapsed < 0）不能把证据压制到时钟追平：视为新窗口，重新放行。
    if (elapsed >= 0L && elapsed < entry.windowMillis) {
      entry.suppressed++;
      return OptionalInt.empty();
    }
    int suppressed = entry.suppressed;
    entry.windowStartMillis = nowMillis;
    entry.suppressed = 0;
    entry.windowMillis = Math.min(maxWindowMillis, entry.windowMillis * BACKOFF_FACTOR);
    return OptionalInt.of(suppressed);
  }

  /** 按访问顺序淘汰最近最少使用键的有界映射。 */
  private static final class BoundedLru extends LinkedHashMap<String, Entry> {
    private static final long serialVersionUID = 1L;

    private final int maxKeys;

    private BoundedLru(int maxKeys) {
      super(16, 0.75F, true);
      this.maxKeys = maxKeys;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
      return size() > maxKeys;
    }
  }

  private static final class Entry {
    private long windowStartMillis;
    private long windowMillis;
    private int suppressed;

    private Entry(long windowStartMillis, long windowMillis) {
      this.windowStartMillis = windowStartMillis;
      this.windowMillis = windowMillis;
    }
  }
}
