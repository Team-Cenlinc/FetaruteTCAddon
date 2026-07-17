package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 折返原子交接后的旧车体防护生命周期。
 *
 * <p>原子交接会把新方向进路写成 hard authority，并把未被新请求覆盖的旧进路保留为物理 footprint 硬占用。与新窗口重叠的旧资源虽然会在账本中成为新的 movement
 * claim，也仍可能压在折返车体下方。周期信号刷新只能收缩普通前瞻窗口，不能据此证明列尾已经离开旧进路；本注册表把 handoff 前的完整旧 footprint 作为 transient
 * sidecar 保存，并且只接受已登记有向路径上的连续有向推进作为释放证据。
 *
 * <p>列尾清空阈值使用方块距离而不是节点数或时间：至少包含列车长度估计，再叠加 {@code rearGuardEdges}
 * 对应的前向边距离。倒退会同步回退净清空进度；跳点、路径外节点和不可靠的长度输入一律转为 fail-retain，不能缩短防护。
 *
 * <p>同一列车连续折返时，每次 handoff 使用独立 traversal epoch。新 handoff 会封存所有旧 epoch，后续节点只推进最新
 * epoch；否则新支路重新汇入旧路径的同名节点时， 旧 epoch 会把错误方向的里程当作列尾清空证据。每个 active epoch 只接受登记路径上的相邻有向节点；跳点、路径外节点会令该
 * epoch fail-retain，倒退则回退当前进度。 已完成 epoch 只能释放未被其他 epoch 引用的资源，因此共享咽喉不会提前释放。
 */
final class TurnbackFootprintGuardRegistry {

  private static final double DISTANCE_EPSILON = 1.0e-6;

  private final Map<String, List<Guard>> guards = new HashMap<>();

  /**
   * 兼容旧调用方的保守注册入口。
   *
   * <p>节点边界数不能证明列尾已经清空，因此该入口只保留资源，不会根据后续节点计数释放。调用方应改用带有向路径和列车长度的重载。
   */
  void register(
      String trainName, NodeId handoffNode, Set<OccupancyResource> resources, int rearGuardEdges) {
    registerInternal(trainName, resources, Optional.empty());
  }

  /**
   * 依据列车长度和配置的列尾边数登记折返防护。
   *
   * <p>释放阈值为“列车长度估计 + 从交接节点起前 {@code max(1, rearGuardEdges)}
   * 条边的长度”。只要长度缺失、路径不连续、存在回环、边长无效或路径不足以覆盖阈值，就采用 fail-retain，不产生释放证据。
   *
   * @param trainName 当前列车名
   * @param handoffNode 折返交接发生的图节点
   * @param resources handoff 前由列车持有的旧进路资源；其中旧窗口独有资源会是物理 footprint 角色，重叠资源可能已成为 movement 角色
   * @param forwardPath 从交接节点开始的有向前进边
   * @param estimatedTrainLengthBlocks 保守的列车总长估计；缺失表示无法安全释放
   * @param rearGuardEdges 列尾离开后仍需额外跨越的配置边数
   */
  void register(
      String trainName,
      NodeId handoffNode,
      Set<OccupancyResource> resources,
      List<ForwardPathEdge> forwardPath,
      OptionalDouble estimatedTrainLengthBlocks,
      int rearGuardEdges) {
    Optional<RearClearPlan> plan =
        buildRearClearPlan(handoffNode, forwardPath, estimatedTrainLengthBlocks, rearGuardEdges);
    registerInternal(trainName, resources, plan);
  }

  /**
   * 使用调用方已经计算好的列尾清空距离登记折返防护。
   *
   * <p>此入口适合调度层已经把列车长度、编组余量和线路专用安全距离统一合并的场景。所给距离必须为有限正数，且登记路径必须能够覆盖该距离，否则采用 fail-retain。
   */
  void register(
      String trainName,
      NodeId handoffNode,
      Set<OccupancyResource> resources,
      List<ForwardPathEdge> forwardPath,
      double requiredClearDistanceBlocks) {
    registerInternal(
        trainName,
        resources,
        RearClearPlan.create(handoffNode, forwardPath, requiredClearDistanceBlocks));
  }

  /** 返回必须并入通用 shrink 保护集的资源。 */
  Set<OccupancyResource> protectedResources(String trainName) {
    String key = keyOf(trainName);
    if (key == null) {
      return Set.of();
    }
    synchronized (guards) {
      List<Guard> epochs = guards.get(key);
      if (epochs == null || epochs.isEmpty()) {
        return Set.of();
      }
      Set<OccupancyResource> resources = new LinkedHashSet<>();
      for (Guard epoch : epochs) {
        resources.addAll(epoch.resources());
      }
      return Set.copyOf(resources);
    }
  }

