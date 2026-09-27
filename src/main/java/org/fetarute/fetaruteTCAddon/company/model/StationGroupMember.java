package org.fetarute.fetaruteTCAddon.company.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 车站组成员。
 *
 * @param groupId 所属车站组
 * @param stationId 成员车站（全局唯一：一个车站最多属于一个组）
 * @param transferType 从组内其他车站换乘到本站的方式
 * @param walkSeconds 换乘步行秒数（可选）
 * @param sortOrder 组内排序，小的在前
 */
public record StationGroupMember(
    UUID groupId,
    UUID stationId,
    StationTransferType transferType,
    Optional<Integer> walkSeconds,
    int sortOrder) {
  public StationGroupMember {
    Objects.requireNonNull(groupId, "groupId");
    Objects.requireNonNull(stationId, "stationId");
    Objects.requireNonNull(transferType, "transferType");
    walkSeconds = walkSeconds == null ? Optional.empty() : walkSeconds.filter(value -> value >= 0);
  }
}
