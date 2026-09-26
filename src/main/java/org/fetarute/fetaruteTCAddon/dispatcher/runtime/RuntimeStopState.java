package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;

/**
 * 当前生效的运行时停车状态。
 *
 * <p>该模型把“为什么停”“什么条件解除”“由什么事件重评”与具体 blocker 资源集中在一个接口中，避免硬停车、普通占用等待和计划停车各自只写一段不可关联的字符串日志。 它只描述当前
 * STOP 生命周期，不授予通行权；解除 STOP 后仍必须重新取得有效 Movement Authority。
 *
 * @param trainName 规范列车名
 * @param reasonCode 稳定停因代码
 * @param detail 触发入口提供的详细原因
 * @param releaseCondition 解除 STOP 前必须成立的条件
 * @param retryTrigger 触发重新评估的事件
 * @param blockers 当前阻塞资源快照；按资源、owner 与角色去重排序，不把枚举顺序变化视为新停因
 * @param invalidatesAuthority 是否已经撤销既有 Movement Authority
 * @param enteredAt 本轮 STOP 生命周期开始时间
 */
public record RuntimeStopState(
    String trainName,
    String reasonCode,
    String detail,
    ReleaseCondition releaseCondition,
    RetryTrigger retryTrigger,
    List<Blocker> blockers,
    boolean invalidatesAuthority,
    Instant enteredAt) {

  /** STOP 的安全解除条件。 */
  public enum ReleaseCondition {
    /** 所有列出的 blocker 已释放或安全转移。 */
    BLOCKING_RESOURCES_RELEASED_OR_TRANSFERRED,

    /** blocker 已清除，并重新签发完整硬授权。 */
    BLOCKING_RESOURCES_RELEASED_AND_AUTHORITY_REISSUED,

    /** 规范路径与可见清出点重新完整可证。 */
    COMPLETE_ROUTE_WITH_VISIBLE_EXIT_RESTORED,

    /** 图、Route 与进度等安全状态恢复，并重新签发授权。 */
    SAFETY_STATE_RESTORED_AND_AUTHORITY_REISSUED,

    /** TrainCarts 目的地重新可达，并重新签发授权。 */
    DESTINATION_PATH_REACHABLE_AND_AUTHORITY_REISSUED,

    /** 互卡赢家或资源释放已经被确认。 */
    DEADLOCK_WINNER_OR_RESOURCE_RELEASE_CONFIRMED,

    /** Layover 已就绪并取得新的离站授权。 */
    LAYOVER_READY_AND_AUTHORITY_REISSUED,

    /** 终点折返、回库或销毁生命周期已完成。 */
    TERMINAL_LIFECYCLE_COMPLETED,

    /** 门控、停站或 dwell 等计划停车条件已完成。 */
    PLANNED_STOP_COMPLETED,

    /** Drain authority、实际 leader 与冲突区归属已经重新一致。 */
    DRAIN_AUTHORITY_AND_LEADER_REVALIDATED,

    /** 已重新建立并验证有效硬授权。 */
    ACTIVE_AUTHORITY_REISSUED,

    /** 异常现场已完成原子占用重建并提交。 */
    FIELD_RECONSTRUCTION_COMMITTED
  }

  /** 负责触发 STOP 重新评估的权威事件源。 */
  public enum RetryTrigger {
    OCCUPANCY_CHANGE_OR_PERIODIC_RECHECK,
    GRAPH_ROUTE_OR_PROGRESS_REFRESH,
    HEALTH_CHECK_OR_OCCUPANCY_CHANGE,
    LAYOVER_RECHECK,
    TERMINAL_EVENT,
    DWELL_OR_DOOR_EVENT,
    OCCUPANCY_OR_PROGRESS_CHANGE_OR_PERIODIC_RECHECK,
    PERIODIC_RECHECK,
    STARTUP_RECONSTRUCTION_RETRY
  }

  /**
   * STOP 时观察到的单个 blocker。
   *
   * @param resource 规范占用资源
   * @param owner 当前 owner
   * @param role owner 的 claim 角色
   */
  public record Blocker(String resource, String owner, String role) {
    public Blocker {
      resource = normalize(resource, "-");
      owner = normalize(owner, "-");
      role = normalize(role, "-");
    }
  }

  public RuntimeStopState {
    trainName = normalize(trainName, "-");
    reasonCode = normalize(reasonCode, "UNKNOWN");
    detail = normalize(detail, "-");
    releaseCondition = Objects.requireNonNull(releaseCondition, "releaseCondition");
    retryTrigger = Objects.requireNonNull(retryTrigger, "retryTrigger");
    blockers =
        blockers == null
            ? List.of()
            : List.copyOf(
                blockers.stream()
                    .distinct()
                    .sorted(
                        Comparator.comparing(Blocker::resource)
                            .thenComparing(Blocker::owner)
                            .thenComparing(Blocker::role))
                    .toList());
    enteredAt = enteredAt == null ? Instant.now() : enteredAt;
  }

  /** 构造不会撤销旧 token 的普通占用等待。 */
  public static RuntimeStopState occupancyHold(
      String trainName, String reasonCode, OccupancyDecision decision, Instant now) {
    return occupancyHold(trainName, reasonCode, decision, null, now);
  }

  /**
   * 构造闭塞等待，并允许调用方显式给出明细。
   *
   * <p>{@link OccupancyDecision#reason()} 的默认值是字面量 {@code "none"}——它表示“没人填过原因”， 而不是“原因是
   * none”。直接印出来会让读日志的人以为这就是结论。调用方知道原因时应当传进来； 传不进来时下面会把这个空缺**显式**标出，而不是伪装成一个结论。
   */
  public static RuntimeStopState occupancyHold(
      String trainName, String reasonCode, OccupancyDecision decision, String detail, Instant now) {
    String safeReasonCode = normalize(reasonCode, "BLOCKED_BY_OCCUPANCY");
    String decisionReason = decision == null ? null : decision.reason();
    String resolved;
    if (detail != null && !detail.isBlank()) {
      resolved = detail;
    } else if (decision == null) {
      resolved = "occupancy-decision-missing";
    } else if (decisionReason == null
        || decisionReason.isBlank()
        || "none".equals(decisionReason)) {
      resolved = safeReasonCode.toLowerCase(java.util.Locale.ROOT) + ":no-decision-reason";
    } else {
      resolved = decisionReason;
    }
    return new RuntimeStopState(
        trainName,
        safeReasonCode,
        stopDetailToken(resolved, HardStopReason.UNKNOWN),
        ReleaseCondition.BLOCKING_RESOURCES_RELEASED_OR_TRANSFERRED,
        RetryTrigger.OCCUPANCY_CHANGE_OR_PERIODIC_RECHECK,
        blockers(decision),
        false,
        now);
  }

  /** 构造撤销既有 Movement Authority 的硬停车。 */
  public static RuntimeStopState hardStop(
      String trainName, HardStopReason reason, OccupancyDecision decision, Instant now) {
    HardStopReason safeReason = reason == null ? HardStopReason.UNKNOWN : reason;
    // 没有 decision 就没有原因可写。此前这里回落到枚举名小写（safety_state_unavailable），
    // 读日志时与真实明细（safety-state-unavailable:xxx）长得几乎一样，却什么都没说——
    // 实服 2026-09-13 有 122 次 invalidatesAuthority=true 的硬停车因此无法归因。
    // 回落值必须自报"我没有原因"，而不是复述停因代码。
    return hardStop(
        trainName,
        safeReason,
        decision == null
            ? safeReason.name().toLowerCase(java.util.Locale.ROOT) + ":no-decision-context"
            : decision.reason(),
        blockers(decision),
        now);
  }

  /**
   * 构造撤销既有 Movement Authority 的硬停车，并显式说明原因。
   *
   * <p>供没有 {@link OccupancyDecision}（因而没有 blocker 列表）但**知道自己为什么停**的路径使用。
   *
   * <p>存在的理由是日志预算：{@code SMART_STOP_LIFECYCLE} 属于必留的事务审计，而承载原因的那些 trace（如 {@code
   * SMART_POTENTIAL_PHYSICAL_CHANGE_CONTAINED}）受普通观察预算门控。实服丢弃率 89% 时，停车本身必然留痕、原因却必然丢失。**fail-closed
   * 停车的原因必须写在必留的那一行里。**
   */
  public static RuntimeStopState hardStop(
      String trainName, HardStopReason reason, String detail, Instant now) {
    HardStopReason safeReason = reason == null ? HardStopReason.UNKNOWN : reason;
    return hardStop(trainName, safeReason, detail, List.of(), now);
  }

  private static RuntimeStopState hardStop(
      String trainName,
      HardStopReason safeReason,
      String detail,
      List<Blocker> blockers,
      Instant now) {
    ReleaseCondition releaseCondition = hardReleaseCondition(safeReason, !blockers.isEmpty());
    return new RuntimeStopState(
        trainName,
        safeReason.name(),
        stopDetailToken(detail, safeReason),
        releaseCondition,
        hardRetryTrigger(safeReason),
        blockers,
        true,
        now);
  }

  /**
   * 把明细压成单个 token。
   *
   * <p>明细会以 {@code detail=<值> releaseCondition=…} 的形式写进空格分隔的 trace；内部空白会把该行切断，
   * 让后续字段错位。这里统一折叠，调用方不必各自记得。
   */
  private static String stopDetailToken(String detail, HardStopReason safeReason) {
    if (detail == null || detail.isBlank()) {
      return safeReason.name().toLowerCase(java.util.Locale.ROOT) + ":no-detail";
    }
    return detail.trim().replaceAll("\\s+", "_");
  }

  /** 构造信号发布门的本地可恢复 STOP；既有 token 保留，但不得维持可见通行信号。 */
  public static RuntimeStopState publicationGateHold(
      String trainName, String publicationReason, OccupancyDecision decision, Instant now) {
    return new RuntimeStopState(
        trainName,
        "PUBLICATION_GATE_LOCAL_STOP",
        normalize(publicationReason, "publication-gate-local-stop"),
        ReleaseCondition.DRAIN_AUTHORITY_AND_LEADER_REVALIDATED,
        RetryTrigger.OCCUPANCY_OR_PROGRESS_CHANGE_OR_PERIODIC_RECHECK,
        blockers(decision),
        false,
        now);
  }

  /** 构造缺少业务停因上下文的 fail-closed STOP，并要求恢复安全状态后重新签发授权。 */
  public static RuntimeStopState stopContextMissing(String trainName, Instant now) {
    return new RuntimeStopState(
        trainName,
        "STOP_CONTEXT_MISSING",
        "stop-published-without-specific-context",
        ReleaseCondition.SAFETY_STATE_RESTORED_AND_AUTHORITY_REISSUED,
        RetryTrigger.GRAPH_ROUTE_OR_PROGRESS_REFRESH,
        List.of(),
        true,
        now);
  }

  /** 构造异常物理编组隔离 STOP；只有现场占用重建完整提交后才允许解除。 */
  public static RuntimeStopState abnormalPhysicalQuarantine(
      String trainName, String detail, Instant now) {
    return new RuntimeStopState(
        trainName,
        "ABNORMAL_PHYSICAL_QUARANTINE",
        normalize(detail, "abnormal-physical-identity"),
        ReleaseCondition.FIELD_RECONSTRUCTION_COMMITTED,
        RetryTrigger.STARTUP_RECONSTRUCTION_RETRY,
        List.of(),
        true,
        now);
  }

  /** 构造门控、停站、Layover 或终点等计划停车。 */
  public static RuntimeStopState plannedStop(
      String trainName,
      String reasonCode,
      String detail,
      ReleaseCondition releaseCondition,
      RetryTrigger retryTrigger,
      Instant now) {
    return new RuntimeStopState(
        trainName, reasonCode, detail, releaseCondition, retryTrigger, List.of(), false, now);
  }

  /**
   * 释放条件是否属于例行停车：停站/门控、折返待命、终点作业。其余（信号、占用、授权、尾保、安全状态不可用等）都是扣停。
   *
   * <p>只看释放条件：门控停车在停站结束后仍是例行停车，是否已经“滞留”要结合停站计时与计划发车另行判断 （见 {@code
   * EtaService#currentHold}）。只供展示与估算，不参与控车判定。
   */
  public boolean routineStop() {
    return releaseCondition == ReleaseCondition.PLANNED_STOP_COMPLETED
        || releaseCondition == ReleaseCondition.LAYOVER_READY_AND_AUTHORITY_REISSUED
        || releaseCondition == ReleaseCondition.TERMINAL_LIFECYCLE_COMPLETED;
  }

  /** 判断两次刷新是否属于同一条 STOP 生命周期。 */
  public boolean sameLifecycle(RuntimeStopState other) {
    return other != null
        && reasonCode.equals(other.reasonCode)
        && detail.equals(other.detail)
        && releaseCondition == other.releaseCondition
        && retryTrigger == other.retryTrigger
        && blockers.equals(other.blockers)
        && invalidatesAuthority == other.invalidatesAuthority;
  }

  private static ReleaseCondition hardReleaseCondition(HardStopReason reason, boolean hasBlockers) {
    if (hasBlockers) {
      return ReleaseCondition.BLOCKING_RESOURCES_RELEASED_AND_AUTHORITY_REISSUED;
    }
    return switch (reason) {
      case SINGLE_CORRIDOR_FAIL_CLOSED -> ReleaseCondition
          .COMPLETE_ROUTE_WITH_VISIBLE_EXIT_RESTORED;
      case SAFETY_STATE_UNAVAILABLE -> ReleaseCondition
          .SAFETY_STATE_RESTORED_AND_AUTHORITY_REISSUED;
      case UNREACHABLE_FAILOVER -> ReleaseCondition
          .DESTINATION_PATH_REACHABLE_AND_AUTHORITY_REISSUED;
        // DEADLOCK_CONFIRMED_WAITING 故意不在这里出现。
        //
        // 上面 hasBlockers 已经提前返回，所以这个 switch **只在没有任何 blocker 时执行**——
        // 也就是说 DEADLOCK_WINNER_OR_RESOURCE_RELEASE_CONFIRMED 只会在"根本没有死锁证据"时被安上，
        // 而它要求的是"决出死锁赢家或某个资源被释放"：没有 blocker 就没有赢家、也没有资源可释放，
        // **这个条件永远不可能满足**，列车就此永久挂起。
        //
        // 实服 2026-09-13 第六轮 207 条快照落在这一族。典型现场（用户报的 LWN 出库堵点）：
        //   train=SURC-WS-LC-3125 reasonCode=DEADLOCK_CONFIRMED_WAITING heldSeconds=139
        //   holdsByRole={MOVEMENT_REQUIRED=5}  blockedBy=[]  movementToken=INVALID
        // 车刚出库、五个资源全部到手、没有任何东西挡着，却被判成死锁并撤销授权；
        // 而停车本身让进度停滞，HealthMonitor 再次判定 progress-stuck 又重新施加——自我维持。
        //
        // 这条停因真正的来源也不是死锁检测，而是 TrainHealthMonitor 的兜底
        // `reapplyHardStopByName(..., "health-stop-progress-stuck")`——它只是"进度停滞、重新施加停车"。
        // 没有 blocker 时，诚实的解除条件就是 default 的 ACTIVE_AUTHORITY_REISSUED：重新签发授权即可走。
      case LAYOVER_HOLD -> ReleaseCondition.LAYOVER_READY_AND_AUTHORITY_REISSUED;
      case TERMINAL_HOLD -> ReleaseCondition.TERMINAL_LIFECYCLE_COMPLETED;
      default -> ReleaseCondition.ACTIVE_AUTHORITY_REISSUED;
    };
  }

  private static RetryTrigger hardRetryTrigger(HardStopReason reason) {
    return switch (reason) {
      case SINGLE_CORRIDOR_FAIL_CLOSED, SAFETY_STATE_UNAVAILABLE -> RetryTrigger
          .GRAPH_ROUTE_OR_PROGRESS_REFRESH;
      case DEADLOCK_CONFIRMED_WAITING -> RetryTrigger.HEALTH_CHECK_OR_OCCUPANCY_CHANGE;
      case LAYOVER_HOLD -> RetryTrigger.LAYOVER_RECHECK;
      case TERMINAL_HOLD -> RetryTrigger.TERMINAL_EVENT;
      case HARD_BLOCKER_STOP, ACQUIRE_FAILED -> RetryTrigger.OCCUPANCY_CHANGE_OR_PERIODIC_RECHECK;
      default -> RetryTrigger.PERIODIC_RECHECK;
    };
  }

  private static List<Blocker> blockers(OccupancyDecision decision) {
    if (decision == null || decision.blockers().isEmpty()) {
      return List.of();
    }
    return decision.blockers().stream()
        .filter(Objects::nonNull)
        .map(
            claim ->
                new Blocker(claim.resource().toString(), claim.trainName(), claim.role().name()))
        .toList();
  }

  private static String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
