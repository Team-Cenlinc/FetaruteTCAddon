package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
    for (GuardTasks.ClaimOutcome outcome : GuardTasks.ClaimOutcome.values()) {
      keys.add("drive.guard.task.claim." + keyOf(outcome));
    }
    for (GuardTask.State state : GuardTask.State.values()) {
      keys.add("drive.guard.task.state." + keyOf(state));
    }
    for (String reason : List.of("departed", "timeout", "pickup-timeout", "disabled")) {
      keys.add("drive.guard.task.ended." + reason);
    }
    for (String key : List.of("arrived", "wrong-cab", "hold")) {
      keys.add("drive.guard.task." + key);
      keys.add("drive.guard.task." + key + "-either");
    }
    for (String item :
        List.of(
            "title",
            "info",
            "info-left",
            "info-claimed",
            "entry-left",
            "entry-claimed",
            "entry-claimed-self")) {
      keys.add("drive.guard.board." + item);
    }
    for (String sub : List.of("on", "off", "seat", "status", "tasks", "task", "stop")) {
      keys.add("drive.guard.command.help." + sub);
    }
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
                    1,
                    seated
                        ? Optional.of(new GuardDisplay.CabChangeState(3, flag ? 40L : -1L))
                        : Optional.empty(),
                    Optional.empty());
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
    GuardDisplay.Snapshot between =
        new GuardDisplay.Snapshot(
            "T",
            Optional.empty(),
            false,
            Optional.empty(),
            true,
            0,
            Optional.empty(),
            Optional.of("S"));
    keys.add(GuardDisplay.prompt(between).key());
    for (DriveSidebarRows.Row row : GuardDisplay.rows(between)) {
      keys.add(row.labelKey());
      keys.add(row.valueKey());
    }
    GuardScore all = new GuardScore();
    all.add(
        new GuardScore.Stop(
            "S", true, true, true, true, true, Optional.of(false), Optional.of(false), 1));
    for (GuardDisplay.Line line : GuardDisplay.sheet(all)) {
      keys.add(line.key());
    }
    keys.add(GuardDisplay.sheet(new GuardScore()).get(0).key());
    for (org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask.State state :
        org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask.State.values()) {
      keys.add("drive.task.state." + state.name().toLowerCase(Locale.ROOT));
    }
    for (String key : keys) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }

  /** 车掌代码里写死的语言键（含驾驶员那边换端一起送的提示）都有文案。 */
  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void everyLiteralKeyInTheGuardCodeHasAMessage(String localeTag) throws Exception {
    YamlConfiguration lang = lang(localeTag);
    Pattern literal =
        Pattern.compile("\"(drive\\.(?:guard|task\\.cab-change)\\.[a-z0-9.-]*[a-z0-9])\"");
    List<Path> sources = new ArrayList<>();
    try (Stream<Path> files = Files.list(Path.of(SOURCE_ROOT, "drive/guard"))) {
      files.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
    }
    sources.add(Path.of(SOURCE_ROOT, "drive/session/DriveSessionManager.java"));
    sources.add(Path.of(SOURCE_ROOT, "command/FtaGuardCommand.java"));
    List<String> keys = new ArrayList<>();
    for (Path source : sources) {
      Matcher matcher = literal.matcher(Files.readString(source, StandardCharsets.UTF_8));
      while (matcher.find()) {
        keys.add(matcher.group(1));
      }
    }
    assertTrue(keys.contains("drive.guard.cab-change.moved-with-driver"));
    // 发车信号要驾驶员回一短时，在原键后加 -ack。
    keys.add("drive.guard.driver.signal-ack");
    keys.add("drive.guard.driver.signal-forced-ack");
    for (String key : keys) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }

  private static final String SOURCE_ROOT = "src/main/java/org/fetarute/fetaruteTCAddon";
}
