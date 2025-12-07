package org.fetarute.fetaruteTCAddon.company.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 铁路公司实体。 */
public record Company(
    UUID id,
    String code,
    String name,
    Optional<String> secondaryName,
    UUID ownerIdentityId,
    CompanyStatus status,
    long balanceMinor,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {
  public Company {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    secondaryName = secondaryName == null ? Optional.empty() : secondaryName;
    Objects.requireNonNull(ownerIdentityId, "ownerIdentityId");
    Objects.requireNonNull(status, "status");
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
