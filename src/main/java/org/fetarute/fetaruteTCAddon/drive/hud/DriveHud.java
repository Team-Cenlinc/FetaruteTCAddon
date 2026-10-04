package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.driver.CabChange;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection;
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;
import org.fetarute.fetaruteTCAddon.drive.menu.CabGauges;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupSystem;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupText;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 驾驶员的动作栏显示：车速与限速、档位与力度、非前进时的行驶方向，以及一条最要紧的提示。
 *
 * <p>车速与限速写 km/h（1 格/秒按 1 米/秒计），去掉了“速度、档位、限速”标签，一眼读完。超速时车速与限速按 {@link OverspeedLevel} 标黄、标红。
 * 文案走语言文件的 {@code drive.hud.*} 键。
 */
public final class DriveHud {

  /** 1 格/秒折合的 km/h。 */
  private static final double KMH_PER_BPS = 3.6;

  private static final long TICKS_PER_SECOND = 20L;

  private static final String BAR_CELL = "|";

  private static final Component SPACE = Component.text(" ");

  private DriveHud() {}

  /**
   * 构建当前这一帧的动作栏内容。
   *
   * @param sidebarShown 驾驶员此刻看得到侧边栏：车站的距离、停站倒计时等只在侧边栏显示，动作栏只提示要动手的操作
   */
  public static Component render(LocaleManager locale, DriveSession session, boolean sidebarShown) {
    String speedText = format(session.speedBps() * KMH_PER_BPS);
    if (session.phase() != DriveSession.Phase.ACTIVE) {
      return locale.component("drive.hud.line-stopping", Map.of("speed_kmh", speedText));
    }
    double limitBps = session.displayLimitBps();
    OverspeedLevel level =
        OverspeedLevel.classify(session.speedBps(), limitBps, session.overspeedRedRatio());
    Component speed =
        locale.component(
            "drive.hud.speed." + (level == OverspeedLevel.NONE ? "ok" : level.key()),
            Map.of("speed_kmh", speedText));
    Component limit =
        locale.component(
            "drive.hud.limit." + level.key(), Map.of("limit_kmh", format(limitBps * KMH_PER_BPS)));
    TagResolver resolver =
        TagResolver.builder()
            .resolver(Placeholder.component("speed", speed))
            .resolver(Placeholder.component("limit", limit))
            .resolver(
                Placeholder.unparsed("notch", session.isAto() ? "ATO" : session.notch().name()))
            .resolver(Placeholder.component("force", forceBar(session.effort())))
            .resolver(Placeholder.component("direction", direction(locale, session)))
            .build();
    // 没有限速信息时只写车速，不显示“-”。
    Component line =
        locale.component(
            level == OverspeedLevel.NONE ? "drive.hud.line-no-limit" : "drive.hud.line", resolver);
    CabSystems cab = session.cab();
    long now = Bukkit.getCurrentTick();
    if (cab.enabled() && cab.vigilance().tripped()) {
      line = locale.component("drive.hud.cab.vigilance-tripped").append(SPACE).append(line);
    } else if (cab.enabled() && cab.vigilance().warning(now)) {
      line =
          locale
              .component(
                  "drive.hud.cab.vigilance-warning",
                  Map.of("seconds", String.valueOf(cab.vigilance().warningRemainingSeconds(now))))
              .append(SPACE)
              .append(line);
    }
    Component status = statusToken(locale, session, sidebarShown);
    if (status != null) {
      line = line.append(SPACE).append(status);
    }
    return line;
  }

