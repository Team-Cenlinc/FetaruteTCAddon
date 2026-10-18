package org.fetarute.fetaruteTCAddon.company.model;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 控制区成员：一个车站或一个车库，按代码记录。
 *
 * <p>车库在库里没有实体（只是调度图里 {@code 运营商:D:库:股道} 形式的节点），所以成员按“种类 + 代码”记，不连外键；
 * 车站改名或删掉时成员不会自动跟着变，显示时找不到的成员直接略过。
 *
 * @param zoneId 所属控制区
 * @param kind 种类
 * @param code 站码或车库代码（大写）
 * @param sortOrder 排序
 */
public record ControlZoneMember(UUID zoneId, Kind kind, String code, int sortOrder) {

  public ControlZoneMember {
    Objects.requireNonNull(zoneId, "zoneId");
    Objects.requireNonNull(kind, "kind");
    code = Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
  }

  /** 成员种类。 */
  public enum Kind {
    STATION,
    DEPOT;

    /** 解析命令或库里的写法（{@code station}、{@code depot}，不分大小写）。 */
    public static Optional<Kind> fromToken(String token) {
      if (token == null) {
        return Optional.empty();
      }
      return switch (token.trim().toUpperCase(Locale.ROOT)) {
        case "STATION" -> Optional.of(STATION);
        case "DEPOT" -> Optional.of(DEPOT);
        default -> Optional.empty();
      };
    }
  }
}
