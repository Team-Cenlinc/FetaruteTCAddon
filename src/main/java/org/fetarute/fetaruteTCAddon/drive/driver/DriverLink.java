package org.fetarute.fetaruteTCAddon.drive.driver;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopWindow;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;

/**
 * 一名驾驶员与一列调度列车之间的控制链路：调度层交来的最新指令、调度层的停车要求，以及保护包络最近一次的结论。
 *
 * <p>只在服务器主线程读写。
 */
public final class DriverLink {

  private final UUID playerId;
  private final String trainName;
  private final DoubleSupplier odometer;
  private final LongSupplier clock;
  private TrainProperties properties;
  private DrivingMode mode = DrivingMode.MANUAL;

  private DriverDirective directive;
  private long directiveTick;
  private double odometerAtDirective;
  private boolean serviceStopRequested;
  private boolean emergencyLatched;
  private String handbackReason;
  private DriverProtection.Decision lastDecision;

  private DriverStationStop stationStop;
  private NodeId completedStopNode;
  private StopWindow stopWindow = StopWindow.DEFAULTS;
  private NodeId approachNode;
  private boolean approachStation;
  private StopAlignment.Reference approachReference = StopAlignment.Reference.CENTER;
  private double approachRemainingAtSample = Double.NaN;
  private double odometerAtApproachSample;
  private long approachSampleTick;
  private Instant approachSampledAt;

  private DriverStationStop lastStop;
  private final SignalConfirm signalConfirm = new SignalConfirm();
  private final TaskScore score = new TaskScore();
  private int vigilanceTrips;
  private long stuckTicks;
  private long heldTicks;
  private double stuckOdometerAnchor;
  private DriverRescueLadder.Stage ladderStage = DriverRescueLadder.Stage.NONE;
  private long departureHoldSince = -1L;
  private long departureHoldQueriedAt = -1L;
  private boolean departureConfirmed;
  private int lateDepartures;

  private DriverDoorSide requiredDoorSide = DriverDoorSide.NONE;
  private String targetLabel = "";
  private String nextStopLabel = "";

  private int serviceInterventions;
  private int emergencyInterventions;
  private int forcedStops;

  /**
   * @param odometer 驾驶会话累计走过的距离（格）
   * @param clock 当前服务器 tick
   */
  public DriverLink(
      UUID playerId,
      String trainName,
      TrainProperties properties,
      DoubleSupplier odometer,
      LongSupplier clock) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.properties = properties;
    this.odometer = Objects.requireNonNull(odometer, "odometer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public UUID playerId() {
    return playerId;
  }

  public String trainName() {
    return trainName;
  }

  public TrainProperties properties() {
    return properties;
  }

  void rebind(TrainProperties newProperties) {
    this.properties = newProperties;
  }

  public DrivingMode mode() {
    return mode;
  }

