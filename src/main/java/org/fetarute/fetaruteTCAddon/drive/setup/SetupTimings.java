package org.fetarute.fetaruteTCAddon.drive.setup;

/**
 * 启动流程各步骤的耗时（tick）。
 *
 * @param keyTicks 插入钥匙、激活驾驶室
 * @param pantographTicks 升弓
 * @param shoeTicks 集电靴受电
 * @param engineTicks 启动发动机
 * @param breakerTicks 合主断路器
 * @param auxTicks 辅助电源起动
 */
public record SetupTimings(
    int keyTicks,
    int pantographTicks,
    int shoeTicks,
    int engineTicks,
    int breakerTicks,
    int auxTicks) {

  public SetupTimings {
    if (keyTicks < 0
        || pantographTicks < 0
        || shoeTicks < 0
        || engineTicks < 0
        || breakerTicks < 0
        || auxTicks < 0) {
      throw new IllegalArgumentException("启动步骤耗时不能为负");
    }
  }

  /** 内置默认值：钥匙 1 秒、升弓 8 秒、集电靴 2 秒、发动机 25 秒、主断 3 秒、辅助电源 5 秒。 */
  public static SetupTimings defaults() {
    return new SetupTimings(20, 160, 40, 500, 60, 100);
  }

  /** 某个系统在给定受电方式下的耗时（tick）。 */
  public int ticksOf(SetupSystem system, PowerSupply supply) {
    return switch (system) {
      case KEY -> keyTicks;
      case POWER -> switch (supply) {
        case PTG5, PTG6 -> pantographTicks;
        case SHOE -> shoeTicks;
        case DIESEL -> engineTicks;
      };
      case BREAKER -> breakerTicks;
      case AUX -> auxTicks;
    };
  }
}
