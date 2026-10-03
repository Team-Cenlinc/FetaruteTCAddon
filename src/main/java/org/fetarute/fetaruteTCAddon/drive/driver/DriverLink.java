package org.fetarute.fetaruteTCAddon.drive.driver;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Objects;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;

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
