package org.fetarute.fetaruteTCAddon.company.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 公司成员邀请记录：需要被邀请人确认后才会写入成员表。
 *
 * @param companyId 公司 ID
 * @param playerIdentityId 被邀请玩家身份 ID
 * @param roles 邀请时附带的角色集合
 * @param invitedByIdentityId 邀请发起人身份 ID
 * @param invitedAt 邀请时间
 */
public record CompanyMemberInvite(
    UUID companyId,
    UUID playerIdentityId,
    Set<MemberRole> roles,
    UUID invitedByIdentityId,
    Instant invitedAt) {

  public CompanyMemberInvite {
    Objects.requireNonNull(companyId, "companyId");
    Objects.requireNonNull(playerIdentityId, "playerIdentityId");
    Objects.requireNonNull(invitedByIdentityId, "invitedByIdentityId");
    Objects.requireNonNull(invitedAt, "invitedAt");
    if (roles == null || roles.isEmpty()) {
      throw new IllegalArgumentException("roles 不能为空");
    }
    EnumSet<MemberRole> normalized = EnumSet.copyOf(roles);
    roles = Set.copyOf(normalized);
  }
}
