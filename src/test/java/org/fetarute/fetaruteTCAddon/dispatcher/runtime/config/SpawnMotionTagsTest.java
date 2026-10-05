package org.fetarute.fetaruteTCAddon.dispatcher.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.junit.jupiter.api.Test;

class SpawnMotionTagsTest {

  private final TrainConfigResolver resolver = new TrainConfigResolver();

  @Test
  // 没打任何标签的出库车：写下的就是调度此刻解析出的值，也就是编表运行曲线的那组数；写入前后解析结果逐位相同
  void freshTrainIsStampedWithResolvedTimetableMotion() {
    ConfigManager.ConfigView config = config(null, null, null);
    TagStore train = new TagStore();
    TrainConfig before = resolver.resolve(train.properties(), config);

    assertEquals(
        SpawnMotionTags.Outcome.STAMPED, SpawnMotionTags.stamp(train.properties(), config));

    TrainConfig after = resolver.resolve(train.properties(), config);
    assertEquals(before, after, "写入不得改变自动运行解析到的值");
    assertEquals(before.accelBps2(), tagValue(train, TrainConfigResolver.TAG_TRAIN_ACCEL_BPS2));
    assertEquals(before.decelBps2(), tagValue(train, TrainConfigResolver.TAG_TRAIN_DECEL_BPS2));
    assertEquals(
        Optional.of(TrainConfigResolver.SOURCE_SPAWN),
        TrainTagHelper.readTagValue(
            train.properties(), TrainConfigResolver.TAG_TRAIN_CONFIG_SOURCE));
    SpeedCurve timetable = RunCurveModel.Settings.fromConfig(config, 8.0).motion();
    assertEquals(timetable.accelBps2(), after.accelBps2(), "编表口径的加速度");
    assertEquals(timetable.decelBps2(), after.decelBps2(), "编表口径的减速度");
  }

  @Test
  // 模板自带车种标签：写该车种的数（与自动运行一致），不改写成编表口径
  void typeTaggedTemplateKeepsItsOwnTypeMotion() {
    ConfigManager.ConfigView config = config(null, null, null);
    TagStore train = new TagStore("FTA_TRAIN_TYPE=dmu");
    TrainConfig before = resolver.resolve(train.properties(), config);

    SpawnMotionTags.stamp(train.properties(), config);

    TrainConfig after = resolver.resolve(train.properties(), config);
    assertEquals(before, after);
    assertEquals(TrainType.DMU.presetAccelBps2(), tagValue(train, "FTA_TRAIN_ACCEL_BPS2"));
    assertEquals(TrainType.DMU.presetDecelBps2(), tagValue(train, "FTA_TRAIN_DECEL_BPS2"));
    assertNotEquals(
        RunCurveModel.Settings.fromConfig(config, 8.0).motion().accelBps2(), after.accelBps2());
    assertEquals(
        Optional.of("dmu"),
        TrainTagHelper.readTagValue(train.properties(), TrainConfigResolver.TAG_TRAIN_TYPE),
        "车种标签原样保留");
  }

  @Test
  // 用户设定的加减速标签一个也不动，解析仍取用户的值
  void userMotionTagsAreNeverOverwritten() {
    ConfigManager.ConfigView config = config(null, null, null);
    TagStore train =
        new TagStore(
            "FTA_TRAIN_TYPE=EMU", "FTA_TRAIN_ACCEL_BPS2=0.55", "FTA_TRAIN_DECEL_BPS2=0.75");
    List<String> original = train.tags();

    assertEquals(
        SpawnMotionTags.Outcome.USER_OWNED, SpawnMotionTags.stamp(train.properties(), config));

    assertEquals(original, train.tags());
    TrainConfig resolved = resolver.resolve(train.properties(), config);
    assertEquals(0.55, resolved.accelBps2());
    assertEquals(0.75, resolved.decelBps2());
  }

  @Test
  // 只设了一项、或值写错的标签同样是用户的：不补写另一项，也不纠正
  void partialOrInvalidUserTagIsStillUserOwned() {
    ConfigManager.ConfigView config = config(null, null, null);
    TagStore partial = new TagStore("FTA_TRAIN_DECEL_BPS2=0.9");
    TagStore invalid = new TagStore("FTA_TRAIN_ACCEL_BPS2=abc");
    List<String> partialOriginal = partial.tags();
    List<String> invalidOriginal = invalid.tags();

    assertEquals(
        SpawnMotionTags.Outcome.USER_OWNED, SpawnMotionTags.stamp(partial.properties(), config));
    assertEquals(
        SpawnMotionTags.Outcome.USER_OWNED, SpawnMotionTags.stamp(invalid.properties(), config));

    assertEquals(partialOriginal, partial.tags());
    assertEquals(invalidOriginal, invalid.tags());
  }

