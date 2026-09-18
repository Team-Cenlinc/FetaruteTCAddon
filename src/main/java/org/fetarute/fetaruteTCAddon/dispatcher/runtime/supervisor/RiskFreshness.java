package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

/**
 * blocker/risk 的新鲜度与可信度。
 *
 * <p>只有 {@link #LIVE} 可以参与 confirmed hard blocker cycle；其余状态仅用于诊断、重新取证或清理候选。
 */
public enum RiskFreshness {
  LIVE,
  STALE,
  /** 抽象 single:/switcher: 冲突键上的尾部/区域保护：准入不把它当阻塞，前瞻只留痕。 */
  PROTECTIVE_ONLY,
  /**
   * 物理 NODE/EDGE 上的尾部保护：准入按 {@code obstructs} 真值表判「挡」，列车到了授权窗口边缘一定会被硬停。
   *
   * <p>此前它和 {@link #PROTECTIVE_ONLY} 混在一起：前瞻侧「不减速也不停」、准入侧「是墙」，同一个 claim 两边判法相反， 于是绿灯直接跳红——实服
   * 2026-09-18 第二十七轮硬停车 blocker 角色 PROTECTIVE_RETAIN 446 : MOVEMENT_REQUIRED 177， STOP 前一刻 44 次是
   * PROCEED、13 次才有 CAUTION。分出来之后它只多参与 caution 限速，不改变任何放行判定。
   */
  PROTECTIVE_PHYSICAL,
  UNKNOWN
}
