package org.fetarute.fetaruteTCAddon.drive.driver;

/** 驾驶员列车卡住多久走到时间阶梯的哪一档。本类不依赖服务器对象。 */
public final class DriverRescueLadder {

  /** 阶梯的档位，按时长递增。 */
  public enum Stage {
    NONE,
    /** 告警驾驶员。 */
    WARN,
    /** 强制转 ATO。 */
    ATO,
    /** 交还自动运行，任务失败。 */
    HANDBACK,
    /** 驾驶员下车送到站台。 */
    RESCUE
  }

  private DriverRescueLadder() {}

  /** 卡住 {@code stuckSeconds} 秒所在的档位。 */
  public static Stage stage(long stuckSeconds, DriverRecovery recovery) {
    if (stuckSeconds >= recovery.rescueSeconds()) {
      return Stage.RESCUE;
    }
    if (stuckSeconds >= recovery.handbackSeconds()) {
      return Stage.HANDBACK;
    }
    if (stuckSeconds >= recovery.atoSeconds()) {
      return Stage.ATO;
    }
    if (stuckSeconds >= recovery.warnSeconds()) {
      return Stage.WARN;
    }
    return Stage.NONE;
  }
}