  /**
   * 动作栏末尾最要紧的一条提示：为什么现在不能牵引。折返换端最先，其次按防护介入、启动流程、车上故障（主断跳闸、受电中断、门关好回路）、停放制动、
   * 制动管、风压、制动试验、车门的顺序取第一条；都没有而门旁路接通着时提示旁路；否则为 {@code null}。各系统的完整状态在侧边栏里。
   */
  private static Component statusToken(
      LocaleManager locale, DriveSession session, boolean sidebarShown) {
    Component cabChange =
        cabChangeToken(locale, session.cabChange(), session.pendingCabSeat().isPresent());
    if (cabChange != null) {
      return cabChange;
    }
    long ebGrace = session.selector().ebGraceRemaining(Bukkit.getCurrentTick());
    if (ebGrace > 0L) {
      return locale.component(
          "drive.hud.eb-grace",
          Map.of(
              "seconds", String.format(Locale.ROOT, "%.1f", ebGrace / (double) TICKS_PER_SECOND)));
    }
    DriverLink link = session.driverLink();
    String intervention = link == null ? null : interventionKey(link);
    if (intervention != null) {
      return locale.component(intervention);
    }
    if (link != null && link.departurePrompt()) {
      return locale.component("drive.hud.ato.confirm");
    }
    if (link != null && link.departureConfirmed()) {
      return locale.component("drive.hud.ato.armed");
    }
    if (link != null && link.warnedBlockingSeconds() > 0L) {
      return locale.component(
          "drive.hud.driver.blocking",
          Map.of("seconds", String.valueOf(link.warnedBlockingSeconds())));
    }
    if (!session.setup().ready()) {
      return setupSegment(locale, session);
    }
    if (session.supercap().filter(sc -> sc.depleted() && !sc.charging()).isPresent()) {
      return locale.component("drive.hud.supercap.depleted");
    }
    CabSystems cab = session.cab();
    Optional<CabSystems.TractionBlock> block = cab.tractionBlock();
    if (block.isPresent()) {
      return switch (block.get()) {
        case BREAKER_TRIPPED -> locale.component("drive.hud.cab.fault.breaker-trip");
        case LINE_LOSS -> locale.component("drive.hud.cab.fault.line-loss");
        case DOOR_CIRCUIT -> locale.component("drive.hud.cab.fault.door");
        case PARKING_BRAKE -> locale.component("drive.hud.cab.parking");
        case BRAKE_PIPE -> locale.component("drive.hud.cab.brake-pipe");
        case LOW_AIR -> locale.component("drive.hud.cab.low-air");
        case BRAKE_TEST -> locale.component(
            "drive.hud.cab.brake-test." + cab.brakeTest().stageKey(),
            CabGauges.brakeTestValues(cab));
      };
    }
    if (link != null) {
      Optional<DriverStationHint.Hint> station = DriverStationHint.of(link, session.isStopped());
      switch (stationSlot(station, sidebarShown)) {
        case SHOW -> {
          return locale.component(station.get().key(), station.get().values());
        }
        case SUPPRESS -> {
          return null;
        }
        case NONE -> {}
      }
    }
    if (session.anyDoorOpen()) {
      return locale.component("drive.hud.doors-open");
    }
    if (session.doorsClosing(session.lastAdvanceTick())) {
      return locale.component("drive.hud.doors-closing");
    }
    if (link != null
        && link.controlsPhysically()
        && link.directive() != null
        && link.directive().isStop()) {
      return locale.component("drive.hud.driver.wait-signal");
    }
    if (cab.enabled() && cab.faults().doorBypassed()) {
      return locale.component("drive.hud.cab.door-bypass");
    }
    return null;
  }

  /**
   * 折返换端的提示：计时中显示要去第几节与剩余秒数，提前告知时只显示第几节，坐进驾驶座没有标记的那一端时请确认座位；不换端时为 {@code null}。
   *
   * @param confirmSeat 正在等驾驶员确认座位
   */
  static Component cabChangeToken(LocaleManager locale, CabChange change, boolean confirmSeat) {
    if (confirmSeat) {
      // 坐进了驾驶座没有标记的那一端：先确认座位（计时中照样计时）。
      return locale.component("drive.hud.cab-change.confirm");
    }
    return switch (change.stage()) {
      case ACTIVE -> locale.component(
          "drive.hud.cab-change.active",
          Map.of(
              "car",
              String.valueOf(change.targetCar()),
              "seconds",
              String.valueOf(Math.max(0L, change.secondsLeft()))));
      case ANNOUNCED -> locale.component(
          "drive.hud.cab-change.announced", Map.of("car", String.valueOf(change.targetCar())));
      case IDLE -> null;
    };
  }

  /** 车站提示在动作栏里怎么处理。 */
  enum StationSlot {
    /** 显示这条车站提示。 */
    SHOW,
    /** 什么也不显示：停站中车门开着是正常的，倒计时与等待在侧边栏里。 */
    SUPPRESS,
    /** 不显示车站提示，接着看后面的提示（车门、停车信号）。 */
    NONE
  }

