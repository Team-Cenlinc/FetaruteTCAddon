package org.fetarute.fetaruteTCAddon.display.pids.map;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 换算好的调色板帧，按内容标识与尺寸共享。
 *
 * <p>同一站台的几块屏内容标识相同；轮播翻回主页时到发没变，标识也与翻页前相同；同一布局、同一色带的宣传页在各站都一样。 这些情况都不必再渲染整帧、再逐像素换算。帧是只读的：{@link
 * PidsDisplay} 只从里面拷贝，不改写。
 *
 * <p>按总字节数淘汰最久没用的帧；随站台屏服务一起作废（重载会换字体与配置）。线程安全。
 */
public final class PidsFrameCache {

  private final long maxBytes;
  private final LinkedHashMap<Key, byte[]> frames = new LinkedHashMap<>(64, 0.75f, true);
  private long bytes;

  /**
   * @param maxBytes 缓存的帧总字节数上限
   */
  public PidsFrameCache(long maxBytes) {
    if (maxBytes <= 0L) {
      throw new IllegalArgumentException("maxBytes 必须为正数");
    }
    this.maxBytes = maxBytes;
  }

  /**
   * 取帧；没有时用 {@code render} 换算一帧并放入缓存。
   *
   * @param key 内容标识（视图、宣传页、测试卡都是纯数据记录，{@code equals} 即内容相同）
   * @param width 宽（像素）
   * @param height 高（像素）
   * @param render 渲染并换算出长度为 {@code width × height} 的调色板帧
   * @return 只读的帧
   */
  public byte[] frame(Object key, int width, int height, Supplier<byte[]> render) {
    Key cacheKey = new Key(Objects.requireNonNull(key, "key"), width, height);
    synchronized (frames) {
      byte[] cached = frames.get(cacheKey);
      if (cached != null) {
        return cached;
      }
    }
    byte[] rendered = render.get();
    synchronized (frames) {
      byte[] previous = frames.put(cacheKey, rendered);
      bytes += rendered.length - (previous == null ? 0 : previous.length);
      Iterator<Map.Entry<Key, byte[]>> eldest = frames.entrySet().iterator();
      while (bytes > maxBytes && eldest.hasNext()) {
        Map.Entry<Key, byte[]> entry = eldest.next();
        if (entry.getKey().equals(cacheKey)) {
          break;
        }
        bytes -= entry.getValue().length;
        eldest.remove();
      }
    }
    return rendered;
  }

  /** 缓存的帧数。 */
  public int size() {
    synchronized (frames) {
      return frames.size();
    }
  }

  private record Key(Object content, int width, int height) {}
}
