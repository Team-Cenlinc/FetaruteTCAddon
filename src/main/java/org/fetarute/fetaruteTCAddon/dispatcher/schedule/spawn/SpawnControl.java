package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * 动态发车闸门。
 *
 * <p>SpawnControl 只处理“是否还能给某条线路再放出一个发车动作”的容量判断，不生成票据，也不负责 TrainCarts 实体操作。它通过短租约把普通发车、fallback
 * 补发、Layover 复用和回库 RETURN 统一纳入同一容量窗口，避免不同入口各自判断导致瞬时超发。
 */
public final class SpawnControl {

  /** 租约默认保留时间：覆盖 spawn 成功后运行时进度尚未刷新到快照的短窗口。 */
  private static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(5);

  private final Duration leaseTtl;
  private final Map<String, LeaseState> leases = new LinkedHashMap<>();

  /** 使用默认租约保留时间创建闸门。 */
  public SpawnControl() {
    this(DEFAULT_LEASE_TTL);
  }

  /**
   * 创建闸门。
   *
   * @param leaseTtl 租约最长保留时间；空值或非正数时使用默认值
   */
  public SpawnControl(Duration leaseTtl) {
    this.leaseTtl =
        leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()
            ? DEFAULT_LEASE_TTL
            : leaseTtl;
  }

  /**
   * 尝试获取发车租约。
   *
   * <p>当线路没有配置容量上限时仍会返回可释放租约，用于同一 ticket 的重复发车防抖。调用方应在发车失败时释放租约；发车成功时可以保留到 TTL 自动过期，覆盖 TrainCarts
   * 事件与运行时快照之间的短暂空窗。
   *
   * @param request 发车控制请求
   * @return 放行或拒绝结果
   */
  public synchronized Decision tryAcquire(Request request) {
    Objects.requireNonNull(request, "request");
    Instant now = request.now() == null ? Instant.now() : request.now();
    pruneExpired(now);
    String ownerKey = normalizeOwnerKey(request.ownerKey());
    if (ownerKey.isEmpty()) {
      return Decision.denied("owner-missing", Snapshot.empty(request.lineId()));
    }
    if (leases.containsKey(ownerKey)) {
      return Decision.denied("lease-active", snapshotFor(request.lineId(), request.baseCounters()));
    }

    Snapshot snapshot = snapshotFor(request.lineId(), request.baseCounters());
    OptionalInt maxTrains = request.maxTrains();
    if (maxTrains != null && maxTrains.isPresent() && snapshot.total() >= maxTrains.getAsInt()) {
      return Decision.denied("line-cap", snapshot);
    }

    LeaseState state =
        new LeaseState(
            ownerKey,
            request.lineId(),
            request.routeId(),
            request.ticketId(),
            request.trainName(),
            request.kind(),
            now,
            now.plus(leaseTtl));
    leases.put(ownerKey, state);
    return Decision.allowed(
        new Lease(this, ownerKey), snapshotFor(request.lineId(), request.baseCounters()));
  }

