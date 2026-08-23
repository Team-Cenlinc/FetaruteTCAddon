package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
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
 * @param blockers 当前阻塞资源快照
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
    blockers = blockers == null ? List.of() : List.copyOf(blockers);
    enteredAt = enteredAt == null ? Instant.now() : enteredAt;
  }

  /** 构造不会撤销旧 token 的普通占用等待。 */
  public static RuntimeStopState occupancyHold(
      String trainName, String reasonCode, OccupancyDecision decision, Instant now) {
    return new RuntimeStopState(
        trainName,
        normalize(reasonCode, "BLOCKED_BY_OCCUPANCY"),
        decision == null ? "occupancy-decision-missing" : decision.reason(),
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
    List<Blocker> blockers = blockers(decision);
    ReleaseCondition releaseCondition = hardReleaseCondition(safeReason, !blockers.isEmpty());
    return new RuntimeStopState(
        trainName,
        safeReason.name(),
        decision == null ? safeReason.name().toLowerCase(java.util.Locale.ROOT) : decision.reason(),
        releaseCondition,
        hardRetryTrigger(safeReason),
        blockers,
        true,
        now);
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
      case DEADLOCK_CONFIRMED_WAITING -> ReleaseCondition
          .DEADLOCK_WINNER_OR_RESOURCE_RELEASE_CONFIRMED;
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
    Set<Blocker> blockers = new LinkedHashSet<>();
    for (OccupancyClaim claim : decision.blockers()) {
      if (claim == null) {
        continue;
      }
      blockers.add(
          new Blocker(claim.resource().toString(), claim.trainName(), claim.role().name()));
    }
    return List.copyOf(blockers);
  }

  private static String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
