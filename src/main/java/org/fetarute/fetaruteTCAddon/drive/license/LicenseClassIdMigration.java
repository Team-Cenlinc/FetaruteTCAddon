package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 驾驶证等级改名：{@code free}（自由驾驶证）改为 {@code learner}（见习驾驶证），{@code dispatch}（调度驾驶证）改为 {@code
 * driver}（正式驾驶证）。
 *
 * <p>改的是 {@code drive.yml} 里 {@code license.classes} 一段：等级的键、{@code requires}
 * 里的引用，以及仍是旧默认值的名称；用户改过的名称、 其余设置与注释原样保留。必须在补全新键之前做，否则补全会按模板加上 learner、driver 两级，与旧的 free、dispatch
 * 并存。考试方式的旧写法 {@code exam: dispatch} 同时改为 {@code exam: road-test}（这是考试方式，不是等级引用，与等级改名无关）。
 *
 * <p>按行处理、不经 YAML 往返，免得重写整个文件。只认出现在 {@code license:} 下 {@code classes:} 块里的行；目标键已存在时不改那一级。
 */
public final class LicenseClassIdMigration {

  /** 旧等级 ID → 新等级 ID。 */
  public static final Map<String, String> RENAMED = Map.of("free", "learner", "dispatch", "driver");

  /** 旧默认名称 → 新默认名称。 */
  static final Map<String, String> RENAMED_NAMES = Map.of("自由驾驶证", "见习驾驶证", "调度驾驶证", "正式驾驶证");

  /** 键行：缩进、可选的引号、键名、同一引号，冒号后可带值或注释（行内映射也认）。 */
  private static final Pattern KEY =
      Pattern.compile("^(\\s*)([\"']?)([A-Za-z0-9_-]+)\\2:(\\s.*)?$");

  private static final Pattern LIST_ITEM =
      Pattern.compile("^(\\s*-\\s*)([\"']?)([A-Za-z0-9_-]+)([\"']?)(\\s*(#.*)?)$");
  private static final Pattern INLINE_REQUIRES =
      Pattern.compile("^(\\s*requires:\\s*\\[)([^\\]]*)(\\].*)$");
  private static final Pattern NAME =
      Pattern.compile("^(\\s*name:\\s*)([\"']?)([^\"'#]*?)([\"']?)(\\s*(#.*)?)$");
  private static final Pattern LEGACY_EXAM =
      Pattern.compile("^(\\s*exam:\\s*)([\"']?)dispatch\\2(\\s*(#.*)?)$");

  private LicenseClassIdMigration() {}

  /** 数据库里已有新 ID 记录的条数（大于 0 即已迁移过）：参数依次为 {@link #RENAMED} 的各个新 ID。 */
  public static String migratedSql(String table) {
    return "SELECT COUNT(*) FROM "
        + table
        + " WHERE class_id IN ("
        + String.join(", ", java.util.Collections.nCopies(RENAMED.size(), "?"))
        + ")";
  }

  /**
   * 数据库里驾驶证与练习次数两张表的改名语句：参数依次为新 ID、旧 ID、新 ID。同一玩家已有新 ID 的行时跳过（主键冲突）。
   *
   * <p>子查询多包一层派生表：MySQL 不允许在 UPDATE 的子查询里直接读被更新的表；派生表里的 {@code DISTINCT} 让它必须先物化，
   * 优化器不会把它合并回外层（合并后照样报 1093）。
   *
   * @param table 带前缀的表名
   */
  public static String renameSql(String table) {
    return "UPDATE "
        + table
        + " SET class_id = ? WHERE class_id = ? AND player_uuid NOT IN"
        + " (SELECT player_uuid FROM (SELECT DISTINCT player_uuid FROM "
        + table
        + " WHERE class_id = ?) AS taken)";
  }