  @Test
  // 配置里不整齐的小数写进标签再读回来，与配置值逐位相等
  void oddConfiguredValuesRoundTripExactly() {
    double accel = 0.1 + 0.2;
    double decel = 1.0 / 3.0;
    ConfigManager.ConfigView config = config("metro", accel, decel);
    TagStore train = new TagStore();

    SpawnMotionTags.stamp(train.properties(), config);

    assertEquals(0, Double.compare(accel, tagValue(train, "FTA_TRAIN_ACCEL_BPS2")));
    assertEquals(0, Double.compare(decel, tagValue(train, "FTA_TRAIN_DECEL_BPS2")));
    assertEquals(accel, resolver.resolve(train.properties(), config).accelBps2());
  }

  @Test
  // 配置重载后、标签还没刷新时，控车照新配置（与从未写过标签的列车一样）；复用时再把标签刷新成新值
  void staleStampFollowsCurrentConfigUntilRefreshed() {
    ConfigManager.ConfigView before = config("metro", 1.1, 1.2);
    ConfigManager.ConfigView after = config("metro", 0.8, 0.9);
    TagStore stamped = new TagStore();
    TagStore untagged = new TagStore();
    SpawnMotionTags.stamp(stamped.properties(), before);

    assertEquals(
        resolver.resolve(untagged.properties(), after),
        resolver.resolve(stamped.properties(), after),
        "旧值不得冻结在车上");
    assertEquals(1.1, tagValue(stamped, "FTA_TRAIN_ACCEL_BPS2"));

    assertEquals(
        SpawnMotionTags.Outcome.STAMPED, SpawnMotionTags.stamp(stamped.properties(), after));
    assertEquals(0.8, tagValue(stamped, "FTA_TRAIN_ACCEL_BPS2"));
    assertEquals(0.9, tagValue(stamped, "FTA_TRAIN_DECEL_BPS2"));
    assertEquals(1, countKey(stamped, "FTA_TRAIN_ACCEL_BPS2"), "同一个 key 只留一条");
  }

  @Test
  // 重载刷新只碰出车写入过的列车
  void refreshTouchesOnlyStampedTrains() {
    ConfigManager.ConfigView before = config("metro", 1.1, 1.2);
    ConfigManager.ConfigView after = config("metro", 0.8, 0.9);
    TagStore stamped = new TagStore();
    SpawnMotionTags.stamp(stamped.properties(), before);
    TagStore user = new TagStore("FTA_TRAIN_ACCEL_BPS2=0.5", "FTA_TRAIN_DECEL_BPS2=0.6");
    TagStore untagged = new TagStore("FTA_ROUTE_ID=abc");
    List<String> userOriginal = user.tags();
    List<String> untaggedOriginal = untagged.tags();

    int refreshed =
        SpawnMotionTags.refreshStamped(
            Arrays.asList(stamped.properties(), user.properties(), untagged.properties(), null),
            after);

    assertEquals(1, refreshed);
    assertEquals(0.8, tagValue(stamped, "FTA_TRAIN_ACCEL_BPS2"));
    assertEquals(userOriginal, user.tags());
    assertEquals(untaggedOriginal, untagged.tags());
  }

  @Test
  // 用户用 /fta train config set 设定后归用户所有：撤掉来源标记，之后出车、复用都不再改写
  void userConfigCommandTakesOwnership() {
    ConfigManager.ConfigView config = config(null, null, null);
    TagStore train = new TagStore();
    SpawnMotionTags.stamp(train.properties(), config);

    resolver.writeConfig(
        train.properties(),
        new TrainConfig(TrainType.METRO, 0.9, 1.0),
        Optional.of(0.9),
        Optional.of(1.0));

    assertFalse(resolver.isSpawnStamped(train.properties()));
    assertTrue(resolver.hasUserMotionTags(train.properties()));
    assertEquals(
        SpawnMotionTags.Outcome.USER_OWNED, SpawnMotionTags.stamp(train.properties(), config));
    TrainConfig resolved = resolver.resolve(train.properties(), config);
    assertEquals(0.9, resolved.accelBps2());
    assertEquals(1.0, resolved.decelBps2());
  }

  @Test
  // 模板若是从出车的列车另存的，会带着来源标记：照样刷新，不当作用户设定
  void templateSavedFromStampedTrainIsRefreshed() {
    ConfigManager.ConfigView config = config("metro", 0.8, 0.9);
    TagStore train =
        new TagStore(
            "FTA_TRAIN_ACCEL_BPS2=1.1",
            "FTA_TRAIN_DECEL_BPS2=1.2",
            "FTA_TRAIN_CONFIG_SOURCE=spawn");

    assertEquals(
        SpawnMotionTags.Outcome.STAMPED, SpawnMotionTags.stamp(train.properties(), config));

    assertEquals(0.8, tagValue(train, "FTA_TRAIN_ACCEL_BPS2"));
    assertEquals(0.9, tagValue(train, "FTA_TRAIN_DECEL_BPS2"));
  }

