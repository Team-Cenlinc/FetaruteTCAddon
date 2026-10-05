package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupSystem;

/** 停车后菜单里一个按钮对应的操作。 */
public enum MenuAction {
  REVERSER_FORWARD,
  REVERSER_NEUTRAL,
  REVERSER_REVERSE,
  DOOR_LEFT,
  DOOR_RIGHT,
  KEY,
  POWER,
  BREAKER,
  AUX,
  /** 一键启动 / 关机。 */
  START,
  /** 压缩机（simulation 级；机车可开关，动车组只作指示）。 */
  COMPRESSOR,
  /** 停放制动（simulation 级）。 */
  PARKING_BRAKE,
  /** 制动试验（simulation 级）。 */
  BRAKE_TEST,
  /** 切换人工驾驶与 ATO（只在调度列车）。 */
  DRIVING_MODE,
  /** 结束驾驶：点两次才执行（调度列车为放弃任务并交还自动运行）。 */
  END_DRIVING,
  /** 任务卡：只显示信息，不可点击。 */
  TASK_CARD,
  /** 门旁路（simulation 级）：车门故障使门关好回路不通时旁路，解除牵引封锁。 */
  DOOR_BYPASS;

  /** 对应的启动流程系统；不是系统开关时为空。 */
  public Optional<SetupSystem> system() {
    return switch (this) {
      case KEY -> Optional.of(SetupSystem.KEY);
      case POWER -> Optional.of(SetupSystem.POWER);
      case BREAKER -> Optional.of(SetupSystem.BREAKER);
      case AUX -> Optional.of(SetupSystem.AUX);
      default -> Optional.empty();
    };
  }

  /** 是否为换向手柄按钮。 */
  public boolean isReverser() {
    return this == REVERSER_FORWARD || this == REVERSER_NEUTRAL || this == REVERSER_REVERSE;
  }
}
