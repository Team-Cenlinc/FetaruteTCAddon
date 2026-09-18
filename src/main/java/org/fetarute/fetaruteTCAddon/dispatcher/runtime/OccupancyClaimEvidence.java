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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResourceResolver;
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
    // 走权威判据，不再自己写一套。这里曾经是只看角色的 switch，
    // 于是抽象 single:/switcher: 上别人的 PROTECTIVE_RETAIN 会把本车的行车权**撤掉**
    // （本路径 invalidatesAuthority=true）——同一个缺陷的第三个实例。
    return obstructs(claim.role(), claim.resource());
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
      if (claim == null || claim.resource() == null || !obstructs(claim.role(), claim.resource())) {
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
   * <p><b>必须同时看角色和资源种类，只看角色是错的。</b>仓库不变量：在**抽象**的 {@code single:} / {@code switcher:} CONFLICT
   * 资源上，{@link ClaimRole#PROTECTIVE_RETAIN} 与 {@link ClaimRole#HOLD_ONLY} 是不挡人的区域/尾部保护；只有在**物理**
   * NODE/EDGE （含 interlocking 冲突）上它们才是硬的。{@code SimpleOccupancyManager.isAdvisoryVisibleClaim}
   * 一直是这么判的，而本方法的第一版漏掉了资源这一维。
   *
   * <p>漏掉的后果是两处：{@link #resourceKeysHeldBy} 拿它证等待环——一个只在抽象 CONFLICT 上持有 PROTECTIVE_RETAIN
   * 的车会被当成“挡着别人”，于是**证出一个不存在的环**， 继而去割一辆正当排队的车；等待图边的现场复核拿它判“这条边还成不成立”， 于是把已不成立的边留在图里。
   *
   * <p>这与 {@code 67ef652} 修掉的 {@code UNLOCK_RESERVATION} 是同一个缺陷——把「不是物理占用的 东西」当成了阻塞。当时只修了一个实例。
   */
  static boolean obstructs(ClaimRole role, OccupancyResource resource) {
    if (role == null) {
      return false;
    }
    return switch (role) {
      case MOVEMENT_REQUIRED, PHYSICAL_FOOTPRINT -> true;
        // 只在物理空间上硬；抽象冲突键上它们是区域/尾部保护，不挡人。
      case PROTECTIVE_RETAIN, HOLD_ONLY -> physicalOccupancyResource(resource);
      case QUEUE_POSITION, LOOKAHEAD_PREVIEW, UNLOCK_RESERVATION -> false;
    };
  }

  /** 资源是否对应真实物理空间——与 {@code SimpleOccupancyManager} 同口径。 */
  private static boolean physicalOccupancyResource(OccupancyResource resource) {
    return resource != null
        && (resource.kind() == ResourceKind.NODE
            || resource.kind() == ResourceKind.EDGE
            || OccupancyResourceResolver.isInterlockingConflict(resource));
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
  /**
   * 按**此刻**的 claim 重新核对停因里记下的 blocker 是否仍然成立。
   *
   * <p>为什么必须单独有这个量：{@code SMART_BLOCKING_SNAPSHOT} 的 {@code detail} 取自 {@link
   * RuntimeStopState#detail()}，那是**停因建立时**存下来的字符串，只在停因发生 transition 时才重写。 实服 2026-09-17 一轮里 102
   * 段长时间保持中有 100 段（98%）的 detail 全程一个字没变，中位持续 153 秒—— 也就是说，日志只回答了「它最初为什么停」，从不回答「它一百五十秒后为什么还停着」。
   *
   * <p>代价已经付过两次，两次都是**把入场理由当成了当前状态**：
   *
   * <ul>
   *   <li>2026-09-14 第十轮：连续 106 条 {@code blockedBy=[]} 被读成「没有阻塞者」，实际是 45 分钟互锁环；
   *   <li>2026-09-17 第二十三轮：一辆 MT 的 detail 连续 183 秒写着某条 EDGE 仍被 {@code PROTECTIVE_RETAIN} 扣着，
   *       而资源生命周期（必留项，不会被预算吞掉）显示那条边在整段时间里**无人持有**。
   * </ul>
   *
   * <p>因此这里**不覆盖** {@code detail}，而是并列输出一个当场重算的量——这正是 {@code departureGateBlockedBy}
   * 立下的规矩：来源不同的两个量混进同一个字段，就是当初误导的根源。
   *
   * @param trainName 被挡住的列车名
   * @param recorded 停因建立时记下的 blocker
   * @param liveClaims 此刻的全部 claim
   * @return 当前成因摘要；记下的 blocker 若已全部消失则明确说出来
   */
  /**
   * 从「按冲突键的方向表」里归约出这辆车唯一的走廊方向；归约不出就是 {@link CorridorDirection#UNKNOWN}。
   *
   * <p>为什么需要它：等待图的割环候选要用方向来判断「谁该让」。而 {@code RuntimeDispatchService.smartPlannerForwardDirection}
   * 长期<b>无条件</b>返回 UNKNOWN， 于是候选永远选不出来。实服 2026-09-17 第二十六轮：等待图检测到环 1035 次 （3 对 WS 车占 1002 次），{@code
   * SMART_DISPATCH_CYCLE_CANDIDATE} <b>0 次</b>， 全部以 {@code INSUFFICIENT_DIRECTION_EVIDENCE} 告终；车只能等
   * {@code PROGRESS_STUCK} 在中位 592 秒后兜底，最长一辆卡了 1019 秒。
   *
   * <p>那个函数的注释本身是对的——方向只能取自 OccupancyRequest / MovementPlanSnapshot 的语义资源方向， 不能从 route 的
   * current/next 硬推。问题在于<b>真正的来源从来没有接上去</b>， 而这两样东西一直就躺在 blocker 快照里。
   *
   * <p><b>{@code unresolvedDirectionKeys} 是这里的安全红线，不是可选优化。</b> {@link
   * org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest} 的文档写明： 出现在该集合里的
   * key 表示「证据本身矛盾或不足」，任何回退推断都不得再给出方向， 否则<b>换向后的列车会沿回退链取回自己的旧方向 claim，从而绕过对向屏障</b>。 本轮堵得最久的正是 CHT
   * 折返站上的车——换向点恰恰是这条红线要防的场景。 因此：只要有<b>任何一个</b>相关 key 落在该集合里，整个归约直接返回 UNKNOWN。
   *
   * <p>缺键与「已判定不可确定」不同：缺键只说明该冲突不在本次计划内，跳过即可。
   *
   * @param directions 按单线冲突 key 的方向表
   * @param unresolvedKeys 语义解析器已明确判定不可确定方向的 key
   * @return 唯一且一致的方向；有矛盾、有 unresolved、或无证据时为 UNKNOWN
   */
  static CorridorDirection consistentCorridorDirection(
      java.util.Map<String, CorridorDirection> directions, Set<String> unresolvedKeys) {
    if (directions == null || directions.isEmpty()) {
      return CorridorDirection.UNKNOWN;
    }
    CorridorDirection agreed = null;
    for (java.util.Map.Entry<String, CorridorDirection> entry : directions.entrySet()) {
      String key = entry.getKey();
      CorridorDirection value = entry.getValue();
      if (key == null || value == null || value == CorridorDirection.UNKNOWN) {
        continue;
      }
      // 红线：该 key 已被判定"不可确定"，任何回退推断都不许再给方向。
      if (unresolvedKeys != null && unresolvedKeys.contains(key)) {
        return CorridorDirection.UNKNOWN;
      }
      if (agreed == null) {
        agreed = value;
      } else if (agreed != value) {
        // 同一辆车在不同单线区里方向相反：证据自相矛盾，按 fail-closed 处理。
        return CorridorDirection.UNKNOWN;
      }
    }
    return agreed == null ? CorridorDirection.UNKNOWN : agreed;
  }

  static String describeLiveStopCause(
      String trainName,
      java.util.Collection<RuntimeStopState.Blocker> recorded,
      java.util.Collection<OccupancyClaim> liveClaims) {
    if (recorded == null || recorded.isEmpty()) {
      return "no-recorded-blockers";
    }
    if (liveClaims == null) {
      return "claims-unavailable";
    }
    List<OccupancyClaim> stillHeld = new ArrayList<>();
    for (RuntimeStopState.Blocker blocker : recorded) {
      if (blocker == null) {
        continue;
      }
      for (OccupancyClaim claim : liveClaims) {
        if (externalOccupancyStopResourceStillHeld(trainName, blocker, claim)) {
          stillHeld.add(claim);
          break;
        }
      }
    }
    if (stillHeld.isEmpty()) {
      // 记下的阻塞者全没了，车却还停着——停因和现实脱钩，下一步该查授权链而不是查占用。
      return "recorded-blockers-all-cleared:0/" + recorded.size();
    }
    return "still-held:"
        + describeBlockerShapes(stillHeld)
        + ":"
        + stillHeld.size()
        + "/"
        + recorded.size();
  }

  /**
   * 「只在变化时输出」的去重判据：签名与上次相同则返回 false。
   *
   * <p>给那些<b>按 tick 产生、但只有变化才有信息</b>的 trace 用。灯位决策就是这样： 实服 2026-09-17 一轮里 {@code OTHER_DIAGNOSTIC}
   * 被诊断预算丢弃 <b>572957 行</b>， {@code SIGNAL_ASPECT_STAGING} 就淹在里面，日志里只剩 1224 条幸存零头——
   * 于是「绿灯为什么突然变红」这个问题根本无法回答。
   *
   * <p>但直接把它设为必留会把日志撑爆（raw 约 5000 行/分钟）。按变化去重之后， 体量退化为「灯位真的变了几次」，与车队规模同阶、不随 tick 放大，
   * 这样才能既进必留名单又不挤掉别的证据。
   *
   * <p>调用方自己持有 {@code lastByKey}，因此该函数保持无状态、可单独测。
   *
   * @param lastByKey 每个 key 上次输出的签名（会被就地更新）
   * @param key 去重维度，通常是列车名
   * @param signature 本次的签名；与上次不同才输出
   * @return 是否应当输出
   */
  static boolean shouldEmitOnChange(
      java.util.Map<String, String> lastByKey, String key, String signature) {
    if (lastByKey == null || key == null || key.isBlank() || signature == null) {
      // 拿不到去重状态时宁可输出：漏掉一次变化比多打一行代价大得多。
      return true;
    }
    return !signature.equals(lastByKey.put(key, signature));
  }

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
