package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 占用事件的轻量等待列车查询器。
 *
 * <p>同步 {@link org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus} 发布发生在 {@code
 * OccupancyManager} 的状态提交边界内。 因此这里严格只读取已登记的 Gate Queue 条目：不得扫描 {@code
 * RouteProgressRegistry}、不得展开图路径、更不得运行 Dijkstra。尚未入队的前向候选由周期巡检
 * 在下一轮完整授权中处理；这以有限的巡检延迟换取主线程和占用事务的可收敛性。
 *
 * @see SignalEvaluator
 * @see OccupancyQueueSupport
 */
public final class RuntimeDispatchRequestProvider implements SignalEvaluator.WaitingTrainProvider {

  private final OccupancyManager occupancyManager;

  /**
   * 创建只读取 Gate Queue 的等待列车查询器。
   *
   * @param occupancyManager 占用管理器；实现 {@link OccupancyQueueSupport} 时可提供稳定队列快照
   */
  public RuntimeDispatchRequestProvider(OccupancyManager occupancyManager) {
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
  }

  /**
   * 返回已在变化资源上登记的等待列车。
   *
   * <p>返回的是 event bridge 可在同步发布期间安全读取的直接队首；每个资源只返回按 Gate Queue 仲裁顺序排列的第一名，结果按逻辑列车名去重， 不包含任何路由推断。
   *
   * @param resources 本次发生事实变化的资源
   * @return 已登记的逻辑列车名
   */
  @Override
  public List<String> trainsWaitingFor(List<OccupancyResource> resources) {
    if (resources == null
        || resources.isEmpty()
        || !(occupancyManager instanceof OccupancyQueueSupport queueSupport)) {
      return List.of();
    }
    Set<String> resourceKeys = new LinkedHashSet<>();
    for (OccupancyResource resource : resources) {
      if (resource != null) {
        resourceKeys.add(resource.key());
      }
    }
    if (resourceKeys.isEmpty()) {
      return List.of();
    }
    Set<String> waiting = new LinkedHashSet<>();
    for (OccupancyQueueSnapshot snapshot : queueSupport.snapshotQueues()) {
      if (snapshot == null
          || snapshot.resource() == null
          || !resourceKeys.contains(snapshot.resource().key())) {
        continue;
      }
      for (OccupancyQueueEntry entry : snapshot.entries()) {
        if (entry != null && entry.trainName() != null && !entry.trainName().isBlank()) {
          waiting.add(entry.trainName());
          break;
        }
      }
    }
    return new ArrayList<>(waiting);
  }
}
