package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一张发车票据：由 SpawnManager 生成，TicketAssigner 负责尝试放行并触发实际出库/生成。
 *
 * <p>ticket 允许重试：失败时更新 notBefore 与 attempts 并重新入队。
 */
public record SpawnTicket(
    UUID id,
    SpawnService service,
    Instant dueAt,
    Instant notBefore,
    int attempts,
    Optional<String> lastError) {
  public SpawnTicket {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(service, "service");
    dueAt = dueAt == null ? Instant.EPOCH : dueAt;
    notBefore = notBefore == null ? dueAt : notBefore;
    if (attempts < 0) {
      throw new IllegalArgumentException("attempts 不能为负");
    }
    lastError = lastError == null ? Optional.empty() : lastError;
  }

  public Instant scheduledTime() {
    return dueAt;
  }

  public SpawnTicket withRetry(Instant nextNotBefore, String error) {
    return new SpawnTicket(
        id,
        service,
        dueAt,
        nextNotBefore == null ? notBefore : nextNotBefore,
        attempts + 1,
        Optional.ofNullable(error));
  }
}
