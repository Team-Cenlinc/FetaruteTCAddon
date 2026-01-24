package org.fetarute.fetaruteTCAddon.display.hud;

import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;

/**
 * HUD 上下文：汇总线路/站点/ETA/运行时状态，用于占位符与状态渲染。
 *
 * <p>所有可能缺失的数据已在 resolver 内标准化为 {@code "-"}，避免模板层处理 null。
 */
public record TrainHudContext(
    String trainName,
    int routeIndex,
    Optional<RouteDefinition> routeDefinition,
    Optional<HudTemplateService.LineInfo> lineInfo,
    StationDisplay currentStation,
    StationDisplay nextStation,
    Destinations destinations,
    EtaResult eta,
    SignalAspect signalAspect,
    Optional<LayoverRegistry.LayoverCandidate> layover,
    boolean stop,
    boolean moving,
    boolean atLastStation,
    boolean terminalNextStop,
    double speedBps) {
  public TrainHudContext {
    Objects.requireNonNull(trainName, "trainName");
    routeDefinition = routeDefinition == null ? Optional.empty() : routeDefinition;
    lineInfo = lineInfo == null ? Optional.empty() : lineInfo;
    Objects.requireNonNull(currentStation, "currentStation");
    Objects.requireNonNull(nextStation, "nextStation");
    Objects.requireNonNull(destinations, "destinations");
    Objects.requireNonNull(eta, "eta");
    layover = layover == null ? Optional.empty() : layover;
  }

  /** 终点信息：EOR/ EOP。 */
  public record Destinations(StationDisplay eor, StationDisplay eop) {
    public Destinations {
      Objects.requireNonNull(eor, "eor");
      Objects.requireNonNull(eop, "eop");
    }

    public static Destinations empty() {
      return new Destinations(StationDisplay.empty(), StationDisplay.empty());
    }
  }

  /** 站点展示信息：主标签 + code + 第二语言名称。 */
  public record StationDisplay(String label, String code, String lang2) {
    public StationDisplay {
      label = sanitize(label);
      code = sanitize(code);
      lang2 = sanitize(lang2);
    }

    public static StationDisplay empty() {
      return new StationDisplay("-", "-", "-");
    }

    public static StationDisplay of(String label, String code, String lang2) {
      return new StationDisplay(label, code, lang2);
    }

    public static StationDisplay fromStation(Station station) {
      String code = station == null ? "-" : station.code();
      String name = station == null ? "-" : station.name();
      String label = name == null || name.isBlank() ? code : name;
      String secondary =
          station == null ? "-" : station.secondaryName().filter(s -> !s.isBlank()).orElse("-");
      return new StationDisplay(label, code, secondary);
    }

    public boolean isEmpty() {
      return "-".equals(label) && "-".equals(code) && "-".equals(lang2);
    }

    public StationDisplay sanitized() {
      return new StationDisplay(label, code, lang2);
    }

    private static String sanitize(String value) {
      if (value == null || value.isBlank()) {
        return "-";
      }
      return value;
    }
  }
}
