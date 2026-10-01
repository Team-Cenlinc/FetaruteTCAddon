package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.display.pids.PidsPlatformSelection.Outcome;
import org.fetarute.fetaruteTCAddon.display.pids.PidsPlatformSelection.Result;
import org.fetarute.fetaruteTCAddon.display.pids.fixtures.PidsFixtures;
import org.junit.jupiter.api.Test;

/** 站台选择：单站台屏换选、多站台屏限量增删、车站统屏任意或全部；自动绑定取最近的几个。 */
class PidsPlatformSelectionTest {

  private static final OptionalInt SINGLE = OptionalInt.of(1);
  private static final OptionalInt UP_TO_FOUR = OptionalInt.of(4);
  private static final OptionalInt ANY = OptionalInt.empty();
  private static final PidsStationKey HHU = new PidsStationKey("SURC", "HHU");
  private static final PidsStationKey TPC = new PidsStationKey("SURC", "TPC");

  @Test
  void builtInLayoutsDeclareTheirLimits() {
    assertEquals(SINGLE, PidsPlatformSelection.limit(PidsFixtures.builtInLayout("platform-1x3")));
    assertEquals(SINGLE, PidsPlatformSelection.limit(PidsFixtures.builtInLayout("platform-1x4")));
    assertEquals(
        UP_TO_FOUR, PidsPlatformSelection.limit(PidsFixtures.builtInLayout("platform-group-1x3")));
    assertEquals(
        UP_TO_FOUR, PidsPlatformSelection.limit(PidsFixtures.builtInLayout("platform-group-1x4")));
    assertEquals(ANY, PidsPlatformSelection.limit(PidsFixtures.builtInLayout("station-3x5")));
  }

  @Test
  void singlePlatformScreensSwitchAndNeverGoEmpty() {
    assertEquals(
        new Result(Outcome.OK, Set.of("4")),
        PidsPlatformSelection.select(Set.of("1"), "4", SINGLE));
    assertEquals(
        new Result(Outcome.OK, Set.of("1")),
        PidsPlatformSelection.select(Set.of("1"), "1", SINGLE),
        "点已选的站台不会清空");
    assertEquals(
        new Result(Outcome.NEED_PLATFORM, Set.of("1")),
        PidsPlatformSelection.select(Set.of("1"), "all", SINGLE));
  }

  @Test
  void groupScreensToggleWithinTheirLimit() {
    assertEquals(
        new Result(Outcome.OK, Set.of("3", "4")),
        PidsPlatformSelection.select(Set.of("3"), "4", UP_TO_FOUR));
    assertEquals(
        new Result(Outcome.NEED_PLATFORM, Set.of("3")),
        PidsPlatformSelection.select(Set.of("3"), "3", UP_TO_FOUR),
        "不能去掉最后一个");
    assertEquals(
        new Result(Outcome.TOO_MANY, Set.of("1", "2", "3", "4")),
        PidsPlatformSelection.select(Set.of("1", "2", "3", "4"), "5", UP_TO_FOUR));
    assertEquals(
        new Result(Outcome.NEED_PLATFORM, Set.of("3")),
        PidsPlatformSelection.select(Set.of("3"), "ALL", UP_TO_FOUR));
  }

  @Test
  void stationScreensTakeAnySubsetOrAll() {
    assertEquals(
        new Result(Outcome.OK, Set.of("1", "2")),
        PidsPlatformSelection.select(Set.of("1"), "2", ANY));
    assertEquals(
        new Result(Outcome.OK, Set.of()), PidsPlatformSelection.select(Set.of("1"), "1", ANY));
    assertEquals(
        new Result(Outcome.OK, Set.of()),
        PidsPlatformSelection.select(Set.of("1", "2"), "all", ANY));
  }

  @Test
  void autoBindingTakesTheNearestPlatformsOfThatStation() {
    List<PidsPlatformNode> nearby =
        List.of(
            new PidsPlatformNode(HHU, "3"),
            new PidsPlatformNode(TPC, "1"),
            new PidsPlatformNode(HHU, "4"),
            new PidsPlatformNode(HHU, "1"));

    assertEquals(Set.of("3"), PidsPlatformSelection.nearest(nearby, HHU, SINGLE));
    assertEquals(
        List.of("3", "4"),
        List.copyOf(PidsPlatformSelection.nearest(nearby, HHU, OptionalInt.of(2))),
        "由近到远");
    assertEquals(Set.of(), PidsPlatformSelection.nearest(nearby, HHU, ANY), "车站统屏显示全部");
  }
}
