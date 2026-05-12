package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

/** 运行时硬停车原因，用于区分闭塞安全 STOP 与计划停站 STOP。 */
public enum HardStopReason {
  /** 前方存在 NODE/EDGE 等真实硬 blocker。 */
  HARD_BLOCKER_STOP,

  /** canEnter/preview 判定不允许继续进入前方授权窗口。 */
  AUTHORIZATION_FAILURE,

  /** 事件驱动链路收到 STOP，需要绕过周期 tick 立即停车。 */
  EVENT_STOP,

  /** preview 允许但 acquire 阶段失败。 */
  ACQUIRE_FAILED,

  /** Movement Authority 判定授权窗口末端不足以安全制动。 */
  AUTHORITY_WINDOW_EXCEEDED,

  /** 单线入口缺少 conflict、方向或 authority end，按 fail-closed 处理。 */
  SINGLE_CORRIDOR_FAIL_CLOSED,

  /** TrainCarts/图层目标不可达时的安全兜底。 */
  UNREACHABLE_FAILOVER,

  /** 已确认或等待确认的 STOP 互卡恢复期间，不允许继续动车。 */
  DEADLOCK_CONFIRMED_WAITING,

  /** Layover 保持等待，不得复用旧 destination 动车。 */
  LAYOVER_HOLD,

  /** 终点保持等待，不得复用旧 destination 动车。 */
  TERMINAL_HOLD,

  /** 未分类硬停车原因，作为诊断兜底。 */
  UNKNOWN
}
