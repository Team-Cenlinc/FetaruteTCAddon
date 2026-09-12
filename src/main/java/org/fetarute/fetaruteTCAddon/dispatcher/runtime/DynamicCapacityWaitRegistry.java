package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 尚未取得动态目标容量的列车通知索引。
 *
 * <p>容量等待不能占据出站列车所需的 Gate Queue，因此单独登记下一动态目标的候选 NODE。资源释放事件只查本索引并请求下一 tick
 * 重评估，不计算路径、不修改占用，也不授予排队优先权。每车只保留当前交路窗口的一份登记；推进、选台成功、移除和 owner 移交会同步更新它。
 * 当前索引决定窗口是否失效，受阻目标索引决定何时撤销容量通知；提前选台时二者可能隔着中间 PASS。
 *
 * <p>两个索引在同一锁内更新；本类不回调运行时或占用管理器，避免同步事件发布期间产生锁反转。
 */
final class DynamicCapacityWaitRegistry {

  private final Map<String, Registration> byTrain = new HashMap<>();
  private final Map<OccupancyResource, Set<String>> byResource = new HashMap<>();

  /** 替换当前下一目标的通知集合；空集合表示不存在可监听的候选容量。 */
  synchronized void register(
      String trainName,
      RouteId routeId,
      int currentIndex,
      int targetIndex,
      Collection<OccupancyResource> resources) {
    String key = TrainNameNormalizer.normalizeKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    Registration registration =
        new Registration(
            trainName.trim(), routeId, currentIndex, targetIndex, Set.copyOf(resources));
    if (registration.equals(byTrain.get(key))) {
      return;
    }
    remove(trainName);
    if (registration.resources().isEmpty()) {
      return;
    }
    byTrain.put(key, registration);
    for (OccupancyResource resource : registration.resources()) {
      byResource.computeIfAbsent(resource, ignored -> new LinkedHashSet<>()).add(key);
    }
  }

  /** 返回变化资源上显式登记的列车；查询成本只与命中资源及等待者数量相关。 */
  synchronized List<String> trainsWaitingFor(List<OccupancyResource> resources) {
    if (resources == null || resources.isEmpty()) {
      return List.of();
    }
    Set<String> waiting = new LinkedHashSet<>();
    for (OccupancyResource resource : resources) {
      for (String key : byResource.getOrDefault(resource, Set.of())) {
        waiting.add(byTrain.get(key).trainName());
      }
    }
    return List.copyOf(waiting);
  }

  /** 交路或当前推进索引改变后，丢弃旧窗口的等待通知。 */
  synchronized void retainCurrentWindow(String trainName, RouteId routeId, int currentIndex) {
    Registration registration = byTrain.get(TrainNameNormalizer.normalizeKey(trainName));
    if (registration != null
        && (!registration.routeId().equals(routeId)
            || registration.currentIndex() != currentIndex)) {
      remove(trainName);
    }
  }

  /** 目标已 materialize 时撤销容量通知；随后的进路等待由普通 Gate Queue 负责。 */
  synchronized void targetResolved(String trainName, RouteId routeId, int targetIndex) {
    Registration registration = byTrain.get(TrainNameNormalizer.normalizeKey(trainName));
    if (registration != null
        && registration.routeId().equals(routeId)
        && registration.targetIndex() == targetIndex) {
      remove(trainName);
    }
  }

  /** 清理列车及其反向资源索引，避免已离线列车被后续释放事件唤醒。 */
  synchronized void remove(String trainName) {
    String key = TrainNameNormalizer.normalizeKey(trainName);
    Registration removed = byTrain.remove(key);
    if (removed == null) {
      return;
    }
    for (OccupancyResource resource : removed.resources()) {
      Set<String> waiters = byResource.get(resource);
      waiters.remove(key);
      if (waiters.isEmpty()) {
        byResource.remove(resource);
      }
    }
  }

  /** 仅在运行时 owner 移交提交后调用；迁移通知接收者，不签发或转移授权。 */
  synchronized void rename(String previousTrainName, String currentTrainName) {
    Registration previous = byTrain.get(TrainNameNormalizer.normalizeKey(previousTrainName));
    if (previous == null) {
      return;
    }
    remove(previousTrainName);
    register(
        currentTrainName,
        previous.routeId(),
        previous.currentIndex(),
        previous.targetIndex(),
        previous.resources());
  }

  /** 一个交路窗口内的不可变通知登记，不包含占用请求或 Movement Authority。 */
  private record Registration(
      String trainName,
      RouteId routeId,
      int currentIndex,
      int targetIndex,
      Set<OccupancyResource> resources) {
    private Registration {
      Objects.requireNonNull(routeId, "routeId");
      resources = Set.copyOf(resources);
    }
  }
}
