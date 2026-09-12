package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 占用事件的轻量等待列车查询器。
 *
 * <p>同步 {@link org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus} 发布发生在 {@code
 * OccupancyManager} 的状态提交边界内。因此这里仅查询已登记的 Gate Queue 队首与动态容量通知索引：不得扫描 {@code
 * RouteProgressRegistry}、不得展开图路径、更不得运行 Dijkstra。容量等待者没有队列位次，唤醒后仍须通过下一 tick 的完整授权。
 *
 * @see SignalEvaluator
 * @see OccupancyQueueSupport
 */
public final class RuntimeDispatchRequestProvider implements SignalEvaluator.WaitingTrainProvider {

  private final OccupancyManager occupancyManager;
  private final Function<List<OccupancyResource>, List<String>> capacityWaiters;

  /**
   * 创建只读取 Gate Queue 的等待列车查询器。
   *
   * @param occupancyManager 占用管理器；实现 {@link OccupancyQueueSupport} 时可提供稳定队列快照
   */
  public RuntimeDispatchRequestProvider(OccupancyManager occupancyManager) {
    this(occupancyManager, resources -> List.of());
  }

  /**
   * 合并普通队首与独立登记的动态容量等待者。
   *
   * @param occupancyManager 占用管理器
   * @param capacityWaiters 只读取资源通知索引的查询器，不得执行寻路或授权
   */
  public RuntimeDispatchRequestProvider(
      OccupancyManager occupancyManager,
      Function<List<OccupancyResource>, List<String>> capacityWaiters) {
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.capacityWaiters = Objects.requireNonNull(capacityWaiters, "capacityWaiters");
  }

  /**
   * 返回已在变化资源上登记的等待列车。
   *
   * <p>先返回每个变化资源的直接队首，再合并显式登记的容量等待者。此结果只安排重评估，不代表 winner，不包含任何路由推断。
   *
   * @param resources 本次发生事实变化的资源
   * @return 已登记的逻辑列车名
   */
  @Override
  public List<String> trainsWaitingFor(List<OccupancyResource> resources) {
    if (resources == null || resources.isEmpty()) {
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
    List<OccupancyQueueSnapshot> queues =
        occupancyManager instanceof OccupancyQueueSupport queueSupport
            ? queueSupport.snapshotQueues()
            : List.of();
    for (OccupancyQueueSnapshot snapshot : queues) {
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
    waiting.addAll(capacityWaiters.apply(resources));
    return new ArrayList<>(waiting);
  }
}
