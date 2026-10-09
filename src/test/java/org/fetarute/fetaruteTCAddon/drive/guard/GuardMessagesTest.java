package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveSidebarRows;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 车掌按枚举与阶段拼出的提示都有文案。 */
class GuardMessagesTest {

  private static YamlConfiguration lang(String localeTag) throws Exception {
    try (InputStream stream =
        GuardMessagesTest.class
            .getClassLoader()
            .getResourceAsStream("lang/" + localeTag + ".yml")) {
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      return yaml;
    }
  }

  private static String keyOf(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void everyOutcomeAndReasonHasAMessage(String localeTag) throws Exception {
    YamlConfiguration lang = lang(localeTag);
    List<String> keys = new ArrayList<>();
    for (GuardSessionManager.StartOutcome outcome : GuardSessionManager.StartOutcome.values()) {
      keys.add("drive.guard.command.start." + keyOf(outcome));
    }
    for (GuardSession.EndReason reason : GuardSession.EndReason.values()) {
      keys.add("drive.guard.end." + keyOf(reason));
    }
    for (GuardSessionManager.IncidentReason reason : GuardSessionManager.IncidentReason.values()) {
      keys.add("drive.guard.report.reason." + reason.name().toLowerCase(Locale.ROOT));
    }
    keys.add("drive.command.start.guard-on-duty");
    keys.add("drive.menu.deny.guard-doors");
    for (String key : keys) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }

  /** 各阶段、各种发车前状态、各开门侧的提示与侧边栏行都有文案。 */
  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void everyPromptAndRowHasAMessage(String localeTag) throws Exception {
    YamlConfiguration lang = lang(localeTag);
    List<String> keys = new ArrayList<>();
    for (Phase phase : Phase.values()) {
      for (DriverDoorSide side : DriverDoorSide.values()) {
        for (boolean flag : new boolean[] {false, true}) {
          for (boolean seated : new boolean[] {false, true}) {
            GuardDisplay.StopState stop =
                new GuardDisplay.StopState(
                    "S", phase, side, flag, !flag, flag, 20L, flag, !flag, false);
            GuardDisplay.Snapshot snapshot =
                new GuardDisplay.Snapshot(
                    "T",
                    flag ? Optional.of("D") : Optional.empty(),
                    flag,
                    Optional.of(stop),
                    seated,
                    1);
            keys.add(GuardDisplay.prompt(snapshot).key());
            for (DriveSidebarRows.Row row : GuardDisplay.rows(snapshot)) {
              keys.add(row.labelKey());
              keys.add(row.valueKey());
            }
          }
        }
      }
    }
    keys.add("drive.guard.prompt.running");
    keys.add("drive.guard.sidebar.title");
    for (String key : keys) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }
}
