package org.fetarute.fetaruteTCAddon.company.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Route 实体，描述线路运行形态。 */
public record Route(
    UUID id,
    String code,
    UUID lineId,
    String name,
    Optional<String> secondaryName,
    RoutePatternType patternType,
    RouteOperationType operationType,
    Optional<Integer> distanceMeters,
    Optional<Integer> runtimeSeconds,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {
  public Route {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(lineId, "lineId");
    Objects.requireNonNull(name, "name");
    secondaryName = secondaryName == null ? Optional.empty() : secondaryName;
    Objects.requireNonNull(patternType, "patternType");
    Objects.requireNonNull(operationType, "operationType");
    distanceMeters = distanceMeters == null ? Optional.empty() : distanceMeters;
    runtimeSeconds = runtimeSeconds == null ? Optional.empty() : runtimeSeconds;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
