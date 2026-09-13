package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 动态调度全局不变量。
 *
 * <p>每条不变量都从实现推导，并在注释中标注出处与"当前是数据结构保证还是仅代码假设"。这些检查是纯函数：只读快照，不触碰运行时， 也不得为了让场景通过而放宽——Phase 0
 * 的目的是把现有行为钉住，包括钉住当前是错的行为。
 *
 * <p>输出是稳定排序的字符串列表，避免 Map 枚举顺序进入断言。
 */
final class DispatchInvariants {

  private static final String SINGLE_PREFIX = "single:";

  private DispatchInvariants() {}

  /**
   * 单次检查所需的只读现场。
   *
   * <p>I1–I3 是纯快照函数。I5 需要"停了多久"，I6 需要"这一 tick 新产生了哪些诊断"，二者都不是单帧信息， 因此由骨架负责跨 tick
   * 累积后传入——判定本身仍然是纯函数，不持有状态。
   */
  record Sample(
      int tick,
      List<OccupancyClaim> claims,
      Map<String, List<NodeId>> routePathsByTrain,
      Map<NodeId, List<NodeId>> adjacency,
      List<StoppedTrain> stoppedTrains,
      List<OccupancyQueueSnapshot> queues,
      List<String> diagnosticsSinceLastTick) {
    Sample {
      claims = claims == null ? List.of() : List.copyOf(claims);
      routePathsByTrain = routePathsByTrain == null ? Map.of() : Map.copyOf(routePathsByTrain);
      adjacency = adjacency == null ? Map.of() : Map.copyOf(adjacency);
      stoppedTrains = stoppedTrains == null ? List.of() : List.copyOf(stoppedTrains);
      queues = queues == null ? List.of() : List.copyOf(queues);
      diagnosticsSinceLastTick =
          diagnosticsSinceLastTick == null ? List.of() : List.copyOf(diagnosticsSinceLastTick);
    }
  }

  /**
   * 一列处于 STOP 的列车及其已持续的 tick 数。
   *
   * @param consecutiveTicks 同一轮 STOP 生命周期（按 reasonCode + enteredAt 判定）已连续出现的 tick 数
   */
  record StoppedTrain(String trainName, RuntimeStopState state, int consecutiveTicks) {}

  static List<String> check(Sample sample) {
    List<String> violations = new ArrayList<>();
    violations.addAll(checkI1PhysicalHardOccupancyIsExclusive(sample));
    violations.addAll(checkI2SingleCorridorDirectionIsConsistent(sample));
    violations.addAll(checkI3ClaimsStayOnOwnRoute(sample));
    violations.addAll(checkI5BlockingIsExplainable(sample));
    violations.addAll(checkI6RequestContextMatchesProgress(sample));
    return List.copyOf(violations);
  }

  // ------------------------------------------------------------------ I1

  /**
   * I1 物理资源硬占用唯一性。
   *
   * <p>对任意 {@code NODE}/{@code EDGE} 资源，持有硬角色 claim 的列车至多一个。
   *
   * <p>出处：{@code SimpleOccupancyManager} 类注释（"真实 MOVEMENT_REQUIRED NODE/EDGE 硬占用始终按 STOP 处理"；{@code
   * PHYSICAL_FOOTPRINT} "始终作为硬占用"）、{@code firstHardBlocker}。
   *
   * <p>当前保证：<b>仅代码假设</b>。{@code claims} 的值类型是 {@code List<OccupancyClaim>}，结构上允许同一资源存在多个 owner。
   */
  private static List<String> checkI1PhysicalHardOccupancyIsExclusive(Sample sample) {
    Map<String, Set<String>> hardOwnersByResource = new TreeMap<>();
    for (OccupancyClaim claim : sample.claims()) {
      if (claim == null || !isPhysical(claim.resource()) || !isHardRole(claim.role())) {
        continue;
      }
      hardOwnersByResource
          .computeIfAbsent(claim.resource().toString(), unused -> new TreeSet<>())
          .add(TrainNameNormalizer.normalizeKey(claim.trainName()));
    }
    List<String> violations = new ArrayList<>();
    for (Map.Entry<String, Set<String>> entry : hardOwnersByResource.entrySet()) {
      if (entry.getValue().size() > 1) {
        violations.add("I1 物理资源硬占用不唯一: resource=" + entry.getKey() + " owners=" + entry.getValue());
      }
    }
    return violations;
  }

