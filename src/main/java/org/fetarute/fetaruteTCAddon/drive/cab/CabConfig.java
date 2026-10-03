package org.fetarute.fetaruteTCAddon.drive.cab;

/**
 * simulation 级车上系统的参数（{@code drive.yml} 的 {@code simulation} 段）。压力单位为 kPa。
 *
 * @param mainReservoirMaxKpa 主风缸满压，压缩机充到它就停
 * @param compressorCutInKpa 主风缸低于它时压缩机自动启动
 * @param compressorFillSeconds 压缩机把主风缸从空充到满压所需的秒数
 * @param tractionLockoutKpa 主风缸低于它时封锁牵引
 * @param fullBrakeKpa 主风缸至少要有它才能发挥全部空气制动力，低于它时制动力按比例下降
 * @param parkingReleaseKpa 缓解停放制动所需的主风缸压力
 * @param parkingAutoApplyKpa 主风缸低于它时停放制动（弹簧制动）自动施加
 * @param brakeCylinderMaxKpa 常用全制动时的制动缸压力
 * @param brakeCylinderConsumption 制动缸每升高 1 kPa 消耗的主风缸压力
 * @param leakKpaPerMinute 无人驾驶时主风缸每分钟的漏泄
 * @param brakeTestApplyKpa 制动试验中制动缸要升到的压力
 * @param brakeTestReleaseKpa 制动试验中缓解后制动缸要降到的压力
 * @param vigilanceIntervalTicks 警惕装置：行车中多久没有操作就报警（tick）
 * @param vigilanceWarningTicks 警惕装置：报警后多久不确认就紧急制动（tick）
 */
public record CabConfig(
    double mainReservoirMaxKpa,
    double compressorCutInKpa,
    double compressorFillSeconds,
    double tractionLockoutKpa,
    double fullBrakeKpa,
    double parkingReleaseKpa,
    double parkingAutoApplyKpa,
    double brakeCylinderMaxKpa,
    double brakeCylinderConsumption,
    double leakKpaPerMinute,
    double brakeTestApplyKpa,
    double brakeTestReleaseKpa,
    int vigilanceIntervalTicks,
    int vigilanceWarningTicks) {

  /** 内置默认值。 */
  public static CabConfig defaults() {
    return new CabConfig(900, 750, 90, 550, 450, 450, 300, 350, 0.1, 30, 300, 20, 1200, 100);
  }
}
