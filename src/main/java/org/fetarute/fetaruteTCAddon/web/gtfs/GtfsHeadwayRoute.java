package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;

/**
 * 一条按间隔发车（不按时刻表）的交路：发布成 {@code frequencies.txt} 的模板车次。
 *
 * @param routeId 交路 UUID
 * @param headwaySeconds 这条交路自己的发车间隔（秒）：线路或交路组的发车基准按交路权重分摊之后的值，同一交路有几个出车点时按频率合并
 * @param stops 跑一趟的站间时分（各节点相对起点发车的到发偏移），与编表同一个走行模型算出
 */
public record GtfsHeadwayRoute(UUID routeId, int headwaySeconds, List<TimetableStop> stops) {

  public GtfsHeadwayRoute {
    Objects.requireNonNull(routeId, "routeId");
    if (headwaySeconds <= 0) {
      throw new IllegalArgumentException("headwaySeconds 必须为正: " + headwaySeconds);
    }
    stops = stops == null ? List.of() : List.copyOf(stops);
  }
}
