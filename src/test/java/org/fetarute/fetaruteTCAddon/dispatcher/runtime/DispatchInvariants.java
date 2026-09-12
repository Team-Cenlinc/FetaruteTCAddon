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

  /** 单次检查所需的只读现场。 */
  record Sample(
      int tick,
      List<OccupancyClaim> claims,
      Map<String, List<NodeId>> routePathsByTrain,
      Map<NodeId, List<NodeId>> adjacency) {
    Sample {
      claims = claims == null ? List.of() : List.copyOf(claims);
      routePathsByTrain = routePathsByTrain == null ? Map.of() : Map.copyOf(routePathsByTrain);
      adjacency = adjacency == null ? Map.of() : Map.copyOf(adjacency);
    }
  }

  static List<String> check(Sample sample) {
    List<String> violations = new ArrayList<>();
    violations.addAll(checkI1PhysicalHardOccupancyIsExclusive(sample));
    violations.addAll(checkI2SingleCorridorDirectionIsConsistent(sample));
    violations.addAll(checkI3ClaimsStayOnOwnRoute(sample));
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
