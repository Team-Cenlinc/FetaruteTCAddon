package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 根据规范化路径签名分类同一道岔下的两条局部 movement。
 *
 * <p>本模块只证明“入口—道岔—出口”的拓扑关系，不读取占用、队列、时钟或 TrainCarts 状态，也不产生放行授权。调用方仍须独立验证证据 freshness、实体
 * footprint、Gate Queue、headway 与 terminal boundary mutex。
 */
public final class SwitcherMovementTopology {

  private static final String SWITCHER_PREFIX = "switcher:";

  private SwitcherMovementTopology() {}

  /** 两条局部 movement 的拓扑关系。 */
  public enum Relation {
    SAME_MOVEMENT,
    MERGE,
    DIVERGE,
    HEAD_ON,
    CROSSING,
    UNKNOWN
  }

  /** 分类所依据的成功证据或 fail-closed 原因。 */
  public enum EvidenceReason {
    EXACT_ORDERED_LEGS,
    OPPOSING_LOCAL_LEG,
    SHARED_EGRESS_LEG,
    SHARED_INGRESS_LEG,
    DISTINCT_LOCAL_LEGS,
    NOT_SWITCHER_CONFLICT,
    FIRST_PLAN_MISSING,
    SECOND_PLAN_MISSING,
    FIRST_SIGNATURE_MISSING,
    SECOND_SIGNATURE_MISSING,
    FIRST_KEY_MISMATCH,
    SECOND_KEY_MISMATCH,
    FIRST_SIGNATURE_PATH_MISMATCH,
    SECOND_SIGNATURE_PATH_MISMATCH,
    FIRST_DIRECTED_EDGE_MISMATCH,
    SECOND_DIRECTED_EDGE_MISMATCH,
    FIRST_SWITCHER_NOT_ON_PATH,
    SECOND_SWITCHER_NOT_ON_PATH,
    FIRST_SWITCHER_REPEATED,
    SECOND_SWITCHER_REPEATED,
    FIRST_INGRESS_MISSING,
    SECOND_INGRESS_MISSING,
    FIRST_EGRESS_MISSING,
    SECOND_EGRESS_MISSING,
    FIRST_DEGENERATE_MOVEMENT,
    SECOND_DEGENERATE_MOVEMENT
  }

  /**
   * 成功分类后携带的局部有向 movement。
   *
   * <p>证据包含 exact switcher conflict key，以及两条 movement 各自的入口与出口节点。所有字段都经过非空、非退化与 switcher-key 格式校验。
   */
  public static final class Proof {

    private final String switcherKey;
    private final NodeId firstIngress;
    private final NodeId firstEgress;
    private final NodeId secondIngress;
    private final NodeId secondEgress;

    private Proof(
        String switcherKey,
        NodeId firstIngress,
        NodeId firstEgress,
        NodeId secondIngress,
        NodeId secondEgress) {
      this.switcherKey = Objects.requireNonNull(switcherKey, "switcherKey");
      this.firstIngress = Objects.requireNonNull(firstIngress, "firstIngress");
      this.firstEgress = Objects.requireNonNull(firstEgress, "firstEgress");
      this.secondIngress = Objects.requireNonNull(secondIngress, "secondIngress");
      this.secondEgress = Objects.requireNonNull(secondEgress, "secondEgress");
      if (!switcherKey.startsWith(SWITCHER_PREFIX)
          || switcherKey.length() == SWITCHER_PREFIX.length()) {
        throw new IllegalArgumentException("proof 必须携带 exact switcher conflict key");
      }
      if (firstIngress.equals(firstEgress) || secondIngress.equals(secondEgress)) {
        throw new IllegalArgumentException("proof 不允许退化 movement");
      }
    }

    public String switcherKey() {
      return switcherKey;
    }

    public NodeId firstIngress() {
      return firstIngress;
    }

    public NodeId firstEgress() {
      return firstEgress;
    }

    public NodeId secondIngress() {
      return secondIngress;
    }

