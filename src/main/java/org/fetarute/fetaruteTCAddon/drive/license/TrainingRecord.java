package org.fetarute.fetaruteTCAddon.drive.license;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一名玩家某一级的路考练习：完整开完了几次（开到交班站，成绩不限）。
 *
 * @param playerId 玩家
 * @param playerName 玩家名字（最近一次练习时）
 * @param classId 驾驶证等级
 * @param runs 完整开完的次数
 * @param lastAt 最近一次完成的时刻
 */
public record TrainingRecord(
    UUID playerId, String playerName, String classId, int runs, Instant lastAt) {

  public TrainingRecord {
    Objects.requireNonNull(playerId, "playerId");
    playerName = playerName == null ? "" : playerName;
    Objects.requireNonNull(classId, "classId");
    runs = Math.max(0, runs);
    Objects.requireNonNull(lastAt, "lastAt");
  }
}
