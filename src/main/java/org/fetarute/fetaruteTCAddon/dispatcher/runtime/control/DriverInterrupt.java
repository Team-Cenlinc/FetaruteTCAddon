package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

/** 调度层对驾驶员控制的列车提出的停车、销毁类要求（自动运行下这些都是直接的物理动作）。 */
public enum DriverInterrupt {
  /** 常用制动停车（自动运行下的立即停车）。 */
  SERVICE_STOP,
  /** 紧急制动（闭塞硬停、安全状态不可用）。 */
  EMERGENCY,
  /** 立即停住（失效保护、出车回滚、启动恢复等：自动运行下是瞬间归零）。 */
  EMERGENCY_INSTANT,
  /** 列车即将被销毁：先结束驾驶。 */
  RELEASE_FOR_DESTROY,
  /** 列车按交路开到收车地点（DSTY，通常是车库），即将正常销毁：驾驶正常结束。 */
  END_OF_SERVICE,
  /** 需要交还自动运行（调头等只有自动运行能做的动作）。 */
  HANDBACK_REQUIRED
}
