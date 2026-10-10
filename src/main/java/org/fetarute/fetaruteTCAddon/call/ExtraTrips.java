package org.fetarute.fetaruteTCAddon.call;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 加开（表外加开一班）的标识与规则：管理员在 {@code /fta depot spawn … --extra} 上从车库加开一班，复用叫车的票与生命周期——不进时刻表、
 * 跑完全程回库，站台屏写“加开”。
 *
 * <p>还没派出的加开与叫车放在同一张待派表里存库：方向一栏写 {@link Key#format()}，叫车时刻一栏写计划发车时刻。
 */
public final class ExtraTrips {

  /** 方向一栏的前缀：带它的待派单是加开，不是玩家叫车。 */
  static final String KEY_PREFIX = "EXTRA:";

  /** 过了计划发车时刻这么久还没派出就撤票（车库一直出不了车）。 */
  static final Duration TIMEOUT = Duration.ofMinutes(30);

  private ExtraTrips() {}

  /**
   * 加开的交路与出车的车库。
   *
   * @param routeId 交路
   * @param depotNode 命令里写的车库节点（只用来认车库，股道照交路的出库写法选）
   */
  record Key(UUID routeId, String depotNode) {

    Key {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(depotNode, "depotNode");
    }

    /** 写进待派单方向一栏的文本。 */
    String format() {
      return KEY_PREFIX + routeId + ":" + depotNode;
    }

    /**
     * 解析方向一栏；不是加开时为空。
     *
     * @param directionKey 方向一栏
     */
    static Optional<Key> parse(String directionKey) {
      if (directionKey == null || !directionKey.startsWith(KEY_PREFIX)) {
        return Optional.empty();
      }
      String rest = directionKey.substring(KEY_PREFIX.length());
      int colon = rest.indexOf(':');
      if (colon <= 0 || colon == rest.length() - 1) {
        return Optional.empty();
      }
      try {
        return Optional.of(
            new Key(UUID.fromString(rest.substring(0, colon)), rest.substring(colon + 1)));
      } catch (IllegalArgumentException ex) {
        return Optional.empty();
      }
    }
  }

  /** 方向一栏写的是不是加开。 */
  static boolean isExtra(String directionKey) {
    return Key.parse(directionKey).isPresent();
  }

  /**
   * {@code --at HH:MM} 的发车时刻（服务器时区）：今天的这个时刻，已经过了就是明天的。
   *
   * @param at 时分
   * @param now 现在（服务器时区）
   */
  public static Instant departAt(LocalTime at, ZonedDateTime now) {
    ZonedDateTime today = now.with(at.withSecond(0).withNano(0));
    return (today.isAfter(now) ? today : today.plusDays(1)).toInstant();
  }

  /**
   * 解析 {@code HH:MM}；写法不对时为空。
   *
   * @param text 命令参数
   */
  public static Optional<LocalTime> parseClock(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String trimmed = text.trim();
    int colon = trimmed.indexOf(':');
    if (colon <= 0 || colon == trimmed.length() - 1) {
      return Optional.empty();
    }
    try {
      int hour = Integer.parseInt(trimmed.substring(0, colon));
      int minute = Integer.parseInt(trimmed.substring(colon + 1));
      if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
        return Optional.empty();
      }
      return Optional.of(LocalTime.of(hour, minute));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }
}
