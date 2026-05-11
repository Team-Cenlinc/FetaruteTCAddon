package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.time.Instant;
import java.util.List;
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
 *   <li>门控状态：route index、发车门控与最近信号来源
 *   <li>速度信息：当前速度、目标速度、建议速度、边限速
 *   <li>前瞻信息：到阻塞/限速/approaching 的距离
 *   <li>信号状态：当前信号、有效信号
 * </ul>
 *
 * @param trainName 列车名
 * @param routeId 当前线路（可选）
 * @param currentNode 当前节点
 * @param nextNode 下一节点（可选）
 * @param currentIndex 当前 route index，缺失时为 -1
 * @param departureGate 发车门控状态摘要
 * @param signalReason 最近一次控车信号来源
 * @param currentSpeedBps 当前速度（blocks/s）
 * @param targetSpeedBps 目标速度（巡航/限速）
 * @param edgeLimitBps 边限速（-1 表示无效/未找到边）
 * @param aspectBaseSpeedBps 信号等级映射出的基础速度（未叠加 approach/MA/前瞻曲线）
 * @param cautionSource CAUTION 速度来源：none/config/component
 * @param approachLimitBps 进站/进库/停靠点 approach 限速
 * @param movementAuthorityLimitBps 移动授权建议最大速度
 * @param edgeSpeedLookaheadMinBps 前方低限速边反推的当前最大速度
 * @param speedCurveLimitBps TrainLaunchManager 内部速度曲线限制后的速度
 * @param finalTargetBps 最终写入 TrainCarts speedLimit 前的目标速度（blocks/s）
 * @param finalLimiterSource 最终限速来源，也作为控车来源（controlSource）诊断字段
 * @param recommendedSpeedBps 建议速度（经 MotionPlanner 计算）
 * @param distanceToBlocker 到阻塞点距离（blocks）
 * @param distanceToCaution 到 CAUTION 区域距离
 * @param distanceToApproach 到 approaching 节点距离
 * @param distanceToAuthorityEnd 到授权窗口末端距离
 * @param authorityEndResource 第一处未授权资源边界摘要
 * @param authorizedEdgeCount 本次前向授权覆盖的实际图边数
 * @param approachNode 触发 approach 限速的节点
 * @param approachKind approach 类型：none/station/depot/stop_waypoint
 * @param approachReason approach 触发原因
 * @param currentSignal 当前信号
 * @param effectiveSignal 有效信号（考虑前瞻）
 * @param allowLaunch 是否允许发车
 * @param destinationPresentWhileBlocked 授权失败/STOP 时 TrainCarts destination 是否仍存在
 * @param retainedDestination 授权失败/STOP 时保留的 destination
 * @param blockedReason 授权失败/STOP 诊断原因
 * @param signalBlockerResources 本次信号判定中的 blocker 资源摘要
 * @param requestResources 本次前向授权请求资源摘要
 * @param currentClaimsForTrain 当前列车持有的占用资源摘要
 * @param sampledAt 采样时间
 */
