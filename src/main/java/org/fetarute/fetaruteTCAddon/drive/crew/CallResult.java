package org.fetarute.fetaruteTCAddon.drive.crew;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 呼叫信号员的结果：呼叫者据此得知有没有人接、为什么没人接。
 *
 * @param status 结果
 * @param zones 涉及的控制区名：接到呼叫的那一片（{@link Status#CALLED}、{@link
 *     Status#PENDING}）；列车不在任何值守的控制区时为它往后会经过的、有人值守的各片，按先后（{@link
 *     Status#OUT_OF_ZONE}，认不出交路或前方没有时为空）；其余为空
 */
public record CallResult(Status status, List<String> zones) {

  /** 提示里列出多片控制区时的分隔。 */
  static final String ZONE_SEPARATOR = " / ";

  /** 不在区内、但前方有值守的控制区时的提示键后缀（列出那几片，接近了再叫）。 */
  static final String AHEAD = "out-of-zone-ahead";

  /** 结果。 */
  public enum Status {
    /** 已转给信号员，排进待回复的呼叫（或换上了新写明的原因）。 */
    CALLED,
    /** 这名呼叫者已在等这片控制区的回复：没有再排一条。 */
    PENDING,
    /** 有信号员值守，但列车不在这些控制区内，也没有接近或在入口等候。 */
    OUT_OF_ZONE,
    /** 当前没有信号员值守。 */
    NO_SIGNALLER,
    /** 信号员功能未启用（没有接上信号员模块，或已在配置里关闭）。 */
    UNAVAILABLE;

    /** 有信号员接到了呼叫。 */
    public boolean reached() {
      return this == CALLED || this == PENDING;
    }

    /** 语言键里的写法（没人值守沿用原有的 {@code none}）。 */
    public String key() {
      return this == NO_SIGNALLER ? "none" : name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  public CallResult {
    Objects.requireNonNull(status, "status");
    zones = zones == null ? List.of() : List.copyOf(zones);
  }

  /** 不涉及控制区的结果（没人值守、功能未启用）。 */
  public static CallResult of(Status status) {
    return new CallResult(status, List.of());
  }

  /** 各种结果的提示键后缀：各结果的 {@link Status#key()}，加上前方有值守控制区时的那一种。 */
  public static List<String> keySuffixes() {
    List<String> suffixes = new ArrayList<>();
    for (Status status : Status.values()) {
      suffixes.add(status.key());
    }
    suffixes.add(AHEAD);
    return List.copyOf(suffixes);
  }

  /**
   * 给呼叫者的提示语言键：{@code <prefix>.<结果>}（called、pending、out-of-zone、none、unavailable）；不在区内但前方有值守的控制区时为
   * {@code <prefix>.out-of-zone-ahead}。
   */
  public String key(String prefix) {
    return prefix + "." + (status == Status.OUT_OF_ZONE && !zones.isEmpty() ? AHEAD : status.key());
  }

  /** 提示里的占位符：{@code <zone>} 接到呼叫的控制区，{@code <zones>} 前方有值守的各片。 */
  public Map<String, String> values() {
    return Map.of(
        "zone", zones.isEmpty() ? "" : zones.get(0), "zones", String.join(ZONE_SEPARATOR, zones));
  }
}
