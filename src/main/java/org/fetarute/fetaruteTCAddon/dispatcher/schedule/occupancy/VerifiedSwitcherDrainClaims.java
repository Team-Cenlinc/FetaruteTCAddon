package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 解析已经由实体位置与规范行车计划共同认证、可在出清阶段忽略的抽象 switcher claims。
 *
 * <p>该模块只返回精确的 {@code CONFLICT:switcher:*} 资源集合，不决定列车是否可以运行。NODE、EDGE、single conflict、其他 switcher
 * 以及最终的 {@code canEnter/acquire} 复判始终由调用方按原规则执行。
 */
public final class VerifiedSwitcherDrainClaims {

  private static final String SWITCHER_CONFLICT_PREFIX = "switcher:";

  private VerifiedSwitcherDrainClaims() {}

  /**
   * 使用当前占用快照生成实体 switcher 出清证明，并在证明完整时晋升请求。
   *
   * <p>该方法不修改 claim、queue 或列车状态。若出口 NODE/EDGE、其他 switcher 存在外车占用，或实体 NODE
   * 所有权、行车计划、资源意图任一不成立，返回的请求保持不变。
   *
   * @param request 已完成版本标记的普通运行请求
   * @param snapshot 与本次判定绑定的 claim、占用版本与进度版本
   * @return 包含准备后请求、候选精确冲突与外部硬 blocker 的纯计算结果
   */
  public static Preparation prepare(OccupancyRequest request, VerificationSnapshot snapshot) {
    if (request == null
        || snapshot == null
        || request.purpose() != AuthorizationPurpose.RUNTIME_MOVE) {
      return Preparation.unchanged(request);
    }
    Optional<MovementPlanSnapshot> planOpt = request.movementPlanSnapshot();
    if (planOpt.isEmpty()) {
      return Preparation.unchanged(request);
    }
    MovementPlanSnapshot plan = planOpt.orElseThrow();
    if (plan.occupancyVersion() < 0
        || plan.progressVersion() < 0
        || plan.occupancyVersion() != snapshot.occupancyVersion()
        || plan.progressVersion() != snapshot.progressVersion()
        || !TrainNameNormalizer.sameLogicalTrain(plan.trainKey(), request.trainName())) {
      return Preparation.unchanged(request);
    }
    Set<OccupancyResource> candidates = new LinkedHashSet<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (structurallyMatches(request, plan, resource)
          && trainHoldsPhysicalNode(request.trainName(), resource, snapshot.claims())) {
        candidates.add(resource);
      }
    }
    if (candidates.isEmpty()) {
      return Preparation.unchanged(request);
    }
    Optional<OccupancyClaim> hardBlocker =
        DrainPathHardBlockers.firstExternalClaim(request, snapshot.claims(), candidates);
    if (hardBlocker.isPresent()) {
      return new Preparation(request, candidates, hardBlocker);
    }
    Map<String, ConflictReleaseHint> hints = new LinkedHashMap<>();
    for (OccupancyResource conflict : candidates) {
      hints.put(
          conflict.key(),
          ConflictReleaseHint.verifiedSwitcherOccupant(
              conflict.key(), "runtime-switcher-occupant-drain"));
    }
    OccupancyRequest prepared =
        request.withConflictReleaseHints(AuthorizationPurpose.CONFLICT_CLEARING, hints);
    Set<OccupancyResource> verified = resolve(prepared, snapshot);
    if (verified.isEmpty()) {
      return Preparation.unchanged(request);
    }
    return new Preparation(prepared, verified, Optional.empty());
  }

  /**
   * 从已经完成证据计算的授权请求中解析可忽略的同 key 抽象 switcher claims。
   *
   * <p>每个 key 都必须独立满足：请求已升级为冲突出清、版本已绑定、hint 为实体 switcher occupant 证据、MovementPlan 包含同 key
   * 路径签名、列车当前节点和计划起点均为该 switcher、第一条有向边从该节点驶向 effectiveTo，且请求把 switcher CONFLICT 与 NODE
   * 都列为硬授权资源。任何缺失都返回不包含该 key 的结果。
   *
   * <p>最终消费证明时必须重新提供当前快照。progress 版本必须仍与计划一致；occupancy 即使因同 key 抽象竞争 claim 后到而推进，也会重新验证实体 NODE
   * 所有权和完整硬路径，只有当前快照仍安全时才保留该精确 key。
   *
   * @param request 已完成版本标记与冲突证据计算的请求
   * @param snapshot 当前 claim、占用版本与可用的进度版本；进度版本传负数表示调用方不拥有该数据
   * @return 只包含精确可忽略抽象 switcher conflict 的不可变集合
   */
  public static Set<OccupancyResource> resolve(
      OccupancyRequest request, VerificationSnapshot snapshot) {
    if (request == null
        || snapshot == null
        || request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return Set.of();
    }
    Optional<MovementPlanSnapshot> planOpt = request.movementPlanSnapshot();
    if (planOpt.isEmpty()) {
      return Set.of();
    }
    MovementPlanSnapshot plan = planOpt.orElseThrow();
    if (plan.occupancyVersion() < 0
        || plan.progressVersion() < 0
        || snapshot.occupancyVersion() < 0
        || (snapshot.progressVersion() >= 0 && plan.progressVersion() != snapshot.progressVersion())
        || !TrainNameNormalizer.sameLogicalTrain(plan.trainKey(), request.trainName())) {
      return Set.of();
    }
    Set<OccupancyResource> resolved = new LinkedHashSet<>();
    for (Map.Entry<String, ConflictReleaseHint> entry : request.conflictReleaseHints().entrySet()) {
      resolveExactClaim(request, plan, entry).ifPresent(resolved::add);
    }
    resolved.removeIf(
        conflict -> !trainHoldsPhysicalNode(request.trainName(), conflict, snapshot.claims()));
    if (resolved.isEmpty()
        || DrainPathHardBlockers.firstExternalClaim(request, snapshot.claims(), resolved)
            .isPresent()) {
      return Set.of();
    }
    return Set.copyOf(resolved);
  }

  private static Optional<OccupancyResource> resolveExactClaim(
      OccupancyRequest request,
      MovementPlanSnapshot plan,
      Map.Entry<String, ConflictReleaseHint> entry) {
    String conflictKey = entry.getKey();
    ConflictReleaseHint hint = entry.getValue();
    if (conflictKey == null
        || !conflictKey.startsWith(SWITCHER_CONFLICT_PREFIX)
        || hint == null
        || hint.kind() != ConflictClearingEvidenceKind.VERIFIED_SWITCHER_OCCUPANT
        || !hint.verifiedFor(conflictKey)) {
      return Optional.empty();
    }
    DirectedTraversalContext.SwitcherPathSignature signature =
        plan.switcherPathSignatures().get(conflictKey);
    if (signature == null
        || !conflictKey.equals(signature.switcherKey())
        || !signature.pathNodes().equals(plan.expandedPathNodes())) {
      return Optional.empty();
    }
    Optional<NodeId> switcherNode = switcherNodeFromConflict(conflictKey);
    if (switcherNode.isEmpty()) {
      return Optional.empty();
    }
    NodeId nodeId = switcherNode.orElseThrow();
    if (plan.currentNode().filter(nodeId::equals).isEmpty()
        || plan.effectiveFromNode().filter(nodeId::equals).isEmpty()
        || signature.pathNodes().isEmpty()
        || !nodeId.equals(signature.pathNodes().get(0))
        || plan.directedEdges().isEmpty()) {
      return Optional.empty();
    }
    DirectedTraversalContext.DirectedEdge firstEdge = plan.directedEdges().get(0);
    EdgeId expectedExitEdge = EdgeId.undirected(nodeId, firstEdge.toNode());
    if (!nodeId.equals(firstEdge.fromNode())
        || nodeId.equals(firstEdge.toNode())
        || plan.effectiveToNode().filter(firstEdge.toNode()::equals).isEmpty()
        || !expectedExitEdge.equals(firstEdge.edgeId())) {
      return Optional.empty();
    }
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    OccupancyResource node = OccupancyResource.forNode(nodeId);
    OccupancyResource exitEdge = OccupancyResource.forEdge(expectedExitEdge);
    if (!request.resourceList().contains(conflict)
        || !request.intentFor(conflict).hardAuthority()
        || !request.resourceList().contains(node)
        || !request.intentFor(node).hardAuthority()
        || !request.resourceList().contains(exitEdge)
        || !request.intentFor(exitEdge).hardAuthority()) {
      return Optional.empty();
    }
    return Optional.of(conflict);
  }

  private static boolean structurallyMatches(
      OccupancyRequest request, MovementPlanSnapshot plan, OccupancyResource conflict) {
    if (conflict == null
        || conflict.kind() != ResourceKind.CONFLICT
        || !conflict.key().startsWith(SWITCHER_CONFLICT_PREFIX)
        || !request.intentFor(conflict).hardAuthority()) {
      return false;
    }
    ConflictReleaseHint synthetic =
        ConflictReleaseHint.verifiedSwitcherOccupant(conflict.key(), "structural-proof");
    OccupancyRequest candidate =
        request.withConflictReleaseHints(
            AuthorizationPurpose.CONFLICT_CLEARING, Map.of(conflict.key(), synthetic));
    return resolveExactClaim(candidate, plan, Map.entry(conflict.key(), synthetic)).isPresent();
  }

  private static boolean trainHoldsPhysicalNode(
      String trainName, OccupancyResource conflict, List<OccupancyClaim> claims) {
    Optional<NodeId> switcherNode = switcherNodeFromConflict(conflict.key());
    if (switcherNode.isEmpty()) {
      return false;
    }
    OccupancyResource node = OccupancyResource.forNode(switcherNode.orElseThrow());
    for (OccupancyClaim claim : claims) {
      if (claim == null
          || !node.equals(claim.resource())
          || !TrainNameNormalizer.sameLogicalTrain(trainName, claim.trainName())) {
        continue;
      }
      if (claim.role() == ClaimRole.MOVEMENT_REQUIRED
          || claim.role() == ClaimRole.PHYSICAL_FOOTPRINT
          || claim.role() == ClaimRole.PROTECTIVE_RETAIN
          || claim.role() == ClaimRole.HOLD_ONLY) {
        return true;
      }
    }
    return false;
  }

  private static Optional<NodeId> switcherNodeFromConflict(String conflictKey) {
    if (conflictKey == null || !conflictKey.startsWith(SWITCHER_CONFLICT_PREFIX)) {
      return Optional.empty();
    }
    String nodeValue = conflictKey.substring(SWITCHER_CONFLICT_PREFIX.length()).trim();
    if (nodeValue.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(NodeId.of(nodeValue));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  /**
   * 与一次证明计算绑定的占用与进度快照。
   *
   * @param claims 当前不可变 claim 列表
   * @param occupancyVersion 当前占用版本
   * @param progressVersion 当前运行进度版本
   */
  public record VerificationSnapshot(
      List<OccupancyClaim> claims, long occupancyVersion, long progressVersion) {

    public VerificationSnapshot {
      List<OccupancyClaim> sanitized = new ArrayList<>();
      if (claims != null) {
        for (OccupancyClaim claim : claims) {
          if (claim != null) {
            sanitized.add(claim);
          }
        }
      }
      claims = List.copyOf(sanitized);
    }
  }

  /**
   * switcher 出清证明准备结果。
   *
   * @param request 证明成功时已晋升的请求，否则为原请求
   * @param verifiedConflicts 已完成实体与路径证明的精确 switcher conflicts
   * @param hardBlocker 当前出口路径上的首个外车硬 blocker
   */
  public record Preparation(
      OccupancyRequest request,
      Set<OccupancyResource> verifiedConflicts,
      Optional<OccupancyClaim> hardBlocker) {

    public Preparation {
      verifiedConflicts = verifiedConflicts == null ? Set.of() : Set.copyOf(verifiedConflicts);
      hardBlocker = hardBlocker == null ? Optional.empty() : hardBlocker;
    }

    private static Preparation unchanged(OccupancyRequest request) {
      return new Preparation(request, Set.of(), Optional.empty());
    }

    /** 返回请求是否已生成至少一条完整的 switcher 出清授权。 */
    public boolean authorized() {
      return request != null
          && request.purpose() == AuthorizationPurpose.CONFLICT_CLEARING
          && hardBlocker.isEmpty()
          && !verifiedConflicts.isEmpty();
    }
  }
}
