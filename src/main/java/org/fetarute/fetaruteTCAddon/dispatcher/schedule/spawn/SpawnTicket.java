package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一张发车票据：由 SpawnManager 生成，TicketAssigner 负责尝试放行并触发实际出库/生成。
 *
 * <p>ticket 允许重试：失败时更新 notBefore 与 attempts 并重新入队。
 *
 * <p>{@code sequenceNumber} 用于在 dueAt 相同时确定顺序，避免同 line 多 route 按 routeCode 排序导致"批量"发车。
 */
public record SpawnTicket(
    UUID id,
    SpawnService service,
    Instant dueAt,
    Instant notBefore,
    int attempts,
    long sequenceNumber,
    Optional<String> selectedDepotNodeId,
    Optional<String> lastError) {
  public SpawnTicket {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(service, "service");
    dueAt = dueAt == null ? Instant.EPOCH : dueAt;
    notBefore = notBefore == null ? dueAt : notBefore;
    if (attempts < 0) {
      throw new IllegalArgumentException("attempts 不能为负");
    }
    selectedDepotNodeId =
        selectedDepotNodeId == null
            ? Optional.empty()
            : selectedDepotNodeId.map(String::trim).filter(s -> !s.isBlank());
    lastError = lastError == null ? Optional.empty() : lastError;
  }

  public Instant scheduledTime() {
    return dueAt;
  }

  /**
   * 创建重试票据。
   *
   * <p>重试时会将 {@code dueAt} 推进到不早于 {@code notBefore} 的时间点，避免长期失败票据持续压住队头，造成同权重 route 饥饿。
   */
  public SpawnTicket withRetry(Instant nextNotBefore, String error) {
    Instant nextWindow = nextNotBefore == null ? notBefore : nextNotBefore;
    Instant nextDueAt = nextWindow.isAfter(dueAt) ? nextWindow : dueAt;
    return new SpawnTicket(
        id,
        service,
        nextDueAt,
        nextWindow,
        attempts + 1,
        sequenceNumber,
        Optional.empty(),
        Optional.ofNullable(error));
  }

  /** 返回带有 depot 选择结果的新票据（不会修改 attempts/notBefore）。 */
  public SpawnTicket withSelectedDepot(String depotNodeId) {
    return new SpawnTicket(
        id,
        service,
        dueAt,
        notBefore,
        attempts,
        sequenceNumber,
        Optional.ofNullable(depotNodeId),
        lastError);
  }
}
