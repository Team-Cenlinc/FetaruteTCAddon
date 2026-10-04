package org.fetarute.fetaruteTCAddon.drive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;

/**
 * 手动驾驶的权限节点，以及 {@code /fta drive} 各子命令要求的节点。命令注册、帮助、补全与运行时检查都从这里取，不各写一份。本类不依赖服务器对象。
 *
 * <p>节点之间的包含关系写在 {@code plugin.yml} 的 {@code children} 里：{@link #BASE} 含 {@link #FREE} 与 {@link
 * #TUTORIAL}，{@link #DRIVER} 含 {@link #BASE}、{@link #ATO}、{@link #RECORDS} 与 {@link #TOP}，{@link
 * #PLAYER} 含全部玩家功能。单独把某个子节点设为 false 即可收回那一项。
 */
public final class DrivePermissions {

  /** 手动驾驶的基本命令（开始、结束、状态、换向手柄）。 */
  public static final String BASE = "fetarute.drive";

  /** 驾驶非调度列车（自由驾驶）。 */
  public static final String FREE = "fetarute.drive.free";

  /** 领取驾驶任务、驾驶调度列车。 */
  public static final String DRIVER = "fetarute.drive.driver";

  /** 以 ATO 方式领取与驾驶调度列车。 */
  public static final String ATO = "fetarute.drive.ato";

  /** 查看自己的驾驶记录。 */
  public static final String RECORDS = "fetarute.drive.records";

  /** 查看驾驶排行。 */
  public static final String TOP = "fetarute.drive.top";

  /** 新手教程与驾驶提示。 */
  public static final String TUTORIAL = "fetarute.drive.tutorial";

  /** 不领任务直接接管停站中的调度列车（运营人员、调试）。 */
  public static final String DRIVER_ADMIN = "fetarute.drive.driver.admin";

  /** 驾驶管理命令：查看与结束会话、收回任务、交还列车、熔断、诊断、查看他人记录。 */
  public static final String ADMIN = "fetarute.drive.admin";

  /** 打包节点：一次授予全部玩家驾驶功能。 */
  public static final String PLAYER = "fetarute.drive.player";

  /** 打包节点包含的玩家功能节点。 */
  public static final List<String> PLAYER_NODES =
      List.of(BASE, FREE, DRIVER, ATO, RECORDS, TOP, TUTORIAL);

  /**
   * 一个子命令。
   *
   * @param name 子命令名（也是帮助条目语言键的后缀）
   * @param permissions 可使用它的节点，满足任一即可
   */
  public record Subcommand(String name, List<String> permissions) {
    public Subcommand {
      Objects.requireNonNull(name, "name");
      permissions = List.copyOf(permissions);
      if (permissions.isEmpty()) {
        throw new IllegalArgumentException("子命令 " + name + " 没有指定权限节点");
      }
    }

    /** 持有这些节点的玩家能否使用。 */
    public boolean allowedFor(Predicate<String> hasPermission) {
      for (String permission : permissions) {
        if (hasPermission.test(permission)) {
          return true;
        }
      }
      return false;
    }
  }

  /** {@code /fta drive} 的全部子命令，按帮助里的顺序：玩家功能在前，管理命令在后。 */
  public static final List<Subcommand> SUBCOMMANDS =
      List.of(
          new Subcommand("on", List.of(BASE)),
          new Subcommand("off", List.of(BASE)),
          new Subcommand("status", List.of(BASE)),
          new Subcommand("reverser", List.of(BASE)),
          new Subcommand("tutorial", List.of(TUTORIAL)),
          new Subcommand("tasks", List.of(DRIVER)),
          new Subcommand("task", List.of(DRIVER)),
          new Subcommand("mode", List.of(DRIVER)),
          new Subcommand("records", List.of(RECORDS, ADMIN)),
          new Subcommand("top", List.of(TOP)),
          new Subcommand("revoke", List.of(ADMIN)),
          new Subcommand("list", List.of(ADMIN)),
          new Subcommand("stop", List.of(ADMIN)),
          new Subcommand("handback", List.of(ADMIN)),
          new Subcommand("breaker", List.of(ADMIN)),
          new Subcommand("probe", List.of(ADMIN)));

  private DrivePermissions() {}

  /**
   * 子命令要求的节点。
   *
   * @throws IllegalArgumentException 没有这个子命令
   */
  public static List<String> of(String subcommand) {
    for (Subcommand candidate : SUBCOMMANDS) {
      if (candidate.name().equals(subcommand)) {
        return candidate.permissions();
      }
    }
    throw new IllegalArgumentException("未登记的驾驶子命令: " + subcommand);
  }

  /** 任意一个子命令的节点：持有其中之一就能使用 {@code /fta drive} 本身（帮助）。 */
  public static List<String> anyCommand() {
    Set<String> all = new LinkedHashSet<>();
    for (Subcommand subcommand : SUBCOMMANDS) {
      all.addAll(subcommand.permissions());
    }
    return List.copyOf(all);
  }

  /** 帮助里要列出的子命令：只列有权限使用的。 */
  public static List<String> visibleSubcommands(Predicate<String> hasPermission) {
    List<String> names = new ArrayList<>();
    for (Subcommand subcommand : SUBCOMMANDS) {
      if (subcommand.allowedFor(hasPermission)) {
        names.add(subcommand.name());
      }
    }
    return names;
  }

  /**
   * 开始驾驶一列车要求的节点。调度列车另有任务或 {@link #DRIVER_ADMIN} 的检查，由驾驶会话管理器负责。
   *
   * @param dispatchTrain 是否为调度列车
   */
  public static String startPermission(boolean dispatchTrain) {
    return dispatchTrain ? DRIVER : FREE;
  }

  /** 以这种方式领取或驾驶调度列车要求的节点。 */
  public static List<String> modePermissions(DrivingMode mode) {
    return mode == DrivingMode.ATO ? List.of(DRIVER, ATO) : List.of(DRIVER);
  }

  /** 持有这些节点的玩家能否以这种方式领取或驾驶。 */
  public static boolean allowsMode(DrivingMode mode, Predicate<String> hasPermission) {
    for (String permission : modePermissions(mode)) {
      if (!hasPermission.test(permission)) {
        return false;
      }
    }
    return true;
  }
}
