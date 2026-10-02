package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.junit.jupiter.api.Test;

/**
 * 编表的走行参数必须与运行时控车读同一组配置：默认车种的加减速、{@code runtime.approach-*}、默认速度与停站开销。
 *
 * <p>参数面板和构建报告都从这一个方法取数，这里钉住它读的是哪几个键，免得"面板说一套、build 算另一套"。
 */
class FtaTimetableCommandRunModelTest {

  /** 默认车种换成 dmu 时，编表跟着换成 dmu 的加减速；进站窗口与限速取 runtime 段，停站开销取 timetable 段。 */
  @Test
  void runSettingsFollowTheRuntimeConfiguration() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("train.default-type", "dmu");
    config.set("train.types.dmu.accel-bps2", 0.7);
    config.set("train.types.dmu.decel-bps2", 0.9);
    config.set("runtime.approach-window-blocks", 80.0);
    config.set("runtime.approach-window-edges", 2);
    config.set("runtime.approach-speed-bps", 9.0);
    config.set("runtime.approach-depot-speed-bps", 4.0);
    config.set("graph.default-speed-blocks-per-second", 12.0);
    config.set("timetable.station-stop-overhead-seconds", 6);
    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("run-model-test"));

    RunCurveModel.Settings run = FtaTimetableCommand.runCurveSettings(view);

    assertEquals(0.7, run.motion().accelBps2(), 1e-9);
    assertEquals(0.9, run.motion().decelBps2(), 1e-9);
    assertEquals(80.0, run.approach().windowBlocks(), 1e-9);
    assertEquals(2, run.approach().windowEdges());
    assertEquals(9.0, run.approach().stationSpeedBps(), 1e-9);
    assertEquals(4.0, run.approach().depotSpeedBps(), 1e-9);
    assertEquals(12.0, run.fallbackSpeedBps(), 1e-9);
    assertEquals(6, run.stationStopOverheadSeconds());
  }

  /** 面板与报告的一句话说明里要看得出加减速、进站规则与停站开销。 */
  @Test
  void describeRunNamesEveryParameter() {
    ConfigManager.ConfigView view =
        ConfigManager.parse(new YamlConfiguration(), Logger.getLogger("run-model-test"));

    String text = FtaTimetableCommand.describeRun(FtaTimetableCommand.runCurveSettings(view));

    // 空配置按默认车种 metro 的预设。
    assertTrue(
        text.contains(String.format(Locale.ROOT, "起步 %.2f", TrainType.METRO.presetAccelBps2())),
        text);
    assertTrue(
        text.contains(String.format(Locale.ROOT, "制动 %.2f", TrainType.METRO.presetDecelBps2())),
        text);
    assertTrue(text.contains("进站 96 格内"), text);
    assertTrue(text.contains("dwell + 4s"), text);
  }

  /** 没有配置时退回默认加减速、不做进站限速，而不是抛异常：面板在任何状态下都要能打开。 */
  @Test
  void missingConfigurationFallsBackQuietly() {
    RunCurveModel.Settings run = FtaTimetableCommand.runCurveSettings(null);

    assertEquals(0.0, run.approach().stationSpeedBps(), 1e-9);
    assertEquals(
        ConfigManager.TimetableSettings.DEFAULT_STATION_STOP_OVERHEAD_SECONDS,
        run.stationStopOverheadSeconds());
  }
}
