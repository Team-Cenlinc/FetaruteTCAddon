package org.fetarute.fetaruteTCAddon.drive.setup;

import java.util.Locale;
import java.util.Optional;

/**
 * 启动流程里的一个车上系统，按启动顺序排列。
 *
 * <p>钥匙属于驾驶室，随驾驶会话开始与结束；受电、主断路器与辅助电源属于列车，驾驶员离开后仍保持，记在列车标签里。
 */
public enum SetupSystem {
  /** 钥匙：激活驾驶室。 */
  KEY,
  /** 受电：升弓、集电靴受电，或启动发动机。 */
  POWER,
  /** 主断路器（仅电力牵引）。 */
  BREAKER,
  /** 辅助电源。 */
  AUX;

  /** 是否随列车保存（钥匙不保存）。 */
  public boolean persistent() {
    return this != KEY;
  }

  /** 标签与语言键里的写法。 */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** 按标签写法解析；无法识别时为空。 */
  public static Optional<SetupSystem> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    for (SetupSystem system : values()) {
      if (system.name().equals(normalized)) {
        return Optional.of(system);
      }
    }
    return Optional.empty();
  }
}
