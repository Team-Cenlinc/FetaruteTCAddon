package org.fetarute.fetaruteTCAddon.company.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 线路实体，直接挂载到 Operator。 */
public record Line(
    UUID id,
    String code,
    UUID operatorId,
    String name,
    Optional<String> secondaryName,
    LineServiceType serviceType,
    Optional<String> color,
    LineStatus status,
    Optional<Integer> spawnFreqBaselineSec,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {
  public Line {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(operatorId, "operatorId");
    Objects.requireNonNull(name, "name");
    secondaryName = secondaryName == null ? Optional.empty() : secondaryName;
    Objects.requireNonNull(serviceType, "serviceType");
    color = color == null ? Optional.empty() : color;
    Objects.requireNonNull(status, "status");
    spawnFreqBaselineSec = spawnFreqBaselineSec == null ? Optional.empty() : spawnFreqBaselineSec;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