    public NodeId secondEgress() {
      return secondEgress;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Proof proof)) {
        return false;
      }
      return switcherKey.equals(proof.switcherKey)
          && firstIngress.equals(proof.firstIngress)
          && firstEgress.equals(proof.firstEgress)
          && secondIngress.equals(proof.secondIngress)
          && secondEgress.equals(proof.secondEgress);
    }

    @Override
    public int hashCode() {
      return Objects.hash(switcherKey, firstIngress, firstEgress, secondIngress, secondEgress);
    }

    @Override
    public String toString() {
      return "Proof[switcherKey="
          + switcherKey
          + ", firstIngress="
          + firstIngress
          + ", firstEgress="
          + firstEgress
          + ", secondIngress="
          + secondIngress
          + ", secondEgress="
          + secondEgress
          + "]";
    }
  }

  /**
   * 拓扑分类结果。
   *
   * <p>{@link Relation#UNKNOWN} 永远不携带 proof；其他关系必须携带 proof。结果不表达 compatible、safe 或 allowed。
   */
  public static final class Classification {

    private final Relation relation;
    private final EvidenceReason reason;
    private final Optional<Proof> proof;

    private Classification(Relation relation, EvidenceReason reason, Optional<Proof> proof) {
      this.relation = relation == null ? Relation.UNKNOWN : relation;
      this.reason = Objects.requireNonNull(reason, "reason");
      this.proof = proof == null ? Optional.empty() : proof;
      if (this.relation == Relation.UNKNOWN) {
        if (this.proof.isPresent() || successfulReason(this.reason)) {
          throw new IllegalArgumentException("UNKNOWN 分类不得携带成功 proof/reason");
        }
      } else if (this.proof.isEmpty()) {
        throw new IllegalArgumentException("已分类的 switcher movement 必须携带 proof");
      } else {
        Relation provenRelation = relationFor(this.proof.get());
        EvidenceReason provenReason = reasonFor(provenRelation);
        if (this.relation != provenRelation || this.reason != provenReason) {
          throw new IllegalArgumentException("relation/reason 与 proof 局部 movement 不一致");
        }
      }
    }

    public Relation relation() {
      return relation;
    }

    public EvidenceReason reason() {
      return reason;
    }

    public Optional<Proof> proof() {
      return proof;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Classification classification)) {
        return false;
      }
      return relation == classification.relation
          && reason == classification.reason
          && proof.equals(classification.proof);
    }

    @Override
    public int hashCode() {
      return Objects.hash(relation, reason, proof);
    }

    @Override
    public String toString() {
      return "Classification[relation="
          + relation
          + ", reason="
          + reason
          + ", proof="
          + proof
          + "]";
    }
  }

  /**
   * 分类 exact switcher conflict 下的两份规范化 movement plan。
   *
   * @param switcherConflict 目标 switcher conflict 资源
   * @param first 第一条 movement plan
   * @param second 第二条 movement plan
   * @return 确定性拓扑分类；证据缺失或含糊时返回 {@link Relation#UNKNOWN}
   */
  public static Classification classify(
      OccupancyResource switcherConflict,
      Optional<MovementPlanSnapshot> first,
      Optional<MovementPlanSnapshot> second) {
    Optional<String> switcherNode = switcherNode(switcherConflict);
    if (switcherNode.isEmpty()) {
      return unknown(EvidenceReason.NOT_SWITCHER_CONFLICT);
    }
    if (first == null || first.isEmpty()) {
      return unknown(EvidenceReason.FIRST_PLAN_MISSING);
    }
    if (second == null || second.isEmpty()) {
      return unknown(EvidenceReason.SECOND_PLAN_MISSING);
    }
    String exactKey = switcherConflict.key();
    PlanValidation firstValidation = validatePlan(first.get(), exactKey, EvidenceSide.FIRST);
    if (firstValidation.failure().isPresent()) {
      return unknown(firstValidation.failure().get());
    }
    PlanValidation secondValidation = validatePlan(second.get(), exactKey, EvidenceSide.SECOND);
    if (secondValidation.failure().isPresent()) {
      return unknown(secondValidation.failure().get());
    }
    return classifyValidatedSignatures(
        switcherConflict,
        switcherNode.get(),
        firstValidation.signature().orElseThrow(),
        secondValidation.signature().orElseThrow());
  }

  /**
   * 分类占用层已随 claim/queue 生命周期保存的 canonical signature。
   *
   * <p>该入口仅供同 package 的占用 policy 使用；Planner 必须调用接收完整 {@link MovementPlanSnapshot}
   * 的公开入口，以校验版本化计划与有向边。
   */
  static Classification classifyRememberedSignatures(
      OccupancyResource switcherConflict,
      Optional<DirectedTraversalContext.SwitcherPathSignature> first,
      Optional<DirectedTraversalContext.SwitcherPathSignature> second) {
    Optional<String> switcherNode = switcherNode(switcherConflict);
    if (switcherNode.isEmpty()) {
      return unknown(EvidenceReason.NOT_SWITCHER_CONFLICT);
    }
    if (first == null || first.isEmpty()) {
      return unknown(EvidenceReason.FIRST_SIGNATURE_MISSING);
    }
    if (second == null || second.isEmpty()) {
      return unknown(EvidenceReason.SECOND_SIGNATURE_MISSING);
    }
    String exactKey = switcherConflict.key();
    if (!exactKey.equals(first.get().switcherKey())) {
      return unknown(EvidenceReason.FIRST_KEY_MISMATCH);
    }
    if (!exactKey.equals(second.get().switcherKey())) {
      return unknown(EvidenceReason.SECOND_KEY_MISMATCH);
    }
    return classifyValidatedSignatures(
        switcherConflict, switcherNode.get(), first.get(), second.get());
  }

  /**
   * 返回一份请求中已经通过 canonical path 与 directed edge 校验的 exact switcher signature。
   *
   * <p>该入口只供同 package 的占用生命周期保存证据；任何缺失、大小写不规范、路径不连续或 edge identity 不匹配的输入都返回空值，调用方必须删除旧证据而不是沿用。正在从
   * switcher 实体出清的计划可以只包含出口侧，因此该入口允许局部 movement 不完整；后续拓扑分类仍会把它判为 {@link Relation#UNKNOWN}，不能据此共享
   * abstract conflict。
   */
  static Optional<DirectedTraversalContext.SwitcherPathSignature> verifiedSignature(
      OccupancyResource switcherConflict, Optional<MovementPlanSnapshot> plan) {
    Optional<String> switcherNode = switcherNode(switcherConflict);
    if (switcherNode.isEmpty() || plan == null || plan.isEmpty()) {
      return Optional.empty();
    }
    PlanValidation validation =
        validatePlan(plan.get(), switcherConflict.key(), EvidenceSide.FIRST);
    if (validation.failure().isPresent()) {
      return Optional.empty();
    }
    return validation.signature();
  }

  private static Classification classifyValidatedSignatures(
      OccupancyResource switcherConflict,
      String switcherNode,
      DirectedTraversalContext.SwitcherPathSignature first,
      DirectedTraversalContext.SwitcherPathSignature second) {
    MovementExtraction firstMovement = extract(first, switcherNode, EvidenceSide.FIRST);
    if (firstMovement.failure().isPresent()) {
      return unknown(firstMovement.failure().get());
    }
    MovementExtraction secondMovement = extract(second, switcherNode, EvidenceSide.SECOND);
    if (secondMovement.failure().isPresent()) {
      return unknown(secondMovement.failure().get());
    }
    LocalMovement firstLocal = firstMovement.movement().orElseThrow();
    LocalMovement secondLocal = secondMovement.movement().orElseThrow();
    Proof proof =
        new Proof(
            switcherConflict.key(),
            firstLocal.ingress(),
            firstLocal.egress(),
            secondLocal.ingress(),
            secondLocal.egress());
    if (firstLocal.equals(secondLocal)) {
      return classified(Relation.SAME_MOVEMENT, EvidenceReason.EXACT_ORDERED_LEGS, proof);
    }
    if (firstLocal.ingress().equals(secondLocal.egress())
        || firstLocal.egress().equals(secondLocal.ingress())) {
      return classified(Relation.HEAD_ON, EvidenceReason.OPPOSING_LOCAL_LEG, proof);
    }
    if (firstLocal.egress().equals(secondLocal.egress())) {
      return classified(Relation.MERGE, EvidenceReason.SHARED_EGRESS_LEG, proof);
    }
    if (firstLocal.ingress().equals(secondLocal.ingress())) {
      return classified(Relation.DIVERGE, EvidenceReason.SHARED_INGRESS_LEG, proof);
    }
    return classified(Relation.CROSSING, EvidenceReason.DISTINCT_LOCAL_LEGS, proof);
  }

  private static PlanValidation validatePlan(
      MovementPlanSnapshot plan, String exactKey, EvidenceSide side) {
    DirectedTraversalContext.SwitcherPathSignature signature =
        plan.switcherPathSignatures().get(exactKey);
    if (signature == null) {
      return PlanValidation.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_SIGNATURE_MISSING
              : EvidenceReason.SECOND_SIGNATURE_MISSING);
    }
    if (!exactKey.equals(signature.switcherKey())) {
      return PlanValidation.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_KEY_MISMATCH
              : EvidenceReason.SECOND_KEY_MISMATCH);
    }
    if (!signature.pathNodes().equals(plan.expandedPathNodes())) {
      return PlanValidation.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_SIGNATURE_PATH_MISMATCH
              : EvidenceReason.SECOND_SIGNATURE_PATH_MISMATCH);
    }
    if (!directedEdgesMatch(plan.expandedPathNodes(), plan.directedEdges())) {
      return PlanValidation.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_DIRECTED_EDGE_MISMATCH
              : EvidenceReason.SECOND_DIRECTED_EDGE_MISMATCH);
    }
    return PlanValidation.success(signature);
  }

  private static boolean directedEdgesMatch(
      List<NodeId> path, List<DirectedTraversalContext.DirectedEdge> directedEdges) {
    if (path.size() < 2 || directedEdges.size() != path.size() - 1) {
      return false;
    }
    for (int index = 0; index < directedEdges.size(); index++) {
      DirectedTraversalContext.DirectedEdge edge = directedEdges.get(index);
      if (edge == null
          || !edge.fromNode().equals(path.get(index))
          || !edge.toNode().equals(path.get(index + 1))
          || !edge.edgeId().equals(EdgeId.undirected(path.get(index), path.get(index + 1)))) {
        return false;
      }
    }
    return true;
  }

  private static Optional<String> switcherNode(OccupancyResource resource) {
    if (resource == null
        || resource.kind() != ResourceKind.CONFLICT
        || !resource.key().startsWith(SWITCHER_PREFIX)) {
      return Optional.empty();
    }
    String node = resource.key().substring(SWITCHER_PREFIX.length()).trim();
    return node.isEmpty() ? Optional.empty() : Optional.of(node);
  }

  private static MovementExtraction extract(
      DirectedTraversalContext.SwitcherPathSignature signature,
      String switcherNode,
      EvidenceSide side) {
    List<NodeId> path = signature.pathNodes();
    int switcherIndex = -1;
    int occurrences = 0;
    for (int i = 0; i < path.size(); i++) {
      NodeId node = path.get(i);
      if (node != null && switcherNode.equals(node.value())) {
        switcherIndex = i;
        occurrences++;
      }
    }
    if (occurrences == 0) {
      return MovementExtraction.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_SWITCHER_NOT_ON_PATH
              : EvidenceReason.SECOND_SWITCHER_NOT_ON_PATH);
    }
    if (occurrences > 1) {
      return MovementExtraction.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_SWITCHER_REPEATED
              : EvidenceReason.SECOND_SWITCHER_REPEATED);
    }
    if (switcherIndex == 0) {
      return MovementExtraction.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_INGRESS_MISSING
              : EvidenceReason.SECOND_INGRESS_MISSING);
    }
    if (switcherIndex + 1 >= path.size()) {
      return MovementExtraction.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_EGRESS_MISSING
              : EvidenceReason.SECOND_EGRESS_MISSING);
    }
    NodeId ingress = path.get(switcherIndex - 1);
    NodeId egress = path.get(switcherIndex + 1);
    if (ingress == null || ingress.value().isBlank()) {
      return MovementExtraction.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_INGRESS_MISSING
              : EvidenceReason.SECOND_INGRESS_MISSING);
    }
    if (egress == null || egress.value().isBlank()) {
      return MovementExtraction.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_EGRESS_MISSING
              : EvidenceReason.SECOND_EGRESS_MISSING);
    }
    if (ingress.equals(egress)) {
      return MovementExtraction.failure(
          side == EvidenceSide.FIRST
              ? EvidenceReason.FIRST_DEGENERATE_MOVEMENT
              : EvidenceReason.SECOND_DEGENERATE_MOVEMENT);
    }
    return MovementExtraction.success(new LocalMovement(ingress, egress));
  }

  private static Classification classified(Relation relation, EvidenceReason reason, Proof proof) {
    return new Classification(relation, reason, Optional.of(proof));
  }

  private static Classification unknown(EvidenceReason reason) {
    return new Classification(Relation.UNKNOWN, reason, Optional.empty());
  }

  private static Relation relationFor(Proof proof) {
    if (proof.firstIngress().equals(proof.secondIngress())
        && proof.firstEgress().equals(proof.secondEgress())) {
      return Relation.SAME_MOVEMENT;
    }
    if (proof.firstIngress().equals(proof.secondEgress())
        || proof.firstEgress().equals(proof.secondIngress())) {
      return Relation.HEAD_ON;
    }
    if (proof.firstEgress().equals(proof.secondEgress())) {
      return Relation.MERGE;
    }
    if (proof.firstIngress().equals(proof.secondIngress())) {
      return Relation.DIVERGE;
    }
    return Relation.CROSSING;
  }

  private static EvidenceReason reasonFor(Relation relation) {
    return switch (relation) {
      case SAME_MOVEMENT -> EvidenceReason.EXACT_ORDERED_LEGS;
      case MERGE -> EvidenceReason.SHARED_EGRESS_LEG;
      case DIVERGE -> EvidenceReason.SHARED_INGRESS_LEG;
      case HEAD_ON -> EvidenceReason.OPPOSING_LOCAL_LEG;
      case CROSSING -> EvidenceReason.DISTINCT_LOCAL_LEGS;
      case UNKNOWN -> throw new IllegalArgumentException("UNKNOWN 没有成功 reason");
    };
  }

  private static boolean successfulReason(EvidenceReason reason) {
    return reason == EvidenceReason.EXACT_ORDERED_LEGS
        || reason == EvidenceReason.SHARED_EGRESS_LEG
        || reason == EvidenceReason.SHARED_INGRESS_LEG
        || reason == EvidenceReason.OPPOSING_LOCAL_LEG
        || reason == EvidenceReason.DISTINCT_LOCAL_LEGS;
  }

  private enum EvidenceSide {
    FIRST,
    SECOND
  }

  private record LocalMovement(NodeId ingress, NodeId egress) {}

  private record MovementExtraction(
      Optional<LocalMovement> movement, Optional<EvidenceReason> failure) {

    private static MovementExtraction success(LocalMovement movement) {
      return new MovementExtraction(Optional.of(movement), Optional.empty());
    }

    private static MovementExtraction failure(EvidenceReason reason) {
      return new MovementExtraction(Optional.empty(), Optional.of(reason));
    }
  }

  private record PlanValidation(
      Optional<DirectedTraversalContext.SwitcherPathSignature> signature,
      Optional<EvidenceReason> failure) {

    private static PlanValidation success(
        DirectedTraversalContext.SwitcherPathSignature signature) {
      return new PlanValidation(Optional.of(signature), Optional.empty());
    }

    private static PlanValidation failure(EvidenceReason reason) {
      return new PlanValidation(Optional.empty(), Optional.of(reason));
    }
  }
}
