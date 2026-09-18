package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnGroup;
import org.junit.jupiter.api.Test;

/** baseline 频率是编表的出发点：显式参数 > 线路 baseline > 交路组 baseline > 默认值。 */
class TimetableHeadwayDefaultsTest {

  @Test
  void explicitHeadwayWinsOverEverything() {
    TimetableHeadwayDefaults.Choice choice =
        TimetableHeadwayDefaults.resolve(
            Optional.of(240), Optional.of(300), List.of(group("a", 120)));

    assertEquals(240, choice.seconds());
    assertEquals(TimetableHeadwayDefaults.Source.EXPLICIT, choice.source());
  }

  @Test
  void lineBaselineBeatsGroupBaseline() {
    TimetableHeadwayDefaults.Choice choice =
        TimetableHeadwayDefaults.resolve(
            Optional.empty(), Optional.of(300), List.of(group("a", 120)));

    assertEquals(300, choice.seconds());
    assertEquals(TimetableHeadwayDefaults.Source.LINE_BASELINE, choice.source());
  }

  @Test
  void singleGroupBaselineIsUsedAsIs() {
    TimetableHeadwayDefaults.Choice choice =
        TimetableHeadwayDefaults.resolve(
            Optional.empty(), Optional.empty(), List.of(group("main", 180), group("nobase", 0)));

    assertEquals(180, choice.seconds());
    assertEquals(TimetableHeadwayDefaults.Source.GROUP_BASELINE, choice.source());
  }

  /** 两个组各 10 分钟一班，全线就是 5 分钟一班：时刻表以整条线路为单位排班。 */
  @Test
  void multipleGroupBaselinesCombineByFrequency() {
    TimetableHeadwayDefaults.Choice choice =
        TimetableHeadwayDefaults.resolve(
            Optional.empty(), Optional.empty(), List.of(group("short", 600), group("long", 600)));

    assertEquals(300, choice.seconds());
    assertEquals(TimetableHeadwayDefaults.Source.GROUP_BASELINE, choice.source());
  }

  @Test
  void fallsBackToTheBuiltInDefault() {
    TimetableHeadwayDefaults.Choice choice =
        TimetableHeadwayDefaults.resolve(Optional.of(0), Optional.of(-5), List.of());

    assertEquals(TimetableBuildOptions.DEFAULT_HEADWAY_SECONDS, choice.seconds());
    assertEquals(TimetableHeadwayDefaults.Source.DEFAULT, choice.source());
  }

  private static SpawnGroup group(String name, int baseline) {
    return new SpawnGroup(name, baseline > 0 ? Optional.of(baseline) : Optional.empty());
  }
}
