package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 站台屏用到的主数据查询：站名、线路色、停站类型与停靠线路，备注（经由、直通）要用的交路停靠点与车站规模， 以及线路运行状况屏要用的运营商线路与封锁区间。
 *
 * <p>快照只带代码（站码、线路代码、交路 ID），显示需要的名称与颜色由这里解析；实现负责缓存，构建视图时会逐行调用。
 */
public interface PidsDirectory {

  /**
   * 车站的中英文名。
   *
   * @param stationId {@code 运营商:站码}
   */
  Optional<PidsView.Names> stationName(String stationId);

  /**
   * 线路的显示代码与颜色。
   *
   * @param operatorCode 运营商代码
   * @param lineCode 线路代码
   */
  Optional<LineStyle> line(String operatorCode, String lineCode);

  /**
   * 交路的停站类型（各停、快速、特急）。
   *
   * @param routeId {@code 运营商:线路:交路}
   */
  Optional<RouteApi.OperationType> serviceType(String routeId);

  /** 车站停靠的线路，供换乘条显示。 */
  List<PidsView.LineChip> linesServing(PidsStationKey station);

  /**
   * 车站停靠的线路（带运营商），与 {@link #linesServing} 同序；色牌不带运营商，跨运营商同代码的线路要靠它分开。
   *
   * @param station 车站
   * @return 默认把 {@link #linesServing} 的代码都当作本站运营商的线路
   */
  default List<RouteApi.LineRef> lineRefsServing(PidsStationKey station) {
    return linesServing(station).stream()
        .map(chip -> new RouteApi.LineRef(station.operatorCode(), chip.code()))
        .toList();
  }

  /**
   * 停靠某个站台的线路，供站台屏色带显示；多条线共用一个站台时色带按条数等分。
   *
   * @param station 车站
   * @param platform 站台号（股道号）
   * @return 查不到时为空，此时按到发行里出现过的线路
   */
  List<PidsView.LineChip> linesServingPlatform(PidsStationKey station, String platform);

  /**
   * 交路途经节点（与 {@code RouteApi.RouteDetail#waypoints()} 相同，停靠序号即下标）。
   *
   * @param routeId 交路 ID（{@code 运营商:线路:交路}）
   * @return 途经节点；不知道时为空
   */
  default List<String> waypoints(String routeId) {
    return List.of();
  }

  /**
   * 调度图节点的世界坐标。
   *
   * @param worldId 世界
   * @param nodeId 节点 ID
   * @return 坐标；不知道时为空
   */
  default Optional<GraphApi.Position> nodePosition(UUID worldId, String nodeId) {
    return Optional.empty();
  }

  /**
   * 交路各停靠点（与 {@link #waypoints} 同下标）：备注据此推“经由”、找“直通”。
   *
   * @param routeId 交路 ID（{@code 运营商:线路:交路}）
   * @return 停靠点；不知道时为空
   */
  default List<RouteStop> stops(String routeId) {
    return List.of();
  }

  /**
   * 交路显式配置的经由站码，按配置顺序。
   *
   * @param routeId 交路 ID（{@code 运营商:线路:交路}）
   * @return 未配置时为空，备注按换乘线路数、股道数与直通站推断
   */
  default List<String> via(String routeId) {
    return List.of();
  }

  /**
   * 车站的站台（股道）数，备注推“经由”时据此认大站。
   *
   * @param stationId {@code 运营商:站码}
   * @return 不知道时为 0
   */
  default int platformCount(String stationId) {
    return 0;
  }

  /**
   * 线路的中英文名，写在“直通”标签后面。
   *
   * @param operatorCode 运营商代码
   * @param lineCode 线路代码
   */
  default Optional<PidsView.Names> lineName(String operatorCode, String lineCode) {
    return Optional.empty();
  }

  /**
   * 运营商的线路，供线路运行状况屏列出；筹建中的线路不列。
   *
   * @param operatorCode 运营商代码
   * @return 按线路代码排序（数字部分按数值，L2 在 L10 之前）；不知道时为空
   */
  default List<OperatorLine> operatorLines(String operatorCode) {
    return List.of();
  }

  /**
   * 运营商的中英文名。
   *
   * @param operatorCode 运营商代码
   */
  default Optional<PidsView.Names> operatorName(String operatorCode) {
    return Optional.empty();
  }

  /**
   * 运营商代码所属的公司（公告按公司显示）。
   *
   * @return 代码不存在或属于多家公司时为空
   */
  default Optional<UUID> companyOfOperator(String operatorCode) {
    return Optional.empty();
  }

  /**
   * 交路的阶段（出库、运营、回库）。
   *
   * @param routeId {@code 运营商:线路:交路}
   * @return 不知道时为空
   */
  default Optional<RouteApi.RouteStage> routeStage(String routeId) {
    return Optional.empty();
  }

  /**
   * 线路的索引键 {@code 运营商:线路}（去空格、大写），各处按它对线路。
   *
   * @param operatorCode 运营商代码
   * @param lineCode 线路代码
   */
  static String lineKey(String operatorCode, String lineCode) {
    return (operatorCode.trim() + ":" + lineCode.trim()).toUpperCase(Locale.ROOT);
  }

  /**
   * 交路本身所属的线路（管理归属，不随直通换线）：车次取消按它归到线路。
   *
   * @param routeId 交路 UUID
   * @return 不知道时为空
   */
  default Optional<RouteApi.LineRef> lineOfRoute(UUID routeId) {
    return Optional.empty();
  }

  /**
   * 线路的显示样式。
   *
   * @param code 显示代码
   * @param color 线路色（{@code 0xRRGGBB}）
   */
  record LineStyle(String code, int color) {}

  /**
   * 运营商的一条线路。
   *
   * @param id 线路 UUID（对上时刻表）
   * @param operatorCode 运营商代码
   * @param chip 代码、颜色与中英文名
   * @param status 主数据状态
   * @param suspended 交路经过被封锁的区间时，第一处断开的前后两个停车站；没有时为空
   */
  record OperatorLine(
      UUID id,
      String operatorCode,
      PidsView.LineChip chip,
      LineApi.LineStatus status,
      Optional<Section> suspended) {

    public OperatorLine {
      suspended = suspended == null ? Optional.empty() : suspended;
    }
  }

  /**
   * 两个停车站之间的一段线路。
   *
   * @param from 起点车站（{@code 运营商:站码}）
   * @param to 终点车站（{@code 运营商:站码}）
   */
  record Section(String from, String to) {}

  /**
   * 交路上的一个停靠点。
   *
   * @param stationId 所属车站（{@code 运营商:站码}）；区间点、咽喉、车库为空
   * @param stops 在此停车（停车或终到）
   * @param lineChange 直通运转：从本站起列车对乘客显示的线路；本站不换线时为空
   */
  record RouteStop(
      Optional<String> stationId, boolean stops, Optional<RouteApi.LineRef> lineChange) {

    public RouteStop {
      stationId = stationId == null ? Optional.empty() : stationId;
      lineChange = lineChange == null ? Optional.empty() : lineChange;
    }
  }
}
