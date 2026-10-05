package org.fetarute.fetaruteTCAddon.interlink;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 跨服整车移交的快照：对端据此原样生成列车、接上交路与时刻表、让乘客与驾驶员重新入座。
 *
 * @param handoffId 快照 ID（幂等键）
 * @param fromServer 发出方
 * @param toServer 接收方
 * @param grantId 边界占用的授予 ID
 * @param trainUid 列车全局 ID（{@code FTA_TRAIN_UID}）
 * @param trainName 列车名
 * @param boundaryNodeId 对端的边界节点（列车在那里出现）
 * @param groupConfig TrainCarts 编组配置（{@code MinecartGroup#saveConfig} 的 YAML），对端用 {@code
 *     SpawnableGroup} 重建
 * @param routeId 交路
 * @param routeIndex 交路进度
 * @param timetable 时刻表任务；没有时为空
 * @param driver 驾驶员；没有时为空
 * @param speedBps 过界时的车速
 * @param createdAt 生成时刻（发送方时钟，各服须对时）
 */
public record TrainHandoff(
    String handoffId,
    String fromServer,
    String toServer,
    String grantId,
    String trainUid,
    String trainName,
    String boundaryNodeId,
    String groupConfig,
    Optional<UUID> routeId,
    int routeIndex,
    Optional<Timetable> timetable,
    Optional<Driver> driver,
    double speedBps,
    Instant createdAt) {

  /**
   * 时刻表任务。
   *
   * @param timetableId 时刻表
   * @param tripCode 车次号
   * @param serviceDate 运营日
   * @param delaySeconds 当前晚点
   */
  public record Timetable(
      UUID timetableId, String tripCode, LocalDate serviceDate, long delaySeconds) {
    public Timetable {
      Objects.requireNonNull(timetableId, "timetableId");
      Objects.requireNonNull(tripCode, "tripCode");
      Objects.requireNonNull(serviceDate, "serviceDate");
    }
  }

  /**
   * 驾驶员与座位。
   *
   * @param playerId 驾驶员
   * @param memberIndex 所在车厢（从车头数起）
   * @param mode 驾驶方式（MANUAL / ATO）
   */
  public record Driver(UUID playerId, int memberIndex, String mode) {
    public Driver {
      Objects.requireNonNull(playerId, "playerId");
      mode = mode == null ? "MANUAL" : mode;
    }
  }

  public TrainHandoff {
    Objects.requireNonNull(handoffId, "handoffId");
    Objects.requireNonNull(fromServer, "fromServer");
    Objects.requireNonNull(toServer, "toServer");
    Objects.requireNonNull(trainUid, "trainUid");
    Objects.requireNonNull(boundaryNodeId, "boundaryNodeId");
    Objects.requireNonNull(groupConfig, "groupConfig");
    Objects.requireNonNull(createdAt, "createdAt");
    grantId = grantId == null ? "" : grantId;
    trainName = trainName == null ? "" : trainName;
    routeId = routeId == null ? Optional.empty() : routeId;
    timetable = timetable == null ? Optional.empty() : timetable;
    driver = driver == null ? Optional.empty() : driver;
  }
}
