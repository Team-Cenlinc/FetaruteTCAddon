package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SwitcherMovementTopology;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * Smart Dispatcher 的 wait-for graph planner。
 *
 * <p>该类只处理内存快照和确定性评分，不读取 TrainCarts，也不修改 occupancy、destination、movement token 或 health recovery
 * counter。执行层必须把输出重新经过 mode、reservation 和 authority 边界检查后才能产生副作用。
 */
public final class SmartWaitForPlanner {

  private static final Comparator<String> TEXT_ORDER = String.CASE_INSENSITIVE_ORDER;

  /** Planner 输入配置。 */
  public record PlannerSettings(
      boolean enabled,
      SmartDispatcherPlannerMode mode,
      int maxReservationResources,
      int reservationTtlTicks,
      long blockerSnapshotTtlMs,
      boolean requireSameDirection,
      boolean allowReverse,
      boolean allowTurnbackBeforeBoundary,
      boolean oneActiveReservationPerCycle) {
    public PlannerSettings {
      mode = mode == null ? SmartDispatcherPlannerMode.OBSERVE_ONLY : mode;
      maxReservationResources = Math.max(1, maxReservationResources);
      reservationTtlTicks = Math.max(1, reservationTtlTicks);
      blockerSnapshotTtlMs = Math.max(1L, blockerSnapshotTtlMs);
    }

    public boolean enforceMinimalForward() {
      return enabled && mode == SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD;
    }
  }

  /** 单条 live blocker 输入边。 */
  public record InputEdge(
      String blockedTrain,
      String blockerTrain,
      String resource,
      String resourceKind,
      String relation,
      String intent,
      String role,
      String source,
      CorridorDirection direction,
      Optional<SwitcherMovementTopology.Classification> switcherMovement,
      long ageMs,
      boolean activeForNormalAdmission) {
    public InputEdge {
      blockedTrain = normalize(blockedTrain, "-");
      blockerTrain = normalize(blockerTrain, "-");
      resource = normalize(resource, "-");
      resourceKind = normalize(resourceKind, resourceKindFrom(resource));
      relation = normalize(relation, "UNKNOWN");
      intent = normalize(intent, "UNKNOWN");
      role = normalize(role, "UNKNOWN");
      source = normalize(source, "unknown");
      direction = direction == null ? CorridorDirection.UNKNOWN : direction;
      switcherMovement = switcherMovement == null ? Optional.empty() : switcherMovement;
      ageMs = Math.max(0L, ageMs);
    }

    public InputEdge(
        String blockedTrain,
        String blockerTrain,
        String resource,
        String resourceKind,
        String relation,
        String intent,
        String role,
        String source,
        CorridorDirection direction,
        long ageMs,
        boolean activeForNormalAdmission) {
      this(
          blockedTrain,
          blockerTrain,
          resource,
          resourceKind,
          relation,
          intent,
          role,
          source,
          direction,
          Optional.empty(),
          ageMs,
          activeForNormalAdmission);
    }
  }

  /** 单列车只读状态。 */
  public record TrainState(
      String trainName,
      String routeId,
      int currentIndex,
      String currentNode,
      String nextNode,
      String lastPassedGraphNode,
      CorridorDirection inferredDirection,
      String directionSource,
      String directionInferenceFailureReason,
      long stuckDurationSeconds,
      boolean stalled,
      boolean reverseCandidate,
      boolean turnbackReverseBeforeBoundary,
      boolean fullRouteCandidate,
      boolean createsOppositeConflict,
      boolean blocksUnrelatedNormalTrain,
      String movementTokenState,
      Optional<CanonicalForwardPathEvidence> forwardPathEvidence) {
    public TrainState {
      trainName = normalize(trainName, "-");
      routeId = normalize(routeId, "-");
      currentNode = normalize(currentNode, "-");
      nextNode = normalize(nextNode, "-");
      lastPassedGraphNode = normalize(lastPassedGraphNode, "-");
      inferredDirection = inferredDirection == null ? CorridorDirection.UNKNOWN : inferredDirection;
      directionSource = normalize(directionSource, "UNKNOWN");
      directionInferenceFailureReason = normalize(directionInferenceFailureReason, "-");
      stuckDurationSeconds = Math.max(0L, stuckDurationSeconds);
      movementTokenState = normalize(movementTokenState, "NONE");
      forwardPathEvidence = forwardPathEvidence == null ? Optional.empty() : forwardPathEvidence;
      if (forwardPathEvidence.isPresent()
          && !forwardPathEvidence.orElseThrow().proves(trainName, currentNode, nextNode)) {
        forwardPathEvidence = Optional.empty();
      }
    }

    public TrainState(
        String trainName,
        String routeId,
        int currentIndex,
        String currentNode,
        String nextNode,
        String lastPassedGraphNode,
        CorridorDirection inferredDirection,
        String directionSource,
        String directionInferenceFailureReason,
        long stuckDurationSeconds,
        boolean stalled,
        boolean reverseCandidate,
        boolean turnbackReverseBeforeBoundary,
        boolean fullRouteCandidate,
        boolean createsOppositeConflict,
        boolean blocksUnrelatedNormalTrain,
        String movementTokenState) {
      this(
          trainName,
          routeId,
          currentIndex,
          currentNode,
          nextNode,
          lastPassedGraphNode,
          inferredDirection,
          directionSource,
          directionInferenceFailureReason,
          stuckDurationSeconds,
          stalled,
          reverseCandidate,
          turnbackReverseBeforeBoundary,
          fullRouteCandidate,
          createsOppositeConflict,
          blocksUnrelatedNormalTrain,
          movementTokenState,
          Optional.empty());
    }

    public String routeFamily() {
      int lastColon = routeId.lastIndexOf(':');
      return lastColon <= 0 ? routeId : routeId.substring(0, lastColon);
    }
  }

  /** 一轮 planner 输入。 */
  public record PlannerInput(
      Instant capturedAt,
      PlannerSettings settings,
      List<InputEdge> edges,
      Map<String, TrainState> trainStates,
      Set<String> activeCycleReservations,
      Set<String> activeReservationTrains) {
    public PlannerInput {
      capturedAt = capturedAt == null ? Instant.now() : capturedAt;
      settings =
          settings == null
              ? new PlannerSettings(
                  false, SmartDispatcherPlannerMode.OFF, 1, 1, 1L, true, false, false, true)
              : settings;
      edges = edges == null ? List.of() : List.copyOf(edges);
      trainStates = trainStates == null ? Map.of() : Map.copyOf(trainStates);
      activeCycleReservations =
          activeCycleReservations == null ? Set.of() : Set.copyOf(activeCycleReservations);
      activeReservationTrains =
          activeReservationTrains == null
              ? Set.of()
              : Set.copyOf(
                  activeReservationTrains.stream()
                      .map(TrainNameNormalizer::normalizeKey)
                      .filter(name -> !name.isEmpty())
                      .toList());
    }
  }

  /** 候选类型。 */
  public enum CandidateKind {
    FORWARD_TO_AUTHORITY_BOUNDARY,
    FORWARD_TO_RELEASE_BLOCKER,
    FORWARD_TO_SAFE_HOLD_POINT,
    YIELD_TO_HEAD_ON
  }