public record ControlDiagnostics(
    String trainName,
    RouteId routeId,
    NodeId currentNode,
    NodeId nextNode,
    int currentIndex,
    String departureGate,
    String signalReason,
    double currentSpeedBps,
    double targetSpeedBps,
    double edgeLimitBps,
    double aspectBaseSpeedBps,
    String cautionSource,
    OptionalDouble approachLimitBps,
    OptionalDouble movementAuthorityLimitBps,
    OptionalDouble edgeSpeedLookaheadMinBps,
    OptionalDouble speedCurveLimitBps,
    double finalTargetBps,
    String finalLimiterSource,
    OptionalDouble recommendedSpeedBps,
    OptionalLong distanceToBlocker,
    OptionalLong distanceToCaution,
    OptionalLong distanceToApproach,
    OptionalLong distanceToAuthorityEnd,
    String authorityEndResource,
    int authorizedEdgeCount,
    NodeId approachNode,
    String approachKind,
    String approachReason,
    SignalAspect currentSignal,
    SignalAspect effectiveSignal,
    boolean allowLaunch,
    boolean destinationPresentWhileBlocked,
    String retainedDestination,
    String blockedReason,
    List<String> signalBlockerResources,
    List<String> requestResources,
    List<String> currentClaimsForTrain,
    Instant sampledAt) {

  public ControlDiagnostics {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(cautionSource, "cautionSource");
    Objects.requireNonNull(approachLimitBps, "approachLimitBps");
    Objects.requireNonNull(movementAuthorityLimitBps, "movementAuthorityLimitBps");
    Objects.requireNonNull(edgeSpeedLookaheadMinBps, "edgeSpeedLookaheadMinBps");
    Objects.requireNonNull(speedCurveLimitBps, "speedCurveLimitBps");
    Objects.requireNonNull(finalLimiterSource, "finalLimiterSource");
    Objects.requireNonNull(recommendedSpeedBps, "recommendedSpeedBps");
    Objects.requireNonNull(distanceToBlocker, "distanceToBlocker");
    Objects.requireNonNull(distanceToCaution, "distanceToCaution");
    Objects.requireNonNull(distanceToApproach, "distanceToApproach");
    Objects.requireNonNull(distanceToAuthorityEnd, "distanceToAuthorityEnd");
    authorityEndResource =
        authorityEndResource == null || authorityEndResource.isBlank()
            ? "none"
            : authorityEndResource.trim();
    authorizedEdgeCount = Math.max(0, authorizedEdgeCount);
    approachKind = approachKind == null || approachKind.isBlank() ? "none" : approachKind.trim();
    approachReason =
        approachReason == null || approachReason.isBlank() ? "none" : approachReason.trim();
    Objects.requireNonNull(currentSignal, "currentSignal");
    Objects.requireNonNull(effectiveSignal, "effectiveSignal");
    signalBlockerResources =
        signalBlockerResources == null ? List.of() : List.copyOf(signalBlockerResources);
    requestResources = requestResources == null ? List.of() : List.copyOf(requestResources);
    currentClaimsForTrain =
        currentClaimsForTrain == null ? List.of() : List.copyOf(currentClaimsForTrain);
    departureGate =
        departureGate == null || departureGate.isBlank() ? "none" : departureGate.trim();
    signalReason = signalReason == null || signalReason.isBlank() ? "none" : signalReason.trim();
    retainedDestination =
        retainedDestination == null || retainedDestination.isBlank()
            ? "-"
            : retainedDestination.trim();
    blockedReason =
        blockedReason == null || blockedReason.isBlank() ? "none" : blockedReason.trim();
    Objects.requireNonNull(sampledAt, "sampledAt");
  }

  /** 获取到最近约束点的距离（取三种距离的最小值）。 */
  public OptionalLong minConstraintDistance() {
    return minStopConstraintDistance();
  }

  /**
   * 控车来源别名。
   *
   * <p>保留 {@link #finalLimiterSource()} 作为历史字段，同时提供更贴近运行时边界审计的 controlSource 语义。
   */
  public String controlSource() {
    return finalLimiterSource();
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
    private int currentIndex = -1;
    private String departureGate = "none";
    private String signalReason = "none";
    private double currentSpeedBps;
    private double targetSpeedBps;
    private double edgeLimitBps = -1.0;
    private double aspectBaseSpeedBps;
    private String cautionSource = "none";
    private OptionalDouble approachLimitBps = OptionalDouble.empty();
    private OptionalDouble movementAuthorityLimitBps = OptionalDouble.empty();
    private OptionalDouble edgeSpeedLookaheadMinBps = OptionalDouble.empty();
    private OptionalDouble speedCurveLimitBps = OptionalDouble.empty();
    private double finalTargetBps;
    private String finalLimiterSource = "none";
    private OptionalDouble recommendedSpeedBps = OptionalDouble.empty();
    private OptionalLong distanceToBlocker = OptionalLong.empty();
    private OptionalLong distanceToCaution = OptionalLong.empty();
    private OptionalLong distanceToApproach = OptionalLong.empty();
    private OptionalLong distanceToAuthorityEnd = OptionalLong.empty();
    private String authorityEndResource = "none";
    private int authorizedEdgeCount;
    private NodeId approachNode;
    private String approachKind = "none";
    private String approachReason = "none";
    private SignalAspect currentSignal = SignalAspect.STOP;
    private SignalAspect effectiveSignal = SignalAspect.STOP;
    private boolean allowLaunch;
    private boolean destinationPresentWhileBlocked;
    private String retainedDestination = "-";
    private String blockedReason = "none";
    private List<String> signalBlockerResources = List.of();
    private List<String> requestResources = List.of();
    private List<String> currentClaimsForTrain = List.of();
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

    public Builder currentIndex(int currentIndex) {
      this.currentIndex = currentIndex;
      return this;
    }

    public Builder departureGate(String departureGate) {
      this.departureGate =
          departureGate == null || departureGate.isBlank() ? "none" : departureGate.trim();
      return this;
    }

    public Builder signalReason(String signalReason) {
      this.signalReason =
          signalReason == null || signalReason.isBlank() ? "none" : signalReason.trim();
      return this;
    }

    public Builder currentSpeedBps(double currentSpeedBps) {
      this.currentSpeedBps = currentSpeedBps;
      return this;
    }

    public Builder targetSpeedBps(double targetSpeedBps) {
      this.targetSpeedBps = targetSpeedBps;
      this.finalTargetBps = targetSpeedBps;
      return this;
    }

    public Builder edgeLimitBps(double edgeLimitBps) {
      this.edgeLimitBps = edgeLimitBps;
      return this;
    }

    public Builder aspectBaseSpeedBps(double aspectBaseSpeedBps) {
      this.aspectBaseSpeedBps = aspectBaseSpeedBps;
      return this;
    }

    public Builder cautionSource(String cautionSource) {
      this.cautionSource =
          cautionSource == null || cautionSource.isBlank() ? "none" : cautionSource.trim();
      return this;
    }

    public Builder approachLimitBps(OptionalDouble approachLimitBps) {
      this.approachLimitBps = approachLimitBps != null ? approachLimitBps : OptionalDouble.empty();
      return this;
    }

    public Builder movementAuthorityLimitBps(OptionalDouble movementAuthorityLimitBps) {
      this.movementAuthorityLimitBps =
          movementAuthorityLimitBps != null ? movementAuthorityLimitBps : OptionalDouble.empty();
      return this;
    }

    public Builder edgeSpeedLookaheadMinBps(OptionalDouble edgeSpeedLookaheadMinBps) {
      this.edgeSpeedLookaheadMinBps =
          edgeSpeedLookaheadMinBps != null ? edgeSpeedLookaheadMinBps : OptionalDouble.empty();
      return this;
    }

    public Builder speedCurveLimitBps(OptionalDouble speedCurveLimitBps) {
      this.speedCurveLimitBps =
          speedCurveLimitBps != null ? speedCurveLimitBps : OptionalDouble.empty();
      return this;
    }

    public Builder finalTargetBps(double finalTargetBps) {
      this.finalTargetBps = finalTargetBps;
      return this;
    }

    public Builder finalLimiterSource(String finalLimiterSource) {
      this.finalLimiterSource =
          finalLimiterSource == null || finalLimiterSource.isBlank()
              ? "none"
              : finalLimiterSource.trim();
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

    public Builder distanceToAuthorityEnd(OptionalLong distanceToAuthorityEnd) {
      this.distanceToAuthorityEnd =
          distanceToAuthorityEnd != null ? distanceToAuthorityEnd : OptionalLong.empty();
      return this;
    }

    public Builder authorityEndResource(String authorityEndResource) {
      this.authorityEndResource =
          authorityEndResource == null || authorityEndResource.isBlank()
              ? "none"
              : authorityEndResource.trim();
      return this;
    }

    public Builder authorizedEdgeCount(int authorizedEdgeCount) {
      this.authorizedEdgeCount = Math.max(0, authorizedEdgeCount);
      return this;
    }

    public Builder approachNode(NodeId approachNode) {
      this.approachNode = approachNode;
      return this;
    }

    public Builder approachKind(String approachKind) {
      this.approachKind =
          approachKind == null || approachKind.isBlank() ? "none" : approachKind.trim();
      return this;
    }

    public Builder approachReason(String approachReason) {
      this.approachReason =
          approachReason == null || approachReason.isBlank() ? "none" : approachReason.trim();
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

    public Builder destinationPresentWhileBlocked(boolean destinationPresentWhileBlocked) {
      this.destinationPresentWhileBlocked = destinationPresentWhileBlocked;
      return this;
    }

    public Builder retainedDestination(String retainedDestination) {
      this.retainedDestination =
          retainedDestination == null || retainedDestination.isBlank()
              ? "-"
              : retainedDestination.trim();
      return this;
    }

    public Builder blockedReason(String blockedReason) {
      this.blockedReason =
          blockedReason == null || blockedReason.isBlank() ? "none" : blockedReason.trim();
      return this;
    }

    public Builder signalBlockerResources(List<String> signalBlockerResources) {
      this.signalBlockerResources =
          signalBlockerResources == null ? List.of() : List.copyOf(signalBlockerResources);
      return this;
    }

    public Builder requestResources(List<String> requestResources) {
      this.requestResources = requestResources == null ? List.of() : List.copyOf(requestResources);
      return this;
    }

    public Builder currentClaimsForTrain(List<String> currentClaimsForTrain) {
      this.currentClaimsForTrain =
          currentClaimsForTrain == null ? List.of() : List.copyOf(currentClaimsForTrain);
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
          currentIndex,
          departureGate,
          signalReason,
          currentSpeedBps,
          targetSpeedBps,
          edgeLimitBps,
          aspectBaseSpeedBps,
          cautionSource,
          approachLimitBps,
          movementAuthorityLimitBps,
          edgeSpeedLookaheadMinBps,
          speedCurveLimitBps,
          finalTargetBps,
          finalLimiterSource,
          recommendedSpeedBps,
          distanceToBlocker,
          distanceToCaution,
          distanceToApproach,
          distanceToAuthorityEnd,
          authorityEndResource,
          authorizedEdgeCount,
          approachNode,
          approachKind,
          approachReason,
          currentSignal,
          effectiveSignal,
          allowLaunch,
          destinationPresentWhileBlocked,
          retainedDestination,
          blockedReason,
          signalBlockerResources,
          requestResources,
          currentClaimsForTrain,
          sampledAt);
    }
  }
}
