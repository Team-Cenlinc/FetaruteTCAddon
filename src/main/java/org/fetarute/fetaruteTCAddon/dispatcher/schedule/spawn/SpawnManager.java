package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.List;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 生成 SpawnTicket 的管理器：只负责“产生需求”，不负责实际出车。 */
public interface SpawnManager {

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

  /** 返回当前计划快照（用于诊断输出）。 */
  SpawnPlan snapshotPlan();
}
