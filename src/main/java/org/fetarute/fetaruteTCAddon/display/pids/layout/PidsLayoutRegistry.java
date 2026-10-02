package org.fetarute.fetaruteTCAddon.display.pids.layout;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 布局目录：内置布局与 {@code pids/layouts/} 下的用户布局。
 *
 * <ul>
 *   <li>内置布局始终从 jar 读取，插件更新即生效；不写出到目录，避免“文件不存在才生成”让旧服务器拿不到修正。
 *   <li>目录里一个文件一个布局，文件名（去掉 {@code .yml}）即布局 ID；与内置同名时覆盖内置。
 *   <li>{@link #reload()} 重读整个目录；单个文件有问题只跳过该文件并列出全部问题，其余照常加载。
 * </ul>
 */
public final class PidsLayoutRegistry {

  /** 内置布局 ID。 */
  public static final List<String> BUILT_IN =
      List.of(
          "platform-1x3",
          "platform-1x4",
          "platform-group-1x3",
          "platform-group-1x4",
          "platform-2x1",
          "station-3x5");

  /** 用户布局目录（相对插件数据目录）。 */
  public static final String DIRECTORY = "pids/layouts";

  private static final String RESOURCE_DIR = "pids/layouts/";
  private static final String EXTENSION = ".yml";
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");

  private final Path directory;
  private final Function<String, InputStream> resources;
  private final Logger logger;
  private volatile Map<String, PidsLayout> builtIn = Map.of();
  private volatile Map<String, PidsLayout> layouts = Map.of();

  /**
   * @param directory 用户布局目录
   * @param resources 按资源路径打开内置布局
   * @param logger 日志出口
   */
  public PidsLayoutRegistry(
      Path directory, Function<String, InputStream> resources, Logger logger) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.resources = Objects.requireNonNull(resources, "resources");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  /** 为插件创建：目录为数据目录下的 {@value #DIRECTORY}，内置布局取自插件 jar。 */
  public static PidsLayoutRegistry forPlugin(JavaPlugin plugin) {
    Objects.requireNonNull(plugin, "plugin");
    return new PidsLayoutRegistry(
        plugin.getDataFolder().toPath().resolve(DIRECTORY),
        plugin::getResource,
        plugin.getLogger());
  }

  /** 重新加载内置布局与目录中的全部布局。 */
  public void reload() {
    Map<String, PidsLayout> loadedBuiltIn = new LinkedHashMap<>();
    for (String id : BUILT_IN) {
      loadBuiltIn(id).ifPresent(layout -> loadedBuiltIn.put(id, layout));
    }
    Map<String, PidsLayout> merged = new LinkedHashMap<>(loadedBuiltIn);
    int custom = 0;
    for (Path file : layoutFiles()) {
      Optional<PidsLayout> layout = loadFile(file);
      if (layout.isPresent()) {
        merged.put(layout.get().id(), layout.get());
        custom++;
      }
    }
    // 保持 BUILT_IN 的顺序：同尺寸有多个内置布局时退回排在前面的那个
    builtIn = Collections.unmodifiableMap(loadedBuiltIn);
    layouts = Collections.unmodifiableMap(merged);
    logger.info("站台屏布局已加载: 内置 " + loadedBuiltIn.size() + " 个，自定义 " + custom + " 个");
  }

  /** 按 ID 查找布局。 */
  public Optional<PidsLayout> find(String id) {
    return Optional.ofNullable(id).map(layouts::get);
  }

  /**
   * 屏幕实际使用的布局：指定的布局存在且尺寸与屏幕一致时用它，否则退回同尺寸的内置布局（按 {@link #BUILT_IN} 顺序取第一个， 如 1×3 退回单站台屏而不是多站台屏）。
   *
   * @param id 屏幕记录的布局 ID
   * @param tileRows 屏幕的地图行数
   * @param tileCols 屏幕的地图列数
   * @return 没有同尺寸的内置布局时为空
   */
  public Optional<PidsLayout> resolve(String id, int tileRows, int tileCols) {
    Optional<PidsLayout> chosen =
        find(id).filter(layout -> layout.tileRows() == tileRows && layout.tileCols() == tileCols);
    if (chosen.isPresent()) {
      return chosen;
    }
    return builtIn.values().stream()
        .filter(layout -> layout.tileRows() == tileRows && layout.tileCols() == tileCols)
        .findFirst();
  }

  /** 全部可用布局（内置在前）。 */
  public Collection<PidsLayout> all() {
    return layouts.values();
  }

  private Optional<PidsLayout> loadBuiltIn(String id) {
    String path = RESOURCE_DIR + id + EXTENSION;
    try (InputStream stream = resources.apply(path)) {
      if (stream == null) {
        logger.severe("缺少内置站台屏布局: " + path);
        return Optional.empty();
      }
      return parse(id, new InputStreamReader(stream, StandardCharsets.UTF_8), "内置布局 " + id);
    } catch (IOException ex) {
      logger.severe("读取内置站台屏布局失败: " + path + " " + ex);
      return Optional.empty();
    }
  }

  private List<Path> layoutFiles() {
    try {
      Files.createDirectories(directory);
      try (Stream<Path> files = Files.list(directory)) {
        return files
            .filter(Files::isRegularFile)
            .filter(file -> fileName(file).endsWith(EXTENSION))
            .sorted()
            .toList();
      }
    } catch (IOException ex) {
      logger.warning("无法读取站台屏布局目录 " + directory + ": " + ex);
      return List.of();
    }
  }

  private static String fileName(Path file) {
    return Optional.ofNullable(file.getFileName()).map(Path::toString).orElse("");
  }

  private Optional<PidsLayout> loadFile(Path file) {
    String name = fileName(file);
    String id = name.substring(0, name.length() - EXTENSION.length());
    if (!ID.matcher(id).matches()) {
      logger.warning("站台屏布局 " + name + " 已跳过: 文件名只能用小写字母、数字、连字符与下划线");
      return Optional.empty();
    }
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return parse(id, reader, "站台屏布局 " + name);
    } catch (IOException ex) {
      logger.warning("站台屏布局 " + name + " 已跳过: 读取失败 " + ex);
      return Optional.empty();
    }
  }

  private Optional<PidsLayout> parse(String id, Reader reader, String source) throws IOException {
    YamlConfiguration yaml = new YamlConfiguration();
    try {
      yaml.load(reader);
    } catch (InvalidConfigurationException ex) {
      logger.warning(source + " 已跳过: YAML 语法错误 " + ex.getMessage());
      return Optional.empty();
    }
    PidsLayoutParser.Result result = PidsLayoutParser.parse(id, yaml);
    if (!result.problems().isEmpty()) {
      logger.warning(source + " 已跳过: " + String.join("；", result.problems()));
    }
    return result.layout();
  }
}
