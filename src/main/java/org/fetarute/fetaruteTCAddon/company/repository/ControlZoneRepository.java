package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.ControlZone;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember;

/** 控制区仓库接口。代码在同一运营商内唯一；成员按“控制区 + 种类 + 代码”唯一。 */
public interface ControlZoneRepository {

  Optional<ControlZone> findById(UUID id);

  Optional<ControlZone> findByOperatorAndCode(UUID operatorId, String code);

  List<ControlZone> listByOperator(UUID operatorId);

  List<ControlZone> listAll();

  /** 按 ID 新增或更新。 */
  ControlZone save(ControlZone zone);

  /** 删除控制区及其全部成员。 */
  void delete(UUID id);

  /** 控制区成员，按 {@code sortOrder} 排序。 */
  List<ControlZoneMember> listMembers(UUID zoneId);

  /** 全部成员（内存目录一次性加载用）。 */
  List<ControlZoneMember> listAllMembers();

  /** 新增或更新成员（按控制区 + 种类 + 代码）。 */
  ControlZoneMember saveMember(ControlZoneMember member);

  void removeMember(UUID zoneId, ControlZoneMember.Kind kind, String code);
}
