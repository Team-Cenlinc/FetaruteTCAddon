package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;

/**
 * 时刻表中的一个停靠点，时间以“相对本趟车发车时刻的秒偏移”表达。
 *
 * <p>用偏移而不是绝对时间是刻意的：同一份 stop profile 会被本时刻表下的每一趟车复用，只有发车时刻不同。这样“区间运行时分”在时刻表里只存一份，改一次全表生效；若某条 route
 * 需要两套运行时分（例如高峰/平峰），做法是建两份时刻表，而不是让每趟车各存一套录制噪声。
 *
 * <p>偏移允许超过一天：跨零点的班次由偏移自然表达，不需要额外的日期字段。
 *
 * <p>是否停车看 {@code passType}，不看停站时长：停站 0 秒的 STOP 站照样停车，PASS 的到发时刻本来就相同。
 *
 * @param stopSequence 停靠序号：交路 {@code waypoints()} 的 0 起下标，与运行时进度索引同一口径（不是 {@code
 *     RouteStop.sequence}——那是库里的排序键，可以不连续）
 * @param stationCode 站点 code，只有车站本体节点才有；区间点、咽喉、车库为空
 * @param nodeId 调度图节点 ID 或 DYNAMIC 占位符
 * @param arrivalOffsetSeconds 相对发车时刻的到达偏移（秒），首站为 0
 * @param departureOffsetSeconds 相对发车时刻的发车偏移（秒），不早于到达偏移
 * @param passType 构建时交路上该点的停车方式
 */
public record TimetableStop(
    int stopSequence,
    Optional<String> stationCode,
    Optional<String> nodeId,
    int arrivalOffsetSeconds,
    int departureOffsetSeconds,
    RouteStopPassType passType) {

  public TimetableStop {
    Objects.requireNonNull(passType, "passType");
    if (stopSequence < 0) {
      throw new IllegalArgumentException("stopSequence 不能为负");
    }
    if (arrivalOffsetSeconds < 0) {
      throw new IllegalArgumentException("arrivalOffsetSeconds 不能为负");
    }
    if (departureOffsetSeconds < arrivalOffsetSeconds) {
      throw new IllegalArgumentException(
          "departureOffsetSeconds 不能早于 arrivalOffsetSeconds: "
              + departureOffsetSeconds
              + " < "
              + arrivalOffsetSeconds);
    }
    stationCode = normalize(stationCode);
    nodeId = normalize(nodeId);
  }

  /** 列车是否在此停车（STOP 与 TERMINATE）。 */
  public boolean stops() {
    return passType != RouteStopPassType.PASS;
  }

  /** 停站时长，由到达/发车偏移之差派生，不单独存储以免二者不一致。 */
  public Duration dwell() {
    return Duration.ofSeconds((long) departureOffsetSeconds - arrivalOffsetSeconds);
  }

  private static Optional<String> normalize(Optional<String> value) {
    if (value == null) {
      return Optional.empty();
    }
    return value.map(String::trim).filter(text -> !text.isBlank());
  }
}
