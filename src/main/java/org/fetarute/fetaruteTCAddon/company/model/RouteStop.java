package org.fetarute.fetaruteTCAddon.company.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Route 顺序节点。 */
public record RouteStop(
    UUID routeId,
    int sequence,
    Optional<UUID> stationId,
    Optional<String> waypointNodeId,
    Optional<Integer> dwellSeconds,
    RouteStopPassType passType,
    Optional<String> notes) {

  /**
   * 停车却没配 {@code dwell} 时的停站秒数。
   *
   * <p>运行时（AutoStation 停站、区间点停车、终到折返）、ETA 与时刻表构建都读这一个值：ETA 若另按 0 秒算， 每经过一个没写 dwell 的站就早报 20 秒。
   */
  public static final int DEFAULT_DWELL_SECONDS = 20;

  public RouteStop {
    Objects.requireNonNull(routeId, "routeId");
    stationId = stationId == null ? Optional.empty() : stationId;
    waypointNodeId = waypointNodeId == null ? Optional.empty() : waypointNodeId;
    dwellSeconds = dwellSeconds == null ? Optional.empty() : dwellSeconds;
    Objects.requireNonNull(passType, "passType");
    notes = notes == null ? Optional.empty() : notes;
  }

  /** 列车是否在此停车：STOP 与 TERMINATE 都停（停站时间为 0 也算），只有 PASS 不停。 */
  public boolean stops() {
    return passType != RouteStopPassType.PASS;
  }

  /** 计划停站秒数：PASS 为 0；停车却没配 dwell（或配了负数）时取 {@link #DEFAULT_DWELL_SECONDS}。 */
  public int plannedDwellSeconds() {
    if (!stops()) {
      return 0;
    }
    return dwellSeconds.filter(value -> value >= 0).orElse(DEFAULT_DWELL_SECONDS);
  }
}
