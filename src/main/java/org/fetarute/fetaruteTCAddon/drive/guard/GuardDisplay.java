package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveSidebarRows;

/** 车掌的侧边栏：由这一刻的值乘状态算出各行的语言键与占位符，不依赖服务器对象。车掌的提示都在侧边栏，动作栏留给车厢的乘客信息与报站。 */
public final class GuardDisplay {

  /**
   * 这一刻的值乘状态。
   *
   * @param train 列车名
   * @param driverName 人工驾驶的驾驶员；没有时为空
   * @param ato 车上有 ATO 驾驶员
   * @param stop 进行中的停站；没有时为空
   * @param seated 车掌坐在自己的座位上
   * @param timeoutStops 有超时的停站数
   * @param cabChange 进行中的终点站换端；没有时为空
   * @param nextStation 两站之间时的下一站；停站中或查不到时为空
   */
  public record Snapshot(
      String train,
      Optional<String> driverName,
      boolean ato,
      Optional<StopState> stop,
      boolean seated,
      int timeoutStops,
      Optional<CabChangeState> cabChange,
      Optional<String> nextStation) {

    public Snapshot {
      Objects.requireNonNull(train, "train");
      driverName = driverName == null ? Optional.empty() : driverName;
      stop = stop == null ? Optional.empty() : stop;
      cabChange = cabChange == null ? Optional.empty() : cabChange;
      nextStation = nextStation == null ? Optional.empty() : nextStation;
    }
  }

  /**
   * 进行中的终点站换端。
   *
   * @param car 要换到第几节（1 起）
   * @param remainingTicks 还剩多少 tick；放行前告知、不计时为 -1
   */
  public record CabChangeState(int car, long remainingTicks) {}

  /**
   * 进行中的停站。
   *
   * @param station 站名
   * @param phase 站台推进到的阶段
   * @param required 应开哪一侧（按车掌面朝的方向）
   * @param leftOpen 车掌左手边的门开着
   * @param rightOpen 车掌右手边的门开着
   * @param closing 关门动画在放
   * @param remainingTicks 当前这一步还剩多少 tick；不计时为 -1
   * @param exitOpen 站台上次问时出站是否放行
   * @param confirmed 已确认出发信号
   * @param released 车掌这边已放行（发了发车信号或超时代发）
   */
  public record StopState(
      String station,
      Phase phase,
      DriverDoorSide required,
      boolean leftOpen,
      boolean rightOpen,
      boolean closing,
      long remainingTicks,
      boolean exitOpen,
      boolean confirmed,
      boolean released) {

    public StopState {
      Objects.requireNonNull(station, "station");
      Objects.requireNonNull(phase, "phase");
      required = required == null ? DriverDoorSide.ANY : required;
    }
  }

  /** 一条提示：语言键与占位符。 */
  public record Line(String key, Map<String, String> values) {

    public Line {
      Objects.requireNonNull(key, "key");
      values = values == null ? Map.of() : Map.copyOf(values);
    }
  }

  private static final String LABEL = "drive.guard.sidebar.label.";
  private static final String VALUE = "drive.guard.sidebar.value.";
  private static final String SHEET = "drive.guard.sheet.";

  private GuardDisplay() {}

  /** 换端一行：放行前只告知到第几节，放行后带剩余秒数。 */
  private static DriveSidebarRows.Row cabChangeRow(CabChangeState change) {
    String car = String.valueOf(change.car());
    return change.remainingTicks() < 0L
        ? row("cab-change", "cab-change-announced", Map.of("car", car))
        : row(
            "cab-change",
            "cab-change",
            Map.of("car", car, "seconds", seconds(change.remainingTicks())));
  }

  /** 侧边栏各行。 */
  public static List<DriveSidebarRows.Row> rows(Snapshot snapshot) {
    List<DriveSidebarRows.Row> rows = new ArrayList<>();
    rows.add(row("train", "train", Map.of("train", snapshot.train())));
    if (snapshot.driverName().isPresent()) {
      rows.add(row("crew", "crew-driver", Map.of("driver", snapshot.driverName().get())));
    } else {
      rows.add(row("crew", snapshot.ato() ? "crew-ato" : "crew-auto", Map.of()));
    }
    // 两站之间写下一站；进站起写本站与开哪一侧的门（开门侧要到站台交来停站才知道）。
    if (snapshot.stop().isEmpty()) {
      snapshot
          .nextStation()
          .ifPresent(
              station -> rows.add(row("next-station", "next-station", Map.of("station", station))));
    }
    snapshot
        .stop()
        .ifPresent(
            stop -> {
              rows.add(
                  row(
                      "station",
                      "station-" + stationSideSuffix(stop.required()),
                      Map.of("station", stop.station())));
              rows.add(stepRow(snapshot, stop));
              if (stop.phase() == Phase.WAIT_DEPARTURE && !stop.released()) {
                rows.add(row("exit", stop.exitOpen() ? "exit-open" : "exit-closed", Map.of()));
              }
              rows.add(row("doors", doorsValue(stop), Map.of()));
            });
    snapshot.cabChange().ifPresent(change -> rows.add(cabChangeRow(change)));
    if (snapshot.timeoutStops() > 0) {
      rows.add(
          row("timeouts", "timeouts", Map.of("count", String.valueOf(snapshot.timeoutStops()))));
    }
    return rows;
  }

