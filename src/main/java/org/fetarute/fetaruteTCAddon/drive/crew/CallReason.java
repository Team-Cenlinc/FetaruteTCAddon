package org.fetarute.fetaruteTCAddon.drive.crew;

import java.util.Locale;
import java.util.Optional;

/** 驾驶员呼叫信号员的原因。信号员要按原因选对回复（哪个原因配哪条回复由信号员模块定）。 */
public enum CallReason {
  /** 在控制区入口的信号前停车等候，请求进路。 */
  SIGNAL,
  /** 车门故障。 */
  DOORS,
  /** 线路异常：发现障碍物、异响等，请求指示。 */
  OBSTRUCTION,
  /** 一般联系：没说明原因（两短铃呼叫、没写原因的命令）；车正在入口等候时按“信号前等候”算。 */
  GENERAL;

  /** 命令与语言键里的写法。 */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** 按命令写法解析；认不出时为空。 */
  public static Optional<CallReason> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String key = raw.trim().toLowerCase(Locale.ROOT);
    for (CallReason reason : values()) {
      if (reason.key().equals(key)) {
        return Optional.of(reason);
      }
    }
    return Optional.empty();
  }
}
