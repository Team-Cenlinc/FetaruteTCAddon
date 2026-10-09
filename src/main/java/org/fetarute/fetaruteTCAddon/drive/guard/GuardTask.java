package org.fetarute.fetaruteTCAddon.drive.guard;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;

/** 一名玩家领取的一班车掌：从接班站上岗，值乘到这趟车次的终点站或交班站。只在服务器主线程使用。 */
public final class GuardTask {

  /** 在车掌任务板领取的任务的来源标记。 */
  public static final String SOURCE_BOARD = "board";

  /** 任务状态。 */
  public enum State {
    /** 已领取，等列车到站。 */
    CLAIMED,
    /** 值乘中。 */
    ON_DUTY,
    /** 值乘到终点站或交班站。 */
    COMPLETED,
    /** 车掌放弃或离开。 */
    ABANDONED,
    /** 列车没等到或已开走。 */
    EXPIRED,
    /** 连续超时、漏乘或换端没坐进车尾。 */
    FAILED,
    /** 管理员撤下、列车不在了或调度收回，不怪车掌。 */
    INTERRUPTED;

    /** 是否已结束。 */
    public boolean finished() {
      return this != CLAIMED && this != ON_DUTY;
    }
  }

  private final UUID taskId = UUID.randomUUID();
  private final UUID playerId;
  private final String playerName;
  private final DriverTaskManager.TaskSpec spec;
  private final Instant claimedAt;
  private State state = State.CLAIMED;
  private String trainName;
  private Instant startedAt;
  private int points = -1;
  private String grade = "";
  private String endReason = "";
  private boolean finishAnnounced;
  private String heldTrain;
  private Instant holdDeadline;
  private CabSeats.Departure heldDeparture;

  public GuardTask(
      UUID playerId, String playerName, DriverTaskManager.TaskSpec spec, Instant claimedAt) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.playerName = playerName == null ? "" : playerName;
    this.spec = Objects.requireNonNull(spec, "spec");
    this.claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
    this.trainName = spec.trainName();
  }

  /** 任务 ID：每次领取或派出都不同，供外部插件辨认。 */
  public UUID taskId() {
    return taskId;
  }

  public UUID playerId() {
    return playerId;
  }

  public String playerName() {
    return playerName;
  }

  public TaskKey key() {
    return spec.key();
  }

  public String routeCode() {
    return spec.routeCode() == null ? "" : spec.routeCode();
  }

  public String operatorCode() {
    return spec.operatorCode() == null ? "" : spec.operatorCode();
  }

  public String stationCode() {
    return spec.stationCode() == null ? "" : spec.stationCode();
  }

  public String stationName() {
    String name = spec.stationName();
    return name == null || name.isBlank() ? stationCode() : name;
  }

  /** 接班站在交路里的停靠序号；0 为始发站。 */
  public int takeoverStopSequence() {
    return spec.takeoverStopSequence();
  }

  public Instant plannedDeparture() {
    return spec.plannedDeparture();
  }

  public Instant claimedAt() {
    return claimedAt;
  }

  /** 交班站的停靠序号；值乘到终点站的任务为 -1。 */
  public int handoverStopSequence() {
    return spec.handoverStopSequence();
  }

  public String handoverStationCode() {
    return spec.handoverStationCode() == null ? "" : spec.handoverStationCode();
  }

  public String handoverStationName() {
    String name = spec.handoverStationName();
    return name == null || name.isBlank() ? handoverStationCode() : name;
  }

  /** 来源：车掌任务板为 {@link #SOURCE_BOARD}，插件派出的为调用方给的标记。 */
  public String source() {
    return spec.source() == null || spec.source().isBlank() ? SOURCE_BOARD : spec.source();
  }

  /** 调用方给的附加数据。 */
  public Map<String, String> metadata() {
    return spec.metadata();
  }

  /** 是否发车掌奖励：插件派任务时可关掉。 */
  public boolean rewards() {
    return spec.rewards();
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

  /** 上岗。始发站扣着的车留着扣车记录：派车放行之前别的候选车不能顶替车掌坐着的这一列。 */
  public void start(String train) {
    this.trainName = train;
    this.startedAt = Instant.now();
    this.state = State.ON_DUTY;
  }

  /** 上岗的时刻；还没上岗时为 {@code null}。 */
  public Instant startedAt() {
    return startedAt;
  }

  /** 记下这一趟的成绩。 */
  public void setResult(int points, String grade) {
    this.points = points;
    this.grade = grade == null ? "" : grade;
  }

  /** 得分；还没评分时为 -1。 */
  public int points() {
    return points;
  }

  public String grade() {
    return grade;
  }

  public String endReason() {
    return endReason;
  }

  /**
   * 结束任务；已结束的不再改变。
   *
   * @return 这一次是否真的结束了它
   */
  public boolean finish(State finalState, String reason) {
    if (state.finished() || finalState == null || !finalState.finished()) {
      return false;
    }
    state = finalState;
    endReason = reason == null ? "" : reason;
    clearHold();
    return true;
  }

  /** 任务结束只对外报一次：第一次调用返回 true。 */
  public boolean announceFinish() {
    if (finishAnnounced || !state.finished()) {
      return false;
    }
    finishAnnounced = true;
    return true;
  }

  /** 始发站扣着等车掌的列车（派车改名之前的车名）；没在扣时为 {@code null}。 */
  public String heldTrain() {
    return heldTrain;
  }

  /** 扣车等车掌的时限；没在扣时为 {@code null}。 */
  public Instant holdDeadline() {
    return holdDeadline;
  }

  /** 扣着的终点站待命车下一趟由哪一端发车：车掌坐它的另一头；没在扣时为 {@code null}。 */
  public CabSeats.Departure heldDeparture() {
    return heldDeparture;
  }

  /**
   * 始发站开始扣着这列车等车掌。
   *
   * @param departure 下一趟由哪一端发车；分不出时为 {@link CabSeats.Departure#EITHER}
   */
  public void hold(String train, Instant deadline, CabSeats.Departure departure) {
    this.heldTrain = train;
    this.holdDeadline = deadline;
    this.heldDeparture = departure == null ? CabSeats.Departure.EITHER : departure;
    this.trainName = train;
  }

  void clearHold() {
    this.heldTrain = null;
    this.holdDeadline = null;
    this.heldDeparture = null;
  }
}
