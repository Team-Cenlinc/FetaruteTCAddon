package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 任务板上一趟车次的行程概要：开到哪里、沿途停几站、按表要开多久。本类不依赖服务器对象，便于单测。
 *
 * <p>只认停车的车站（有站码、不是通过）；终点站是停靠表里最后一个停车的车站。运行时长按时刻表：终点站计划到达减去本站计划发车。
 */
public final class TaskTripSummary {

  /**
   * 停靠表里的一个点。
   *
   * @param sequence 停靠序号
   * @param stationCode 站码；区间点、咽喉、车库为空
   * @param nodeId 调度图节点
   * @param stops 是否停车
   * @param arrival 计划到达
   * @param departure 计划发车
   */
  public record Stop(
      int sequence,
      Optional<String> stationCode,
      Optional<String> nodeId,
      boolean stops,
      Optional<Instant> arrival,
      Optional<Instant> departure) {
    public Stop {
      stationCode = stationCode == null ? Optional.empty() : stationCode;
      nodeId = nodeId == null ? Optional.empty() : nodeId;
      arrival = arrival == null ? Optional.empty() : arrival;
      departure = departure == null ? Optional.empty() : departure;
    }

    boolean isStation() {
      return stops && stationCode.filter(code -> !code.isBlank()).isPresent();
    }
  }

  /**
   * 行程概要。
   *
   * @param terminusCode 终点站站码
   * @param terminusNodeId 终点站的调度图节点（用来确定运营商）
   * @param stopCount 本站之后停车的车站数，含终点站
   * @param runSeconds 按表的运行时长（秒）；缺少时刻时为 -1
   */
  public record Summary(
      String terminusCode, Optional<String> terminusNodeId, int stopCount, long runSeconds) {
    public Summary {
      Objects.requireNonNull(terminusCode, "terminusCode");
      terminusNodeId = terminusNodeId == null ? Optional.empty() : terminusNodeId;
    }
  }

  /**
   * 运行时长的显示：语言键与占位符。
   *
   * @param key 语言键（{@code drive.task.board.run-time.*}）
   * @param values 占位符
   */
  public record RunTimeText(String key, Map<String, String> values) {
    public RunTimeText {
      values = Map.copyOf(values);
    }

    /** 把语言文件里的模板（纯文本，占位符写作 {@code <name>}）填上数值。 */
    public String render(String template) {
      String result = template;
      for (Map.Entry<String, String> entry : values.entrySet()) {
        result = result.replace("<" + entry.getKey() + ">", entry.getValue());
      }
      return result;
    }
  }

  private TaskTripSummary() {}

  /**
   * 从本站起的行程概要。
   *
   * @param stops 整趟车次的停靠表
   * @param boardSequence 本站的停靠序号
   * @return 停靠表里找不到终点站，或本站就是终点站时为空
   */
  public static Optional<Summary> of(List<Stop> stops, int boardSequence) {
    Objects.requireNonNull(stops, "stops");
    Stop board = null;
    Stop terminus = null;
    int stopCount = 0;
    for (Stop stop : stops) {
      if (stop.sequence() == boardSequence) {
        board = stop;
      }
      if (!stop.isStation()) {
        continue;
      }
      if (terminus == null || stop.sequence() > terminus.sequence()) {
        terminus = stop;
      }
      if (stop.sequence() > boardSequence) {
        stopCount++;
      }
    }
    if (terminus == null || terminus.sequence() <= boardSequence) {
      return Optional.empty();
    }
    long runSeconds = -1L;
    Optional<Instant> departure = board == null ? Optional.empty() : board.departure();
    Optional<Instant> arrival = terminus.arrival().or(terminus::departure);
    if (departure.isPresent() && arrival.isPresent()) {
      long seconds = Duration.between(departure.get(), arrival.get()).getSeconds();
      runSeconds = seconds >= 0L ? seconds : -1L;
    }
    return Optional.of(
        new Summary(
            terminus.stationCode().orElseThrow(), terminus.nodeId(), stopCount, runSeconds));
  }

  /**
   * 运行时长怎么显示：不到一分钟按秒；不到一小时按分钟（四舍五入）；更长按小时加分钟。
   *
   * @param seconds 运行时长（秒），不得为负
   */
  public static RunTimeText runTime(long seconds) {
    if (seconds < 0L) {
      throw new IllegalArgumentException("运行时长不得为负: " + seconds);
    }
    if (seconds < 60L) {
      return new RunTimeText(
          "drive.task.board.run-time.seconds", Map.of("seconds", String.valueOf(seconds)));
    }
    long minutes = (seconds + 30L) / 60L;
    if (minutes < 60L) {
      return new RunTimeText(
          "drive.task.board.run-time.minutes", Map.of("minutes", String.valueOf(minutes)));
    }
    return new RunTimeText(
        "drive.task.board.run-time.hours",
        Map.of("hours", String.valueOf(minutes / 60L), "minutes", String.valueOf(minutes % 60L)));
  }
}
