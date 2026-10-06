package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;

/**
 * 驾驶台菜单第三排的任务卡：驾驶调度列车时写车次、任务区间、驾驶方式、下一站与实时评分；驾驶非调度列车时写车名、车种、加减速与最高速度。
 *
 * <p>只描述要显示的各行（语言键与占位符），不依赖服务器对象，便于单测。
 */
public final class TaskCard {

  private static final double KMH_PER_BPS = 3.6;

  /**
   * 卡片上的一行。
   *
   * @param key 语言键
   * @param values 占位符
   */
  public record Line(String key, Map<String, String> values) {
    public Line {
      Objects.requireNonNull(key, "key");
      values = values == null ? Map.of() : Map.copyOf(values);
    }
  }

  /**
   * 卡片。
   *
   * @param titleKey 标题的语言键
   * @param lines 各行
   */
  public record Card(String titleKey, List<Line> lines) {

    /** 末尾加一行本次驾驶的仿真等级。 */
    public Card withLevel(SimulationLevel level) {
      List<Line> all = new ArrayList<>(lines);
      all.add(new Line("drive.menu.card.level." + level.name().toLowerCase(Locale.ROOT), Map.of()));
      return new Card(titleKey, all);
    }

    public Card {
      Objects.requireNonNull(titleKey, "titleKey");
      lines = lines == null ? List.of() : List.copyOf(lines);
    }
  }

  /**
   * 驾驶任务的摘要（没有领任务、由运营人员直接接管时为空）。
   *
   * @param routeCode 线路（交路）代码
   * @param tripCode 车次号
   * @param handoverStation 区间任务的交班站；开到终点站时为空串
   */
  public record TaskSummary(String routeCode, String tripCode, String handoverStation) {}

  private TaskCard() {}

  /**
   * 驾驶调度列车时的卡片。
   *
   * @param trainName 列车名
   * @param link 控制链路
   * @param task 驾驶任务
   * @param liveScore 实时评分
   */
  public static Card dispatch(
      String trainName,
      DriverLink link,
      Optional<TaskSummary> task,
      Optional<ScoreRules.Result> liveScore) {
    List<Line> lines = new ArrayList<>();
    lines.add(new Line("drive.menu.card.train", Map.of("train", trainName)));
    task.ifPresent(
        summary ->
            lines.add(
                summary.handoverStation().isBlank()
                    ? new Line(
                        "drive.menu.card.task-terminal",
                        Map.of("route", summary.routeCode(), "trip", summary.tripCode()))
                    : new Line(
                        "drive.menu.card.task-interval",
                        Map.of(
                            "route",
                            summary.routeCode(),
                            "trip",
                            summary.tripCode(),
                            "station",
                            summary.handoverStation()))));
    lines.add(
        new Line(
            "drive.menu.card.mode." + (link.mode() == DrivingMode.ATO ? "ato" : "manual"),
            Map.of()));
    String next = link.targetLabel().isBlank() ? link.nextStopLabel() : link.targetLabel();
    if (!next.isBlank()) {
      lines.add(new Line("drive.menu.card.next", Map.of("station", next)));
    }
    liveScore.ifPresent(
        score ->
            lines.add(
                new Line(
                    "drive.menu.card.score",
                    Map.of(
                        "points", String.valueOf(score.points()), "grade", score.grade().name()))));
    return new Card("drive.menu.item.task-card-dispatch", lines);
  }

  /** 驾驶非调度列车时的卡片。 */
  public static Card free(String trainName, DriveParams params) {
    return new Card(
        "drive.menu.item.task-card-free",
        List.of(
            new Line("drive.menu.card.train", Map.of("train", trainName)),
            new Line(
                "drive.menu.card.vehicle." + params.mode().name().toLowerCase(Locale.ROOT),
                Map.of()),
            new Line(
                "drive.menu.card.motion",
                Map.of("accel", format(params.accelBps2()), "decel", format(params.decelBps2()))),
            new Line(
                "drive.menu.card.max-speed",
                Map.of("kmh", String.valueOf(Math.round(params.maxSpeedBps() * KMH_PER_BPS))))));
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
