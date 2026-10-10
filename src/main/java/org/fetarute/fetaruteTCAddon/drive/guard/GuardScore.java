package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/**
 * 车掌一次值乘的成绩：满分 100 按站扣分，再折成与驾驶员相同的 S/A/B/C/D 评级。本类不依赖服务器对象。
 *
 * <ul>
 *   <li>时限：开门超时由站台代开、关门超时由站台代关、发车铃超时代发，各 5 分；
 *   <li>车门：开错一侧 5 分；停站时间未到就关门 2 分；
 *   <li>监视：关门时未在站台注视车门、出站时未朝站台监视，各 2 分（没采到样的不判）。
 * </ul>
 *
 * <p>异常情况报告只记次数，不扣分。连续超时被撤下的值乘最高 D。
 */
public final class GuardScore {

  /**
   * 一站的结果。
   *
   * @param station 站名
   * @param forcedOpen 开门超时，站台代开
   * @param forcedClose 关门超时，站台代关
   * @param forcedSignal 发车铃超时，代发发车信号
   * @param wrongDoor 开过不该开的一侧
   * @param closedEarly 停站时间未到就关了门
   * @param closingWatch 关门监视合格与否；没有采样时为空
   * @param departureWatch 出站监视合格与否；没有采样时为空
   * @param incidents 异常情况报告次数
   */
  public record Stop(
      String station,
      boolean forcedOpen,
      boolean forcedClose,
      boolean forcedSignal,
      boolean wrongDoor,
      boolean closedEarly,
      Optional<Boolean> closingWatch,
      Optional<Boolean> departureWatch,
      int incidents) {

    public Stop {
      station = station == null ? "" : station;
      closingWatch = closingWatch == null ? Optional.empty() : closingWatch;
      departureWatch = departureWatch == null ? Optional.empty() : departureWatch;
      incidents = Math.max(0, incidents);
    }

    /** 这一站扣多少分。 */
    public int penalty() {
      int penalty = 0;
      if (forcedOpen) {
        penalty += TIMEOUT_PENALTY;
      }
      if (forcedClose) {
        penalty += TIMEOUT_PENALTY;
      }
      if (forcedSignal) {
        penalty += TIMEOUT_PENALTY;
      }
      if (wrongDoor) {
        penalty += WRONG_DOOR_PENALTY;
      }
      if (closedEarly) {
        penalty += EARLY_CLOSE_PENALTY;
      }
      if (closingWatch.filter(passed -> !passed).isPresent()) {
        penalty += WATCH_PENALTY;
      }
      if (departureWatch.filter(passed -> !passed).isPresent()) {
        penalty += WATCH_PENALTY;
      }
      return penalty;
    }

    /** 由这一站的作业记录得出。 */
    public static Stop of(String station, GuardStopWork work) {
      return new Stop(
          station,
          work.forcedOpen(),
          work.forcedClose(),
          work.forcedSignal(),
          work.wrongDoor(),
          work.closedEarly(),
          work.closingWatchPassed(),
          work.departureWatchPassed(),
          work.incidents());
    }
  }

  static final int TIMEOUT_PENALTY = 5;
  static final int WRONG_DOOR_PENALTY = 5;
  static final int EARLY_CLOSE_PENALTY = 2;
  static final int WATCH_PENALTY = 2;

  private final List<Stop> stops = new ArrayList<>();

  /** 记一站。 */
  public void add(Stop stop) {
    stops.add(Objects.requireNonNull(stop, "stop"));
  }

  public List<Stop> stops() {
    return List.copyOf(stops);
  }

  /** 已记的站数。 */
  public int stopCount() {
    return stops.size();
  }

  /** 异常情况报告的总次数。 */
  public int incidents() {
    int total = 0;
    for (Stop stop : stops) {
      total += stop.incidents();
    }
    return total;
  }

  /** 有超时的站数。 */
  public int timeoutStops() {
    int count = 0;
    for (Stop stop : stops) {
      if (stop.forcedOpen() || stop.forcedClose() || stop.forcedSignal()) {
        count++;
      }
    }
    return count;
  }

  /**
   * 计分。
   *
   * @param completed 照常评级；连续超时被撤下时为 {@code false}，最高 D
   */
  public ScoreRules.Result evaluate(boolean completed) {
    int penalty = 0;
    for (Stop stop : stops) {
      penalty += stop.penalty();
    }
    int points = Math.max(0, 100 - penalty);
    return new ScoreRules.Result(points, ScoreRules.gradeOf(points, completed));
  }
}