  // ------------------------------------------------------------------ I2

  /**
   * I2 单线走廊方向一致性。
   *
   * <p>任一 {@code single:} 冲突资源上，所有 claim 的 {@code corridorDirection} 必须相同；{@code UNKNOWN}
   * 不得与任何已知方向在同一资源上共存。
   *
   * <p>出处：{@code SimpleOccupancyManager.singleRegionOppositeOrUnknownExternalBarrier} 与 {@code
   * OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER}。
   *
   * <p>当前保证：准入路径强制，但<b>账本本身不强制</b>——绕过准入的写入（authority handoff、现场重建、恢复路径）可以打破它。
   */
  private static List<String> checkI2SingleCorridorDirectionIsConsistent(Sample sample) {
    Map<String, Map<String, String>> directionsByResource = new TreeMap<>();
    for (OccupancyClaim claim : sample.claims()) {
      if (claim == null || !isSingleCorridorConflict(claim.resource())) {
        continue;
      }
      String direction =
          claim
              .corridorDirection()
              .map(CorridorDirection::name)
              .orElse(CorridorDirection.UNKNOWN.name());
      directionsByResource
          .computeIfAbsent(claim.resource().toString(), unused -> new TreeMap<>())
          .put(
              TrainNameNormalizer.normalizeKey(claim.trainName()) + "@" + claim.role().name(),
              direction);
    }
    List<String> violations = new ArrayList<>();
    for (Map.Entry<String, Map<String, String>> entry : directionsByResource.entrySet()) {
      Set<String> distinct = new TreeSet<>(entry.getValue().values());
      if (distinct.size() > 1) {
        violations.add(
            "I2 单线冲突区方向不一致: resource=" + entry.getKey() + " holders=" + entry.getValue());
      }
    }
    return violations;
  }

  // ------------------------------------------------------------------ I3

  /**
   * I3 claim 不得离开本车交路。
   *
   * <p>列车持有的每个 {@code NODE}/{@code EDGE} claim 必须位于该车当前交路路径上。
   *
   * <p>出处：{@code RuntimeDispatchService.releaseResourcesNotInRequest}（"列车推进后即时释放窗口外资源"）。
   *
   * <p>这里刻意采用比"claim ⊆ 当前请求窗口"更弱的形式：窗口大小由生产逻辑决定，在测试里重算窗口等于把调度逻辑复制到测试层。
   * 限定在"必须位于本车交路上"既能抓住跨交路/跨世代的陈旧残留，又不会把生产的窗口策略钉死。
   *
   * <p><b>只检查 {@code MOVEMENT_REQUIRED}</b>。{@code releaseResourcesNotInRequest} 的契约是 "claim ⊆ 请求窗口
   * ∪ protectedResources"，而 {@code protectedResources} 明确包含 {@code
   * protectedSwitcherZoneClaims}——道岔联锁区保护会合法地覆盖会让环的并行股道等本车交路之外的资源。 把保护性角色一并纳入会把正确的联锁行为误报成陈旧
   * claim（首轮实测：会让站 CHARLIE 的 2 道被 1 道列车 PROTECTIVE_RETAIN，这是对的）。硬授权则必须严格落在本车路径上。
   *
   * <p>当前保证：<b>靠清理代码维持</b>；任何遗漏的 {@code releaseResourcesNotInRequest} 调用点都会留下陈旧 claim。
   */
  private static List<String> checkI3ClaimsStayOnOwnRoute(Sample sample) {
    Map<String, Set<String>> allowedByTrain = new LinkedHashMap<>();
    for (Map.Entry<String, List<NodeId>> entry : sample.routePathsByTrain().entrySet()) {
      allowedByTrain.put(
          TrainNameNormalizer.normalizeKey(entry.getKey()),
          physicalResourceKeys(entry.getValue(), sample.adjacency()));
    }
    List<String> violations = new ArrayList<>();
    Set<String> reported = new TreeSet<>();
    for (OccupancyClaim claim : sample.claims()) {
      if (claim == null
          || !isPhysical(claim.resource())
          || claim.role() != ClaimRole.MOVEMENT_REQUIRED) {
        continue;
      }
      String owner = TrainNameNormalizer.normalizeKey(claim.trainName());
      Set<String> allowed = allowedByTrain.get(owner);
      if (allowed == null) {
        // 非场景登记列车（外部注入的占用夹具）不参与本条检查。
        continue;
      }
      String resource = claim.resource().toString();
      if (!allowed.contains(resource)) {
        reported.add(
            "I3 claim 离开本车交路: train="
                + owner
                + " resource="
                + resource
                + " role="
                + claim.role().name());
      }
    }
    violations.addAll(reported);
    return violations;
  }

