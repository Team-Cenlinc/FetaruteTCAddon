package org.fetarute.fetaruteTCAddon.company.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 运营商实体。 */
public record Operator(
    UUID id,
    String code,
    UUID companyId,
    String name,
    Optional<String> secondaryName,
    Optional<String> colorTheme,
    int priority,
    Optional<String> description,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {
  public Operator {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(companyId, "companyId");
    Objects.requireNonNull(name, "name");
    secondaryName = secondaryName == null ? Optional.empty() : secondaryName;
    colorTheme = colorTheme == null ? Optional.empty() : colorTheme;
    description = description == null ? Optional.empty() : description;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
