package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;

/** 公司成员仓库接口。 */
public interface CompanyMemberRepository {

  Optional<CompanyMember> findMembership(UUID companyId, UUID playerIdentityId);

  List<CompanyMember> listMembers(UUID companyId);

  List<CompanyMember> listMemberships(UUID playerIdentityId);

  CompanyMember save(CompanyMember member);

  void delete(UUID companyId, UUID playerIdentityId);
}
