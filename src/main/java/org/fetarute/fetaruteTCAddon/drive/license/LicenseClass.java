package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 操作许可证上的一项资格：叫什么、要先有哪几项、怎么考、考过给哪些权限节点。驾驶、车掌、信号员三个岗位并列（见 {@link #post()}）：驾驶分级（见习、正式……，按配置先后排级数），
 * 车掌与信号员各是一项资格，不占驾驶的级数。
 *
 * @param id 等级标识（配置里的键，小写）
 * @param name 显示名
 * @param description 持证后能做什么（考前说明与发证时告诉玩家）
 * @param enabled 是否开放考试与发证
 * @param requires 考这一级前要先持有的等级
 * @param exam 考试方式
 * @param examStops 路考区间要开过几个停车站（{@link Exam#ROAD_TEST}）；车掌考试要做满几站作业（{@link
 *     Exam#GUARD}）；信号员考试要亲手排满几列车（{@link Exam#SIGNALLER}）
 * @param minPoints 路考、车掌与信号员考试的及格分（0–100）
 * @param allowEmergency 路考中触发紧急制动是否仍可及格
 * @param allowOverrun 路考中停过头、越站是否仍可及格
 * @param allowWrongDoor 路考、车掌考试中开错门是否仍可及格
 * @param trainingRuns 报名路考、车掌或信号员考试前至少要完整做完几次练习（0 为不强制）
 * @param grants 持证时给的权限节点（子节点随之生效）
 * @param drillSeconds 车掌考试中夹人夹物演练的处置时限（秒）；0 为不安排演练（{@link Exam#GUARD}）
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
    List<String> grants,
    int drillSeconds) {

  /** 车掌考试夹人夹物演练的默认处置时限（秒）。 */
  public static final int DEFAULT_DRILL_SECONDS = 15;

  /** 考试方式。 */
  public enum Exam {
    /** 在非调度列车上完整做完新手教程（不跳过练习步骤）。 */
    TUTORIAL,
    /** 路考：驾驶一段调度列车的区间任务，按成绩判定。 */
    ROAD_TEST,
    /** 车掌：在调度列车上值乘，做满几站作业后按车掌成绩判定。 */
    GUARD,
    /** 信号员：在控制台上值守、转人工，亲手排满几列车的进路后按扣分判定。 */
    SIGNALLER;

    /** 按配置写法解析（旧写法 {@code dispatch} 照认）；认不出时为空。 */
    public static Exam parse(String raw) {
      if (raw == null) {
        return null;
      }
      return switch (raw.trim().toLowerCase(Locale.ROOT)) {
        case "tutorial" -> TUTORIAL;
        case "road-test", "dispatch" -> ROAD_TEST;
        case "guard" -> GUARD;
        case "signaller" -> SIGNALLER;
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
    drillSeconds = Math.max(0, drillSeconds);
  }

  /** 岗位：操作许可证上并列的三项。 */
  public enum Post {
    /** 驾驶：分级，见习、正式……按配置先后排级数。 */
    DRIVER,
    /** 车掌。 */
    GUARD,
    /** 信号员。 */
    SIGNALLER
  }

  /** 这一项属于哪个岗位：按考法定，教程与路考是驾驶。 */
  public Post post() {
    return switch (exam) {
      case TUTORIAL, ROAD_TEST -> Post.DRIVER;
      case GUARD -> Post.GUARD;
      case SIGNALLER -> Post.SIGNALLER;
    };
  }

  /** 是驾驶的一级：排进级数（“第 N 级”）；车掌、信号员不占级数。 */
  public boolean graded() {
    return post() == Post.DRIVER;
  }

  /** 演练时限按考法取默认：车掌考试 {@link #DEFAULT_DRILL_SECONDS} 秒，其余不演练。 */
  public LicenseClass(
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
    this(
        id,
        name,
        description,
        enabled,
        requires,
        exam,
        examStops,
        minPoints,
        allowEmergency,
        allowOverrun,
        allowWrongDoor,
        trainingRuns,
        grants,
        exam == Exam.GUARD ? DEFAULT_DRILL_SECONDS : 0);
  }
}
