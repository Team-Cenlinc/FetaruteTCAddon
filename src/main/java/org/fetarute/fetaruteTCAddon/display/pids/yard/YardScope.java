package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 站场图画哪一片：一个车站，或一个控制区的成员。值相等即同一片，站场几何按它缓存。
 *
 * @param operator 运营商代码（大写）
 * @param members 成员车站与车库，按控制区里的顺序
 */
public record YardScope(String operator, List<YardArea.Member> members) {

  public YardScope {
    operator = Objects.requireNonNull(operator, "operator").trim().toUpperCase(Locale.ROOT);
    members = List.copyOf(members);
  }

  /** 一个车站的站场。 */
  public static YardScope station(PidsStationKey station) {
    return new YardScope(
        station.operatorCode(), List.of(YardArea.Member.station(station.stationCode())));
  }
}
