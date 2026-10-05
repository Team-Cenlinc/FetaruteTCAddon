package org.fetarute.fetaruteTCAddon.drive.setup;

/** 启动流程在语言文件里的键。 */
public final class SetupText {

  private SetupText() {}

  /**
   * 一个步骤名称的语言键（纯文本，用作占位符）。受电一步按受电方式区分：升弓、集电靴受电、启动发动机、投入超级电容。
   *
   * @return 如 {@code drive.setup.step.pantograph}
   */
  public static String stepKey(SetupSystem system, PowerSupply supply) {
    String step =
        switch (system) {
          case KEY -> "key";
          case POWER -> switch (supply) {
            case PTG5, PTG6 -> "pantograph";
            case SHOE -> "shoe";
            case DIESEL -> "engine";
            case SUPERCAP -> "supercap";
          };
          case BREAKER -> "breaker";
          case AUX -> "aux";
        };
    return "drive.setup.step." + step;
  }
}
