package org.fetarute.fetaruteTCAddon.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;

/** 将默认配置合并到用户配置，填补新键并保留用户已改值。 */
public final class ConfigUpdater {

  private final File configFile;
  private final Supplier<InputStream> defaultSupplier;
  private final LoggerManager logger;

  public ConfigUpdater(
      File configFile, Supplier<InputStream> defaultSupplier, LoggerManager logger) {
    this.configFile = configFile;
    this.defaultSupplier = defaultSupplier;
    this.logger = logger;
  }

  public static ConfigUpdater forPlugin(
      java.io.File dataFolder,
      java.util.function.Supplier<InputStream> defaultSupplier,
      LoggerManager logger) {
    return new ConfigUpdater(new File(dataFolder, "config.yml"), defaultSupplier, logger);
  }

  /** 执行合并。若未检测到差异则不写盘。 */
  public UpdateResult update() {
    if (defaultSupplier == null) {
      return UpdateResult.empty();
    }
    try (InputStream defaultStream = defaultSupplier.get()) {
      if (defaultStream == null) {
        logger.warn("未找到内置配置模板，跳过配置合并");
        return UpdateResult.empty();
      }
      if (!configFile.exists()) {
        ensureParent();
        Files.copy(defaultStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        logger.info("已创建默认配置文件");
        return new UpdateResult(true, 0, readVersion(configFile), List.of(), List.of());
      }

      YamlConfiguration defaultConfig =
          YamlConfiguration.loadConfiguration(
              new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
      YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile);

      UpdateState state = merge(defaultConfig, existing);
      List<String> existingLines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
      List<String> defaultLines = readTemplateLines();
      List<String> mergedLines =
          mergeWithComments(defaultLines, existingLines, defaultConfig, existing, state.addedKeys);
      String mergedText = joinLines(mergedLines);
      String existingText = joinLines(existingLines);
      boolean textChanged = !Objects.equals(existingText, mergedText);
      if (!state.changed && !textChanged) {
        return new UpdateResult(false, state.oldVersion, state.newVersion, List.of(), List.of());
      }

      backupConfig();
      Files.writeString(
          configFile.toPath(),
          mergedText,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      logResult(state);
      return new UpdateResult(
          true, state.oldVersion, state.newVersion, state.addedKeys, state.extraKeys);
    } catch (IOException ex) {
      logger.error("合并配置失败: " + ex.getMessage());
      return UpdateResult.empty();
    }
  }

  private UpdateState merge(YamlConfiguration defaults, YamlConfiguration existing) {
    int defaultVersion = defaults.getInt("config-version", 0);
    int oldVersion = existing.getInt("config-version", 0);
    List<String> added = new ArrayList<>();
    List<String> extras = new ArrayList<>();

    // 先填充模板中的键
    for (String key : defaults.getKeys(true)) {
      if (defaults.isConfigurationSection(key)) {
        continue;
      }
      if (!existing.contains(key)) {
        added.add(key);
      }
    }

    // 保留用户的额外键并记录
    Set<String> defaultKeys = new HashSet<>(defaults.getKeys(true));
    for (String key : existing.getKeys(true)) {
      if (existing.isConfigurationSection(key)) {
        continue;
      }
      if (!defaultKeys.contains(key)) {
        extras.add(key);
      }
    }

    boolean changed = oldVersion != defaultVersion || !added.isEmpty() || !extras.isEmpty();
    return new UpdateState(oldVersion, defaultVersion, added, extras, changed);
  }

  private void ensureParent() throws IOException {
    File parent = configFile.getParentFile();
    if (parent != null && !parent.exists()) {
      Files.createDirectories(parent.toPath());
    }
  }

  private void backupConfig() {
    Path source = configFile.toPath();
    Path target = source.resolveSibling("config.yml.bak");
    try {
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      logger.debug("已备份配置到 " + target.getFileName());
    } catch (IOException ex) {
      logger.warn("备份配置失败: " + ex.getMessage());
    }
  }

  private void logResult(UpdateState state) {
    if (state.oldVersion != state.newVersion) {
      logger.info("config-version " + state.oldVersion + " -> " + state.newVersion);
    }
    if (!state.addedKeys.isEmpty()) {
      logger.info("新增配置键: " + String.join(", ", state.addedKeys));
    }
    if (!state.extraKeys.isEmpty()) {
      logger.warn("配置中存在未识别的键: " + String.join(", ", state.extraKeys));
    }
  }

  private int readVersion(File file) {
    try (InputStream in = new FileInputStream(file)) {
      YamlConfiguration yaml =
          YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
      return yaml.getInt("config-version", 0);
    } catch (IOException ex) {
      return 0;
    }
  }

  public record UpdateResult(
      boolean changed,
      int oldVersion,
      int newVersion,
      List<String> addedKeys,
      List<String> extraKeys) {

    public UpdateResult {
      addedKeys = addedKeys == null ? List.of() : List.copyOf(addedKeys);
      extraKeys = extraKeys == null ? List.of() : List.copyOf(extraKeys);
    }

    static UpdateResult empty() {
      return new UpdateResult(false, 0, 0, List.of(), List.of());
    }
  }

  private record UpdateState(
      int oldVersion,
      int newVersion,
      List<String> addedKeys,
      List<String> extraKeys,
      boolean changed) {}

  private List<String> readTemplateLines() throws IOException {
    try (InputStream in = defaultSupplier.get()) {
      if (in == null) {
        return List.of();
      }
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (content.isEmpty()) {
        return List.of();
      }
      String[] parts = content.split("\\r?\\n", -1);
      List<String> lines = new ArrayList<>(parts.length);
      for (String part : parts) {
        lines.add(part);
      }
      return lines;
    }
  }

  private List<String> mergeWithComments(
      List<String> templateLines,
      List<String> existingLines,
      YamlConfiguration defaults,
      YamlConfiguration existing,
      List<String> addedKeys) {
    if (templateLines.isEmpty()) {
      return existingLines;
    }
    List<String> merged = new ArrayList<>(existingLines);
    int defaultVersion = defaults.getInt("config-version", 0);
    replaceConfigVersion(merged, defaultVersion);

    Map<String, List<String>> templateBlocks = extractTemplateBlocks(templateLines);
    Map<String, List<String>> sectionBlocks = extractSectionBlocks(templateLines);
    Map<String, Integer> sectionEnd = computeSectionEndIndices(merged);

    List<InsertBlock> inserts = new ArrayList<>();
    Set<String> insertedSections = new HashSet<>();
    int order = 0;
    for (String addedKey : addedKeys) {
      if (isUnderInsertedSection(addedKey, insertedSections)) {
        continue;
      }
      List<String> block = templateBlocks.get(addedKey);
      String parent = parentPath(addedKey);
      if (!parent.isEmpty() && !sectionEnd.containsKey(parent)) {
        if (insertedSections.add(parent)) {
          List<String> sectionBlock = sectionBlocks.get(parent);
          if (sectionBlock != null && !sectionBlock.isEmpty()) {
            int insertIndex = resolveSectionInsertIndex(parent, sectionEnd, merged.size());
            inserts.add(new InsertBlock(insertIndex, order++, sectionBlock));
            continue;
          }
        }
      }
      if (block == null || block.isEmpty()) {
        continue;
      }
      Integer insertIndex = sectionEnd.get(parent);
      if (insertIndex == null) {
        logger.warn("配置合并未找到父级段: " + parent + "，已追加到末尾");
        inserts.add(new InsertBlock(merged.size(), order++, block));
      } else {
        inserts.add(new InsertBlock(insertIndex + 1, order++, block));
      }
    }

    inserts.sort(
        (a, b) -> {
          int indexCompare = Integer.compare(b.index(), a.index());
          if (indexCompare != 0) {
            return indexCompare;
          }
          return Integer.compare(b.order(), a.order());
        });
    for (InsertBlock block : inserts) {
      merged.addAll(block.index(), block.lines());
    }

    applyExistingValues(merged, existing);
    return merged;
  }

  /**
   * 用模板版本覆盖 config-version，避免被旧值回写。
   *
   * <p>仅处理单行 key-value 写法。
   */
  private void replaceConfigVersion(List<String> lines, int version) {
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      String trimmed = line.trim();
      if (trimmed.startsWith("config-version:")) {
        String indent = line.substring(0, line.indexOf('c'));
        lines.set(i, indent + "config-version: " + version);
        return;
      }
    }
  }

  /**
   * 抽取模板中的“键 + 前置注释”块，用于把新增键连同注释插回用户配置。
   *
   * <p>仅支持单行 key-value，数组/多行写法会被忽略。
   */
  private Map<String, List<String>> extractTemplateBlocks(List<String> lines) {
    Map<String, List<String>> blocks = new LinkedHashMap<>();
    Deque<SectionFrame> stack = new ArrayDeque<>();
    List<String> pending = new ArrayList<>();
    for (String raw : lines) {
      String line = raw;
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        pending.add(line);
        continue;
      }
      LineInfo info = parseLine(line);
      if (!info.isKey()) {
        pending.clear();
        continue;
      }
      int indent = info.indent();
      while (!stack.isEmpty() && indent <= stack.peek().indent()) {
        stack.pop();
      }
      String path = stackPath(stack, info.key());
      if (info.isSection()) {
        stack.push(new SectionFrame(info.key(), indent));
        pending.clear();
        continue;
      }
      List<String> block = new ArrayList<>(pending);
      block.add(line);
      pending.clear();
      blocks.put(path, block);
    }
    return blocks;
  }

