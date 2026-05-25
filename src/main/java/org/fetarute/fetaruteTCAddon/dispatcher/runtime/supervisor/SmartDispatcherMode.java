package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import java.util.Locale;
import java.util.Optional;

/** Smart Dispatcher / Traffic Control Supervisor 的运行模式。 */
public enum SmartDispatcherMode {
  /** 完全关闭 Smart Dispatcher，不调用决策器，只保留最小禁用 trace。 */
  OFF,
  /** 只输出诊断 trace，禁止任何 signal、destination、token、occupancy 或 destroy 副作用。 */
  OBSERVE_ONLY,
  /** 允许通过 effect class gate 的动作执行；危险动作仍必须显式通过 gate。 */
  ENFORCE;

  /** 解析配置值，支持大小写无关的枚举名。 */
  public static Optional<SmartDispatcherMode> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(SmartDispatcherMode.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }
}
