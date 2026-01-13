package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.Optional;

/**
 * 调度占用/闭塞管理器：负责资源互斥与 headway 约束。
 *
 * <p>运行时层通过 {@link OccupancyRequest} 申请占用，调度层返回可进入时间与信号许可。
 */
public interface OccupancyManager {

  OccupancyDecision canEnter(OccupancyRequest request);

  OccupancyDecision acquire(OccupancyRequest request);

  int releaseByTrain(String trainName);

  boolean releaseResource(OccupancyResource resource, Optional<String> trainName);

  int cleanupExpired(Instant now);
}
