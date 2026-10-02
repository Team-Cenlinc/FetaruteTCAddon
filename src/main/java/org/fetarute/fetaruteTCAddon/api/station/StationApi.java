package org.fetarute.fetaruteTCAddon.api.station;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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
 *   <li><b>车站组</b>（1.6.0）：乘客视角的一座换乘站，成员可跨运营商、跨公司
 *   <li><b>停靠线路</b>（1.6.0）：停靠本站及同组各站的线路，换乘由 FTCA 计算
 * </ul>
 *
 * <h2>车站组与停靠线路</h2>
 *
 * <p>同一运营商、同一站码的不同股道本来就是同一站；车站组解决的是“不同车站记录属于同一换乘站”。 一个车站最多属于一个组。
 *
 * <p>车站组与停靠线路查询读的是内存快照：路线、车站、线路、车站组变化时重建，每次查询只做查表，不访问存储，可在任意线程、高频调用。 数据变化后 {@code
 * FetaruteApi#dataRevision()} 递增。
 *
 * <pre>{@code
 * // 列车浮层：下一站可换乘的线路（排除本线）
 * for (StationApi.ServingLine line : api.stations().linesServingNode(nextStopNodeId)) {
 *     if (line.lineId().equals(currentLineId)) {
 *         continue;
 *     }
 *     String how = line.transferType().map(Enum::name).orElse("本站");
 *     System.out.println(line.lineCode() + " " + line.lineName() + " (" + how + ")");
 * }
 * }</pre>
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
  // 车站组与停靠线路（1.6.0，内存快照）
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * 列出全部车站组（按组代码排序）。
   *
   * @return 车站组集合（不可变）
   */
  Collection<StationGroupInfo> listStationGroups();

  /**
   * 查找车站所属的车站组。
   *
   * @param stationId 车站 UUID
   * @return 车站组；车站不在任何组时为 empty
   */
  Optional<StationGroupInfo> findGroupOfStation(UUID stationId);

  /**
   * 按节点 ID 找所属车站组（站台、咽喉、DYNAMIC 占位均可）。
   *
   * @param nodeId 节点 ID（如 {@code SURC:S:PPK:1}、{@code SURC:S:PPK:1:001}）
   * @return 车站组；节点不是车站节点、或车站不在任何组时为 empty
   */
  Optional<StationGroupInfo> findGroupOfNode(String nodeId);

  /**
   * 停靠本站及同组各站的全部线路。
   *
   * <p>只统计 STOP 与 TERMINATE（PASS 不算）；DYNAMIC 停靠归到它所在的车站；出库、回库、运营各阶段的路线都统计。
   * 同一条线路既停本站又停同组其他站时只列一次（本站那条）。排序：成员 sortOrder → 运营商代码 → 线路代码。
   *
   * <p>直通运转（1.7.0）：按列车在本站所属的线路统计——换线之后的车站算新线路，换线站本身两条都算（以原线路到达、 以新线路发车），见 {@code
   * RouteApi.StopInfo#lineChange}。
   *
   * @param stationId 车站 UUID
   * @return 线路列表（不可变）；没有线路时为空列表
   */
  List<ServingLine> linesServing(UUID stationId);

  /**
   * 同 {@link #linesServing}，按节点 ID 查询（站台、咽喉、DYNAMIC 占位均可）。
   *
   * @param nodeId 节点 ID
   * @return 线路列表（不可变）；节点不是车站节点时为空列表
   */
  List<ServingLine> linesServingNode(String nodeId);

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

  /** 换乘方式（1.6.0）。 */
  enum TransferType {
    /** 同台换乘 */
    SAME_PLATFORM,
    /** 站内换乘（不出闸） */
    IN_STATION,
    /** 出站换乘 */
    OUT_OF_STATION
  }

  /**
   * 车站组（1.6.0）：乘客视角的一座换乘站。
   *
   * @param id 车站组 UUID
   * @param companyId 组所属公司
   * @param code 组代码（公司内唯一）
   * @param name 组名
   * @param secondaryName 第二语言名称
   * @param members 成员（按 sortOrder、运营商代码、站码排序）
   */
  record StationGroupInfo(
      UUID id,
      UUID companyId,
      String code,
      String name,
      Optional<String> secondaryName,
      List<StationGroupMember> members) {
    public StationGroupInfo {
      members = List.copyOf(members);
    }
  }

  /**
   * 车站组成员（1.6.0）。
   *
   * @param stationId 车站 UUID
   * @param operatorId 车站所属运营商
   * @param operatorCode 运营商代码
   * @param stationCode 站码
   * @param stationName 站名
   * @param transferType 从组内其他车站换乘到本站的方式
   * @param walkSeconds 换乘步行秒数（可选）
   * @param sortOrder 组内排序，小的在前
   */
  record StationGroupMember(
      UUID stationId,
      UUID operatorId,
      String operatorCode,
      String stationCode,
      String stationName,
      TransferType transferType,
      OptionalInt walkSeconds,
      int sortOrder) {}

  /**
   * 停靠查询站（或同组车站）的一条线路（1.6.0）。
   *
   * @param lineId 线路 UUID
   * @param operatorCode 线路所属运营商代码
   * @param lineCode 线路代码
   * @param lineName 线路名称
   * @param color 线路色；缺失时回退运营商主题色
   * @param stationId 这条线实际停靠的车站（可能是同组的另一站）
   * @param stationCode 实际停靠车站的站码
   * @param transferType 相对查询站的换乘方式；查询站自身的线路为空
   * @param walkSeconds 换乘步行秒数；查询站自身的线路为空
   */
  record ServingLine(
      UUID lineId,
      String operatorCode,
      String lineCode,
      String lineName,
      Optional<String> color,
      UUID stationId,
      String stationCode,
      Optional<TransferType> transferType,
      OptionalInt walkSeconds) {}
}