  /**
   * 迁移文件内容。
   *
   * @param lines 文件的各行
   * @return 迁移后的各行；没有可迁移的内容时与输入相等
   */
  public static List<String> migrate(List<String> lines) {
    List<String> out = new ArrayList<>(lines);
    int license = -1;
    for (int i = 0; i < out.size(); i++) {
      if (out.get(i).matches("^license:\\s*(#.*)?$")) {
        license = i;
        break;
      }
    }
    if (license < 0) {
      return out;
    }
    int classes = -1;
    int classesIndent = -1;
    for (int i = license + 1; i < out.size() && !endsBlock(out.get(i), 0); i++) {
      Matcher key = KEY.matcher(out.get(i));
      if (key.matches() && key.group(3).equals("classes")) {
        classes = i;
        classesIndent = key.group(1).length();
        break;
      }
    }
    if (classes < 0) {
      return out;
    }
    int end = classes + 1;
    while (end < out.size() && !endsBlock(out.get(end), classesIndent)) {
      end++;
    }
    // 等级键所在的缩进：classes 下第一个键的缩进。
    int classIndent = -1;
    List<String> existingKeys = new ArrayList<>();
    for (int i = classes + 1; i < end; i++) {
      Matcher key = KEY.matcher(out.get(i));
      if (!key.matches()) {
        continue;
      }
      if (classIndent < 0) {
        classIndent = key.group(1).length();
      }
      if (key.group(1).length() == classIndent) {
        existingKeys.add(key.group(3));
      }
    }
    if (classIndent < 0) {
      return out;
    }
    // 只改目标键还不存在的等级：已有 learner 时留着的 free 是用户自己的等级，对它的引用也不能跟着改。
    Map<String, String> renames = new java.util.HashMap<>();
    RENAMED.forEach(
        (from, to) -> {
          if (!existingKeys.contains(to)) {
            renames.put(from, to);
          }
        });
    for (int i = classes + 1; i < end; i++) {
      String line = out.get(i);
      Matcher key = KEY.matcher(line);
      if (key.matches() && key.group(1).length() == classIndent) {
        String renamed = renames.get(key.group(3));
        if (renamed != null) {
          out.set(
              i,
              key.group(1)
                  + key.group(2)
                  + renamed
                  + key.group(2)
                  + ":"
                  + (key.group(4) == null ? "" : key.group(4)));
        }
        continue;
      }
      Matcher item = LIST_ITEM.matcher(line);
      if (item.matches() && renames.containsKey(item.group(3))) {
        out.set(
            i,
            item.group(1)
                + item.group(2)
                + renames.get(item.group(3))
                + item.group(4)
                + item.group(5));
        continue;
      }
      Matcher inline = INLINE_REQUIRES.matcher(line);
      if (inline.matches()) {
        out.set(i, inline.group(1) + renameInline(inline.group(2), renames) + inline.group(3));
        continue;
      }
      Matcher exam = LEGACY_EXAM.matcher(line);
      if (exam.matches()) {
        out.set(i, exam.group(1) + exam.group(2) + "road-test" + exam.group(2) + exam.group(3));
        continue;
      }
      Matcher name = NAME.matcher(line);
      if (name.matches() && RENAMED_NAMES.containsKey(name.group(3).trim())) {
        out.set(
            i,
            name.group(1)
                + name.group(2)
                + RENAMED_NAMES.get(name.group(3).trim())
                + name.group(4)
                + name.group(5));
      }
    }
    return out;
  }

  /** {@code [free, "dispatch"]} 里逐项改名，保留引号与空白。 */
  private static String renameInline(String items, Map<String, String> renames) {
    StringBuilder out = new StringBuilder();
    for (String part : items.split(",", -1)) {
      if (!out.isEmpty()) {
        out.append(',');
      }
      Matcher id = Pattern.compile("^(\\s*[\"']?)([A-Za-z0-9_-]+)([\"']?\\s*)$").matcher(part);
      out.append(
          id.matches() && renames.containsKey(id.group(2))
              ? id.group(1) + renames.get(id.group(2)) + id.group(3)
              : part);
    }
    return out.toString();
  }

  /** 这一行是否已离开缩进为 {@code indent} 的块：非空、非注释且缩进不超过它。 */
  private static boolean endsBlock(String line, int indent) {
    String trimmed = line.trim();
    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
      return false;
    }
    int leading = line.length() - line.stripLeading().length();
    return leading <= indent;
  }
}
