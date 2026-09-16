package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 占用 claim 与等待关系的**纯判据**。
 *
 * <p>只放不碰任何运行时状态的函数：给定一组 claim / blocker / 资源 key，算出它们意味着什么。 它们原本写在 {@link RuntimeDispatchService}
 * 里，搬出来有两个理由，后一个才是重点：
 *
 * <ol>
 *   <li>本来就是纯函数，放在三万行的服务类里既不好找也不好单独测；
 *   <li><b>SpotBugs 对单个类有硬上限</b>。{@code AnalysisContext.isTooBig()} 在类文件 &gt; 1,000,000 字节 <b>或方法数
 *       &gt; 1000</b> 时跳过<b>整个类</b>。 {@code RuntimeDispatchService} 曾经到 1007，只超 7 个，代价却是全仓改动最频繁的
 *       那个类整个没被静态分析过——而字节数（~900KB）还没到上限，卡的是方法数。
 * </ol>
 *
 * <p><b>这不是“拆类”，只是把它拉回阀值下方。</b>余量只有一位数，下次加功能很容易再顶上去， 而顶上去是<b>静默的</b>：SpotBugs 只会多报一条 {@code
 * SKIPPED_CLASS_TOO_BIG}。真正的解法仍然是 按职责拆开（信号 tick / 授权构建 / 恢复动作 / 诊断）。加方法前先跑这一句：
 *
 * <pre>
 *   javap -p build/classes/java/main/.../RuntimeDispatchService.class | grep -cE '\(.*\);$'
 * </pre>
 */
final class OccupancyClaimEvidence {

  /** 阻塞快照里最多列出多少个资源 key。 */
  private static final int BLOCKING_SNAPSHOT_MAX_RESOURCES = 8;

  private OccupancyClaimEvidence() {}

