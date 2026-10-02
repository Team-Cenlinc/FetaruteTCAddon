package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;

/**
 * 运行时调度优先级解析结果。
 *
 * <p>结果对象同时携带最终 priority 与 route 识别上下文，调用方可把它直接写入 {@code OccupancyRequest}，并把 source /
 * operationType / fallbackReason 输出到诊断日志。缺失字段统一使用 {@link Optional} 暴露，避免把 UNKNOWN 与空字符串混淆。
 */
public record DispatchPriorityResolution(
    int priority,
    DispatchPrioritySource source,
    Optional<UUID> routeUuid,
    Optional<String> routeCode,
    Optional<RouteOperationType> operationType,
    String fallbackReason,
    boolean depotExitContext,
    boolean manualPriorityPresent,
    int policyBasePriority,
    int policyAdjustment) {

  public DispatchPriorityResolution(
      int priority,
      DispatchPrioritySource source,
      Optional<UUID> routeUuid,
      Optional<String> routeCode,
      Optional<RouteOperationType> operationType,
      String fallbackReason) {
    this(
        priority,
        source,
        routeUuid,
        routeCode,
        operationType,
        fallbackReason,
        false,
        false,
        priority,
        0);
  }

  public DispatchPriorityResolution {
    source = source == null ? DispatchPrioritySource.DEFAULT_UNKNOWN : source;
    routeUuid = routeUuid == null ? Optional.empty() : routeUuid;
    routeCode = normalizeOptional(routeCode);
    operationType = operationType == null ? Optional.empty() : operationType;
    fallbackReason =
        fallbackReason == null || fallbackReason.isBlank() ? "-" : fallbackReason.trim();
    Objects.requireNonNull(routeUuid, "routeUuid");
    Objects.requireNonNull(routeCode, "routeCode");
    Objects.requireNonNull(operationType, "operationType");
  }

  /** 返回是否已经识别到任一 route 身份。 */
  public boolean routeKnown() {
    return routeUuid.isPresent() || routeCode.isPresent();
  }

  private static Optional<String> normalizeOptional(Optional<String> value) {
    if (value == null || value.isEmpty()) {
      return Optional.empty();
    }
    String trimmed = value.get() == null ? "" : value.get().trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
  }
}
