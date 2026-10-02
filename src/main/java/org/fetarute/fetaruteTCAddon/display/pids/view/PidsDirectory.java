package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 站台屏用到的主数据查询：站名、线路色、停站类型与停靠线路。
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
   * 线路的显示样式。
   *
   * @param code 显示代码
   * @param color 线路色（{@code 0xRRGGBB}）
   */
  record LineStyle(String code, int color) {}
}
