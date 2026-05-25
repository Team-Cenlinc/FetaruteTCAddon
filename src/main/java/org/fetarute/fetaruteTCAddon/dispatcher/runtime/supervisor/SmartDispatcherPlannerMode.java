package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import java.util.Locale;
import java.util.Optional;

/** Smart Dispatcher planner 的独立执行模式。 */
public enum SmartDispatcherPlannerMode {
  /** 不构建 wait-for graph，也不输出 planner trace。 */
  OFF,
  /** 只构建 graph、评分和输出计划，不创建 reservation。 */
  OBSERVE_ONLY,
  /** 只允许执行同向、短窗口、可回滚的 minimal forward unlock。 */
  ENFORCE_MINIMAL_FORWARD;

  /** 解析配置值，支持大小写无关的枚举名。 */
  public static Optional<SmartDispatcherPlannerMode> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(SmartDispatcherPlannerMode.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }
}
