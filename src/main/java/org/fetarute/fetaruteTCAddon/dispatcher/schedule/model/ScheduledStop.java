package org.fetarute.fetaruteTCAddon.dispatcher.schedule.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 车次计划中的一个停靠点。
 *
 * <p>该模型是静态计划输出，不保存列车当前索引、等待状态或实际延误。运行时的 current index、hold、delay 仍由 {@code
 * TrainRuntimeState}/{@code RouteProgressRegistry} 等运行时组件维护。
 *
 * @param stopSequence 停靠序号，使用 RouteStop 的 sequence 语义
 * @param stationCode 站点主数据 code；缺失时由节点或 DYNAMIC 站台兜底
 * @param nodeId 调度图节点 ID 或 DYNAMIC 解析出的 placeholder
 * @param plannedArrival 计划到达时间；无明确语义时为空
 * @param plannedDeparture 计划出发时间；通过 headway 骨干和 dwell 粗略派生
 * @param dwell 停站时间；RouteStop 未配置时为空
 * @param notes 原 RouteStop notes 或计划生成器补充说明
 */
public record ScheduledStop(
    int stopSequence,
    Optional<String> stationCode,
    Optional<String> nodeId,
    Optional<Instant> plannedArrival,
    Optional<Instant> plannedDeparture,
    Optional<Duration> dwell,
    Optional<String> notes) {

  public ScheduledStop {
    if (stopSequence < 0) {
      throw new IllegalArgumentException("stopSequence 不能为负");
    }
    stationCode = normalizeOptional(stationCode);
    nodeId = normalizeOptional(nodeId);
    plannedArrival = plannedArrival == null ? Optional.empty() : plannedArrival;
    plannedDeparture = plannedDeparture == null ? Optional.empty() : plannedDeparture;
    dwell = dwell == null ? Optional.empty() : dwell.filter(value -> !value.isNegative());
    notes = normalizeOptional(notes);
  }

  private static Optional<String> normalizeOptional(Optional<String> value) {
    if (value == null) {
      return Optional.empty();
    }
    return value.map(String::trim).filter(text -> !text.isBlank());
  }
}