  /**
   * 把允许的节点集合展开成资源 key 集合。
   *
   * <p>边必须按<b>图邻接</b>展开，不能只取路径上的相邻对：DYNAMIC 选台会把列车分到会让环的另一条股道，
   * 那条股道与咽喉之间的边在原路径里并不相邻，按相邻对展开会把正确的选台结果误报成越界 claim。
   */
  private static Set<String> physicalResourceKeys(
      List<NodeId> allowedNodes, Map<NodeId, List<NodeId>> adjacency) {
    Set<String> keys = new LinkedHashSet<>();
    if (allowedNodes == null) {
      return keys;
    }
    Set<NodeId> allowed = new LinkedHashSet<>(allowedNodes);
    for (NodeId node : allowed) {
      if (node != null) {
        keys.add(OccupancyResource.forNode(node).toString());
      }
    }
    for (NodeId node : allowed) {
      for (NodeId neighbour : adjacency.getOrDefault(node, List.of())) {
        if (allowed.contains(neighbour)) {
          keys.add(OccupancyResource.forEdge(EdgeId.undirected(node, neighbour)).toString());
        }
      }
    }
    return keys;
  }

  // ------------------------------------------------------------------ I5

  /**
   * I5 阻塞可解释性。
   *
   * <p>列车若处于非计划性 STOP 且持续超过 1 个 tick，必须存在一条当前有效的依赖：一个具名 blocker（资源与 owner 都不是占位符 {@code
   * "-"}），或它在某个冲突队列中有一个具名位次。<b>不允许既没有 blocker 又不在任何队列里</b>——那意味着系统停了车却说不出在等谁。
   *
   * <p>出处：{@code RuntimeStopState.blockers} 字段的设计意图（"避免硬停车、普通占用等待和计划停车各自只写一段不可关联的字符串日志"）； 实服中
   * {@code DEADLOCK_DESTROY_SKIPPED ... blockers=[] conflictKey=-} 伴随 127–178s 停车即是反例。
   *
   * <p>只看持续 <b>2 个及以上</b> tick 的 STOP：单 tick 的瞬时停车可能是授权刚撤销、blocker 尚未采样的正常中间态， 把它算进来会把时序噪声报成缺陷。
   *
   * <p>计划停车被排除——它们的"依赖"不是资源而是时间（dwell、门控、终到流程）。判别用 {@code releaseCondition} 而不是停因字符串：前者是枚举，后者是自由文本。
   *
   * <p>当前保证：<b>不成立</b>，这是收益最高的一条新增不变量。
   */
  private static List<String> checkI5BlockingIsExplainable(Sample sample) {
    List<String> violations = new ArrayList<>();
    Set<String> queuedTrains = new TreeSet<>();
    for (OccupancyQueueSnapshot queue : sample.queues()) {
      if (queue == null) {
        continue;
      }
      for (OccupancyQueueEntry entry : queue.entries()) {
        if (entry != null) {
          queuedTrains.add(TrainNameNormalizer.normalizeKey(entry.trainName()));
        }
      }
    }
    for (StoppedTrain stopped : sample.stoppedTrains()) {
      if (stopped == null || stopped.state() == null || stopped.consecutiveTicks() < 2) {
        continue;
      }
      RuntimeStopState state = stopped.state();
      if (!isUnplannedStop(state)) {
        continue;
      }
      if (hasNamedBlocker(state)
          || queuedTrains.contains(TrainNameNormalizer.normalizeKey(stopped.trainName()))) {
        continue;
      }
      violations.add(
          "I5 停车不可解释: train="
              + TrainNameNormalizer.normalizeKey(stopped.trainName())
              + " reason="
              + state.reasonCode()
              + " detail="
              + state.detail()
              + " ticks="
              + stopped.consecutiveTicks()
              + " blockers="
              + state.blockers()
              + " inAnyQueue=false");
    }
    return violations;
  }

