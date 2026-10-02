package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一次已确认的车站停靠事件（到达或发车）。
 *
 * <p>这是运行时向外暴露的只读事实，不携带任何控车语义：观察者不能据此改变列车状态，也不能阻塞调度路径。 事件里同时给出 {@code routeUuid} 与 {@code
 * routeKey}，是因为只有前者能关联业务主数据，而仅由 code 定义的交路没有 UUID，此时仍需 key 用于诊断输出。
 *
 * @param trainName 规范列车名
 * @param routeUuid 交路 UUID；仅 code 定义的交路为空
 * @param routeKey 交路 key（{@code RouteId.value()}），始终存在
 * @param stopIndex 本次事件对应的交路索引
 * @param stopCount 交路的节点总数，用于判断是否已到末站
 * @param nodeId 实际停靠的调度图节点
 * @param at 事件发生的现实时间，取自调度层时钟
 */
public record StationStopEvent(
    String trainName,
    Optional<UUID> routeUuid,
    String routeKey,
    int stopIndex,
    int stopCount,
    String nodeId,
    Instant at) {

  public StationStopEvent {
    trainName = trainName == null ? "" : trainName.trim();
    routeUuid = routeUuid == null ? Optional.empty() : routeUuid;
    routeKey = routeKey == null ? "" : routeKey.trim();
    nodeId = nodeId == null ? "" : nodeId.trim();
    Objects.requireNonNull(at, "at");
  }

  /** 是否为交路的最后一个节点。 */
  public boolean terminal() {
    return stopCount > 0 && stopIndex >= stopCount - 1;
  }
}
