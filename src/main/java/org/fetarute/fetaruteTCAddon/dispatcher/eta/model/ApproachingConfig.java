package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.Objects;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * Approaching（进站/进库）限速配置。
 *
 * <p>用于 {@link DynamicTravelTimeModel} 在计算 ETA 时考虑进站减速。
 *
 * <h2>用途</h2>
 *
 * <p>实际运行时，列车在进站/进库前会减速到 approaching 限速。 ETA 计算若不考虑这个减速过程，会低估最后一段边的时间。
 *
 * <h2>配置项</h2>
 *
 * <ul>
 *   <li>{@code stationApproachSpeedBps}：站点进站限速（blocks/s）
 *   <li>{@code depotApproachSpeedBps}：车库进库限速（blocks/s）
 *   <li>{@code isStation}：判断节点是否为站点的谓词
 *   <li>{@code isDepot}：判断节点是否为车库的谓词
 * </ul>
 */
public record ApproachingConfig(
    double stationApproachSpeedBps,
    double depotApproachSpeedBps,
    Predicate<NodeId> isStation,
    Predicate<NodeId> isDepot) {

  /** 默认站点进站限速（blocks/s）。 */
  public static final double DEFAULT_STATION_APPROACH_SPEED_BPS = 4.0;

  /** 默认车库进库限速（blocks/s）。 */
  public static final double DEFAULT_DEPOT_APPROACH_SPEED_BPS = 3.5;

  public ApproachingConfig {
    Objects.requireNonNull(isStation, "isStation");
    Objects.requireNonNull(isDepot, "isDepot");
    if (!Double.isFinite(stationApproachSpeedBps) || stationApproachSpeedBps < 0.0) {
      throw new IllegalArgumentException("stationApproachSpeedBps 必须为非负数");
    }
    if (!Double.isFinite(depotApproachSpeedBps) || depotApproachSpeedBps < 0.0) {
      throw new IllegalArgumentException("depotApproachSpeedBps 必须为非负数");
    }
  }

  /** 是否启用 approaching 限速（至少有一个限速 > 0 且有有效谓词）。 */
  public boolean enabled() {
    return stationApproachSpeedBps > 0.0 || depotApproachSpeedBps > 0.0;
  }

  /** 创建禁用的配置（不考虑 approaching 限速）。 */
  public static ApproachingConfig disabled() {
    return new ApproachingConfig(0.0, 0.0, node -> false, node -> false);
  }

  /**
   * 使用默认限速创建配置。
   *
   * @param isStation 判断节点是否为站点
   * @param isDepot 判断节点是否为车库
   */
  public static ApproachingConfig withDefaults(
      Predicate<NodeId> isStation, Predicate<NodeId> isDepot) {
    return new ApproachingConfig(
        DEFAULT_STATION_APPROACH_SPEED_BPS, DEFAULT_DEPOT_APPROACH_SPEED_BPS, isStation, isDepot);
  }

  /**
   * 使用自定义限速创建配置。
   *
   * @param stationApproachSpeedBps 站点进站限速
   * @param depotApproachSpeedBps 车库进库限速
   * @param isStation 判断节点是否为站点
   * @param isDepot 判断节点是否为车库
   */
  public static ApproachingConfig of(
      double stationApproachSpeedBps,
      double depotApproachSpeedBps,
      Predicate<NodeId> isStation,
      Predicate<NodeId> isDepot) {
    return new ApproachingConfig(
        stationApproachSpeedBps, depotApproachSpeedBps, isStation, isDepot);
  }
}
