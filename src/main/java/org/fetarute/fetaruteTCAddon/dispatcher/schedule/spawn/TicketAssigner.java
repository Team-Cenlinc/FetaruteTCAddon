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
   * 强制分配一张票据，并携带存储上下文供实现执行 SpawnControl 容量判定。
   *
   * <p>默认回退到旧入口，兼容不关心容量上下文的实现。
   *
   * @param provider 存储提供者
   * @param trainName 目标列车名
   * @param ticket 服务票据
   * @return 分配成功返回 true
   */
  default boolean forceAssign(StorageProvider provider, String trainName, ServiceTicket ticket) {
    return forceAssign(trainName, ticket);
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
   * 撤回一张还没派出的票（叫车取消）：在发车队列或等待待命车的票撤掉；已进入实体化或折返交接的票不撤。
   *
   * @param ticketId 票据 id
   * @return 撤回了时为 true
   */
  default boolean withdraw(java.util.UUID ticketId) {
    return false;
  }

  /**
   * 票据是否还在处理中：在发车队列里、在等待待命车，或已进入实体化事务。
   *
   * @param ticketId 票据 id
   */
  default boolean isTicketLive(java.util.UUID ticketId) {
    return false;
  }

  /**
   * 追加派发成功的观察者（在主回调之后调用）。
   *
   * @param observer 票据与最终派出的列车名
   */
  default void addDispatchObserver(java.util.function.BiConsumer<SpawnTicket, String> observer) {}

  /**
   * 清理待分配/等待 Layover 的票据。
   *
   * @return 清理的票据数量
   */
  default int clearPendingTickets() {
    return 0;
  }

  /**
   * 在重载或停用前安全收口已经实体化的发车事务。
   *
   * @param now 当前现实时间
   * @return 不再持有任何必须由本实例继续恢复的物理事务时为 {@code true}
   */
  default boolean prepareForReplacement(Instant now) {
    return true;
  }

  /** 清理出车诊断计数（成功/重试/错误分布）。 */
  default void resetDiagnostics() {}

  /**
   * 重启或重载后找回在车库等候的提前出车：启动恢复在打开授权门之前调用。默认不处理。
   *
   * @param trains 列车
   * @param now 当前时刻
   */
  default void restoreEarlySpawnHolds(
      java.util.Collection<
              ? extends org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeTrainHandle>
          trains,
      Instant now) {}
}
