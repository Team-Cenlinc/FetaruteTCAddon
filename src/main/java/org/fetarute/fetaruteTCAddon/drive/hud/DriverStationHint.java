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
 * <p>同一条提示在动作栏与侧边栏各有一套语言键：侧边栏常驻显示全部，动作栏只放要驾驶员动手的（{@link Hint#actionable()}）。 本类不依赖服务器对象，便于单测。
 */
public final class DriverStationHint {

  /** 离停车点超过这么远不提示距离。 */
  static final double SHOW_DISTANCE_BLOCKS = 300.0;

  private static final long TICKS_PER_SECOND = 20L;

  /** 提示种类。 */
  public enum Kind {
    /** 进站中：离停车点还有多远。 */
    APPROACH("approach", false, false),
    /** 已在停准范围内。 */
    ON_MARK("on-mark", false, false),
    /** 停短超出可开门范围：前移。 */
    MOVE_UP("move-up", true, false),
    /** 越过停车点。 */
    OVERRUN("overrun", false, false),
    /** 停妥，等驾驶员开门。 */
    OPEN_DOORS("open-doors", true, true),
    /** 停站计时中。 */
    DWELL("dwell", false, true),
    /** 停站时间到，等驾驶员关门。 */
    CLOSE_DOORS("close-doors", true, true),
    /** 车门已关，等出站许可。 */
    WAIT_DEPARTURE("wait-departure", false, true),
    /** 已有出站许可，可以起步。 */
    DEPART("depart", true, true);

    private final String key;
    private final boolean actionable;
    private final boolean atStation;

    Kind(String key, boolean actionable, boolean atStation) {
      this.key = key;
      this.actionable = actionable;
      this.atStation = atStation;
    }
  }

  /**
   * 一条提示。
   *
   * @param kind 种类
   * @param variant 种类下的细分（开门时为站台侧，如 {@code left}）；没有时为空串
   * @param values 占位符
   */
  public record Hint(Kind kind, String variant, Map<String, String> values) {
    public Hint {
      variant = variant == null ? "" : variant;
      values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** 动作栏用的语言键。 */
    public String key() {
      return "drive.hud.station." + suffix();
    }

    /** 侧边栏值的语言键。 */
    public String sidebarKey() {
      return "drive.sidebar.value.stop." + suffix();
    }

    /** 侧边栏标签的语言键：在站内是“停站”，进站中是“停车点”。 */
    public String sidebarLabelKey() {
      return kind.atStation ? "drive.sidebar.label.stop" : "drive.sidebar.label.stop-mark";
    }

    /** 列车已在站内停妥（开门到发车之间）。 */
    public boolean atStation() {
      return kind.atStation;
    }

    /** 要驾驶员动手（前移、开关门、起步）；只有这类提示放进动作栏。 */
    public boolean actionable() {
      return kind.actionable;
    }

    private String suffix() {
      return variant.isEmpty() ? kind.key : kind.key + "." + variant;
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
                Kind.OPEN_DOORS, link.requiredDoorSide().name().toLowerCase(Locale.ROOT), station));
        case DWELL -> Optional.of(
            new Hint(
                Kind.DWELL,
                "",
                Map.of(
                    "station",
                    current.stationName(),
                    "seconds",
                    String.valueOf(
                        (current.dwellRemainingTicks() + TICKS_PER_SECOND - 1)
                            / TICKS_PER_SECOND))));
        case CLOSE_DOORS -> Optional.of(new Hint(Kind.CLOSE_DOORS, "", station));
        case WAIT_DEPARTURE -> Optional.of(new Hint(Kind.WAIT_DEPARTURE, "", station));
        case DEPART -> Optional.of(new Hint(Kind.DEPART, "", station));
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
    // 只有进站后（站台量出的停车点）停短才提示前移；进站前停车多半是在等信号。
    boolean precise = target.get().precise();
    if (stopped && precise && remaining > StopAlignment.acceptBlocks()) {
      return Optional.of(
          new Hint(
              Kind.MOVE_UP, "", Map.of("station", label, "distance", formatDistance(remaining))));
    }
    if (precise && Math.abs(remaining) <= StopAlignment.accurateBlocks()) {
      return Optional.of(new Hint(Kind.ON_MARK, "", Map.of("station", label)));
    }
    return Optional.of(
        new Hint(
            remaining < 0.0 ? Kind.OVERRUN : Kind.APPROACH,
            "",
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