  /**
   * 车站提示放不放进动作栏：侧边栏看得到时只放要驾驶员动手的提示，其余在侧边栏；侧边栏看不到时全放。
   *
   * @param hint 此刻的车站提示
   * @param sidebarShown 驾驶员此刻看得到侧边栏
   */
  static StationSlot stationSlot(Optional<DriverStationHint.Hint> hint, boolean sidebarShown) {
    if (hint.isEmpty()) {
      return StationSlot.NONE;
    }
    if (!sidebarShown || hint.get().actionable()) {
      return StationSlot.SHOW;
    }
    return hint.get().atStation() ? StationSlot.SUPPRESS : StationSlot.NONE;
  }

  /** 驾驶调度列车时防护正在介入的提示；没有介入时为 {@code null}。强制停车最要紧，其次紧急制动、ATP 制动、等待交还、无行车许可。 */
  static String interventionKey(DriverLink link) {
    DriverProtection.Decision decision = link.lastDecision();
    DriverProtection.Intervention intervention =
        decision == null ? DriverProtection.Intervention.NONE : decision.intervention();
    if (intervention == DriverProtection.Intervention.CLAMP) {
      return "drive.hud.driver.forced-stop";
    }
    if (intervention == DriverProtection.Intervention.EMERGENCY || link.emergencyLatched()) {
      return "drive.hud.driver.emergency";
    }
    if (link.signalConfirm().pending()) {
      return "drive.hud.driver.confirm-signal";
    }
    if (intervention == DriverProtection.Intervention.SERVICE) {
      return "drive.hud.driver.service";
    }
    if (link.handbackRequested()) {
      return "drive.hud.driver.handback";
    }
    if (!link.controlsPhysically()) {
      // ATO 下由自动运行操纵，不向驾驶员下发行车许可。
      return null;
    }
    if (link.directive() == null) {
      return "drive.hud.driver.no-signal";
    }
    return null;
  }

  /** 行驶方向：前进是常态，不占位置；空挡与后退才显示，前面带一个空格。 */
  static Component direction(LocaleManager locale, DriveSession session) {
    if (session.reverser() == ReverserPosition.FORWARD) {
      return Component.empty();
    }
    String key = "drive.hud.direction." + session.reverser().name().toLowerCase(Locale.ROOT);
    return SPACE.append(
        locale.component("drive.hud.direction-tag", Map.of("direction", locale.text(key))));
  }

  /** 十格力度条：点亮的格按种类着色，其余暗灰。 */
  static Component forceBar(double effort) {
    ForceBar.Bar bar = ForceBar.of(effort);
    NamedTextColor color =
        switch (bar.kind()) {
          case TRACTION -> NamedTextColor.GREEN;
          case BRAKE -> NamedTextColor.GOLD;
          case EMERGENCY -> NamedTextColor.RED;
          case NONE -> NamedTextColor.DARK_GRAY;
        };
    return Component.text()
        .append(Component.text(BAR_CELL.repeat(bar.filled()), color))
        .append(
            Component.text(
                BAR_CELL.repeat(ForceBar.CELLS - bar.filled()), NamedTextColor.DARK_GRAY))
        .build();
  }

  /** 列车尚未启动时的提示：正在接通哪一步、还剩几秒；逐项操作时提示下一步；一键启动时提示未启动。 */
  private static Component setupSegment(LocaleManager locale, DriveSession session) {
    TrainSetup setup = session.setup();
    Optional<TrainSetup.Progress> progress = setup.progress(Bukkit.getCurrentTick());
    if (progress.isEmpty()) {
      Optional<SetupSystem> next = setup.nextStep();
      if (session.setupMode() == SimulationLevel.SetupMode.MANUAL && next.isPresent()) {
        // 逐项操作时直接提示下一步要拨哪个开关；一键启动只需按启动按钮。
        return locale.component(
            "drive.hud.setup.next",
            Map.of("step", locale.text(SetupText.stepKey(next.get(), setup.supply()))));
      }
      return locale.component("drive.hud.setup.not-ready");
    }
    long seconds = (progress.get().remainingTicks() + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    return locale.component(
        "drive.hud.setup.progress",
        Map.of(
            "step",
            locale.text(SetupText.stepKey(progress.get().system(), setup.supply())),
            "seconds",
            String.valueOf(seconds)));
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.0f", value);
  }
}
