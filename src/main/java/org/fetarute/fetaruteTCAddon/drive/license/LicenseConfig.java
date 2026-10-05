package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.fetarute.fetaruteTCAddon.drive.DrivePermissions;

/**
 * 驾驶证与考试（{@code drive.yml} 的 {@code license} 段）。
 *
 * @param enabled 是否启用驾驶证：关闭时不能考试，已发的证也不再给权限
 * @param examWindowMinutes 报名后多久内要考完（教程考试；路考以任务本身为准）
 * @param retryCooldownMinutes 路考不及格后多久才能再考
 * @param reissueCooldownMinutes 两次补发之间至少隔多久
 * @param classes 各级驾驶证，按声明顺序
 */
public record LicenseConfig(
    boolean enabled,
    int examWindowMinutes,
    int retryCooldownMinutes,
    int reissueCooldownMinutes,
    List<LicenseClass> classes) {

  public LicenseConfig {
    examWindowMinutes = Math.max(1, examWindowMinutes);
    retryCooldownMinutes = Math.max(0, retryCooldownMinutes);
    reissueCooldownMinutes = Math.max(0, reissueCooldownMinutes);
    classes = classes == null ? List.of() : List.copyOf(classes);
  }

  /** 默认两级：自由驾驶证考新手教程；调度驾驶证要先有自由驾驶证，再路考。 */
  public static LicenseConfig defaults() {
    return new LicenseConfig(
        true,
        30,
        10,
        10,
        List.of(
            new LicenseClass(
                "free",
                "自由驾驶证",
                "驾驶非调度列车，使用新手教程与驾驶提示",
                true,
                List.of(),
                LicenseClass.Exam.TUTORIAL,
                1,
                0,
                true,
                true,
                true,
                List.of(DrivePermissions.BASE)),
            new LicenseClass(
                "dispatch",
                "调度驾驶证",
                "领取驾驶任务、驾驶调度列车（人工与 ATO）、自选仿真等级、查看驾驶记录与排行",
                true,
                List.of("free"),
                LicenseClass.Exam.DISPATCH,
                3,
                70,
                false,
                false,
                false,
                List.of(DrivePermissions.DRIVER))));
  }

  /** 第几级：按配置里的先后，从 1 起；没有这一级时为 0。 */
  public int levelOf(String id) {
    Optional<LicenseClass> found = find(id);
    return found.map(c -> classes.indexOf(c) + 1).orElse(0);
  }

  /** 按标识找一级（不分大小写）。 */
  public Optional<LicenseClass> find(String id) {
    if (id == null) {
      return Optional.empty();
    }
    String key = id.trim().toLowerCase(Locale.ROOT);
    return classes.stream().filter(c -> c.id().equals(key)).findFirst();
  }

  /**
   * 从 {@code license} 段解析；段缺失时用默认值，非法项回退默认值并提示。
   *
   * @param section {@code drive.yml} 的 {@code license} 段，可为空
   */
  public static LicenseConfig from(ConfigurationSection section, Consumer<String> warn) {
    LicenseConfig d = defaults();
    if (section == null) {
      return d;
    }
    Consumer<String> sink = warn != null ? warn : message -> {};
    List<LicenseClass> classes = new ArrayList<>();
    ConfigurationSection classSection = section.getConfigurationSection("classes");
    if (classSection == null) {
      classes.addAll(d.classes());
    } else {
      for (String key : classSection.getKeys(false)) {
        ConfigurationSection entry = classSection.getConfigurationSection(key);
        if (entry == null) {
          continue;
        }
        LicenseClass.Exam exam = LicenseClass.Exam.parse(entry.getString("exam", "dispatch"));
        if (exam == null) {
          sink.accept(
              "drive.yml 的 license.classes." + key + ".exam 只能是 tutorial 或 dispatch，已跳过这一级");
          continue;
        }
        classes.add(
            new LicenseClass(
                key,
                entry.getString("name", key),
                entry.getString("description", ""),
                entry.getBoolean("enabled", true),
                entry.getStringList("requires"),
                exam,
                entry.getInt("exam-stops", 3),
                entry.getInt("min-points", 70),
                entry.getBoolean("allow-emergency", false),
                entry.getBoolean("allow-overrun", false),
                entry.getBoolean("allow-wrong-door", false),
                entry.getStringList("grants")));
      }
    }
    Set<String> ids = new LinkedHashSet<>();
    for (LicenseClass c : classes) {
      ids.add(c.id());
    }
    for (LicenseClass c : classes) {
      for (String required : c.requires()) {
        if (!ids.contains(required.trim().toLowerCase(Locale.ROOT))) {
          sink.accept(
              "drive.yml 的 license.classes." + c.id() + ".requires 里的 " + required + " 不存在");
        }
      }
    }
    return new LicenseConfig(
        section.getBoolean("enabled", d.enabled()),
        section.getInt("exam-window-minutes", d.examWindowMinutes()),
        section.getInt("retry-cooldown-minutes", d.retryCooldownMinutes()),
        section.getInt("reissue-cooldown-minutes", d.reissueCooldownMinutes()),
        classes);
  }
}
