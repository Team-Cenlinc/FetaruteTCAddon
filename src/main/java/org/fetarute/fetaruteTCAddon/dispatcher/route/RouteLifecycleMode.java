package org.fetarute.fetaruteTCAddon.dispatcher.route;

/** 线路终到后的生命周期模式。 */
public enum RouteLifecycleMode {
  /** 终到后进入待命复用（Layover），等待分配下一张 Operation/Return 票据。 */
  REUSE_AT_TERM,

  /** 终到后继续执行前往 DSTY 的路径并销毁/回库（不进入复用池）。 */
  DESTROY_AFTER_TERM
}
