package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.Locale;
import java.util.Optional;

/**
 * simulation 级可注入的车上故障。
 *
 * <ul>
 *   <li>{@link #LINE_LOSS}：网压消失，没有牵引、没有电制动，一段时间后自动恢复；
 *   <li>{@link #BREAKER_TRIP}：主断路器跳闸，驾驶台把主断开关断开再闭合即复位；
 *   <li>{@link #COMPRESSOR}：压缩机故障，主风缸不再回升；
 *   <li>{@link #BRAKE_LEAK}：制动缸漏泄，制动保持不住，保压试验不能通过；
 *   <li>{@link #DOOR}：车门故障，门关好回路不通而封锁牵引，可用门旁路旁路。
 * </ul>
 *
 * <p>受电中断与主断跳闸只有电力牵引的列车才会发生。
 */
public enum CabFault {
  LINE_LOSS("line-loss", true),
  BREAKER_TRIP("breaker-trip", true),
  COMPRESSOR("compressor", false),
  BRAKE_LEAK("brake-leak", false),
  DOOR("door", false);

  private final String key;
  private final boolean electricOnly;

  CabFault(String key, boolean electricOnly) {
    this.key = key;
    this.electricOnly = electricOnly;
  }

  /** 命令参数、配置与语言键里的写法。 */
  public String key() {
    return key;
  }

  /** 是否只有电力牵引的列车才会发生。 */
  public boolean electricOnly() {
    return electricOnly;
  }

  /**
   * 按命令或配置里的写法解析（不区分大小写，下划线与连字符等同）。
   *
   * @return 对应故障；无法识别时为空
   */
  public static Optional<CabFault> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    for (CabFault fault : values()) {
      if (fault.key.equals(normalized)) {
        return Optional.of(fault);
      }
    }
    return Optional.empty();
  }
}
