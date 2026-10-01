package org.fetarute.fetaruteTCAddon.display.pids;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 站台节点 {@code 运营商:S:站码:股道}。咽喉（5 段）、车库（{@code D}）与区间点都不是站台。
 *
 * @param station 车站
 * @param platform 站台号（股道）
 */
public record PidsPlatformNode(PidsStationKey station, String platform) {

  private static final Pattern NUMERIC = Pattern.compile("\\+?\\d{1,9}");

  /** 站台号排序：纯数字按数值，其余按字典序且排在数字之后。 */
  public static final Comparator<String> PLATFORM_ORDER =
      Comparator.comparing((String p) -> !p.matches("\\d{1,9}"))
          .thenComparing(p -> p.matches("\\d{1,9}") ? Integer.parseInt(p) : 0)
          .thenComparing(Comparator.naturalOrder());

  public PidsPlatformNode {
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(platform, "platform");
  }

  /** 解析节点 ID；不是站台节点时为空。 */
  public static Optional<PidsPlatformNode> parse(String nodeId) {
    if (nodeId == null) {
      return Optional.empty();
    }
    String[] parts = nodeId.split(":", -1);
    if (parts.length != 4
        || !"S".equalsIgnoreCase(parts[1])
        || parts[0].isBlank()
        || parts[2].isBlank()
        || parts[3].isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new PidsPlatformNode(new PidsStationKey(parts[0], parts[2]), normalize(parts[3])));
  }

  /** 站台号规整：牌子上写成 {@code 01}、{@code +1} 的股道按数值写成 {@code 1}，与到发行里的站台号同一写法；非数字原样保留。 */
  public static String normalize(String platform) {
    String trimmed = platform.trim();
    return NUMERIC.matcher(trimmed).matches()
        ? Integer.toString(Integer.parseInt(trimmed))
        : trimmed;
  }
}
