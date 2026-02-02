package org.fetarute.fetaruteTCAddon.api.line;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * 线路 API：提供线路信息的只读访问。
 *
 * <p>线路挂载在运营商之下，包含服务类型、颜色、状态等信息，适用于 PIDS 与地图展示。
 *
 * <h2>使用示例</h2>
 *
 * <pre>{@code
 * LineApi lines = api.lines();
 *
 * // 列出某运营商线路
 * for (LineInfo line : lines.listByOperator(operatorId)) {
 *     System.out.println(line.code() + ": " + line.name());
 * }
 *
 * // 查找线路
 * lines.findByCode(operatorId, "L1").ifPresent(line -> {
 *     System.out.println(line.serviceType());
 * });
 * }</pre>
 */
public interface LineApi {

  /**
   * 列出所有线路（按运营商聚合）。
   *
   * @return 线路集合（不可变）
   */
  Collection<LineInfo> listAllLines();

  /**
   * 列出指定运营商的线路。
   *
   * @param operatorId 运营商 UUID
   * @return 线路集合（不可变）
   */
  Collection<LineInfo> listByOperator(UUID operatorId);

  /**
   * 获取指定线路详情。
   *
   * @param lineId 线路 UUID
   * @return 线路信息，若不存在则返回 empty
   */
  Optional<LineInfo> getLine(UUID lineId);

  /**
   * 按运营商和代码查找线路。
   *
   * @param operatorId 运营商 UUID
   * @param lineCode 线路代码（如 "L1"）
   * @return 线路信息，若不存在则返回 empty
   */
  Optional<LineInfo> findByCode(UUID operatorId, String lineCode);

  /**
   * 获取线路数量。
   *
   * @return 线路总数
   */
  int lineCount();

  // ─────────────────────────────────────────────────────────────────────────────
  // 数据模型
  // ─────────────────────────────────────────────────────────────────────────────

  /** 线路类型。 */
  enum ServiceType {
    METRO,
    REGIONAL,
    COMMUTER,
    LRT,
    EXPRESS,
    UNKNOWN
  }

  /** 线路状态。 */
  enum LineStatus {
    PLANNING,
    ACTIVE,
    MAINTENANCE,
    UNKNOWN
  }

  /**
   * 线路信息。
   *
   * @param id 线路 UUID
   * @param code 线路代码
   * @param operatorId 运营商 UUID
   * @param name 线路名称
   * @param secondaryName 副名称（可选）
   * @param serviceType 服务类型
   * @param color 线路颜色（可选）
   * @param status 线路状态
   * @param spawnFreqBaselineSec 发车基准间隔（秒，可选）
   */
  record LineInfo(
      UUID id,
      String code,
      UUID operatorId,
      String name,
      Optional<String> secondaryName,
      ServiceType serviceType,
      Optional<String> color,
      LineStatus status,
      Optional<Integer> spawnFreqBaselineSec) {}
}
