package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;

/**
 * 驾驶调度列车时与车站有关的提示：进站时离停车点多远、停短了要前移、该开哪侧门、停站倒计时、关门、发车信号。
 *
 * <p>只给语言键与占位符，由动作栏与侧边栏渲染。本类不依赖服务器对象，便于单测。
 */
public final class DriverStationHint {

  /** 离停车点超过这么远不提示距离。 */
  static final double SHOW_DISTANCE_BLOCKS = 300.0;

  private static final long TICKS_PER_SECOND = 20L;

  /**
   * 一条提示。
   *
   * @param key 语言键
   * @param values 占位符
   */
  public record Hint(String key, Map<String, String> values) {
    public Hint {
      values = values == null ? Map.of() : Map.copyOf(values);
    }
  }

  private DriverStationHint() {}

  /** 此刻的车站提示；没有时为空。 */
  public static Optional<Hint> of(DriverLink link, boolean stopped) {
    Optional<DriverStationStop> stop = link.stationStop();
    if (stop.isPresent() && stop.get().phase() != DriverStationStop.Phase.APPROACH) {
      DriverStationStop current = stop.get();
      Map<String, String> station = Map.of("station", current.stationName());
      return switch (current.phase()) {
        case OPEN_DOORS -> Optional.of(
            new Hint(
                "drive.hud.station.open-doors."
                    + link.requiredDoorSide().name().toLowerCase(Locale.ROOT),
                station));
        case DWELL -> Optional.of(
            new Hint(
                "drive.hud.station.dwell",
                Map.of(
                    "station",
                    current.stationName(),
                    "seconds",
                    String.valueOf(
                        (current.dwellRemainingTicks() + TICKS_PER_SECOND - 1)
                            / TICKS_PER_SECOND))));
        case CLOSE_DOORS -> Optional.of(new Hint("drive.hud.station.close-doors", station));
        case WAIT_DEPARTURE -> Optional.of(new Hint("drive.hud.station.wait-departure", station));
        case DEPART -> Optional.of(new Hint("drive.hud.station.depart", station));
        default -> Optional.empty();
      };
    }
    Optional<DriverLink.StationTarget> target = link.stationTarget();
    if (target.isEmpty()) {
      return Optional.empty();
    }
    double remaining = target.get().remainingBlocks();
    if (remaining > SHOW_DISTANCE_BLOCKS) {
      return Optional.empty();
    }
    String label = link.targetLabel();
    if (stopped && remaining > StopAlignment.ACCEPT_BLOCKS) {
      return Optional.of(
          new Hint(
              "drive.hud.station.move-up",
              Map.of("station", label, "distance", formatDistance(remaining))));
    }
    if (target.get().precise() && Math.abs(remaining) <= StopAlignment.ACCURATE_BLOCKS) {
      return Optional.of(new Hint("drive.hud.station.on-mark", Map.of("station", label)));
    }
    return Optional.of(
        new Hint(
            remaining < 0.0 ? "drive.hud.station.overrun" : "drive.hud.station.approach",
            Map.of("station", label, "distance", formatDistance(Math.abs(remaining)))));
  }

  /** 10 格以内保留一位小数，更远取整。 */
  static String formatDistance(double blocks) {
    if (blocks < 10.0) {
      return String.format(Locale.ROOT, "%.1f", blocks);
    }
    return String.format(Locale.ROOT, "%.0f", blocks);
  }
}
