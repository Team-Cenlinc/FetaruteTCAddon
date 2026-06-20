package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;

/**
 * 运行时进路竞争的相对优先级策略。
 *
 * <p>这些分值只用于 Occupancy Gate Queue 的稳定排序，不授予额外 movement authority，也不覆盖 NODE/EDGE 硬占用、对向
 * single-region 硬屏障或 final signal gate。人工 {@code FTA_PRIORITY} 仍作为最高层覆盖，由调用方在进入本策略前处理。
 */
public final class DispatchPriorityPolicy {

  /** 正线运营班次：默认压过 depot 出库与回库回送。 */
  public static final int OPERATION_OFFSET = 20;

  /** Depot 出库/补车：低于正线运营，高于回库回送。 */
  public static final int DEPOT_EXIT_OFFSET = 10;

  /** 回库/回送：默认让行，避免摘车压住补车与正线运营。 */
  public static final int RETURN_OFFSET = -10;

  private DispatchPriorityPolicy() {}

  /**
   * 计算 route operation 的队列偏移。
   *
   * @param operationType route 运营类型；为空时不引入偏移
   * @param depotExitContext 当前请求是否位于 depot 出库/切入主线语义
   * @return 应叠加到 operator/service priority 上的相对偏移
   */
  public static int operationOffset(RouteOperationType operationType, boolean depotExitContext) {
    if (operationType == null) {
      return 0;
    }
    if (operationType == RouteOperationType.RETURN) {
      return RETURN_OFFSET;
    }
    if (depotExitContext || operationType == RouteOperationType.CREATE) {
      return DEPOT_EXIT_OFFSET;
    }
    return OPERATION_OFFSET;
  }

  /**
   * 组合常规运行请求优先级。
   *
   * @param basePriority operator/service 层已有优先级
   * @param operationType route 运营类型
   * @param depotExitContext 当前请求是否处于 depot 出库语义
   * @return 最终用于 OccupancyRequest 的优先级
   */
  public static int runtimePriority(
      int basePriority, RouteOperationType operationType, boolean depotExitContext) {
    return basePriority + operationOffset(operationType, depotExitContext);
  }

  /**
   * 组合 depot spawn gate 优先级。
   *
   * <p>历史实现给 depot spawn 固定 +100，会使出库车天然压过正线车。这里保留 ticket priority 的调度含义， 但把 route operation
   * 的相对关系收敛到“正线 OPERATION > depot exit/CREATE > RETURN”。
   *
   * @param operationType route 运营类型
   * @param ticketPriority 发车票据携带的计划优先级
   * @return depot spawn gate 的队列优先级
   */
  public static int depotSpawnPriority(RouteOperationType operationType, int ticketPriority) {
    return ticketPriority + operationOffset(operationType, true);
  }
}
