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
  public RouteStop {
    Objects.requireNonNull(routeId, "routeId");
    stationId = stationId == null ? Optional.empty() : stationId;
    waypointNodeId = waypointNodeId == null ? Optional.empty() : waypointNodeId;
    dwellSeconds = dwellSeconds == null ? Optional.empty() : dwellSeconds;
    Objects.requireNonNull(passType, "passType");
    notes = notes == null ? Optional.empty() : notes;
  }
}
