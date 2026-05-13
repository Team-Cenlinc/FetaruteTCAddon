package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

/**
 * 移动授权窗口末端的来源。
 *
 * <p>只有真实物理边界才能把可见信号降级。人工窗口边界只说明当前占用授权窗口还不够长，应尝试内部扩展或等待下一周期扩大授权， 不能单独把信号发布为黄灯或红灯。
 */
public enum AuthorityEndReason {
  /** 前方存在硬 blocker 或硬约束距离。 */
  HARD_BLOCKER(true),

  /** 路线终点、RouteStop 或 terminal 形成的真实停车边界。 */
  ROUTE_STOP_OR_TERMINAL(true),

  /** dwell 或站停窗口形成的真实停车边界。 */
  DWELL_OR_STATION_STOP(true),

  /** 单线入口未能验证出口，必须安全侧失败。 */
  SINGLE_EXIT_NOT_VERIFIED(true),

  /** 达到配置允许的最大授权上限。 */
  MAX_AUTHORITY_CAP_REACHED(true),

  /** 仅因当前 lookahead/授权窗口截断而形成的人工边界。 */
  ARTIFICIAL_WINDOW_LIMIT(false),

  /** 本周期没有授权末端。 */
  NONE(false);

  private final boolean physical;

  AuthorityEndReason(boolean physical) {
    this.physical = physical;
  }

  /** 是否代表真实物理限制。 */
  public boolean physical() {
    return physical;
  }
}
