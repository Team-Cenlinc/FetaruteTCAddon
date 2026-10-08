package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 生成 SpawnTicket 的管理器：只负责“产生需求”，不负责实际出车。 */
public interface SpawnManager {

  /** 重载时需要原样迁移的队列与服务生成游标。 */
  record ReplacementSnapshot(
      List<SpawnTicket> queuedTickets,
      Map<SpawnServiceKey, Instant> nextDueAtByService,
      long nextSequence) {
    public ReplacementSnapshot {
      queuedTickets = queuedTickets == null ? List.of() : List.copyOf(queuedTickets);
      nextDueAtByService = nextDueAtByService == null ? Map.of() : Map.copyOf(nextDueAtByService);
      nextSequence = Math.max(0L, nextSequence);
    }
  }

  /**
   * 推进内部状态并提取“已到点可尝试”的票据列表。
   *
   * @param provider 存储提供者（用于刷新计划）
   * @param now 当前时间
   */
  List<SpawnTicket> pollDueTickets(StorageProvider provider, Instant now);

  /** 将失败票据重新入队（用于重试）。 */
  void requeue(SpawnTicket ticket);

  /** 标记票据已完成（成功出车），用于释放 backlog 容量。 */
  void complete(SpawnTicket ticket);

  /**
   * 撤回还在队列里的票据（叫车取消）。
   *
   * @param ticketId 票据 id
   * @return 票据在队列里并已撤回时为 true
   */
  default boolean withdraw(java.util.UUID ticketId) {
    return false;
  }

  /** 返回当前计划快照（用于诊断输出）。 */
  SpawnPlan snapshotPlan();

  /** 返回当前队列快照（用于 ETA/诊断）。 */
  default List<SpawnTicket> snapshotQueue() {
    return List.of();
  }

  /** 返回重载所需的队列、服务生成游标与全局序号快照。 */
  default ReplacementSnapshot snapshotForReplacement() {
    return new ReplacementSnapshot(snapshotQueue(), Map.of(), 0L);
  }

  /**
   * 恢复被替换调度器的精确状态。
   *
   * <p>实现必须按 ticket id 去重，把额外 pending 票据纳入 backlog，并优先恢复原服务的 {@code nextDueAt}，不能从旧票据反推已经推进过的计划游标。
   *
   * @param snapshot 原调度器快照
   * @param additionalTickets 原 assigner 持有但不在 queue 中的票据
   * @param restoredAt 重载现实时间
   */
  void restoreForReplacement(
      ReplacementSnapshot snapshot, List<SpawnTicket> additionalTickets, Instant restoredAt);
}
