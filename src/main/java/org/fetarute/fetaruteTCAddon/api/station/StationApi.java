package org.fetarute.fetaruteTCAddon.api.station;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * 站点 API：提供站点信息的只读访问。
 *
 * <p>站点是铁路网络中的物理位置，包含：
 *
 * <ul>
 *   <li><b>基本信息</b>：名称、代码、运营商
 *   <li><b>位置信息</b>：世界、坐标、关联的图节点
 *   <li><b>站台信息</b>：侧线池（siding pools）
 * </ul>
 *
 * <h2>使用示例</h2>
 *
 * <pre>{@code
 * StationApi stations = api.stations();
 *
 * // 列出某运营商的所有站点
 * for (StationInfo station : stations.listByOperator(operatorId)) {
 *     System.out.println(station.code() + ": " + station.name());
 * }
 *
 * // 获取站点详情
 * stations.getStation(stationId).ifPresent(station -> {
 *     station.location().ifPresent(loc -> {
 *         System.out.println("位置: " + loc.x() + ", " + loc.y() + ", " + loc.z());
 *     });
 * });
 * }</pre>
 */
public interface StationApi {

  /**
   * 列出所有已注册的站点。
   *
   * @return 站点信息集合（不可变）
   */
  Collection<StationInfo> listAllStations();

  /**
   * 列出指定运营商的所有站点。
   *
   * @param operatorId 运营商 UUID
   * @return 站点信息集合（不可变）
   */
  Collection<StationInfo> listByOperator(UUID operatorId);

  /**
   * 列出指定线路的所有站点。
   *
   * @param lineId 线路 UUID
   * @return 站点信息集合（不可变）
   */
  Collection<StationInfo> listByLine(UUID lineId);

  /**
   * 获取指定站点的详情。
   *
   * @param stationId 站点 UUID
   * @return 站点信息，若不存在则返回 empty
   */
  Optional<StationInfo> getStation(UUID stationId);

  /**
   * 按运营商和代码查找站点。
   *
   * @param operatorId 运营商 UUID
   * @param stationCode 站点代码（如 "AAA"）
   * @return 站点信息，若不存在则返回 empty
   */
  Optional<StationInfo> findByCode(UUID operatorId, String stationCode);

  /**
   * 获取已注册的站点数量。
   *
   * @return 站点总数
   */
  int stationCount();

  // ─────────────────────────────────────────────────────────────────────────────
  // 数据模型
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * 站点信息。
   *
   * @param id 站点 UUID
   * @param code 站点代码（如 "AAA"）
   * @param operatorId 运营商 UUID
   * @param primaryLineId 主要线路 UUID（可选）
   * @param name 站点名称
   * @param secondaryName 副站名（可选，如英文名）
   * @param worldName 所在世界名称（可选）
   * @param location 坐标位置（可选）
   * @param graphNodeId 关联的调度图节点 ID（可选）
   */
  record StationInfo(
      UUID id,
      String code,
      UUID operatorId,
      Optional<UUID> primaryLineId,
      String name,
      Optional<String> secondaryName,
      Optional<String> worldName,
      Optional<Position> location,
      Optional<String> graphNodeId) {}

  /**
   * 位置坐标。
   *
   * @param x X 坐标
   * @param y Y 坐标
   * @param z Z 坐标
   */
  record Position(double x, double y, double z) {}
}
