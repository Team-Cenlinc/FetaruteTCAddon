package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * 叫车按需票的车次标识（{@link SpawnTicket#serviceTripId()}）：{@code CALL-<叫车标签>[#<入路下标>]}。
 *
 * <p>叫车标签由叫车服务写、也由它读；发车侧只看入路下标：带着它的票在交路中途的区间点生成列车，从那个下标起跑， 不从车库出车、也不接首站的待命车。重载时票据原样迁移，标识跟着走。
 */
public final class OnDemandTrip {

  public static final String PREFIX = "CALL-";
  private static final char ENTRY_SEPARATOR = '#';

  private OnDemandTrip() {}

  /**
   * 写车次标识。
   *
   * @param callTag 叫车标签（不含 {@code #}）
   * @param entryIndex 区间生成的入路下标；不在区间生成时为空
   */
  public static String format(String callTag, OptionalInt entryIndex) {
    String base = PREFIX + (callTag == null ? "" : callTag.replace(ENTRY_SEPARATOR, '_'));
    return entryIndex != null && entryIndex.isPresent() && entryIndex.getAsInt() > 0
        ? base + ENTRY_SEPARATOR + entryIndex.getAsInt()
        : base;
  }

  /** 叫车标签；不是叫车票时为空。 */
  public static Optional<String> callTagOf(Optional<String> serviceTripId) {
    return serviceTripId
        .filter(id -> id.startsWith(PREFIX))
        .map(id -> id.substring(PREFIX.length()))
        .map(
            rest -> {
              int at = rest.indexOf(ENTRY_SEPARATOR);
              return at < 0 ? rest : rest.substring(0, at);
            })
        .filter(tag -> !tag.isBlank());
  }

  /** 区间生成的入路下标；不是叫车票、或不在区间生成时为空。 */
  public static OptionalInt entryIndexOf(Optional<String> serviceTripId) {
    if (serviceTripId == null || serviceTripId.isEmpty()) {
      return OptionalInt.empty();
    }
    String id = serviceTripId.get();
    if (!id.startsWith(PREFIX)) {
      return OptionalInt.empty();
    }
    int at = id.lastIndexOf(ENTRY_SEPARATOR);
    if (at < 0 || at == id.length() - 1) {
      return OptionalInt.empty();
    }
    try {
      int index = Integer.parseInt(id.substring(at + 1));
      return index > 0 ? OptionalInt.of(index) : OptionalInt.empty();
    } catch (NumberFormatException ex) {
      return OptionalInt.empty();
    }
  }

  /** 去掉入路下标：区间生成一直不成时改走交路本来的车源。 */
  public static Optional<String> withoutEntry(Optional<String> serviceTripId) {
    return serviceTripId.map(
        id -> {
          if (!id.startsWith(PREFIX)) {
            return id;
          }
          int at = id.lastIndexOf(ENTRY_SEPARATOR);
          return at < 0 ? id : id.substring(0, at);
        });
  }
}
