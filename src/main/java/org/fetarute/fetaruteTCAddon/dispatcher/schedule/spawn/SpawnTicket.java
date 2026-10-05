package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;

/**
 * 一张发车票据：由 SpawnManager 生成，TicketAssigner 负责尝试放行并触发实际出库/生成。
 *
 * <p>ticket 允许重试：失败时更新 notBefore 与 attempts 并重新入队。
 *
 * <p>{@code sequenceNumber} 用于在 dueAt 相同时确定顺序，避免同 line 多 route 按 routeCode 排序导致"批量"发车。
 *
 * <p>{@code firstDueAt} 记录票据首次进入计划窗口的时间；重试会推进 {@code dueAt} 以避免压住队头，但不会推进 {@code
 * firstDueAt}，用于长期运行时清理过旧票据。
 *
 * <p>{@code serviceTripId/source/priority} 是 schedule backbone 接入后的轻量扩展：旧的 headway 票据默认视为 {@link
 * TripSource#SCHEDULED}，优先级为 0；由 ServiceTrip 派生的票据可以携带更明确的计划来源和仲裁优先级。
 *
 * <p>{@code consist} 是按表出的票指定的车型（交路的车型，{@link
 * org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistKey#of} 归一后的键）：出车只能出这个车型。间隔发车与不区分车型的表为空，
 * 出车按 route 的编组方案现排。
 */
