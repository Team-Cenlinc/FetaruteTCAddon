package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 运行许可信号：用于调度层对“可进入/需等待”的粗粒度提示。
 *
 * <p>调度层不直接驱动车辆制动，仅输出许可等级供运行时层或信息系统使用。
 */
public enum SignalAspect {
  PROCEED,
  PROCEED_WITH_CAUTION,
  CAUTION,
  STOP;

  /**
   * 判断该信号是否允许列车产生前向运动。
   *
   * <p>除 STOP 外的所有信号都会在运行时形成正目标速度，因此都必须由有效的 Movement Authority 支撑；不能把 CAUTION 仅视为速度提示而绕过授权校验。
   *
   * @return 该信号是否必须持有已激活的 Movement Authority
   */
  public boolean requiresActiveMovementAuthority() {
    return this != STOP;
  }
}
