package org.fetarute.fetaruteTCAddon.drive.guard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;

/**
 * 车掌值乘的一趟：列车跑的同一个车次里做过的停站作业与走过的距离，车次换了（终点站折返开下一趟）或值乘结束时结算一次。
 *
 * <p>不按时刻表运行的列车没有车次：整段值乘算一趟，只给成绩、不记录、不发奖励（与驾驶员接管不按表运行的列车不记任务一致）。只在服务器主线程读写。
 */
public final class GuardTrip {

  private final TaskKey key;
  private final String routeCode;
  private final Instant startedAt;
  private final List<Worked> stops = new ArrayList<>();
  private double blocks;
  private boolean examined;

  /** 做过作业的一站：作业记录到结算时才折成成绩（出站监视在离站后还要采样一会儿）。 */
  private record Worked(String station, GuardStopWork work) {}

  /**
   * @param key 车次；不按时刻表运行时为 {@code null}
   * @param routeCode 交路代码；查不到时为空串
   */
  public GuardTrip(TaskKey key, String routeCode, Instant startedAt) {
    this.key = key;
    this.routeCode = routeCode == null ? "" : routeCode;
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
  }

  /** 车次；不按时刻表运行时为空。 */
  public Optional<TaskKey> key() {
    return Optional.ofNullable(key);
  }

  public String routeCode() {
    return routeCode;
  }

  public Instant startedAt() {
    return startedAt;
  }

  /** 记一站作业。 */
  public void addStop(String station, GuardStopWork work) {
    stops.add(new Worked(station, Objects.requireNonNull(work, "work")));
  }

  /** 这一趟到此刻的成绩。 */
  public GuardScore score() {
    GuardScore score = new GuardScore();
    for (Worked stop : stops) {
      score.add(GuardScore.Stop.of(stop.station(), stop.work()));
    }
    return score;
  }

  /** 这一趟列车走过的距离（格）。 */
  public double blocks() {
    return blocks;
  }

  /** 列车又走了一段（车掌在车上时）。 */
  public void addBlocks(double moved) {
    if (Double.isFinite(moved) && moved > 0.0) {
      blocks += moved;
    }
  }

  /** 这一趟有车掌考试中做的站：不发奖励。 */
  public boolean examined() {
    return examined;
  }

  public void markExamined() {
    this.examined = true;
  }

  /** 做过停站作业：值得结算。 */
  public boolean hasStops() {
    return !stops.isEmpty();
  }

  /**
   * 列车此刻的车次与这一趟不同：做过停站作业的这一趟该结算了。
   *
   * @param current 列车此刻的车次；不按时刻表运行时为 {@code null}
   */
  public boolean endedBy(TaskKey current) {
    return hasStops() && !Objects.equals(key, current);
  }

  /** 值乘结束的原因对应的终态：中途离开为放弃、被撤下或列车不在为中断、连续超时与漏乘等为未完成。 */
  public static DriverTask.State stateFor(GuardSession.EndReason reason) {
    return switch (reason) {
      case COMMAND, OFFLINE, DEATH, GAME_MODE -> DriverTask.State.ABANDONED;
      case TRAIN_GONE, DISABLED, ADMIN, EXAM -> DriverTask.State.INTERRUPTED;
      case TIMEOUTS, LEFT_BEHIND, CAB_CHANGE -> DriverTask.State.FAILED;
      case HANDOVER -> DriverTask.State.COMPLETED;
    };
  }

  /** 这个终态给不给奖励：开完一趟、中途离开、被撤下的按做过的站给；未完成的不给。 */
  public static boolean rewarded(DriverTask.State state) {
    return state == DriverTask.State.COMPLETED
        || state == DriverTask.State.ABANDONED
        || state == DriverTask.State.INTERRUPTED;
  }
}
