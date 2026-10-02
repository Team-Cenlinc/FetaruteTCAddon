package org.fetarute.fetaruteTCAddon.company.model;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 车站组成员之间的换乘方式（相对乘客而言）。 */
public enum StationTransferType {

  /** 同台换乘：下车后在同一站台换乘。 */
  SAME_PLATFORM,

  /** 站内换乘：不出闸，经通道/楼梯换乘。 */
  IN_STATION,

  /** 出站换乘：需要出闸步行到另一座车站。 */
  OUT_OF_STATION;

  private static final Map<String, StationTransferType> TOKEN_MAP =
      Map.ofEntries(
          Map.entry("SAME_PLATFORM", SAME_PLATFORM),
          Map.entry("IN_STATION", IN_STATION),
          Map.entry("OUT_OF_STATION", OUT_OF_STATION),
          // 常用缩写
          Map.entry("SAME", SAME_PLATFORM),
          Map.entry("PLATFORM", SAME_PLATFORM),
          Map.entry("IN", IN_STATION),
          Map.entry("OUT", OUT_OF_STATION));

  /** 从命令/存储的字符串解析（兼容常用缩写，大小写不敏感）。 */
  public static Optional<StationTransferType> fromToken(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(TOKEN_MAP.get(normalized));
  }
}
