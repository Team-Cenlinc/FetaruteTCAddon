package org.fetarute.fetaruteTCAddon.display.pids.announce;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 最近的站台变更：哪辆车在哪一站从哪个站台改到了哪个站台。
 *
 * <p>由公开事件 {@code TrainPlatformAssignedEvent} 喂入（已定的站台改了、或没能停到计划站台）。站台屏快照据此给行标上变更前的站台 （{@link
 * PidsRow#previousPlatform()}），站台屏显示“站台变更”，站台广播播报“改在 N 站台”。 只记最近一段时间：变更在车离站后就没有意义，过期即删。
 *
 * <p>按“车 + 车站”记，不按交路与停靠序号：折返站上站牌列的是这辆车接着开的下一趟（另一条交路的首站），车停的还是同一条股道， 进站前的变更对那一趟同样成立。
 */
public final class PidsPlatformChanges {

  /** 一条变更保留多久。 */
  static final Duration RETENTION = Duration.ofMinutes(15);

  private final Map<String, Change> changes = new ConcurrentHashMap<>();

  /** 一份空的变更记录（测试与未接入事件时）。每次新建：记录是可变的，不能共用一份。 */
  public static PidsPlatformChanges none() {
    return new PidsPlatformChanges();
  }

  /**
   * 记一条变更，顺带清掉过期的。股道不是车站站台（车库、区间点）时不记。
   *
   * @param trainName 列车名
   * @param nodeId 现在的股道节点
   * @param from 原站台（上一次定下的或计划的）
   * @param to 现在的站台
   * @param at 发生时刻
   */
  public void record(String trainName, String nodeId, String from, String to, Instant at) {
    Objects.requireNonNull(at, "at");
    changes.values().removeIf(change -> change.at().plus(RETENTION).isBefore(at));
    if (trainName == null || from == null || to == null || from.equals(to)) {
      return;
    }
    RouteTerminals.stationRefOfNode(nodeId)
        .map(ref -> new PidsStationKey(ref.operatorCode(), ref.stationCode()))
        .ifPresent(station -> changes.put(key(trainName, station), new Change(from, to, at)));
  }

  /** 这辆车在这一站的最近一次变更。 */
  public Optional<Change> find(String trainName, PidsStationKey station) {
    return Optional.ofNullable(changes.get(key(trainName, station)));
  }

  /** 这辆车在这一站改到 {@code platform} 之前的站台；最近一次变更不是改到这个站台（之后又改回、或站台牌已过时）时为空。 */
  public Optional<String> previousOf(String trainName, PidsStationKey station, String platform) {
    return find(trainName, station)
        .filter(change -> change.to().equals(platform))
        .map(Change::from);
  }

  /** 接过上一份记录（{@code /fta reload} 重建站台屏服务时），已有的同一键以本份为准。 */
  public void absorb(PidsPlatformChanges previous) {
    if (previous != null && previous != this) {
      previous.changes.forEach(changes::putIfAbsent);
    }
  }

  private static String key(String trainName, PidsStationKey station) {
    return String.valueOf(trainName).toLowerCase(Locale.ROOT) + "|" + station;
  }

  /**
   * 一条站台变更。
   *
   * @param from 原站台
   * @param to 现在的站台
   * @param at 发生时刻
   */
  public record Change(String from, String to, Instant at) {

    public Change {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      Objects.requireNonNull(at, "at");
    }
  }
}
