package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.HashMap;
import java.util.Map;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.DynamicTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;

/**
 * 编表用的边限速：只认<b>永久</b>的运维限速覆盖（{@code fta_rail_edge_overrides.speed_limit_bps}）。
 *
 * <p>图快照里的边基础限速多半是 0（牌子没写），运维实际是用覆盖表把线路限到 16–22 bps 的；不看覆盖表，时分就按默认 8 bps 算，
 * 全线慢两到三倍，表上的每个时刻都失真。临时限速与封锁是运行时的事：它们带截止时刻，进了表就让"同一份网络状态"依赖构建那一刻， 违反确定性，所以这里一律不看。
 */
public final class TimetableEdgeSpeeds {

  private TimetableEdgeSpeeds() {}

  /** 由某个世界的覆盖表得到解析器；覆盖表为空时退化成"边基础限速，没有就用默认速度"。 */
  public static DynamicTravelTimeModel.EdgeSpeedResolver resolver(
      Map<EdgeId, RailEdgeOverrideRecord> overrides) {
    Map<EdgeId, Double> permanent = new HashMap<>();
    if (overrides != null) {
      overrides.forEach(
          (edgeId, record) -> {
            if (edgeId != null
                && record != null
                && record.speedLimitBlocksPerSecond().isPresent()) {
              double limit = record.speedLimitBlocksPerSecond().getAsDouble();
              if (Double.isFinite(limit) && limit > 0.0D) {
                permanent.put(EdgeId.undirected(edgeId.a(), edgeId.b()), limit);
              }
            }
          });
    }
    Map<EdgeId, Double> frozen = Map.copyOf(permanent);
    return (graph, edge, fallback) -> {
      double base = edge.baseSpeedLimit();
      double normal = Double.isFinite(base) && base > 0.0D ? base : fallback;
      if (edge.id() == null) {
        return normal;
      }
      return frozen.getOrDefault(EdgeId.undirected(edge.id().a(), edge.id().b()), normal);
    };
  }
}
