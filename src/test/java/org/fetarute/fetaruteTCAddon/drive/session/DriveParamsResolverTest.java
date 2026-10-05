package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpawnMotionTags;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 驾驶侧读到的加减速与编表运行曲线、调度控车的口径一致；用插件自带的 config.yml 与 drive.yml。 */
class DriveParamsResolverTest {

  private static final int CARS = 6;

  private ConfigManager.ConfigView config;
  private DriveConfig drive;
  private SpeedCurve timetable;

  @BeforeEach
  void loadBundledConfigs() {
    config = bundledConfig();
    drive = bundledDriveConfig();
    timetable = RunCurveModel.Settings.fromConfig(config, 8.0).motion();
  }

  @Test
  // 出车写入标签后上车：满牵引的加速度、常用制动的减速度就是编表运行曲线的那组数
  void stampedTrainDrivesWithTimetableMotion() {
    TagStore train = new TagStore();
    SpawnMotionTags.stamp(train.properties(), config);

    DriveParams params = DriveParamsResolver.resolve(train.properties(), CARS, config, drive);

    assertEquals(timetable.accelBps2(), params.accelBps2());
    assertEquals(timetable.decelBps2(), params.decelBps2());
    assertEquals(DriveMode.MU, params.mode(), "默认车种为动车组，不按机车车厢数折算");
    assertEquals(drive.muReferenceMotorFraction(), params.motorFraction(), "未给动拖比不折算");
  }

  @Test
  // 档位力度：P3 为满牵引、B4 为常用全制动，与编表曲线的满加速度、满减速度相同
  void topNotchesMatchTimetableCurve() {
    TagStore train = new TagStore();
    SpawnMotionTags.stamp(train.properties(), config);

    DriveParams params = DriveParamsResolver.resolve(train.properties(), CARS, config, drive);

    assertEquals(timetable.accelBps2(), params.accelBps2() * drive.tractionFraction(Notch.P3));
    assertEquals(timetable.decelBps2(), params.decelBps2() * drive.brakeFraction(Notch.B4));
  }

  @Test
  // 写入前后驾驶参数相同：驾驶本就走调度同一个解析器，写入只是把这组数留在列车上
  void stampingDoesNotChangeDriveParams() {
    TagStore train = new TagStore();
    DriveParams before = DriveParamsResolver.resolve(train.properties(), CARS, config, drive);

    SpawnMotionTags.stamp(train.properties(), config);

    assertEquals(before, DriveParamsResolver.resolve(train.properties(), CARS, config, drive));
  }

  @Test
  // 用户设定的加减速：驾驶与调度都按用户的值，与编表口径无关
  void userTagsWinForBothDriverAndDispatcher() {
    TagStore train = new TagStore("FTA_TRAIN_ACCEL_BPS2=0.7", "FTA_TRAIN_DECEL_BPS2=0.8");
    SpawnMotionTags.stamp(train.properties(), config);

    DriveParams params = DriveParamsResolver.resolve(train.properties(), CARS, config, drive);
    TrainConfig dispatch = new TrainConfigResolver().resolve(train.properties(), config);

    assertEquals(0.7, params.accelBps2());
    assertEquals(0.8, params.decelBps2());
    assertEquals(dispatch.accelBps2(), params.accelBps2());
    assertEquals(dispatch.decelBps2(), params.decelBps2());
  }

  @Test
  // 配置重载后标签尚未刷新：驾驶照新配置，与调度、编表（重编后）一致
  void staleStampFollowsReloadedConfig() {
    TagStore train = new TagStore();
    SpawnMotionTags.stamp(train.properties(), config);
    YamlConfiguration yaml = bundledYaml("/config.yml");
    yaml.set("train.types.metro.accel-bps2", 0.95);
    yaml.set("train.types.metro.decel-bps2", 1.05);
    ConfigManager.ConfigView reloaded = ConfigManager.parse(yaml, Logger.getLogger("drive-test"));

    DriveParams params = DriveParamsResolver.resolve(train.properties(), CARS, reloaded, drive);

    SpeedCurve rebuilt = RunCurveModel.Settings.fromConfig(reloaded, 8.0).motion();
    assertEquals(rebuilt.accelBps2(), params.accelBps2());
    assertEquals(rebuilt.decelBps2(), params.decelBps2());
  }

  @Test
  // 最高速度不来自编表：编表只按线路限速走，没有列车最高速度；驾驶取标签或 drive.yml 的默认值
  void maxSpeedComesFromDriveConfigOrTag() {
    TagStore plain = new TagStore();
    TagStore tagged = new TagStore("FTA_TRAIN_MAX_BPS=27.5");

    assertEquals(
        drive.defaultMaxSpeedBps(),
        DriveParamsResolver.resolve(plain.properties(), CARS, config, drive).maxSpeedBps());
    assertEquals(
        27.5, DriveParamsResolver.resolve(tagged.properties(), CARS, config, drive).maxSpeedBps());
  }

  private static ConfigManager.ConfigView bundledConfig() {
    return ConfigManager.parse(bundledYaml("/config.yml"), Logger.getLogger("drive-test"));
  }

  private static DriveConfig bundledDriveConfig() {
    List<String> warnings = new ArrayList<>();
    DriveConfig parsed = DriveConfig.from(bundledYaml("/drive.yml"), warnings::add);
    assertEquals(List.of(), warnings, "自带 drive.yml 不应有非法项");
    return parsed;
  }

  private static YamlConfiguration bundledYaml(String resource) {
    try (InputStream in =
            Objects.requireNonNull(
                DriveParamsResolverTest.class.getResourceAsStream(resource), resource);
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return YamlConfiguration.loadConfiguration(reader);
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** 用列表模拟 TrainCarts 的标签存储。 */
  private static final class TagStore {
    private final TrainProperties properties;
    private final List<String> tags;

    private TagStore(String... initial) {
      this.tags = new ArrayList<>(Arrays.asList(initial));
      this.properties = mock(TrainProperties.class);
      when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
      when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
      doAnswer(
              inv -> {
                tags.addAll(extractTags(inv.getArgument(0)));
                return null;
              })
          .when(properties)
          .addTags(any(String[].class));
      doAnswer(
              inv -> {
                tags.removeAll(extractTags(inv.getArgument(0)));
                return null;
              })
          .when(properties)
          .removeTags(any(String[].class));
    }

    private TrainProperties properties() {
      return properties;
    }

    private static List<String> extractTags(Object arg) {
      if (arg instanceof String[] values) {
        return Arrays.asList(values);
      }
      if (arg instanceof String value) {
        return List.of(value);
      }
      return List.of();
    }
  }
}