  /** 非计划性 STOP：撤销了授权，或是占用等待；计划停车（dwell / 终到流程）按解除条件排除。 */
  private static boolean isUnplannedStop(RuntimeStopState state) {
    RuntimeStopState.ReleaseCondition release = state.releaseCondition();
    if (release == RuntimeStopState.ReleaseCondition.PLANNED_STOP_COMPLETED
        || release == RuntimeStopState.ReleaseCondition.TERMINAL_LIFECYCLE_COMPLETED
        || release == RuntimeStopState.ReleaseCondition.LAYOVER_READY_AND_AUTHORITY_REISSUED) {
      return false;
    }
    return state.invalidatesAuthority() || "BLOCKED_BY_OCCUPANCY".equals(state.reasonCode());
  }

  private static boolean hasNamedBlocker(RuntimeStopState state) {
    for (RuntimeStopState.Blocker blocker : state.blockers()) {
      if (blocker != null && isNamed(blocker.resource()) && isNamed(blocker.owner())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isNamed(String value) {
    return value != null && !value.isBlank() && !"-".equals(value);
  }

  // ------------------------------------------------------------------ I6

  /**
   * I6 请求上下文与进度表一致。
   *
   * <p>进入准入的请求所携带的进度锚点必须与 {@code RouteProgressRegistry} 当前记录一致，否则该次判定产生的 blocker 证据会被 {@code
   * liveBlockerSnapshotProgressFresh} 丢弃——后果不是判错，是判不出，直接导致 I5 失效。
   *
   * <p>断言方式刻意选择<b>观察生产 trace</b> 而不是反射进内部：{@code SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED}
   * 正是该丢弃行为唯一的对外信号。骨架的 tick 循环是同步的——信号 tick 阶段不更新进度表，到达提交在其后单独一段——
   * 因此这里出现的任何"陈旧"都不可能是真的异步滞后，只能是上下文本身没对齐。
   *
   * <p>不把 {@code progressVersion == -1} 单列为违反：只有经过 {@code markDirectedRequest} 的运行时授权请求才会被写入版本号，
   * 后方保护、保位、当前位置等请求天然没有版本，按 -1 判违反会把正常路径报成缺陷。
   *
   * <p>出处：{@code RuntimeDispatchService.liveBlockerSnapshotProgressFresh} 与 {@code
   * traceLiveBlockerSnapshotRejected}。
   */
  private static List<String> checkI6RequestContextMatchesProgress(Sample sample) {
    List<String> violations = new ArrayList<>();
    Set<String> reported = new TreeSet<>();
    for (String line : sample.diagnosticsSinceLastTick()) {
      if (line != null && line.contains("SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED")) {
        reported.add("I6 请求上下文与进度表不一致，blocker 证据被丢弃: " + line.trim());
      }
    }
    violations.addAll(reported);
    return violations;
  }

  // ------------------------------------------------------------------ 工具

  private static boolean isPhysical(OccupancyResource resource) {
    return resource != null
        && (resource.kind() == ResourceKind.NODE || resource.kind() == ResourceKind.EDGE);
  }

  private static boolean isHardRole(ClaimRole role) {
    return role == ClaimRole.MOVEMENT_REQUIRED || role == ClaimRole.PHYSICAL_FOOTPRINT;
  }

  private static boolean isSingleCorridorConflict(OccupancyResource resource) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith(SINGLE_PREFIX);
  }
}
