package org.fetarute.fetaruteTCAddon.company.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家身份实体，统一封装离线/在线 UUID 与外部账号绑定。
 */
public record PlayerIdentity(
        UUID id,
        UUID playerUuid,
        String name,
        IdentityAuthType authType,
        Optional<String> externalRef,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public PlayerIdentity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(authType, "authType");
        externalRef = externalRef == null ? Optional.empty() : externalRef;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
