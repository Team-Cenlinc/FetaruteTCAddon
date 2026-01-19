package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条“可发车服务”定义：包含 headway（baseFrequency）与出库点（CRET）。
 *
 * <p>当同一条 {@code Line} 配置了多条可发车 route 时，本字段 {@link #baseHeadway()} 已按 route 权重分摊 （总频率仍由 {@code
 * Line.spawnFreqBaselineSec} 决定）。
 *
 * <p>注意：SpawnService 不包含“是否能发车”的实时判断；闭塞门控由 TicketAssigner 在运行时完成。
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
    Duration baseHeadway,
    String depotNodeId) {
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
    baseHeadway = baseHeadway == null ? Duration.ZERO : baseHeadway;
    depotNodeId = depotNodeId == null ? "" : depotNodeId.trim();
    if (baseHeadway.isNegative() || baseHeadway.isZero()) {
      throw new IllegalArgumentException("baseHeadway 必须为正数");
    }
    if (depotNodeId.isBlank()) {
      throw new IllegalArgumentException("depotNodeId 不能为空");
    }
  }
}