  /** shadow simulation 置信度。 */
  public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
  }

  /** Graph-only shadow simulation 结果。 */
  public record GraphOnlySimulation(
      int beforeBlockers,
      int afterBlockers,
      boolean cycleBroken,
      int releasedBlockerCount,
      int newConflicts,
      int reservationResourceCount,
      Confidence confidence,
      String lowConfidenceReason) {
    public GraphOnlySimulation {
      beforeBlockers = Math.max(0, beforeBlockers);
      afterBlockers = Math.max(0, afterBlockers);
      releasedBlockerCount = Math.max(0, releasedBlockerCount);
      newConflicts = Math.max(0, newConflicts);
      reservationResourceCount = Math.max(0, reservationResourceCount);
      confidence = confidence == null ? Confidence.LOW : confidence;
      lowConfidenceReason = normalize(lowConfidenceReason, "-");
    }
  }

  /** 已评分候选。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "构造器已用不可变 List 规整 resources，record accessor 可安全共享。")
  public record UnlockCandidate(
      String train,
      String cycleId,
      CandidateKind kind,
      List<String> releaseResources,
      List<String> resources,
      String authorityEnd,
      String currentNode,
      String nextNode,
      CorridorDirection direction,
      int releaseResourceCount,
      int fullRouteResourceCount,
      int reservationResourceLimit,
      int score,
      boolean accepted,
      String rejectReason,
      String recommendation,
      GraphOnlySimulation simulation,
      boolean releasesBottleneck,
      boolean improvesSameLineCascade,
      long stuckDurationSeconds,
      Optional<CanonicalForwardPathEvidence> forwardPathEvidence) {
    public UnlockCandidate {
      train = normalize(train, "-");
      cycleId = normalize(cycleId, "-");
      kind = kind == null ? CandidateKind.FORWARD_TO_RELEASE_BLOCKER : kind;
      releaseResources = releaseResources == null ? List.of() : sortedDistinct(releaseResources);
      resources = resources == null ? List.of() : sortedDistinct(resources);
      authorityEnd = normalize(authorityEnd, "-");
      currentNode = normalize(currentNode, "-");
      nextNode = normalize(nextNode, "-");
      direction = direction == null ? CorridorDirection.UNKNOWN : direction;
      releaseResourceCount = Math.max(0, releaseResourceCount);
      fullRouteResourceCount = Math.max(0, fullRouteResourceCount);
      reservationResourceLimit = Math.max(1, reservationResourceLimit);
      rejectReason = normalize(rejectReason, accepted ? "-" : "UNKNOWN");
      recommendation = normalize(recommendation, "-");
      forwardPathEvidence = forwardPathEvidence == null ? Optional.empty() : forwardPathEvidence;
      if (forwardPathEvidence.isPresent()
          && !forwardPathEvidence.orElseThrow().proves(train, currentNode, nextNode)) {
        forwardPathEvidence = Optional.empty();
      }
    }

    public UnlockCandidate(
        String train,
        String cycleId,
        CandidateKind kind,
        List<String> releaseResources,
        List<String> resources,
        String authorityEnd,
        String currentNode,
        String nextNode,
        CorridorDirection direction,
        int releaseResourceCount,
        int fullRouteResourceCount,
        int reservationResourceLimit,
        int score,
        boolean accepted,
        String rejectReason,
        String recommendation,
        GraphOnlySimulation simulation,
        boolean releasesBottleneck,
        boolean improvesSameLineCascade,
        long stuckDurationSeconds) {
      this(
          train,
          cycleId,
          kind,
          releaseResources,
          resources,
          authorityEnd,
          currentNode,
          nextNode,
          direction,
          releaseResourceCount,
          fullRouteResourceCount,
          reservationResourceLimit,
          score,
          accepted,
          rejectReason,
          recommendation,
          simulation,
          releasesBottleneck,
          improvesSameLineCascade,
          stuckDurationSeconds,
          Optional.empty());
    }

    public int reservationResourceCount() {
      return resources.size();
    }

    /**
     * 是否具有足以请求下一 tick 规范重评估的前向证据。
     *
     * <p>已知 {@link CorridorDirection} 仍只代表单线/单冲突二值方向；通用 NODE/EDGE 候选必须携带独立的 canonical path
     * 证明，二者不会互相伪造。
     */
    public boolean hasForwardDirectionEvidence() {
      if (resources.isEmpty()) {
        return false;
      }
      List<String> canonicalResources =
          resources.stream().filter(SmartWaitForPlanner::canonicalPathResource).toList();
      boolean canonicalSatisfied =
          canonicalResources.isEmpty()
              || forwardPathEvidence
                  .filter(evidence -> evidence.proves(train, currentNode, nextNode))
                  .filter(
                      evidence ->
                          canonicalResources.stream().allMatch(evidence::coversReleaseResource))
                  .isPresent();
      boolean corridorOrConflictSatisfied =
          resources.stream().allMatch(SmartWaitForPlanner::canonicalPathResource)
              || direction != CorridorDirection.UNKNOWN;
      return canonicalSatisfied && corridorOrConflictSatisfied;
    }

    public String planHash() {
      return Integer.toHexString(
          Objects.hash(
              train,
              cycleId,
              kind,
              resources,
              authorityEnd,
              direction,
              forwardPathEvidence.map(CanonicalForwardPathEvidence::evidenceHash).orElse("-"),
              releaseResourceCount,
              fullRouteResourceCount,
              score));
    }
  }

  /** 本轮 planner 输出。 */
  public record PlanResult(
      String graphHash,
      String selectedPlanHash,
      String throttleKey,
      List<String> traceLines,
      List<UnlockCandidate> candidates,
      Optional<UnlockCandidate> selectedPlan,
      boolean directionAuditNeeded,
      boolean hardDeadlockEvidenceStrong) {
    public PlanResult {
      graphHash = normalize(graphHash, "-");
      selectedPlanHash = normalize(selectedPlanHash, "-");
      throttleKey = normalize(throttleKey, graphHash + ":" + selectedPlanHash);
      traceLines = traceLines == null ? List.of() : List.copyOf(traceLines);
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
      selectedPlan = selectedPlan == null ? Optional.empty() : selectedPlan;
    }
  }

  private record Edge(InputEdge input) {
    String blocked() {
      return input.blockedTrain();
    }

    String blocker() {
      return input.blockerTrain();
    }

    String resource() {
      return input.resource();
    }
  }

  private record Pattern(
      String id, String type, Set<String> trains, Set<String> blockers, boolean hardCycle) {
    private Pattern {
      id = normalize(id, "-");
      type = normalize(type, "UNKNOWN");
      trains = trains == null ? Set.of() : Set.copyOf(trains);
      blockers = blockers == null ? Set.of() : Set.copyOf(blockers);
    }
  }

  private record SwitcherMergeEvidence(
      String switcherKey, String currentConflictOwner, String otherTrain, String reason) {}

  private record DirectionResolution(
      CorridorDirection direction,
      Optional<CanonicalForwardPathEvidence> forwardPathEvidence,
      String source,
      String failureReason) {
    private DirectionResolution {
      direction = direction == null ? CorridorDirection.UNKNOWN : direction;
      forwardPathEvidence = forwardPathEvidence == null ? Optional.empty() : forwardPathEvidence;
      source = normalize(source, "UNKNOWN");
      failureReason = normalize(failureReason, "-");
    }

    boolean known() {
      return direction != CorridorDirection.UNKNOWN || forwardPathEvidence.isPresent();
    }

    boolean corridorKnown() {
      return direction != CorridorDirection.UNKNOWN;
    }
  }

  /** 生成只读调度计划。 */
  public PlanResult plan(PlannerInput input) {
    if (input == null
        || !input.settings().enabled()
        || input.settings().mode() == SmartDispatcherPlannerMode.OFF) {
      return new PlanResult("-", "-", "-", List.of(), List.of(), Optional.empty(), false, false);
    }
    List<String> traces = new ArrayList<>();
    List<Edge> activeEdges = new ArrayList<>();
    int rejected = 0;
    int stale = 0;
    for (InputEdge edge : input.edges()) {
      String rejection = inputEdgeRejection(edge, input.settings());
      if (!rejection.isBlank()) {
        rejected++;
        if ("STALE_EDGE".equals(rejection)) {
          stale++;
        }
        traces.add(inputEdgeRejectedTrace(edge, rejection, input.settings()));
        continue;
      }
      activeEdges.add(new Edge(edge));
      traces.add(inputEdgeTrace(edge));
    }
    String graphHash = graphHash(activeEdges);
    traces.add(
        "SMART_WAIT_FOR_GRAPH nodes="
            + graphNodes(activeEdges).size()
            + " edges="
            + activeEdges.size()
            + " rejectedEdges="
            + rejected
            + " staleEdges="
            + stale
            + " graphHash="
            + graphHash);
    if (activeEdges.isEmpty() && !input.edges().isEmpty()) {
      traces.add(
          "SMART_DISPATCH_FALLBACK_NOT_READY reason=NO_FRESH_BLOCKER_EDGES rejectedEdges="
              + rejected
              + " staleEdges="
              + stale);
    }

    List<Pattern> patterns = detectPatterns(activeEdges, input.trainStates(), traces);
    Map<String, SwitcherMergeEvidence> switcherMerges =
        detectSwitcherMergeEvidence(activeEdges, patterns, traces);
    List<Pattern> plannerPatterns =
        patterns.stream().filter(pattern -> !switcherMerges.containsKey(pattern.id())).toList();
    boolean onlySwitcherMergePatterns = !switcherMerges.isEmpty() && plannerPatterns.isEmpty();
    List<UnlockCandidate> candidates = buildCandidates(input, activeEdges, plannerPatterns, traces);
    traceActiveReservationSkips(input, candidates, traces);
    Optional<UnlockCandidate> selected = selectCandidate(input, candidates);
    boolean directionAuditNeeded =
        !candidates.isEmpty()
            && candidates.stream()
                .allMatch(
                    candidate ->
                        "INSUFFICIENT_DIRECTION_EVIDENCE".equals(candidate.rejectReason()));
    boolean hardEvidenceStrong =
        patterns.stream()
            .anyMatch(pattern -> pattern.hardCycle() && knownDirections(activeEdges, pattern));
    if (selected.isPresent()) {
      UnlockCandidate plan = selected.get();
      String effectClass = input.settings().enforceMinimalForward() ? "OCCUPANCY_MUTATION" : "NONE";
      traces.add(
          "SMART_DISPATCH_PLAN_SELECTED train="
              + plan.train()
              + " cycleId="
              + plan.cycleId()
              + " kind="
              + plan.kind()
              + " score="
              + plan.score()
              + " readOnly="
              + !input.settings().enforceMinimalForward()
              + " effectClass="
              + effectClass
              + " selectedPlanHash="
              + plan.planHash());
    } else if (!patterns.isEmpty()) {
      String recommendation =
          onlySwitcherMergePatterns
              ? "SWITCHER_MERGE_EXECUTOR_NOT_READY"
              : directionAuditNeeded
                  ? "NEED_DIRECTION_AUDIT"
                  : hardEvidenceStrong ? "DESTROY_REVIEW" : "NO_SAFE_SAME_DIRECTION_PLAN";
      traces.add(
          "SMART_NO_SAME_DIRECTION_UNLOCK_PLAN recommendation="
              + recommendation
              + " directionAuditNeeded="
              + directionAuditNeeded
              + " hardDeadlockEvidenceStrong="
              + hardEvidenceStrong);
      String fallbackReason =
          onlySwitcherMergePatterns
              ? "SWITCHER_MERGE_EXECUTOR_NOT_READY"
              : directionAuditNeeded
                  ? "INSUFFICIENT_DIRECTION_EVIDENCE"
                  : stale > 0 ? "STALE_OR_NO_FRESH_EDGE" : "NO_SAFE_SAME_DIRECTION_PLAN";
      traces.add("SMART_DISPATCH_FALLBACK_NOT_READY reason=" + fallbackReason);
    }
    String selectedPlanHash = selected.map(UnlockCandidate::planHash).orElse("-");
    String throttleKey =
        graphHash
            + ":"
            + selected.map(UnlockCandidate::cycleId).orElse("-")
            + ":"
            + selectedPlanHash;
    return new PlanResult(
        graphHash,
        selectedPlanHash,
        throttleKey,
        traces,
        candidates,
        selected,
        directionAuditNeeded,
        hardEvidenceStrong);
  }

  private static List<UnlockCandidate> buildCandidates(
      PlannerInput input, List<Edge> edges, List<Pattern> patterns, List<String> traces) {
    List<UnlockCandidate> candidates = new ArrayList<>();
    Map<String, List<Edge>> blockedByTrain = new TreeMap<>(TEXT_ORDER);
    for (Edge edge : edges) {
      blockedByTrain.computeIfAbsent(edge.blocker(), unused -> new ArrayList<>()).add(edge);
    }
    for (Pattern pattern : patterns) {
      boolean generatedForPattern = false;
      boolean acceptedForPattern = false;
      String firstRejectReason = "NO_RELEASABLE_BLOCKER_RESOURCE";
      UnlockCandidate decisionCandidate = null;
      List<String> patternTraces = new ArrayList<>();
      for (String blocker : pattern.blockers()) {
        List<Edge> releaseEdges = blockedByTrain.get(blocker);
        if (releaseEdges == null || releaseEdges.isEmpty()) {
          continue;
        }
        generatedForPattern = true;
        TrainState state = input.trainStates().getOrDefault(blocker, defaultState(blocker));
        UnlockCandidate candidate =
            candidateForTrain(input.settings(), edges, releaseEdges, pattern, state, patternTraces);
        candidates.add(candidate);
        if (decisionCandidate == null || (!decisionCandidate.accepted() && candidate.accepted())) {
          decisionCandidate = candidate;
        }
        if (candidate.accepted()) {
          acceptedForPattern = true;
          patternTraces.add(candidateTrace(candidate));
          patternTraces.add(simulationTrace(candidate));
        } else {
          firstRejectReason = candidate.rejectReason();
          patternTraces.add(rejectedCandidateTrace(candidate));
          patternTraces.add(rejectedPlanTrace(candidate));
          patternTraces.addAll(invariantTraces(candidate));
        }
      }
      if ("SAME_LINE_CASCADE".equals(pattern.type())) {
        List<UnlockCandidate> yieldCandidates =
            headOnYieldCandidates(input, edges, pattern, patternTraces);
        candidates.addAll(yieldCandidates);
        if (!yieldCandidates.isEmpty()) {
          generatedForPattern = true;
          if (yieldCandidates.stream().anyMatch(UnlockCandidate::accepted)) {
            acceptedForPattern = true;
          }
          if (decisionCandidate == null
              || (!decisionCandidate.accepted()
                  && yieldCandidates.stream().anyMatch(UnlockCandidate::accepted))) {
            decisionCandidate =
                yieldCandidates.stream().filter(UnlockCandidate::accepted).findFirst().orElse(null);
          }
        }
      }
      if ("MUTUAL".equals(pattern.type())) {
        List<UnlockCandidate> mutualYieldCandidates =
            mutualHeadOnYieldCandidates(input, edges, pattern, patternTraces);
        candidates.addAll(mutualYieldCandidates);
        if (!mutualYieldCandidates.isEmpty()) {
          generatedForPattern = true;
          if (mutualYieldCandidates.stream().anyMatch(UnlockCandidate::accepted)) {
            acceptedForPattern = true;
          }
          if (decisionCandidate == null
              || (!decisionCandidate.accepted()
                  && mutualYieldCandidates.stream().anyMatch(UnlockCandidate::accepted))) {
            decisionCandidate =
                mutualYieldCandidates.stream()
                    .filter(UnlockCandidate::accepted)
                    .findFirst()
                    .orElse(null);
          }
        }
      }
      traces.add(candidateDecisionTrace(input, pattern, decisionCandidate, generatedForPattern));
      traces.addAll(patternTraces);
      if (!generatedForPattern || !acceptedForPattern) {
        traces.add(noCandidateForPatternTrace(pattern, firstRejectReason));
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * 识别 mutual wait-for 中由同一道岔多分支汇流形成的关系。
   *
   * <p>该证据只识别“全部 active edge 都投影到同一个 exact switcher”的纯汇流，不生成 release candidate。没有两阶段停车与 admission
   * lease 前，planner 不得释放任何一方的 {@code MOVEMENT_REQUIRED} claim。
   */
  private static Map<String, SwitcherMergeEvidence> detectSwitcherMergeEvidence(
      List<Edge> graphEdges, List<Pattern> patterns, List<String> traces) {
    Map<String, SwitcherMergeEvidence> evidenceByPattern = new TreeMap<>(TEXT_ORDER);
    for (Pattern pattern : patterns) {
      if (!"MUTUAL".equals(pattern.type()) || pattern.trains().size() != 2) {
        continue;
      }
      List<Edge> patternEdges =
          graphEdges.stream()
              .filter(
                  edge ->
                      pattern.trains().contains(edge.blocked())
                          && pattern.trains().contains(edge.blocker()))
              .toList();
      Optional<SwitcherMergeEvidence> evidenceOpt = switcherMergeEvidence(pattern, patternEdges);
      if (evidenceOpt.isEmpty()) {
        continue;
      }
      SwitcherMergeEvidence evidence = evidenceOpt.get();
      evidenceByPattern.put(pattern.id(), evidence);
      traces.add(
          "SMART_DISPATCH_SWITCHER_MERGE_DETECTED cycleId="
              + pattern.id()
              + " switcherKey="
              + evidence.switcherKey()
              + " currentConflictOwner="
              + evidence.currentConflictOwner()
              + " otherTrain="
              + evidence.otherTrain()
              + " relation="
              + SwitcherMovementTopology.Relation.MERGE
              + " reason="
              + evidence.reason()
              + " action=OBSERVE_ONLY"
              + " movementAuthorityIssued=false"
              + " occupancyMutated=false");
    }
    return Map.copyOf(evidenceByPattern);
  }

  private static Optional<SwitcherMergeEvidence> switcherMergeEvidence(
      Pattern pattern, List<Edge> patternEdges) {
    if (pattern == null || patternEdges == null || patternEdges.size() < 2) {
      return Optional.empty();
    }
    boolean reciprocal =
        patternEdges.stream()
            .allMatch(
                edge ->
                    patternEdges.stream()
                        .anyMatch(
                            candidate ->
                                candidate.blocked().equals(edge.blocker())
                                    && candidate.blocker().equals(edge.blocked())));
    if (!reciprocal) {
      return Optional.empty();
    }
    Set<String> switcherKeys = new TreeSet<>(TEXT_ORDER);
    Set<String> reasons = new TreeSet<>(TEXT_ORDER);
    for (Edge edge : patternEdges) {
      Optional<SwitcherMovementTopology.Classification> merge =
          provenMerge(edge.input().switcherMovement());
      if (merge.isEmpty()) {
        return Optional.empty();
      }
      SwitcherMovementTopology.Classification classification = merge.get();
      switcherKeys.add(classification.proof().orElseThrow().switcherKey());
      reasons.add(classification.reason().name());
    }
    if (switcherKeys.size() != 1) {
      return Optional.empty();
    }
    String switcherKey = switcherKeys.iterator().next();
    String switcherNode = switcherKey.substring("switcher:".length());
    String conflictResource = "CONFLICT:" + switcherKey;
    String nodeResource = "NODE:" + switcherNode;
    if (patternEdges.stream()
        .anyMatch(
            edge ->
                !conflictResource.equals(edge.resource())
                    && !nodeResource.equals(edge.resource()))) {
      return Optional.empty();
    }
    Set<String> conflictOwners = new TreeSet<>(TEXT_ORDER);
    for (Edge edge : patternEdges) {
      if (conflictResource.equals(edge.resource())) {
        conflictOwners.add(edge.blocker());
      }
    }
    if (conflictOwners.size() != 1) {
      return Optional.empty();
    }
    String currentOwner = conflictOwners.iterator().next();
    Optional<String> otherTrain =
        pattern.trains().stream().filter(train -> !train.equals(currentOwner)).findFirst();
    if (otherTrain.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new SwitcherMergeEvidence(
            switcherKey, currentOwner, otherTrain.get(), String.join("+", reasons)));
  }

  private static Optional<SwitcherMovementTopology.Classification> provenMerge(
      Optional<SwitcherMovementTopology.Classification> classification) {
    if (classification == null || classification.isEmpty()) {
      return Optional.empty();
    }
    SwitcherMovementTopology.Classification value = classification.get();
    return value.relation() == SwitcherMovementTopology.Relation.MERGE && value.proof().isPresent()
        ? Optional.of(value)
        : Optional.empty();
  }

  private static List<UnlockCandidate> headOnYieldCandidates(
      PlannerInput input, List<Edge> graphEdges, Pattern pattern, List<String> traces) {
    if (input == null || pattern == null || pattern.blockers().isEmpty()) {
      return List.of();
    }
    List<UnlockCandidate> candidates = new ArrayList<>();
    for (String chainHead : pattern.blockers()) {
      TrainState headState = input.trainStates().getOrDefault(chainHead, defaultState(chainHead));
      CorridorDirection headDirection = headState.inferredDirection();
      if (headDirection == CorridorDirection.UNKNOWN) {
        continue;
      }
      Map<String, List<Edge>> directBlockers = new TreeMap<>(TEXT_ORDER);
      for (Edge edge : graphEdges) {
        if (!chainHead.equals(edge.blocked())) {
          continue;
        }
        TrainState blockerState =
            input.trainStates().getOrDefault(edge.blocker(), defaultState(edge.blocker()));
        if (headState.routeFamily().equals(blockerState.routeFamily())) {
          continue;
        }
        CorridorDirection blockerDirection = edge.input().direction();
        if (blockerDirection == CorridorDirection.UNKNOWN || blockerDirection == headDirection) {
          continue;
        }
        directBlockers.computeIfAbsent(edge.blocker(), unused -> new ArrayList<>()).add(edge);
      }
      for (Map.Entry<String, List<Edge>> entry : directBlockers.entrySet()) {
        TrainState yieldState =
            input.trainStates().getOrDefault(entry.getKey(), defaultState(entry.getKey()));
        UnlockCandidate candidate =
            yieldCandidateForHeadOn(
                input.settings(), graphEdges, entry.getValue(), pattern, yieldState, true);
        candidates.add(candidate);
        traces.add(
            "SMART_DISPATCH_HEAD_ON_YIELD_DETECTED chainHead="
                + chainHead
                + " yieldTrain="
                + candidate.train()
                + " resources="
                + candidate.resources()
                + " headDirection="
                + headDirection
                + " yieldDirection="
                + candidate.direction());
        traces.add(candidateTrace(candidate));
        traces.add(simulationTrace(candidate));
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * 为方向已知且相反的 mutual wait-for 环生成让行候选。
   *
   * <p>该候选只让执行层释放让行车在相关资源上的非物理 claim/队列；它不释放车体 NODE/EDGE，也不签发新的移动授权。
   */
  private static List<UnlockCandidate> mutualHeadOnYieldCandidates(
      PlannerInput input, List<Edge> graphEdges, Pattern pattern, List<String> traces) {
    if (input == null || pattern == null || !"MUTUAL".equals(pattern.type())) {
      return List.of();
    }
    List<Edge> patternEdges =
        graphEdges.stream()
            .filter(
                edge ->
                    pattern.trains().contains(edge.blocked())
                        && pattern.trains().contains(edge.blocker()))
            .toList();
    List<UnlockCandidate> candidates = new ArrayList<>();
    Set<String> seenPairs = new LinkedHashSet<>();
    for (Edge edge : patternEdges) {
      Edge reciprocal =
          patternEdges.stream()
              .filter(
                  candidate ->
                      candidate.blocked().equals(edge.blocker())
                          && candidate.blocker().equals(edge.blocked()))
              .findFirst()
              .orElse(null);
      if (reciprocal == null) {
        continue;
      }
      String pairKey = String.join("|", sortedSet(Set.of(edge.blocked(), edge.blocker())));
      if (!seenPairs.add(pairKey)) {
        continue;
      }
      CorridorDirection firstDirection = edge.input().direction();
      CorridorDirection secondDirection = reciprocal.input().direction();
      if (firstDirection == CorridorDirection.UNKNOWN
          || secondDirection == CorridorDirection.UNKNOWN
          || firstDirection == secondDirection) {
        continue;
      }
      candidates.add(
          mutualHeadOnYieldCandidate(
              input, graphEdges, pattern, traces, edge, secondDirection, firstDirection));
      candidates.add(
          mutualHeadOnYieldCandidate(
              input, graphEdges, pattern, traces, reciprocal, firstDirection, secondDirection));
    }
    return List.copyOf(candidates);
  }

  private static UnlockCandidate mutualHeadOnYieldCandidate(
      PlannerInput input,
      List<Edge> graphEdges,
      Pattern pattern,
      List<String> traces,
      Edge releaseEdge,
      CorridorDirection blockedDirection,
      CorridorDirection yieldDirection) {
    TrainState yieldState =
        input
            .trainStates()
            .getOrDefault(releaseEdge.blocker(), defaultState(releaseEdge.blocker()));
    UnlockCandidate candidate =
        yieldCandidateForHeadOn(
            input.settings(), graphEdges, List.of(releaseEdge), pattern, yieldState, false);
    traces.add(
        "SMART_DISPATCH_MUTUAL_HEAD_ON_YIELD_DETECTED blockedTrain="
            + releaseEdge.blocked()
            + " yieldTrain="
            + candidate.train()
            + " resources="
            + candidate.resources()
            + " blockedDirection="
            + blockedDirection
            + " yieldDirection="
            + yieldDirection);
    traces.add(candidateTrace(candidate));
    traces.add(simulationTrace(candidate));
    return candidate;
  }

  private static UnlockCandidate yieldCandidateForHeadOn(
      PlannerSettings settings,
      List<Edge> graphEdges,
      List<Edge> releaseEdges,
      Pattern pattern,
      TrainState state,
      boolean improvesSameLineCascade) {
    List<Edge> effectiveReleaseEdges = releaseEdges == null ? List.of() : releaseEdges;
    List<String> resources =
        effectiveReleaseEdges.stream().map(Edge::resource).distinct().sorted(TEXT_ORDER).toList();
    List<String> reservationResources =
        resources.stream().limit(settings.maxReservationResources()).toList();
    DirectionResolution direction = resolveDirection(effectiveReleaseEdges, state);
    GraphOnlySimulation simulation =
        simulate(
            graphEdges,
            state.trainName(),
            reservationResources,
            pattern.id(),
            direction.corridorKnown(),
            state);
    boolean physicalInterlocking = containsPhysicalInterlocking(effectiveReleaseEdges);
    boolean accepted =
        !physicalInterlocking && direction.corridorKnown() && !reservationResources.isEmpty();
    String rejectReason =
        physicalInterlocking
            ? "PHYSICAL_INTERLOCKING_NON_SPECULATIVE"
            : accepted
                ? "-"
                : direction.corridorKnown()
                    ? "NO_RELEASABLE_BLOCKER_RESOURCE"
                    : "INSUFFICIENT_DIRECTION_EVIDENCE";
    int score =
        accepted
            ? score(simulation, false, improvesSameLineCascade, state)
                + 500
                + (simulation.cycleBroken() ? 500 : 0)
            : Integer.MIN_VALUE;
    return new UnlockCandidate(
        state.trainName(),
        pattern.id(),
        CandidateKind.YIELD_TO_HEAD_ON,
        resources,
        reservationResources,
        state.currentNode(),
        state.currentNode(),
        state.nextNode(),
        direction.direction(),
        resources.size(),
        0,
        settings.maxReservationResources(),
        score,
        accepted,
        rejectReason,
        accepted ? "HEAD_ON_YIELD" : "NEED_DIRECTION_AUDIT",
        simulation,
        false,
        improvesSameLineCascade,
        state.stuckDurationSeconds());
  }

  private static UnlockCandidate candidateForTrain(
      PlannerSettings settings,
      List<Edge> graphEdges,
      List<Edge> releaseEdges,
      Pattern pattern,
      TrainState state,
      List<String> traces) {
    List<Edge> safeReleaseEdges = releaseEdges == null ? List.of() : releaseEdges;
    List<String> releaseResources = new ArrayList<>();
    for (Edge edge : safeReleaseEdges) {
      releaseResources.add(edge.resource());
    }
    List<String> distinctReleaseResources = sortedDistinct(releaseResources);
    List<String> reservationResources =
        distinctReleaseResources.stream().limit(settings.maxReservationResources()).toList();
    DirectionResolution directionResolution = resolveDirection(safeReleaseEdges, state);
    if (directionResolution.known()) {
      traces.add(directionInferredTrace(state, directionResolution));
    } else {
      traces.add(directionInferenceFailedTrace(state, directionResolution));
    }
    CorridorDirection direction = directionResolution.direction();
    String cycleId = pattern == null ? "-" : pattern.id();
    GraphOnlySimulation simulation =
        simulate(
            graphEdges,
            state.trainName(),
            reservationResources,
            cycleId,
            directionResolution.known(),
            state);
    String rejectReason = "";
    String recommendation = "-";
    boolean corridorDirectionRequired =
        safeReleaseEdges.stream()
            .map(Edge::resource)
            .anyMatch(resource -> resource.startsWith("CONFLICT:single:"));
    boolean canonicalPathRequired =
        safeReleaseEdges.stream()
            .map(Edge::resource)
            .anyMatch(SmartWaitForPlanner::canonicalPathResource);
    boolean conflictDirectionRequired =
        safeReleaseEdges.stream()
            .map(Edge::resource)
            .anyMatch(resource -> !canonicalPathResource(resource));
    if (containsPhysicalInterlocking(safeReleaseEdges)) {
      rejectReason = "PHYSICAL_INTERLOCKING_NON_SPECULATIVE";
    } else if (corridorDirectionRequired
        && !hasCompleteConsistentSingleCorridorDirection(safeReleaseEdges)) {
      rejectReason = "INSUFFICIENT_DIRECTION_EVIDENCE";
      recommendation = "NEED_DIRECTION_AUDIT";
      traces.add(directionEvidenceTrace(state, pattern, safeReleaseEdges, directionResolution));
    } else if (canonicalPathRequired && directionResolution.forwardPathEvidence().isEmpty()) {
      rejectReason = "INSUFFICIENT_DIRECTION_EVIDENCE";
      recommendation = "NEED_DIRECTION_AUDIT";
      traces.add(directionEvidenceTrace(state, pattern, safeReleaseEdges, directionResolution));
    } else if (conflictDirectionRequired && !directionResolution.corridorKnown()) {
      rejectReason = "INSUFFICIENT_DIRECTION_EVIDENCE";
      recommendation = "NEED_DIRECTION_AUDIT";
      traces.add(directionEvidenceTrace(state, pattern, safeReleaseEdges, directionResolution));
    } else if (settings.requireSameDirection() && !directionResolution.known()) {
      rejectReason = "INSUFFICIENT_DIRECTION_EVIDENCE";
      recommendation = "NEED_DIRECTION_AUDIT";
      traces.add(directionEvidenceTrace(state, pattern, safeReleaseEdges, directionResolution));
    } else if (!settings.allowReverse() && state.reverseCandidate()) {
      rejectReason = "REVERSE_DIRECTION";
    } else if (!settings.allowTurnbackBeforeBoundary() && state.turnbackReverseBeforeBoundary()) {
      rejectReason = "TURNBACK_REVERSE_LEG_NOT_REACHED";
    } else if (state.fullRouteCandidate()) {
      rejectReason = "FULL_ROUTE_PRECLAIM";
    } else if (state.createsOppositeConflict()) {
      rejectReason = "WOULD_CREATE_OPPOSITE_DIRECTION_CLAIM";
    } else if (state.blocksUnrelatedNormalTrain()) {
      rejectReason = "WOULD_BLOCK_UNRELATED_NORMAL_TRAIN";
    } else if (reservationResources.size() > settings.maxReservationResources()) {
      rejectReason = "RESERVATION_RESOURCE_LIMIT_EXCEEDED";
    } else if (reservationResources.isEmpty()) {
      rejectReason = "NO_RELEASABLE_BLOCKER_RESOURCE";
    } else if (!simulation.cycleBroken() && simulation.releasedBlockerCount() <= 0) {
      rejectReason = "GRAPH_NOT_IMPROVED";
    }
    boolean accepted = rejectReason.isBlank();
    int score =
        accepted
            ? score(
                simulation,
                pattern != null && "BOTTLENECK".equals(pattern.type()),
                pattern != null && "SAME_LINE_CASCADE".equals(pattern.type()),
                state)
            : Integer.MIN_VALUE;
    CandidateKind kind =
        state.nextNode().equals("-")
            ? CandidateKind.FORWARD_TO_RELEASE_BLOCKER
            : CandidateKind.FORWARD_TO_AUTHORITY_BOUNDARY;
    return new UnlockCandidate(
        state.trainName(),
        cycleId,
        kind,
        distinctReleaseResources,
        reservationResources,
        state.nextNode(),
        state.currentNode(),
        state.nextNode(),
        direction,
        distinctReleaseResources.size(),
        state.fullRouteCandidate() ? distinctReleaseResources.size() : 0,
        settings.maxReservationResources(),
        score,
        accepted,
        rejectReason,
        recommendation,
        simulation,
        pattern != null && "BOTTLENECK".equals(pattern.type()),
        pattern != null && "SAME_LINE_CASCADE".equals(pattern.type()),
        state.stuckDurationSeconds(),
        directionResolution.forwardPathEvidence());
  }

  /** 物理联锁资源必须等待真实占用释放，planner 不得把它建模成可推测撤销的 reservation。 */
  private static boolean containsPhysicalInterlocking(List<Edge> edges) {
    if (edges == null || edges.isEmpty()) {
      return false;
    }
    return edges.stream()
        .filter(Objects::nonNull)
        .map(Edge::resource)
        .anyMatch(resource -> resource.startsWith("CONFLICT:interlocking:"));
  }

  private static GraphOnlySimulation simulate(
      List<Edge> graphEdges,
      String train,
      List<String> resources,
      String cycleId,
      boolean forwardDirectionKnown,
      TrainState state) {
    int before = graphEdges.size();
    Set<String> resourceSet = new LinkedHashSet<>(resources);
    List<Edge> afterEdges = new ArrayList<>();
    int released = 0;
    for (Edge edge : graphEdges) {
      if (edge.blocker().equals(train) && resourceSet.contains(edge.resource())) {
        released++;
        continue;
      }
      afterEdges.add(edge);
    }
    boolean beforeCycle = hasCycle(graphEdges, cycleId);
    boolean afterCycle = hasCycle(afterEdges, cycleId);
    int newConflicts =
        state.createsOppositeConflict() || state.blocksUnrelatedNormalTrain() ? 1 : 0;
    Confidence confidence;
    String lowReason = "-";
    if (!forwardDirectionKnown || state.nextNode().equals("-")) {
      confidence = Confidence.LOW;
      lowReason = !forwardDirectionKnown ? "direction-unknown" : "authority-boundary-unknown";
    } else if (beforeCycle) {
      confidence = Confidence.HIGH;
    } else {
      confidence = Confidence.MEDIUM;
    }
    return new GraphOnlySimulation(
        before,
        afterEdges.size() + newConflicts,
        beforeCycle && !afterCycle,
        released,
        newConflicts,
        resourceSet.size(),
        confidence,
        lowReason);
  }

  private static int score(
      GraphOnlySimulation simulation,
      boolean releasesBottleneckTrain,
      boolean improvesSameLineCascade,
      TrainState state) {
    int score = 0;
    if (simulation.cycleBroken()) {
      score += 1000;
    }
    score += 200 * simulation.releasedBlockerCount();
    if (releasesBottleneckTrain) {
      score += 150;
    }
    if (improvesSameLineCascade) {
      score += 100;
    }
    if (!state.nextNode().equals("-")) {
      score += 50;
    }
    score -= 10 * simulation.reservationResourceCount();
    score -= 500 * simulation.newConflicts();
    return score;
  }

  private static Optional<UnlockCandidate> selectCandidate(
      PlannerInput input, List<UnlockCandidate> candidates) {
    return candidates.stream()
        .filter(UnlockCandidate::accepted)
        .filter(candidate -> !hasActiveReservation(input, candidate))
        .sorted(
            Comparator.comparingInt(
                    (UnlockCandidate candidate) -> candidate.improvesSameLineCascade() ? 0 : 1)
                .thenComparingInt(
                    candidate -> candidate.kind() == CandidateKind.YIELD_TO_HEAD_ON ? 0 : 1)
                .thenComparing(Comparator.comparingInt(UnlockCandidate::score).reversed())
                .thenComparingInt(UnlockCandidate::reservationResourceCount)
                .thenComparing(UnlockCandidate::stuckDurationSeconds, Comparator.reverseOrder())
                .thenComparing(UnlockCandidate::train, TEXT_ORDER))
        .findFirst();
  }

  private static void traceActiveReservationSkips(
      PlannerInput input, List<UnlockCandidate> candidates, List<String> traces) {
    for (UnlockCandidate candidate : candidates) {
      if (!candidate.accepted() || !hasActiveReservation(input, candidate)) {
        continue;
      }
      boolean cycleActive = input.activeCycleReservations().contains(candidate.cycleId());
      traces.add(
          "SMART_DISPATCH_ACTIVE_RESERVATION_SKIPPED train="
              + candidate.train()
              + " cycleId="
              + candidate.cycleId()
              + " activeBy="
              + (cycleActive ? "CYCLE" : "TRAIN")
              + " reason=ACTIVE_RESERVATION_EXISTS");
    }
  }

  private static boolean hasActiveReservation(PlannerInput input, UnlockCandidate candidate) {
    return input.activeCycleReservations().contains(candidate.cycleId())
        || input
            .activeReservationTrains()
            .contains(TrainNameNormalizer.normalizeKey(candidate.train()));
  }

  private static List<Pattern> detectPatterns(
      List<Edge> edges, Map<String, TrainState> states, List<String> traces) {
    List<Pattern> patterns = new ArrayList<>();
    Map<String, Set<String>> graph = adjacency(edges);
    Set<String> mutualSeen = new LinkedHashSet<>();
    for (Edge edge : edges) {
      if (graph.getOrDefault(edge.blocker(), Set.of()).contains(edge.blocked())) {
        Set<String> trains = sortedSet(Set.of(edge.blocked(), edge.blocker()));
        String id = "mutual:" + String.join("|", trains);
        if (mutualSeen.add(id)) {
          Pattern pattern = new Pattern(id, "MUTUAL", trains, trains, true);
          patterns.add(pattern);
          traces.add(
              "SMART_DISPATCH_CYCLE_DETECTED type=MUTUAL cycleId=" + id + " trains=" + trains);
        }
      }
    }
    for (Set<String> component : stronglyConnectedComponents(graph)) {
      if (component.size() <= 2) {
        continue;
      }
      String id = "scc:" + String.join("|", sortedSet(component));
      Pattern pattern = new Pattern(id, "SCC", component, component, true);
      patterns.add(pattern);
      traces.add(
          "SMART_DISPATCH_CYCLE_DETECTED type=SCC cycleId="
              + id
              + " trains="
              + sortedSet(component));
    }
    Map<String, Set<String>> blockedByBlocker = new TreeMap<>(TEXT_ORDER);
    for (Edge edge : edges) {
      blockedByBlocker
          .computeIfAbsent(edge.blocker(), unused -> new TreeSet<>(TEXT_ORDER))
          .add(edge.blocked());
    }
    for (Map.Entry<String, Set<String>> entry : blockedByBlocker.entrySet()) {
      if (entry.getValue().size() < 2) {
        continue;
      }
      Set<String> trains = new TreeSet<>(TEXT_ORDER);
      trains.add(entry.getKey());
      trains.addAll(entry.getValue());
      String id = "bottleneck:" + entry.getKey();
      patterns.add(new Pattern(id, "BOTTLENECK", trains, Set.of(entry.getKey()), false));
      traces.add(
          "SMART_DISPATCH_BOTTLENECK_DETECTED blocker="
              + entry.getKey()
              + " blockedCount="
              + entry.getValue().size()
              + " trains="
              + trains);
      TrainState leader = states.get(entry.getKey());
      if (leader != null && leader.stalled()) {
        patterns.add(
            new Pattern(
                "chain:" + entry.getKey(),
                "STALLED_LEADER_CHAIN",
                trains,
                Set.of(entry.getKey()),
                false));
        traces.add(
            "SMART_DISPATCH_STALLED_LEADER_CHAIN_DETECTED leader="
                + entry.getKey()
                + " followers="
                + entry.getValue()
                + " routeFamily="
                + leader.routeFamily());
      }
    }
    for (List<String> chain : sameLineChains(edges, states)) {
      if (chain.size() < 3) {
        continue;
      }
      Set<String> trains = sortedSet(chain);
      String id = "cascade:" + String.join("|", trains);
      patterns.add(
          new Pattern(id, "SAME_LINE_CASCADE", trains, Set.of(chain.get(chain.size() - 1)), false));
      traces.add(
          "SMART_DISPATCH_SAME_LINE_CASCADE_DETECTED chain="
              + chain
              + " routeFamily="
              + states.getOrDefault(chain.get(0), defaultState(chain.get(0))).routeFamily());
    }
    return List.copyOf(patterns);
  }

  private static List<List<String>> sameLineChains(
      List<Edge> edges, Map<String, TrainState> states) {
    Map<String, String> next = new TreeMap<>(TEXT_ORDER);
    for (Edge edge : edges) {
      TrainState blocked = states.get(edge.blocked());
      TrainState blocker = states.get(edge.blocker());
      if (blocked == null
          || blocker == null
          || !blocked.routeFamily().equals(blocker.routeFamily())) {
        continue;
      }
      next.putIfAbsent(edge.blocked(), edge.blocker());
    }
    List<List<String>> chains = new ArrayList<>();
    for (String start : next.keySet()) {
      List<String> chain = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      String cursor = start;
      while (cursor != null && seen.add(cursor)) {
        chain.add(cursor);
        cursor = next.get(cursor);
      }
      chains.add(chain);
    }
    return chains;
  }

  private static Set<Set<String>> stronglyConnectedComponents(Map<String, Set<String>> graph) {
    Set<Set<String>> result = new LinkedHashSet<>();
    Set<String> nodes = new TreeSet<>(TEXT_ORDER);
    nodes.addAll(graph.keySet());
    graph.values().forEach(nodes::addAll);
    for (String node : nodes) {
      Set<String> reachable = reachable(graph, node);
      Set<String> component = new TreeSet<>(TEXT_ORDER);
      for (String other : nodes) {
        if (reachable.contains(other) && reachable(graph, other).contains(node)) {
          component.add(other);
        }
      }
      if (component.size() > 1) {
        result.add(component);
      }
    }
    return result;
  }

  private static Set<String> reachable(Map<String, Set<String>> graph, String start) {
    Set<String> seen = new TreeSet<>(TEXT_ORDER);
    ArrayDeque<String> queue = new ArrayDeque<>();
    queue.add(start);
    while (!queue.isEmpty()) {
      String node = queue.removeFirst();
      if (!seen.add(node)) {
        continue;
      }
      for (String next : graph.getOrDefault(node, Set.of())) {
        if (!seen.contains(next)) {
          queue.addLast(next);
        }
      }
    }
    return seen;
  }

  private static boolean hasCycle(List<Edge> edges, String cycleId) {
    if (edges.isEmpty() || "-".equals(cycleId)) {
      return false;
    }
    Map<String, Set<String>> graph = adjacency(edges);
    return stronglyConnectedComponents(graph).stream().anyMatch(component -> component.size() > 1);
  }

  private static boolean knownDirections(List<Edge> edges, Pattern pattern) {
    for (Edge edge : edges) {
      if (!pattern.trains().contains(edge.blocked())
          || !pattern.trains().contains(edge.blocker())) {
        continue;
      }
      if (edge.input().direction() == CorridorDirection.UNKNOWN) {
        return false;
      }
    }
    return true;
  }

  private static DirectionResolution resolveDirection(List<Edge> releaseEdges, TrainState state) {
    Optional<CanonicalForwardPathEvidence> forwardPathEvidence =
        canonicalForwardPathEvidence(releaseEdges, state);
    List<Edge> singleCorridorEdges =
        releaseEdges.stream()
            .filter(edge -> edge.resource().startsWith("CONFLICT:single:"))
            .toList();
    if (!singleCorridorEdges.isEmpty()) {
      if (!hasCompleteConsistentSingleCorridorDirection(singleCorridorEdges)) {
        return new DirectionResolution(
            CorridorDirection.UNKNOWN,
            forwardPathEvidence,
            forwardPathEvidence.isPresent() ? "CANONICAL_MOVEMENT_PLAN" : "UNKNOWN",
            "SINGLE_CONFLICT_DIRECTION_MISSING_OR_CONFLICTING");
      }
    }

    List<Edge> directedConflictEdges =
        releaseEdges.stream().filter(edge -> !canonicalPathResource(edge.resource())).toList();
    boolean missingConflictDirection =
        directedConflictEdges.stream()
            .map(edge -> edge.input().direction())
            .anyMatch(direction -> direction == null || direction == CorridorDirection.UNKNOWN);
    if (missingConflictDirection) {
      return new DirectionResolution(
          CorridorDirection.UNKNOWN,
          forwardPathEvidence,
          forwardPathEvidence.isPresent() ? "CANONICAL_MOVEMENT_PLAN" : "UNKNOWN",
          "CONFLICT_DIRECTION_MISSING");
    }
    Set<CorridorDirection> knownDirections =
        directedConflictEdges.stream()
            .map(edge -> edge.input().direction())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (knownDirections.size() == 1) {
      return new DirectionResolution(
          knownDirections.iterator().next(), forwardPathEvidence, "CONFLICT_DIRECTIONS", "-");
    }
    if (knownDirections.size() > 1) {
      return new DirectionResolution(
          CorridorDirection.UNKNOWN,
          forwardPathEvidence,
          forwardPathEvidence.isPresent() ? "CANONICAL_MOVEMENT_PLAN" : "UNKNOWN",
          "CONFLICTING_EDGE_DIRECTIONS");
    }
    if (forwardPathEvidence.isPresent()) {
      return new DirectionResolution(
          CorridorDirection.UNKNOWN, forwardPathEvidence, "CANONICAL_MOVEMENT_PLAN", "-");
    }
    return new DirectionResolution(
        CorridorDirection.UNKNOWN,
        Optional.empty(),
        "UNKNOWN",
        "-".equals(state.directionInferenceFailureReason())
            ? "INSUFFICIENT_DIRECTION_EVIDENCE"
            : state.directionInferenceFailureReason());
  }

  /** 仅当 canonical plan 覆盖候选中的每一个 NODE/EDGE blocker 时返回通用路径证明。 */
  private static Optional<CanonicalForwardPathEvidence> canonicalForwardPathEvidence(
      List<Edge> releaseEdges, TrainState state) {
    List<String> canonicalResources =
        releaseEdges.stream()
            .map(Edge::resource)
            .filter(SmartWaitForPlanner::canonicalPathResource)
            .toList();
    if (canonicalResources.isEmpty() || state.forwardPathEvidence().isEmpty()) {
      return Optional.empty();
    }
    CanonicalForwardPathEvidence evidence = state.forwardPathEvidence().orElseThrow();
    return canonicalResources.stream().allMatch(evidence::coversReleaseResource)
        ? Optional.of(evidence)
        : Optional.empty();
  }

  /** 每一条待释放 single-corridor 边都必须携带同一个已知方向，不能由其他边代填。 */
  private static boolean hasCompleteConsistentSingleCorridorDirection(List<Edge> releaseEdges) {
    Set<CorridorDirection> directions = new LinkedHashSet<>();
    boolean singleCorridorPresent = false;
    for (Edge edge : releaseEdges) {
      if (!edge.resource().startsWith("CONFLICT:single:")) {
        continue;
      }
      singleCorridorPresent = true;
      CorridorDirection direction = edge.input().direction();
      if (direction == null || direction == CorridorDirection.UNKNOWN) {
        return false;
      }
      directions.add(direction);
    }
    return singleCorridorPresent && directions.size() == 1;
  }

  private static Map<String, Set<String>> adjacency(List<Edge> edges) {
    Map<String, Set<String>> graph = new TreeMap<>(TEXT_ORDER);
    for (Edge edge : edges) {
      graph
          .computeIfAbsent(edge.blocked(), unused -> new TreeSet<>(TEXT_ORDER))
          .add(edge.blocker());
      graph.computeIfAbsent(edge.blocker(), unused -> new TreeSet<>(TEXT_ORDER));
    }
    return graph;
  }

  private static Set<String> graphNodes(List<Edge> edges) {
    Set<String> nodes = new TreeSet<>(TEXT_ORDER);
    for (Edge edge : edges) {
      nodes.add(edge.blocked());
      nodes.add(edge.blocker());
    }
    return nodes;
  }

  private static String inputEdgeRejection(InputEdge edge, PlannerSettings settings) {
    if (edge == null) {
      return "null-edge";
    }
    if (edge.blockedTrain().equals("-") || edge.blockerTrain().equals("-")) {
      return "missing-train";
    }
    // TrainCarts split 会为同一逻辑列车附加临时后缀；这类边必须在进入 wait-for graph 前剔除。
    if (TrainNameNormalizer.sameLogicalTrain(edge.blockedTrain(), edge.blockerTrain())) {
      return "self-owned-edge";
    }
    if (!edge.activeForNormalAdmission()) {
      return "inactive-for-normal-admission";
    }
    if (edge.ageMs() > settings.blockerSnapshotTtlMs()) {
      return "STALE_EDGE";
    }
    return "";
  }

  private static String inputEdgeTrace(InputEdge edge) {
    return "SMART_DISPATCH_INPUT_EDGE blockedTrain="
        + edge.blockedTrain()
        + " blockerTrain="
        + edge.blockerTrain()
        + " resource="
        + edge.resource()
        + " resourceKind="
        + edge.resourceKind()
        + " relation="
        + edge.relation()
        + " intent="
        + edge.intent()
        + " role="
        + edge.role()
        + " source="
        + edge.source()
        + " direction="
        + edge.direction()
        + " switcherRelation="
        + edge.switcherMovement().map(value -> value.relation().name()).orElse("UNKNOWN")
        + " switcherReason="
        + edge.switcherMovement().map(value -> value.reason().name()).orElse("MISSING")
        + " ageMs="
        + edge.ageMs()
        + " activeForNormalAdmission="
        + edge.activeForNormalAdmission();
  }

  private static String inputEdgeRejectedTrace(
      InputEdge edge, String reason, PlannerSettings settings) {
    InputEdge safe =
        edge == null
            ? new InputEdge(
                "-", "-", "-", "-", "-", "-", "-", "-", CorridorDirection.UNKNOWN, 0, false)
            : edge;
    String staleDetail =
        "STALE_EDGE".equals(reason)
            ? " ageMs=" + safe.ageMs() + " ttlMs=" + settings.blockerSnapshotTtlMs()
            : "";
    return "SMART_DISPATCH_INPUT_EDGE_REJECTED blockedTrain="
        + safe.blockedTrain()
        + " blockerTrain="
        + safe.blockerTrain()
        + " resource="
        + safe.resource()
        + " reason="
        + reason
        + staleDetail;
  }

  private static String candidateTrace(UnlockCandidate candidate) {
    return "SMART_DISPATCH_UNLOCK_CANDIDATE train="
        + candidate.train()
        + " cycleId="
        + candidate.cycleId()
        + " kind="
        + candidate.kind()
        + " resources="
        + candidate.resources()
        + " direction="
        + candidate.direction()
        + " directionEvidenceKind="
        + directionEvidenceKind(candidate)
        + " forwardPathEvidenceHash="
        + candidate
            .forwardPathEvidence()
            .map(CanonicalForwardPathEvidence::evidenceHash)
            .orElse("-")
        + " releaseResourceCount="
        + candidate.releaseResourceCount()
        + " reservationResourceCount="
        + candidate.reservationResourceCount()
        + " fullRouteResourceCount="
        + candidate.fullRouteResourceCount()
        + " sameLineCascade="
        + candidate.improvesSameLineCascade()
        + " score="
        + candidate.score();
  }

  private static String rejectedCandidateTrace(UnlockCandidate candidate) {
    return "SMART_DISPATCH_UNLOCK_CANDIDATE_REJECTED train="
        + candidate.train()
        + " cycleId="
        + candidate.cycleId()
        + " kind="
        + candidate.kind()
        + " reason="
        + candidate.rejectReason()
        + " recommendation="
        + candidate.recommendation()
        + " releaseResourceCount="
        + candidate.releaseResourceCount()
        + " reservationResourceCount="
        + candidate.reservationResourceCount()
        + " fullRouteResourceCount="
        + candidate.fullRouteResourceCount();
  }

  private static String directionInferredTrace(TrainState state, DirectionResolution resolution) {
    return "SMART_DISPATCH_DIRECTION_INFERRED train="
        + state.trainName()
        + " source="
        + resolution.source()
        + " direction="
        + resolution.direction()
        + " directionEvidenceKind="
        + directionEvidenceKind(resolution)
        + " forwardPathEvidenceHash="
        + resolution
            .forwardPathEvidence()
            .map(CanonicalForwardPathEvidence::evidenceHash)
            .orElse("-");
  }

  private static String directionInferenceFailedTrace(
      TrainState state, DirectionResolution resolution) {
    return "SMART_DISPATCH_DIRECTION_INFERENCE_FAILED train="
        + state.trainName()
        + " reason="
        + resolution.failureReason();
  }

  private static String noCandidateForPatternTrace(Pattern pattern, String reason) {
    String safeReason = normalize(reason, "NO_RELEASABLE_BLOCKER_RESOURCE");
    String actor = pattern.blockers().stream().sorted(TEXT_ORDER).findFirst().orElse("-");
    return switch (pattern.type()) {
      case "BOTTLENECK" -> "SMART_DISPATCH_NO_CANDIDATE_FOR_BOTTLENECK blocker="
          + actor
          + " reason="
          + safeReason;
      case "STALLED_LEADER_CHAIN" -> "SMART_DISPATCH_NO_CANDIDATE_FOR_CHAIN leader="
          + actor
          + " reason="
          + safeReason;
      default -> "SMART_DISPATCH_NO_CANDIDATE_FOR_CYCLE cycleId="
          + pattern.id()
          + " reason="
          + safeReason;
    };
  }

  private static String simulationTrace(UnlockCandidate candidate) {
    GraphOnlySimulation simulation = candidate.simulation();
    return "SMART_DISPATCH_UNLOCK_SIMULATION train="
        + candidate.train()
        + " cycleId="
        + candidate.cycleId()
        + " simulationModel=GRAPH_ONLY beforeBlockers="
        + simulation.beforeBlockers()
        + " afterBlockers="
        + simulation.afterBlockers()
        + " cycleBroken="
        + simulation.cycleBroken()
        + " releasedBlockerCount="
        + simulation.releasedBlockerCount()
        + " newConflicts="
        + simulation.newConflicts()
        + " reservationResourceCount="
        + simulation.reservationResourceCount()
        + " confidence="
        + simulation.confidence()
        + " lowConfidenceReason="
        + simulation.lowConfidenceReason();
  }

  private static String rejectedPlanTrace(UnlockCandidate candidate) {
    return "SMART_DISPATCH_PLAN_REJECTED train="
        + candidate.train()
        + " cycleId="
        + candidate.cycleId()
        + " reason="
        + candidate.rejectReason()
        + " recommendation="
        + candidate.recommendation();
  }

  private static String candidateDecisionTrace(
      PlannerInput input, Pattern pattern, UnlockCandidate candidate, boolean attempted) {
    String actor =
        pattern == null
            ? "-"
            : pattern.blockers().stream().sorted(TEXT_ORDER).findFirst().orElse("-");
    Set<String> followers = new TreeSet<>(TEXT_ORDER);
    if (pattern != null) {
      followers.addAll(pattern.trains());
      followers.remove(actor);
    }
    String rejectedReason =
        candidate == null
            ? "NO_SAFE_FORWARD_SLICE"
            : diagnosticDecisionReason(candidate.rejectReason(), candidate.accepted());
    String recommendation =
        candidate == null
            ? "NO_RELEASABLE_BLOCKER_RESOURCE"
            : normalize(candidate.recommendation(), "-");
    boolean sameDirectionRequired = input != null && input.settings().requireSameDirection();
    CorridorDirection direction =
        candidate == null ? CorridorDirection.UNKNOWN : candidate.direction();
    boolean sameDirectionSatisfied =
        !sameDirectionRequired || (candidate != null && candidate.hasForwardDirectionEvidence());
    return "SMART_DISPATCH_CANDIDATE_DECISION cycleId="
        + (pattern == null ? "-" : pattern.id())
        + " tick="
        + traceTick(input == null ? null : input.capturedAt())
        + " pattern="
        + (pattern == null ? "UNKNOWN" : pattern.type())
        + " leaderTrain="
        + actor
        + " followers="
        + followers
        + " candidateAttempted="
        + attempted
        + " candidateTrain="
        + (candidate == null ? "-" : candidate.train())
        + " candidateType="
        + (candidate == null ? "-" : candidate.kind())
        + " targetResource="
        + (candidate == null ? "-" : candidate.authorityEnd())
        + " blockerResource="
        + firstOrDash(candidate == null ? List.of() : candidate.releaseResources())
        + " releaseResources="
        + (candidate == null ? List.of() : candidate.releaseResources())
        + " reservationResources="
        + (candidate == null ? List.of() : candidate.resources())
        + " fullRouteResourceCount="
        + (candidate == null ? 0 : candidate.fullRouteResourceCount())
        + " releaseResourceCount="
        + (candidate == null ? 0 : candidate.releaseResourceCount())
        + " reservationResourceCount="
        + (candidate == null ? 0 : candidate.reservationResourceCount())
        + " maxReservationResources="
        + (input == null ? 0 : input.settings().maxReservationResources())
        + " sameDirectionRequired="
        + sameDirectionRequired
        + " sameDirectionSatisfied="
        + sameDirectionSatisfied
        + " inferredDirection="
        + direction
        + " directionEvidenceKind="
        + directionEvidenceKind(candidate)
        + " forwardPathEvidenceHash="
        + (candidate == null
            ? "-"
            : candidate
                .forwardPathEvidence()
                .map(CanonicalForwardPathEvidence::evidenceHash)
                .orElse("-"))
        + " rejected="
        + (candidate == null || !candidate.accepted())
        + " rejectedReason="
        + rejectedReason
        + " recommendation="
        + recommendation;
  }

  private static String diagnosticDecisionReason(String rejectReason, boolean accepted) {
    if (accepted) {
      return "-";
    }
    return switch (normalize(rejectReason, "UNKNOWN_BUG")) {
      case "INSUFFICIENT_DIRECTION_EVIDENCE" -> "INSUFFICIENT_DIRECTION_EVIDENCE";
      case "STALE_EDGE" -> "STALE_EDGE";
      case "RESERVATION_RESOURCE_LIMIT_EXCEEDED" -> "RESERVATION_RESOURCE_LIMIT_EXCEEDED";
      case "REVERSE_DIRECTION",
          "TURNBACK_REVERSE_LEG_NOT_REACHED",
          "WOULD_CREATE_OPPOSITE_DIRECTION_CLAIM" -> "SAME_DIRECTION_FAILED";
      case "NO_RELEASABLE_BLOCKER_RESOURCE",
          "GRAPH_NOT_IMPROVED",
          "FULL_ROUTE_PRECLAIM",
          "WOULD_BLOCK_UNRELATED_NORMAL_TRAIN",
          "PHYSICAL_INTERLOCKING_NON_SPECULATIVE" -> "NO_SAFE_FORWARD_SLICE";
      default -> "UNKNOWN_BUG";
    };
  }

  private static String directionEvidenceTrace(
      TrainState state, Pattern pattern, List<Edge> releaseEdges, DirectionResolution resolution) {
    List<Edge> safeEdges = releaseEdges == null ? List.of() : releaseEdges;
    Edge first = safeEdges.isEmpty() ? null : safeEdges.get(0);
    String resource = first == null ? "-" : first.input().resource();
    String resourceKind = first == null ? "UNKNOWN" : first.input().resourceKind();
    CorridorDirection requestDirection =
        first == null ? CorridorDirection.UNKNOWN : first.input().direction();
    CorridorDirection singleConflictDirection =
        resource.startsWith("CONFLICT:single") ? requestDirection : CorridorDirection.UNKNOWN;
    CorridorDirection directedEdgeDirection =
        "EDGE".equals(resourceKind) ? requestDirection : CorridorDirection.UNKNOWN;
    boolean fallbackAttempted =
        safeEdges.stream().noneMatch(edge -> known(edge.input().direction()));
    return "SMART_DISPATCH_DIRECTION_EVIDENCE train="
        + state.trainName()
        + " canonicalTrain="
        + canonicalTrain(state.trainName())
        + " cycleId="
        + (pattern == null ? "-" : pattern.id())
        + " pattern="
        + (pattern == null ? "UNKNOWN" : pattern.type())
        + " targetResource="
        + state.nextNode()
        + " blockerResource="
        + resource
        + " resourceKind="
        + resourceKind
        + " liveSnapshotDirection="
        + joinedDirections(safeEdges)
        + " requestDirection="
        + requestDirection
        + " singleConflictDirection="
        + singleConflictDirection
        + " directedEdgeDirection="
        + directedEdgeDirection
        + " routeContextDirection="
        + state.inferredDirection()
        + " canonicalPathEvidence="
        + state.forwardPathEvidence().map(CanonicalForwardPathEvidence::evidenceHash).orElse("-")
        + " authorityDirection="
        + CorridorDirection.UNKNOWN
        + " currentNode="
        + state.currentNode()
        + " nextNode="
        + state.nextNode()
        + " lastPassedGraphNode="
        + state.lastPassedGraphNode()
        + " fallbackAttempted="
        + fallbackAttempted
        + " inferredDirection="
        + resolution.direction()
        + " confidence="
        + directionConfidence(resolution)
        + " failureReason="
        + resolution.failureReason();
  }

  private static List<String> invariantTraces(UnlockCandidate candidate) {
    List<String> traces = new ArrayList<>();
    if ("REVERSE_DIRECTION".equals(candidate.rejectReason())) {
      traces.add(
          "SMART_REVERSE_UNLOCK_REJECTED train="
              + candidate.train()
              + " cycleId="
              + candidate.cycleId());
    }
    if ("FULL_ROUTE_PRECLAIM".equals(candidate.rejectReason())
        || candidate.fullRouteResourceCount() > 0) {
      traces.add(
          "SMART_FULL_ROUTE_PRECLAIM_REJECTED train="
              + candidate.train()
              + " cycleId="
              + candidate.cycleId()
              + " fullRouteResourceCount="
              + candidate.fullRouteResourceCount()
              + " reservationResourceCount="
              + candidate.reservationResourceCount());
    }
    if ("RESERVATION_RESOURCE_LIMIT_EXCEEDED".equals(candidate.rejectReason())) {
      traces.add(
          "SMART_RESERVATION_RESOURCE_LIMIT_EXCEEDED train="
              + candidate.train()
              + " reservationResourceCount="
              + candidate.reservationResourceCount()
              + " limit="
              + candidate.reservationResourceLimit());
    }
    if ("WOULD_CREATE_OPPOSITE_DIRECTION_CLAIM".equals(candidate.rejectReason())
        || "INSUFFICIENT_DIRECTION_EVIDENCE".equals(candidate.rejectReason())) {
      traces.add(
          "SMART_DIRECTION_INVARIANT_BLOCKED train="
              + candidate.train()
              + " cycleId="
              + candidate.cycleId()
              + " reason="
              + candidate.rejectReason());
    }
    return traces;
  }

  private static String graphHash(List<Edge> edges) {
    List<String> parts = new ArrayList<>();
    for (Edge edge : edges) {
      String switcherEvidence =
          edge.input()
              .switcherMovement()
              .map(
                  value ->
                      value.relation()
                          + ":"
                          + value.reason()
                          + ":"
                          + value.proof().map(Object::toString).orElse("-"))
              .orElse("-");
      parts.add(
          edge.blocked() + ">" + edge.blocker() + "@" + edge.resource() + "#" + switcherEvidence);
    }
    parts.sort(TEXT_ORDER);
    return Integer.toHexString(parts.hashCode());
  }

  private static String resourceKindFrom(String resource) {
    if (resource == null || resource.isBlank() || !resource.contains(":")) {
      return "UNKNOWN";
    }
    return resource.substring(0, resource.indexOf(':')).toUpperCase(Locale.ROOT);
  }

  private static boolean canonicalPathResource(String resource) {
    return resource != null && (resource.startsWith("NODE:") || resource.startsWith("EDGE:"));
  }

  private static String firstOrDash(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "-";
    }
    String value = values.get(0);
    return value == null || value.isBlank() ? "-" : value;
  }

  private static long traceTick(Instant capturedAt) {
    Instant instant = capturedAt == null ? Instant.now() : capturedAt;
    return instant.toEpochMilli() / 50L;
  }

  private static String canonicalTrain(String trainName) {
    return normalize(trainName, "-").toLowerCase(Locale.ROOT);
  }

  private static boolean known(CorridorDirection direction) {
    return direction != null && direction != CorridorDirection.UNKNOWN;
  }

  private static String joinedDirections(List<Edge> edges) {
    Set<String> directions = new TreeSet<>(TEXT_ORDER);
    if (edges != null) {
      for (Edge edge : edges) {
        if (edge != null && known(edge.input().direction())) {
          directions.add(edge.input().direction().name());
        }
      }
    }
    return directions.isEmpty() ? CorridorDirection.UNKNOWN.name() : directions.toString();
  }

  private static Confidence directionConfidence(DirectionResolution resolution) {
    if (resolution == null || !resolution.known()) {
      return Confidence.LOW;
    }
    return "CONFLICT_DIRECTIONS".equals(resolution.source())
            || "CANONICAL_MOVEMENT_PLAN".equals(resolution.source())
        ? Confidence.HIGH
        : Confidence.MEDIUM;
  }

  private static String directionEvidenceKind(UnlockCandidate candidate) {
    if (candidate == null) {
      return "MISSING";
    }
    if (candidate.direction() != CorridorDirection.UNKNOWN
        && candidate.forwardPathEvidence().isPresent()) {
      return compositeCanonicalEvidenceKind(candidate.forwardPathEvidence().orElseThrow());
    }
    if (candidate.direction() != CorridorDirection.UNKNOWN) {
      return "SINGLE_CORRIDOR_DIRECTION";
    }
    return candidate
        .forwardPathEvidence()
        .map(CanonicalForwardPathEvidence::evidenceKind)
        .orElse("MISSING");
  }

  private static String directionEvidenceKind(DirectionResolution resolution) {
    if (resolution == null) {
      return "MISSING";
    }
    if (resolution.corridorKnown() && resolution.forwardPathEvidence().isPresent()) {
      return compositeCanonicalEvidenceKind(resolution.forwardPathEvidence().orElseThrow());
    }
    if (resolution.corridorKnown()) {
      return "SINGLE_CORRIDOR_DIRECTION";
    }
    return resolution
        .forwardPathEvidence()
        .map(CanonicalForwardPathEvidence::evidenceKind)
        .orElse("MISSING");
  }

  private static String compositeCanonicalEvidenceKind(CanonicalForwardPathEvidence evidence) {
    return evidence.forwardProgressReleaseResources().isEmpty()
        ? "COMPOSITE_CORRIDOR_AND_CANONICAL"
        : "COMPOSITE_CORRIDOR_AND_CANONICAL_REAR_RETAIN";
  }

  private static TrainState defaultState(String trainName) {
    return new TrainState(
        trainName,
        "-",
        -1,
        "-",
        "-",
        "-",
        CorridorDirection.UNKNOWN,
        "UNKNOWN",
        "ACTIVE_STATE_MISSING",
        0,
        true,
        false,
        false,
        false,
        false,
        false,
        "NONE");
  }

  private static Set<String> sortedSet(Iterable<String> values) {
    Set<String> sorted = new TreeSet<>(TEXT_ORDER);
    if (values != null) {
      for (String value : values) {
        sorted.add(normalize(value, "-"));
      }
    }
    return sorted;
  }

  private static List<String> sortedDistinct(List<String> values) {
    Set<String> sorted = new TreeSet<>(TEXT_ORDER);
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        sorted.add(value.trim());
      }
    }
    return List.copyOf(sorted);
  }

  private static String normalize(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }
}
