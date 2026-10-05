package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.Map;
import org.fetarute.fetaruteTCAddon.drive.cab.AirSystem;
import org.fetarute.fetaruteTCAddon.drive.cab.BrakePipe;
import org.fetarute.fetaruteTCAddon.drive.cab.BrakePipeConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;

/**
 * 按车上系统的状态取菜单表计的读数、量程与区段。本类不依赖服务器对象，便于单测。
 *
 * <ul>
 *   <li>主风缸：低于封锁线为异常，低于压缩机启动压力为偏低；
 *   <li>制动缸：漏泄时为异常；量程取常用全制动压力的 1.5 倍，紧急制动时指针也不会顶满；
 *   <li>制动管（仅机车）：低于自动紧急制动压力为异常，低于常用全制动后的压力为偏低。
 * </ul>
 */
public final class CabGauges {

  /** 制动缸表量程相对常用全制动压力的倍数。 */
  private static final double CYLINDER_RANGE_RATIO = 1.5;

  /** 制动管表量程相对定压的倍数。 */
  private static final double PIPE_RANGE_RATIO = 1.2;

  private CabGauges() {}

  /**
   * 一块压力表此刻的样子。
   *
   * @return 表计的样子；动车组没有制动管表、standard 级没有表计，以及故障指示，都为 {@code null}
   */
  public static GaugeView of(MenuIndicator indicator, CabSystems cab) {
    if (!cab.enabled()) {
      return null;
    }
    AirSystem air = cab.air();
    CabConfig config = cab.config();
    return switch (indicator) {
      case MAIN_RESERVOIR -> {
        double mr = air.mainReservoirKpa();
        GaugeView.Band band =
            mr < config.tractionLockoutKpa()
                ? GaugeView.Band.ALARM
                : mr < config.compressorCutInKpa() ? GaugeView.Band.WARNING : GaugeView.Band.NORMAL;
        yield new GaugeView(
            indicator,
            mr,
            GaugeView.rangeFor(config.mainReservoirMaxKpa()),
            band,
            "drive.menu.gauge.main-reservoir-detail",
            Map.of(
                "lockout", kpa(config.tractionLockoutKpa()),
                "parking", kpa(config.parkingAutoApplyKpa())));
      }
      case BRAKE_CYLINDER -> {
        boolean leaking = air.cylinderLeaking();
        yield new GaugeView(
            indicator,
            air.brakeCylinderKpa(),
            GaugeView.rangeFor(config.brakeCylinderMaxKpa() * CYLINDER_RANGE_RATIO),
            leaking ? GaugeView.Band.ALARM : GaugeView.Band.NORMAL,
            leaking
                ? "drive.menu.gauge.brake-cylinder-leak"
                : "drive.menu.gauge.brake-cylinder-detail",
            Map.of("full", kpa(config.brakeCylinderMaxKpa())));
      }
      case BRAKE_PIPE -> {
        BrakePipe pipe = cab.brakePipe().orElse(null);
        if (pipe == null) {
          yield null;
        }
        BrakePipeConfig pipeConfig = pipe.config();
        double bp = pipe.pressureKpa();
        GaugeView.Band band =
            bp < pipeConfig.emergencyKpa()
                ? GaugeView.Band.ALARM
                : pipe.belowFullService() ? GaugeView.Band.WARNING : GaugeView.Band.NORMAL;
        yield new GaugeView(
            indicator,
            bp,
            GaugeView.rangeFor(pipeConfig.nominalKpa() * PIPE_RANGE_RATIO),
            band,
            "drive.menu.gauge.brake-pipe-detail",
            Map.of(
                "nominal", kpa(pipeConfig.nominalKpa()),
                "full", kpa(pipeConfig.fullServiceKpa()),
                "emergency", kpa(pipeConfig.emergencyKpa())));
      }
      case FAULTS -> null;
    };
  }

  /** 制动试验提示里的占位符：制动缸、制动管压力，试验减压量与保压剩余秒数。 */
  public static Map<String, String> brakeTestValues(CabSystems cab) {
    BrakePipeConfig pipeConfig = cab.config().brakePipe();
    double bp = cab.brakePipe().map(BrakePipe::pressureKpa).orElse(0.0);
    return Map.of(
        "bc", kpa(cab.air().brakeCylinderKpa()),
        "bp", kpa(bp),
        "need", kpa(pipeConfig.testReductionKpa()),
        "seconds", String.valueOf(cab.brakeTest().holdRemainingSeconds(pipeConfig)));
  }

  private static String kpa(double value) {
    return String.valueOf(Math.round(value));
  }
}