  private static DriveSidebarRows.Row stepRow(Snapshot snapshot, StopState stop) {
    String seconds = seconds(stop.remainingTicks());
    return switch (stop.phase()) {
      case APPROACH -> row("step", "step-approach", Map.of());
      case OPEN_DOORS -> row(
          "step", "step-open-" + sideSuffix(stop.required()), Map.of("seconds", seconds));
      case DWELL -> row("step", "step-dwell", Map.of());
      case CLOSE_DOORS -> stop.closing() && !stop.leftOpen() && !stop.rightOpen()
          ? row("step", "step-closing", Map.of())
          : row("step", "step-close", Map.of("seconds", seconds));
      case WAIT_DEPARTURE -> stop.released()
          ? row("step", "step-released", Map.of())
          : !snapshot.seated()
              ? row("step", "step-return", Map.of("seconds", seconds))
              : !stop.confirmed() && !stop.exitOpen()
                  ? row("step", "step-wait-exit", Map.of())
                  : !stop.confirmed()
                      ? row("step", "step-confirm", Map.of("seconds", seconds))
                      : row("step", "step-buzzer", Map.of("seconds", seconds));
      case DEPART, ENDED -> row("step", "step-released", Map.of());
    };
  }

  private static String doorsValue(StopState stop) {
    if (stop.leftOpen() && stop.rightOpen()) {
      return "doors-both";
    }
    if (stop.leftOpen()) {
      return "doors-left";
    }
    if (stop.rightOpen()) {
      return "doors-right";
    }
    return stop.closing() ? "doors-closing" : "doors-closed";
  }

  /** 一趟的成绩单：每一项扣分一行（站名与原因）；全无扣分时一行说明；有异常报告时再一行。 */
  public static List<Line> sheet(GuardScore score) {
    List<Line> lines = new ArrayList<>();
    for (GuardScore.Stop stop : score.stops()) {
      Map<String, String> station = Map.of("station", stop.station());
      if (stop.forcedOpen()) {
        lines.add(new Line(SHEET + "forced-open", station));
      }
      if (stop.forcedClose()) {
        lines.add(new Line(SHEET + "forced-close", station));
      }
      if (stop.forcedSignal()) {
        lines.add(new Line(SHEET + "forced-signal", station));
      }
      if (stop.wrongDoor()) {
        lines.add(new Line(SHEET + "wrong-door", station));
      }
      if (stop.closedEarly()) {
        lines.add(new Line(SHEET + "closed-early", station));
      }
      if (stop.closingWatch().filter(passed -> !passed).isPresent()) {
        lines.add(new Line(SHEET + "closing-watch", station));
      }
      if (stop.departureWatch().filter(passed -> !passed).isPresent()) {
        lines.add(new Line(SHEET + "departure-watch", station));
      }
    }
    if (lines.isEmpty()) {
      lines.add(new Line(SHEET + "clean", Map.of("count", String.valueOf(score.stopCount()))));
    }
    if (score.incidents() > 0) {
      lines.add(new Line(SHEET + "incidents", Map.of("count", String.valueOf(score.incidents()))));
    }
    return lines;
  }

  /** 车站一行写开哪一侧的门：语言键的后缀；本站不开门时另写。 */
  static String stationSideSuffix(DriverDoorSide side) {
    return side == DriverDoorSide.NONE ? "no-doors" : sideSuffix(side);
  }

  /** 应开哪一侧：语言键的后缀。 */
  static String sideSuffix(DriverDoorSide side) {
    return switch (side) {
      case LEFT -> "left";
      case RIGHT -> "right";
      case BOTH -> "both";
      case NONE, ANY -> "any";
    };
  }

  /** 剩余秒数（向上取整）；不计时为“—”。 */
  static String seconds(long remainingTicks) {
    return remainingTicks < 0L ? "—" : String.valueOf((remainingTicks + 19L) / 20L);
  }

  private static DriveSidebarRows.Row row(String label, String value, Map<String, String> values) {
    return new DriveSidebarRows.Row(LABEL + label, VALUE + value, values);
  }
}
