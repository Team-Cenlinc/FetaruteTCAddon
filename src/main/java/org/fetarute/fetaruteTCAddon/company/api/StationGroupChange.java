package org.fetarute.fetaruteTCAddon.company.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;

/**
 * 车站组的一次数据变化（刷新车站目录、发出公开事件用）。
 *
 * @param groupId 车站组
 * @param companyId 组所属公司
 * @param groupCode 组代码
 * @param kind 变化类型
 * @param stationId 涉及的成员车站（成员变化时）
 */
public record StationGroupChange(
    UUID groupId, UUID companyId, String groupCode, Kind kind, Optional<UUID> stationId) {

  public StationGroupChange {
    Objects.requireNonNull(groupId, "groupId");
    Objects.requireNonNull(companyId, "companyId");
    Objects.requireNonNull(groupCode, "groupCode");
    Objects.requireNonNull(kind, "kind");
    stationId = stationId == null ? Optional.empty() : stationId;
  }

  static StationGroupChange of(StationGroup group, Kind kind, UUID stationId) {
    return new StationGroupChange(
        group.id(), group.companyId(), group.code(), kind, Optional.ofNullable(stationId));
  }

  /** 变化类型。 */
  public enum Kind {
    CREATED,
    MEMBER_ADDED,
    MEMBER_UPDATED,
    MEMBER_REMOVED,
    DELETED
  }
}
