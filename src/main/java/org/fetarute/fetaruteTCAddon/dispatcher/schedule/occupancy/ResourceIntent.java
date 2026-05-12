package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 占用请求中单个资源的语义用途。
 *
 * <p>同一个 {@link OccupancyRequest} 可以同时携带“前进必须资源”和“保留/保护资源”。信号授权只能把 {@link #MOVEMENT_REQUIRED} 当作
 * fail-closed 边界；其它意图用于维持保护、队列位次或诊断，不得反向把前车打成 STOP。
 */
public enum ResourceIntent {
  /** 前进授权必须获取的资源。 */
  MOVEMENT_REQUIRED,
  /** 当前位置或车尾保护资源，只能尽力保留。 */
  PROTECTIVE_RETAIN,
  /** 冲突队列位次资源，不代表立即占用前方窗口。 */
  QUEUE_POSITION,
  /** 停车/等待期间保留已有 claim 的资源。 */
  HOLD_ONLY,
  /** 只用于预览距离或诊断，不参与硬授权。 */
  LOOKAHEAD_PREVIEW;

  /** 是否代表本次运动授权的硬边界。 */
  public boolean hardAuthority() {
    return this == MOVEMENT_REQUIRED;
  }
}
