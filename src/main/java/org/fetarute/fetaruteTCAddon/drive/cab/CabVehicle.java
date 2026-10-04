package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;

/**
 * simulation 级车上系统要知道的车辆特征。
 *
 * @param mode 动力配置方式：机车牵引用制动管、压缩机手动开关，动车组用电空制动、压缩机自动
 * @param electric 是否为电力牵引（有电制动，会发生受电中断与主断跳闸）
 * @param cars 编组节数（制动管充风时长按它折算）
 * @param kneeBps 恒功率段的拐点速度（格/秒）
 */
public record CabVehicle(DriveMode mode, boolean electric, int cars, double kneeBps) {

  public CabVehicle {
    Objects.requireNonNull(mode, "mode");
    cars = Math.max(1, cars);
    if (!Double.isFinite(kneeBps) || kneeBps <= 0.0) {
      throw new IllegalArgumentException("kneeBps 必须为正数");
    }
  }

  /**
   * 按列车的驾驶参数、受电方式与车种取车辆特征。
   *
   * @param params 驾驶参数（决定动力配置方式）
   * @param supply 受电方式（决定是否电力牵引）
   * @param type 车种（决定拐点速度）
   * @param cars 编组节数
   */
  public static CabVehicle of(
      DriveParams params, PowerSupply supply, TrainType type, int cars, CabConfig config) {
    Objects.requireNonNull(params, "params");
    Objects.requireNonNull(supply, "supply");
    Objects.requireNonNull(config, "config");
    return new CabVehicle(
        params.mode(), supply.electric(), cars, config.constantPower().kneeBpsFor(type));
  }

  /** 单节电动车组，拐点速度取全局默认。 */
  public static CabVehicle defaultMultipleUnit(CabConfig config) {
    return new CabVehicle(DriveMode.MU, true, 1, config.constantPower().kneeBps());
  }

  /** 是否用制动管（机车牵引）。 */
  public boolean brakePipe() {
    return mode == DriveMode.LOCO;
  }

  /** 压缩机是否要手动打开（机车牵引）。 */
  public boolean manualCompressor() {
    return mode == DriveMode.LOCO;
  }
}
