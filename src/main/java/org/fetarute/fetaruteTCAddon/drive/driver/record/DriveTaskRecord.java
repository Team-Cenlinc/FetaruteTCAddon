package org.fetarute.fetaruteTCAddon.drive.driver.record;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 一趟已结束的驾驶任务的记录。
 *
 * @param id 记录 ID
 * @param serverId 产生记录的服务器（跨服预留）；单服时为 {@code null}
 * @param playerId 驾驶员
 * @param playerName 驾驶员名字（记录时）
 * @param timetableId 时刻表
 * @param tripCode 车次号
 * @param serviceDate 运营日
 * @param routeCode 交路代码
 * @param trainName 列车
 * @param mode 驾驶方式（MANUAL / ATO），车掌值乘为 {@link #MODE_GUARD}
 * @param state 任务终态
 * @param points 得分 0–100
 * @param grade 评级
 * @param startedAt 开始驾驶
 * @param finishedAt 结束
 * @param detailJson 明细（带 formatVersion 的 JSON）
 */
public record DriveTaskRecord(
    UUID id,
    String serverId,
    UUID playerId,
    String playerName,
    UUID timetableId,
    String tripCode,
    LocalDate serviceDate,
    String routeCode,
    String trainName,
    String mode,
    String state,
    int points,
    String grade,
    Instant startedAt,
    Instant finishedAt,
    String detailJson) {

  /** 车掌值乘的记录：不进驾驶排行与驾驶员的汇总。 */
  public static final String MODE_GUARD = "GUARD";

  public DriveTaskRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(timetableId, "timetableId");
    Objects.requireNonNull(serviceDate, "serviceDate");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(finishedAt, "finishedAt");
    playerName = playerName == null ? "" : playerName;
    tripCode = tripCode == null ? "" : tripCode;
    routeCode = routeCode == null ? "" : routeCode;
    trainName = trainName == null ? "" : trainName;
    mode = mode == null ? "" : mode;
    state = state == null ? "" : state;
    grade = grade == null ? "" : grade;
    detailJson = detailJson == null ? "{}" : detailJson;
  }
}
