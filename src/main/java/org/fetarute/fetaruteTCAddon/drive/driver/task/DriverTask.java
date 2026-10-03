package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;

/** 一名玩家领取的一趟驾驶任务：从领取的车站开到这趟车次的终点站。只在服务器主线程使用。 */
public final class DriverTask {

  /** 任务状态。 */
  public enum State {
    /** 已领取，等列车到站。 */
    CLAIMED,
    /** 驾驶中。 */
    DRIVING,
    /** 开到终点站。 */
    COMPLETED,
    /** 驾驶员放弃或离开。 */
    ABANDONED,
    /** 列车没等到或已开走。 */
    EXPIRED,
    /** 卡住太久或超过任务时限，被收回。 */
    FAILED,
    /** 调度、管理员或熔断收回，不怪驾驶员。 */
    INTERRUPTED;

    /** 是否已结束。 */
    public boolean finished() {
      return this != CLAIMED && this != DRIVING;
    }
  }

  private final UUID playerId;
  private final String playerName;
  private final TaskKey key;
  private final String routeCode;
  private final String operatorCode;
  private final String stationCode;
  private final String stationName;
  private final String boardNodeId;
  private final int boardStopSequence;
  private final Instant plannedDeparture;
  private final Instant claimedAt;
  private DrivingMode mode;
  private State state = State.CLAIMED;
  private String trainName;
  private long startedTick = -1L;
  private String endReason = "";

  public DriverTask(
      UUID playerId,
      String playerName,
      TaskKey key,
      String routeCode,
      String operatorCode,
      String stationCode,
      String stationName,
      String boardNodeId,
      int boardStopSequence,
      Instant plannedDeparture,
      DrivingMode mode,
      Instant claimedAt) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.playerName = playerName == null ? "" : playerName;
    this.key = Objects.requireNonNull(key, "key");
    this.routeCode = routeCode == null ? "" : routeCode;
    this.operatorCode = operatorCode == null ? "" : operatorCode;
    this.stationCode = stationCode == null ? "" : stationCode;
    this.stationName =
        stationName == null || stationName.isBlank() ? this.stationCode : stationName;
    this.boardNodeId = boardNodeId;
    this.boardStopSequence = boardStopSequence;
    this.plannedDeparture = Objects.requireNonNull(plannedDeparture, "plannedDeparture");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
  }

  public UUID playerId() {
    return playerId;
  }

  public String playerName() {
    return playerName;
  }

  public TaskKey key() {
    return key;
  }

  public String routeCode() {
    return routeCode;
  }

  public String operatorCode() {
    return operatorCode;
  }

  public String stationCode() {
    return stationCode;
  }

  public String stationName() {
    return stationName;
  }

  /** 接班站台的节点；没有时为 {@code null}。 */
  public String boardNodeId() {
    return boardNodeId;
  }

  /** 接班站在交路里的停靠序号。 */
  public int boardStopSequence() {
    return boardStopSequence;
  }

  public Instant plannedDeparture() {
    return plannedDeparture;
  }

  public Instant claimedAt() {
    return claimedAt;
  }

  public DrivingMode mode() {
    return mode;
  }

  public void setMode(DrivingMode mode) {
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  public State state() {
    return state;
  }

  /** 担当这趟车次的列车；还没对上时为 {@code null}。 */
  public String trainName() {
    return trainName;
  }

  public void setTrainName(String trainName) {
    this.trainName = trainName;
  }

  /** 开始驾驶。 */
  public void start(String train, long nowTick) {
    this.trainName = train;
    this.startedTick = nowTick;
    this.state = State.DRIVING;
  }

  /** 开始驾驶的 tick；还没开始时为 -1。 */
  public long startedTick() {
    return startedTick;
  }

  /** 结束任务；已结束的不再改变。 */
  public void finish(State finalState, String reason) {
    if (state.finished() || finalState == null || !finalState.finished()) {
      return;
    }
    this.state = finalState;
    this.endReason = reason == null ? "" : reason;
  }

  public String endReason() {
    return endReason;
  }
}