  /** 返回指定列车是否仍有待列尾证据释放的保护状态。 */
  boolean contains(String trainName) {
    String key = keyOf(trainName);
    if (key == null) {
      return false;
    }
    synchronized (guards) {
      return guards.containsKey(key);
    }
  }

  /**
   * 记录一次真实图节点推进。
   *
   * <p>相邻前向节点推进进度，重复节点保持不变，倒退节点回退净清空距离。跳点或路径外节点无法证明实际走过登记的有向子段，会封存当前 epoch 并保持资源占用。
   *
   * @return 当累计前进距离达到列尾清空阈值时，返回可解除 sidecar 保护的旧资源；调用方只立即释放其中仍为 {@code PHYSICAL_FOOTPRINT} 的
   *     claim，重叠 movement claim 交给后续正常窗口收缩
   */
  Optional<Release> observeProgress(String trainName, NodeId observedNode) {
    String key = keyOf(trainName);
    if (key == null || observedNode == null) {
      return Optional.empty();
    }
    synchronized (guards) {
      List<Guard> current = guards.get(key);
      if (current == null || current.isEmpty()) {
        return Optional.empty();
      }
      List<Guard> remaining = new ArrayList<>(current.size());
      Set<OccupancyResource> completedResources = new LinkedHashSet<>();
      for (Guard epoch : current) {
        if (!epoch.active() || epoch.plan().isEmpty()) {
          remaining.add(epoch);
          continue;
        }
        RearClearPlan plan = epoch.plan().orElseThrow();
        int observedIndex = plan.indexOf(observedNode);
        if (observedIndex == epoch.currentNodeIndex()) {
          remaining.add(epoch);
          continue;
        }
        if (observedIndex < 0 || observedIndex > epoch.currentNodeIndex() + 1) {
          // 单独命中后续汇流点不能证明走过登记的有向子段；保守封存本 epoch。
          remaining.add(epoch.withActive(false));
          continue;
        }
        if (observedIndex < epoch.currentNodeIndex()) {
          // 真实倒退会缩短列尾净清空距离，必须同步回退，而不是保留历史最大值。
          remaining.add(new Guard(epoch.plan(), observedIndex, epoch.resources(), true));
          continue;
        }
        double observedDistance = plan.observations().get(observedIndex).cumulativeDistanceBlocks();
        if (observedDistance + DISTANCE_EPSILON < plan.requiredClearDistanceBlocks()) {
          remaining.add(new Guard(epoch.plan(), observedIndex, epoch.resources(), true));
          continue;
        }
        completedResources.addAll(epoch.resources());
      }
      if (completedResources.isEmpty()) {
        guards.put(key, List.copyOf(remaining));
        return Optional.empty();
      }
      if (remaining.isEmpty()) {
        guards.remove(key);
      } else {
        guards.put(key, List.copyOf(remaining));
      }
      for (Guard epoch : remaining) {
        completedResources.removeAll(epoch.resources());
      }
      return completedResources.isEmpty()
          ? Optional.empty()
          : Optional.of(new Release(completedResources));
    }
  }

  /** 迁移列车名；源列车没有 guard 时视为成功的 no-op。 */
  boolean rename(String currentTrainName, String nextTrainName) {
    String currentKey = keyOf(currentTrainName);
    String nextKey = keyOf(nextTrainName);
    if (currentKey == null || nextKey == null) {
      return false;
    }
    if (currentKey.equals(nextKey)) {
      return true;
    }
    synchronized (guards) {
      List<Guard> current = guards.remove(currentKey);
      if (current == null) {
        return true;
      }
      if (guards.containsKey(nextKey)) {
        guards.put(currentKey, current);
        return false;
      }
      guards.put(nextKey, current);
      return true;
    }
  }

  /** 清除列车的 transient guard；列车移除路径会另行释放其全部占用。 */
  void clear(String trainName) {
    String key = keyOf(trainName);
    if (key == null) {
      return;
    }
    synchronized (guards) {
      guards.remove(key);
    }
  }

  private void registerInternal(
      String trainName, Set<OccupancyResource> resources, Optional<RearClearPlan> plan) {
    String key = keyOf(trainName);
    if (key == null || resources == null || resources.isEmpty()) {
      return;
    }
    Set<OccupancyResource> immutableResources = Set.copyOf(resources);
    Optional<RearClearPlan> normalizedPlan = plan == null ? Optional.empty() : plan;
    synchronized (guards) {
      List<Guard> existing = guards.getOrDefault(key, List.of());
      List<Guard> epochs = new ArrayList<>(existing.size() + 1);
      existing.stream().map(epoch -> epoch.withActive(false)).forEach(epochs::add);
      epochs.add(new Guard(normalizedPlan, 0, immutableResources, true));
      guards.put(key, List.copyOf(epochs));
    }
  }

