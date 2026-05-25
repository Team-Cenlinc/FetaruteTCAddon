package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

/**
 * blocker/risk 的新鲜度与可信度。
 *
 * <p>只有 {@link #LIVE} 可以参与 confirmed hard blocker cycle；其余状态仅用于诊断、重新取证或清理候选。
 */
public enum RiskFreshness {
  LIVE,
  STALE,
  PROTECTIVE_ONLY,
  UNKNOWN
}