  /**
   * 清理已过期租约。
   *
   * @param now 当前时间
   */
  public synchronized void pruneExpired(Instant now) {
    Instant effectiveNow = now == null ? Instant.now() : now;
    leases.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(effectiveNow));
  }

  /**
   * 返回指定线路的容量快照。
   *
   * @param lineId 线路 ID
   * @param baseCounters 外部统计的运行中与 pending 基础计数
   * @return 容量快照
   */
  public synchronized Snapshot snapshotFor(UUID lineId, BaseCounters baseCounters) {
    BaseCounters counters = baseCounters == null ? BaseCounters.empty() : baseCounters;
    int spawnReserved = 0;
    int layoverReserved = 0;
    int reclaimReturn = 0;
    for (LeaseState state : leases.values()) {
      if (state == null || lineId == null || !lineId.equals(state.lineId())) {
        continue;
      }
      if (state.kind() == LeaseKind.LAYOVER_REUSE) {
        layoverReserved++;
      } else if (state.kind() == LeaseKind.RECLAIM_RETURN) {
        reclaimReturn++;
      } else {
        spawnReserved++;
      }
    }
    return new Snapshot(
        lineId,
        counters.running(),
        counters.pending(),
        spawnReserved,
        layoverReserved,
        reclaimReturn);
  }

  private synchronized void release(String ownerKey) {
    String key = normalizeOwnerKey(ownerKey);
    if (!key.isEmpty()) {
      leases.remove(key);
    }
  }

  private static String normalizeOwnerKey(String ownerKey) {
    return ownerKey == null ? "" : ownerKey.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /** 租约类型，用于诊断区分不同发车入口。 */
  public enum LeaseKind {
    SPAWN,
    FALLBACK,
    LAYOVER_REUSE,
    RECLAIM_RETURN
  }

  /**
   * 外部基础计数。
   *
   * @param running 当前目标线路上已运行/已待命的列车数
   * @param pending 已出票但尚未发车的目标线路票据数
   */
  public record BaseCounters(int running, int pending) {
    public BaseCounters {
      running = Math.max(0, running);
      pending = Math.max(0, pending);
    }

    public static BaseCounters empty() {
      return new BaseCounters(0, 0);
    }
  }

  /**
   * 发车控制请求。
   *
   * @param ownerKey 去重键；同一 ticket 的普通发车与 fallback 应使用同一个 key
   * @param lineId 目标线路 ID
   * @param routeId 目标 route ID
   * @param ticketId 发车票据 ID
   * @param trainName 目标列车名，Layover/RETURN 复用时可用
   * @param kind 发车入口类型
   * @param maxTrains 线路容量上限；empty 表示不限制
   * @param baseCounters 外部基础计数
   * @param now 当前时间
   */
  public record Request(
      String ownerKey,
      UUID lineId,
      UUID routeId,
      UUID ticketId,
      Optional<String> trainName,
      LeaseKind kind,
      OptionalInt maxTrains,
      BaseCounters baseCounters,
      Instant now) {
    public Request {
      Objects.requireNonNull(lineId, "lineId");
      trainName = trainName == null ? Optional.empty() : trainName;
      kind = kind == null ? LeaseKind.SPAWN : kind;
      maxTrains = maxTrains == null ? OptionalInt.empty() : maxTrains;
      baseCounters = baseCounters == null ? BaseCounters.empty() : baseCounters;
    }
  }

  /**
   * 发车容量快照。
   *
   * @param lineId 线路 ID
   * @param running 已运行/待命列车数
   * @param pending 已出票未发车票据数
   * @param spawnReserved 普通 spawn/fallback 短租约数
   * @param layoverReserved Layover 复用短租约数
   * @param reclaimReturn 回库 RETURN 短租约数
   */
  public record Snapshot(
      UUID lineId,
      int running,
      int pending,
      int spawnReserved,
      int layoverReserved,
      int reclaimReturn) {
    public Snapshot {
      running = Math.max(0, running);
      pending = Math.max(0, pending);
      spawnReserved = Math.max(0, spawnReserved);
      layoverReserved = Math.max(0, layoverReserved);
      reclaimReturn = Math.max(0, reclaimReturn);
    }

    public static Snapshot empty(UUID lineId) {
      return new Snapshot(lineId, 0, 0, 0, 0, 0);
    }

    /**
     * @return 该线路当前占用的总容量。
     */
    public int total() {
      return running + pending + spawnReserved + layoverReserved + reclaimReturn;
    }
  }

  /**
   * 发车控制结果。
   *
   * @param allowed 是否放行
   * @param reason 拒绝原因；放行时为空
   * @param lease 放行租约
   * @param snapshot 判定时容量快照
   */
  public record Decision(boolean allowed, String reason, Optional<Lease> lease, Snapshot snapshot) {
    public Decision {
      lease = lease == null ? Optional.empty() : lease;
      snapshot = snapshot == null ? Snapshot.empty(null) : snapshot;
    }

    private static Decision allowed(Lease lease, Snapshot snapshot) {
      return new Decision(true, "", Optional.of(lease), snapshot);
    }

    private static Decision denied(String reason, Snapshot snapshot) {
      return new Decision(false, reason == null ? "unknown" : reason, Optional.empty(), snapshot);
    }
  }

  /** 可释放的发车租约。 */
  public static final class Lease implements AutoCloseable {
    private final SpawnControl owner;
    private final String ownerKey;
    private boolean released;

    private Lease(SpawnControl owner, String ownerKey) {
      this.owner = owner;
      this.ownerKey = ownerKey;
    }

    /** 释放该租约。重复释放是安全的。 */
    public void release() {
      if (released) {
        return;
      }
      released = true;
      owner.release(ownerKey);
    }

    @Override
    public void close() {
      release();
    }
  }

  private record LeaseState(
      String ownerKey,
      UUID lineId,
      UUID routeId,
      UUID ticketId,
      Optional<String> trainName,
      LeaseKind kind,
      Instant acquiredAt,
      Instant expiresAt) {
    private LeaseState {
      trainName = trainName == null ? Optional.empty() : trainName;
    }
  }
}
