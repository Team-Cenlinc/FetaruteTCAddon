package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一条可出票服务定义：包含起点与可选的周期发车间隔。
 *
 * <p>当同一条 {@code Line} 配置了多条可发车 route 时，本字段 {@link #baseHeadway()} 已按 route 权重分摊 （总频率仍由 {@code
 * Line.spawnFreqBaselineSec} 决定）。
 *
 * <p>注意：SpawnService 不包含“是否能发车”的实时判断；闭塞门控由 TicketAssigner 在运行时完成。
 *
 * <p>{@link #baseHeadway()} 为空表示仅随运营车辆交路出票；服务仍可用于表定出入库与替补， 但周期生成器、周期预测与按间隔计划器必须跳过它，不能以默认间隔补齐。
 */
public record SpawnService(
    SpawnServiceKey key,
    UUID companyId,
    String companyCode,
    UUID operatorId,
    String operatorCode,
    UUID lineId,
    String lineCode,
    UUID routeId,
    String routeCode,
    Optional<Duration> baseHeadway,
    String depotNodeId) {
  /** 保持普通周期服务的构造方式，周期间隔仍必须为正。 */
  public SpawnService(
      SpawnServiceKey key,
      UUID companyId,
      String companyCode,
      UUID operatorId,
      String operatorCode,
      UUID lineId,
      String lineCode,
      UUID routeId,
      String routeCode,
      Duration baseHeadway,
      String depotNodeId) {
    this(
        key,
        companyId,
        companyCode,
        operatorId,
        operatorCode,
        lineId,
        lineCode,
        routeId,
        routeCode,
        Optional.of(baseHeadway),
        depotNodeId);
  }

  public SpawnService {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(companyId, "companyId");
    companyCode = companyCode == null ? "" : companyCode;
    Objects.requireNonNull(operatorId, "operatorId");
    operatorCode = operatorCode == null ? "" : operatorCode;
    Objects.requireNonNull(lineId, "lineId");
    lineCode = lineCode == null ? "" : lineCode;
    Objects.requireNonNull(routeId, "routeId");
    routeCode = routeCode == null ? "" : routeCode;
    baseHeadway = baseHeadway == null ? Optional.empty() : baseHeadway;
    depotNodeId = depotNodeId == null ? "" : depotNodeId.trim();
    if (baseHeadway.filter(value -> value.isNegative() || value.isZero()).isPresent()) {
      throw new IllegalArgumentException("baseHeadway 必须为正数");
    }
    if (depotNodeId.isBlank()) {
      throw new IllegalArgumentException("depotNodeId 不能为空");
    }
  }
}
