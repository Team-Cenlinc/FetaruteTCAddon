package org.fetarute.fetaruteTCAddon.drive.crew;

import java.util.Locale;

/** 呼叫信号员的乘务员：驾驶员或车掌。信号员的控制台与提醒据此写明是谁在呼叫。 */
public enum CallerRole {
  /** 驾驶员（人工驾驶或 ATO）。 */
  DRIVER,
  /** 车掌。 */
  GUARD;

  /** 语言键里的写法。 */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
