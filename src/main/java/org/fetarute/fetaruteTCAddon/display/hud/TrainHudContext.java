package org.fetarute.fetaruteTCAddon.display.hud;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
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
 * @param nextStopTransfers 下一站可换乘的线路（不含本车在该站所属的线路）；没有时为空列表
 * @param nextStopDelaySeconds 按表运行时到达下一站的偏差秒数（正数为晚点，见 {@link
 *     org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService#arrivalDeviationSeconds}）；不按表运行时为空
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
    boolean outOfService,
    List<Transfer> nextStopTransfers,
    OptionalLong nextStopDelaySeconds) {
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
    nextStopTransfers = nextStopTransfers == null ? List.of() : List.copyOf(nextStopTransfers);
    nextStopDelaySeconds =
        nextStopDelaySeconds == null ? OptionalLong.empty() : nextStopDelaySeconds;
  }

  /**
   * 停靠站可换乘的一条线路。
   *
   * @param code 线路代码
   * @param name 线路名
   * @param lang2 第二语言名；未填写时为线路名
   * @param colorTag 线路色的 MiniMessage 标签名（见 {@link HudText#colorTag}）
   */
  public record Transfer(String code, String name, String lang2, String colorTag) {
    public Transfer {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(lang2, "lang2");
      Objects.requireNonNull(colorTag, "colorTag");
    }
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
