package org.fetarute.fetaruteTCAddon.drive.driver.record;

import java.util.UUID;

/**
 * 排行榜的一行：一名驾驶员在统计期内完成的任务。
 *
 * @param playerId 驾驶员
 * @param playerName 最近一次记录里的名字
 * @param tasks 完成的任务数
 * @param totalPoints 总分
 */
public record DriveLeaderboardRow(UUID playerId, String playerName, int tasks, long totalPoints) {}
