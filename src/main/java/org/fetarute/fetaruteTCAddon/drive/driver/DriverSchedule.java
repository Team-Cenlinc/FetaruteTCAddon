package org.fetarute.fetaruteTCAddon.drive.driver;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * 驾驶台上的表定时刻：区间里是下一站的表定到站，停站时是本站的表定发车。
 *
 * <p>本类不依赖服务器对象，便于单测。
 *
 * @param departure 是否为表定发车（停站中）；否则为下一站的表定到站
 * @param planned 表定时刻
 * @param deviationSeconds 偏差秒数，正数为晚点：到站取预计到达相对表定，发车取此刻相对表定；不明时为空
 */
public record DriverSchedule(boolean departure, Instant planned, OptionalLong deviationSeconds) {

  /** 偏差在这么多秒以内算正点。 */
  public static final long ON_TIME_SECONDS = 30L;

  /** 按列车名与停靠序号查表定时刻。 */
  @FunctionalInterface
  public interface Lookup {
    Optional<Instant> planned(String trainName, int stopIndex);
  }

  public DriverSchedule {
    Objects.requireNonNull(planned, "planned");
    deviationSeconds = deviationSeconds == null ? OptionalLong.empty() : deviationSeconds;
  }

  /** 与表定的关系，对应语言键的末段。 */
  public enum State {
    /** 没有偏差可比，或停站中还没到表定发车时刻。 */
    PLAIN("plain"),
    ON_TIME("on-time"),
    LATE("late"),
    EARLY("early");

    private final String key;

    State(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }
  }

  /**
   * 选出该显示的表定时刻。
   *
   * @param trainName 列车名
   * @param atStation 列车是否停在本站（交路进度已到本站）
   * @param routeIndex 交路进度下标（停站时即本站的停靠序号）
   * @param nextStopIndex 前方下一个停靠站的序号；没有时为空
   * @param arrivalDeviation 到达下一站的预计偏差秒数；不明时为空
   * @param arrivals 表定到站
   * @param departures 表定发车
   * @param now 此刻
   */
  public static Optional<DriverSchedule> of(
      String trainName,
      boolean atStation,
      int routeIndex,
      OptionalInt nextStopIndex,
      OptionalLong arrivalDeviation,
      Lookup arrivals,
      Lookup departures,
      Instant now) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    if (atStation) {
      return departures
          .planned(trainName, routeIndex)
          .map(
              at ->
                  new DriverSchedule(
                      true, at, OptionalLong.of(Duration.between(at, now).toSeconds())));
    }
    if (nextStopIndex == null || nextStopIndex.isEmpty()) {
      return Optional.empty();
    }
    return arrivals
        .planned(trainName, nextStopIndex.getAsInt())
        .map(at -> new DriverSchedule(false, at, arrivalDeviation));
  }

  /** 与表定的关系。停站中只在过了表定发车时刻后才算晚点，之前不提示（停站倒计时已经在显示）。 */
  public State state() {
    if (deviationSeconds.isEmpty()) {
      return State.PLAIN;
    }
    long deviation = deviationSeconds.getAsLong();
    if (deviation > ON_TIME_SECONDS) {
      return State.LATE;
    }
    if (departure) {
      return State.PLAIN;
    }
    return deviation < -ON_TIME_SECONDS ? State.EARLY : State.ON_TIME;
  }

  /** 偏差的绝对值，按“分:秒”写；不明时为空串。 */
  public String deviationText() {
    if (deviationSeconds.isEmpty()) {
      return "";
    }
    long seconds = Math.abs(deviationSeconds.getAsLong());
    return (seconds / 60L) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60L);
  }
}
