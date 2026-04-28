package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Depot 发车请求的统一仲裁点。
 *
 * <p>该接口只处理“哪些 depot 请求在本 tick 可以尝试”，不直接访问 TrainCarts，也不写 occupancy claim。第一阶段的目标是让多个 line 共用同一
 * depot 时先在 spawn 层收敛为确定性顺序，再进入全局 {@code OccupancyManager}。
 */
public interface DepotDispatchCoordinator {

  /**
   * 对到期票据做 depot 维度仲裁。
   *
   * @param tickets 到期票据
   * @param now 当前时间
   * @return ready 为本 tick 可尝试票据；deferred 为应重新入队的退避票据
   */
  DispatchBatch coordinate(List<SpawnTicket> tickets, Instant now);

  /**
   * 记录某个 depot 申请发生 occupancy/gate 失败。
   *
   * <p>实现可据此对同 depot 后续票据做短暂 backoff，避免多个请求在同一 tick 或相邻 tick 反复抢同一资源。
   */
  default void recordOccupancyFailure(SpawnTicket ticket, Instant now) {}

  /**
   * 查询指定 depot 当前是否处于退避窗口。
   *
   * <p>选择器可用该信息避开刚被 gate 阻塞的 depot，把同线路票据切换到其它候选 depot。
   */
  default Optional<Instant> backoffUntil(String depotNodeId, Instant now) {
    return Optional.empty();
  }

  /** 仲裁结果。 */
  record DispatchBatch(List<SpawnTicket> ready, List<SpawnTicket> deferred) {
    public DispatchBatch {
      ready = ready == null ? List.of() : List.copyOf(ready);
      deferred = deferred == null ? List.of() : List.copyOf(deferred);
    }
  }
}
