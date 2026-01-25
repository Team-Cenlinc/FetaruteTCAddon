package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 控车诊断数据：记录列车当前控车状态的快照。
 *
 * <p>用于 {@code /fta train debug} 命令显示实时控车信息。
 *
 * <p>数据来源：
 *
 * <ul>
 *   <li>基础状态：列车名、线路、当前/下一节点
 *   <li>速度信息：当前速度、目标速度、建议速度、边限速
 *   <li>前瞻信息：到阻塞/限速/approaching 的距离
 *   <li>信号状态：当前信号、有效信号
 * </ul>
 *
 * @param trainName 列车名
 * @param routeId 当前线路（可选）
 * @param currentNode 当前节点
 * @param nextNode 下一节点（可选）
 * @param currentSpeedBps 当前速度（blocks/s）
 * @param targetSpeedBps 目标速度（巡航/限速）
 * @param edgeLimitBps 边限速（-1 表示无效/未找到边）
 * @param recommendedSpeedBps 建议速度（经 MotionPlanner 计算）
 * @param distanceToBlocker 到阻塞点距离（blocks）
 * @param distanceToCaution 到 CAUTION 区域距离
 * @param distanceToApproach 到 approaching 节点距离
 * @param currentSignal 当前信号
 * @param effectiveSignal 有效信号（考虑前瞻）
 * @param allowLaunch 是否允许发车
 * @param sampledAt 采样时间
 */
public record ControlDiagnostics(
    String trainName,
    RouteId routeId,
    NodeId currentNode,
    NodeId nextNode,
    double currentSpeedBps,
    double targetSpeedBps,
    double edgeLimitBps,
    OptionalDouble recommendedSpeedBps,
    OptionalLong distanceToBlocker,
    OptionalLong distanceToCaution,
    OptionalLong distanceToApproach,
    SignalAspect currentSignal,
    SignalAspect effectiveSignal,
    boolean allowLaunch,
    Instant sampledAt) {

  public ControlDiagnostics {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(recommendedSpeedBps, "recommendedSpeedBps");
    Objects.requireNonNull(distanceToBlocker, "distanceToBlocker");
    Objects.requireNonNull(distanceToCaution, "distanceToCaution");
    Objects.requireNonNull(distanceToApproach, "distanceToApproach");
    Objects.requireNonNull(currentSignal, "currentSignal");
    Objects.requireNonNull(effectiveSignal, "effectiveSignal");
    Objects.requireNonNull(sampledAt, "sampledAt");
  }

  /** 获取到最近约束点的距离（取三种距离的最小值）。 */
  public OptionalLong minConstraintDistance() {
    return minStopConstraintDistance();
  }

  /**
   * 获取到最近停车约束点的距离（blocker/caution，不含 approaching）。
   *
   * <p>与 {@link SignalLookahead.LookaheadResult#minStopConstraintDistance()} 保持一致。
   */
  public OptionalLong minStopConstraintDistance() {
    long min = Long.MAX_VALUE;
    if (distanceToBlocker.isPresent()) {
      min = Math.min(min, distanceToBlocker.getAsLong());
    }
    if (distanceToCaution.isPresent()) {
      min = Math.min(min, distanceToCaution.getAsLong());
    }
    // 不包含 distanceToApproach
    return min == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(min);
  }

  /** Builder 便于逐步构建诊断数据。 */
  public static final class Builder {
    private String trainName;
    private RouteId routeId;
    private NodeId currentNode;
    private NodeId nextNode;
    private double currentSpeedBps;
    private double targetSpeedBps;
    private double edgeLimitBps = -1.0;
    private OptionalDouble recommendedSpeedBps = OptionalDouble.empty();
    private OptionalLong distanceToBlocker = OptionalLong.empty();
    private OptionalLong distanceToCaution = OptionalLong.empty();
    private OptionalLong distanceToApproach = OptionalLong.empty();
    private SignalAspect currentSignal = SignalAspect.STOP;
    private SignalAspect effectiveSignal = SignalAspect.STOP;
    private boolean allowLaunch;
    private Instant sampledAt = Instant.now();

    public Builder trainName(String trainName) {
      this.trainName = trainName;
      return this;
    }

    public Builder routeId(RouteId routeId) {
      this.routeId = routeId;
      return this;
    }

    public Builder currentNode(NodeId currentNode) {
      this.currentNode = currentNode;
      return this;
    }

    public Builder nextNode(NodeId nextNode) {
      this.nextNode = nextNode;
      return this;
    }

    public Builder currentSpeedBps(double currentSpeedBps) {
      this.currentSpeedBps = currentSpeedBps;
      return this;
    }

    public Builder targetSpeedBps(double targetSpeedBps) {
      this.targetSpeedBps = targetSpeedBps;
      return this;
    }

    public Builder edgeLimitBps(double edgeLimitBps) {
      this.edgeLimitBps = edgeLimitBps;
      return this;
    }

    public Builder recommendedSpeedBps(double recommendedSpeedBps) {
      this.recommendedSpeedBps = OptionalDouble.of(recommendedSpeedBps);
      return this;
    }

    public Builder recommendedSpeedBps(OptionalDouble recommendedSpeedBps) {
      this.recommendedSpeedBps =
          recommendedSpeedBps != null ? recommendedSpeedBps : OptionalDouble.empty();
      return this;
    }

    public Builder lookahead(SignalLookahead.LookaheadResult lookahead) {
      if (lookahead != null) {
        this.distanceToBlocker = lookahead.distanceToBlocker();
        this.distanceToCaution = lookahead.distanceToCaution();
        this.distanceToApproach = lookahead.distanceToApproach();
        this.effectiveSignal = lookahead.effectiveSignal();
      }
      return this;
    }

    public Builder distanceToBlocker(OptionalLong distanceToBlocker) {
      this.distanceToBlocker = distanceToBlocker != null ? distanceToBlocker : OptionalLong.empty();
      return this;
    }

    public Builder distanceToCaution(OptionalLong distanceToCaution) {
      this.distanceToCaution = distanceToCaution != null ? distanceToCaution : OptionalLong.empty();
      return this;
    }

    public Builder distanceToApproach(OptionalLong distanceToApproach) {
      this.distanceToApproach =
          distanceToApproach != null ? distanceToApproach : OptionalLong.empty();
      return this;
    }

    public Builder currentSignal(SignalAspect currentSignal) {
      this.currentSignal = currentSignal != null ? currentSignal : SignalAspect.STOP;
      return this;
    }

    public Builder effectiveSignal(SignalAspect effectiveSignal) {
      this.effectiveSignal = effectiveSignal != null ? effectiveSignal : SignalAspect.STOP;
      return this;
    }

    public Builder allowLaunch(boolean allowLaunch) {
      this.allowLaunch = allowLaunch;
      return this;
    }

    public Builder sampledAt(Instant sampledAt) {
      this.sampledAt = sampledAt != null ? sampledAt : Instant.now();
      return this;
    }

    public ControlDiagnostics build() {
      return new ControlDiagnostics(
          trainName,
          routeId,
          currentNode,
          nextNode,
          currentSpeedBps,
          targetSpeedBps,
          edgeLimitBps,
          recommendedSpeedBps,
          distanceToBlocker,
          distanceToCaution,
          distanceToApproach,
          currentSignal,
          effectiveSignal,
          allowLaunch,
          sampledAt);
    }
  }
}
