package org.fetarute.fetaruteTCAddon.stationmaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveSidebarRows;

/** 站长的快捷栏样子与侧边栏内容。本类不依赖服务器对象。 */
final class StationMasterDisplay {

  /** 来车倒数的 Boss 栏按多少秒算满。 */
  private static final int INBOUND_BAR_SECONDS = 180;

  private static final String BAR = "stationmaster.bar.";
  private static final String LABEL = "stationmaster.sidebar.label.";
  private static final String VALUE = "stationmaster.sidebar.value.";

  private StationMasterDisplay() {}

  /**
   * 快捷栏按钮此刻的样子。
   *
   * @param waiting 身旁有车在等发车信号
   * @param holding 正扣着车
   * @param emergency 正用站台紧急停车扣着车
   * @param offConfirm 按过一次下岗键、等再按一次
   */
  record View(boolean waiting, boolean holding, boolean emergency, boolean offConfirm) {
    static final View IDLE = new View(false, false, false, false);
  }

  /**
   * 站长身边要画的标记。
   *
   * @param trainName 当前这一列
   * @param stand 站长该站的地方
   * @param flashing 这列车要不要闪：正进站、在等发车信号时闪给站长看是哪一列
   */
  record Marks(String trainName, StationMasterWorld.StandPoint stand, boolean flashing) {}

  /** 当前这一列此刻在做什么。 */
  enum State {
    INBOUND,
    APPROACHING,
    DWELLING,
    CLEARING,
    READY,
    WAITING,
    SIGNALLED,
    HELD,
    EMERGENCY,
    DEPARTING;

    String key() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * 侧边栏上的一列车。
   *
   * @param train 车次
   * @param track 股道号
   * @param state 在做什么
   * @param seconds 来车还有几秒到，等发车信号、扣车、清客的剩余秒数；不适用时为 -1
   */
  record TrainRow(String train, String track, State state, long seconds) {}

  /**
   * 侧边栏的内容。
   *
   * @param station 站名
   * @param tracks 所辖股道（已拼好）
   * @param current 当前这一列；歇着时为空
   * @param doorsClosed 这一列在站台时车门是否全关；还没到站时为空
   * @param crew 这一列的值乘情况；歇着时为空
   * @param restSeconds 手上没车时还要歇几秒才排下一列；已歇够时为 0
   * @param restTotalSeconds 办完一列要歇的总秒数
   * @param signals 亲手发的发车信号数
   * @param autoSignals 代发数
   * @param watchMissed 监视不合格的次数
   * @param points 此刻的评分
   * @param grade 此刻的评级
   */
  record Snapshot(
      String station,
      String tracks,
      Optional<TrainRow> current,
      Optional<Boolean> doorsClosed,
      Optional<StationMasterWorld.CrewOnTrain> crew,
      long restSeconds,
      int restTotalSeconds,
      int signals,
      int autoSignals,
      int watchMissed,
      int points,
      String grade) {}

  /**
   * Boss 栏：当前这一列到了哪一步。
   *
   * @param key 语言键
   * @param values 占位符
   * @param progress 进度（0–1）
   * @param urgent 要站长马上动手
   */
  record Bar(String key, Map<String, String> values, float progress, boolean urgent) {}

  /** Boss 栏此刻的内容。 */
  static Bar bar(Snapshot snapshot, int signalTimeoutSeconds, int maxHoldSeconds) {
    if (snapshot.current().isEmpty()) {
      return snapshot.restSeconds() > 0
          ? new Bar(
              BAR + "rest",
              Map.of("seconds", String.valueOf(snapshot.restSeconds())),
              fraction(snapshot.restSeconds(), snapshot.restTotalSeconds()),
              false)
          : new Bar(BAR + "idle", Map.of(), 1.0f, false);
    }
    TrainRow row = snapshot.current().get();
    float progress =
        switch (row.state()) {
          case INBOUND -> fraction(row.seconds(), INBOUND_BAR_SECONDS);
          case WAITING -> fraction(row.seconds(), signalTimeoutSeconds);
          case HELD -> fraction(row.seconds(), maxHoldSeconds);
          default -> 1.0f;
        };
    return new Bar(
        BAR + row.state().key(),
        Map.of(
            "train", row.train(),
            "track", row.track(),
            "seconds", String.valueOf(Math.max(0L, row.seconds()))),
        progress,
        row.state() == State.READY
            || row.state() == State.WAITING
            || row.state() == State.EMERGENCY);
  }

  /** 把股道号写短：连着三条以上的写成一段（1-4），侧边栏才放得下多股道的车站。 */
  static String compactTracks(List<Integer> numbers, String separator) {
    List<String> parts = new ArrayList<>();
    int from = 0;
    while (from < numbers.size()) {
      int to = from;
      while (to + 1 < numbers.size() && numbers.get(to + 1) == numbers.get(to) + 1) {
        to++;
      }
      if (to - from >= 2) {
        parts.add(numbers.get(from) + "-" + numbers.get(to));
      } else {
        for (int i = from; i <= to; i++) {
          parts.add(String.valueOf(numbers.get(i)));
        }
      }
      from = to + 1;
    }
    return String.join(separator, parts);
  }

  private static float fraction(long seconds, int total) {
    return total <= 0 ? 1.0f : Math.max(0.0f, Math.min(1.0f, seconds / (float) total));
  }

  /** 侧边栏各行：一行只写一件事、值都很短，与驾驶员的侧边栏同一个写法，长句子会把侧边栏撑得很宽。 */
  static List<DriveSidebarRows.Row> rows(Snapshot snapshot) {
    List<DriveSidebarRows.Row> rows = new ArrayList<>();
    rows.add(row("tracks", "tracks", Map.of("tracks", snapshot.tracks())));
    snapshot
        .current()
        .ifPresentOrElse(
            train -> {
              rows.add(row("train", "train", Map.of("train", train.train())));
              rows.add(row("stop", "stop", Map.of("track", train.track())));
              rows.add(
                  row(
                      "work",
                      train.state().key() + (train.seconds() >= 0 ? "-timed" : ""),
                      Map.of("seconds", String.valueOf(Math.max(0L, train.seconds())))));
            },
            () ->
                rows.add(
                    snapshot.restSeconds() > 0
                        ? row(
                            "work",
                            "rest-timed",
                            Map.of("seconds", String.valueOf(snapshot.restSeconds())))
                        : row("work", "no-train", Map.of())));
    snapshot
        .doorsClosed()
        .ifPresent(
            closed -> rows.add(row("doors", closed ? "doors-closed" : "doors-open", Map.of())));
    snapshot
        .crew()
        .ifPresent(
            crew ->
                rows.add(row("crew", "crew-" + crew.name().toLowerCase(Locale.ROOT), Map.of())));
    rows.add(
        new DriveSidebarRows.Row(
            "drive.sidebar.label.score",
            "drive.sidebar.value.score",
            Map.of("points", String.valueOf(snapshot.points()), "grade", snapshot.grade())));
    return rows;
  }

  private static DriveSidebarRows.Row row(String label, String value, Map<String, String> values) {
    return new DriveSidebarRows.Row(LABEL + label, VALUE + value, values);
  }
}
