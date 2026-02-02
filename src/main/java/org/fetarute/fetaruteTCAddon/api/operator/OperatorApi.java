package org.fetarute.fetaruteTCAddon.api.operator;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * 运营商 API：提供运营商信息的只读访问。
 *
 * <p>运营商用于区分线路/站点的运营主体，常用于 HUD 与地图渲染的颜色/标识展示。
 *
 * <h2>使用示例</h2>
 *
 * <pre>{@code
 * OperatorApi operators = api.operators();
 *
 * // 列出某公司下的运营商
 * for (OperatorInfo op : operators.listByCompany(companyId)) {
 *     System.out.println(op.code() + ": " + op.name());
 * }
 *
 * // 查询运营商
 * operators.findByCode(companyId, "MTR").ifPresent(op -> {
 *     op.colorTheme().ifPresent(color -> System.out.println("主题色: " + color));
 * });
 * }</pre>
 */
public interface OperatorApi {

  /**
   * 列出所有运营商（按公司聚合）。
   *
   * @return 运营商集合（不可变）
   */
  Collection<OperatorInfo> listAllOperators();

  /**
   * 列出指定公司下的运营商。
   *
   * @param companyId 公司 UUID
   * @return 运营商集合（不可变）
   */
  Collection<OperatorInfo> listByCompany(UUID companyId);

  /**
   * 获取指定运营商详情。
   *
   * @param operatorId 运营商 UUID
   * @return 运营商信息，若不存在则返回 empty
   */
  Optional<OperatorInfo> getOperator(UUID operatorId);

  /**
   * 按公司和代码查找运营商。
   *
   * @param companyId 公司 UUID
   * @param operatorCode 运营商代码（如 "MTR"）
   * @return 运营商信息，若不存在则返回 empty
   */
  Optional<OperatorInfo> findByCode(UUID companyId, String operatorCode);

  /**
   * 获取运营商数量。
   *
   * @return 运营商总数
   */
  int operatorCount();

  // ─────────────────────────────────────────────────────────────────────────────
  // 数据模型
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * 运营商信息。
   *
   * @param id 运营商 UUID
   * @param code 运营商代码
   * @param companyId 所属公司 UUID
   * @param name 运营商名称
   * @param secondaryName 副名称（可选）
   * @param colorTheme HUD/地图主题色（可选）
   * @param priority 排序优先级
   * @param description 描述（可选）
   */
  record OperatorInfo(
      UUID id,
      String code,
      UUID companyId,
      String name,
      Optional<String> secondaryName,
      Optional<String> colorTheme,
      int priority,
      Optional<String> description) {}
}
