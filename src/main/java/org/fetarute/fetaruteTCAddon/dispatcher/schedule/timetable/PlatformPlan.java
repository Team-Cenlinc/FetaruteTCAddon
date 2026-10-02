package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.Objects;
import java.util.UUID;

/**
 * 时刻表里一趟车在某个动态站台（DYNAMIC）停靠的计划股道。
 *
 * <p>编表时按站台组容量排出，运行时作为选台偏好：计划股道空闲就选它，不空闲照常改选并播报站台变更。车次每天按同一张表跑， 所以计划不分服务日。
 *
 * @param tripId 车次
 * @param stopSequence 停靠序号（交路节点的 0 起下标）
 * @param nodeId 计划股道节点
 */
public record PlatformPlan(UUID tripId, int stopSequence, String nodeId) {

  public PlatformPlan {
    Objects.requireNonNull(tripId, "tripId");
    Objects.requireNonNull(nodeId, "nodeId");
    if (stopSequence < 0) {
      throw new IllegalArgumentException("stopSequence 不能为负: " + stopSequence);
    }
  }
}