  public void setMode(DrivingMode mode) {
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  /** 转为人工驾驶：ATO 期间调度层不向驾驶员下发行车许可，旧许可的距离与包络早已过时，清掉后由下一条指令重新开始 （没有指令时防护按限制速度、停着不许起步）。 */
  public void enterManual() {
    mode = DrivingMode.MANUAL;
    directive = null;
    lastDecision = null;
    signalConfirm.reset();
    clearDepartureHold();
    if (handbackReason == null) {
      serviceStopRequested = false;
    }
  }

  /** 转为 ATO：清掉人工驾驶时等着的信号确认与防护结论。 */
  public void enterAto() {
    mode = DrivingMode.ATO;
    lastDecision = null;
    signalConfirm.reset();
  }

  /** 驾驶员是否物理控车（ATO 下由自动运行代为操纵）。 */
  public boolean controlsPhysically() {
    return mode == DrivingMode.MANUAL;
  }

  /** 收到调度层的新指令。 */
  public void acceptDirective(DriverDirective newDirective) {
    this.directive = Objects.requireNonNull(newDirective, "directive");
    this.directiveTick = clock.getAsLong();
    this.odometerAtDirective = odometer.getAsDouble();
    if (!newDirective.isStop() && handbackReason == null) {
      serviceStopRequested = false;
    }
  }

  public DriverDirective directive() {
    return directive;
  }

  /** 收到最近一次指令后过了多少 tick；还没收到时为 {@link Long#MAX_VALUE}。 */
  public long ticksSinceDirective() {
    return directive == null ? Long.MAX_VALUE : Math.max(0L, clock.getAsLong() - directiveTick);
  }

  /** 收到最近一次指令后走了多远（格）。 */
  public double travelledSinceDirective() {
    return directive == null ? 0.0 : Math.max(0.0, odometer.getAsDouble() - odometerAtDirective);
  }

  /** 行车许可还允许车头往前走多远（格）：不是停车信号时为无穷大；停车信号按授权末端减去收到指令后走过的距离； 没有指令或停车信号没给距离时为 0。 */
  public double authorityAheadBlocks() {
    if (directive == null) {
      return 0.0;
    }
    if (!directive.isStop()) {
      return Double.POSITIVE_INFINITY;
    }
    return directive.distanceBlocks().isPresent()
        ? Math.max(0.0, directive.distanceBlocks().getAsLong() - travelledSinceDirective())
        : 0.0;
  }

  /** 站停的停车窗口（驾驶配置）。 */
  public StopWindow stopWindow() {
    return stopWindow;
  }

  public void setStopWindow(StopWindow window) {
    this.stopWindow = window == null ? StopWindow.DEFAULTS : window;
  }

  /** 调度层要求停车（自动运行下的立即停车）；收到下一条非停车指令时解除，已请求交还时不解除。 */
  public void requestServiceStop() {
    serviceStopRequested = true;
  }

  public boolean serviceStopRequested() {
    return serviceStopRequested;
  }

  /** 调度层要求紧急制动，停稳后解除。 */
  public void latchEmergency() {
    emergencyLatched = true;
  }

  public boolean emergencyLatched() {
    return emergencyLatched;
  }

  public void releaseEmergency() {
    emergencyLatched = false;
  }

  /** 请求停车后交还自动运行。 */
  public void requestHandback(String reason) {
    if (handbackReason == null) {
      handbackReason = reason == null ? "unknown" : reason;
    }
    serviceStopRequested = true;
  }

  public boolean handbackRequested() {
    return handbackReason != null;
  }

  public String handbackReason() {
    return handbackReason;
  }

  /** 记下保护包络的结论，并按介入方式由轻到重的变化计数。 */
  public void recordDecision(DriverProtection.Decision decision) {
    DriverProtection.Intervention previous =
        lastDecision == null ? DriverProtection.Intervention.NONE : lastDecision.intervention();
    DriverProtection.Intervention current = decision.intervention();
    if (current != previous && current.ordinal() > previous.ordinal()) {
      switch (current) {
        case SERVICE -> serviceInterventions++;
        case EMERGENCY -> emergencyInterventions++;
        case CLAMP -> forcedStops++;
        default -> {}
      }
    }
    lastDecision = decision;
  }

  public DriverProtection.Decision lastDecision() {
    return lastDecision;
  }

  /** 上一次评估是否处于常用制动介入。 */
  public boolean serviceLatched() {
    return lastDecision != null
        && lastDecision.intervention() == DriverProtection.Intervention.SERVICE;
  }

  /** 记一次立即停住（调度层的瞬间归零也算）。 */
  public void countForcedStop() {
    forcedStops++;
  }

  /**
   * 前方停车点：列车中心还能走多远（格）。
   *
   * @param node 车站或停车点节点
   * @param remainingBlocks 列车中心到停车点的距离；越过为负
   * @param precise 由站台按实际位置量出（进站后）；否则是按调度采样推算的估计
   * @param station 车站；否则是区间停车点（调度在车头到点时就地停车）
   * @param reference 用列车的哪个部位对准停车点：车站牌子是列车中心，停车位置标与区间停车点是车头
   */
  public record StationTarget(
      NodeId node,
      double remainingBlocks,
      boolean precise,
      boolean station,
      StopAlignment.Reference reference) {}

  /** 估计值超过这么久没有更新就不再使用。 */
  private static final long APPROACH_SAMPLE_MAX_AGE_TICKS = 100L;

  /** 站台交来一次停站。 */
  public void beginStationStop(DriverStationStop stop) {
    this.stationStop = Objects.requireNonNull(stop, "stop");
    stop.setWindow(stopWindow);
  }

  /** 进行中的停站；结束后清掉，并记住这一站，避免调度采样还没刷新时又把它当成前方停车点。 */
  public Optional<DriverStationStop> stationStop() {
    if (stationStop != null && !stationStop.active()) {
      completedStopNode = stationStop.node();
      if (completedStopNode.equals(approachNode)) {
        // 停过的这一站不再是前方停车点（调度采样要过一会儿才刷新）。
        approachNode = null;
        approachRemainingAtSample = Double.NaN;
      }
      lastStop = stationStop;
      score.addStop(StopScore.of(stationStop));
      stationStop = null;
    }
    return Optional.ofNullable(stationStop);
  }

  /**
   * 用调度层的诊断采样更新前方停车点的估计。
   *
   * @param node 前方停车节点；没有时为 {@code null}
   * @param kind 停车点类型；只认车站与区间停车点（{@code station}、{@code stop_waypoint}）
   * @param headDistanceBlocks 车头到停车节点的距离
   * @param sampledAt 采样时刻；与上次相同时不更新
   * @param halfLengthBlocks 车头到列车中心的距离
   */
  public void updateApproach(
      NodeId node,
      String kind,
      OptionalDouble headDistanceBlocks,
      Instant sampledAt,
      double halfLengthBlocks) {
    // 车站按列车中心对标；区间停车点由调度在车头到点时就地停车，按车头算。
    boolean station = "station".equals(kind);
    updateApproach(
        node,
        kind,
        headDistanceBlocks,
        sampledAt,
        station ? Math.max(0.0, halfLengthBlocks) : 0.0,
        station ? StopAlignment.Reference.CENTER : StopAlignment.Reference.HEAD);
  }

  /**
   * 用调度层的诊断采样更新前方停车点的估计，停车点相对停车节点另有偏移时用这个（例如停车位置标）。
   *
   * @param targetOffsetBlocks 停车点比车头到停车节点的距离多出多少（列车中心对标时为半个车长，车头对标时为标志在车站牌子前方的距离）
   * @param reference 用列车的哪个部位对准停车点
   */
  public void updateApproach(
      NodeId node,
      String kind,
      OptionalDouble headDistanceBlocks,
      Instant sampledAt,
      double targetOffsetBlocks,
      StopAlignment.Reference reference) {
    if (sampledAt == null || sampledAt.equals(approachSampledAt)) {
      return;
    }
    approachSampledAt = sampledAt;
    boolean stopKind = "station".equals(kind) || "stop_waypoint".equals(kind);
    if (node != null && !node.equals(completedStopNode)) {
      completedStopNode = null;
    }
    // 先结算已结束的停站，让刚停过的这一站进入 completedStopNode。
    stationStop();
    if (node == null
        || !stopKind
        || headDistanceBlocks == null
        || headDistanceBlocks.isEmpty()
        || node.equals(completedStopNode)) {
      approachNode = null;
      approachRemainingAtSample = Double.NaN;
      return;
    }
    approachNode = node;
    approachStation = "station".equals(kind);
    approachReference = reference == null ? StopAlignment.Reference.CENTER : reference;
    approachRemainingAtSample = headDistanceBlocks.getAsDouble() + targetOffsetBlocks;
    odometerAtApproachSample = odometer.getAsDouble();
    approachSampleTick = clock.getAsLong();
  }

  /** 前方停车点；进站后按站台量出的偏移，进站前按调度采样推算，都没有时为空。 */
  public Optional<StationTarget> stationTarget() {
    DriverStationStop stop = stationStop().orElse(null);
    if (stop == null) {
      return approachEstimate();
    }
    if (stop.phase() != DriverStationStop.Phase.APPROACH) {
      return Optional.empty();
    }
    if (Double.isFinite(stop.offsetBlocks())) {
      return Optional.of(
          new StationTarget(stop.node(), -stop.offsetBlocks(), true, true, stop.reference()));
    }
    // 站台还没量出偏移（刚交来停站，或量不出列车走向）：沿用进站前对这一站的估计。
    return approachEstimate().filter(target -> target.node().equals(stop.node()));
  }

  private Optional<StationTarget> approachEstimate() {
    if (approachNode == null
        || !Double.isFinite(approachRemainingAtSample)
        || clock.getAsLong() - approachSampleTick > APPROACH_SAMPLE_MAX_AGE_TICKS) {
      return Optional.empty();
    }
    double travelled = Math.max(0.0, odometer.getAsDouble() - odometerAtApproachSample);
    return Optional.of(
        new StationTarget(
            approachNode,
            approachRemainingAtSample - travelled,
            false,
            approachStation,
            approachReference));
  }

  /** 信号确认。 */
  public SignalConfirm signalConfirm() {
    return signalConfirm;
  }

  /** 本次驾驶的成绩明细（各站停站随停站结束记入）。 */
  public TaskScore score() {
    return score;
  }

  /** 警惕装置紧急制动一次。 */
  public void countVigilanceTrip() {
    vigilanceTrips++;
  }

  /** 把介入与确认的计数写进成绩明细。 */
  public TaskScore finalizeScore() {
    stationStop();
    score.setCounts(
        serviceInterventions,
        emergencyInterventions,
        forcedStops,
        signalConfirm.confirmations(),
        signalConfirm.misses(),
        signalConfirm.averageReactionSeconds(),
        vigilanceTrips,
        lateDepartures);
    return score;
  }

  /** 最近一次已结束的停站；还没停过时为空。 */
  public Optional<DriverStationStop> lastStop() {
    stationStop();
    return Optional.ofNullable(lastStop);
  }

  /** 走过这么远算有进展。 */
  private static final double PROGRESS_BLOCKS = 2.0;

  /**
   * 每 tick 记一次是否卡住：走了一段就清零；能走却没走（不在表定停站、不被调度扣住）时累计。
   *
   * @param held 此刻是表定停站或被调度扣住
   */
  public void tickStuck(boolean held) {
    double travelled = odometer.getAsDouble();
    if (travelled - stuckOdometerAnchor >= PROGRESS_BLOCKS) {
      stuckOdometerAnchor = travelled;
      stuckTicks = 0L;
      heldTicks = 0L;
      ladderStage = DriverRescueLadder.Stage.NONE;
      return;
    }
    if (held) {
      heldTicks++;
    } else {
      stuckTicks++;
    }
  }

  /** 没有前进、一直被扣住的累计秒数（交还给自动运行后由健康层的恢复手段接手）。 */
  public long heldSeconds() {
    return heldTicks / 20L;
  }

  /** 累计卡住的秒数。 */
  public long stuckSeconds() {
    return stuckTicks / 20L;
  }

  /** 时间阶梯已经走到的档位（每档的动作只做一次）。 */
  public DriverRescueLadder.Stage ladderStage() {
    return ladderStage;
  }

  public void setLadderStage(DriverRescueLadder.Stage stage) {
    this.ladderStage = Objects.requireNonNull(stage, "stage");
  }

  /** 两次询问相隔超过这么久，算作新的一次停站。 */
  private static final long DEPARTURE_QUERY_GAP_TICKS = 60L;

  /**
   * ATO 下站台问是否还要扣着等驾驶员确认发车。驾驶员确认过就放行；等太久也放行，记一次迟确认。
   *
   * @param timeoutTicks 最多等多久
   */
  public boolean holdDeparture(long timeoutTicks) {
    if (mode != DrivingMode.ATO) {
      return false;
    }
    long now = clock.getAsLong();
    if (departureHoldQueriedAt < 0L || now - departureHoldQueriedAt > DEPARTURE_QUERY_GAP_TICKS) {
      departureHoldSince = now;
      departureConfirmed = false;
    }
    departureHoldQueriedAt = now;
    if (departureConfirmed) {
      clearDepartureHold();
      return false;
    }
    if (now - departureHoldSince >= timeoutTicks) {
      clearDepartureHold();
      lateDepartures++;
      return false;
    }
    return true;
  }

  private void clearDepartureHold() {
    departureHoldSince = -1L;
    departureHoldQueriedAt = -1L;
    departureConfirmed = false;
  }

  /** 站台正在等驾驶员确认发车。 */
  public boolean departurePending() {
    return departureHoldSince >= 0L
        && clock.getAsLong() - departureHoldQueriedAt <= DEPARTURE_QUERY_GAP_TICKS;
  }

  /**
   * 驾驶员确认发车。
   *
   * @return 站台确实在等确认
   */
  public boolean confirmDeparture() {
    if (!departurePending()) {
      return false;
    }
    departureConfirmed = true;
    return true;
  }

  /** ATO 下超时未确认发车的次数。 */
  public int lateDepartures() {
    return lateDepartures;
  }

  /** 本站应开的门（驾驶会话按驾驶员朝向算好后写入，供显示）。 */
  public DriverDoorSide requiredDoorSide() {
    return requiredDoorSide;
  }

  public void setRequiredDoorSide(DriverDoorSide side) {
    this.requiredDoorSide = side == null ? DriverDoorSide.NONE : side;
  }

  /** 前方停车点的站名（显示用）；没有时为空串。 */
  public String targetLabel() {
    return targetLabel;
  }

  /** 按交路进度的下一个停靠站（与乘客 HUD 一致）；不明时为空串。 */
  public String nextStopLabel() {
    return nextStopLabel;
  }

  public void setNextStopLabel(String label) {
    this.nextStopLabel = label == null ? "" : label;
  }

  public void setTargetLabel(String label) {
    this.targetLabel = label == null ? "" : label;
  }

  public int serviceInterventions() {
    return serviceInterventions;
  }

  public int emergencyInterventions() {
    return emergencyInterventions;
  }

  public int forcedStops() {
    return forcedStops;
  }
}
