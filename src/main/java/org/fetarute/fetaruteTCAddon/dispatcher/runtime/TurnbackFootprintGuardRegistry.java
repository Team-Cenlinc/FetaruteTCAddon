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
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.utils.StableCollections;

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
 *
 * <p><b>后备释放：有序路线到达。</b>连续节点事件是唯一证据时，fail-retain 没有出口：旧站台节点与咽喉道岔会一直被占到车被销毁，
 * 期间后车进不了该站、发车许可锁只能等安全超时。该 epoch 可能是"登记时没有连续路径计划"，也可能是"被越界节点事件封存"，两条路径 都有诊断（{@code
 * TURNBACK_FOOTPRINT_GUARD_NO_PLAN} / {@code _SEALED}），不假定其中一条。后备证据：每个 epoch 另存一份"交路 ID + 路线下标 →
 * 沿登记前进路径累计距离" （{@link RouteEvidence}）；列车按序到达同一条交路的下标 k（路径点到达事件由运行时逐个推进，节点名必须对得上），且累计距离达到（车长 +
 * 车尾保护边距 + {@value #FAR_CLEAR_MARGIN_BLOCKS} 格余量）时，车尾必然已经离开旧进路，无论连续节点事件是否成立都可以解除。车长未知、没有路线证据的 epoch
 * 仍然 fail-retain。
 */
final class TurnbackFootprintGuardRegistry {

  private static final double DISTANCE_EPSILON = 1.0e-6;

  /** 后备释放在原阈值（车长 + 车尾保护边距）之上多要求的余量：路线到达点是站台中心，车头越过节点约半个车长。 */
  static final double FAR_CLEAR_MARGIN_BLOCKS = 32.0;

  private final Map<String, List<Guard>> guards = new HashMap<>();

  /** 封存、无计划与后备释放的诊断出口；运行时接调试日志，测试里默认丢弃。 */
  private volatile Consumer<String> diagnostics = message -> {};

  TurnbackFootprintGuardRegistry() {}

  TurnbackFootprintGuardRegistry(Consumer<String> diagnostics) {
    useDiagnostics(diagnostics);
  }

  /** 接上诊断日志出口；为空时丢弃。 */
  void useDiagnostics(Consumer<String> diagnostics) {
    this.diagnostics = diagnostics == null ? message -> {} : diagnostics;
  }

  /**
   * 兼容旧调用方的保守注册入口。
   *
   * <p>节点边界数不能证明列尾已经清空，因此该入口只保留资源，不会根据后续节点计数释放。调用方应改用带有向路径和列车长度的重载。
   */
  void register(
      String trainName, NodeId handoffNode, Set<OccupancyResource> resources, int rearGuardEdges) {
    registerInternal(trainName, resources, Optional.empty(), Optional.empty());
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
    register(
        trainName,
        handoffNode,
        resources,
        forwardPath,
        estimatedTrainLengthBlocks,
        rearGuardEdges,
        RouteEvidence.none());
  }

  /**
   * 登记折返防护，并带上路线有序到达的后备证据。
   *
   * @param route handoff 时的路线节点序列与折返所在下标；{@link RouteEvidence#none()} 表示不提供后备证据
   * @see #observeRouteArrival
   */
  void register(
      String trainName,
      NodeId handoffNode,
      Set<OccupancyResource> resources,
      List<ForwardPathEdge> forwardPath,
      OptionalDouble estimatedTrainLengthBlocks,
      int rearGuardEdges,
      RouteEvidence route) {
    PlanAttempt attempt =
        buildRearClearPlan(handoffNode, forwardPath, estimatedTrainLengthBlocks, rearGuardEdges);
    Optional<FarClearance> farClearance =
        attempt.requiredClearDistanceBlocks().isPresent()
            ? FarClearance.create(
                handoffNode,
                forwardPath,
                route,
                attempt.requiredClearDistanceBlocks().getAsDouble() + FAR_CLEAR_MARGIN_BLOCKS)
            : Optional.empty();
    boolean registered = registerInternal(trainName, resources, attempt.plan(), farClearance);
    if (registered && attempt.plan().isEmpty()) {
      diagnostics.accept(
          "TURNBACK_FOOTPRINT_GUARD_NO_PLAN train="
              + trainName
              + " handoff="
              + (handoffNode == null ? "-" : handoffNode.value())
              + " reason="
              + attempt.failureReason()
              + " resources="
              + (resources == null ? 0 : resources.size())
              + " farEvidence="
              + farClearance.isPresent());
    }
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
        RearClearPlan.create(handoffNode, forwardPath, requiredClearDistanceBlocks),
        Optional.empty());
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
    List<String> notes = new ArrayList<>();
    Optional<Release> release;
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
          notes.add(
              "TURNBACK_FOOTPRINT_GUARD_SEALED train="
                  + trainName
                  + " observed="
                  + observedNode.value()
                  + " observedIndex="
                  + observedIndex
                  + " expectedIndex="
                  + (epoch.currentNodeIndex() + 1)
                  + " handoff="
                  + plan.observations().get(0).node().value()
                  + " resources="
                  + epoch.resources().size()
                  + " farEvidence="
                  + epoch.farClearance().isPresent());
          continue;
        }
        if (observedIndex < epoch.currentNodeIndex()) {
          // 真实倒退会缩短列尾净清空距离，必须同步回退，而不是保留历史最大值。
          remaining.add(epoch.withProgress(observedIndex));
          continue;
        }
        double observedDistance = plan.observations().get(observedIndex).cumulativeDistanceBlocks();
        if (observedDistance + DISTANCE_EPSILON < plan.requiredClearDistanceBlocks()) {
          remaining.add(epoch.withProgress(observedIndex));
          continue;
        }
        completedResources.addAll(epoch.resources());
      }
      release = finishRelease(key, remaining, completedResources);
    }
    notes.forEach(diagnostics);
    return release;
  }

  /**
   * 记录一次按序的路线路径点到达（后备释放）。
   *
   * <p>路线下标 {@code routeIndex} 由运行时进度逐个推进，交路标识与节点名都必须与登记时一致；对应的累计前进距离达到 （车长 + 车尾保护边距 + {@value
   * #FAR_CLEAR_MARGIN_BLOCKS} 格余量）时，无论连续节点事件是否成立，旧进路上的车尾都已离开。 不提供路线证据或车长未知的 epoch 不受影响，继续
   * fail-retain。
   *
   * @return 可解除 sidecar 保护的旧资源，语义同 {@link #observeProgress}
   */
  Optional<Release> observeRouteArrival(
      String trainName, String routeKey, int routeIndex, NodeId arrivedNode) {
    String key = keyOf(trainName);
    if (key == null || routeKey == null || arrivedNode == null || routeIndex < 0) {
      return Optional.empty();
    }
    List<String> notes = new ArrayList<>();
    Optional<Release> release;
    synchronized (guards) {
      List<Guard> current = guards.get(key);
      if (current == null || current.isEmpty()) {
        return Optional.empty();
      }
      List<Guard> remaining = new ArrayList<>(current.size());
      Set<OccupancyResource> completedResources = new LinkedHashSet<>();
      for (Guard epoch : current) {
        FarClearance far = epoch.farClearance().orElse(null);
        RouteWaypoint waypoint =
            far == null || !far.routeKey().equals(routeKey)
                ? null
                : far.waypointsByRouteIndex().get(routeIndex);
        if (waypoint == null
            || !waypoint.node().equals(arrivedNode)
            || waypoint.cumulativeBlocks() + DISTANCE_EPSILON < far.thresholdBlocks()) {
          remaining.add(epoch);
          continue;
        }
        completedResources.addAll(epoch.resources());
        notes.add(
            "TURNBACK_FOOTPRINT_GUARD_FAR_CLEAR train="
                + trainName
                + " routeIndex="
                + routeIndex
                + " node="
                + arrivedNode.value()
                + " distance="
                + Math.round(waypoint.cumulativeBlocks())
                + " threshold="
                + Math.round(far.thresholdBlocks())
                + " active="
                + epoch.active()
                + " resources="
                + epoch.resources().size());
      }
      release = finishRelease(key, remaining, completedResources);
    }
    notes.forEach(diagnostics);
    return release;
  }

  /** 完成的 epoch 从表里摘掉；其资源里仍被别的 epoch 引用的不能释放。调用方持有 {@code guards} 锁。 */
  private Optional<Release> finishRelease(
      String key, List<Guard> remaining, Set<OccupancyResource> completedResources) {
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

  /**
   * @return 是否真的登记了 guard（没有列车名或没有资源时不登记）
   */
  private boolean registerInternal(
      String trainName,
      Set<OccupancyResource> resources,
      Optional<RearClearPlan> plan,
      Optional<FarClearance> farClearance) {
    String key = keyOf(trainName);
    if (key == null || resources == null || resources.isEmpty()) {
      return false;
    }
    Set<OccupancyResource> immutableResources = Set.copyOf(resources);
    Optional<RearClearPlan> normalizedPlan = plan == null ? Optional.empty() : plan;
    synchronized (guards) {
      List<Guard> existing = guards.getOrDefault(key, List.of());
      List<Guard> epochs = new ArrayList<>(existing.size() + 1);
      existing.stream().map(epoch -> epoch.withActive(false)).forEach(epochs::add);
      epochs.add(new Guard(normalizedPlan, 0, immutableResources, true, farClearance));
      guards.put(key, List.copyOf(epochs));
    }
    return true;
  }

  private static PlanAttempt buildRearClearPlan(
      NodeId handoffNode,
      List<ForwardPathEdge> forwardPath,
      OptionalDouble estimatedTrainLengthBlocks,
      int rearGuardEdges) {
    if (estimatedTrainLengthBlocks == null || estimatedTrainLengthBlocks.isEmpty()) {
      return PlanAttempt.failed("length-unknown");
    }
    double trainLength = estimatedTrainLengthBlocks.getAsDouble();
    if (!Double.isFinite(trainLength) || trainLength <= 0.0) {
      return PlanAttempt.failed("length-invalid");
    }
    if (forwardPath == null) {
      return PlanAttempt.failed("path-missing");
    }
    int guardEdgeCount = Math.max(1, rearGuardEdges);
    if (forwardPath.size() < guardEdgeCount) {
      return PlanAttempt.failed("path-shorter-than-rear-guard");
    }
    double rearGuardDistance = 0.0;
    for (int i = 0; i < guardEdgeCount; i++) {
      ForwardPathEdge edge = forwardPath.get(i);
      if (edge == null || !Double.isFinite(edge.lengthBlocks()) || edge.lengthBlocks() <= 0.0) {
        return PlanAttempt.failed("rear-guard-edge-invalid");
      }
      rearGuardDistance += edge.lengthBlocks();
      if (!Double.isFinite(rearGuardDistance)) {
        return PlanAttempt.failed("rear-guard-edge-invalid");
      }
    }
    double requiredDistance = trainLength + rearGuardDistance;
    Optional<RearClearPlan> plan = RearClearPlan.create(handoffNode, forwardPath, requiredDistance);
    return plan.isPresent()
        ? new PlanAttempt(plan, OptionalDouble.of(requiredDistance), "-")
        : new PlanAttempt(
            plan, OptionalDouble.of(requiredDistance), "path-not-continuous-or-insufficient");
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
      boolean active,
      Optional<FarClearance> farClearance) {
    private Guard {
      plan = plan == null ? Optional.empty() : plan;
      currentNodeIndex = Math.max(0, currentNodeIndex);
      resources = resources == null ? Set.of() : Set.copyOf(resources);
      farClearance = farClearance == null ? Optional.empty() : farClearance;
    }

    private Guard withActive(boolean nextActive) {
      return active == nextActive
          ? this
          : new Guard(plan, currentNodeIndex, resources, nextActive, farClearance);
    }

    private Guard withProgress(int nextNodeIndex) {
      return new Guard(plan, nextNodeIndex, resources, true, farClearance);
    }
  }

  /** 连续路径计划的构建结果：计划本身、无论计划成败都算得出的清空阈值，以及失败原因（诊断用）。 */
  private record PlanAttempt(
      Optional<RearClearPlan> plan,
      OptionalDouble requiredClearDistanceBlocks,
      String failureReason) {
    private static PlanAttempt failed(String reason) {
      return new PlanAttempt(Optional.empty(), OptionalDouble.empty(), reason);
    }
  }

  /**
   * handoff 时的路线上下文：路线节点序列（生效节点，下标 0 起）与折返所在的下标。后备释放只认下标严格大于 {@code startIndex} 的路径点。
   *
   * @param routeKey 交路标识；路线下标只在同一条交路内有意义，同一列车换交路后旧 epoch 不能用新交路的下标去比对
   * @param routeNodes 路线的生效路径点序列
   * @param startIndex 折返发生在路线的哪个下标（列车出库/复用时所在的首站）
   */
  record RouteEvidence(String routeKey, List<NodeId> routeNodes, int startIndex) {
    RouteEvidence {
      routeNodes = routeNodes == null ? List.of() : List.copyOf(routeNodes);
    }

    /** 不提供后备证据。 */
    static RouteEvidence none() {
      return new RouteEvidence(null, List.of(), -1);
    }
  }

  private record RouteWaypoint(NodeId node, double cumulativeBlocks) {}

  /** 路线下标 → 该路径点沿登记前进路径的累计距离，以及要达到的阈值（车长 + 车尾保护边距 + 余量）。 */
  private record FarClearance(
      String routeKey, Map<Integer, RouteWaypoint> waypointsByRouteIndex, double thresholdBlocks) {
    private FarClearance {
      waypointsByRouteIndex = Map.copyOf(waypointsByRouteIndex);
    }

    private static Optional<FarClearance> create(
        NodeId handoffNode,
        List<ForwardPathEdge> forwardPath,
        RouteEvidence route,
        double thresholdBlocks) {
      if (handoffNode == null
          || forwardPath == null
          || route == null
          || route.routeKey() == null
          || route.startIndex() < 0
          || !Double.isFinite(thresholdBlocks)
          || thresholdBlocks <= 0.0) {
        return Optional.empty();
      }
      // 沿登记路径展开节点与累计距离；路径不连续或边长无效处停止，后面的路径点没有证据
      List<NodeId> nodes = new ArrayList<>();
      List<Double> cumulative = new ArrayList<>();
      nodes.add(handoffNode);
      cumulative.add(0.0);
      double total = 0.0;
      NodeId expectedFrom = handoffNode;
      for (ForwardPathEdge edge : forwardPath) {
        if (edge == null
            || edge.fromNode() == null
            || edge.toNode() == null
            || !expectedFrom.equals(edge.fromNode())
            || !Double.isFinite(edge.lengthBlocks())
            || edge.lengthBlocks() <= 0.0) {
          break;
        }
        total += edge.lengthBlocks();
        nodes.add(edge.toNode());
        cumulative.add(total);
        expectedFrom = edge.toNode();
      }
      // 路线路径点按下标依次在路径上向后找：下标单调，路径位置也必须单调不减
      Map<Integer, RouteWaypoint> byIndex = new HashMap<>();
      int position = 0;
      List<NodeId> routeNodes = route.routeNodes();
      for (int index = route.startIndex() + 1; index < routeNodes.size(); index++) {
        NodeId target = routeNodes.get(index);
        int found = -1;
        for (int i = position; i < nodes.size(); i++) {
          if (nodes.get(i).equals(target)) {
            found = i;
            break;
          }
        }
        if (found < 0) {
          break;
        }
        byIndex.put(index, new RouteWaypoint(target, cumulative.get(found)));
        position = found;
      }
      return byIndex.isEmpty()
          ? Optional.empty()
          : Optional.of(new FarClearance(route.routeKey(), byIndex, thresholdBlocks));
    }
  }

  /**
   * 已取得列尾离开旧进路证据、可解除 sidecar 保护的 handoff 前旧资源集合。
   *
   * <p>{@code resources} 按 {@link OccupancyResource#STABLE_ORDER} 排列：它按序交给占用管理器释放，释放事件的先后决定等待容量的列车
   * 被唤醒、重新取得资源的先后。来源集合的顺序不可信（资源 hash 含枚举），所以这里显式排序而不是保留插入序。
   */
  record Release(Set<OccupancyResource> resources) {
    Release {
      resources =
          resources == null
              ? Set.of()
              : StableCollections.copySorted(resources, OccupancyResource.STABLE_ORDER);
    }
  }
}
