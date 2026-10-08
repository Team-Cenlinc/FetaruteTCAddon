package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("驾驶会话按枚举拼出的提示都有文案")
class DriveSessionMessagesTest {

  private static YamlConfiguration lang(String localeTag) throws Exception {
    try (InputStream stream =
        DriveSessionMessagesTest.class
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
  @DisplayName("开始驾驶的每种结果都有提示，另有别人已领时的收回按钮与传送到驾驶座按钮")
  void everyStartOutcomeHasAMessage(String localeTag) throws Exception {
    YamlConfiguration lang = lang(localeTag);
    for (DriveSessionManager.StartOutcome outcome : DriveSessionManager.StartOutcome.values()) {
      String key = "drive.command.start." + keyOf(outcome);
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
    for (String key :
        new String[] {
          "drive.command.start.reserved-by-other-revoke", "drive.command.start.cab-offer"
        }) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  @DisplayName("标记驾驶座的每种结果都有提示")
  void everySeatMarkOutcomeHasAMessage(String localeTag) throws Exception {
    YamlConfiguration lang = lang(localeTag);
    for (String key :
        new String[] {
          "player-only", "no-cab-names", "not-seated", "shared-model", "marked", "already-marked",
          "car-full", "also-marked", "no-such-seat", "unmarked", "not-marked", "end-head",
          "end-tail", "end-middle", "end-none", "end-inner", "end-unmarked-train", "save-hint",
          "save-hover"
        }) {
      assertTrue(
          lang.isString("command.train.attachment." + key),
          localeTag + " 缺少 command.train.attachment." + key);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  @DisplayName("接管时没记成任务、终点没有接续车次：每种原因都有说法")
  void everyReasonHasAMessage(String localeTag) throws Exception {
    YamlConfiguration lang = lang(localeTag);
    for (DriverTaskManager.ClaimOutcome outcome : DriverTaskManager.ClaimOutcome.values()) {
      if (outcome == DriverTaskManager.ClaimOutcome.CLAIMED) {
        continue;
      }
      String key = "drive.task.not-recorded." + keyOf(outcome);
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
    for (TimetableService.NoNextTripReason reason : TimetableService.NoNextTripReason.values()) {
      String key = "drive.task.no-next-trip." + keyOf(reason);
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
    for (String key :
        new String[] {
          "drive.task.no-next-trip.taken",
          "drive.task.takeover.untracked",
          "drive.task.takeover.not-recorded",
          "drive.task.takeover.finished-next",
          "drive.task.takeover.finished-next-code",
          "drive.task.takeover.finished-no-next",
          "drive.task.settled-no-next-trip",
          "drive.task.no-next-trip-line",
          "drive.task.reward.both",
          "drive.task.reward.experience",
          "drive.task.reward.money"
        }) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }
}
