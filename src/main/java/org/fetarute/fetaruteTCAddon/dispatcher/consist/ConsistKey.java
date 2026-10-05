package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.Locale;
import java.util.Optional;

/**
 * 车型的身份：出车编组的写法（通常是 TrainCarts 存车名）。
 *
 * <p>同一个车型会出现在三处：编组方案里的写法、出车时写到车上的 {@code FTA_SPAWN_PATTERN} 标签、复用时从待命车标签读回的值。 三处由不同的人和代码写出，
 * 大小写与空格不一定一致，比较一律先归一：去掉首尾空白、连续空白压成一个空格、转小写。
 */
public final class ConsistKey {

  /** 出车时写入、复用时保留的车型标签；与出车编组同一个键。 */
  public static final String TRAIN_TAG = "FTA_SPAWN_PATTERN";

  private ConsistKey() {}

  /**
   * 编组写法的书写形式：去掉首尾空白，连续空白压成一个空格；保留大小写，出车与显示用它。
   *
   * @param pattern 原始写法
   * @return 规整后的写法；空白时为空
   */
  public static Optional<String> tidy(String pattern) {
    if (pattern == null) {
      return Optional.empty();
    }
    String tidy = pattern.trim().replaceAll("\\s+", " ");
    return tidy.isEmpty() ? Optional.empty() : Optional.of(tidy);
  }

  /**
   * 用于比较的键：书写形式再转小写。
   *
   * @param pattern 原始写法
   * @return 比较键；空白时为空
   */
  public static Optional<String> of(String pattern) {
    return tidy(pattern).map(value -> value.toLowerCase(Locale.ROOT));
  }
}
