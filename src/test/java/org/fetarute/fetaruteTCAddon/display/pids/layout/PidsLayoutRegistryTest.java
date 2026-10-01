package org.fetarute.fetaruteTCAddon.display.pids.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 布局目录：内置布局始终在；目录里的同名文件覆盖内置；有问题的文件只跳过自己；屏幕按尺寸退回内置布局。 */
class PidsLayoutRegistryTest {

  private static final String CUSTOM_1X2 =
      """
      format: 1
      name: 自定义 1×2
      tiles: {rows: 1, cols: 2}
      widgets:
        - type: clock
          x: 8
          y: 92
      """;

  @TempDir Path dataFolder;

  private final List<String> warnings = new ArrayList<>();
  private Path directory;
  private PidsLayoutRegistry registry;

  @BeforeEach
  void setUp() {
    directory = dataFolder.resolve(PidsLayoutRegistry.DIRECTORY);
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    logger.addHandler(
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
              warnings.add(record.getMessage());
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        });
    registry =
        new PidsLayoutRegistry(
            directory, PidsLayoutRegistryTest.class.getClassLoader()::getResourceAsStream, logger);
  }

  @Test
  void builtInLayoutsLoadAndTheDirectoryIsCreatedEmpty() throws Exception {
    registry.reload();

    assertEquals(PidsLayoutRegistry.BUILT_IN, registry.all().stream().map(PidsLayout::id).toList());
    assertTrue(Files.isDirectory(directory));
    try (var files = Files.list(directory)) {
      assertEquals(0, files.count(), "内置布局不写出到目录");
    }
    assertTrue(warnings.isEmpty(), warnings::toString);
  }

  @Test
  void customLayoutsAreAddedAndSameIdOverridesBuiltIn() throws Exception {
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("custom-1x2.yml"), CUSTOM_1X2, StandardCharsets.UTF_8);
    Files.writeString(
        directory.resolve("platform-1x3.yml"),
        CUSTOM_1X2.replace("cols: 2", "cols: 3").replace("1×2", "1×3"),
        StandardCharsets.UTF_8);

    registry.reload();

    assertEquals("自定义 1×2", registry.find("custom-1x2").orElseThrow().name());
    assertEquals("自定义 1×3", registry.find("platform-1x3").orElseThrow().name());
  }

  @Test
  void brokenFilesAreSkippedWithTheirProblems() throws Exception {
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("good.yml"), CUSTOM_1X2, StandardCharsets.UTF_8);
    Files.writeString(
        directory.resolve("bad.yml"),
        CUSTOM_1X2.replace("y: 92", "y: 300"),
        StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("syntax.yml"), "tiles: [", StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("Upper Case.yml"), CUSTOM_1X2, StandardCharsets.UTF_8);

    registry.reload();

    assertTrue(registry.find("good").isPresent());
    assertTrue(registry.find("bad").isEmpty());
    assertTrue(registry.find("syntax").isEmpty());
    assertEquals(3, warnings.size(), warnings::toString);
    assertTrue(warnings.stream().anyMatch(w -> w.contains("bad.yml") && w.contains("超出画布")));
    assertTrue(warnings.stream().anyMatch(w -> w.contains("syntax.yml") && w.contains("YAML")));
    assertTrue(warnings.stream().anyMatch(w -> w.contains("Upper Case.yml")));
  }

  @Test
  void screensFallBackToTheBuiltInLayoutOfTheirSize() throws Exception {
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("custom-1x2.yml"), CUSTOM_1X2, StandardCharsets.UTF_8);
    registry.reload();

    assertEquals("custom-1x2", registry.resolve("custom-1x2", 1, 2).orElseThrow().id());
    assertEquals("platform-1x3", registry.resolve("missing", 1, 3).orElseThrow().id(), "布局不存在");
    assertEquals("platform-1x3", registry.resolve("custom-1x2", 1, 3).orElseThrow().id(), "尺寸不符");
    assertEquals("platform-1x4", registry.resolve("missing", 1, 4).orElseThrow().id());
    assertEquals("station-3x5", registry.resolve(null, 3, 5).orElseThrow().id());
    assertTrue(registry.resolve("missing", 2, 2).isEmpty(), "没有同尺寸的内置布局");
  }
}
