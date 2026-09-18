package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;

/**
 * 一条 route 在时刻表里的计划：站间时分档案 + 目标服务比例。
 *
 * <p>时刻表以<b>线路</b>为单位构建，一条线路下可能有多条 route（上行/下行/快慢车），它们的站序和时分各不相同。 所以站间时分挂在 route 上而不是挂在整份时刻表上——把多条
 * route 的时分揉成一套 profile 会得到一张 谁都对不上的表。
 *
 * <p>{@code kind} 区分三类 route：{@code OPERATION} 进发车表并按 weight 分配份额；{@code CREATE}（车库 → 首站）与 {@code
 * RETURN}（末站 → 车库）不进发车表，只提供车辆交路两端的走行时分与端点。它们同样从路网算出来，不用固定的估计值——回库段用常数顶上， 就会让"每辆车都回库"这条不变量在时间上是假的。
 *
 * <p>{@code weight} 在这里是<b>目标服务比例</b>，不是抽签概率；具体如何转成班次由 {@link WeightedTripAllocator} 决定。
 * CREATE/RETURN 的 weight 恒为 0。
 *
 * @param routeId Route UUID
 * @param routeCode Route code，同时用作确定性排序键
 * @param kind route 类型
 * @param weight 目标服务比例权重
 * @param stops 站间时分档案，相对本 route 首站发车的秒偏移
 * @param originNodeId 起点节点
 * @param terminalNodeId 终点节点
 * @param depotNodeId 出库点；为空时由线路 depot pool 决定
 * @param notes 构建期说明（例如为什么这条 route 被降级）
 */
public record TimetableRoutePlan(
    UUID routeId,
    String routeCode,
    RouteOperationType kind,
    int weight,
    List<TimetableStop> stops,
    String originNodeId,
    String terminalNodeId,
    Optional<String> depotNodeId,
    Optional<String> notes) {

  public TimetableRoutePlan {
    Objects.requireNonNull(routeId, "routeId");
    routeCode = routeCode == null ? "" : routeCode.trim();
    if (routeCode.isBlank()) {
      throw new IllegalArgumentException("routeCode 不能为空");
    }
    kind = kind == null ? RouteOperationType.OPERATION : kind;
    if (weight < 0) {
      throw new IllegalArgumentException("weight 不能为负");
    }
    if (kind != RouteOperationType.OPERATION) {
      weight = 0;
    }
    stops =
        stops == null
            ? List.of()
            : stops.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(TimetableStop::stopSequence))
                .toList();
    originNodeId = originNodeId == null ? "" : originNodeId.trim();
    terminalNodeId = terminalNodeId == null ? "" : terminalNodeId.trim();
    depotNodeId =
        depotNodeId == null
            ? Optional.empty()
            : depotNodeId.map(String::trim).filter(s -> !s.isBlank());
    notes = notes == null ? Optional.empty() : notes.map(String::trim).filter(s -> !s.isBlank());
  }

  /** 运营 route 的便捷构造。 */
  public TimetableRoutePlan(
      UUID routeId,
      String routeCode,
      int weight,
      List<TimetableStop> stops,
      String originNodeId,
      String terminalNodeId,
      Optional<String> depotNodeId,
      Optional<String> notes) {
    this(
        routeId,
        routeCode,
        RouteOperationType.OPERATION,
        weight,
        stops,
        originNodeId,
        terminalNodeId,
        depotNodeId,
        notes);
  }

  /** 是否是进发车表的运营 route。 */
  public boolean operation() {
    return kind == RouteOperationType.OPERATION;
  }

  /** 全程时分（首站发车 → 末站到达），秒。 */
  public int totalRunSeconds() {
    return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).arrivalOffsetSeconds();
  }

  /** 按 stopSequence 查档案。 */
  public Optional<TimetableStop> stopAt(int stopSequence) {
    for (TimetableStop stop : stops) {
      if (stop.stopSequence() == stopSequence) {
        return Optional.of(stop);
      }
    }
    return Optional.empty();
  }
}