  @Test
  // 缺列车或配置时什么也不写；读写标签出错时吞掉异常，不让出车事务失败
  void missingInputsSkipAndFailuresAreSwallowed() {
    ConfigManager.ConfigView config = config(null, null, null);
    assertEquals(SpawnMotionTags.Outcome.SKIPPED, SpawnMotionTags.stamp(null, config));
    assertEquals(
        SpawnMotionTags.Outcome.SKIPPED,
        SpawnMotionTags.stamp(new TagStore().properties(), (ConfigManager.ConfigView) null));

    TrainProperties broken = mock(TrainProperties.class);
    when(broken.hasTags()).thenReturn(true);
    when(broken.getTags()).thenThrow(new IllegalStateException("boom"));
    assertEquals(SpawnMotionTags.Outcome.FAILED, SpawnMotionTags.stamp(broken, config));
    assertEquals(0, SpawnMotionTags.refreshStamped(List.of(broken), config));
  }

  @Test
  // 编组方案只给车型：先写车型再按该车型写出车值，与自动运行解析一致
  void consistTypeIsWrittenBeforeStamping() {
    ConfigManager.ConfigView config = config(null, null, null);
    TagStore train = new TagStore();

    assertEquals(
        SpawnMotionTags.Outcome.STAMPED,
        SpawnMotionTags.stampWithConsist(
            train.properties(), config, Map.of(TrainConfigResolver.TAG_TRAIN_TYPE, "DMU")));

    assertEquals(TrainType.DMU.presetAccelBps2(), tagValue(train, "FTA_TRAIN_ACCEL_BPS2"));
    assertEquals(TrainType.DMU.presetDecelBps2(), tagValue(train, "FTA_TRAIN_DECEL_BPS2"));
    assertTrue(resolver.isSpawnStamped(train.properties()));
  }

  @Test
  // 编组方案覆盖了加速度：归方案所有，撤掉模板带来的出车标记与出车减速度，解析取方案的值、减速度按车种
  void consistMotionOverrideOwnsTheTags() {
    ConfigManager.ConfigView config = config(null, null, null);
    TagStore train =
        new TagStore(
            "FTA_TRAIN_CONFIG_SOURCE=spawn",
            "FTA_TRAIN_ACCEL_BPS2=0.9",
            "FTA_TRAIN_DECEL_BPS2=1.1");

    assertEquals(
        SpawnMotionTags.Outcome.USER_OWNED,
        SpawnMotionTags.stampWithConsist(
            train.properties(),
            config,
            Map.of(
                TrainConfigResolver.TAG_TRAIN_TYPE,
                "EMU",
                TrainConfigResolver.TAG_TRAIN_ACCEL_BPS2,
                "0.6")));

    assertFalse(resolver.isSpawnStamped(train.properties()));
    assertEquals(0, countKey(train, "FTA_TRAIN_DECEL_BPS2"), "模板的出车减速度撤掉");
    TrainConfig resolved = resolver.resolve(train.properties(), config);
    assertEquals(0.6, resolved.accelBps2(), 1e-9);
    assertEquals(TrainType.EMU.presetDecelBps2(), resolved.decelBps2(), 1e-9);
    assertEquals(
        SpawnMotionTags.Outcome.USER_OWNED,
        SpawnMotionTags.stamp(train.properties(), config),
        "之后折返复用不再改写");
  }

  /**
   * 按配置文件的写法构造配置：不给值的项按内置预设（默认车种 metro）。
   *
   * @param type 要覆盖的车种 key；为空时不覆盖
   */
  private static ConfigManager.ConfigView config(String type, Double accel, Double decel) {
    YamlConfiguration yaml = new YamlConfiguration();
    if (type != null) {
      yaml.set("train.types." + type + ".accel-bps2", accel);
      yaml.set("train.types." + type + ".decel-bps2", decel);
    }
    return ConfigManager.parse(yaml, Logger.getLogger("spawn-motion-tags-test"));
  }

  private static double tagValue(TagStore store, String key) {
    return Double.parseDouble(TrainTagHelper.readTagValue(store.properties(), key).orElseThrow());
  }

  private static long countKey(TagStore store, String key) {
    return store.tags().stream().filter(tag -> tag.startsWith(key + "=")).count();
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

    private List<String> tags() {
      return List.copyOf(tags);
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
