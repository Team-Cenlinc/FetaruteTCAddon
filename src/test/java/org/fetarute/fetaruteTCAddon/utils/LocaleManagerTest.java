package org.fetarute.fetaruteTCAddon.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public final class LocaleManagerTest {

  @Test
  void enumTextShouldResolveLocalizedValues(@TempDir Path tempDir) throws Exception {
    Path langDir = tempDir.resolve("lang");
    Files.createDirectories(langDir);

    // 写入一个最小语言文件，避免依赖真实插件 dataFolder。
    // LocaleManager 会把内置模板缺失键合并进来，因此这里只需要提供 prefix 即可。
    Files.writeString(langDir.resolve("zh_CN.yml"), "prefix: \"\"\n", StandardCharsets.UTF_8);

    LoggerManager logger = new LoggerManager(Logger.getLogger("LocaleManagerTest"));
    logger.setDebugEnabled(false);

    LocaleManager.LocaleAccess access =
        new LocaleManager.LocaleAccess(tempDir.toFile(), logger, (path, replace) -> {});
    LocaleManager locale = new LocaleManager(access, "zh_CN", logger);
    locale.reload();

    assertEquals("地铁", locale.enumText("enum.line-service-type", LineServiceType.METRO));
    assertEquals("快速", locale.enumText("enum.route-pattern-type", RoutePatternType.RAPID));

    // 未知前缀应回退到枚举 name()
    assertEquals("RAPID", locale.enumText("enum.unknown", RoutePatternType.RAPID));

    // 纯文本键读取（用于 list/status 等占位符）
    assertEquals("生效", locale.text("command.graph.edge.list.status.active"));
  }

  /** 旧文案清单里的每个键都还在内置语言文件里，且旧值与现在的文案不同（否则换了也白换）。 */
  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void supersededListMatchesTheBundledLocale(String localeTag) throws Exception {
    YamlConfiguration bundled = bundled("lang/" + localeTag + ".yml");
    YamlConfiguration superseded = bundled("lang-superseded/" + localeTag + ".yml");

    for (String key : superseded.getKeys(true)) {
      if (superseded.isConfigurationSection(key)) {
        continue;
      }
      assertTrue(bundled.isString(key), () -> key + " 不在内置语言文件里");
      assertFalse(superseded.getStringList(key).isEmpty(), key);
      assertFalse(superseded.getStringList(key).contains(bundled.getString(key)), key);
    }
  }

  private static YamlConfiguration bundled(String path) throws Exception {
    try (InputStream stream = LocaleManagerTest.class.getClassLoader().getResourceAsStream(path)) {
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      return yaml;
    }
  }

  /** 服务器语言文件里仍是改写前旧内置文案的键换成新文案，改过的保留，并写回文件。 */
  @Test
  void supersededBuiltInTextIsReplacedButCustomTextIsKept(@TempDir Path tempDir) throws Exception {
    Path langDir = tempDir.resolve("lang");
    Files.createDirectories(langDir);
    Files.writeString(
        langDir.resolve("zh_CN.yml"),
        String.join(
            "\n",
            "prefix: \"\"",
            "pids:",
            "  board:",
            "    notice:",
            "      order:",
            "        body: \"请让乘客先下车\"",
            "      queue:",
            "        body: \"本站自定义的排队提示\"",
            ""),
        StandardCharsets.UTF_8);
    LoggerManager logger = new LoggerManager(Logger.getLogger("LocaleManagerTest"));
    LocaleManager locale =
        new LocaleManager(
            new LocaleManager.LocaleAccess(tempDir.toFile(), logger, (path, replace) -> {}),
            "zh_CN",
            logger);

    locale.reload();

    assertEquals("请在车门两侧等候", locale.text("pids.board.notice.order.body"), "没改过的旧文案换成新的");
    assertEquals("本站自定义的排队提示", locale.text("pids.board.notice.queue.body"), "改过的保留");
    assertTrue(
        Files.readString(langDir.resolve("zh_CN.yml"), StandardCharsets.UTF_8).contains("请在车门两侧等候"),
        "写回服务器的语言文件");
  }

  /** 点击命令参数里的占位符换成实际值：MiniMessage 本身不在引号参数里解析占位符。 */
  @Test
  void clickCommandArgumentsGetPlaceholderValues(@TempDir Path tempDir) throws Exception {
    assertEquals(
        "<click:run_command:'/fta license exam dispatch'>x</click> <class>",
        LocaleManager.expandClickArguments(
            "<click:run_command:'/fta license exam <class>'>x</click> <class>",
            java.util.Map.of("class", "dispatch")));
    assertEquals(
        "<click:suggest_command:\"/say it's\">y</click>",
        LocaleManager.expandClickArguments(
            "<click:suggest_command:\"/say <text>\">y</click>", java.util.Map.of("text", "it's")),
        "双引号参数里的单引号不用转义");
    assertEquals(
        "<click:run_command:'/say it\\'s'>z</click>",
        LocaleManager.expandClickArguments(
            "<click:run_command:'/say <text>'>z</click>", java.util.Map.of("text", "it's")));

    Path langDir = tempDir.resolve("lang");
    Files.createDirectories(langDir);
    Files.writeString(langDir.resolve("zh_CN.yml"), "prefix: \"\"\n", StandardCharsets.UTF_8);
    LoggerManager logger = new LoggerManager(Logger.getLogger("LocaleManagerTest"));
    logger.setDebugEnabled(false);
    LocaleManager locale =
        new LocaleManager(
            new LocaleManager.LocaleAccess(tempDir.toFile(), logger, (path, replace) -> {}),
            "zh_CN",
            logger);
    locale.reload();
    net.kyori.adventure.text.Component line =
        locale.component(
            "drive.license.exam.retry-now", java.util.Map.of("class", "dispatch", "minutes", "10"));
    java.util.List<String> commands = new java.util.ArrayList<>();
    collectClicks(line, commands);
    assertEquals(java.util.List.of("/fta license exam dispatch"), commands);
  }

  private static void collectClicks(
      net.kyori.adventure.text.Component component, java.util.List<String> out) {
    net.kyori.adventure.text.event.ClickEvent click = component.clickEvent();
    if (click != null && !out.contains(click.value())) {
      out.add(click.value());
    }
    for (net.kyori.adventure.text.Component child : component.children()) {
      collectClicks(child, out);
    }
  }
}