public record SpawnTicket(
    UUID id,
    SpawnService service,
    Instant dueAt,
    Instant notBefore,
    Instant firstDueAt,
    int attempts,
    long sequenceNumber,
    Optional<String> selectedDepotNodeId,
    Optional<String> lastError,
    Optional<String> serviceTripId,
    TripSource source,
    int priority,
    Optional<String> consist) {
  /** 不指定车型的票据。 */
  public SpawnTicket(
      UUID id,
      SpawnService service,
      Instant dueAt,
      Instant notBefore,
      Instant firstDueAt,
      int attempts,
      long sequenceNumber,
      Optional<String> selectedDepotNodeId,
      Optional<String> lastError,
      Optional<String> serviceTripId,
      TripSource source,
      int priority) {
    this(
        id,
        service,
        dueAt,
        notBefore,
        firstDueAt,
        attempts,
        sequenceNumber,
        selectedDepotNodeId,
        lastError,
        serviceTripId,
        source,
        priority,
        Optional.empty());
  }

  /** 使用 {@code dueAt} 作为首次计划时间创建票据。 */
  public SpawnTicket(
      UUID id,
      SpawnService service,
      Instant dueAt,
      Instant notBefore,
      int attempts,
      long sequenceNumber,
      Optional<String> selectedDepotNodeId,
      Optional<String> lastError) {
    this(
        id,
        service,
        dueAt,
        notBefore,
        dueAt,
        attempts,
        sequenceNumber,
        selectedDepotNodeId,
        lastError);
  }

  /** 使用 {@code dueAt} 作为首次计划时间创建带计划来源的票据。 */
  public SpawnTicket(
      UUID id,
      SpawnService service,
      Instant dueAt,
      Instant notBefore,
      int attempts,
      long sequenceNumber,
      Optional<String> selectedDepotNodeId,
      Optional<String> lastError,
      Optional<String> serviceTripId,
      TripSource source,
      int priority) {
    this(
        id,
        service,
        dueAt,
        notBefore,
        dueAt,
        attempts,
        sequenceNumber,
        selectedDepotNodeId,
        lastError,
        serviceTripId,
        source,
        priority);
  }

  public SpawnTicket(
      UUID id,
      SpawnService service,
      Instant dueAt,
      Instant notBefore,
      Instant firstDueAt,
      int attempts,
      long sequenceNumber,
      Optional<String> selectedDepotNodeId,
      Optional<String> lastError) {
    this(
        id,
        service,
        dueAt,
        notBefore,
        firstDueAt,
        attempts,
        sequenceNumber,
        selectedDepotNodeId,
        lastError,
        Optional.empty(),
        TripSource.SCHEDULED,
        0);
  }

  public SpawnTicket {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(service, "service");
    dueAt = dueAt == null ? Instant.EPOCH : dueAt;
    notBefore = notBefore == null ? dueAt : notBefore;
    firstDueAt = firstDueAt == null ? dueAt : firstDueAt;
    if (attempts < 0) {
      throw new IllegalArgumentException("attempts 不能为负");
    }
    selectedDepotNodeId =
        selectedDepotNodeId == null
            ? Optional.empty()
            : selectedDepotNodeId.map(String::trim).filter(s -> !s.isBlank());
    lastError = lastError == null ? Optional.empty() : lastError;
    serviceTripId =
        serviceTripId == null
            ? Optional.empty()
            : serviceTripId.map(String::trim).filter(s -> !s.isBlank());
    source = source == null ? TripSource.SCHEDULED : source;
    consist = consist == null ? Optional.empty() : consist.filter(key -> !key.isBlank());
  }

  /** 表定车次票据的 {@code serviceTripId} 前缀（{@link TimetableSpawnManager} 出的票）。 */
  public static final String TIMETABLE_TRIP_PREFIX = "TIMETABLE-";

  public Instant scheduledTime() {
    return dueAt;
  }

  /**
   * 这张票是不是按表出的：何时发车由时刻表决定，编表时已经过冲突检查。
   *
   * <p>按表运行与按间隔发车的票都标 {@link TripSource#SCHEDULED}，只能靠车次号区分。
   */
  public boolean timetableDriven() {
    return serviceTripId.map(id -> id.startsWith(TIMETABLE_TRIP_PREFIX)).orElse(false);
  }

  /**
   * 创建重试票据。
   *
   * <p>重试时会将 {@code dueAt} 推进到不早于 {@code notBefore} 的时间点，避免长期失败票据持续压住队头，造成同权重 route 饥饿。
   */
  public SpawnTicket withRetry(Instant nextNotBefore, String error) {
    return rescheduled(nextNotBefore, error, attempts + 1, Optional.empty());
  }

  /** 创建延迟票据，不增加 attempts，用于 depot 仲裁等未真正尝试的退避。 */
  public SpawnTicket delayedUntil(Instant nextNotBefore, String reason) {
    return rescheduled(nextNotBefore, reason, attempts, selectedDepotNodeId);
  }

  /**
   * 出库被闭塞挡住后的重试票据：不增加 attempts，但丢掉本次选定的 depot，下次重新挑。
   *
   * <p>挡住它的是别的车，不是这张票的过错；累到 max-attempts 会被丢掉，那是取消发车而不是推迟发车。depot 要重新挑： 多 depot 线路的 backoff
   * 只在重新选择时生效，留着旧选择就会一直撞同一个被挡住的出库点。
   */
  public SpawnTicket blockedUntil(Instant nextNotBefore, String reason) {
    return rescheduled(nextNotBefore, reason, attempts, Optional.empty());
  }

  private SpawnTicket rescheduled(
      Instant nextNotBefore, String reason, int nextAttempts, Optional<String> nextDepot) {
    Instant nextWindow = nextNotBefore == null ? notBefore : nextNotBefore;
    Instant nextDueAt = nextWindow.isAfter(dueAt) ? nextWindow : dueAt;
    return new SpawnTicket(
        id,
        service,
        nextDueAt,
        nextWindow,
        firstDueAt,
        nextAttempts,
        sequenceNumber,
        nextDepot,
        Optional.ofNullable(reason),
        serviceTripId,
        source,
        priority,
        consist);
  }

  /** 返回带有 depot 选择结果的新票据（不会修改 attempts/notBefore）。 */
  public SpawnTicket withSelectedDepot(String depotNodeId) {
    return new SpawnTicket(
        id,
        service,
        dueAt,
        notBefore,
        firstDueAt,
        attempts,
        sequenceNumber,
        Optional.ofNullable(depotNodeId),
        lastError,
        serviceTripId,
        source,
        priority,
        consist);
  }

  /** 返回指定了车型的新票据（其余不变）。 */
  public SpawnTicket withConsist(Optional<String> nextConsist) {
    return new SpawnTicket(
        id,
        service,
        dueAt,
        notBefore,
        firstDueAt,
        attempts,
        sequenceNumber,
        selectedDepotNodeId,
        lastError,
        serviceTripId,
        source,
        priority,
        nextConsist);
  }
}
