package org.fetarute.fetaruteTCAddon.drive.license;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 驾驶证服务与命令里写死的、按考法拼出的语言键都有文案。 */
class LicenseMessagesTest {

  private static final String SOURCE_ROOT = "src/main/java/org/fetarute/fetaruteTCAddon";

  private static YamlConfiguration lang(String localeTag) throws Exception {
    try (InputStream stream =
        LicenseMessagesTest.class
            .getClassLoader()
            .getResourceAsStream("lang/" + localeTag + ".yml")) {
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      return yaml;
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void everyKeyHasAMessage(String localeTag) throws Exception {
    YamlConfiguration lang = lang(localeTag);
    Pattern literal = Pattern.compile("\"(drive\\.(?:license|handbook)\\.[a-z0-9.-]*[a-z0-9])\"");
    List<String> keys = new ArrayList<>();
    for (String source :
        List.of("drive/license/LicenseService.java", "command/FtaLicenseCommand.java")) {
      Matcher matcher =
          literal.matcher(Files.readString(Path.of(SOURCE_ROOT, source), StandardCharsets.UTF_8));
      while (matcher.find()) {
        keys.add(matcher.group(1));
      }
    }
    assertTrue(keys.contains("drive.license.practice.choose"));
    for (String line : List.of("header", "route", "how", "handbook")) {
      keys.add("drive.license.exam.brief.guard." + line);
      keys.add("drive.license.practice.guard.brief." + line);
    }
    keys.add("drive.license.practice.guard.brief.what");
    for (String line : List.of("duties", "watch", "pass", "no-wrong-door", "drill")) {
      keys.add("drive.license.exam.brief.guard." + line);
    }
    for (String verdict : List.of("passed", "failed", "void")) {
      keys.add("drive.license.practice.guard.review." + verdict);
      keys.add("drive.license.exam.guard." + verdict);
    }
    for (String line : List.of("header", "route", "pass", "handbook", "feedback", "how")) {
      keys.add("drive.license.practice.brief." + line);
    }
    for (String line : List.of("header", "route", "pass", "how", "handbook", "feedback")) {
      keys.add("drive.license.exam.brief.road-test." + line);
    }
    keys.add("drive.guard.result-practice");
    for (String key : keys) {
      // 拼键用的前缀（如“drive.license.practice”后接“.warn.…”）是一段，不是文案。
      assertTrue(lang.isString(key) || lang.isConfigurationSection(key), localeTag + " 缺少 " + key);
    }
  }
}
