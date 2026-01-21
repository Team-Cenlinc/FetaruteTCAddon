package org.fetarute.fetaruteTCAddon.company.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 车站实体，可通过轨道节点或牌子同步位置。 */
public record Station(
    UUID id,
    String code,
    UUID operatorId,
    Optional<UUID> primaryLineId,
    String name,
    Optional<String> secondaryName,
    Optional<String> world,
    Optional<StationLocation> location,
    Optional<String> graphNodeId,
    Optional<Map<String, Object>> amenities,
    List<StationSidingPool> sidingPools,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {
  public Station {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(operatorId, "operatorId");
    primaryLineId = primaryLineId == null ? Optional.empty() : primaryLineId;
    Objects.requireNonNull(name, "name");
    secondaryName = secondaryName == null ? Optional.empty() : secondaryName;
    world = world == null ? Optional.empty() : world;
    location = location == null ? Optional.empty() : location;
    graphNodeId = graphNodeId == null ? Optional.empty() : graphNodeId;
    amenities = amenities == null ? Optional.empty() : amenities.map(Map::copyOf);
    sidingPools = sidingPools == null ? List.of() : List.copyOf(sidingPools);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
