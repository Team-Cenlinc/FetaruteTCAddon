package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMemberInvite;

/** 公司成员邀请仓库接口。 */
public interface CompanyMemberInviteRepository {

  /** 查询某玩家在指定公司的待处理邀请。 */
  Optional<CompanyMemberInvite> findInvite(UUID companyId, UUID playerIdentityId);

  /** 列出玩家所有待处理邀请。 */
  List<CompanyMemberInvite> listInvites(UUID playerIdentityId);

  /** 保存或更新邀请（companyId + playerIdentityId 作为唯一键）。 */
  CompanyMemberInvite save(CompanyMemberInvite invite);

  /** 删除邀请记录。 */
  void delete(UUID companyId, UUID playerIdentityId);
}
