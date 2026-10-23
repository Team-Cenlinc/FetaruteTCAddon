package org.fetarute.fetaruteTCAddon.drive.crew;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("驾驶模块里信号员相关的提示都有文案")
class SignallerMessagesTest {

  private static YamlConfiguration lang(String localeTag) throws Exception {
    try (InputStream stream =
        SignallerMessagesTest.class
            .getClassLoader()
            .getResourceAsStream("lang/" + localeTag + ".yml")) {
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      return yaml;
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  @DisplayName("班次结算、记录里的下岗原因、呼叫信号员与被拒的说法")
  void everyKeyHasAMessage(String localeTag) throws Exception {
    YamlConfiguration lang = lang(localeTag);
    List<String> keys =
        new ArrayList<>(
            List.of(
                "drive.signaller.result.summary",
                "drive.signaller.result.no-trains",
                "drive.driver.signaller.called",
                "drive.driver.signaller.none",
                "drive.command.call.called",
                "drive.command.call.none",
                "drive.command.call.not-dispatch",
                "drive.command.help.entry-call",
                "drive.task.claim.signaller-busy",
                "drive.license.exam.signaller-on-duty"));
    for (SignallerShift.EndReason reason : SignallerShift.EndReason.values()) {
      keys.add("drive.signaller.end." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
    for (String key : keys) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }
}
