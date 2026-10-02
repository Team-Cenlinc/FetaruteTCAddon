package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 由规范 {@link MovementPlanSnapshot} 派生的通用前向路径证明。
 *
 * <p>该证明只说明“这辆列车当前计划从哪个节点沿哪些有向边前往哪个节点”，供 Smart Dispatcher 判断 NODE/EDGE
 * 等无二值走廊方向资源是否存在可重评估的前向动作。它不等价于单线 {@code A_TO_B/B_TO_A} 方向、不能授权 head-on 让行，也不能直接签发 Movement
 * Authority；最终移动仍必须由下一 tick 的完整信号链重新申请全部硬资源。
 *
 * @param trainKey 规范化列车键
 * @param routeId 计划 route id；缺失 route 的快照不得派生证明
 * @param routeIndex 生成计划时的 route index
 * @param fromNode 本次计划的有效起点
 * @param toNode 本次计划的首个前向目标
 * @param lastPassedGraphNode 最近经过的图节点；缺失时为 {@code -}
 * @param directedSteps 完整展开路径的有向边链
 * @param movementRequiredResources 当前计划明确要求的前进硬资源
 * @param forwardProgressReleaseResources 已由运行时实时验证、会随规范前进收缩的自持有保护性尾部资源
 * @param occupancyVersion 生成计划时观察到的占用版本
 * @param progressVersion 生成计划时观察到的进度版本
 * @param requestId 规范请求 id
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "构造器已把三组 List 复制为不可变快照；DirectedStep 与 String 也均为不可变值。")
public record CanonicalForwardPathEvidence(
    String trainKey,
    String routeId,
    int routeIndex,
    String fromNode,
    String toNode,
    String lastPassedGraphNode,
    List<DirectedStep> directedSteps,
    List<String> movementRequiredResources,
    List<String> forwardProgressReleaseResources,
    long occupancyVersion,
    long progressVersion,
    String requestId) {

  public CanonicalForwardPathEvidence {
    trainKey = normalize(trainKey, "-");
    routeId = normalize(routeId, "-");
    fromNode = normalize(fromNode, "-");
    toNode = normalize(toNode, "-");
    lastPassedGraphNode = normalize(lastPassedGraphNode, "-");
    directedSteps = directedSteps == null ? List.of() : List.copyOf(directedSteps);
    movementRequiredResources = sortedResources(movementRequiredResources);
    forwardProgressReleaseResources = sortedNodeEdgeResources(forwardProgressReleaseResources);
    requestId = normalize(requestId, "-");
  }

  /** 保留旧调用点的便捷构造器；没有实时 rear-retain 校验时不得自行声明尾部收缩资源。 */
  public CanonicalForwardPathEvidence(
      String trainKey,
      String routeId,
      int routeIndex,
      String fromNode,
      String toNode,
      String lastPassedGraphNode,
      List<DirectedStep> directedSteps,
      List<String> movementRequiredResources,
      long occupancyVersion,
      long progressVersion,
      String requestId) {
    this(
        trainKey,
        routeId,
        routeIndex,
        fromNode,
        toNode,
        lastPassedGraphNode,
        directedSteps,
        movementRequiredResources,
        List.of(),
        occupancyVersion,
        progressVersion,
        requestId);
  }

  /**
   * 从规范计划派生证明，并对路径节点、有向边、物理 edge id 与有效边界做全链一致性校验。
   *
   * <p>该方法不检查 TTL 或当前运行进度；调用方必须先在自己的快照时钟和进度窗口内完成 freshness 校验。
   *
   * @param expectedTrain 预期使用该计划的列车
   * @param snapshot 规范计划快照
   * @return 派生结果；失败时包含稳定原因码且不返回半有效证明
   */
  public static Derivation derive(String expectedTrain, MovementPlanSnapshot snapshot) {
    if (snapshot == null) {
      return Derivation.rejected("MOVEMENT_PLAN_MISSING");
    }
    String expectedKey = TrainNameNormalizer.normalizeKey(expectedTrain);
    if (expectedKey.isBlank()
        || snapshot.trainKey().isBlank()
        || !expectedKey.equals(snapshot.trainKey())) {
      return Derivation.rejected("PLAN_TRAIN_MISMATCH");
    }
    if (snapshot.routeId().isEmpty() || snapshot.routeId().orElseThrow().value().isBlank()) {
      return Derivation.rejected("PLAN_ROUTE_ID_MISSING");
    }
    if (snapshot.routeIndex() < 0) {
      return Derivation.rejected("PLAN_ROUTE_INDEX_MISSING");
    }
    if (snapshot.requestId().isBlank() || snapshot.requestId().equals("-")) {
      return Derivation.rejected("PLAN_REQUEST_ID_MISSING");
    }
    if (snapshot.movementRequiredResources().isEmpty()) {
      return Derivation.rejected("PLAN_MOVEMENT_WINDOW_EMPTY");
    }
    Optional<NodeId> fromNode = snapshot.effectiveFromNode();
    Optional<NodeId> toNode = snapshot.effectiveToNode();
    if (fromNode.isEmpty() || toNode.isEmpty()) {
      return Derivation.rejected("PLAN_EFFECTIVE_BOUNDARY_MISSING");
    }
    if (fromNode.get().equals(toNode.get())) {
      return Derivation.rejected("PLAN_FORWARD_BOUNDARY_COLLAPSED");
    }
    List<NodeId> nodes = snapshot.expandedPathNodes();
    List<DirectedTraversalContext.DirectedEdge> edges = snapshot.directedEdges();
    if (nodes.size() < 2) {
      return Derivation.rejected("PLAN_PATH_TOO_SHORT");
    }
    if (edges.size() != nodes.size() - 1) {
      return Derivation.rejected("PLAN_PATH_EDGE_COUNT_MISMATCH");
    }
    if (!nodes.get(0).equals(fromNode.get()) || !nodes.get(1).equals(toNode.get())) {
      return Derivation.rejected("PLAN_EFFECTIVE_BOUNDARY_MISMATCH");
    }
    List<DirectedStep> steps = new ArrayList<>(edges.size());
    for (int index = 0; index < edges.size(); index++) {
      NodeId expectedFrom = nodes.get(index);
      NodeId expectedTo = nodes.get(index + 1);
      DirectedTraversalContext.DirectedEdge edge = edges.get(index);
      if (edge == null
          || !edge.fromNode().equals(expectedFrom)
          || !edge.toNode().equals(expectedTo)
          || !edge.edgeId().equals(EdgeId.undirected(expectedFrom, expectedTo))) {
        return Derivation.rejected("PLAN_DIRECTED_EDGE_MISMATCH");
      }
      steps.add(new DirectedStep(edge.edgeId(), edge.fromNode(), edge.toNode()));
    }
    if (!movementWindowMatchesPath(snapshot.movementRequiredResources(), nodes, steps)) {
      return Derivation.rejected("PLAN_MOVEMENT_WINDOW_PATH_MISMATCH");
    }
    CanonicalForwardPathEvidence evidence =
        new CanonicalForwardPathEvidence(
            snapshot.trainKey(),
            snapshot.routeId().orElseThrow().value(),
            snapshot.routeIndex(),
            fromNode.get().value(),
            toNode.get().value(),
            snapshot.lastPassedGraphNode().map(NodeId::value).orElse("-"),
            steps,
            snapshot.movementRequiredResources().stream().map(Object::toString).toList(),
            List.of(),
            snapshot.occupancyVersion(),
            snapshot.progressVersion(),
            snapshot.requestId());
    return new Derivation(Optional.of(evidence), "-");
  }

  /** 判断该证明是否精确对应候选列车及其当前前向节点窗口。 */
  public boolean proves(String candidateTrain, String candidateFromNode, String candidateToNode) {
    return trainKey.equals(TrainNameNormalizer.normalizeKey(candidateTrain))
        && fromNode.equals(normalize(candidateFromNode, "-"))
        && toNode.equals(normalize(candidateToNode, "-"))
        && hasValidDirectedChain();
  }

  /**
   * 判断规范计划是否实际覆盖 planner 预计通过前进释放的 NODE/EDGE blocker。
   *
   * <p>当前硬窗口中的资源直接成立；展开路径上的节点/边也可成立。路径外的 rear claim、其他列车资源或任意 conflict
   * 均拒绝，避免把“列车有一条前向路径”误当成“该路径会释放任意 blocker”。
   */
  public boolean coversNodeOrEdgeResource(String resource) {
    String normalized = normalize(resource, "-");
    if ((!normalized.startsWith("NODE:") && !normalized.startsWith("EDGE:"))
        || !hasValidDirectedChain()) {
      return false;
    }
    if (normalized.equals("NODE:" + fromNode)) {
      return true;
    }
    for (DirectedStep step : directedSteps) {
      if (normalized.equals("NODE:" + step.toNode().value())
          || normalized.equals("EDGE:" + resourceKey(step.edgeId()))) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断候选 blocker 是否会由本次规范前进直接覆盖或随保护性尾部窗口收缩。
   *
   * <p>{@link #coversNodeOrEdgeResource(String)} 始终保持纯前向语义。只有运行时把实时、自持有、同 route 的 {@code
   * PROTECTIVE_RETAIN} 与 builder 显式验证的 {@code canonicalRearRetainPathPlan} 末端锚点之前的资源取交集后，才可通过此入口承认
   * rear claim；任意路径外、非保护性或已消失的 claim 均不成立。
   */
  public boolean coversReleaseResource(String resource) {
    String normalized = normalize(resource, "-");
    return coversNodeOrEdgeResource(normalized)
        || forwardProgressReleaseResources.contains(normalized);
  }

  /** 返回附加实时保护性尾部验证后的不可变证明。 */
  public CanonicalForwardPathEvidence withForwardProgressReleaseResources(
      List<String> releaseResources) {
    return new CanonicalForwardPathEvidence(
        trainKey,
        routeId,
        routeIndex,
        fromNode,
        toNode,
        lastPassedGraphNode,
        directedSteps,
        movementRequiredResources,
        releaseResources,
        occupancyVersion,
        progressVersion,
        requestId);
  }

  /** 返回诊断 trace 使用的证据类型。 */
  public String evidenceKind() {
    return forwardProgressReleaseResources.isEmpty()
        ? "CANONICAL_MOVEMENT_PLAN"
        : "CANONICAL_MOVEMENT_PLAN_WITH_REAR_RETAIN";
  }

  /**
   * 返回可进入 plan hash 与诊断 trace 的稳定路径语义摘要。
   *
   * <p>占用/进度版本与 request id 只用于审计，不进入摘要；相同路径与同一组实时验证 rear resources 被重算时不会制造新的 planner plan
   * hash。rear 集合变化会更新摘要，避免复用旧的收缩目标。
   */
  public String evidenceHash() {
    return Integer.toHexString(
        Objects.hash(
            trainKey,
            routeId,
            routeIndex,
            fromNode,
            toNode,
            directedSteps,
            forwardProgressReleaseResources));
  }

  /** 规范展开路径中的单条有向边。 */
  public record DirectedStep(EdgeId edgeId, NodeId fromNode, NodeId toNode) {
    public DirectedStep {
      Objects.requireNonNull(edgeId, "edgeId");
      Objects.requireNonNull(fromNode, "fromNode");
      Objects.requireNonNull(toNode, "toNode");
    }
  }

  /**
   * 前向证明派生结果。
   *
   * @param evidence 完整有效的证明
   * @param failureReason 失败原因；成功时为 {@code -}
   */
  public record Derivation(Optional<CanonicalForwardPathEvidence> evidence, String failureReason) {
    public Derivation {
      evidence = evidence == null ? Optional.empty() : evidence;
      failureReason = normalize(failureReason, evidence.isPresent() ? "-" : "UNKNOWN");
    }

    private static Derivation rejected(String reason) {
      return new Derivation(Optional.empty(), reason);
    }
  }

  private static String normalize(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }

  private static List<String> sortedResources(List<String> resources) {
    TreeSet<String> sorted = new TreeSet<>();
    if (resources != null) {
      for (String resource : resources) {
        String normalized = normalize(resource, "-");
        if (!normalized.equals("-")) {
          sorted.add(normalized);
        }
      }
    }
    return List.copyOf(sorted);
  }

  private static List<String> sortedNodeEdgeResources(List<String> resources) {
    return sortedResources(resources).stream()
        .filter(resource -> resource.startsWith("NODE:") || resource.startsWith("EDGE:"))
        .toList();
  }

  private static String resourceKey(EdgeId edgeId) {
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    return normalized.a().value() + "~" + normalized.b().value();
  }

  private boolean hasValidDirectedChain() {
    if (routeIndex < 0
        || routeId.equals("-")
        || fromNode.equals("-")
        || toNode.equals("-")
        || fromNode.equals(toNode)
        || directedSteps.isEmpty()
        || requestId.equals("-")) {
      return false;
    }
    String expectedFrom = fromNode;
    for (int index = 0; index < directedSteps.size(); index++) {
      DirectedStep step = directedSteps.get(index);
      if (step == null
          || !step.fromNode().value().equals(expectedFrom)
          || step.fromNode().equals(step.toNode())
          || !step.edgeId().equals(EdgeId.undirected(step.fromNode(), step.toNode()))) {
        return false;
      }
      if (index == 0 && !step.toNode().value().equals(toNode)) {
        return false;
      }
      expectedFrom = step.toNode().value();
    }
    return true;
  }

  private static boolean movementWindowMatchesPath(
      List<OccupancyResource> resources, List<NodeId> nodes, List<DirectedStep> steps) {
    TreeSet<String> pathResources = new TreeSet<>();
    for (NodeId node : nodes) {
      pathResources.add(OccupancyResource.forNode(node).toString());
    }
    for (DirectedStep step : steps) {
      pathResources.add(OccupancyResource.forEdge(step.edgeId()).toString());
    }
    for (OccupancyResource resource : resources) {
      if (resource != null
          && (resource.kind() == ResourceKind.NODE || resource.kind() == ResourceKind.EDGE)
          && !pathResources.contains(resource.toString())) {
        return false;
      }
    }
    return true;
  }
}
