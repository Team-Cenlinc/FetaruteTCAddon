package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 提供停车换向期间的原子行车授权交接能力。
 *
 * <p>普通 {@link OccupancyManager#acquire(OccupancyRequest)}
 * 必须拒绝同一列车直接翻转既有单线方向；终点折返则需要在不暴露资源空窗的前提下结束旧授权并建立新授权。因此该能力与普通刷新分离，只供已经确认停车的运行时折返流程调用。
 */
public interface AuthorityHandoffSupport {

  /**
   * 将列车当前持有的行车授权原子替换为下一段授权。
   *
   * <p>实现必须先校验外部 blocker 与 Gate Queue；拒绝时保留旧 claim，成功时其它线程或同步事件只能看到完整的旧状态或完整的新状态。未被新窗口覆盖且没有
   * rear-clear 证据的旧物理资源必须继续作为专用硬占用，不能仅因列车静止或普通保护刷新就释放。
   *
   * @param nextAuthority 下一段完整硬授权，列车名仍为当前 TrainCarts 名称
   * @return 是否完成授权交接；拒绝结果必须携带实际 blocker
   */
  OccupancyDecision handoffAuthority(OccupancyRequest nextAuthority);

  /**
   * 原子迁移某列车的 claim、队列与冲突锁 owner。
   *
   * @param currentTrainName 当前列车名
   * @param nextTrainName 新列车名
   * @return 是否完成迁移；源名称没有占用状态视为成功的 no-op，目标名称已有占用状态时必须拒绝且不修改旧状态
   */
  boolean migrateAuthorityOwner(String currentTrainName, String nextTrainName);

  /**
   * 校验请求中的全部硬授权当前是否仍由请求列车持有。
   *
   * @param authority 待校验的授权请求
   * @return 所有硬资源、角色及已知单线方向均与占用账本一致时返回 {@code true}
   */
  boolean holdsHardAuthority(OccupancyRequest authority);
}
