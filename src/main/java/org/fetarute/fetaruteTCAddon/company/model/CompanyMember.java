package org.fetarute.fetaruteTCAddon.company.model;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 公司成员关系，包含多角色与可扩展权限。
 */
public record CompanyMember(
        UUID companyId,
        UUID playerIdentityId,
        Set<MemberRole> roles,
        Instant joinedAt,
        Optional<Map<String, Object>> permissions
) {
    public CompanyMember {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(playerIdentityId, "playerIdentityId");
        if (roles == null) {
            roles = Collections.emptySet();
        } else {
            roles = Collections.unmodifiableSet(EnumSet.copyOf(roles));
        }
        Objects.requireNonNull(joinedAt, "joinedAt");
        permissions = permissions == null ? Optional.empty() : permissions.map(Map::copyOf);
    }
}
