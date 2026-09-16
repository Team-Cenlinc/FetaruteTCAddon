package org.fetarute.fetaruteTCAddon.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigUpdaterTest {

  @TempDir private Path tempDir;

  @Test
  void mergesNewKeyWithCommentsAndKeepsExistingValues() throws IOException {
    String template =
        String.join(
            "\n",
            "# config 版本",
            "config-version: 2",
            "runtime:",
            "  # A",
            "  speed-curve-enabled: true",
            "  # B",
            "  speed-curve-type: \"physics\"",
            "storage:",
            "  backend: sqlite",
            "");
    String existing =
        String.join(
            "\n",
            "config-version: 1",
            "runtime:",
            "  speed-curve-enabled: false",
            "storage:",
            "  backend: mysql",
            "");

    String merged = runUpdate(template, existing);

    assertTrue(merged.contains("speed-curve-enabled: false"));
    assertTrue(merged.contains("# B"));
    assertTrue(merged.contains("speed-curve-type: \"physics\""));
    assertTrue(merged.contains("backend: mysql"));
    assertTrue(merged.contains("config-version: 2"));
  }

  @Test
  void insertsMissingSectionWithComments() throws IOException {
    String template =
        String.join(
            "\n",
            "# config 版本",
            "config-version: 1",
            "runtime:",
            "  # A",
            "  speed-curve-enabled: true",
            "  # B",
            "  speed-curve-type: \"physics\"",
            "storage:",
            "  backend: sqlite",
            "");
    String existing = String.join("\n", "config-version: 1", "storage:", "  backend: sqlite", "");

    String merged = runUpdate(template, existing);

    assertTrue(merged.contains("runtime:"));
    assertTrue(merged.contains("# A"));
    assertTrue(merged.contains("speed-curve-enabled: true"));
    assertTrue(merged.contains("# B"));
    assertTrue(merged.contains("speed-curve-type: \"physics\""));
  }

  private String runUpdate(String template, String existing) throws IOException {
    Path config = tempDir.resolve("config.yml");
    Files.writeString(config, existing, StandardCharsets.UTF_8);
    LoggerManager logger = new LoggerManager(Logger.getLogger("ConfigUpdaterTest"));
    ConfigUpdater updater =
        new ConfigUpdater(
            config.toFile(),
            () -> new ByteArrayInputStream(template.getBytes(StandardCharsets.UTF_8)),
            logger);
    updater.update();
    return Files.readString(config, StandardCharsets.UTF_8);
  }

  @Test
  // 用真实模板对 v29 实服 config 做迁移预演：三个准入控制新键必须被补进去，且既有值不被覆盖。
  // 实服 config 是手工调过的（enabled=true、max-attempts=20），迁移吃掉它们会直接改变发车行为。
  void realTemplateAddsAdmissionControlKeysToLiveConfig() throws IOException {
    String template =
        new String(
            Objects.requireNonNull(
                    ConfigUpdaterTest.class.getClassLoader().getResourceAsStream("config.yml"))
                .readAllBytes(),
            StandardCharsets.UTF_8);
    String existing =
        String.join(
            "\n",
            "config-version: 29",
            "spawn:",
            "  enabled: true",
            "  tick-interval-ticks: 100",
            "  max-spawn-per-tick: 1",
            "  max-attempts: 20",
            "  layover-fallback-multiplier: 2.0",
            "  pending-layover-max-age-seconds: 7200",
            "");

    String merged = runUpdate(template, existing);

    assertTrue(merged.contains("max-active-trains: 16"), "应补入在网列车上限");
    assertTrue(merged.contains("congestion-network-reference-trains: 16"), "应补入全网参考车数（与准入上限解耦的那个）");
    assertTrue(merged.contains("congestion-hold-threshold: 0.58"), "应补入拥挤触发阈值");
    assertTrue(merged.contains("congestion-release-threshold: 0.48"), "应补入拥挤解除阈值");
    // 版本号从**模板**读，不写死：写死的话每次升版本都要改这条用例，
    // 而它真正要钉的不是“等于 30”，是“合并后对齐模板且确实升了”。
    int templateVersion = versionOf(template);
    assertTrue(templateVersion > 29, "前置：模板版本应高于旧配置的 29");
    assertTrue(
        merged.contains("config-version: " + templateVersion), () -> "应升到模板版本 " + templateVersion);
    assertTrue(merged.contains("enabled: true"), "既有的自动发车开关不能被模板覆盖");
    assertTrue(merged.contains("max-attempts: 20"), "既有的重试次数不能被模板覆盖");
  }

  private static int versionOf(String yaml) {
    for (String line : yaml.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("config-version:")) {
        return Integer.parseInt(trimmed.substring("config-version:".length()).trim());
      }
    }
    throw new IllegalStateException("模板里没有 config-version");
  }
}
