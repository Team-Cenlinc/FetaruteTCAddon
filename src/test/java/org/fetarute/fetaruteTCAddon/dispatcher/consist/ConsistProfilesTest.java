package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistProfiles.IssueKind;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.junit.jupiter.api.Test;

/** 档案合成：车种按"方案 &gt; 标签 &gt; 名字 &gt; 默认"取，加减速标签优先于车种预设，与控车同一规则。 */
class ConsistProfilesTest {

  private static final ConfigManager.TrainConfigSettings SETTINGS =
      new ConfigManager.TrainConfigSettings("metro", Map.of());

  private static ConsistInspection saved(Map<String, Set<String>> tags) {
    return new ConsistInspection(true, 6, 70.5, tags, OptionalInt.of(4));
  }

  private static ConsistOverrides override(TrainType type) {
    return new ConsistOverrides(
        Optional.empty(),
        Optional.of(type),
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        OptionalDouble.empty());
  }

  @Test
  void typeComesFromPlanThenTagThenNameThenDefault() {
    ConsistInspection tagged = saved(Map.of("FTA_TRAIN_TYPE", Set.of("DMU")));

    ConsistProfile plan =
        ConsistProfiles.resolve("SH_A6", tagged, override(TrainType.EMU), SETTINGS)
            .profile()
            .orElseThrow();
    assertEquals(TrainType.EMU, plan.type());
    assertEquals(ConsistProfile.TypeSource.PLAN, plan.typeSource());
    assertEquals(0.9, plan.accelBps2(), 1e-9, "方案改了车种，加减速跟着新车种的预设");

    ConsistProfile tag =
        ConsistProfiles.resolve("SH_A6", tagged, ConsistOverrides.NONE, SETTINGS)
            .profile()
            .orElseThrow();
    assertEquals(TrainType.DMU, tag.type());
    assertEquals(ConsistProfile.TypeSource.TAG, tag.typeSource());

    ConsistProfile name =
        ConsistProfiles.resolve("dmu_3car", saved(Map.of()), ConsistOverrides.NONE, SETTINGS)
            .profile()
            .orElseThrow();
    assertEquals(TrainType.DMU, name.type());
    assertEquals(ConsistProfile.TypeSource.NAME, name.typeSource());

    ConsistProfile fallback =
        ConsistProfiles.resolve("SH_A6", saved(Map.of()), ConsistOverrides.NONE, SETTINGS)
            .profile()
            .orElseThrow();
    assertEquals(TrainType.METRO, fallback.type());
    assertEquals(ConsistProfile.TypeSource.DEFAULT, fallback.typeSource());
    assertEquals(6, fallback.cars());
    assertEquals(70.5, fallback.lengthBlocks(), 1e-9);
    assertEquals(OptionalInt.of(4), fallback.spawnLimit());
  }

  @Test
  void accelTagBeatsTypePresetAndPlanBeatsTag() {
    ConsistInspection tagged =
        saved(
            Map.of(
                "fta_train_accel_bps2", Set.of("0.7"),
                "FTA_TRAIN_DECEL_BPS2", Set.of("1.3"),
                "FTA_TRAIN_MAX_BPS", Set.of("20")));
    ConsistProfile fromTags =
        ConsistProfiles.resolve("SH_A6", tagged, ConsistOverrides.NONE, SETTINGS)
            .profile()
            .orElseThrow();
    assertEquals(0.7, fromTags.accelBps2(), 1e-9, "标签键不区分大小写");
    assertEquals(1.3, fromTags.decelBps2(), 1e-9);
    assertEquals(OptionalDouble.of(20.0), fromTags.maxSpeedBps());

    ConsistOverrides plan =
        new ConsistOverrides(
            Optional.of("6 节"),
            Optional.empty(),
            OptionalDouble.of(0.5),
            OptionalDouble.empty(),
            OptionalDouble.of(16));
    ConsistProfile fromPlan =
        ConsistProfiles.resolve("SH_A6", tagged, plan, SETTINGS).profile().orElseThrow();
    assertEquals(0.5, fromPlan.accelBps2(), 1e-9);
    assertEquals(1.3, fromPlan.decelBps2(), 1e-9);
    assertEquals(OptionalDouble.of(16.0), fromPlan.maxSpeedBps());
    assertEquals(Optional.of("6 节"), fromPlan.displayName());
  }

  @Test
  void conflictingTagsBlockUnlessThePlanOverridesThem() {
    ConsistInspection mixed = saved(Map.of("FTA_TRAIN_TYPE", Set.of("EMU", "METRO")));

    ConsistProfiles.Resolution blocked =
        ConsistProfiles.resolve("SH_A6", mixed, ConsistOverrides.NONE, SETTINGS);
    assertTrue(blocked.profile().isEmpty());
    assertEquals(
        List.of(
            new ConsistProfiles.Issue(IssueKind.CONFLICTING_TAG, true, "FTA_TRAIN_TYPE=EMU/METRO")),
        blocked.issues());

    assertTrue(
        ConsistProfiles.resolve("SH_A6", mixed, override(TrainType.EMU), SETTINGS)
            .profile()
            .isPresent(),
        "方案覆盖了车种，出车时会把车上的标签统一改写");
  }

  @Test
  void unreadableTagsAndPlainPatternsOnlyWarn() {
    ConsistInspection plain =
        new ConsistInspection(
            false,
            2,
            3.0,
            Map.of("FTA_TRAIN_TYPE", Set.of("maglev"), "FTA_TRAIN_ACCEL_BPS2", Set.of("fast")),
            OptionalInt.empty());
    ConsistProfiles.Resolution resolution =
        ConsistProfiles.resolve("mm", plain, ConsistOverrides.NONE, SETTINGS);

    assertTrue(resolution.profile().isPresent());
    assertEquals(TrainType.METRO, resolution.profile().get().type());
    assertEquals(1.1, resolution.profile().get().accelBps2(), 1e-9);
    assertEquals(
        List.of(
            new ConsistProfiles.Issue(IssueKind.NOT_SAVED_TRAIN, false, "mm"),
            new ConsistProfiles.Issue(IssueKind.UNREADABLE_TAG, false, "FTA_TRAIN_TYPE=maglev"),
            new ConsistProfiles.Issue(
                IssueKind.UNREADABLE_TAG, false, "FTA_TRAIN_ACCEL_BPS2=fast")),
        resolution.issues());
  }

  @Test
  void patternWithoutCarsIsUnresolved() {
    ConsistProfiles.Resolution resolution =
        ConsistProfiles.resolve(
            "SH_X", ConsistInspection.unresolved(), ConsistOverrides.NONE, SETTINGS);
    assertTrue(resolution.profile().isEmpty());
    assertEquals(
        List.of(new ConsistProfiles.Issue(IssueKind.UNRESOLVED, true, "SH_X")),
        resolution.issues());
  }
}
