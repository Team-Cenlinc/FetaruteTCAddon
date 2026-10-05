package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 车上故障的参数（{@code drive.yml} 的 {@code simulation.faults} 段）。
 *
 * <p>随机故障默认关闭；管理员命令注入的故障不受开关影响。故障状态只存在驾驶会话的内存里，会话结束即消失。
 *
 * @param enabled 是否在驾驶中随机发生故障
 * @param chancePerHour 驾驶一小时内至少发生一次随机故障的概率，[0, 1)
 * @param types 随机故障从哪些类型里挑
 * @param lineLossTicks 受电中断多久后自动恢复（tick）
 * @param brakeLeakKpaPerSecond 制动缸漏泄时制动缸每秒下降的压力（kPa）
 */
public record FaultConfig(
    boolean enabled,
    double chancePerHour,
    Set<CabFault> types,
    int lineLossTicks,
    double brakeLeakKpaPerSecond) {

  private static final double SECONDS_PER_HOUR = 3600.0;

  public FaultConfig {
    if (!Double.isFinite(chancePerHour) || chancePerHour < 0.0 || chancePerHour >= 1.0) {
      throw new IllegalArgumentException("chancePerHour 必须在 [0, 1) 内");
    }
    Objects.requireNonNull(types, "types");
    types = Set.copyOf(types);
    if (lineLossTicks <= 0) {
      throw new IllegalArgumentException("lineLossTicks 必须为正数");
    }
    if (!Double.isFinite(brakeLeakKpaPerSecond) || brakeLeakKpaPerSecond <= 0.0) {
      throw new IllegalArgumentException("brakeLeakKpaPerSecond 必须为正数");
    }
  }

  /** 内置默认值：随机故障关闭（开启后每小时约五成概率），受电中断 30 秒后恢复，制动缸每秒漏 6 kPa。 */
  public static FaultConfig defaults() {
    return new FaultConfig(false, 0.5, EnumSet.allOf(CabFault.class), 30 * 20, 6.0);
  }

  /** 每秒发生随机故障的概率：连续驾驶一小时累计起来恰好是 {@link #chancePerHour()}。 */
  public double chancePerSecond() {
    if (chancePerHour <= 0.0) {
      return 0.0;
    }
    return 1.0 - Math.pow(1.0 - chancePerHour, 1.0 / SECONDS_PER_HOUR);
  }
}
