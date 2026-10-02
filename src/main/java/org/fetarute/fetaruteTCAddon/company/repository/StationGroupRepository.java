package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;

/**
 * 车站组仓库接口。
 *
 * <p>成员的 {@code stationId} 全局唯一：同一车站再加入另一个组会违反唯一约束并抛出存储异常，调用方应先用 {@link #findMemberByStation} 检查。
 */
public interface StationGroupRepository {

  Optional<StationGroup> findById(UUID id);

  Optional<StationGroup> findByCompanyAndCode(UUID companyId, String code);

  List<StationGroup> listByCompany(UUID companyId);

  List<StationGroup> listAll();

  StationGroup save(StationGroup group);

  /** 删除车站组及其全部成员。 */
  void delete(UUID id);

  /** 组内成员，按 {@code sortOrder} 排序。 */
  List<StationGroupMember> listMembers(UUID groupId);

  /** 全部成员（内存索引一次性加载用）。 */
  List<StationGroupMember> listAllMembers();

  Optional<StationGroupMember> findMemberByStation(UUID stationId);

  /** 新增或更新成员（按 {@code groupId + stationId}）。 */
  StationGroupMember saveMember(StationGroupMember member);

  void removeMember(UUID groupId, UUID stationId);
}
