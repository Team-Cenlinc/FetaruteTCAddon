package org.fetarute.fetaruteTCAddon.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  }
}