  /**
   * 抽取模板中的 section 块（含前置注释 + section 本体 + 子级内容）。
   *
   * <p>用于当用户配置缺失整个父段时整体插入。
   */
  private Map<String, List<String>> extractSectionBlocks(List<String> lines) {
    Map<String, List<String>> blocks = new LinkedHashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      LineInfo info = parseLine(line);
      if (!info.isKey() || !info.isSection()) {
        continue;
      }
      int startIndex = findSectionBlockStart(lines, i);
      int endIndex = findSectionBlockEnd(lines, i, info.indent());
      String path = resolvePathAtLine(lines, i);
      if (path.isEmpty()) {
        continue;
      }
      blocks.put(path, new ArrayList<>(lines.subList(startIndex, endIndex)));
    }
    return blocks;
  }

  /** 查找 section 上方连续的注释/空行范围，作为插入块的起点。 */
  private int findSectionBlockStart(List<String> lines, int sectionIndex) {
    int start = sectionIndex;
    for (int i = sectionIndex - 1; i >= 0; i--) {
      String trimmed = lines.get(i).trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        start = i;
        continue;
      }
      break;
    }
    return start;
  }

  /** 查找 section 的结束位置，并剔除尾部的“属于下一段的注释”。 */
  private int findSectionBlockEnd(List<String> lines, int sectionIndex, int indent) {
    for (int i = sectionIndex + 1; i < lines.size(); i++) {
      LineInfo info = parseLine(lines.get(i));
      if (!info.isKey()) {
        continue;
      }
      if (info.indent() <= indent) {
        return trimTrailingComments(lines, i, indent);
      }
    }
    return lines.size();
  }

  /** 返回 section 末尾之前应剔除的注释/空行索引。 */
  private int trimTrailingComments(List<String> lines, int endIndex, int sectionIndent) {
    int index = endIndex;
    while (index > 0) {
      String line = lines.get(index - 1);
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        index--;
        continue;
      }
      if (trimmed.startsWith("#") && leadingSpaces(line) <= sectionIndent) {
        index--;
        continue;
      }
      break;
    }
    return index;
  }

  /** 统计行首缩进空格数。 */
  private int leadingSpaces(String line) {
    int indent = 0;
    while (indent < line.length() && line.charAt(indent) == ' ') {
      indent++;
    }
    return indent;
  }

  /**
   * 从模板定位某行所在的完整路径（a.b.c）。
   *
   * <p>仅依赖缩进层级推断，不解析复杂 YAML 结构。
   */
  private String resolvePathAtLine(List<String> lines, int index) {
    Deque<SectionFrame> stack = new ArrayDeque<>();
    for (int i = 0; i <= index; i++) {
      LineInfo info = parseLine(lines.get(i));
      if (!info.isKey()) {
        continue;
      }
      int indent = info.indent();
      while (!stack.isEmpty() && indent <= stack.peek().indent()) {
        stack.pop();
      }
      if (info.isSection()) {
        stack.push(new SectionFrame(info.key(), indent));
      } else if (i == index) {
        stack.push(new SectionFrame(info.key(), indent));
      }
    }
    if (stack.isEmpty()) {
      return "";
    }
    return stackPath(stack, null);
  }

  private Map<String, Integer> computeSectionEndIndices(List<String> lines) {
    Map<String, Integer> ends = new LinkedHashMap<>();
    Deque<SectionFrame> stack = new ArrayDeque<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      LineInfo info = parseLine(line);
      if (!info.isKey()) {
        continue;
      }
      int indent = info.indent();
      while (!stack.isEmpty() && indent <= stack.peek().indent()) {
        stack.pop();
      }
      String path = stackPath(stack, info.key());
      if (info.isSection()) {
        stack.push(new SectionFrame(info.key(), indent));
        ends.put(path, i);
      } else {
        if (!stack.isEmpty()) {
          ends.put(stackPath(stack, null), i);
        }
      }
    }
    return ends;
  }

  private boolean isUnderInsertedSection(String key, Set<String> insertedSections) {
    for (String section : insertedSections) {
      if (key.equals(section) || key.startsWith(section + ".")) {
        return true;
      }
    }
    return false;
  }

  private int resolveSectionInsertIndex(
      String sectionPath, Map<String, Integer> sectionEnd, int fallback) {
    String parent = parentPath(sectionPath);
    Integer parentIndex = sectionEnd.get(parent);
    if (parentIndex != null) {
      return parentIndex + 1;
    }
    return fallback;
  }

  /**
   * 更新用户已有键的值，同时保留模板里的注释与格式。
   *
   * <p>config-version 始终以模板版本为准，不参与回写。
   */
  private void applyExistingValues(List<String> lines, YamlConfiguration existing) {
    Deque<SectionFrame> stack = new ArrayDeque<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      LineInfo info = parseLine(line);
      if (!info.isKey()) {
        continue;
      }
      int indent = info.indent();
      while (!stack.isEmpty() && indent <= stack.peek().indent()) {
        stack.pop();
      }
      String path = stackPath(stack, info.key());
      if (info.isSection()) {
        stack.push(new SectionFrame(info.key(), indent));
        continue;
      }
      if ("config-version".equals(path)) {
        continue;
      }
      if (!existing.contains(path)) {
        continue;
      }
      Object value = existing.get(path);
      if (value == null) {
        continue;
      }
      String formatted = formatYamlValue(value);
      lines.set(i, info.indentText() + info.key() + ": " + formatted + info.inlineComment());
    }
  }

  /** 将简单类型写回 YAML 行；复杂对象降级为字符串。 */
  private String formatYamlValue(Object value) {
    if (value instanceof String str) {
      return quoteIfNeeded(str);
    }
    if (value instanceof Boolean || value instanceof Number) {
      return value.toString();
    }
    if (value instanceof List<?> list) {
      List<String> parts = new ArrayList<>();
      for (Object entry : list) {
        parts.add(formatYamlValue(entry == null ? "" : entry));
      }
      return "[" + String.join(", ", parts) + "]";
    }
    return quoteIfNeeded(String.valueOf(value));
  }

  /** 仅在存在危险字符时加引号，避免破坏用户已有布局。 */
  private String quoteIfNeeded(String raw) {
    if (raw == null) {
      return "\"\"";
    }
    String value = raw;
    boolean needsQuote =
        value.isEmpty()
            || value.startsWith(" ")
            || value.endsWith(" ")
            || value.contains(":")
            || value.contains("#")
            || value.contains("\"")
            || value.contains("\\");
    if (!needsQuote) {
      return value;
    }
    String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
    return "\"" + escaped + "\"";
  }

  /**
   * 解析单行 key 信息（缩进/键名/是否 section）。
   *
   * <p>仅支持单行 key-value 或 key: 形式；列表项与复杂语法将被忽略。
   */
  private LineInfo parseLine(String line) {
    int indent = 0;
    while (indent < line.length() && line.charAt(indent) == ' ') {
      indent++;
    }
    String trimmed = line.trim();
    if (trimmed.startsWith("#") || trimmed.startsWith("-") || !trimmed.contains(":")) {
      return LineInfo.notKey();
    }
    int colonIndex = line.indexOf(':', indent);
    if (colonIndex <= indent) {
      return LineInfo.notKey();
    }
    String key = line.substring(indent, colonIndex).trim();
    if (key.isEmpty()) {
      return LineInfo.notKey();
    }
    String rest = line.substring(colonIndex + 1);
    String comment = "";
    int commentIndex = rest.indexOf(" #");
    if (commentIndex >= 0) {
      comment = rest.substring(commentIndex);
      rest = rest.substring(0, commentIndex);
    }
    boolean section = rest.trim().isEmpty();
    return new LineInfo(true, key, indent, line.substring(0, indent), comment, section);
  }

  private String parentPath(String key) {
    int index = key.lastIndexOf('.');
    if (index <= 0) {
      return "";
    }
    return key.substring(0, index);
  }

  private String stackPath(Deque<SectionFrame> stack, String leaf) {
    if (stack.isEmpty()) {
      return leaf == null ? "" : leaf;
    }
    StringBuilder sb = new StringBuilder();
    for (java.util.Iterator<SectionFrame> it = stack.descendingIterator(); it.hasNext(); ) {
      SectionFrame frame = it.next();
      if (sb.length() > 0) {
        sb.append('.');
      }
      sb.append(frame.key());
    }
    if (leaf != null) {
      if (sb.length() > 0) {
        sb.append('.');
      }
      sb.append(leaf);
    }
    return sb.toString();
  }

  private String joinLines(List<String> lines) {
    if (lines.isEmpty()) {
      return "";
    }
    return String.join("\n", lines) + "\n";
  }

  private record InsertBlock(int index, int order, List<String> lines) {}

  private record SectionFrame(String key, int indent) {}

  private record LineInfo(
      boolean isKey,
      String key,
      int indent,
      String indentText,
      String inlineComment,
      boolean isSection) {
    static LineInfo notKey() {
      return new LineInfo(false, "", 0, "", "", false);
    }
  }
}
