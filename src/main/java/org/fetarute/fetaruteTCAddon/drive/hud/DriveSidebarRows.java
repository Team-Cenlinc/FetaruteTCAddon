package org.fetarute.fetaruteTCAddon.drive.hud;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
import org.fetarute.fetaruteTCAddon.drive.cab.AirSystem;
import org.fetarute.fetaruteTCAddon.drive.cab.BrakePipe;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFault;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFaults;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.cab.Vigilance;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverSchedule;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;

/**
 * 驾驶员侧边栏的内容：只放持续变化、行车中要随时看的状态——车门，simulation 级再加风压（机车含制动管，电制动出力时标“再生”）、警惕装置、
 * 当前故障与门旁路；驾驶调度列车时最上面加车次、行车许可与车站。
 *
 * <p>车站一行平时显示下一站，进站时换成离停车点的距离，停妥后显示停站阶段。计分板不会被别的插件的动作栏消息顶掉，所以车站信息以这里为准，动作栏只提示要动手的操作。
 *
 * <p>启动、停放制动、制动试验这类一次性步骤只在挡着牵引时由动作栏提示；方向在动作栏里；启动流程各开关的明细在驾驶台菜单里。 每行是“标签 + 值”，都用语言键表示，由 {@link
 * DriveSidebar} 渲染。本类不依赖服务器对象，便于单测。
 */
public final class DriveSidebarRows {

  private static final double KMH_PER_BPS = 3.6;

  /** 压缩机运转时风压后面的标记。 */
  private static final String PUMP_RUNNING = "↑";

  /** 表定时刻按服务器时区显示。 */
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

  /**
   * 侧边栏的一行。
   *
   * @param labelKey 标签的语言键
   * @param valueKey 值的语言键
   * @param values 值里的占位符
   */
  public record Row(String labelKey, String valueKey, Map<String, String> values) {
    public Row {
      Objects.requireNonNull(labelKey, "labelKey");
      Objects.requireNonNull(valueKey, "valueKey");
      values = values == null ? Map.of() : Map.copyOf(values);
    }
  }

  private DriveSidebarRows() {}

  /** 构建侧边栏全部行。 */
  public static List<Row> build(DriveSession session, long nowTick) {
    Objects.requireNonNull(session, "session");
    List<Row> rows = new ArrayList<>();
    DriverLink link = session.driverLink();
    if (link != null) {
      rows.add(
          new Row(
              "drive.sidebar.label.train",
              "drive.sidebar.value.text",
              Map.of("text", session.trainName())));
      rows.add(
          session.isAto()
              ? new Row("drive.sidebar.label.signal", "drive.sidebar.value.signal.ato", Map.of())
              : signalRow(link));
      rows.add(stationRow(link, session.isStopped()));
      link.schedule().map(DriveSidebarRows::scheduleRow).ifPresent(rows::add);
    }
    rows.add(new Row("drive.sidebar.label.doors", doorsKey(session, nowTick), Map.of()));
    CabSystems cab = session.cab();
    if (cab.enabled()) {
      rows.add(airRow(cab));
      rows.add(vigilanceRow(cab.vigilance(), nowTick, session.isStopped()));
      addFaultRows(rows, cab.faults());
    }
    return rows;
  }

  /** 每个故障一行；门旁路接通时再加一行警示。 */
  private static void addFaultRows(List<Row> rows, CabFaults faults) {
    for (CabFault fault : faults.activeFaults()) {
      String suffix =
          fault == CabFault.BREAKER_TRIP
              ? "."
                  + faults
                      .breakerStage()
                      .map(stage -> stage.name().toLowerCase(Locale.ROOT))
                      .orElse("tripped")
              : "";
      rows.add(
          new Row(
              "drive.sidebar.label.fault",
              "drive.sidebar.value.fault." + fault.key() + suffix,
              Map.of()));
    }
    if (faults.doorBypassed()) {
      rows.add(
          new Row("drive.sidebar.label.door-bypass", "drive.sidebar.value.door-bypass", Map.of()));
    }
  }