  private static Optional<RearClearPlan> buildRearClearPlan(
      NodeId handoffNode,
      List<ForwardPathEdge> forwardPath,
      OptionalDouble estimatedTrainLengthBlocks,
      int rearGuardEdges) {
    if (estimatedTrainLengthBlocks == null || estimatedTrainLengthBlocks.isEmpty()) {
      return Optional.empty();
    }
    double trainLength = estimatedTrainLengthBlocks.getAsDouble();
    if (!Double.isFinite(trainLength) || trainLength <= 0.0 || forwardPath == null) {
      return Optional.empty();
    }
    int guardEdgeCount = Math.max(1, rearGuardEdges);
    if (forwardPath.size() < guardEdgeCount) {
      return Optional.empty();
    }
    double rearGuardDistance = 0.0;
    for (int i = 0; i < guardEdgeCount; i++) {
      ForwardPathEdge edge = forwardPath.get(i);
      if (edge == null || !Double.isFinite(edge.lengthBlocks()) || edge.lengthBlocks() <= 0.0) {
        return Optional.empty();
      }
      rearGuardDistance += edge.lengthBlocks();
      if (!Double.isFinite(rearGuardDistance)) {
        return Optional.empty();
      }
    }
    double requiredDistance = trainLength + rearGuardDistance;
    return RearClearPlan.create(handoffNode, forwardPath, requiredDistance);
  }

  private static String keyOf(String trainName) {
    String key = TrainNameNormalizer.normalizeKey(trainName);
    return key.isEmpty() ? null : key;
  }

  /** 登记路径中的一条有向边；方向必须与折返后列车的前进方向一致。 */
  record ForwardPathEdge(NodeId fromNode, NodeId toNode, double lengthBlocks) {}

  private record PathObservation(NodeId node, double cumulativeDistanceBlocks) {}

  private record RearClearPlan(
      List<PathObservation> observations, double requiredClearDistanceBlocks) {

    private RearClearPlan {
      observations = List.copyOf(observations);
    }

    private static Optional<RearClearPlan> create(
        NodeId handoffNode, List<ForwardPathEdge> forwardPath, double requiredClearDistanceBlocks) {
      if (handoffNode == null
          || forwardPath == null
          || forwardPath.isEmpty()
          || !Double.isFinite(requiredClearDistanceBlocks)
          || requiredClearDistanceBlocks <= 0.0) {
        return Optional.empty();
      }
      List<PathObservation> observations = new ArrayList<>();
      Set<NodeId> observedNodes = new HashSet<>();
      observations.add(new PathObservation(handoffNode, 0.0));
      observedNodes.add(handoffNode);
      NodeId expectedFrom = handoffNode;
      double cumulativeDistance = 0.0;
      for (ForwardPathEdge edge : forwardPath) {
        boolean invalidEdge =
            edge == null
                || edge.fromNode() == null
                || edge.toNode() == null
                || !expectedFrom.equals(edge.fromNode())
                || !Double.isFinite(edge.lengthBlocks())
                || edge.lengthBlocks() <= 0.0
                || observedNodes.contains(edge.toNode());
        if (invalidEdge) {
          if (cumulativeDistance + DISTANCE_EPSILON >= requiredClearDistanceBlocks) {
            break;
          }
          return Optional.empty();
        }
        observedNodes.add(edge.toNode());
        cumulativeDistance += edge.lengthBlocks();
        if (!Double.isFinite(cumulativeDistance)) {
          return Optional.empty();
        }
        observations.add(new PathObservation(edge.toNode(), cumulativeDistance));
        expectedFrom = edge.toNode();
      }
      if (cumulativeDistance + DISTANCE_EPSILON < requiredClearDistanceBlocks) {
        return Optional.empty();
      }
      return Optional.of(new RearClearPlan(observations, requiredClearDistanceBlocks));
    }

    private int indexOf(NodeId node) {
      for (int i = 0; i < observations.size(); i++) {
        if (observations.get(i).node().equals(node)) {
          return i;
        }
      }
      return -1;
    }
  }

  private record Guard(
      Optional<RearClearPlan> plan,
      int currentNodeIndex,
      Set<OccupancyResource> resources,
      boolean active) {
    private Guard {
      plan = plan == null ? Optional.empty() : plan;
      currentNodeIndex = Math.max(0, currentNodeIndex);
      resources = resources == null ? Set.of() : Set.copyOf(resources);
    }

    private Guard withActive(boolean nextActive) {
      return active == nextActive ? this : new Guard(plan, currentNodeIndex, resources, nextActive);
    }
  }

  /** 已取得列尾离开旧进路证据、可解除 sidecar 保护的 handoff 前旧资源集合。 */
  record Release(Set<OccupancyResource> resources) {
    Release {
      resources = resources == null ? Set.of() : Set.copyOf(resources);
    }
  }
}