  static String summarizeResourceKeys(List<OccupancyClaim> claims) {
    List<String> keys = new ArrayList<>();
    for (OccupancyClaim claim : claims) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      if (keys.size() >= BLOCKING_SNAPSHOT_MAX_RESOURCES) {
        keys.add("…+" + (claims.size() - BLOCKING_SNAPSHOT_MAX_RESOURCES));
        break;
      }
      keys.add(claim.resource().key() + "@" + claim.role());
    }
    return keys.toString();
  }

  static Optional<OccupancyResource> plannerSwitcherConflictResource(String resourceKey) {
    if (resourceKey == null || resourceKey.isBlank()) {
      return Optional.empty();
    }
    String normalized = resourceKey.trim();
    if (normalized.startsWith("CONFLICT:switcher:")) {
      return Optional.of(OccupancyResource.forConflict(normalized.substring("CONFLICT:".length())));
    }
    if (normalized.startsWith("NODE:SWITCHER:")) {
      return Optional.of(
          OccupancyResource.forConflict("switcher:" + normalized.substring("NODE:".length())));
    }
    return Optional.empty();
  }

  static String resourceKindName(String resource) {
    if (resource == null || resource.isBlank() || !resource.contains(":")) {
      return "UNKNOWN";
    }
    return resource.substring(0, resource.indexOf(':')).toUpperCase(Locale.ROOT);
  }

  static boolean externalOccupancyStopResourceStillHeld(
      String trainName, RuntimeStopState.Blocker blocker, OccupancyClaim claim) {
    if (blocker == null
        || claim == null
        || claim.resource() == null
        || TrainNameNormalizer.sameLogicalTrain(trainName, claim.trainName())
        || !blocker.resource().equals(claim.resource().toString())) {
      return false;
    }
    return switch (claim.role()) {
      case MOVEMENT_REQUIRED, PHYSICAL_FOOTPRINT, PROTECTIVE_RETAIN, HOLD_ONLY -> true;
      case QUEUE_POSITION, LOOKAHEAD_PREVIEW, UNLOCK_RESERVATION -> false;
    };
  }

  static boolean isSingleConflict(OccupancyResource resource) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("single:");
  }

  static String queuePositionYieldCooldownKey(String resourceKey, String queueOwner) {
    return (resourceKey == null ? "-" : resourceKey)
        + "|"
        + TrainNameNormalizer.normalizeKey(queueOwner);
  }

  /**
   * 在等待关系里找出一条**可证明成环**的排队边。
   *
   * <p>两条判据，都不涉及任何具体线路或拓扑：
   *
   * <ol>
   *   <li>被卡列车 A 的 blocker <b>全部</b>是 {@code QUEUE_POSITION}——它没有被任何真实占用挡住。
   *       只要还有一个真实占用挡着它，割队列既解不开问题，又白白牺牲别人的排队公平性。
   *   <li>排队者 B 自己也被挡，且挡它的是 A 本身、或是 A 正持有的某个资源 ⇒ A→B→A 成环，<b>已证明</b>，不是超时猜测。
   * </ol>
   *
   * @param blockedTrain 被卡的列车 A
   * @param blockedBy A 的 blocker 集合
   * @param blockersOf 查某列车自身 blocker 的入口
   * @param heldByBlocked A 当前持有的资源 key（不含排队位次）
   */
  static Optional<RuntimeDispatchService.QueueYieldTarget> findProvenQueueCycle(
      String blockedTrain,
      Set<RuntimeDispatchService.DeadlockBlockerInfo> blockedBy,
      java.util.function.Function<String, Set<RuntimeDispatchService.DeadlockBlockerInfo>>
          blockersOf,
      Set<String> heldByBlocked) {
    if (blockedTrain == null || blockedBy == null || blockedBy.isEmpty() || blockersOf == null) {
      return Optional.empty();
    }
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : blockedBy) {
      if (blocker == null || !"QUEUE_POSITION".equals(blocker.role())) {
        return Optional.empty(); // 有真实占用挡着 ⇒ 不是纯排队反转，不动。
      }
    }
    Set<String> held = heldByBlocked == null ? Set.of() : heldByBlocked;
    // blockedBy 是 Set，迭代顺序不保证稳定。同一份网络状态必须永远割同一条边，
    // 否则事后对着日志复盘会得到对不上的结论。
    List<RuntimeDispatchService.DeadlockBlockerInfo> ordered = new ArrayList<>();
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : blockedBy) {
      if (blocker != null) {
        ordered.add(blocker);
      }
    }
    ordered.sort(
        Comparator.comparing(
                RuntimeDispatchService.DeadlockBlockerInfo::resourceKey,
                Comparator.nullsLast(String::compareTo))
            .thenComparing(
                RuntimeDispatchService.DeadlockBlockerInfo::ownerCanonical,
                Comparator.nullsLast(String::compareTo))
            .thenComparing(
                RuntimeDispatchService.DeadlockBlockerInfo::trainName,
                Comparator.nullsLast(String::compareTo)));
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : ordered) {
      String queueOwner = blocker.trainName();
      if (queueOwner == null || queueOwner.isBlank()) {
        continue;
      }
      Set<RuntimeDispatchService.DeadlockBlockerInfo> ownerBlockers = blockersOf.apply(queueOwner);
      if (ownerBlockers == null || ownerBlockers.isEmpty()) {
        continue; // 排队者自己没被挡 ⇒ 它排队是正当的，等它。
      }
      boolean closesCycle =
          ownerBlockers.stream()
              .filter(Objects::nonNull)
              .anyMatch(
                  b ->
                      TrainNameNormalizer.sameLogicalTrain(b.trainName(), blockedTrain)
                          || held.contains(b.resourceKey()));
      if (closesCycle) {
        return Optional.of(
            new RuntimeDispatchService.QueueYieldTarget(queueOwner, blocker.resourceKey()));
      }
    }
    return Optional.empty();
  }

  /**
   * 把 {@code OccupancyResource.toString()} 形式的 key 还原成资源。
   *
   * <p>格式是 {@code KIND:key}，与 {@code OccupancyResource.toString()} 一一对应。 无法识别的 kind 返回空 ——
   * 宁可不动，也不要把一个猜出来的资源交给账本去改。
   */
  static Optional<OccupancyResource> parseOccupancyResourceKey(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    int separator = trimmed.indexOf(':');
    if (separator <= 0 || separator >= trimmed.length() - 1) {
      return Optional.empty();
    }
    String kindText = trimmed.substring(0, separator);
    String key = trimmed.substring(separator + 1);
    for (ResourceKind kind : ResourceKind.values()) {
      if (kind.name().equals(kindText)) {
        return Optional.of(new OccupancyResource(kind, key));
      }
    }
    return Optional.empty();
  }

  /**
   * 某列车当前持有的、**能挡住别人**的资源 key。用于证明等待环的另一半。
   *
   * <p>“持有”在这里必须按**会不会挡住排队者**来定义，而不是“账本里有一条记录”。 否则会把一个**并不存在**的等待环判成已证明，继而去割一辆正当排队的车。
   *
   * <p>实际会踩到的是 {@link ClaimRole#UNLOCK_RESERVATION}：它**会**写进 claims，但 {@link
   * ResourceIntent#UNLOCK_RESERVATION} 自己的文档写着「不得阻塞正常行车 admission」。 {@link
   * ClaimRole#QUEUE_POSITION} 与 {@link ClaimRole#LOOKAHEAD_PREVIEW} 同样不挡人（后者目前根本 不会落入
   * claims，列在这里只是不指望那个事实永远不变）。
   */
  static Set<String> resourceKeysHeldBy(SimpleOccupancyManager manager, String trainName) {
    Set<String> keys = new LinkedHashSet<>();
    for (OccupancyClaim claim : manager.snapshotClaims()) {
      if (claim == null || claim.resource() == null || !blockingClaimRole(claim.role())) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        keys.add(claim.resource().toString());
      }
    }
    return keys;
  }

  /**
   * claim 角色是否真的会挡住另一辆车。
   *
   * <p>口径与 {@link #externalOccupancyStopResourceStillHeld} 完全一致——同一个问题不应当在同一个类里 有两套答案。
   */
  static boolean blockingClaimRole(ClaimRole role) {
    if (role == null) {
      return false;
    }
    return switch (role) {
      case MOVEMENT_REQUIRED, PHYSICAL_FOOTPRINT, PROTECTIVE_RETAIN, HOLD_ONLY -> true;
      case QUEUE_POSITION, LOOKAHEAD_PREVIEW, UNLOCK_RESERVATION -> false;
    };
  }

  static OptionalInt parsePositiveInt(String raw) {
    if (raw == null) {
      return OptionalInt.empty();
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return OptionalInt.empty();
    }
    try {
      int value = Integer.parseInt(trimmed);
      return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
    } catch (NumberFormatException ex) {
      return OptionalInt.empty();
    }
  }

  static boolean isDirectionalSingleConflict(OccupancyResource resource) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("single:")
        && !resource.key().contains(":cycle:");
  }

  static String queueAdmissionBlockReason(OccupancyDecision preview) {
    if (preview == null || preview.reason() == null || "none".equals(preview.reason())) {
      return "queue-arbitration-wait";
    }
    return preview.reason();
  }

  static boolean isSingleConflict(OccupancyClaim claim) {
    return claim != null
        && claim.resource() != null
        && claim.resource().kind() == ResourceKind.CONFLICT
        && claim.resource().key().startsWith("single:");
  }

  /**
   * 把一组 blocker 压成“形态”描述：{@code KIND/ROLE} 的有序去重集合。
   *
   * <p>用于停车明细。**不带列车名**——明细会进去重键，带上车名会让它随车数爆炸， 把诊断预算吃掉、挤掉别的必留行。
   */
  static String describeBlockerShapes(java.util.Collection<OccupancyClaim> blockers) {
    if (blockers == null || blockers.isEmpty()) {
      return "no-blockers-listed";
    }
    java.util.TreeSet<String> shapes = new java.util.TreeSet<>();
    for (OccupancyClaim claim : blockers) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      shapes.add(claim.resource().kind() + "/" + claim.role());
    }
    return shapes.isEmpty() ? "no-blockers-listed" : String.join(",", shapes);
  }
}