  /** 行车许可：信号与此刻的容许速度。 */
  private static Row signalRow(DriverLink link) {
    String label = "drive.sidebar.label.signal";
    DriverDirective directive = link.directive();
    if (directive == null) {
      return new Row(label, "drive.sidebar.value.signal.none", Map.of());
    }
    DriverProtection.Decision decision = link.lastDecision();
    double permitted = decision == null ? directive.permittedBps() : decision.permittedBps();
    return new Row(
        label,
        "drive.sidebar.value.signal."
            + directive.aspect().name().toLowerCase(Locale.ROOT).replace('_', '-'),
        Map.of("limit_kmh", String.valueOf(Math.round(permitted * KMH_PER_BPS))));
  }

  /** 车站一行：平时是下一站，进站时换成离停车点的距离，停妥后是停站各阶段。 */
  private static Row stationRow(DriverLink link, boolean stopped) {
    return DriverStationHint.of(link, stopped)
        .map(hint -> new Row(hint.sidebarLabelKey(), hint.sidebarKey(), hint.values()))
        .orElseGet(
            () ->
                link.targetLabel().isEmpty()
                    ? new Row(
                        "drive.sidebar.label.next-station",
                        "drive.sidebar.value.station.none",
                        Map.of())
                    : new Row(
                        "drive.sidebar.label.next-station",
                        "drive.sidebar.value.text",
                        Map.of("text", link.targetLabel())));
  }

  /** 表定时刻一行：时刻按服务器时区写，后面跟晚点或早点多少。 */
  static Row scheduleRow(DriverSchedule schedule) {
    return new Row(
        schedule.departure()
            ? "drive.sidebar.label.scheduled-departure"
            : "drive.sidebar.label.scheduled-arrival",
        "drive.sidebar.value.schedule." + schedule.state().key(),
        Map.of("time", CLOCK.format(schedule.planned()), "deviation", schedule.deviationText()));
  }

  private static String doorsKey(DriveSession session, long nowTick) {
    boolean left = session.isLeftDoorOpen();
    boolean right = session.isRightDoorOpen();
    String state =
        left && right
            ? "both"
            : left
                ? "left"
                : right ? "right" : session.doorsClosing(nowTick) ? "closing" : "closed";
    return "drive.sidebar.value.doors." + state;
  }

  /** 风压一行：动车组为主风缸 / 制动缸，机车为主风缸 / 制动管 / 制动缸；主风缸按启动压力与封锁线着色，压缩机运转时加标记，电制动出力时换用带“再生”标记的写法。 */
  private static Row airRow(CabSystems cab) {
    AirSystem air = cab.air();
    CabConfig config = cab.config();
    double mr = air.mainReservoirKpa();
    String band =
        mr < config.tractionLockoutKpa() ? "low" : mr < config.compressorCutInKpa() ? "warn" : "ok";
    String pump = air.compressorRunning() ? PUMP_RUNNING : "";
    String mrText = String.valueOf(Math.round(mr));
    String bcText = String.valueOf(Math.round(air.brakeCylinderKpa()));
    String regen = cab.regenerating() ? "-regen" : "";
    Optional<BrakePipe> pipe = cab.brakePipe();
    if (pipe.isPresent()) {
      return new Row(
          "drive.sidebar.label.air",
          "drive.sidebar.value.air-loco" + regen + "." + band,
          Map.of(
              "mr",
              mrText,
              "bp",
              String.valueOf(Math.round(pipe.get().pressureKpa())),
              "bc",
              bcText,
              "pump",
              pump));
    }
    return new Row(
        "drive.sidebar.label.air",
        "drive.sidebar.value.air" + regen + "." + band,
        Map.of("mr", mrText, "bc", bcText, "pump", pump));
  }

  private static Row vigilanceRow(Vigilance vigilance, long nowTick, boolean stopped) {
    String label = "drive.sidebar.label.vigilance";
    if (vigilance.tripped()) {
      return new Row(label, "drive.sidebar.value.vigilance.tripped", Map.of());
    }
    if (vigilance.warning(nowTick)) {
      return new Row(
          label,
          "drive.sidebar.value.vigilance.warning",
          Map.of("seconds", String.valueOf(vigilance.warningRemainingSeconds(nowTick))));
    }
    if (stopped) {
      return new Row(label, "drive.sidebar.value.vigilance.stopped", Map.of());
    }
    return new Row(
        label,
        "drive.sidebar.value.vigilance.counting",
        Map.of("seconds", String.valueOf(vigilance.secondsUntilWarning(nowTick))));
  }
}
