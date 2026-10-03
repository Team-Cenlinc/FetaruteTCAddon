package org.fetarute.fetaruteTCAddon.drive;

import java.util.Locale;
import java.util.Optional;

/**
 * 手动驾驶的仿真等级：决定启动流程怎么操作，以及之后叠加多少仿真项。
 *
 * <p>两个等级共同的约束：必须先启动才能牵引，紧急制动必须停稳后才能缓解，车门没关不能牵引。
 *
 * <ul>
 *   <li>{@link #STANDARD}：驾驶台上一个启动按钮，按顺序自动完成启动；
 *   <li>{@link #SIMULATION}：驾驶台上逐项操作各个开关，其余仿真项（气压、制动试验、警惕装置等）在此基础上叠加。
 * </ul>
 */
public enum SimulationLevel {
  STANDARD,
  SIMULATION;

  /** 启动流程的操作方式。 */
  public enum SetupMode {
    /** 驾驶台上一个启动按钮，按顺序自动完成全部步骤。 */
    ONE_CLICK,
    /** 驾驶台上逐项操作各个开关。 */
    MANUAL
  }

  /** 该等级的启动流程操作方式。 */
  public SetupMode setupMode() {
    return this == SIMULATION ? SetupMode.MANUAL : SetupMode.ONE_CLICK;
  }

  /**
   * 解析配置里的等级名（不区分大小写）。
   *
   * @return 对应等级；空白或无法识别时为空
   */
  public static Optional<SimulationLevel> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    for (SimulationLevel level : values()) {
      if (level.name().equals(normalized)) {
        return Optional.of(level);
      }
    }
    return Optional.empty();
  }
}
