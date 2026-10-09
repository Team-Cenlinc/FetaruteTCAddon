package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 一级驾驶证：叫什么、要先有哪几级、怎么考、考过给哪些权限节点。
 *
 * @param id 等级标识（配置里的键，小写）
 * @param name 显示名
 * @param description 持证后能做什么（考前说明与发证时告诉玩家）
 * @param enabled 是否开放考试与发证
 * @param requires 考这一级前要先持有的等级
 * @param exam 考试方式
 * @param examStops 路考区间要开过几个停车站（{@link Exam#ROAD_TEST}）；车掌考试要做满几站作业（{@link Exam#GUARD}）
 * @param minPoints 路考与车掌考试的及格分（0–100）
 * @param allowEmergency 路考中触发紧急制动是否仍可及格
 * @param allowOverrun 路考中停过头、越站是否仍可及格
 * @param allowWrongDoor 路考、车掌考试中开错门是否仍可及格
 * @param trainingRuns 报名路考前至少要完整开完几次练习（0 为不强制）
 * @param grants 持证时给的权限节点（子节点随之生效）
 */
public record LicenseClass(
    String id,
    String name,
    String description,
    boolean enabled,
    List<String> requires,
    Exam exam,
    int examStops,
    int minPoints,
    boolean allowEmergency,
    boolean allowOverrun,
    boolean allowWrongDoor,
    int trainingRuns,
    List<String> grants) {

  /** 考试方式。 */
  public enum Exam {
    /** 在非调度列车上完整做完新手教程（不跳过练习步骤）。 */
    TUTORIAL,
    /** 路考：驾驶一段调度列车的区间任务，按成绩判定。 */
    ROAD_TEST,
    /** 车掌：在调度列车上值乘，做满几站作业后按车掌成绩判定。 */
    GUARD;

    /** 按配置写法解析（旧写法 {@code dispatch} 照认）；认不出时为空。 */
    public static Exam parse(String raw) {
      if (raw == null) {
        return null;
      }
      return switch (raw.trim().toLowerCase(Locale.ROOT)) {
        case "tutorial" -> TUTORIAL;
        case "road-test", "dispatch" -> ROAD_TEST;
        case "guard" -> GUARD;
        default -> null;
      };
    }
  }

  public LicenseClass {
    Objects.requireNonNull(id, "id");
    id = id.trim().toLowerCase(Locale.ROOT);
    name = name == null || name.isBlank() ? id : name;
    description = description == null ? "" : description;
    requires = requires == null ? List.of() : List.copyOf(requires);
    Objects.requireNonNull(exam, "exam");
    examStops = Math.max(1, examStops);
    minPoints = Math.max(0, Math.min(100, minPoints));
    trainingRuns = Math.max(0, trainingRuns);
    grants = grants == null ? List.of() : List.copyOf(grants);
  }
}
