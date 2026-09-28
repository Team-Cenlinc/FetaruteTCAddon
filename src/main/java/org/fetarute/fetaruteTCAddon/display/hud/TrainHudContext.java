package org.fetarute.fetaruteTCAddon.display.hud;

import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;

/**
 * HUD 上下文：汇总线路/站点/ETA/运行时状态，用于占位符与状态渲染。
 *
 * <p>所有可能缺失的数据已在 resolver 内标准化为 {@code "-"}，避免模板层处理 null。
 *
 * <p>线路：{@code routeDefinition} 是交路本身；{@code currentLine}/{@code lineInfo} 是列车当前所属的线路——
 * 直通运转（CHANGE）换线后两者不同，线路名、颜色、运营商、绑定模板都跟当前线路走。
 *
 * @param currentLine 列车当前所属线路（线路标签优先，见 {@link RouteLineChanges#current}）；交路线路不明时为空
 * @param throughService 前方的直通换线（下一次换线）；没有时为空
 * @param outOfService 回库车已越过运营终点（{@link
 *     org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals#outOfService}）
 */
public record TrainHudContext(
    String trainName,
    int routeIndex,
    Optional<RouteDefinition> routeDefinition,
    Optional<HudTemplateService.LineInfo> lineInfo,
    Optional<RoutePatternType> routePatternType,
    StationDisplay currentStation,
    StationDisplay nextStation,
    String nextStationTrack,
    Destinations destinations,
    EtaResult eta,
    SignalAspect signalAspect,
    Optional<LayoverRegistry.LayoverCandidate> layover,
    boolean stop,
    boolean moving,
    boolean atLastStation,
    boolean terminalNextStop,
    double speedBps,
    Optional<RouteLineChanges.LineRef> currentLine,
    Optional<ThroughService> throughService,
    boolean outOfService) {
  public TrainHudContext {
    Objects.requireNonNull(trainName, "trainName");
    routeDefinition = routeDefinition == null ? Optional.empty() : routeDefinition;
    lineInfo = lineInfo == null ? Optional.empty() : lineInfo;
    routePatternType = routePatternType == null ? Optional.empty() : routePatternType;
    Objects.requireNonNull(currentStation, "currentStation");
    Objects.requireNonNull(nextStation, "nextStation");
    nextStationTrack =
        nextStationTrack == null || nextStationTrack.isBlank() ? "-" : nextStationTrack;
    Objects.requireNonNull(destinations, "destinations");
    Objects.requireNonNull(eta, "eta");
    layover = layover == null ? Optional.empty() : layover;
    currentLine = currentLine == null ? Optional.empty() : currentLine;
    throughService = throughService == null ? Optional.empty() : throughService;
  }

  /**
   * 前方的直通换线：列车将从 {@code station} 起改属 {@code line}（在该站以原线路到达、以新线路发车）。
   *
   * @param station 换线站
   * @param line 换线后的线路
   * @param lineInfo 换线后线路的名称与颜色；线路不存在时为空
   */
  public record ThroughService(
      StationDisplay station,
      RouteLineChanges.LineRef line,
      Optional<HudTemplateService.LineInfo> lineInfo) {
    public ThroughService {
      Objects.requireNonNull(station, "station");
      Objects.requireNonNull(line, "line");
      lineInfo = lineInfo == null ? Optional.empty() : lineInfo;
    }
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
