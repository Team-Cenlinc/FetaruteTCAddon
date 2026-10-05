package org.fetarute.fetaruteTCAddon.drive.license;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一名玩家持有的一级驾驶证。
 *
 * @param playerId 持证人
 * @param playerName 持证人名字（发证时）
 * @param classId 驾驶证等级（见 {@link LicenseClass#id()}）
 * @param grantedAt 发证时刻
 * @param grantedBy 怎么拿到的：考试为 {@code exam}，管理员发放为管理员名字
 */
public record LicenseRecord(
    UUID playerId, String playerName, String classId, Instant grantedAt, String grantedBy) {

  public LicenseRecord {
    Objects.requireNonNull(playerId, "playerId");
    playerName = playerName == null ? "" : playerName;
    Objects.requireNonNull(classId, "classId");
    Objects.requireNonNull(grantedAt, "grantedAt");
    grantedBy = grantedBy == null ? "" : grantedBy;
  }
}
