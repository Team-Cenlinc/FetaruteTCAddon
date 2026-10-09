package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 车掌第一次遇到每一步时的说明：按站停作业的先后出，出过的不再出。 */
class GuardTipTest {

  private static GuardDisplay.Snapshot snapshot(
      GuardDisplay.StopState stop, boolean seated, GuardDisplay.CabChangeState change) {
    return new GuardDisplay.Snapshot(
        "T-1",
        Optional.empty(),
        true,
        Optional.ofNullable(stop),
        seated,
        0,
        Optional.ofNullable(change),
        Optional.empty());
  }

  private static GuardDisplay.StopState stop(
      Phase phase,
      DriverDoorSide side,
      boolean closing,
      boolean exitOpen,
      boolean confirmed,
      boolean released) {
    return new GuardDisplay.StopState(
        "PPK", phase, side, false, false, closing, 200L, exitOpen, confirmed, released);
  }

  private static Optional<GuardTip> next(GuardDisplay.Snapshot snapshot, GuardTip... shown) {
    Set<GuardTip> seen = EnumSet.noneOf(GuardTip.class);
    seen.addAll(Set.of(shown));
    return GuardTip.firstDue(snapshot, seen);
  }

  @Test
  void firstDutyComesFirst() {
    assertEquals(Optional.of(GuardTip.ON_DUTY), next(snapshot(null, true, null)));
    assertEquals(Optional.empty(), next(snapshot(null, true, null), GuardTip.ON_DUTY), "站间没有别的提示");
  }

  @Test
  void eachStepInOrder() {
    GuardTip on = GuardTip.ON_DUTY;
    assertEquals(
        Optional.of(GuardTip.OPEN_DOORS),
        next(
            snapshot(
                stop(Phase.OPEN_DOORS, DriverDoorSide.LEFT, false, false, false, false),
                true,
                null),
            on));
    assertEquals(
        Optional.empty(),
        next(
            snapshot(
                stop(Phase.OPEN_DOORS, DriverDoorSide.NONE, false, false, false, false),
                true,
                null),
            on),
        "本站不开门时不教开门");
    assertEquals(
        Optional.of(GuardTip.CLOSE_DOORS),
        next(
            snapshot(
                stop(Phase.CLOSE_DOORS, DriverDoorSide.LEFT, false, false, false, false),
                false,
                null),
            on));
    assertEquals(
        Optional.of(GuardTip.RETURN_SEAT),
        next(
            snapshot(
                stop(Phase.WAIT_DEPARTURE, DriverDoorSide.LEFT, false, true, false, false),
                false,
                null),
            on));
    assertEquals(
        Optional.of(GuardTip.WAIT_EXIT),
        next(
            snapshot(
                stop(Phase.WAIT_DEPARTURE, DriverDoorSide.LEFT, false, false, false, false),
                true,
                null),
            on));
    assertEquals(
        Optional.of(GuardTip.CONFIRM),
        next(
            snapshot(
                stop(Phase.WAIT_DEPARTURE, DriverDoorSide.LEFT, false, true, false, false),
                true,
                null),
            on));
    assertEquals(
        Optional.of(GuardTip.BUZZER),
        next(
            snapshot(
                stop(Phase.WAIT_DEPARTURE, DriverDoorSide.LEFT, false, true, true, false),
                true,
                null),
            on));
    assertEquals(
        Optional.of(GuardTip.DEPARTURE_WATCH),
        next(
            snapshot(
                stop(Phase.WAIT_DEPARTURE, DriverDoorSide.LEFT, false, true, true, true),
                true,
                null),
            on));
    assertEquals(
        Optional.of(GuardTip.CAB_CHANGE),
        next(snapshot(null, true, new GuardDisplay.CabChangeState(4, -1L)), on));
  }

  /** 关门动画在放时不再教关门（已经在关了）。 */
  @Test
  void closingIsNotAskedToClose() {
    assertEquals(
        Optional.empty(),
        next(
            snapshot(
                stop(Phase.CLOSE_DOORS, DriverDoorSide.LEFT, true, false, false, false),
                false,
                null),
            GuardTip.ON_DUTY));
  }

  @Test
  void keysAreUnique() {
    Set<String> keys = new java.util.HashSet<>();
    for (GuardTip tip : GuardTip.values()) {
      assertTrue(keys.add(tip.key()), tip.name());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void everyTipHasMessages(String localeTag) throws Exception {
    YamlConfiguration lang = new YamlConfiguration();
    try (InputStream stream =
        GuardTipTest.class.getClassLoader().getResourceAsStream("lang/" + localeTag + ".yml")) {
      lang.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertTrue(lang.isString("drive.guard.tip"), localeTag);
    for (GuardTip tip : GuardTip.values()) {
      for (String part : new String[] {".chat", ".subtitle"}) {
        String key = "drive.guard.tips." + tip.key() + part;
        assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
      }
    }
  }
}
