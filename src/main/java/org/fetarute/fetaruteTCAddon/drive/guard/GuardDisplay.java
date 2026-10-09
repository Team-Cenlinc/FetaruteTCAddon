package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveSidebarRows;

/** 车掌的侧边栏与动作栏提示：由这一刻的值乘状态算出语言键与占位符，不依赖服务器对象。 */
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
   */
  public record Snapshot(
      String train,
      Optional<String> driverName,
      boolean ato,
      Optional<StopState> stop,
      boolean seated,
      int timeoutStops) {

    public Snapshot {
      Objects.requireNonNull(train, "train");
      driverName = driverName == null ? Optional.empty() : driverName;
      stop = stop == null ? Optional.empty() : stop;
    }
  }

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

  private static final String PROMPT = "drive.guard.prompt.";
  private static final String LABEL = "drive.guard.sidebar.label.";
  private static final String VALUE = "drive.guard.sidebar.value.";

  private GuardDisplay() {}

  /** 动作栏上要车掌做的事。 */
  public static Line prompt(Snapshot snapshot) {
    if (snapshot.stop().isEmpty()) {
      return new Line(PROMPT + "running", Map.of());
    }
    StopState stop = snapshot.stop().get();
    String seconds = seconds(stop.remainingTicks());
    return switch (stop.phase()) {
      case APPROACH -> new Line(PROMPT + "approach", Map.of("station", stop.station()));
      case OPEN_DOORS -> new Line(
          PROMPT + "open-doors-" + sideSuffix(stop.required()), Map.of("seconds", seconds));
      case DWELL -> new Line(PROMPT + "dwell", Map.of());
      case CLOSE_DOORS -> stop.closing() && !stop.leftOpen() && !stop.rightOpen()
          ? new Line(PROMPT + "closing", Map.of())
          : new Line(PROMPT + "close-doors", Map.of("seconds", seconds));
      case WAIT_DEPARTURE -> departurePrompt(snapshot, stop, seconds);
      case DEPART, ENDED -> new Line(PROMPT + "released", Map.of());
    };
  }

  private static Line departurePrompt(Snapshot snapshot, StopState stop, String seconds) {
    if (stop.released()) {
      return new Line(PROMPT + "released", Map.of());
    }
    if (!snapshot.seated()) {
      return new Line(PROMPT + "return-seat", Map.of("seconds", seconds));
    }
    if (!stop.confirmed() && !stop.exitOpen()) {
      return new Line(PROMPT + "exit-closed", Map.of());
    }
    if (!stop.confirmed()) {
      return new Line(PROMPT + "confirm", Map.of("seconds", seconds));
    }
    return new Line(PROMPT + "buzzer", Map.of("seconds", seconds));
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
    snapshot
        .stop()
        .ifPresent(
            stop -> {
              rows.add(row("station", "station", Map.of("station", stop.station())));
              rows.add(stepRow(snapshot, stop));
              if (stop.phase() == Phase.WAIT_DEPARTURE && !stop.released()) {
                rows.add(row("exit", stop.exitOpen() ? "exit-open" : "exit-closed", Map.of()));
              }
              rows.add(row("doors", doorsValue(stop), Map.of()));
            });
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
      case OPEN_DOORS -> row("step", "step-open", Map.of("seconds", seconds));
      case DWELL -> row("step", "step-dwell", Map.of());
      case CLOSE_DOORS -> row("step", "step-close", Map.of("seconds", seconds));
      case WAIT_DEPARTURE -> stop.released()
          ? row("step", "step-released", Map.of())
          : !snapshot.seated()
              ? row("step", "step-return", Map.of("seconds", seconds))
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
