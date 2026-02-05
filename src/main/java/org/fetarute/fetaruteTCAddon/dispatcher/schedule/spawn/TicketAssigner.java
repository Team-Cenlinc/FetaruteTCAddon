package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ServiceTicket;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 尝试将 SpawnTicket 变成真实列车或将 ticket 分配给待命列车（未来扩展）。 */
public interface TicketAssigner {

  /** 执行一次调度 tick（建议由 Bukkit task 驱动）。 */
  void tick(StorageProvider provider, Instant now);

  /**
   * 强制分配一张票据（不经 SpawnManager 队列），直接派发给指定列车。
   *
   * @param trainName 目标列车名
   * @param ticket 服务票据
   * @return 分配成功返回 true
   */
  default boolean forceAssign(String trainName, ServiceTicket ticket) {
    return false;
  }

  /**
   * Layover 注册事件回调：有列车进入待命池时触发。
   *
   * <p>默认不处理，具体行为由实现类决定。
   */
  default void onLayoverRegistered(LayoverRegistry.LayoverCandidate candidate) {}

  /** 返回待分配/等待 Layover 的票据快照（用于 ETA/诊断）。 */
  default java.util.List<SpawnTicket> snapshotPendingTickets() {
    return java.util.List.of();
  }

  /**
   * 清理待分配/等待 Layover 的票据。
   *
   * @return 清理的票据数量
   */
  default int clearPendingTickets() {
    return 0;
  }

  /** 清理出车诊断计数（成功/重试/错误分布）。 */
  default void resetDiagnostics() {}
}
