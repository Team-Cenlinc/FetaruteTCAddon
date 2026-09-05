package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalDecisionInputClassifier;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalDecisionInputType;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyAcquiredEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyQueueChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyReleasedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;

/**
 * 基于内存 Map 的占用管理器，适合作为“最小可用版本”。
 *
 * <p>占用释放由事件驱动触发：释放后即认为可重新判定，不依赖 releaseAt；headway 仅作为配置保留。
 *
 * <p>单线走廊冲突支持方向锁：同向允许多车跟驰，对向互斥。
 *
 * <p>道岔、单线与物理联锁冲突使用 Gate Queue 保障进入顺序，并基于 priority、等待老化与 lookahead entryOrder 选择稳定
 * winner。物理联锁始终按无方向共享资源互斥，不复用单线同向跟驰规则。
 *
 * <p>这个实现只负责“资源互斥 + 队列公平性 + 冲突区放行”，不承担列车控车、恢复和调度重排。若上层信号看起来不稳定， 这里优先排查的通常是队列位次、锁定边界和 claim
 * 释放粒度，而不是时刻表本身。
 *
 * <p>冲突区放行只处理已证明正在清空冲突区出口的 CONFLICT blocker；真实 {@code MOVEMENT_REQUIRED} NODE/EDGE 硬占用始终按 STOP
 * 处理。外部列车的 {@code PROTECTIVE_RETAIN}/{@code HOLD_ONLY} NODE/EDGE 同样表示尚未释放的物理窗口，不能仅凭同一
 * single-corridor 方向一致而共享；同向吞吐必须来自互不重叠的 EDGE/NODE 窗口。{@code PHYSICAL_FOOTPRINT}
 * 表示折返交接后尚未取得列尾清空证据的完整旧进路足迹，也始终作为硬占用。
 */
public final class SimpleOccupancyManager
    implements OccupancyManager,
        OccupancyQueueSupport,
        OccupancyPreviewSupport,
        OccupancyAdvisoryPreviewSupport,
        AuthorityHandoffSupport,
        StartupOccupancyReconstructionSupport,
        PhysicalFootprintHydrationSupport {

  public static final String OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER =
      "OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER";

  private static final String SINGLE_SECTION_CONFLICT_PREFIX = "single:section:";
  private static final Duration QUEUE_ENTRY_TTL = Duration.ofSeconds(30);
  private static final Duration QUEUE_PRIORITY_POINT_ADVANTAGE = Duration.ofMillis(500);
  private static final Duration QUEUE_MAX_PRIORITY_ADVANTAGE = Duration.ofMinutes(2);
  private static final LiveBlockerSnapshotListener NOOP_LIVE_BLOCKER_SNAPSHOT_LISTENER =
      (trainName, decision, request, sampledAt, source) -> {};

  /** 冲突区放行锁定时长：一旦放行某车，在此期间内不允许对手车放行，避免信号乒乓。 */
  private static final Duration DEADLOCK_RELEASE_LOCK_TTL = Duration.ofSeconds(8);

  private final HeadwayRule headwayRule;
  private final SignalAspectPolicy signalPolicy;
  private final SignalEventBus eventBus;
  private final Map<OccupancyResource, List<OccupancyClaim>> claims = new LinkedHashMap<>();
  private final Map<OccupancyResource, ConflictQueue> queues = new LinkedHashMap<>();
  private final Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature>
      switcherClaimSignatures = new LinkedHashMap<>();
  private final Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature>
      switcherQueueSignatures = new LinkedHashMap<>();
  private final AtomicLong version = new AtomicLong();
  private final AtomicLong lifecycleSequence = new AtomicLong();
  private final AtomicLong staleQueueCleanupCount = new AtomicLong();
  private final Map<String, SelfOwnedStaleRetainCandidate> selfOwnedStaleRetainCandidates =
      new LinkedHashMap<>();
  private volatile LiveBlockerSnapshotListener liveBlockerSnapshotListener =
      NOOP_LIVE_BLOCKER_SNAPSHOT_LISTENER;

  /** 冲突区放行锁：key=冲突资源 key，value=被放行列车与锁定过期时间。 */
  private final Map<String, DeadlockReleaseLock> deadlockReleaseLocks = new LinkedHashMap<>();

  /**
   * OCCUPANCY 层产生阻塞判定时的轻量旁路监听。
   *
   * <p>监听只用于把最近 blocker 证据同步给健康监控与 Smart recovery，不参与占用仲裁，也不改变信号发布结果。
   */
  public interface LiveBlockerSnapshotListener {

    /**
     * 记录一次阻塞判定。
     *
     * @param trainName 被阻塞列车名
     * @param decision 阻塞判定
     * @param request 原始占用请求
     * @param sampledAt 采样时间
     * @param source 产生判定的方法/原因
     */
    void onBlockedDecision(
        String trainName,
        OccupancyDecision decision,
        OccupancyRequest request,
        Instant sampledAt,
        String source);
  }

  /**
   * Smart recovery 可释放的自持保护性 retain 候选。
   *
   * <p>候选只来自最近一次真实占用判定：列车被自己持有的 CONFLICT retain 卡住，且该 claim 不是当前前进必须 claim。它不会授权放行，只为 Smart
   * recovery 的 OCCUPANCY_MUTATION action 提供最小可验证目标。{@code hardAuthorityScope}
   * 固化采样时完整的前进硬资源集合，最终释放前必须重新检查其中是否出现外车 claim，避免 health 的缩减复核请求遗忘 NODE、EDGE 或物理联锁 blocker。
   */
  public record SelfOwnedStaleRetainCandidate(
      String trainName,
      OccupancyResource resource,
      ClaimRole claimRole,
      ResourceIntent requestIntent,
      CorridorDirection heldDirection,
      CorridorDirection requestedDirection,
      Set<OccupancyResource> hardAuthorityScope,
      String reason,
      Instant sampledAt) {

    public SelfOwnedStaleRetainCandidate {
      trainName = trainName == null ? "" : trainName.trim();
      Objects.requireNonNull(resource, "resource");
      claimRole = claimRole == null ? ClaimRole.MOVEMENT_REQUIRED : claimRole;
      requestIntent = requestIntent == null ? ResourceIntent.MOVEMENT_REQUIRED : requestIntent;
      heldDirection = heldDirection == null ? CorridorDirection.UNKNOWN : heldDirection;
      requestedDirection =
          requestedDirection == null ? CorridorDirection.UNKNOWN : requestedDirection;
      hardAuthorityScope = hardAuthorityScope == null ? Set.of() : Set.copyOf(hardAuthorityScope);
      reason = reason == null || reason.isBlank() ? "self-owned-retain" : reason.trim();
      sampledAt = sampledAt == null ? Instant.EPOCH : sampledAt;
    }
  }

  /** Smart recovery 自持 retain 释放结果。 */
  public record SelfOwnedStaleRetainReleaseResult(
      boolean candidate,
      boolean released,
      String reason,
      List<OccupancyResource> releasedResources) {

    public SelfOwnedStaleRetainReleaseResult {
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
      releasedResources = releasedResources == null ? List.of() : List.copyOf(releasedResources);
    }
  }

  /** 单线方向解析来源，用于区分请求窗口与完整行车快照。 */
  private enum DirectionSource {
    REQUEST_CORRIDOR_DIRECTIONS,
    REQUEST_SECTION_TOKEN_DIRECTIONS,
    MOVEMENT_PLAN_SINGLE_CONFLICT_DIRECTIONS,
    MOVEMENT_PLAN_SECTION_TOKEN_DIRECTIONS,
    HELD_DIRECTION_FALLBACK,
    UNKNOWN
  }

  /** 单线方向解析结果。 */
  private record DirectionResolution(
      Optional<CorridorDirection> direction, DirectionSource source) {

    private DirectionResolution {
      direction = direction == null ? Optional.empty() : direction;
      source = source == null ? DirectionSource.UNKNOWN : source;
    }

    private static DirectionResolution unknown() {
      return new DirectionResolution(Optional.empty(), DirectionSource.UNKNOWN);
    }
  }

  /** 已提交 single token 的规范化键。 */
  private record SingleConflictToken(String namespace, String axis, boolean section) {
    private SingleConflictToken {
      namespace = namespace == null ? "" : namespace.trim();
      axis = axis == null ? "" : axis.trim();
    }
  }

  /** 自持 continuation 被外部 blocker 阻断时的最小可审计身份。 */
  private record ExternalBlockerDetail(String owner, String resourceKey) {
    private ExternalBlockerDetail {
      owner = owner == null || owner.isBlank() ? "-" : owner.trim();
      resourceKey = resourceKey == null || resourceKey.isBlank() ? "-" : resourceKey.trim();
    }
  }

  /** 道岔冲突签名缓存 key。 */
  private record SwitcherClaimKey(OccupancyResource resource, String trainKey) {
    private SwitcherClaimKey {
      Objects.requireNonNull(resource, "resource");
      trainKey = TrainNameNormalizer.normalizeKey(trainKey);
    }
  }

  /**
   * 构建占用管理器（无事件总线）。
   *
   * @param headwayRule 追踪间隔策略
   * @param signalPolicy 信号优先级与放行策略
   */
  public SimpleOccupancyManager(HeadwayRule headwayRule, SignalAspectPolicy signalPolicy) {
    this(headwayRule, signalPolicy, null);
  }

  /**
   * 构建占用管理器。
   *
   * @param headwayRule 追踪间隔策略
   * @param signalPolicy 信号优先级与放行策略
   * @param eventBus 信号事件总线（可选，为 null 时不发布事件）
   */
  public SimpleOccupancyManager(
      HeadwayRule headwayRule, SignalAspectPolicy signalPolicy, SignalEventBus eventBus) {
    this.headwayRule = Objects.requireNonNull(headwayRule, "headwayRule");
    this.signalPolicy = signalPolicy != null ? signalPolicy : SignalAspectPolicy.defaultPolicy();
    this.eventBus = eventBus;
  }

  /** 返回占用/队列快照版本。claim 或 queue 发生真实变更时递增。 */
  public long version() {
    return version.get();
  }

  /** 返回因 TTL 清理的 queue entry 数量。 */
  public long staleQueueCleanupCount() {
    return staleQueueCleanupCount.get();
  }

  /**
   * 以单次提交替换启动现场逻辑保护与稀疏物理占用。
   *
   * <p>方法先在局部结构中完成全部校验与 claim 构建，再替换 live map；任何无效请求都会保持旧快照不变。Route/Node 推导资源保持原逻辑角色，只有快照显式标记的
   * sparse Zone 写为 {@link ClaimRole#PHYSICAL_FOOTPRINT}。现场重叠不会被准入仲裁吞掉，恢复后的运动授权仍能看到全部真实冲突并保持 STOP。
   */
  @Override
  public synchronized ReconstructionResult reconstructPhysicalSnapshot(
      List<FieldOccupancySnapshot> fieldSnapshots) {
    if (fieldSnapshots == null) {
      return ReconstructionResult.rejected("field-snapshots-null");
    }
    List<FieldOccupancySnapshot> orderedSnapshots = new ArrayList<>(fieldSnapshots);
    if (orderedSnapshots.stream().anyMatch(Objects::isNull)) {
      return ReconstructionResult.rejected("field-snapshot-null");
    }
    orderedSnapshots.sort(
        java.util.Comparator.comparing(
            snapshot -> TrainNameNormalizer.normalizeKey(snapshot.request().trainName())));
    Set<String> trainKeys = new LinkedHashSet<>();
    List<StartupFieldClaim> stagedClaims = new ArrayList<>();
    Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> stagedSignatures =
        new LinkedHashMap<>();
    for (FieldOccupancySnapshot snapshot : orderedSnapshots) {
      OccupancyRequest request = snapshot.request();
      String trainKey = TrainNameNormalizer.normalizeKey(request.trainName());
      if (trainKey.isBlank() || !trainKeys.add(trainKey)) {
        return ReconstructionResult.rejected("duplicate-or-empty-train:" + trainKey);
      }
      if (request.resourceList().isEmpty()) {
        return ReconstructionResult.rejected("field-resources-empty:" + request.trainName());
      }
      if (!request.resourceList().containsAll(snapshot.physicalResources())) {
        return ReconstructionResult.rejected(
            "physical-resource-outside-field-request:" + request.trainName());
      }
      Set<OccupancyResource> resources = new LinkedHashSet<>();
      for (OccupancyResource resource : request.resourceList()) {
        if (resource == null || !resources.add(resource)) {
          return ReconstructionResult.rejected(
              "invalid-or-duplicate-resource:" + request.trainName());
        }
        if (request.intentFor(resource).hardAuthority()) {
          return ReconstructionResult.rejected(
              "movement-authority-not-allowed:" + request.trainName() + ":" + resource);
        }
        OccupancyClaim claim =
            new OccupancyClaim(
                resource,
                request.trainName(),
                request.routeId(),
                request.now(),
                headwayRule.headwayFor(request.routeId(), resource),
                resolveCorridorDirection(request, resource),
                snapshot.roleFor(resource));
        stagedClaims.add(new StartupFieldClaim(resource, trainKey, claim));
        if (isSwitcherConflictResource(resource)) {
          switcherSignatureFor(request, resource)
              .ifPresent(
                  signature ->
                      stagedSignatures.put(
                          new SwitcherClaimKey(resource, request.trainName()), signature));
        }
      }
    }
    stagedClaims.sort(
        java.util.Comparator.comparing(
                (StartupFieldClaim staged) -> staged.resource().kind().name())
            .thenComparing(staged -> staged.resource().key())
            .thenComparing(StartupFieldClaim::trainKey));
    Map<OccupancyResource, List<OccupancyClaim>> nextClaims = new LinkedHashMap<>();
    for (StartupFieldClaim staged : stagedClaims) {
      nextClaims
          .computeIfAbsent(staged.resource(), unused -> new ArrayList<>())
          .add(staged.claim());
    }

    Map<OccupancyResource, List<OccupancyClaim>> previousClaims = copyClaims(claims);
    Map<OccupancyResource, List<OccupancyQueueEntry>> previousQueues = snapshotQueueEntries();
    Map<String, DeadlockReleaseLock> previousReleaseLocks =
        new LinkedHashMap<>(deadlockReleaseLocks);
    claims.clear();
    claims.putAll(nextClaims);
    queues.clear();
    switcherClaimSignatures.clear();
    switcherClaimSignatures.putAll(stagedSignatures);
    switcherQueueSignatures.clear();
    deadlockReleaseLocks.clear();
    selfOwnedStaleRetainCandidates.clear();
    version.incrementAndGet();
    traceReconstructionDiff(previousClaims, previousQueues, previousReleaseLocks, nextClaims);
    return ReconstructionResult.committed(trainKeys.size(), stagedClaims.size());
  }

  /** 已验证、尚未提交的单条现场 claim。 */
  private record StartupFieldClaim(
      OccupancyResource resource, String trainKey, OccupancyClaim claim) {}

  /** 深拷贝当前 claim 列表结构，供原子提交后生成稳定差异。 */
  private Map<OccupancyResource, List<OccupancyClaim>> copyClaims(
      Map<OccupancyResource, List<OccupancyClaim>> source) {
    Map<OccupancyResource, List<OccupancyClaim>> copy = new LinkedHashMap<>();
    if (source == null || source.isEmpty()) {
      return copy;
    }
    source.forEach(
        (resource, resourceClaims) ->
            copy.put(resource, resourceClaims == null ? List.of() : List.copyOf(resourceClaims)));
    return copy;
  }

  /** 复制全部队列条目；返回值不再引用可变的内部 queue map。 */
  private Map<OccupancyResource, List<OccupancyQueueEntry>> snapshotQueueEntries() {
    Map<OccupancyResource, List<OccupancyQueueEntry>> snapshot = new LinkedHashMap<>();
    queues.forEach(
        (resource, queue) ->
            snapshot.put(
                resource, queue == null ? List.of() : queue.snapshotEntries(Instant.EPOCH)));
    return snapshot;
  }

  /** 输出启动原子提交造成的 claim、等待队列与 release lock 差异。 */
  private void traceReconstructionDiff(
      Map<OccupancyResource, List<OccupancyClaim>> previousClaims,
      Map<OccupancyResource, List<OccupancyQueueEntry>> previousQueues,
      Map<String, DeadlockReleaseLock> previousReleaseLocks,
      Map<OccupancyResource, List<OccupancyClaim>> nextClaims) {
    traceClaimDiff(previousClaims, nextClaims, "snapshot-", "startup-reconstruction");
    previousQueues.forEach(
        (resource, entries) ->
            entries.forEach(
                entry ->
                    traceQueueLifecycle(
                        resource,
                        entry,
                        "WAITING_TRAIN",
                        "snapshot-clear",
                        "startup-reconstruction",
                        null)));
    previousReleaseLocks.forEach(
        (conflictKey, lock) ->
            traceQueueLifecycle(
                OccupancyResource.forConflict(conflictKey),
                lock.trainName(),
                null,
                null,
                null,
                null,
                null,
                null,
                "DEADLOCK_RELEASE_LOCK",
                "snapshot-clear",
                "startup-reconstruction",
                lock.expiresAt()));
  }

  /** 按 resource + canonical train owner 输出两份 claim 快照的差异。 */
  private void traceClaimDiff(
      Map<OccupancyResource, List<OccupancyClaim>> previousClaims,
      Map<OccupancyResource, List<OccupancyClaim>> nextClaims,
      String eventPrefix,
      String reason) {
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    resources.addAll(previousClaims.keySet());
    resources.addAll(nextClaims.keySet());
    for (OccupancyResource resource : resources) {
      Map<String, OccupancyClaim> before = claimsByTrain(previousClaims.get(resource));
      Map<String, OccupancyClaim> after = claimsByTrain(nextClaims.get(resource));
      Set<String> owners = new LinkedHashSet<>();
      owners.addAll(before.keySet());
      owners.addAll(after.keySet());
      for (String owner : owners) {
        OccupancyClaim oldClaim = before.get(owner);
        OccupancyClaim newClaim = after.get(owner);
        if (Objects.equals(oldClaim, newClaim)) {
          continue;
        }
        String event =
            oldClaim == null
                ? eventPrefix + "acquire"
                : newClaim == null ? eventPrefix + "remove" : eventPrefix + "update";
        traceResourceLifecycle(null, resource, oldClaim, newClaim, event, reason);
      }
    }
  }

  /** 将一组 claim 按规范列车名索引，供差异计算使用。 */
  private Map<String, OccupancyClaim> claimsByTrain(List<OccupancyClaim> source) {
    Map<String, OccupancyClaim> byTrain = new LinkedHashMap<>();
    if (source == null || source.isEmpty()) {
      return byTrain;
    }
    for (OccupancyClaim claim : source) {
      if (claim == null) {
        continue;
      }
      byTrain.put(TrainNameNormalizer.normalizeKey(claim.trainName()), claim);
    }
    return byTrain;
  }

  /**
   * 原子替换单列现场逻辑保护与稀疏物理 footprint，同时保留所有其它 owner 的 claim 与排队状态。
   *
   * <p>全部校验与 claim 构建在修改 live map 之前完成；进入提交段后方法受同一 monitor 保护，外部观察不到“先删后加”的中间状态。现场重叠仍会保留多个 {@link
   * ClaimRole#PHYSICAL_FOOTPRINT} owner。
   */
  @Override
  public synchronized ReconstructionResult replaceTrainPhysicalFootprint(
      FieldOccupancySnapshot fieldSnapshot) {
    if (fieldSnapshot == null) {
      return ReconstructionResult.rejected("field-snapshot-null");
    }
    OccupancyRequest fieldRequest = fieldSnapshot.request();
    String trainKey = TrainNameNormalizer.normalizeKey(fieldRequest.trainName());
    if (trainKey.isBlank()) {
      return ReconstructionResult.rejected("field-train-empty");
    }
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    List<StartupFieldClaim> stagedClaims = new ArrayList<>();
    Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> stagedSignatures =
        new LinkedHashMap<>();
    if (!fieldRequest.resourceList().containsAll(fieldSnapshot.physicalResources())) {
      return ReconstructionResult.rejected(
          "physical-resource-outside-field-request:" + fieldRequest.trainName());
    }
    for (OccupancyResource resource : fieldRequest.resourceList()) {
      if (resource == null || !resources.add(resource)) {
        return ReconstructionResult.rejected(
            "invalid-or-duplicate-resource:" + fieldRequest.trainName());
      }
      if (fieldRequest.intentFor(resource).hardAuthority()) {
        return ReconstructionResult.rejected(
            "movement-authority-not-allowed:" + fieldRequest.trainName() + ":" + resource);
      }
      OccupancyClaim claim =
          new OccupancyClaim(
              resource,
              fieldRequest.trainName(),
              fieldRequest.routeId(),
              fieldRequest.now(),
              headwayRule.headwayFor(fieldRequest.routeId(), resource),
              resolveCorridorDirection(fieldRequest, resource),
              fieldSnapshot.roleFor(resource));
      stagedClaims.add(new StartupFieldClaim(resource, trainKey, claim));
      if (isSwitcherConflictResource(resource)) {
        switcherSignatureFor(fieldRequest, resource)
            .ifPresent(
                signature ->
                    stagedSignatures.put(
                        new SwitcherClaimKey(resource, fieldRequest.trainName()), signature));
      }
    }
    if (stagedClaims.isEmpty()) {
      return ReconstructionResult.rejected("field-resources-empty:" + fieldRequest.trainName());
    }

    Set<OccupancyResource> previousResources =
        claims.entrySet().stream()
            .filter(
                entry ->
                    entry.getValue() != null
                        && entry.getValue().stream()
                            .anyMatch(
                                claim ->
                                    claim != null
                                        && TrainNameNormalizer.sameLogicalTrain(
                                            claim.trainName(), fieldRequest.trainName())))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Map<OccupancyResource, List<OccupancyClaim>> previousClaims = copyClaims(claims);
    removeClaimsByTrainWithoutEvents(fieldRequest.trainName());
    removeFromQueuesForTrain(fieldRequest.trainName());
    releaseDeadlockLocksForTrain(fieldRequest.trainName());
    selfOwnedStaleRetainCandidates.remove(trainKey);
    stagedClaims.sort(
        java.util.Comparator.comparing(
                (StartupFieldClaim staged) -> staged.resource().kind().name())
            .thenComparing(staged -> staged.resource().key()));
    for (StartupFieldClaim staged : stagedClaims) {
      claims.computeIfAbsent(staged.resource(), unused -> new ArrayList<>()).add(staged.claim());
    }
    switcherClaimSignatures.putAll(stagedSignatures);
    version.incrementAndGet();
    traceClaimDiff(
        previousClaims, copyClaims(claims), "field-hydration-", "field-footprint-hydration");
    previousResources.removeAll(resources);
    if (!previousResources.isEmpty()) {
      publishReleasedEvent(
          fieldRequest.trainName(), List.copyOf(previousResources), fieldRequest.now());
    }
    publishAcquiredEvent(fieldRequest, List.copyOf(resources), fieldRequest.now());
    return ReconstructionResult.committed(1, stagedClaims.size());
  }

  /**
   * 设置阻塞快照旁路监听。
   *
   * <p>监听器不得回调占用写接口；它只应复制诊断证据到上层恢复组件。
   *
   * @param listener 新监听器；为 null 时恢复为空实现
   */
  public void setLiveBlockerSnapshotListener(LiveBlockerSnapshotListener listener) {
    liveBlockerSnapshotListener = listener == null ? NOOP_LIVE_BLOCKER_SNAPSHOT_LISTENER : listener;
  }

  /**
   * 执行一次可写入的进路准入判定。
   *
   * <p>该入口会维护过期队列、刷新冲突队列位次，并可能推进 occupancy version。只读预览必须调用 {@link
   * #canEnterPreview(OccupancyRequest)}。
   */
  @Override
  public synchronized OccupancyDecision canEnter(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    Instant now = request.now();
    purgeExpiredQueueEntries(now);
    List<OccupancyClaim> blockers = new ArrayList<>();
    Set<OccupancyResource> blockedResources = new LinkedHashSet<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      boolean hardAuthority = request.intentFor(resource).hardAuthority();
      if (hardAuthority) {
        Optional<OccupancyDecision> directionBlocked =
            failClosedUnknownSingleConflictEntry(request, resource, now);
        if (directionBlocked.isPresent()) {
          return traceDecision("canEnter:unknown-single", request, directionBlocked.get());
        }
      }
      List<OccupancyClaim> existing = claims.get(resource);
      if (existing == null || existing.isEmpty()) {
        continue;
      }
      for (OccupancyClaim claim : existing) {
        if (claim == null) {
          continue;
        }
        BlockerRelation relation = BlockerClassifier.classify(request, resource, claim);
        if (relation == BlockerRelation.SELF) {
          Optional<OccupancyDecision> selfBlocked =
              selfOwnedSingleDirectionMismatchDecision(request, resource, claim, now, "canEnter");
          if (selfBlocked.isPresent()) {
            return traceDecision(
                "canEnter:self-owned-single-opposite-direction", request, selfBlocked.get());
          }
          continue;
        }
        if (singleRegionOppositeOrUnknownExternalBarrier(request, resource, claim)) {
          if (!hardAuthority) {
            return traceDecision(
                "canEnter:" + OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER,
                request,
                singleRegionHardBarrierDecision(now, claim));
          }
          blockers.add(claim);
          blockedResources.add(resource);
          continue;
        }
        if (relation == BlockerRelation.STALE_PROTECTIVE_CLAIM) {
          continue;
        }
        if (relation == BlockerRelation.SWITCHER_CONFLICT
            && sameDirectionSectionAllowsSwitcherClaim(request, resource, claim, "canEnter")) {
          continue;
        }
        if (!hardAuthority || relation == BlockerRelation.SAME_DIRECTION_FRONT) {
          continue;
        }
        blockers.add(claim);
        blockedResources.add(resource);
      }
    }
    if (!blockers.isEmpty()) {
      Set<OccupancyResource> queueTargets = resolveQueueTargets(request, blockedResources);
      enqueueWaiting(request, queueTargets, now);
      Optional<String> singleRegionHardBarrierReason =
          singleRegionHardBarrierReason(request, blockers);
      if (singleRegionHardBarrierReason.isPresent()) {
        return traceDecision(
            "canEnter:" + singleRegionHardBarrierReason.get(),
            request,
            new OccupancyDecision(
                false,
                now,
                SignalAspect.STOP,
                List.copyOf(blockers),
                false,
                singleRegionHardBarrierReason.get()));
      }
      Optional<String> hardBlockerReason = conflictReleaseHardBlockerReason(request, blockers);
      if (hardBlockerReason.isPresent()) {
        return traceDecision(
            "canEnter:" + hardBlockerReason.get(),
            request,
            new OccupancyDecision(
                false,
                now,
                SignalAspect.STOP,
                List.copyOf(blockers),
                false,
                hardBlockerReason.get()));
      }
      OccupancyDecision resolved = tryResolveConflictDeadlock(request, blockers, now);
      if (resolved != null) {
        return traceDecision("canEnter:conflict-release", request, resolved);
      }
      return traceDecision(
          "canEnter:blockers",
          request,
          new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers)));
    }

    boolean queueBlocked = false;
    for (OccupancyResource resource : request.resourceList()) {
      if (!request.intentFor(resource).hardAuthority() || !isQueueableConflict(resource)) {
        continue;
      }
      OccupancyClaim selfClaim = findClaim(claims.get(resource), request.trainName());
      if (selfClaimBypassesQueue(selfClaim)) {
        Optional<OccupancyDecision> selfBlocked =
            selfOwnedSingleDirectionMismatchDecision(request, resource, selfClaim, now, "canEnter");
        if (selfBlocked.isPresent()) {
          return traceDecision(
              "canEnter:self-owned-single-opposite-direction", request, selfBlocked.get());
        }
        continue;
      }
      Optional<OccupancyDecision> directionBlocked =
          failClosedUnknownSingleConflictEntry(request, resource, now);
      if (directionBlocked.isPresent()) {
        return traceDecision("canEnter:unknown-single", request, directionBlocked.get());
      }
      CorridorDirection direction = queueDirectionFor(request, resource);
      ConflictQueue queue = queues.computeIfAbsent(resource, unused -> new ConflictQueue());
      QueueTouchResult queueTouch =
          touchQueueWithDirectionTrace(request, resource, queue, direction, now, "canEnter");
      CorridorDirection effectiveDirection = queueTouch.direction();
      commitQueueTouchChange(request, resource, now, queueTouch);
      if (!isQueueAllowed(request, resource, effectiveDirection, queue, now)) {
        Optional<OccupancyQueueEntry> blockingEntry = queue.blockingEntry(effectiveDirection, now);
        Optional<OccupancyQueueEntry> forcedBlocker = Optional.empty();
        if (blockingEntry.isPresent()
            && TrainNameNormalizer.sameLogicalTrain(
                blockingEntry.get().trainName(), request.trainName())) {
          if (selfOwnedQueueEntryCanContinue(request, resource, queue, blockingEntry.get())) {
            continue;
          }
          Optional<OccupancyQueueEntry> fallbackBlocker = queue.headAny(now);
          if (fallbackBlocker.isPresent()
              && !TrainNameNormalizer.sameLogicalTrain(
                  fallbackBlocker.get().trainName(), request.trainName())) {
            forcedBlocker = fallbackBlocker;
          } else {
            return traceDecision(
                "canEnter:self-owned-single-opposite-direction",
                request,
                selfOwnedQueueMismatchDecision(request, resource, blockingEntry.get(), now));
          }
        }
        queueBlocked = true;
        blockingEntry =
            forcedBlocker.or(
                () ->
                    blockingQueueEntryFor(request, resource, queue, effectiveDirection, null, now));
        tracePendingWinnerArbitration(
            "canEnter", request, resource, blockingEntry, "hold-lower-priority", "queue-blocked");
        blockingEntry.map(entry -> createQueueBlocker(resource, entry)).ifPresent(blockers::add);
      }
    }
    if (queueBlocked) {
      return traceDecision(
          "canEnter:queue-blocked",
          request,
          new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers)));
    }
    SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
    return traceDecision(
        "canEnter:allowed",
        request,
        new OccupancyDecision(true, now, signal, List.copyOf(blockers)));
  }

  /**
   * 预览占用判定（不写入状态、不入队）。
   *
   * <p>用于 ETA 估算，避免对运行时队列造成副作用。
   */
  @Override
  public synchronized OccupancyDecision canEnterPreview(OccupancyRequest request) {
    return canEnterPreview(request, Set.of());
  }

  /**
   * 执行只读准入预检，并允许停车折返中的 incumbent 在已经物理持有的资源上保留续行队列权。
   *
   * <p>{@code continuationQueueEntitlements} 只跳过纯队列竞争；外部真实 claim 仍在前半段 blocker 扫描中按普通规则拒绝。公开
   * preview 入口始终传入空集合，只有原子 handoff 会按旧物理 footprint 计算该集合。
   */
  private OccupancyDecision canEnterPreview(
      OccupancyRequest request, Set<OccupancyResource> continuationQueueEntitlements) {
    Objects.requireNonNull(request, "request");
    Set<OccupancyResource> continuationEntitlements =
        continuationQueueEntitlements == null ? Set.of() : continuationQueueEntitlements;
    Instant now = request.now();
    List<OccupancyClaim> blockers = new ArrayList<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      boolean hardAuthority = request.intentFor(resource).hardAuthority();
      if (hardAuthority && !continuationEntitlements.contains(resource)) {
        Optional<OccupancyDecision> directionBlocked =
            failClosedUnknownSingleConflictEntry(request, resource, now);
        if (directionBlocked.isPresent()) {
          return traceDecision("canEnterPreview:unknown-single", request, directionBlocked.get());
        }
      }
      List<OccupancyClaim> existing = claims.get(resource);
      if (existing == null || existing.isEmpty()) {
        continue;
      }
      for (OccupancyClaim claim : existing) {
        if (claim == null) {
          continue;
        }
        BlockerRelation relation = BlockerClassifier.classify(request, resource, claim);
        if (relation == BlockerRelation.SELF) {
          Optional<OccupancyDecision> selfBlocked =
              selfOwnedSingleDirectionMismatchDecision(
                  request, resource, claim, now, "canEnterPreview");
          if (selfBlocked.isPresent()) {
            return traceDecision(
                "canEnterPreview:self-owned-single-opposite-direction", request, selfBlocked.get());
          }
          continue;
        }
        if (singleRegionOppositeOrUnknownExternalBarrier(request, resource, claim)) {
          blockers.add(claim);
          continue;
        }
        if (relation == BlockerRelation.STALE_PROTECTIVE_CLAIM) {
          continue;
        }
        if (relation == BlockerRelation.SWITCHER_CONFLICT
            && sameDirectionSectionAllowsSwitcherClaim(
                request, resource, claim, "canEnterPreview")) {
          continue;
        }
        if (!hardAuthority || relation == BlockerRelation.SAME_DIRECTION_FRONT) {
          continue;
        }
        blockers.add(claim);
      }
    }
    if (!blockers.isEmpty()) {
      Optional<String> singleRegionHardBarrierReason =
          singleRegionHardBarrierReason(request, blockers);
      if (singleRegionHardBarrierReason.isPresent()) {
        return traceDecision(
            "canEnterPreview:" + singleRegionHardBarrierReason.get(),
            request,
            new OccupancyDecision(
                false,
                now,
                SignalAspect.STOP,
                List.copyOf(blockers),
                false,
                singleRegionHardBarrierReason.get()));
      }
      Optional<String> hardBlockerReason = conflictReleaseHardBlockerReason(request, blockers);
      if (hardBlockerReason.isPresent()) {
        return traceDecision(
            "canEnterPreview:" + hardBlockerReason.get(),
            request,
            new OccupancyDecision(
                false,
                now,
                SignalAspect.STOP,
                List.copyOf(blockers),
                false,
                hardBlockerReason.get()));
      }
      OccupancyDecision resolved = tryResolveConflictDeadlockPreview(request, blockers, now);
      if (resolved != null) {
        return traceDecision("canEnterPreview:conflict-release", request, resolved);
      }
      return traceDecision(
          "canEnterPreview:blockers",
          request,
          new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers)));
    }

    boolean queueBlocked = false;
    for (OccupancyResource resource : request.resourceList()) {
      if (!request.intentFor(resource).hardAuthority() || !isQueueableConflict(resource)) {
        continue;
      }
      if (continuationEntitlements.contains(resource)) {
        continue;
      }
      OccupancyClaim selfClaim = findClaim(claims.get(resource), request.trainName());
      if (selfClaimBypassesQueue(selfClaim)) {
        Optional<OccupancyDecision> selfBlocked =
            selfOwnedSingleDirectionMismatchDecision(
                request, resource, selfClaim, now, "canEnterPreview");
        if (selfBlocked.isPresent()) {
          return traceDecision(
              "canEnterPreview:self-owned-single-opposite-direction", request, selfBlocked.get());
        }
        continue;
      }
      Optional<OccupancyDecision> directionBlocked =
          failClosedUnknownSingleConflictEntry(request, resource, now);
      if (directionBlocked.isPresent()) {
        return traceDecision("canEnterPreview:unknown-single", request, directionBlocked.get());
      }
      CorridorDirection direction = queueDirectionFor(request, resource);
      ConflictQueue queue = queues.get(resource);
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      CorridorDirection effectiveDirection =
          effectiveQueueDirectionForPreview(request, resource, queue, direction);
      if (!isQueueAllowedPreview(
          request,
          resource,
          effectiveDirection,
          queue,
          request.priority(),
          queueEntryOrderFor(request, resource),
          now)) {
        Optional<OccupancyQueueEntry> blockingEntry = queue.blockingEntry(effectiveDirection, now);
        Optional<OccupancyQueueEntry> forcedBlocker = Optional.empty();
        if (blockingEntry.isPresent()
            && TrainNameNormalizer.sameLogicalTrain(
                blockingEntry.get().trainName(), request.trainName())) {
          if (selfOwnedQueueEntryCanContinue(request, resource, queue, blockingEntry.get())) {
            continue;
          }
          Optional<OccupancyQueueEntry> fallbackBlocker = queue.headAny(now);
          if (fallbackBlocker.isPresent()
              && !TrainNameNormalizer.sameLogicalTrain(
                  fallbackBlocker.get().trainName(), request.trainName())) {
            forcedBlocker = fallbackBlocker;
          } else {
            return traceDecision(
                "canEnterPreview:self-owned-single-opposite-direction",
                request,
                selfOwnedQueueMismatchDecision(request, resource, blockingEntry.get(), now));
          }
        }
        queueBlocked = true;
        blockingEntry =
            forcedBlocker.or(
                () ->
                    blockingQueueEntryFor(
                        request,
                        resource,
                        queue,
                        effectiveDirection,
                        queue.candidateEntry(
                            request.trainName(),
                            effectiveDirection,
                            now,
                            request.priority(),
                            queueEntryOrderFor(request, resource)),
                        now));
        tracePendingWinnerArbitration(
            "canEnterPreview",
            request,
            resource,
            blockingEntry,
            "hold-lower-priority",
            "queue-blocked");
        blockingEntry.map(entry -> createQueueBlocker(resource, entry)).ifPresent(blockers::add);
      }
    }
    if (queueBlocked) {
      return traceDecision(
          "canEnterPreview:queue-blocked",
          request,
          new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers)));
    }
    SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
    return traceDecision(
        "canEnterPreview:allowed",
        request,
        new OccupancyDecision(true, now, signal, List.copyOf(blockers)));
  }

  /**
   * 只读扫描 advisory lookahead 风险。
   *
   * <p>这里故意不复用 {@link #canEnterPreview(OccupancyRequest)} 的 hard-blocker 语义：LOOKAHEAD_PREVIEW
   * 的占用只会形成黄灯候选，不会把当前授权窗口判为 {@code allowed=false}，也不会触发排队写入。
   */
  @Override
  public synchronized List<AdvisoryRisk> scanAdvisoryRisks(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    List<AdvisoryRisk> risks = new ArrayList<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null || request.intentFor(resource) != ResourceIntent.LOOKAHEAD_PREVIEW) {
        continue;
      }
      List<OccupancyClaim> existing = claims.get(resource);
      if (existing != null) {
        for (OccupancyClaim claim : existing) {
          if (!isAdvisoryVisibleClaim(request, resource, claim)) {
            continue;
          }
          AdvisoryRisk risk =
              new AdvisoryRisk(resource, claim, advisoryRiskSourceFor(resource), "live-occupancy");
          traceAdvisoryRisk(request, risk);
          risks.add(risk);
        }
      }
      if (!isQueueableConflict(resource)) {
        continue;
      }
      OccupancyClaim selfClaim = findClaim(existing, request.trainName());
      if (selfClaimBypassesQueue(selfClaim)) {
        /*
         * 排队位次只决定尚未取得进路者的次序。当前列车已经持有同一资源的可执行
         * MOVEMENT_REQUIRED claim 时，失败方的纯队列记录不能反向成为停车点；
         * 外部 live claim 已在上方独立扫描，仍会保持 fail-closed。
         */
        continue;
      }
      ConflictQueue queue = queues.get(resource);
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      for (OccupancyQueueEntry entry : queue.snapshotEntries(request.now())) {
        if (entry == null
            || TrainNameNormalizer.sameLogicalTrain(entry.trainName(), request.trainName())) {
          continue;
        }
        if (!isAdvisoryVisibleQueueEntry(request, resource, entry)) {
          continue;
        }
        OccupancyClaim blocker = createQueueBlocker(resource, entry);
        AdvisoryRisk risk =
            new AdvisoryRisk(resource, blocker, AdvisoryRiskSource.CONFLICT_QUEUE, "queue-entry");
        traceAdvisoryRisk(request, risk);
        risks.add(risk);
      }
    }
    return List.copyOf(risks);
  }

  /**
   * 获取占用：将申请列车写入资源占用与队列状态。
   *
   * <p>若不可进入则返回拒绝决策，并写入排队快照供诊断使用。
   *
   * <p>只有 claim、道岔签名或关联队列实际变化时才发布 {@link OccupancyAcquiredEvent}；同值心跳刷新不会推进版本或触发信号重评估。
   */
  @Override
  public synchronized OccupancyDecision acquire(OccupancyRequest request) {
    request = Objects.requireNonNull(request, "request").withoutLookaheadPreviewResources();
    OccupancyDecision decision = canEnter(request);
    if (!decision.allowed()) {
      return decision;
    }
    Instant now = request.now();
    if (decision.conflictRelease()) {
      Optional<String> hardBlockerReason =
          conflictReleaseHardBlockerReason(request, decision.blockers());
      if (hardBlockerReason.isPresent()) {
        return new OccupancyDecision(
            false, now, SignalAspect.STOP, decision.blockers(), false, hardBlockerReason.get());
      }
      Optional<OccupancyClaim> unscopedBlocker =
          firstConflictBlockerOutsideActiveRelease(request, decision.blockers(), now);
      if (unscopedBlocker.isPresent()) {
        return new OccupancyDecision(
            false,
            now,
            SignalAspect.STOP,
            decision.blockers(),
            false,
            "conflict-release-unscoped-blocker:" + unscopedBlocker.get().resource());
      }
    }
    Set<OccupancyResource> blockedResources =
        decision.conflictRelease()
            ? resolveBlockedResourcesForPartialAcquire(request, decision, now)
            : Set.of();
    List<OccupancyResource> admittedResources = new ArrayList<>();
    List<OccupancyResource> changedResources = new ArrayList<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      if (blockedResources.contains(resource)) {
        continue;
      }
      Duration headway = headwayRule.headwayFor(request.routeId(), resource);
      List<OccupancyClaim> existing = claims.computeIfAbsent(resource, unused -> new ArrayList<>());
      OccupancyClaim current = findClaim(existing, request.trainName());
      if (current == null
          && !request.intentFor(resource).hardAuthority()
          && hasOtherLogicalClaim(existing, request.trainName())) {
        continue;
      }
      if (current == null
          && !request.intentFor(resource).hardAuthority()
          && hasOtherQueueEntry(resource, request.trainName())) {
        continue;
      }
      Optional<CorridorDirection> direction = resolveCorridorDirection(request, resource);
      ClaimRole requestedRole = request.claimRoleFor(resource);
      if (current != null) {
        Duration nextHeadway =
            current.headway().compareTo(headway) >= 0 ? current.headway() : headway;
        ClaimRefresh refresh = resolveClaimRefresh(current, requestedRole, direction);
        OccupancyClaim updated =
            new OccupancyClaim(
                resource,
                current.trainName(),
                request.routeId(),
                current.acquiredAt(),
                nextHeadway,
                refresh.direction(),
                refresh.role());
        boolean claimChanged = !updated.equals(current);
        if (claimChanged) {
          existing.remove(current);
          existing.add(updated);
        }
        boolean signatureChanged = rememberSwitcherClaimSignature(request, resource);
        boolean stateChanged = claimChanged || signatureChanged;
        traceOccupancyClaimMerge(
            request,
            current,
            requestedRole,
            refresh.role(),
            direction,
            refresh.direction(),
            refresh.reason());
        String lifecycleEvent =
            !stateChanged
                ? "refresh-noop"
                : refresh.role() == ClaimRole.MOVEMENT_REQUIRED ? "merge" : "retain";
        traceResourceLifecycle(
            request, resource, current, updated, lifecycleEvent, refresh.reason());
        admittedResources.add(resource);
        if (stateChanged) {
          changedResources.add(resource);
        }
        continue;
      }
      OccupancyClaim created =
          new OccupancyClaim(
              resource,
              request.trainName(),
              request.routeId(),
              now,
              headway,
              direction,
              requestedRole);
      existing.add(created);
      rememberSwitcherClaimSignature(request, resource);
      traceResourceLifecycle(request, resource, null, created, "acquire", "new-claim");
      admittedResources.add(resource);
      changedResources.add(resource);
    }
    int removedQueueEntries =
        admittedResources.isEmpty()
            ? 0
            : removeFromQueuesForResources(request.trainName(), admittedResources, false);
    if (!changedResources.isEmpty() || removedQueueEntries > 0) {
      version.incrementAndGet();
    }
    // 发布占用获取事件
    List<OccupancyResource> eventResources =
        removedQueueEntries > 0 ? List.copyOf(admittedResources) : changedResources;
    publishAcquiredEvent(request, eventResources, now);
    return decision;
  }

  /**
   * 在列车停车换向时原子替换旧、新行车授权。
   *
   * <p>预检阶段暂时从内存视图隐藏本车旧 claim 与队列位次，使普通准入逻辑只评估外部竞争者；该暂存状态不会推进
   * version，也不会发布事件。拒绝时完整恢复。成功时以新授权替换重叠资源，并把未被新窗口覆盖的旧物理 footprint 保留为专用硬角色，等待正常
   * rear-clear/推进流程释放；整个过程不会发布虚假的资源释放事件。
   */
  @Override
  public synchronized OccupancyDecision handoffAuthority(OccupancyRequest nextAuthority) {
    Objects.requireNonNull(nextAuthority, "nextAuthority");
    if (!nextAuthority.hasMovementRequiredResources()) {
      return handoffRejected(nextAuthority, "authority-handoff-missing-hard-authority", List.of());
    }
    DetachedAuthorityState detached = detachAuthorityState(nextAuthority.trainName());
    if (detached.claims().isEmpty()) {
      restoreAuthorityState(detached);
      return handoffRejected(
          nextAuthority, "authority-handoff-current-authority-missing", List.of());
    }

    OccupancyDecision preflight;
    try {
      preflight =
          canEnterPreview(
              nextAuthority, handoffContinuationQueueEntitlements(nextAuthority, detached));
    } catch (RuntimeException exception) {
      restoreAuthorityState(detached);
      throw exception;
    }
    if (!preflight.allowed() || preflight.conflictRelease() || !preflight.blockers().isEmpty()) {
      restoreAuthorityState(detached);
      if (!preflight.allowed()) {
        return preflight;
      }
      return handoffRejected(
          nextAuthority, "authority-handoff-requires-clean-window", preflight.blockers());
    }

    List<OccupancyResource> admittedResources = new ArrayList<>();
    try {
      for (OccupancyResource resource : nextAuthority.resourceList()) {
        if (resource == null) {
          continue;
        }
        List<OccupancyClaim> existing =
            claims.computeIfAbsent(resource, unused -> new ArrayList<>());
        if (!nextAuthority.intentFor(resource).hardAuthority()
            && (hasOtherLogicalClaim(existing, nextAuthority.trainName())
                || hasOtherQueueEntry(resource, nextAuthority.trainName()))) {
          if (existing.isEmpty()) {
            claims.remove(resource);
          }
          continue;
        }
        Optional<OccupancyClaim> retainedFootprint =
            nextAuthority.intentFor(resource).hardAuthority()
                ? Optional.empty()
                : detachedPhysicalClaim(detached, resource);
        OccupancyClaim created =
            retainedFootprint
                .map(claim -> retainedFootprintClaim(nextAuthority.trainName(), claim))
                .orElseGet(
                    () ->
                        new OccupancyClaim(
                            resource,
                            nextAuthority.trainName(),
                            nextAuthority.routeId(),
                            nextAuthority.now(),
                            headwayRule.headwayFor(nextAuthority.routeId(), resource),
                            resolveCorridorDirection(nextAuthority, resource),
                            nextAuthority.claimRoleFor(resource)));
        existing.add(created);
        if (retainedFootprint.isEmpty()) {
          rememberSwitcherClaimSignature(nextAuthority, resource);
          admittedResources.add(resource);
        }
        traceResourceLifecycle(
            nextAuthority,
            resource,
            null,
            created,
            retainedFootprint.isPresent()
                ? "authority-handoff-retain"
                : "authority-handoff-acquire",
            retainedFootprint.isPresent() ? "terminal-footprint-retained" : "terminal-turnback");
      }
      retainDetachedFootprint(nextAuthority.trainName(), detached);
    } catch (RuntimeException exception) {
      removeClaimsByTrainWithoutEvents(nextAuthority.trainName());
      restoreAuthorityState(detached);
      throw exception;
    }

    for (OccupancyClaim oldClaim : detached.claims()) {
      OccupancyClaim replacement =
          findClaim(claims.get(oldClaim.resource()), nextAuthority.trainName());
      traceResourceLifecycle(
          nextAuthority,
          oldClaim.resource(),
          oldClaim,
          replacement,
          replacement != null && replacement.role() == ClaimRole.PHYSICAL_FOOTPRINT
              ? "authority-handoff-retain"
              : "authority-handoff-replace",
          replacement != null && replacement.role() == ClaimRole.PHYSICAL_FOOTPRINT
              ? "terminal-footprint-retained"
              : "terminal-turnback");
    }
    removeFromQueuesForTrain(nextAuthority.trainName());
    releaseDeadlockLocksForTrain(nextAuthority.trainName());
    selfOwnedStaleRetainCandidates.remove(
        TrainNameNormalizer.normalizeKey(nextAuthority.trainName()));
    version.incrementAndGet();

    publishAcquiredEvent(nextAuthority, List.copyOf(admittedResources), nextAuthority.now());
    return preflight;
  }

  private void retainDetachedFootprint(String trainName, DetachedAuthorityState detached) {
    for (OccupancyClaim oldClaim : detached.claims()) {
      if (oldClaim == null
          || oldClaim.resource() == null
          || !isRetainableFootprintClaim(oldClaim)
          || findClaim(claims.get(oldClaim.resource()), trainName) != null) {
        continue;
      }
      OccupancyClaim retained =
          new OccupancyClaim(
              oldClaim.resource(),
              oldClaim.trainName(),
              oldClaim.routeId(),
              oldClaim.acquiredAt(),
              oldClaim.headway(),
              oldClaim.corridorDirection(),
              ClaimRole.PHYSICAL_FOOTPRINT);
      claims.computeIfAbsent(oldClaim.resource(), unused -> new ArrayList<>()).add(retained);
    }
    for (var entry : detached.claimSignatures().entrySet()) {
      if (detachedPhysicalClaim(detached, entry.getKey().resource()).isPresent()) {
        switcherClaimSignatures.putIfAbsent(entry.getKey(), entry.getValue());
      }
    }
  }

  private Optional<OccupancyClaim> detachedPhysicalClaim(
      DetachedAuthorityState detached, OccupancyResource resource) {
    if (detached == null || resource == null) {
      return Optional.empty();
    }
    return detached.claims().stream()
        .filter(Objects::nonNull)
        .filter(claim -> resource.equals(claim.resource()))
        .filter(SimpleOccupancyManager::isRetainableFootprintClaim)
        .findFirst();
  }

  /**
   * 计算 handoff 预检可保留的 incumbent 队列权。
   *
   * <p>旧 claim 必须能证明物理 footprint，且新请求必须把同一资源列为 hard authority。这样只会避免“外车因本车旧 claim
   * 排队，本车又反过来等待外车”的环形等待，不会让列车跨过尚未持有的前方资源队列。
   */
  private Set<OccupancyResource> handoffContinuationQueueEntitlements(
      OccupancyRequest nextAuthority, DetachedAuthorityState detached) {
    if (nextAuthority == null || detached == null || detached.claims().isEmpty()) {
      return Set.of();
    }
    Set<OccupancyResource> requestedResources = Set.copyOf(nextAuthority.resourceList());
    Set<OccupancyResource> entitlements = new LinkedHashSet<>();
    for (OccupancyClaim claim : detached.claims()) {
      if (!isRetainableFootprintClaim(claim)
          || claim.resource() == null
          || !requestedResources.contains(claim.resource())
          || !nextAuthority.intentFor(claim.resource()).hardAuthority()
          || !isQueueableConflict(claim.resource())) {
        continue;
      }
      entitlements.add(claim.resource());
    }
    return Set.copyOf(entitlements);
  }

  private static boolean isRetainableFootprintClaim(OccupancyClaim claim) {
    if (claim == null) {
      return false;
    }
    return claim.role() == ClaimRole.MOVEMENT_REQUIRED
        || claim.role() == ClaimRole.PHYSICAL_FOOTPRINT
        || claim.role() == ClaimRole.PROTECTIVE_RETAIN
        || claim.role() == ClaimRole.HOLD_ONLY;
  }

  private OccupancyClaim retainedFootprintClaim(String trainName, OccupancyClaim oldClaim) {
    return new OccupancyClaim(
        oldClaim.resource(),
        trainName,
        oldClaim.routeId(),
        oldClaim.acquiredAt(),
        oldClaim.headway(),
        oldClaim.corridorDirection(),
        ClaimRole.PHYSICAL_FOOTPRINT);
  }

  /** 原子迁移 claim、queue、道岔签名与冲突释放锁的列车 owner。 */
  @Override
  public synchronized boolean migrateAuthorityOwner(String currentTrainName, String nextTrainName) {
    if (currentTrainName == null
        || currentTrainName.isBlank()
        || nextTrainName == null
        || nextTrainName.isBlank()) {
      return false;
    }
    if (currentTrainName.equals(nextTrainName)) {
      return true;
    }
    purgeExpiredQueueEntries(Instant.now());
    if (hasAuthorityState(nextTrainName)) {
      return false;
    }
    boolean changed = false;
    for (List<OccupancyClaim> existing : claims.values()) {
      if (existing == null || existing.isEmpty()) {
        continue;
      }
      for (int index = 0; index < existing.size(); index++) {
        OccupancyClaim claim = existing.get(index);
        if (claim == null
            || !TrainNameNormalizer.sameLogicalTrain(claim.trainName(), currentTrainName)) {
          continue;
        }
        OccupancyClaim migrated =
            new OccupancyClaim(
                claim.resource(),
                nextTrainName,
                claim.routeId(),
                claim.acquiredAt(),
                claim.headway(),
                claim.corridorDirection(),
                claim.role());
        existing.set(index, migrated);
        traceResourceLifecycle(
            null, claim.resource(), claim, migrated, "owner-migrate", "authority-owner-migration");
        changed = true;
      }
    }
    for (Map.Entry<OccupancyResource, ConflictQueue> entry : queues.entrySet()) {
      ConflictQueue queue = entry.getValue();
      if (queue != null && queue.rename(currentTrainName, nextTrainName)) {
        queue
            .entryFor(nextTrainName)
            .ifPresent(
                migrated ->
                    traceQueueLifecycle(
                        entry.getKey(),
                        migrated,
                        "WAITING_TRAIN",
                        "owner-migrate",
                        "authority-owner-migration-from:" + currentTrainName,
                        null));
        changed = true;
      }
    }
    changed |= migrateSwitcherSignatures(switcherClaimSignatures, currentTrainName, nextTrainName);
    changed |= migrateSwitcherSignatures(switcherQueueSignatures, currentTrainName, nextTrainName);
    changed |= migrateDeadlockReleaseLocks(currentTrainName, nextTrainName);
    changed |= migrateStaleRetainCandidate(currentTrainName, nextTrainName);
    if (changed) {
      version.incrementAndGet();
    }
    return true;
  }

  /** 校验硬资源的 owner、角色与单线方向仍与待发布授权一致。 */
  @Override
  public synchronized boolean holdsHardAuthority(OccupancyRequest authority) {
    if (authority == null) {
      return false;
    }
    boolean foundHardAuthority = false;
    for (OccupancyResource resource : authority.resourceList()) {
      if (resource == null || !authority.intentFor(resource).hardAuthority()) {
        continue;
      }
      foundHardAuthority = true;
      OccupancyClaim claim = findClaim(claims.get(resource), authority.trainName());
      if (claim == null || claim.role() != ClaimRole.MOVEMENT_REQUIRED) {
        return false;
      }
      Optional<CorridorDirection> requested = resolveCorridorDirection(authority, resource);
      if (requested.isPresent() && !requested.equals(claim.corridorDirection())) {
        return false;
      }
    }
    return foundHardAuthority;
  }

  private OccupancyDecision handoffRejected(
      OccupancyRequest request, String reason, List<OccupancyClaim> blockers) {
    return new OccupancyDecision(
        false,
        request.now(),
        SignalAspect.STOP,
        blockers == null ? List.of() : blockers,
        false,
        reason);
  }

  private DetachedAuthorityState detachAuthorityState(String trainName) {
    List<OccupancyClaim> detachedClaims = new ArrayList<>();
    Iterator<Map.Entry<OccupancyResource, List<OccupancyClaim>>> claimIterator =
        claims.entrySet().iterator();
    while (claimIterator.hasNext()) {
      Map.Entry<OccupancyResource, List<OccupancyClaim>> entry = claimIterator.next();
      List<OccupancyClaim> existing = entry.getValue();
      if (existing == null || existing.isEmpty()) {
        claimIterator.remove();
        continue;
      }
      Iterator<OccupancyClaim> existingIterator = existing.iterator();
      while (existingIterator.hasNext()) {
        OccupancyClaim claim = existingIterator.next();
        if (claim != null && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
          detachedClaims.add(claim);
          existingIterator.remove();
        }
      }
      if (existing.isEmpty()) {
        claimIterator.remove();
      }
    }

    Map<OccupancyResource, OccupancyQueueEntry> detachedQueues = new LinkedHashMap<>();
    Iterator<Map.Entry<OccupancyResource, ConflictQueue>> queueIterator =
        queues.entrySet().iterator();
    while (queueIterator.hasNext()) {
      Map.Entry<OccupancyResource, ConflictQueue> entry = queueIterator.next();
      ConflictQueue queue = entry.getValue();
      if (queue == null) {
        queueIterator.remove();
        continue;
      }
      queue.detach(trainName).ifPresent(value -> detachedQueues.put(entry.getKey(), value));
      if (queue.isEmpty()) {
        queueIterator.remove();
      }
    }

    Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> detachedClaimSignatures =
        detachSwitcherSignatures(switcherClaimSignatures, trainName);
    Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> detachedQueueSignatures =
        detachSwitcherSignatures(switcherQueueSignatures, trainName);
    return new DetachedAuthorityState(
        List.copyOf(detachedClaims),
        Map.copyOf(detachedQueues),
        Map.copyOf(detachedClaimSignatures),
        Map.copyOf(detachedQueueSignatures));
  }

  private void restoreAuthorityState(DetachedAuthorityState state) {
    if (state == null) {
      return;
    }
    for (OccupancyClaim claim : state.claims()) {
      claims.computeIfAbsent(claim.resource(), unused -> new ArrayList<>()).add(claim);
    }
    for (Map.Entry<OccupancyResource, OccupancyQueueEntry> entry : state.queues().entrySet()) {
      queues
          .computeIfAbsent(entry.getKey(), unused -> new ConflictQueue())
          .restore(entry.getValue());
    }
    switcherClaimSignatures.putAll(state.claimSignatures());
    switcherQueueSignatures.putAll(state.queueSignatures());
  }

  private Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature>
      detachSwitcherSignatures(
          Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> signatures,
          String trainName) {
    Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> detached =
        new LinkedHashMap<>();
    Iterator<Map.Entry<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature>> iterator =
        signatures.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> entry =
          iterator.next();
      if (TrainNameNormalizer.sameLogicalTrain(entry.getKey().trainKey(), trainName)) {
        detached.put(entry.getKey(), entry.getValue());
        iterator.remove();
      }
    }
    return detached;
  }

  private void removeClaimsByTrainWithoutEvents(String trainName) {
    Iterator<Map.Entry<OccupancyResource, List<OccupancyClaim>>> iterator =
        claims.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<OccupancyResource, List<OccupancyClaim>> entry = iterator.next();
      List<OccupancyClaim> existing = entry.getValue();
      if (existing == null) {
        iterator.remove();
        continue;
      }
      existing.removeIf(
          claim ->
              claim != null && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName));
      if (existing.isEmpty()) {
        iterator.remove();
      }
    }
    detachSwitcherSignatures(switcherClaimSignatures, trainName);
  }

  private boolean hasAuthorityState(String trainName) {
    for (List<OccupancyClaim> existing : claims.values()) {
      if (findClaim(existing, trainName) != null) {
        return true;
      }
    }
    for (ConflictQueue queue : queues.values()) {
      if (queue != null && queue.contains(trainName)) {
        return true;
      }
    }
    if (switcherClaimSignatures.keySet().stream()
        .anyMatch(key -> TrainNameNormalizer.sameLogicalTrain(key.trainKey(), trainName))) {
      return true;
    }
    if (switcherQueueSignatures.keySet().stream()
        .anyMatch(key -> TrainNameNormalizer.sameLogicalTrain(key.trainKey(), trainName))) {
      return true;
    }
    if (deadlockReleaseLocks.values().stream()
        .filter(Objects::nonNull)
        .anyMatch(lock -> lock.matches(trainName))) {
      return true;
    }
    return selfOwnedStaleRetainCandidates.containsKey(TrainNameNormalizer.normalizeKey(trainName));
  }

  private boolean migrateSwitcherSignatures(
      Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> signatures,
      String currentTrainName,
      String nextTrainName) {
    Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> migrated =
        detachSwitcherSignatures(signatures, currentTrainName);
    if (migrated.isEmpty()) {
      return false;
    }
    for (var entry : migrated.entrySet()) {
      signatures.put(
          new SwitcherClaimKey(entry.getKey().resource(), nextTrainName), entry.getValue());
    }
    return true;
  }

  private boolean migrateDeadlockReleaseLocks(String currentTrainName, String nextTrainName) {
    boolean changed = false;
    for (Map.Entry<String, DeadlockReleaseLock> entry : deadlockReleaseLocks.entrySet()) {
      DeadlockReleaseLock lock = entry.getValue();
      if (lock == null || !lock.matches(currentTrainName)) {
        continue;
      }
      DeadlockReleaseLock migrated = new DeadlockReleaseLock(nextTrainName, lock.expiresAt());
      entry.setValue(migrated);
      traceQueueLifecycle(
          OccupancyResource.forConflict(entry.getKey()),
          migrated.trainName(),
          null,
          null,
          null,
          null,
          null,
          null,
          "DEADLOCK_RELEASE_LOCK",
          "owner-migrate",
          "authority-owner-migration-from:" + currentTrainName,
          migrated.expiresAt());
      changed = true;
    }
    return changed;
  }

  private boolean migrateStaleRetainCandidate(String currentTrainName, String nextTrainName) {
    String currentKey = TrainNameNormalizer.normalizeKey(currentTrainName);
    SelfOwnedStaleRetainCandidate candidate = selfOwnedStaleRetainCandidates.remove(currentKey);
    if (candidate == null) {
      return false;
    }
    selfOwnedStaleRetainCandidates.put(
        TrainNameNormalizer.normalizeKey(nextTrainName),
        new SelfOwnedStaleRetainCandidate(
            nextTrainName,
            candidate.resource(),
            candidate.claimRole(),
            candidate.requestIntent(),
            candidate.heldDirection(),
            candidate.requestedDirection(),
            candidate.hardAuthorityScope(),
            candidate.reason(),
            candidate.sampledAt()));
    return true;
  }

  /**
   * 解析同一列车对同一资源的 claim 刷新。
   *
   * <p>前进授权是当前 traversal 的方向所有者。任何 refresh 都只能在方向缺失时补全证据，不能反向改写已有已知方向；保护或等待请求不能把已经取得的前进授权或尚未取得
   * rear-clear 证据的物理 footprint 降级为短生命周期 claim。换向必须通过原子 handoff，新硬授权可以接管与其重叠的物理 footprint。
   */
  private ClaimRefresh resolveClaimRefresh(
      OccupancyClaim current,
      ClaimRole requestedRole,
      Optional<CorridorDirection> requestedDirection) {
    Optional<CorridorDirection> safeRequestedDirection =
        requestedDirection == null ? Optional.empty() : requestedDirection;
    Optional<CorridorDirection> finalDirection =
        current.corridorDirection().or(() -> safeRequestedDirection);
    boolean hardAuthorityRefresh = requestedRole == ClaimRole.MOVEMENT_REQUIRED;
    boolean preserveCurrentHardRole =
        !hardAuthorityRefresh
            && (current.role() == ClaimRole.MOVEMENT_REQUIRED
                || current.role() == ClaimRole.PHYSICAL_FOOTPRINT);
    ClaimRole finalRole = preserveCurrentHardRole ? current.role() : requestedRole;
    String reason =
        finalRole != requestedRole
            ? current.role() == ClaimRole.PHYSICAL_FOOTPRINT
                ? "physical-footprint-preserved"
                : "movement-authority-preserved"
            : !finalDirection.equals(safeRequestedDirection)
                ? "movement-authority-direction-preserved"
                : "same-owner-resource-claim-refresh";
    return new ClaimRefresh(finalDirection, finalRole, reason);
  }

  /**
   * 记录同一列车在同一资源上的 claim 覆盖细节。
   *
   * <p>该 trace 只描述 acquire 内部“旧 claim 被新请求刷新”的事实，不参与角色、方向或 headway 选择。
   */
  private void traceOccupancyClaimMerge(
      OccupancyRequest request,
      OccupancyClaim current,
      ClaimRole requestedRole,
      ClaimRole finalRole,
      Optional<CorridorDirection> requestedDirection,
      Optional<CorridorDirection> finalDirection,
      String reason) {
    if (request == null || current == null || current.resource() == null) {
      return;
    }
    Optional<CorridorDirection> resolvedFinalDirection =
        finalDirection == null ? Optional.empty() : finalDirection;
    if (current.role() == finalRole && current.corridorDirection().equals(resolvedFinalDirection)) {
      return;
    }
    SignalComputationTrace.emitRaw(
        "SMART_OCCUPANCY_CLAIM_MERGE train="
            + request.trainName()
            + " resource="
            + current.resource()
            + " requestInputType="
            + SignalDecisionInputClassifier.classify(request)
            + " priority="
            + request.priority()
            + " operationType=- oldRole="
            + current.role()
            + " newRole="
            + requestedRole
            + " finalRole="
            + finalRole
            + " oldDirection="
            + current.corridorDirection().orElse(CorridorDirection.UNKNOWN)
            + " newDirection="
            + (requestedDirection == null
                ? CorridorDirection.UNKNOWN
                : requestedDirection.orElse(CorridorDirection.UNKNOWN))
            + " finalDirection="
            + (finalDirection == null
                ? CorridorDirection.UNKNOWN
                : finalDirection.orElse(CorridorDirection.UNKNOWN))
            + " action=updated"
            + " reason="
            + reason);
  }

  /**
   * 记录全部资源 claim 的统一生命周期，并保留 switcher/物理联锁专用诊断。
   *
   * <p>{@code SMART_RESOURCE_LIFECYCLE} 是 NODE、EDGE、single、switcher 与物理联锁共享的审计
   * seam；专用事件继续承载道岔路径与联锁排障的兼容字段。
   */
  private void traceResourceLifecycle(
      OccupancyRequest request,
      OccupancyResource resource,
      OccupancyClaim oldClaim,
      OccupancyClaim finalClaim,
      String event,
      String reason) {
    if (!hasSemanticClaimTransition(oldClaim, finalClaim)) {
      return;
    }
    OccupancyClaim visibleClaim = finalClaim == null ? oldClaim : finalClaim;
    if (resource == null || visibleClaim == null) {
      return;
    }
    ClaimRole finalRole = finalClaim == null ? null : finalClaim.role();
    ClaimRole oldRole = oldClaim == null ? null : oldClaim.role();
    if (!"refresh-noop".equals(event)) {
      SignalComputationTrace.emitRaw(
          "SMART_RESOURCE_LIFECYCLE sequence="
              + lifecycleSequence.incrementAndGet()
              + " observedVersion="
              + version.get()
              + " train="
              + safeLifecycleValue(request == null ? visibleClaim.trainName() : request.trainName())
              + " resource="
              + resource
              + " resourceKind="
              + resource.kind()
              + " event="
              + safeLifecycleValue(event)
              + " owner="
              + safeLifecycleValue(visibleClaim.trainName())
              + " oldOwner="
              + safeLifecycleValue(oldClaim == null ? null : oldClaim.trainName())
              + " newOwner="
              + safeLifecycleValue(finalClaim == null ? null : finalClaim.trainName())
              + " ownerCount="
              + ownerCount(resource)
              + " oldRole="
              + roleText(oldRole)
              + " newRole="
              + roleText(finalRole)
              + " direction="
              + visibleClaim.corridorDirection().orElse(CorridorDirection.UNKNOWN)
              + " requestInputType="
              + (request == null ? "-" : SignalDecisionInputClassifier.classify(request))
              + " priority="
              + (request == null ? "-" : request.priority())
              + " source="
              + safeLifecycleValue(reason));
    }

    boolean interlocking = OccupancyResourceResolver.isInterlockingConflict(resource);
    if (!isSwitcherConflictResource(resource) && !interlocking) {
      return;
    }
    SignalComputationTrace.emitRaw(
        (interlocking
                ? "SMART_INTERLOCKING_CLAIM_LIFECYCLE train="
                : "SMART_SWITCHER_CLAIM_LIFECYCLE train=")
            + safeLifecycleValue(request == null ? visibleClaim.trainName() : request.trainName())
            + " resource="
            + resource
            + " event="
            + safeLifecycleValue(event)
            + " owner="
            + safeLifecycleValue(visibleClaim.trainName())
            + " ownerCount="
            + ownerCount(resource)
            + " otherOwners="
            + otherOwners(resource, visibleClaim.trainName())
            + " oldRole="
            + roleText(oldRole)
            + " newRole="
            + roleText(finalRole)
            + " finalRole="
            + roleText(finalRole)
            + " direction="
            + visibleClaim.corridorDirection().orElse(CorridorDirection.UNKNOWN)
            + " requestInputType="
            + (request == null ? "-" : SignalDecisionInputClassifier.classify(request))
            + " priority="
            + (request == null ? "-" : request.priority())
            + " operationType=- routeId="
            + routeIdText(request, visibleClaim)
            + " routeIndex="
            + routeIndexText(request)
            + " currentNode="
            + currentNodeText(request)
            + " effectiveToNode="
            + effectiveToNodeText(request)
            + " physicalFootprint="
            + physicalFootprintText(visibleClaim.role())
            + " reservedAuthority="
            + reservedAuthorityText(visibleClaim.role())
            + " claimSource="
            + claimSourceText(request, resource, visibleClaim.role())
            + " reason="
            + safeLifecycleValue(reason));
  }

  /**
   * 判断 claim 的变化是否会影响占用仲裁或运行时物理语义。
   *
   * <p>同一逻辑列车对同一资源的 lease 心跳、读取 blocker 与等价 refresh 不属于生命周期。它们仍可更新内部时效字段，
   * 但不能产生调度诊断事件或把字符串格式化负担带到主线程。
   */
  private static boolean hasSemanticClaimTransition(
      OccupancyClaim oldClaim, OccupancyClaim finalClaim) {
    if (oldClaim == null || finalClaim == null) {
      return oldClaim != finalClaim;
    }
    return !TrainNameNormalizer.sameLogicalTrain(oldClaim.trainName(), finalClaim.trainName())
        || !oldClaim.routeId().equals(finalClaim.routeId())
        || !oldClaim.headway().equals(finalClaim.headway())
        || oldClaim.role() != finalClaim.role()
        || !oldClaim.corridorDirection().equals(finalClaim.corridorDirection());
  }

  /**
   * 记录冲突队列及其放行锁的统一生命周期。
   *
   * <p>等待条目和临时 release lock 共用同一事件名，以 {@code entryType} 区分；调用方只在调度语义发生变化时输出，lastSeen 心跳不会刷屏。
   */
  private void traceQueueLifecycle(
      OccupancyResource resource,
      OccupancyQueueEntry entry,
      String entryType,
      String event,
      String reason,
      Instant expiresAt) {
    if (resource == null || entry == null) {
      return;
    }
    traceQueueLifecycle(
        resource,
        entry.trainName(),
        entry.direction(),
        entry.priority(),
        entry.entryOrder(),
        entry.enqueueSequence(),
        entry.firstSeen(),
        entry.lastSeen(),
        entryType,
        event,
        reason,
        expiresAt);
  }

  private void traceQueueLifecycle(
      OccupancyResource resource,
      String trainName,
      CorridorDirection direction,
      Integer priority,
      Integer entryOrder,
      Long enqueueSequence,
      Instant firstSeen,
      Instant lastSeen,
      String entryType,
      String event,
      String reason,
      Instant expiresAt) {
    if (resource == null) {
      return;
    }
    SignalComputationTrace.emitRaw(
        "SMART_QUEUE_LIFECYCLE sequence="
            + lifecycleSequence.incrementAndGet()
            + " observedVersion="
            + version.get()
            + " resource="
            + resource
            + " entryType="
            + safeLifecycleValue(entryType)
            + " event="
            + safeLifecycleValue(event)
            + " train="
            + safeLifecycleValue(trainName)
            + " direction="
            + (direction == null ? "-" : direction)
            + " priority="
            + (priority == null ? "-" : priority)
            + " entryOrder="
            + (entryOrder == null ? "-" : entryOrder)
            + " enqueueSequence="
            + (enqueueSequence == null ? "-" : enqueueSequence)
            + " firstSeen="
            + (firstSeen == null ? "-" : firstSeen)
            + " lastSeen="
            + (lastSeen == null ? "-" : lastSeen)
            + " expiresAt="
            + (expiresAt == null ? "-" : expiresAt)
            + " reason="
            + safeLifecycleValue(reason));
  }

  private void traceSwitcherBlockerReads(
      String source, OccupancyRequest request, OccupancyDecision decision) {
    if (decision == null || decision.blockers().isEmpty()) {
      return;
    }
    for (OccupancyClaim blocker : decision.blockers()) {
      if (blocker == null || blocker.resource() == null) {
        continue;
      }
      traceResourceLifecycle(request, blocker.resource(), blocker, blocker, "blocker-read", source);
    }
  }

  /**
   * 记录因队头 winner 保留而产生的仲裁阻塞。
   *
   * <p>recoverable STOP rollback 会释放可执行 hard authority，但释放前会重新写入队列位次。该 trace
   * 用于证明后续事件重评估没有让低优先级竞争者绕过队头。
   */
  private void tracePendingWinnerArbitration(
      String source,
      OccupancyRequest request,
      OccupancyResource resource,
      Optional<OccupancyQueueEntry> blockingEntry,
      String decision,
      String reason) {
    if (request == null || resource == null || blockingEntry == null || blockingEntry.isEmpty()) {
      return;
    }
    OccupancyQueueEntry entry = blockingEntry.get();
    long pendingWinnerWaitSeconds =
        Math.max(0L, Duration.between(entry.firstSeen(), request.now()).toSeconds());
    SignalComputationTrace.emitRaw(
        "SMART_PENDING_WINNER_ARBITRATION requesterTrain="
            + safeLifecycleValue(request.trainName())
            + " requesterPriority="
            + request.priority()
            + " pendingWinnerTrain="
            + safeLifecycleValue(entry.trainName())
            + " pendingWinnerPriority="
            + entry.priority()
            + " pendingWinnerWaitSeconds="
            + pendingWinnerWaitSeconds
            + " priorityAdvantageCapSeconds="
            + QUEUE_MAX_PRIORITY_ADVANTAGE.toSeconds()
            + " resource="
            + resource
            + " decision="
            + safeLifecycleValue(decision)
            + " reason="
            + safeLifecycleValue(reason)
            + " source="
            + safeLifecycleValue(source));
  }

  private boolean isSwitcherConflictResource(OccupancyResource resource) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("switcher:")
        && !resource.key().substring("switcher:".length()).isBlank();
  }

  private boolean sameDirectionSectionAllowsSwitcherClaim(
      OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim, String source) {
    if (claim == null) {
      return false;
    }
    return sameDirectionSectionAllowsSwitcherTrain(
        request, resource, claim.trainName(), source, "claim", false);
  }

  private boolean sameDirectionSectionAllowsSwitcherTrain(
      OccupancyRequest request,
      OccupancyResource switcherResource,
      String otherTrain,
      String source,
      String relationSource,
      boolean knownDirectedPathLeader) {
    if (!isSwitcherConflictResource(switcherResource)
        || request == null
        || otherTrain == null
        || otherTrain.isBlank()) {
      return false;
    }
    Optional<DirectedTraversalContext.SwitcherPathSignature> requested =
        switcherSignatureFor(request, switcherResource);
    Optional<DirectedTraversalContext.SwitcherPathSignature> held =
        "queue".equals(relationSource)
            ? switcherQueueSignature(switcherResource, otherTrain)
            : switcherClaimSignature(switcherResource, otherTrain);
    if (switcherTerminalBoundaryRequiresMutex(switcherResource, requested, held)) {
      traceSwitcherTerminalBoundaryMutex(
          source, request, switcherResource, otherTrain, requested, held, relationSource);
      return false;
    }
    SwitcherMovementTopology.Classification movement =
        SwitcherMovementTopology.classifyRememberedSignatures(switcherResource, requested, held);
    if (movement.relation() != SwitcherMovementTopology.Relation.SAME_MOVEMENT) {
      return false;
    }
    Optional<SectionDirectionMatch> match = sameDirectionSectionMatch(request, otherTrain);
    if (match.isEmpty() && !knownDirectedPathLeader) {
      return false;
    }
    traceSwitcherSameDirectionSectionFollowThrough(
        source,
        request,
        switcherResource,
        otherTrain,
        relationSource,
        match.orElse(
            new SectionDirectionMatch(
                switcherResource, CorridorDirection.UNKNOWN, "same-route-progress")));
    return true;
  }

  private Optional<SectionDirectionMatch> sameDirectionSectionMatch(
      OccupancyRequest request, String otherTrain) {
    if (request == null || otherTrain == null || otherTrain.isBlank()) {
      return Optional.empty();
    }
    for (OccupancyResource section : request.resourceList()) {
      if (!isSingleCorridorConflict(section)) {
        continue;
      }
      Optional<CorridorDirection> requestedDirection = resolveCorridorDirection(request, section);
      if (requestedDirection.isEmpty()) {
        continue;
      }
      CorridorDirection requested = requestedDirection.get();
      OccupancyClaim claim = findClaim(claims.get(section), otherTrain);
      if (claim != null
          && claim.corridorDirection().isPresent()
          && claim.corridorDirection().get() == requested) {
        return Optional.of(new SectionDirectionMatch(section, requested, "claim"));
      }
      ConflictQueue queue = queues.get(section);
      if (queue == null) {
        continue;
      }
      Optional<CorridorDirection> queued = queue.directionOf(otherTrain);
      if (queued.isPresent() && queued.get() == requested) {
        return Optional.of(new SectionDirectionMatch(section, requested, "queue"));
      }
    }
    return Optional.empty();
  }

  private void traceSwitcherSameDirectionSectionFollowThrough(
      String source,
      OccupancyRequest request,
      OccupancyResource switcherResource,
      String otherTrain,
      String relationSource,
      SectionDirectionMatch match) {
    if (request == null || switcherResource == null || match == null) {
      return;
    }
    SignalComputationTrace.emitRaw(
        "SMART_SWITCHER_SAME_DIRECTION_SECTION_FOLLOW_THROUGH train="
            + safeLifecycleValue(request.trainName())
            + " otherTrain="
            + safeLifecycleValue(otherTrain)
            + " resource="
            + switcherResource
            + " section="
            + match.section()
            + " direction="
            + match.direction()
            + " source="
            + safeLifecycleValue(source)
            + " relationSource="
            + safeLifecycleValue(relationSource)
            + " evidence="
            + safeLifecycleValue(match.evidence())
            + " decision=same-section-same-direction");
  }

  private boolean switcherQueueAllowsEntry(
      OccupancyRequest request, OccupancyResource resource, ConflictQueue queue, Instant now) {
    return switcherConflictingQueueHead(request, resource, queue, null, now)
        .map(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), request.trainName()))
        .orElse(true);
  }

  private boolean switcherQueueWouldAllowEntry(
      OccupancyRequest request,
      OccupancyResource resource,
      ConflictQueue queue,
      int priority,
      int entryOrder,
      Instant now) {
    Instant safeNow = now == null ? Instant.now() : now;
    OccupancyQueueEntry candidate =
        queue.candidateEntry(
            request.trainName(),
            queueDirectionFor(request, resource),
            safeNow,
            priority,
            entryOrder);
    return switcherConflictingQueueHead(request, resource, queue, candidate, safeNow)
        .map(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), request.trainName()))
        .orElse(true);
  }

  private Optional<OccupancyQueueEntry> blockingQueueEntryFor(
      OccupancyRequest request,
      OccupancyResource resource,
      ConflictQueue queue,
      CorridorDirection direction,
      OccupancyQueueEntry candidate,
      Instant now) {
    if (isSwitcherConflictResource(resource)) {
      return switcherConflictingQueueHead(request, resource, queue, candidate, now)
          .filter(
              entry ->
                  !TrainNameNormalizer.sameLogicalTrain(entry.trainName(), request.trainName()));
    }
    return queue.blockingEntry(direction, now);
  }

  private Optional<OccupancyQueueEntry> switcherConflictingQueueHead(
      OccupancyRequest request,
      OccupancyResource resource,
      ConflictQueue queue,
      OccupancyQueueEntry candidate,
      Instant now) {
    if (!isSwitcherConflictResource(resource) || request == null || queue == null) {
      return Optional.empty();
    }
    List<OccupancyQueueEntry> entries = new ArrayList<>(queue.snapshotEntries(now));
    if (candidate != null && !queue.contains(candidate.trainName())) {
      entries.add(candidate);
    }
    OccupancyQueueEntry best = null;
    for (OccupancyQueueEntry entry : entries) {
      if (entry == null) {
        continue;
      }
      if (!switcherQueueEntryConflictsWithRequest(request, resource, entry)) {
        continue;
      }
      if (best == null || queue.compareEntries(entry, best, now) < 0) {
        best = entry;
      }
    }
    return Optional.ofNullable(best);
  }

  private boolean switcherQueueEntryConflictsWithRequest(
      OccupancyRequest request, OccupancyResource resource, OccupancyQueueEntry entry) {
    if (TrainNameNormalizer.sameLogicalTrain(entry.trainName(), request.trainName())) {
      return true;
    }
    if (sameDirectionSectionAllowsSwitcherTrain(
        request, resource, entry.trainName(), "queue", "queue", false)) {
      return false;
    }
    return true;
  }

  private Optional<DirectedTraversalContext.SwitcherPathSignature> switcherSignatureFor(
      OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return Optional.empty();
    }
    return SwitcherMovementTopology.verifiedSignature(resource, request.movementPlanSnapshot());
  }

  private Optional<DirectedTraversalContext.SwitcherPathSignature> switcherClaimSignature(
      OccupancyResource resource, String trainName) {
    if (!isSwitcherConflictResource(resource)) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        switcherClaimSignatures.get(new SwitcherClaimKey(resource, trainName)));
  }

  private Optional<DirectedTraversalContext.SwitcherPathSignature> switcherQueueSignature(
      OccupancyResource resource, String trainName) {
    if (!isSwitcherConflictResource(resource)) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        switcherQueueSignatures.get(new SwitcherClaimKey(resource, trainName)));
  }

  private boolean switcherTerminalBoundaryRequiresMutex(
      OccupancyResource resource,
      Optional<DirectedTraversalContext.SwitcherPathSignature> first,
      Optional<DirectedTraversalContext.SwitcherPathSignature> second) {
    if (!isSwitcherConflictResource(resource)) {
      return false;
    }
    return first
            .map(signature -> switcherSignatureTouchesTerminalBoundary(resource, signature))
            .orElse(false)
        || second
            .map(signature -> switcherSignatureTouchesTerminalBoundary(resource, signature))
            .orElse(false);
  }

  private boolean switcherSignatureTouchesTerminalBoundary(
      OccupancyResource resource, DirectedTraversalContext.SwitcherPathSignature signature) {
    if (!isSwitcherConflictResource(resource) || signature == null) {
      return false;
    }
    String switcherNode = switcherNodeValue(resource);
    if (switcherNode.isBlank()) {
      return false;
    }
    List<NodeId> path = signature.pathNodes();
    for (int i = 0; i < path.size(); i++) {
      NodeId node = path.get(i);
      if (node == null || !switcherNode.equals(node.value())) {
        continue;
      }
      if (i > 0 && isStationOrDepotBoundaryNode(path.get(i - 1))) {
        return true;
      }
      if (i + 1 < path.size() && isStationOrDepotBoundaryNode(path.get(i + 1))) {
        return true;
      }
    }
    return false;
  }

  private String switcherNodeValue(OccupancyResource resource) {
    if (!isSwitcherConflictResource(resource)) {
      return "";
    }
    return resource.key().substring("switcher:".length()).trim();
  }

  private boolean isStationOrDepotBoundaryNode(NodeId node) {
    return node != null && isStationOrDepotBoundaryNodeValue(node.value());
  }

  private boolean isStationOrDepotBoundaryNodeValue(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String[] parts = value.split(":");
    return parts.length == 4 && ("S".equals(parts[1]) || "D".equals(parts[1]));
  }

  private void traceSwitcherTerminalBoundaryMutex(
      String source,
      OccupancyRequest request,
      OccupancyResource resource,
      String otherTrain,
      Optional<DirectedTraversalContext.SwitcherPathSignature> requested,
      Optional<DirectedTraversalContext.SwitcherPathSignature> existing,
      String relationSource) {
    SignalComputationTrace.emitRaw(
        "SMART_SWITCHER_TERMINAL_BOUNDARY_MUTEX train="
            + safeLifecycleValue(request == null ? "-" : request.trainName())
            + " otherTrain="
            + safeLifecycleValue(otherTrain)
            + " resource="
            + resource
            + " source="
            + safeLifecycleValue(source)
            + " relationSource="
            + safeLifecycleValue(relationSource)
            + " requestedSignature="
            + requested.map(Object::toString).orElse("-")
            + " existingSignature="
            + existing.map(Object::toString).orElse("-")
            + " decision=terminal-boundary-switcher-mutex");
  }

  private boolean rememberSwitcherClaimSignature(
      OccupancyRequest request, OccupancyResource resource) {
    if (!isSwitcherConflictResource(resource) || request == null) {
      return false;
    }
    Optional<DirectedTraversalContext.SwitcherPathSignature> signature =
        switcherSignatureFor(request, resource);
    SwitcherClaimKey key = new SwitcherClaimKey(resource, request.trainName());
    if (signature.isEmpty()) {
      return switcherClaimSignatures.remove(key) != null;
    }
    DirectedTraversalContext.SwitcherPathSignature previous =
        switcherClaimSignatures.put(key, signature.get());
    return !Objects.equals(previous, signature.get());
  }

  private boolean rememberSwitcherQueueSignature(
      OccupancyRequest request, OccupancyResource resource) {
    if (!isSwitcherConflictResource(resource) || request == null) {
      return false;
    }
    Optional<DirectedTraversalContext.SwitcherPathSignature> signature =
        switcherSignatureFor(request, resource);
    SwitcherClaimKey key = new SwitcherClaimKey(resource, request.trainName());
    if (signature.isEmpty()) {
      return switcherQueueSignatures.remove(key) != null;
    }
    DirectedTraversalContext.SwitcherPathSignature previous =
        switcherQueueSignatures.put(key, signature.get());
    return !Objects.equals(previous, signature.get());
  }

  private void forgetSwitcherClaimSignature(OccupancyResource resource, String trainName) {
    if (!isSwitcherConflictResource(resource)) {
      return;
    }
    switcherClaimSignatures.remove(new SwitcherClaimKey(resource, trainName));
  }

  private void forgetSwitcherQueueSignature(OccupancyResource resource, String trainName) {
    if (!isSwitcherConflictResource(resource)) {
      return;
    }
    switcherQueueSignatures.remove(new SwitcherClaimKey(resource, trainName));
  }

  private void pruneDetachedSwitcherQueueSignatures() {
    if (switcherQueueSignatures.isEmpty()) {
      return;
    }
    switcherQueueSignatures
        .entrySet()
        .removeIf(
            entry -> {
              ConflictQueue queue = queues.get(entry.getKey().resource());
              return queue == null || !queue.contains(entry.getKey().trainKey());
            });
  }

  private int ownerCount(OccupancyResource resource) {
    List<OccupancyClaim> list = claims.get(resource);
    return list == null ? 0 : list.size();
  }

  private List<String> otherOwners(OccupancyResource resource, String owner) {
    List<OccupancyClaim> list = claims.get(resource);
    if (list == null || list.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (OccupancyClaim claim : list) {
      if (claim == null || TrainNameNormalizer.sameLogicalTrain(claim.trainName(), owner)) {
        continue;
      }
      result.add(claim.trainName());
    }
    return List.copyOf(result);
  }

  private static String roleText(ClaimRole role) {
    return role == null ? "-" : role.name();
  }

  private static String routeIdText(OccupancyRequest request, OccupancyClaim claim) {
    if (request != null && request.routeId().isPresent()) {
      return request.routeId().get().value();
    }
    return claim == null ? "-" : claim.routeId().map(Object::toString).orElse("-");
  }

  private static String routeIndexText(OccupancyRequest request) {
    return request == null
        ? "-"
        : request
            .directedContext()
            .map(DirectedTraversalContext::currentIndex)
            .map(String::valueOf)
            .orElse("-");
  }

  private static String currentNodeText(OccupancyRequest request) {
    return request == null
        ? "-"
        : request
            .directedContext()
            .flatMap(DirectedTraversalContext::currentNode)
            .map(NodeId::value)
            .orElse("-");
  }

  private static String effectiveToNodeText(OccupancyRequest request) {
    return request == null
        ? "-"
        : request
            .directedContext()
            .flatMap(DirectedTraversalContext::effectiveToNode)
            .map(NodeId::value)
            .orElse("-");
  }

  private static String physicalFootprintText(ClaimRole role) {
    if (role == ClaimRole.PHYSICAL_FOOTPRINT) {
      return "true";
    }
    if (role == ClaimRole.UNLOCK_RESERVATION
        || role == ClaimRole.QUEUE_POSITION
        || role == ClaimRole.LOOKAHEAD_PREVIEW) {
      return "false";
    }
    return "unknown";
  }

  private static String reservedAuthorityText(ClaimRole role) {
    if (role == ClaimRole.UNLOCK_RESERVATION) {
      return "true";
    }
    if (role == ClaimRole.QUEUE_POSITION || role == ClaimRole.LOOKAHEAD_PREVIEW) {
      return "false";
    }
    return "unknown";
  }

  private static String claimSourceText(
      OccupancyRequest request, OccupancyResource resource, ClaimRole role) {
    if (request != null && resource != null) {
      return request.intentFor(resource).name().toLowerCase(Locale.ROOT);
    }
    return role == null ? "unknown" : role.name().toLowerCase(Locale.ROOT);
  }

  private static String safeLifecycleValue(String value) {
    return value == null || value.isBlank() ? "-" : value.trim();
  }

  /**
   * 查询某资源当前占用（只返回首个占用者）。
   *
   * <p>用于诊断，不保证公平队列顺序。
   */
  @Override
  public synchronized Optional<OccupancyClaim> getClaim(OccupancyResource resource) {
    if (resource == null) {
      return Optional.empty();
    }
    List<OccupancyClaim> list = claims.get(resource);
    if (list == null || list.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(list.get(0));
  }

  /**
   * 获取全部占用快照。
   *
   * <p>用于诊断，不建议高频调用。
   */
  @Override
  public synchronized List<OccupancyClaim> snapshotClaims() {
    List<OccupancyClaim> snapshot = new ArrayList<>();
    for (List<OccupancyClaim> list : claims.values()) {
      if (list == null || list.isEmpty()) {
        continue;
      }
      snapshot.addAll(list);
    }
    return List.copyOf(snapshot);
  }

  @Override
  public synchronized boolean isProvenSameDirectionFollower(
      OccupancyRequest request,
      OccupancyResource blockerResource,
      String blockerTrainName,
      boolean knownDirectedPathLeader) {
    if (request == null
        || blockerResource == null
        || blockerTrainName == null
        || blockerTrainName.isBlank()
        || TrainNameNormalizer.sameLogicalTrain(request.trainName(), blockerTrainName)) {
      return false;
    }
    if (isSwitcherConflictResource(blockerResource)) {
      String relationSource =
          findClaim(claims.get(blockerResource), blockerTrainName) == null ? "queue" : "claim";
      return sameDirectionSectionAllowsSwitcherTrain(
          request,
          blockerResource,
          blockerTrainName,
          "forward-risk",
          relationSource,
          knownDirectedPathLeader);
    }
    if (isSingleCorridorConflict(blockerResource)) {
      return knownDirectedPathLeader
          || sameDirectionSectionMatch(request, blockerTrainName).isPresent();
    }
    return false;
  }

  /**
   * 获取排队快照。
   *
   * <p>用于诊断单线走廊方向与排队情况。
   */
  @Override
  public synchronized List<OccupancyQueueSnapshot> snapshotQueues() {
    List<OccupancyQueueSnapshot> snapshots = new ArrayList<>();
    for (Map.Entry<OccupancyResource, ConflictQueue> entry : queues.entrySet()) {
      ConflictQueue queue = entry.getValue();
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      OccupancyResource resource = entry.getKey();
      Optional<CorridorDirection> activeDirection = activeDirectionFor(resource);
      int activeClaims = claims.getOrDefault(resource, List.of()).size();
      snapshots.add(
          new OccupancyQueueSnapshot(
              resource, activeDirection, activeClaims, queue.snapshotEntries(Instant.now())));
    }
    return List.copyOf(snapshots);
  }

  /** 返回最近一次判定发现的自持 stale/protective retain 释放候选。 */
  public synchronized Optional<SelfOwnedStaleRetainCandidate> selfOwnedStaleRetainReleaseCandidate(
      String trainName) {
    String key = TrainNameNormalizer.normalizeKey(trainName);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    SelfOwnedStaleRetainCandidate candidate = selfOwnedStaleRetainCandidates.get(key);
    if (candidate == null) {
      return Optional.empty();
    }
    if (!stillHasReleasableSelfOwnedRetain(candidate)) {
      selfOwnedStaleRetainCandidates.remove(key);
      return Optional.empty();
    }
    return Optional.of(candidate);
  }

  /**
   * 只读识别本次请求可释放的自持反向保护 retain。
   *
   * <p>该方法不调用 {@link #canEnter(OccupancyRequest)}，不写入候选缓存，不触碰 conflict queue，也不推进 occupancy
   * version。运行时可用它在发车预检查阶段发现 P0 self-retain case，随后仍必须经过 mode/effect gate 才能执行真实释放。
   */
  public synchronized Optional<SelfOwnedStaleRetainCandidate>
      previewSelfOwnedStaleRetainReleaseCandidate(OccupancyRequest request) {
    if (request == null) {
      return Optional.empty();
    }
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null || resource.kind() != ResourceKind.CONFLICT) {
        continue;
      }
      if (OccupancyResourceResolver.isInterlockingConflict(resource)) {
        continue;
      }
      OccupancyClaim selfClaim = findClaim(claims.get(resource), request.trainName());
      if (selfClaim == null || selfClaim.role() != ClaimRole.PROTECTIVE_RETAIN) {
        continue;
      }
      Optional<CorridorDirection> requested = resolveCorridorDirection(request, resource);
      Optional<CorridorDirection> held = selfClaim.corridorDirection();
      if (requested.isEmpty()
          || held.isEmpty()
          || requested.get() == CorridorDirection.UNKNOWN
          || held.get() == CorridorDirection.UNKNOWN
          || requested.get() == held.get()) {
        continue;
      }
      if (isStillInsideSwitcherZoneRetain(request, resource)
          || hasExternalClaim(resource, request.trainName())
          || hasExternalHardBlockerAhead(request)) {
        continue;
      }
      ConflictQueue queue = queues.get(resource);
      if (queue != null && queue.hasAnyOtherTrain(request.trainName())) {
        continue;
      }
      return Optional.of(
          new SelfOwnedStaleRetainCandidate(
              request.trainName(),
              resource,
              selfClaim.role(),
              request.intentFor(resource),
              held.get(),
              requested.get(),
              hardAuthorityScope(request),
              "opposite-direction",
              request.now()));
    }
    return Optional.empty();
  }

  /**
   * 释放 Smart recovery 已确认的自持 stale/protective retain。
   *
   * <p>该入口只释放同一逻辑列车持有的 CONFLICT retain；不会清 destination、不会变更 movement token，也不会释放 NODE/EDGE
   * 车体占用。若同一资源上存在外部列车或外部队列等待，返回 skipped，避免释放后把对向列车放入当前区间。
   */
  public synchronized SelfOwnedStaleRetainReleaseResult releaseSelfOwnedStaleRetain(
      String trainName) {
    Optional<SelfOwnedStaleRetainCandidate> candidateOpt =
        selfOwnedStaleRetainReleaseCandidate(trainName);
    if (candidateOpt.isEmpty()) {
      return new SelfOwnedStaleRetainReleaseResult(
          false, false, "self-owned-stale-retain-not-found", List.of());
    }
    return releaseSelfOwnedStaleRetain(trainName, candidateOpt.get());
  }

  /**
   * 释放调用方刚刚用只读预览确认的 P0 self-retain 候选。
   *
   * <p>显式候选路径不依赖候选缓存，因此可用于发车预检查；释放边界仍与缓存路径一致，只允许同车、同资源、反向、{@link ClaimRole#PROTECTIVE_RETAIN} 的
   * CONFLICT claim。
   */
  public synchronized SelfOwnedStaleRetainReleaseResult releaseSelfOwnedStaleRetain(
      String trainName, SelfOwnedStaleRetainCandidate candidate) {
    if (!strictSelfOwnedProtectiveRetainCandidate(trainName, candidate)) {
      return new SelfOwnedStaleRetainReleaseResult(
          candidate != null, false, "outside-p0-self-retain-boundary", List.of());
    }
    if (!stillHasReleasableSelfOwnedRetain(candidate)) {
      selfOwnedStaleRetainCandidates.remove(
          TrainNameNormalizer.normalizeKey(candidate.trainName()));
      return new SelfOwnedStaleRetainReleaseResult(
          true, false, "claim-no-longer-matches", List.of());
    }
    if (hasExternalClaim(candidate.resource(), candidate.trainName())) {
      return new SelfOwnedStaleRetainReleaseResult(
          true, false, "external-owner-present", List.of());
    }
    if (hasExternalClaimInScope(candidate)) {
      return new SelfOwnedStaleRetainReleaseResult(
          true, false, "external-hard-blocker-present", List.of());
    }
    ConflictQueue queue = queues.get(candidate.resource());
    if (queue != null && queue.hasAnyOtherTrain(candidate.trainName())) {
      return new SelfOwnedStaleRetainReleaseResult(
          true, false, "external-queue-present", List.of());
    }
    List<OccupancyClaim> list = claims.get(candidate.resource());
    if (list == null || list.isEmpty()) {
      selfOwnedStaleRetainCandidates.remove(
          TrainNameNormalizer.normalizeKey(candidate.trainName()));
      return new SelfOwnedStaleRetainReleaseResult(true, false, "claim-already-gone", List.of());
    }
    boolean released = false;
    Iterator<OccupancyClaim> iterator = list.iterator();
    while (iterator.hasNext()) {
      OccupancyClaim claim = iterator.next();
      if (!matchesSelfOwnedRetainCandidate(candidate, claim)) {
        continue;
      }
      traceResourceLifecycle(
          null, candidate.resource(), claim, null, "release", "release-self-owned-stale-retain");
      forgetSwitcherClaimSignature(candidate.resource(), claim.trainName());
      iterator.remove();
      released = true;
    }
    if (!released) {
      selfOwnedStaleRetainCandidates.remove(
          TrainNameNormalizer.normalizeKey(candidate.trainName()));
      return new SelfOwnedStaleRetainReleaseResult(
          true, false, "claim-no-longer-matches", List.of());
    }
    if (list.isEmpty()) {
      claims.remove(candidate.resource());
    }
    removeFromQueuesForResources(candidate.trainName(), List.of(candidate.resource()));
    releaseDeadlockLocksForTrain(candidate.trainName());
    selfOwnedStaleRetainCandidates.remove(TrainNameNormalizer.normalizeKey(candidate.trainName()));
    version.incrementAndGet();
    publishReleasedEvent(candidate.trainName(), List.of(candidate.resource()), Instant.now());
    return new SelfOwnedStaleRetainReleaseResult(
        true, true, "released-self-owned-stale-retain", List.of(candidate.resource()));
  }

  /**
   * 仅刷新冲突队列中的排队位次，不真正获取占用。
   *
   * <p>用于停站/门控场景：列车还停在当前位置，但需要持续保留自己在前方冲突区的排队顺序，避免后车先抢到队头。 lastSeen 心跳始终更新；只有方向、优先级、稳定 entryOrder
   * 或道岔路径签名变化时才推进 occupancy version。
   */
  @Override
  public synchronized void touchQueues(OccupancyRequest request) {
    if (request == null) {
      return;
    }
    Instant now = request.now();
    purgeExpiredQueueEntries(now);
    for (OccupancyResource resource : request.resourceList()) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      if (findClaim(claims.get(resource), request.trainName()) != null) {
        continue;
      }
      CorridorDirection direction = queueDirectionFor(request, resource);
      ConflictQueue queue = queues.computeIfAbsent(resource, unused -> new ConflictQueue());
      QueueTouchResult queueTouch =
          touchQueueWithDirectionTrace(request, resource, queue, direction, now, "touchQueues");
      commitQueueTouchChange(request, resource, now, queueTouch);
    }
  }

  /**
   * 从指定冲突队列中移除列车排队条目。
   *
   * <p>只清理 queue，不释放 claim。调用方必须先判断该条目确实是可丢弃的前瞻位次，避免破坏真实会车或道岔让行顺序。
   */
  @Override
  public synchronized int removeQueueEntries(String trainName, List<OccupancyResource> resources) {
    if (trainName == null || trainName.isBlank() || resources == null || resources.isEmpty()) {
      return 0;
    }
    int removed = 0;
    Instant now = Instant.now();
    List<OccupancyResource> changedResources = new ArrayList<>();
    Set<String> eligibleTrainNames = new LinkedHashSet<>();
    for (OccupancyResource resource : resources) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      ConflictQueue queue = queues.get(resource);
      if (queue == null || !queue.contains(trainName)) {
        continue;
      }
      Optional<OccupancyQueueEntry> previousQueueHead = queue.headAny(now);
      OccupancyQueueEntry removedEntry = queue.entryFor(trainName).orElse(null);
      queue.remove(trainName);
      traceQueueLifecycle(
          resource, removedEntry, "WAITING_TRAIN", "remove", "explicit-remove", null);
      forgetSwitcherQueueSignature(resource, trainName);
      removed++;
      changedResources.add(resource);
      eligibleTrainNames.addAll(newlyEligibleQueueHeads(previousQueueHead, queue.headAny(now)));
      version.incrementAndGet();
      if (queue.isEmpty()) {
        queues.remove(resource);
      }
    }
    publishQueueChangedEvent(trainName, changedResources, List.copyOf(eligibleTrainNames), now);
    return removed;
  }

  /**
   * 按列车名释放全部占用资源。
   *
   * <p>释放后会发布 {@link OccupancyReleasedEvent}，通知订阅者资源已可用。
   *
   * @return 实际释放的占用数量
   */
  @Override
  public synchronized int releaseByTrain(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return 0;
    }
    int removed = 0;
    List<OccupancyResource> releasedResources = new ArrayList<>();
    Iterator<Map.Entry<OccupancyResource, List<OccupancyClaim>>> iterator =
        claims.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<OccupancyResource, List<OccupancyClaim>> entry = iterator.next();
      List<OccupancyClaim> list = entry.getValue();
      if (list == null || list.isEmpty()) {
        iterator.remove();
        continue;
      }
      Iterator<OccupancyClaim> claimIterator = list.iterator();
      while (claimIterator.hasNext()) {
        OccupancyClaim claim = claimIterator.next();
        if (claim != null && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
          traceResourceLifecycle(null, entry.getKey(), claim, null, "release", "release-by-train");
          forgetSwitcherClaimSignature(entry.getKey(), claim.trainName());
          claimIterator.remove();
          releasedResources.add(entry.getKey());
          removed++;
        }
      }
      if (list.isEmpty()) {
        iterator.remove();
      }
    }
    int removedQueueEntries = removeFromQueuesForTrain(trainName);
    int removedReleaseLocks = releaseDeadlockLocksForTrain(trainName);
    selfOwnedStaleRetainCandidates.remove(TrainNameNormalizer.normalizeKey(trainName));
    // 发布占用释放事件
    if (!releasedResources.isEmpty() || removedQueueEntries > 0 || removedReleaseLocks > 0) {
      version.incrementAndGet();
    }
    if (!releasedResources.isEmpty()) {
      publishReleasedEvent(trainName, releasedResources, Instant.now());
    }
    return removed;
  }

  /**
   * 释放指定资源上的单个列车占用。
   *
   * <p>释放后会发布 {@link OccupancyReleasedEvent}，通知订阅者资源已可用。
   *
   * @return 是否存在并成功移除
   */
  @Override
  public synchronized boolean releaseResource(
      OccupancyResource resource, Optional<String> trainName) {
    if (resource == null) {
      return false;
    }
    List<OccupancyClaim> list = claims.get(resource);
    if (list == null || list.isEmpty()) {
      return false;
    }
    if (trainName != null && trainName.isPresent()) {
      String expected = trainName.get();
      list.stream()
          .filter(
              claim ->
                  claim != null
                      && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), expected))
          .forEach(
              claim ->
                  traceResourceLifecycle(
                      null, resource, claim, null, "release", "release-resource-train"));
      boolean removed =
          list.removeIf(
              claim ->
                  claim != null
                      && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), expected));
      // 发布占用释放事件
      if (removed) {
        if (list.isEmpty()) {
          claims.remove(resource);
        }
        forgetSwitcherClaimSignature(resource, expected);
        removeFromQueuesForResources(expected, List.of(resource));
        version.incrementAndGet();
        selfOwnedStaleRetainCandidates.remove(TrainNameNormalizer.normalizeKey(expected));
        publishReleasedEvent(expected, List.of(resource), Instant.now());
      }
      return removed;
    }
    // 全量释放：先收集被驱逐的列车名，再逐一清理队列条目
    List<String> evictedTrains = new ArrayList<>();
    for (OccupancyClaim claim : list) {
      if (claim != null && claim.trainName() != null && !claim.trainName().isBlank()) {
        evictedTrains.add(claim.trainName());
        traceResourceLifecycle(null, resource, claim, null, "release", "release-resource-all");
      }
    }
    claims.remove(resource);
    for (String evicted : evictedTrains) {
      forgetSwitcherClaimSignature(resource, evicted);
      removeFromQueuesForResources(evicted, List.of(resource));
      selfOwnedStaleRetainCandidates.remove(TrainNameNormalizer.normalizeKey(evicted));
    }
    publishReleasedEvent("*", List.of(resource), Instant.now());
    version.incrementAndGet();
    return true;
  }

  /**
   * 释放资源时同步保留 winner 的冲突队列位次。
   *
   * <p>该入口专供 recoverable STOP rollback 使用。它不会把保留位次写成物理 claim，而是在发布 {@link OccupancyReleasedEvent}
   * 前刷新队列，使事件驱动重评估立即看到当前 winner 仍是队头。
   */
  @Override
  public synchronized boolean releaseResourceRetainingQueuePosition(
      OccupancyResource resource, Optional<String> trainName, OccupancyRequest queueRequest) {
    if (resource == null || trainName == null || trainName.isEmpty()) {
      releaseResource(resource, trainName);
      return false;
    }
    List<OccupancyClaim> list = claims.get(resource);
    if (list == null || list.isEmpty()) {
      return false;
    }
    String expected = trainName.get();
    list.stream()
        .filter(
            claim ->
                claim != null && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), expected))
        .forEach(
            claim ->
                traceResourceLifecycle(
                    null, resource, claim, null, "release", "release-resource-retaining-queue"));
    boolean removed =
        list.removeIf(
            claim ->
                claim != null && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), expected));
    if (!removed) {
      return false;
    }
    if (list.isEmpty()) {
      claims.remove(resource);
    }
    forgetSwitcherClaimSignature(resource, expected);
    boolean retainedQueue = retainQueuePosition(resource, expected, queueRequest);
    if (!retainedQueue) {
      removeFromQueuesForResources(expected, List.of(resource));
    }
    version.incrementAndGet();
    selfOwnedStaleRetainCandidates.remove(TrainNameNormalizer.normalizeKey(expected));
    publishReleasedEvent(expected, List.of(resource), Instant.now());
    return retainedQueue;
  }

  /**
   * 只释放指定角色的 claim。
   *
   * <p>用于 Smart Dispatcher minimal forward reservation 回滚。该方法不会释放同车的物理 NODE/EDGE 占用，也不会清理保护性
   * retain；只有角色完全匹配的短生命周期 claim 会被移除。
   */
  @Override
  public synchronized int releaseResourcesByTrainAndRole(
      String trainName, List<OccupancyResource> resources, ClaimRole role) {
    if (trainName == null
        || trainName.isBlank()
        || resources == null
        || resources.isEmpty()
        || role == null) {
      return 0;
    }
    int removed = 0;
    List<OccupancyResource> releasedResources = new ArrayList<>();
    for (OccupancyResource resource : resources) {
      if (resource == null) {
        continue;
      }
      List<OccupancyClaim> list = claims.get(resource);
      if (list == null || list.isEmpty()) {
        continue;
      }
      int before = list.size();
      list.stream()
          .filter(
              claim ->
                  claim != null
                      && claim.role() == role
                      && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName))
          .forEach(
              claim ->
                  traceResourceLifecycle(
                      null,
                      resource,
                      claim,
                      null,
                      role == ClaimRole.UNLOCK_RESERVATION ? "rollback-release" : "release",
                      "release-by-train-and-role"));
      list.removeIf(
          claim ->
              claim != null
                  && claim.role() == role
                  && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName));
      int delta = before - list.size();
      if (delta <= 0) {
        continue;
      }
      forgetSwitcherClaimSignature(resource, trainName);
      removed += delta;
      releasedResources.add(resource);
      removeFromQueuesForResources(trainName, List.of(resource));
      if (list.isEmpty()) {
        claims.remove(resource);
      }
    }
    if (removed > 0) {
      selfOwnedStaleRetainCandidates.remove(TrainNameNormalizer.normalizeKey(trainName));
      version.incrementAndGet();
      publishReleasedEvent(trainName, releasedResources, Instant.now());
    }
    return removed;
  }

  /**
   * 清理同车 single conflict 的反向残留占用。
   *
   * <p>该方法只移除“同一逻辑列车、同一个 single conflict、旧方向与当前请求方向明确相反”的 claim/queue。方向未知时不清理，且 {@link
   * ClaimRole#PHYSICAL_FOOTPRINT} 必须等待专用 rear-clear 证据，避免健康恢复绕过真实列尾释放旧进路。
   */
  @Override
  public synchronized int clearSelfOwnedSingleDirectionMismatches(OccupancyRequest request) {
    if (request == null || request.trainName() == null || request.trainName().isBlank()) {
      return 0;
    }
    int removed = 0;
    Instant now = Instant.now();
    Set<OccupancyResource> releasedResources = new LinkedHashSet<>();
    Set<OccupancyResource> queueResources = new LinkedHashSet<>();
    Set<OccupancyResource> directQueueChanges = new LinkedHashSet<>();
    Set<String> eligibleTrainNames = new LinkedHashSet<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (!isSingleCorridorConflict(resource)) {
        continue;
      }
      Optional<CorridorDirection> requestedDirection = resolveCorridorDirection(request, resource);
      if (!isKnownDirection(requestedDirection)) {
        continue;
      }
      List<OccupancyClaim> list = claims.get(resource);
      if (list != null && !list.isEmpty()) {
        Iterator<OccupancyClaim> claimIterator = list.iterator();
        while (claimIterator.hasNext()) {
          OccupancyClaim claim = claimIterator.next();
          if (!isSelfOwnedOppositeSingleDirection(
              request.trainName(), claim, requestedDirection.get())) {
            continue;
          }
          traceResourceLifecycle(
              request,
              resource,
              claim,
              null,
              "release",
              "clear-self-owned-single-direction-mismatch");
          forgetSwitcherClaimSignature(resource, claim.trainName());
          claimIterator.remove();
          releasedResources.add(resource);
          queueResources.add(resource);
          removed++;
        }
        if (list.isEmpty()) {
          claims.remove(resource);
        }
      }
      ConflictQueue queue = queues.get(resource);
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      Optional<CorridorDirection> queuedDirection = queue.directionOf(request.trainName());
      if (!isKnownDirection(queuedDirection) || queuedDirection.get() == requestedDirection.get()) {
        continue;
      }
      Optional<OccupancyQueueEntry> previousQueueHead = queue.headAny(now);
      OccupancyQueueEntry removedEntry = queue.entryFor(request.trainName()).orElse(null);
      queue.remove(request.trainName());
      traceQueueLifecycle(
          resource,
          removedEntry,
          "WAITING_TRAIN",
          "remove",
          "clear-self-owned-single-direction-mismatch",
          null);
      queueResources.add(resource);
      directQueueChanges.add(resource);
      eligibleTrainNames.addAll(newlyEligibleQueueHeads(previousQueueHead, queue.headAny(now)));
      removed++;
      if (queue.isEmpty()) {
        queues.remove(resource);
      }
    }
    if (removed <= 0) {
      return 0;
    }
    if (!queueResources.isEmpty()) {
      removeFromQueuesForResources(request.trainName(), List.copyOf(queueResources));
    }
    publishQueueChangedEvent(
        request.trainName(), List.copyOf(directQueueChanges), List.copyOf(eligibleTrainNames), now);
    releaseDeadlockLocksForTrain(request.trainName());
    selfOwnedStaleRetainCandidates.remove(TrainNameNormalizer.normalizeKey(request.trainName()));
    version.incrementAndGet();
    if (!releasedResources.isEmpty()) {
      publishReleasedEvent(request.trainName(), List.copyOf(releasedResources), Instant.now());
    }
    return removed;
  }

  /**
   * 优先级让行判定：当冲突队列中存在更高优先级列车时返回 true。
   *
   * <p>仅针对单线走廊、道岔与物理联锁冲突资源；同向单线不触发让行，物理联锁始终按无方向全局队头仲裁。
   */
  @Override
  public synchronized boolean shouldYield(OccupancyRequest request) {
    if (request == null || request.trainName() == null || request.trainName().isBlank()) {
      return false;
    }
    Instant now = request.now();
    purgeExpiredQueueEntries(now);
    int priority = request.priority();
    for (OccupancyResource resource : request.resourceList()) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      ConflictQueue queue = queues.get(resource);
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      if (isSingleCorridorConflict(resource)) {
        Optional<CorridorDirection> direction = resolveCorridorDirection(request, resource);
        if (direction.isPresent()) {
          if (queue.hasHigherPriorityOutside(request.trainName(), direction.get(), priority, now)) {
            return true;
          }
        } else if (queue.hasHigherPriorityAny(request.trainName(), priority, now)) {
          return true;
        }
        continue;
      }
      if (queue.hasHigherPriorityAny(request.trainName(), priority, now)) {
        return true;
      }
    }
    return false;
  }

  // 事件反射式释放不需要“基于时间”的清理。

  private Optional<OccupancyDecision> failClosedUnknownSingleConflictEntry(
      OccupancyRequest request, OccupancyResource resource, Instant now) {
    if (request == null || resource == null || !isSingleCorridorConflict(resource)) {
      return Optional.empty();
    }
    if (hasClaimByTrain(resource, request.trainName())) {
      return Optional.empty();
    }
    if (resolveCorridorDirection(request, resource).isPresent()) {
      return Optional.empty();
    }
    if (!hasExternalSinglePresence(request, resource)) {
      return Optional.empty();
    }
    return Optional.of(
        new OccupancyDecision(
            false,
            now != null ? now : request.now(),
            SignalAspect.STOP,
            List.of(),
            false,
            "single-conflict-direction-unknown"));
  }

  private boolean stillHasReleasableSelfOwnedRetain(SelfOwnedStaleRetainCandidate candidate) {
    if (candidate == null
        || candidate.resource().kind() != ResourceKind.CONFLICT
        || OccupancyResourceResolver.isInterlockingConflict(candidate.resource())) {
      return false;
    }
    List<OccupancyClaim> list = claims.get(candidate.resource());
    if (list == null || list.isEmpty()) {
      return false;
    }
    for (OccupancyClaim claim : list) {
      if (matchesSelfOwnedRetainCandidate(candidate, claim)) {
        return true;
      }
    }
    return false;
  }

  private boolean strictSelfOwnedProtectiveRetainCandidate(
      String trainName, SelfOwnedStaleRetainCandidate candidate) {
    if (candidate == null
        || candidate.resource().kind() != ResourceKind.CONFLICT
        || OccupancyResourceResolver.isInterlockingConflict(candidate.resource())
        || candidate.claimRole() != ClaimRole.PROTECTIVE_RETAIN
        || !TrainNameNormalizer.sameLogicalTrain(trainName, candidate.trainName())) {
      return false;
    }
    return candidate.heldDirection() != CorridorDirection.UNKNOWN
        && candidate.requestedDirection() != CorridorDirection.UNKNOWN
        && candidate.heldDirection() != candidate.requestedDirection();
  }

  private boolean matchesSelfOwnedRetainCandidate(
      SelfOwnedStaleRetainCandidate candidate, OccupancyClaim claim) {
    if (candidate == null || claim == null || claim.resource() == null) {
      return false;
    }
    if (!claim.resource().equals(candidate.resource())) {
      return false;
    }
    if (!TrainNameNormalizer.sameLogicalTrain(claim.trainName(), candidate.trainName())) {
      return false;
    }
    if (claim.resource().kind() != ResourceKind.CONFLICT) {
      return false;
    }
    if (claim.role() != candidate.claimRole()) {
      return false;
    }
    return isProtectiveSelfRetain(candidate.requestIntent(), claim.role());
  }

  private boolean isProtectiveSelfRetain(ResourceIntent requestIntent, ClaimRole claimRole) {
    if (claimRole == ClaimRole.PROTECTIVE_RETAIN || claimRole == ClaimRole.HOLD_ONLY) {
      return true;
    }
    return requestIntent == ResourceIntent.PROTECTIVE_RETAIN
        || requestIntent == ResourceIntent.HOLD_ONLY;
  }

  private boolean hasExternalClaim(OccupancyResource resource, String trainName) {
    List<OccupancyClaim> list = claims.get(resource);
    if (list == null || list.isEmpty()) {
      return false;
    }
    for (OccupancyClaim claim : list) {
      if (claim != null && !TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasClaimByTrain(OccupancyResource resource, String trainName) {
    return findClaim(claims.get(resource), trainName) != null;
  }

  private boolean isAdvisoryVisibleClaim(
      OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim) {
    if (request == null || claim == null) {
      return false;
    }
    if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), request.trainName())) {
      return false;
    }
    ClaimRole role = claim.role();
    if (role == ClaimRole.LOOKAHEAD_PREVIEW
        || role == ClaimRole.QUEUE_POSITION
        || role == ClaimRole.UNLOCK_RESERVATION) {
      return false;
    }
    if (role == ClaimRole.PHYSICAL_FOOTPRINT) {
      return true;
    }
    if ((role == ClaimRole.PROTECTIVE_RETAIN || role == ClaimRole.HOLD_ONLY)
        && !isPhysicalOccupancyResource(resource)) {
      return false;
    }
    if (isSwitcherConflictResource(resource)
        && sameDirectionSectionAllowsSwitcherClaim(request, resource, claim, "advisory-claim")) {
      return false;
    }
    return !isSameDirectionSinglePresence(request, resource, claim.corridorDirection());
  }

  private boolean isPhysicalOccupancyResource(OccupancyResource resource) {
    return resource != null
        && (resource.kind() == ResourceKind.NODE
            || resource.kind() == ResourceKind.EDGE
            || OccupancyResourceResolver.isInterlockingConflict(resource));
  }

  private boolean isAdvisoryVisibleQueueEntry(
      OccupancyRequest request, OccupancyResource resource, OccupancyQueueEntry entry) {
    if (request == null || entry == null) {
      return false;
    }
    if (TrainNameNormalizer.sameLogicalTrain(entry.trainName(), request.trainName())) {
      return false;
    }
    CorridorDirection direction =
        entry.direction() == null ? CorridorDirection.UNKNOWN : entry.direction();
    if (isSwitcherConflictResource(resource)
        && sameDirectionSectionAllowsSwitcherTrain(
            request, resource, entry.trainName(), "advisory-queue", "queue", false)) {
      return false;
    }
    return !isSameDirectionSinglePresence(request, resource, Optional.of(direction));
  }

  /**
   * 同向 single-zone 占用不是 advisory 停车点。
   *
   * <p>同向跟驰的安全边界来自前车实际 NODE/EDGE 占用和单线入口 gate；如果把 single conflict 本身当成前方 blocker， 距离会被解析为“单线入口”，通常是
   * 0 blocks，后车会被 movement authority 错误压成红灯，失去慢速靠近能力。
   */
  private boolean isSameDirectionSinglePresence(
      OccupancyRequest request,
      OccupancyResource resource,
      Optional<CorridorDirection> otherDirection) {
    if (request == null || resource == null || !isSingleCorridorConflict(resource)) {
      return false;
    }
    CorridorDirection requested = request.corridorDirections().get(resource.key());
    if (requested == null || requested == CorridorDirection.UNKNOWN) {
      return false;
    }
    if (otherDirection == null || otherDirection.isEmpty()) {
      return false;
    }
    CorridorDirection other = otherDirection.get();
    return other != CorridorDirection.UNKNOWN && requested == other;
  }

  private AdvisoryRiskSource advisoryRiskSourceFor(OccupancyResource resource) {
    if (resource == null) {
      return AdvisoryRiskSource.OCCUPIED_NODE;
    }
    if (resource.kind() == ResourceKind.EDGE) {
      return AdvisoryRiskSource.OCCUPIED_EDGE;
    }
    if (resource.kind() == ResourceKind.CONFLICT) {
      if (isSingleCorridorConflict(resource)) {
        return AdvisoryRiskSource.ACTIVE_SINGLE_CONFLICT;
      }
      if (resource.key().startsWith("switcher:")) {
        return AdvisoryRiskSource.ACTIVE_SWITCHER_CONFLICT;
      }
    }
    return AdvisoryRiskSource.OCCUPIED_NODE;
  }

  private void traceAdvisoryRisk(OccupancyRequest request, AdvisoryRisk risk) {
    if (request == null || risk == null) {
      return;
    }
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                SignalComputationTrace.Source.OCCUPANCY,
                SignalAspect.PROCEED_WITH_CAUTION)
            .primaryReason("ADVISORY_RISK_FOUND")
            .field("advisoryRiskSource", risk.source())
            .field("advisoryRiskOwner", risk.claim().trainName())
            .field("advisoryRiskResource", risk.resource())
            .field("reason", risk.reason())
            .request(request));
  }

  Optional<String> conflictReleaseHardBlockerReason(
      OccupancyRequest request, List<OccupancyClaim> blockers) {
    if (request == null || request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return Optional.empty();
    }
    OccupancyClaim hardBlocker = firstHardBlocker(blockers, request.trainName());
    if (hardBlocker == null || hardBlocker.resource() == null) {
      return Optional.empty();
    }
    return Optional.of("conflict-release-hard-blocker:" + hardBlocker.resource());
  }

  private OccupancyClaim firstHardBlocker(List<OccupancyClaim> blockers, String trainName) {
    if (blockers == null || blockers.isEmpty()) {
      return null;
    }
    for (OccupancyClaim blocker : blockers) {
      if (blocker == null || blocker.resource() == null) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), trainName)) {
        continue;
      }
      if (blocker.role() == ClaimRole.PHYSICAL_FOOTPRINT) {
        return blocker;
      }
      ResourceKind kind = blocker.resource().kind();
      if (BlockerClassifier.isStrictConflictResource(blocker.resource())) {
        return blocker;
      }
      if (kind == ResourceKind.NODE || kind == ResourceKind.EDGE) {
        return blocker;
      }
    }
    return null;
  }

  private Optional<String> singleRegionHardBarrierReason(
      OccupancyRequest request, List<OccupancyClaim> blockers) {
    if (request == null || blockers == null || blockers.isEmpty()) {
      return Optional.empty();
    }
    for (OccupancyClaim blocker : blockers) {
      if (blocker == null || blocker.resource() == null) {
        continue;
      }
      if (singleRegionOppositeOrUnknownExternalBarrier(request, blocker.resource(), blocker)) {
        return Optional.of(OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER);
      }
    }
    return Optional.empty();
  }

  private OccupancyDecision singleRegionHardBarrierDecision(Instant now, OccupancyClaim blocker) {
    Instant effectiveNow = now == null ? Instant.now() : now;
    return new OccupancyDecision(
        false,
        effectiveNow,
        SignalAspect.STOP,
        blocker == null ? List.of() : List.of(blocker),
        false,
        OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER);
  }

  private boolean singleRegionOppositeOrUnknownExternalBarrier(
      OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim) {
    if (request == null
        || resource == null
        || claim == null
        || claim.role() == ClaimRole.UNLOCK_RESERVATION
        || !isSingleCorridorConflict(resource)
        || TrainNameNormalizer.sameLogicalTrain(request.trainName(), claim.trainName())) {
      return false;
    }
    Optional<CorridorDirection> requested = resolveCorridorDirection(request, resource);
    Optional<CorridorDirection> held = claim.corridorDirection();
    return requested.isEmpty()
        || held.isEmpty()
        || requested.get() == CorridorDirection.UNKNOWN
        || held.get() == CorridorDirection.UNKNOWN
        || requested.get() != held.get();
  }

  private Optional<CorridorDirection> resolveCorridorDirection(
      OccupancyRequest request, OccupancyResource resource) {
    return resolveCorridorDirectionWithSource(request, resource).direction();
  }

  private DirectionResolution resolveCorridorDirectionWithSource(
      OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return DirectionResolution.unknown();
    }
    if (!isSingleCorridorConflict(resource)) {
      return DirectionResolution.unknown();
    }
    CorridorDirection direction = request.corridorDirections().get(resource.key());
    if (direction != null && direction != CorridorDirection.UNKNOWN) {
      return new DirectionResolution(
          Optional.of(direction), DirectionSource.REQUEST_CORRIDOR_DIRECTIONS);
    }
    DirectionResolution requestSectionToken =
        resolveEquivalentSectionTokenDirection(
            request.corridorDirections(),
            resource,
            DirectionSource.REQUEST_SECTION_TOKEN_DIRECTIONS);
    if (requestSectionToken.direction().isPresent()) {
      return requestSectionToken;
    }
    Optional<MovementPlanSnapshot> plan = request.movementPlanSnapshot();
    if (plan.isPresent()) {
      CorridorDirection snapshotDirection =
          plan.get().singleConflictDirections().get(resource.key());
      if (snapshotDirection != null && snapshotDirection != CorridorDirection.UNKNOWN) {
        return new DirectionResolution(
            Optional.of(snapshotDirection),
            DirectionSource.MOVEMENT_PLAN_SINGLE_CONFLICT_DIRECTIONS);
      }
      DirectionResolution planSectionToken =
          resolveEquivalentSectionTokenDirection(
              plan.get().singleConflictDirections(),
              resource,
              DirectionSource.MOVEMENT_PLAN_SECTION_TOKEN_DIRECTIONS);
      if (planSectionToken.direction().isPresent()) {
        return planSectionToken;
      }
    }
    if (!request.intentFor(resource).hardAuthority()) {
      OccupancyClaim heldClaim = findClaim(claims.get(resource), request.trainName());
      if (heldClaim != null && isKnownDirection(heldClaim.corridorDirection())) {
        return new DirectionResolution(
            heldClaim.corridorDirection(), DirectionSource.HELD_DIRECTION_FALLBACK);
      }
    }
    return DirectionResolution.unknown();
  }

  private DirectionResolution resolveEquivalentSectionTokenDirection(
      Map<String, CorridorDirection> directions,
      OccupancyResource resource,
      DirectionSource source) {
    if (directions == null || directions.isEmpty() || resource == null) {
      return DirectionResolution.unknown();
    }
    Optional<SingleConflictToken> target = singleConflictToken(resource.key());
    if (target.isEmpty() || !resource.key().startsWith(SINGLE_SECTION_CONFLICT_PREFIX)) {
      return DirectionResolution.unknown();
    }
    CorridorDirection matched = null;
    for (Map.Entry<String, CorridorDirection> entry : directions.entrySet()) {
      if (entry.getKey() == null
          || entry.getKey().equals(resource.key())
          || entry.getValue() == null
          || entry.getValue() == CorridorDirection.UNKNOWN) {
        continue;
      }
      Optional<SingleConflictToken> candidate = singleConflictToken(entry.getKey());
      if (!equivalentSectionToken(target.get(), entry.getKey(), candidate)) {
        continue;
      }
      if (matched != null && matched != entry.getValue()) {
        return DirectionResolution.unknown();
      }
      matched = entry.getValue();
    }
    return matched == null
        ? DirectionResolution.unknown()
        : new DirectionResolution(Optional.of(matched), source);
  }

  private static Optional<SingleConflictToken> singleConflictToken(String key) {
    if (key == null || key.isBlank()) {
      return Optional.empty();
    }
    boolean section = key.startsWith(SINGLE_SECTION_CONFLICT_PREFIX);
    String rest;
    if (section) {
      rest = key.substring(SINGLE_SECTION_CONFLICT_PREFIX.length());
    } else if (key.startsWith("single:")) {
      rest = key.substring("single:".length());
    } else {
      return Optional.empty();
    }
    int componentEnd = rest.indexOf(':');
    if (componentEnd <= 0 || componentEnd >= rest.length() - 1) {
      return Optional.empty();
    }
    String namespace = rest.substring(0, componentEnd);
    String axis = rest.substring(componentEnd + 1);
    if (!axis.contains("~")) {
      return Optional.empty();
    }
    return Optional.of(new SingleConflictToken(namespace, axis, section));
  }

  /**
   * 判断已提交 token 是否能为目标 section 提供同一轴线的方向证据。
   *
   * <p>旧 section 继续要求 namespace 与 axis 都一致；新的桥链 section 使用固定 {@code bridge} namespace，其方向可以从同 axis
   * 的 micro corridor 恢复，但不能从另一个 section、闭环或不同轴线借用。micro component 本身可能是含冒号的 NodeId，因此 bridge 匹配使用完整
   * axis 后缀，不能按第一个冒号拆 component。
   */
  private static boolean equivalentSectionToken(
      SingleConflictToken target, String candidateKey, Optional<SingleConflictToken> candidate) {
    if (candidate.isPresent() && target.equals(candidate.get())) {
      return true;
    }
    return target.section()
        && "bridge".equals(target.namespace())
        && candidateKey != null
        && candidateKey.startsWith("single:")
        && !candidateKey.startsWith(SINGLE_SECTION_CONFLICT_PREFIX)
        && !candidateKey.contains(":cycle:")
        && candidateKey.endsWith(":" + target.axis());
  }

  private static boolean isKnownDirection(Optional<CorridorDirection> direction) {
    return direction.isPresent() && direction.get() != CorridorDirection.UNKNOWN;
  }

  private static boolean isSelfOwnedOppositeSingleDirection(
      String trainName, OccupancyClaim claim, CorridorDirection requestedDirection) {
    if (claim == null
        || requestedDirection == null
        || requestedDirection == CorridorDirection.UNKNOWN) {
      return false;
    }
    Optional<CorridorDirection> heldDirection = claim.corridorDirection();
    return TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)
        && claim.role() != ClaimRole.PHYSICAL_FOOTPRINT
        && isKnownDirection(heldDirection)
        && heldDirection.get() != requestedDirection;
  }

  private boolean isSingleCorridorConflict(OccupancyResource resource) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("single:")
        && !resource.key().contains(":cycle:");
  }

  private boolean isQueueableConflict(OccupancyResource resource) {
    if (resource == null || resource.kind() != ResourceKind.CONFLICT) {
      return false;
    }
    return resource.key().startsWith("single:")
        || resource.key().startsWith("switcher:")
        || OccupancyResourceResolver.isInterlockingConflict(resource);
  }

  private boolean isQueueAllowed(
      OccupancyRequest request,
      OccupancyResource resource,
      CorridorDirection direction,
      ConflictQueue queue,
      Instant now) {
    if (queue == null || queue.isEmpty()) {
      return true;
    }
    if (!resource.key().startsWith("single:") || resource.key().contains(":cycle:")) {
      if (isSwitcherConflictResource(resource)) {
        return switcherQueueAllowsEntry(request, resource, queue, now);
      }
      return queue.isHeadAny(request.trainName(), now);
    }
    Optional<CorridorDirection> activeDirection = activeDirectionFor(resource);
    if (activeDirection.isEmpty()) {
      if (direction != CorridorDirection.UNKNOWN && !queue.hasEntriesOutside(direction)) {
        return true;
      }
      return queue.isHeadAny(request.trainName(), now);
    }
    CorridorDirection active = activeDirection.get();
    if (direction == CorridorDirection.UNKNOWN) {
      // 车库口/分叉区可能无法解析语义方向；只有无外部等待时才允许同车 claim 绕过队列自锁。
      return hasClaimByTrain(resource, request.trainName())
          && !queue.hasAnyOtherTrain(request.trainName());
    }
    if (direction != active) {
      return false;
    }
    if (!queue.hasEntriesOutside(active)) {
      return true;
    }
    return queue.isHeadForDirection(request.trainName(), direction, now);
  }

  /**
   * 判断自持 claim 是否可绕过冲突队列。
   *
   * <p>{@link ClaimRole#MOVEMENT_REQUIRED} 代表列车已经拥有可执行 hard authority，可以继续刷新同一资源；保护性 retain
   * 只是当前位置/车尾保护，不能在升级为前进授权时绕过外部 pending winner 队列。
   */
  private boolean selfClaimBypassesQueue(OccupancyClaim selfClaim) {
    return selfClaim != null && selfClaim.role() == ClaimRole.MOVEMENT_REQUIRED;
  }

  private boolean isQueueAllowedPreview(
      OccupancyRequest request,
      OccupancyResource resource,
      CorridorDirection direction,
      ConflictQueue queue,
      int priority,
      int entryOrder,
      Instant now) {
    if (queue == null || queue.isEmpty()) {
      return true;
    }
    String trainName = request.trainName();
    if (queue.contains(trainName)) {
      return isQueueAllowed(request, resource, direction, queue, now);
    }
    if (!resource.key().startsWith("single:") || resource.key().contains(":cycle:")) {
      if (isSwitcherConflictResource(resource)) {
        return switcherQueueWouldAllowEntry(request, resource, queue, priority, entryOrder, now);
      }
      return queue.wouldBeHeadAny(trainName, direction, priority, entryOrder, now);
    }
    Optional<CorridorDirection> activeDirection = activeDirectionFor(resource);
    if (activeDirection.isEmpty()) {
      if (direction != CorridorDirection.UNKNOWN && !queue.hasEntriesOutside(direction)) {
        return true;
      }
      return queue.wouldBeHeadAny(trainName, direction, priority, entryOrder, now);
    }
    CorridorDirection active = activeDirection.get();
    if (direction == CorridorDirection.UNKNOWN) {
      // 预览路径保持与可写路径一致：UNKNOWN 只绕过零外部存在的同车队列自锁。
      return hasClaimByTrain(resource, trainName) && !queue.hasAnyOtherTrain(trainName);
    }
    if (direction != active) {
      return false;
    }
    if (!queue.hasEntriesOutside(active)) {
      return true;
    }
    return queue.wouldBeHeadForDirection(trainName, direction, priority, entryOrder, now);
  }

  private Optional<CorridorDirection> activeDirectionFor(OccupancyResource resource) {
    if (!resource.key().startsWith("single:") || resource.key().contains(":cycle:")) {
      return Optional.empty();
    }
    List<OccupancyClaim> list = claims.get(resource);
    if (list == null || list.isEmpty()) {
      return Optional.empty();
    }
    CorridorDirection current = null;
    for (OccupancyClaim claim : list) {
      if (claim == null || claim.corridorDirection().isEmpty()) {
        continue;
      }
      CorridorDirection direction = claim.corridorDirection().get();
      if (current == null) {
        current = direction;
        continue;
      }
      if (current != direction) {
        return Optional.empty();
      }
    }
    return Optional.ofNullable(current);
  }

  private CorridorDirection queueDirectionFor(
      OccupancyRequest request, OccupancyResource resource) {
    return queueDirectionResolutionFor(request, resource)
        .direction()
        .orElse(CorridorDirection.UNKNOWN);
  }

  private DirectionResolution queueDirectionResolutionFor(
      OccupancyRequest request, OccupancyResource resource) {
    if (request == null) {
      return DirectionResolution.unknown();
    }
    if (resource == null) {
      return DirectionResolution.unknown();
    }
    if (!resource.key().startsWith("single:") || resource.key().contains(":cycle:")) {
      return DirectionResolution.unknown();
    }
    return resolveCorridorDirectionWithSource(request, resource);
  }

  /**
   * 自持 single claim 只在方向连续时视为 continuation。
   *
   * <p>旧逻辑把 {@link BlockerRelation#SELF} 一律跳过，导致列车持有同一 single conflict
   * 后即使本轮请求方向反转、路径不连续或前方已有真实硬阻塞，也能绕过 fail-closed。这里仅在方向、路径与 immediate hard window 都能证明仍在继续穿越/退出同一
   * single zone 时忽略自持 claim。
   */
  private Optional<OccupancyDecision> selfOwnedSingleDirectionMismatchDecision(
      OccupancyRequest request,
      OccupancyResource resource,
      OccupancyClaim selfClaim,
      Instant now,
      String source) {
    if (request == null
        || resource == null
        || selfClaim == null
        || !isSingleCorridorConflict(resource)) {
      return Optional.empty();
    }
    Optional<CorridorDirection> requested = resolveCorridorDirection(request, resource);
    Optional<CorridorDirection> held = selfClaim.corridorDirection();
    boolean directionsKnown =
        requested.isPresent()
            && held.isPresent()
            && requested.get() != CorridorDirection.UNKNOWN
            && held.get() != CorridorDirection.UNKNOWN;
    boolean directionMatches = directionsKnown && requested.get() == held.get();
    boolean pathExitsZone = selfOwnedPathExitsOrContinuesZone(request, resource);
    Optional<ExternalBlockerDetail> externalBlockerDetail =
        firstExternalHardBlockerDetail(
            request, resource, requested.orElse(CorridorDirection.UNKNOWN), true);
    boolean externalBlockerAhead = externalBlockerDetail.isPresent();
    boolean externalSinglePresence = hasExternalSinglePresence(request, resource);
    boolean oppositeSingleAhead =
        directionsKnown && hasOppositeLiveSinglePresence(request, resource, requested.get());
    traceSmartSelfOwnedContinuation(
        request,
        resource,
        "SMART_SELF_OWNED_CONTINUATION_CHECK",
        SignalAspect.PROCEED,
        "check",
        held.orElse(CorridorDirection.UNKNOWN),
        requested.orElse(CorridorDirection.UNKNOWN),
        directionMatches,
        pathExitsZone,
        externalBlockerAhead || oppositeSingleAhead);
    if (!directionsKnown && request.directedContext().isEmpty() && requested.isEmpty()) {
      SignalComputationTrace.emit(
          SignalComputationTrace.builder(
                  request.trainName(),
                  request.trainName(),
                  SignalComputationTrace.Source.OCCUPANCY,
                  SignalAspect.PROCEED)
              .primaryReason("SELF_OWNED_SINGLE_BLOCKER_IGNORED")
              .field("conflictKey", resource.key())
              .field("heldDirection", held.orElse(CorridorDirection.UNKNOWN))
              .field("requestedDirection", CorridorDirection.UNKNOWN)
              .field("source", source)
              .field("reason", "hold-refresh-omitted-direction")
              .field("selfOwnedDirectionMatches", false)
              .field("selfOwnedPathExitsZone", false)
              .field("selfOwnedExternalBlockerAhead", false)
              .request(request));
      return Optional.empty();
    }
    if (!directionsKnown && pathExitsZone && !externalBlockerAhead && !externalSinglePresence) {
      traceSmartSelfOwnedContinuation(
          request,
          resource,
          "SMART_SELF_OWNED_CONTINUATION_ALLOWED",
          SignalAspect.PROCEED,
          "unknown-direction-self-owned-drain",
          held.orElse(CorridorDirection.UNKNOWN),
          requested.orElse(CorridorDirection.UNKNOWN),
          false,
          true,
          false);
      SignalComputationTrace.emit(
          SignalComputationTrace.builder(
                  request.trainName(),
                  request.trainName(),
                  SignalComputationTrace.Source.OCCUPANCY,
                  SignalAspect.PROCEED)
              .primaryReason("SELF_OWNED_SINGLE_BLOCKER_IGNORED")
              .field("conflictKey", resource.key())
              .field("heldDirection", held.orElse(CorridorDirection.UNKNOWN))
              .field("requestedDirection", requested.orElse(CorridorDirection.UNKNOWN))
              .field("source", source)
              .field("reason", "unknown-direction-self-owned-drain")
              .field("selfOwnedDirectionMatches", false)
              .field("selfOwnedPathExitsZone", true)
              .field("selfOwnedExternalBlockerAhead", false)
              .request(request));
      traceSelfOwnedBlockerFiltered(
          request, resource, selfClaim, "SELF_OWNED_RETAIN_IGNORED_FOR_CONTINUATION");
      return Optional.empty();
    }
    if (!directionsKnown
        && !externalBlockerAhead
        && !externalSinglePresence
        && !oppositeSingleAhead) {
      traceSmartSelfOwnedContinuation(
          request,
          resource,
          "SMART_SELF_OWNED_CONTINUATION_ALLOWED",
          SignalAspect.PROCEED,
          "unknown-direction-self-owned-no-external-presence",
          held.orElse(CorridorDirection.UNKNOWN),
          requested.orElse(CorridorDirection.UNKNOWN),
          false,
          pathExitsZone,
          false);
      SignalComputationTrace.emit(
          SignalComputationTrace.builder(
                  request.trainName(),
                  request.trainName(),
                  SignalComputationTrace.Source.OCCUPANCY,
                  SignalAspect.PROCEED)
              .primaryReason("SELF_OWNED_SINGLE_BLOCKER_IGNORED")
              .field("conflictKey", resource.key())
              .field("heldDirection", held.orElse(CorridorDirection.UNKNOWN))
              .field("requestedDirection", requested.orElse(CorridorDirection.UNKNOWN))
              .field("source", source)
              .field("reason", "unknown-direction-self-owned-no-external-presence")
              .field("selfOwnedDirectionMatches", false)
              .field("selfOwnedPathExitsZone", pathExitsZone)
              .field("selfOwnedExternalBlockerAhead", false)
              .field("selfOwnedExternalSinglePresence", false)
              .field("selfOwnedOppositeSingleAhead", false)
              .request(request));
      traceSelfOwnedBlockerFiltered(
          request, resource, selfClaim, "SELF_OWNED_RETAIN_IGNORED_FOR_CONTINUATION");
      return Optional.empty();
    }
    if (directionMatches && pathExitsZone && !externalBlockerAhead && !oppositeSingleAhead) {
      traceSmartSelfOwnedContinuation(
          request,
          resource,
          "SMART_SELF_OWNED_CONTINUATION_ALLOWED",
          SignalAspect.PROCEED,
          externalSinglePresence
              ? "known-direction-self-owned-drain-with-same-direction-presence"
              : "known-direction-self-owned-drain",
          held.get(),
          requested.get(),
          true,
          true,
          false);
      SignalComputationTrace.emit(
          SignalComputationTrace.builder(
                  request.trainName(),
                  request.trainName(),
                  SignalComputationTrace.Source.OCCUPANCY,
                  SignalAspect.PROCEED)
              .primaryReason("SELF_OWNED_SINGLE_BLOCKER_IGNORED")
              .field("conflictKey", resource.key())
              .field("heldDirection", held.get())
              .field("requestedDirection", requested.get())
              .field("source", source)
              .field("selfOwnedDirectionMatches", true)
              .field("selfOwnedPathExitsZone", true)
              .field("selfOwnedExternalBlockerAhead", false)
              .field("selfOwnedExternalSinglePresence", externalSinglePresence)
              .field("selfOwnedOppositeSingleAhead", false)
              .request(request));
      traceSelfOwnedBlockerFiltered(
          request, resource, selfClaim, "SELF_OWNED_RETAIN_IGNORED_FOR_CONTINUATION");
      return Optional.empty();
    }
    if (!pathExitsZone
        && request.movementPlanSnapshot().isPresent()
        && !request.intentFor(resource).hardAuthority()) {
      // 折返/换段后 movement plan 已不再穿越该 zone：自持 claim 只承担车尾/区域保护职责，
      // 不能反过来否决本车其他方向的移动，否则列车会被自己的旧方向 claim 永久锁死，
      // 并把真正可被 health 恢复识别的 opposite-direction 拒绝掩盖掉。claim 原样保留，
      // 外部对向/未知方向列车仍由 single-region barrier 拦截；对该 zone 仍要求 hard
      // authority 的请求继续走 fail-closed 判定。
      traceSmartSelfOwnedContinuation(
          request,
          resource,
          "SMART_SELF_OWNED_CONTINUATION_ALLOWED",
          SignalAspect.PROCEED,
          "tail-protection-zone-not-on-plan",
          held.orElse(CorridorDirection.UNKNOWN),
          requested.orElse(CorridorDirection.UNKNOWN),
          directionMatches,
          false,
          externalBlockerAhead || oppositeSingleAhead);
      traceSelfOwnedBlockerFiltered(
          request, resource, selfClaim, "SELF_OWNED_RETAIN_IGNORED_FOR_CONTINUATION");
      return Optional.empty();
    }
    String reason =
        !directionsKnown
            ? "direction-unknown"
            : !directionMatches
                ? "opposite-direction"
                : !pathExitsZone
                    ? "path-does-not-exit-or-continue"
                    : externalBlockerAhead
                        ? "external-hard-blocker-ahead"
                        : "external-single-blocker-ahead";
    if (requested.isEmpty()
        || held.isEmpty()
        || requested.get() == CorridorDirection.UNKNOWN
        || held.get() == CorridorDirection.UNKNOWN
        || requested.get() != held.get()) {
      // 保留旧 reason，避免既有诊断和运维查询失效。
      reason =
          requested.isPresent()
                  && held.isPresent()
                  && requested.get() != CorridorDirection.UNKNOWN
                  && held.get() != CorridorDirection.UNKNOWN
                  && requested.get() != held.get()
              ? "opposite-direction"
              : reason;
    }
    if ("opposite-direction".equals(reason)
        && directionMatches
        && pathExitsZone
        && !externalBlockerAhead
        && !oppositeSingleAhead) {
      traceSmartSelfOwnedContinuation(
          request,
          resource,
          "SMART_SELF_OWNED_CONTINUATION_CONTRADICTION",
          SignalAspect.PROCEED,
          "invariant-would-have-rejected-valid-continuation",
          held.get(),
          requested.get(),
          true,
          true,
          false);
      traceSelfOwnedBlockerFiltered(
          request, resource, selfClaim, "SELF_OWNED_RETAIN_IGNORED_FOR_CONTINUATION");
      return Optional.empty();
    }
    CorridorDirection heldDirection = held.orElse(CorridorDirection.UNKNOWN);
    CorridorDirection requestedDirection = requested.orElse(CorridorDirection.UNKNOWN);
    if (externalBlockerAhead || externalSinglePresence || oppositeSingleAhead) {
      selfOwnedStaleRetainCandidates.remove(TrainNameNormalizer.normalizeKey(request.trainName()));
    } else {
      rememberSelfOwnedStaleRetainCandidate(
          request, resource, selfClaim, heldDirection, requestedDirection, reason, now);
    }
    if (externalBlockerAhead || oppositeSingleAhead) {
      traceSelfOwnedExternalBlocker(request, resource, externalBlockerDetail);
    }
    traceSmartSelfOwnedContinuation(
        request,
        resource,
        externalBlockerAhead || externalSinglePresence || oppositeSingleAhead
            ? "SMART_SELF_OWNED_CONTINUATION_BLOCKED_BY_EXTERNAL_OWNER"
            : "SMART_SELF_OWNED_CONTINUATION_BLOCKED",
        SignalAspect.STOP,
        reason,
        heldDirection,
        requestedDirection,
        directionMatches,
        pathExitsZone,
        externalBlockerAhead || externalSinglePresence);
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                SignalComputationTrace.Source.OCCUPANCY,
                SignalAspect.STOP)
            .primaryReason("SELF_OWNED_CONFLICT_CONTINUATION_REJECTED")
            .field("conflictKey", resource.key())
            .field("heldDirection", heldDirection)
            .field("requestedDirection", requestedDirection)
            .field("source", source)
            .field("reason", reason)
            .field("selfOwnedDirectionMatches", directionMatches)
            .field("selfOwnedPathExitsZone", pathExitsZone)
            .field("selfOwnedExternalBlockerAhead", externalBlockerAhead)
            .field("selfOwnedExternalSinglePresence", externalSinglePresence)
            .field("selfOwnedOppositeSingleAhead", oppositeSingleAhead)
            .field(
                "externalBlockerOwner",
                externalBlockerDetail.map(ExternalBlockerDetail::owner).orElse("-"))
            .field(
                "externalBlockerResource",
                externalBlockerDetail.map(ExternalBlockerDetail::resourceKey).orElse("-"))
            .request(request));
    return Optional.of(
        new OccupancyDecision(
            false,
            now,
            SignalAspect.STOP,
            selfOwnedContinuationBlockers(request, resource, selfClaim, externalBlockerDetail),
            false,
            "opposite-direction".equals(reason)
                ? "self-owned-single-opposite-direction"
                : "self-owned-single-continuation-rejected"));
  }

  private List<OccupancyClaim> selfOwnedContinuationBlockers(
      OccupancyRequest request,
      OccupancyResource singleResource,
      OccupancyClaim selfClaim,
      Optional<ExternalBlockerDetail> externalBlockerDetail) {
    List<OccupancyClaim> blockers = new ArrayList<>();
    addUniqueClaim(blockers, selfClaim);
    addExternalClaimsForResource(blockers, request, singleResource);
    externalBlockerDetail
        .map(ExternalBlockerDetail::resourceKey)
        .ifPresent(resourceKey -> addExternalClaimsForResourceKey(blockers, request, resourceKey));
    return List.copyOf(blockers);
  }

  private void addExternalClaimsForResource(
      List<OccupancyClaim> blockers, OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return;
    }
    List<OccupancyClaim> existing = claims.get(resource);
    if (existing == null || existing.isEmpty()) {
      return;
    }
    for (OccupancyClaim claim : existing) {
      if (claim == null
          || TrainNameNormalizer.sameLogicalTrain(claim.trainName(), request.trainName())) {
        continue;
      }
      addUniqueClaim(blockers, claim);
    }
  }

  private void addExternalClaimsForResourceKey(
      List<OccupancyClaim> blockers, OccupancyRequest request, String resourceKey) {
    if (request == null || resourceKey == null || resourceKey.isBlank()) {
      return;
    }
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null
          || (resource.kind() == ResourceKind.CONFLICT
              && !OccupancyResourceResolver.isInterlockingConflict(resource))
          || !request.intentFor(resource).hardAuthority()
          || !resourceKey.equals(resource.kind() + ":" + resource.key())) {
        continue;
      }
      addExternalClaimsForResource(blockers, request, resource);
    }
  }

  private static void addUniqueClaim(List<OccupancyClaim> claims, OccupancyClaim claim) {
    if (claims == null || claim == null || claims.contains(claim)) {
      return;
    }
    claims.add(claim);
  }

  /**
   * 判断同车旧 single claim 是否仍位于本次 hard-authority 计划内。
   *
   * <p>调度窗口可能短于完整 route plan，导致 movement snapshot 没有当前 single key；此时只要该资源仍是本次 {@link
   * ResourceIntent#MOVEMENT_REQUIRED} 且请求自身能解析出明确方向，就应视为同向继续运行，而不是把本车旧 claim 当成阻塞。
   */
  private boolean selfOwnedPathExitsOrContinuesZone(
      OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null || !request.resourceList().contains(resource)) {
      return false;
    }
    if (!request.intentFor(resource).hardAuthority()) {
      return false;
    }
    Optional<MovementPlanSnapshot> plan = request.movementPlanSnapshot();
    if (plan.isEmpty()) {
      return false;
    }
    if (plan.get().singleConflictDirections().containsKey(resource.key())) {
      return true;
    }
    return plan.get().movementRequiredResources().contains(resource)
        && resolveCorridorDirectionWithSource(request, resource).direction().isPresent();
  }

  private boolean hasExternalHardBlockerAhead(OccupancyRequest request) {
    return firstExternalHardBlockerDetail(request, null, CorridorDirection.UNKNOWN, false)
        .isPresent();
  }

  private Optional<ExternalBlockerDetail> firstExternalHardBlockerDetail(
      OccupancyRequest request,
      OccupancyResource focusResource,
      CorridorDirection direction,
      boolean ignoreSameDirectionFront) {
    if (request == null) {
      return Optional.empty();
    }
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null
          || !request.intentFor(resource).hardAuthority()
          || (resource.kind() == ResourceKind.CONFLICT
              && !OccupancyResourceResolver.isInterlockingConflict(resource))) {
        continue;
      }
      List<OccupancyClaim> existing = claims.get(resource);
      if (existing == null || existing.isEmpty()) {
        continue;
      }
      for (OccupancyClaim claim : existing) {
        if (claim == null) {
          continue;
        }
        if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), request.trainName())) {
          traceSelfOwnedBlockerFiltered(request, resource, claim, "SELF_OWNED_BLOCKER_FILTERED");
          continue;
        }
        if (BlockerClassifier.classify(request, resource, claim)
            == BlockerRelation.STALE_PROTECTIVE_CLAIM) {
          continue;
        }
        if (ignoreSameDirectionFront
            && sameDirectionSectionMatch(request, claim.trainName()).isPresent()) {
          traceSelfOwnedSameDirectionFrontFiltered(
              request, focusResource, resource, claim, direction);
          continue;
        }
        return Optional.of(
            new ExternalBlockerDetail(claim.trainName(), resource.kind() + ":" + resource.key()));
      }
    }
    return Optional.empty();
  }

  private boolean hasExternalSinglePresence(OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return false;
    }
    List<OccupancyClaim> existing = claims.get(resource);
    if (existing != null) {
      for (OccupancyClaim claim : existing) {
        if (claim != null
            && !TrainNameNormalizer.sameLogicalTrain(claim.trainName(), request.trainName())) {
          return true;
        }
      }
    }
    ConflictQueue queue = queues.get(resource);
    return queue != null && !queue.isEmpty() && queue.hasAnyOtherTrain(request.trainName());
  }

  private void traceSelfOwnedSameDirectionFrontFiltered(
      OccupancyRequest request,
      OccupancyResource focusResource,
      OccupancyResource blockerResource,
      OccupancyClaim claim,
      CorridorDirection requestedDirection) {
    if (request == null || blockerResource == null || claim == null) {
      return;
    }
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                SignalComputationTrace.Source.OCCUPANCY,
                SignalAspect.PROCEED)
            .primaryReason("SELF_OWNED_SAME_DIRECTION_FRONT_BLOCKER_FILTERED")
            .field("conflictKey", focusResource == null ? "-" : focusResource.key())
            .field("blockerResource", blockerResource)
            .field("blockerOwner", claim.trainName())
            .field("requestedDirection", requestedDirection)
            .field("reason", "same-direction-front-hard-blocker")
            .request(request));
  }

  private void traceSelfOwnedExternalBlocker(
      OccupancyRequest request,
      OccupancyResource resource,
      Optional<ExternalBlockerDetail> externalBlockerDetail) {
    if (request == null || resource == null) {
      return;
    }
    ExternalBlockerDetail detail =
        externalBlockerDetail.orElse(
            new ExternalBlockerDetail("-", resource.kind() + ":" + resource.key()));
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                SignalComputationTrace.Source.OCCUPANCY,
                SignalAspect.STOP)
            .primaryReason("SMART_SELF_OWNED_EXTERNAL_BLOCKER")
            .field("conflictKey", resource.key())
            .field("blockerOwner", detail.owner())
            .field("blockerResource", detail.resourceKey())
            .request(request));
  }

  private void traceSmartSelfOwnedContinuation(
      OccupancyRequest request,
      OccupancyResource resource,
      String event,
      SignalAspect aspect,
      String reason,
      CorridorDirection heldDirection,
      CorridorDirection requestedDirection,
      boolean directionMatches,
      boolean pathExitsZone,
      boolean externalBlockerAhead) {
    if (request == null || resource == null) {
      return;
    }
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                SignalComputationTrace.Source.OCCUPANCY,
                aspect == null ? SignalAspect.STOP : aspect)
            .primaryReason(event)
            .field("conflictKey", resource.key())
            .field(
                "heldDirection", heldDirection == null ? CorridorDirection.UNKNOWN : heldDirection)
            .field(
                "requestedDirection",
                requestedDirection == null ? CorridorDirection.UNKNOWN : requestedDirection)
            .field("reason", reason == null || reason.isBlank() ? "-" : reason)
            .field("selfOwnedDirectionMatches", directionMatches)
            .field("selfOwnedPathExitsZone", pathExitsZone)
            .field("selfOwnedExternalBlockerAhead", externalBlockerAhead)
            .field(
                "directionSource", resolveCorridorDirectionWithSource(request, resource).source())
            .request(request));
  }

  private void rememberSelfOwnedStaleRetainCandidate(
      OccupancyRequest request,
      OccupancyResource resource,
      OccupancyClaim selfClaim,
      CorridorDirection heldDirection,
      CorridorDirection requestedDirection,
      String reason,
      Instant now) {
    if (request == null
        || resource == null
        || selfClaim == null
        || resource.kind() != ResourceKind.CONFLICT
        || OccupancyResourceResolver.isInterlockingConflict(resource)
        || !TrainNameNormalizer.sameLogicalTrain(selfClaim.trainName(), request.trainName())) {
      return;
    }
    ResourceIntent requestIntent = request.intentFor(resource);
    if (!isProtectiveSelfRetain(requestIntent, selfClaim.role())) {
      return;
    }
    if (isStillInsideSwitcherZoneRetain(request, resource)) {
      traceStaleRetainReleaseSkippedInsideSwitcherZone(request, resource);
      return;
    }
    String key = TrainNameNormalizer.normalizeKey(request.trainName());
    if (key.isEmpty()) {
      return;
    }
    SelfOwnedStaleRetainCandidate candidate =
        new SelfOwnedStaleRetainCandidate(
            request.trainName(),
            resource,
            selfClaim.role(),
            requestIntent,
            heldDirection,
            requestedDirection,
            hardAuthorityScope(request),
            reason,
            now != null ? now : request.now());
    selfOwnedStaleRetainCandidates.put(key, candidate);
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                SignalComputationTrace.Source.OCCUPANCY,
                SignalAspect.STOP)
            .primaryReason("SMART_STALE_SELF_RETAIN_RELEASE_CANDIDATE")
            .field("conflictKey", resource.key())
            .field("claimRole", selfClaim.role())
            .field("requestIntent", requestIntent)
            .field("heldDirection", heldDirection)
            .field("requestedDirection", requestedDirection)
            .field("reason", candidate.reason())
            .field(
                "directionSource", resolveCorridorDirectionWithSource(request, resource).source())
            .field("destinationMutated", false)
            .field("tokenInvalidated", false)
            .request(request));
  }

  private static Set<OccupancyResource> hardAuthorityScope(OccupancyRequest request) {
    if (request == null) {
      return Set.of();
    }
    Set<OccupancyResource> scope = new LinkedHashSet<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource != null && request.intentFor(resource).hardAuthority()) {
        scope.add(resource);
      }
    }
    return Set.copyOf(scope);
  }

  private boolean hasExternalClaimInScope(SelfOwnedStaleRetainCandidate candidate) {
    if (candidate == null) {
      return false;
    }
    for (OccupancyResource resource : candidate.hardAuthorityScope()) {
      if (resource != null && hasExternalClaim(resource, candidate.trainName())) {
        return true;
      }
    }
    return false;
  }

  private boolean isStillInsideSwitcherZoneRetain(
      OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null || resource.kind() != ResourceKind.CONFLICT) {
      return false;
    }
    Optional<MovementPlanSnapshot> planOpt = request.movementPlanSnapshot();
    if (planOpt.isEmpty()) {
      return false;
    }
    MovementPlanSnapshot plan = planOpt.get();
    if (!plan.switcherPathSignatures().isEmpty()) {
      return true;
    }
    if (!resource.key().startsWith("switcher:")) {
      return false;
    }
    String switcherNode = resource.key().substring("switcher:".length()).trim();
    if (switcherNode.isBlank()) {
      return false;
    }
    return plan.currentNode().map(NodeId::value).filter(switcherNode::equals).isPresent()
        || plan.lastPassedGraphNode().map(NodeId::value).filter(switcherNode::equals).isPresent()
        || plan.effectiveFromNode().map(NodeId::value).filter(switcherNode::equals).isPresent()
        || plan.effectiveToNode().map(NodeId::value).filter(switcherNode::equals).isPresent();
  }

  private void traceStaleRetainReleaseSkippedInsideSwitcherZone(
      OccupancyRequest request, OccupancyResource resource) {
    MovementPlanSnapshot plan = request.movementPlanSnapshot().orElse(null);
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                SignalComputationTrace.Source.OCCUPANCY,
                SignalAspect.STOP)
            .primaryReason("SMART_STALE_RETAIN_RELEASE_SKIPPED")
            .field("train", request.trainName())
            .field("reason", "still-inside-switcher-zone")
            .field("conflictKey", resource.key())
            .field(
                "currentNode",
                plan == null ? "-" : plan.currentNode().map(NodeId::value).orElse("-"))
            .field(
                "lastPassedGraphNode",
                plan == null ? "-" : plan.lastPassedGraphNode().map(NodeId::value).orElse("-"))
            .field(
                "nextTarget",
                plan == null ? "-" : plan.effectiveToNode().map(NodeId::value).orElse("-"))
            .request(request));
  }

  private void traceSelfOwnedBlockerFiltered(
      OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim, String event) {
    if (request == null || resource == null || claim == null) {
      return;
    }
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                SignalComputationTrace.Source.OCCUPANCY,
                SignalAspect.PROCEED)
            .primaryReason(event)
            .field("resource", resource)
            .field("owner", claim.trainName())
            .field("ownerCanonical", TrainNameNormalizer.normalizeKey(claim.trainName()))
            .field("currentTrainCanonical", TrainNameNormalizer.normalizeKey(request.trainName()))
            .field("claimRole", claim.role())
            .field("relation", "SELF")
            .request(request));
  }

  private boolean hasOppositeLiveSinglePresence(
      OccupancyRequest request, OccupancyResource resource, CorridorDirection requestedDirection) {
    List<OccupancyClaim> existing = claims.get(resource);
    if (existing != null) {
      for (OccupancyClaim claim : existing) {
        if (claim == null
            || TrainNameNormalizer.sameLogicalTrain(claim.trainName(), request.trainName())) {
          if (claim != null) {
            traceSelfOwnedBlockerFiltered(
                request, resource, claim, "SELF_OWNED_OPPOSITE_REJECTED_AS_NOT_EXTERNAL");
          }
          continue;
        }
        CorridorDirection claimDirection =
            claim.corridorDirection().orElse(CorridorDirection.UNKNOWN);
        if (claimDirection == CorridorDirection.UNKNOWN || claimDirection != requestedDirection) {
          return true;
        }
      }
    }
    ConflictQueue queue = queues.get(resource);
    return queue != null && queue.hasEntriesOutside(requestedDirection, request.trainName());
  }

  private boolean selfOwnedQueueEntryCanContinue(
      OccupancyRequest request,
      OccupancyResource resource,
      ConflictQueue queue,
      OccupancyQueueEntry entry) {
    if (request == null || resource == null || queue == null || entry == null) {
      return false;
    }
    if (!isSingleCorridorConflict(resource)) {
      return queue.isHeadAny(request.trainName(), request.now());
    }
    CorridorDirection requested = queueDirectionFor(request, resource);
    CorridorDirection queued = entry.direction();
    if (requested == CorridorDirection.UNKNOWN || queued == CorridorDirection.UNKNOWN) {
      return false;
    }
    return requested == queued && !queue.hasEntriesOutside(requested, request.trainName());
  }

  private OccupancyDecision selfOwnedQueueMismatchDecision(
      OccupancyRequest request,
      OccupancyResource resource,
      OccupancyQueueEntry entry,
      Instant now) {
    OccupancyClaim blocker = createQueueBlocker(resource, entry);
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                SignalComputationTrace.Source.OCCUPANCY,
                SignalAspect.STOP)
            .primaryReason("SELF_OWNED_CONFLICT_CONTINUATION_REJECTED")
            .field("conflictKey", resource.key())
            .field("heldDirection", entry.direction())
            .field("requestedDirection", queueDirectionFor(request, resource))
            .field("directionSource", queueDirectionResolutionFor(request, resource).source())
            .field("source", "queue")
            .field("reason", "opposite-or-unknown-direction")
            .request(request));
    return new OccupancyDecision(
        false,
        now,
        SignalAspect.STOP,
        List.of(blocker),
        false,
        "self-owned-single-opposite-direction");
  }

  private int queueEntryOrderFor(OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return Integer.MAX_VALUE;
    }
    Integer order = request.conflictEntryOrders().get(resource.key());
    if (order == null || order < 0) {
      return Integer.MAX_VALUE;
    }
    return order;
  }

  private Set<OccupancyResource> resolveQueueTargets(
      OccupancyRequest request, Set<OccupancyResource> blockedResources) {
    Set<OccupancyResource> targets = new LinkedHashSet<>();
    if (blockedResources != null) {
      for (OccupancyResource resource : blockedResources) {
        if (isQueueableConflict(resource)) {
          targets.add(resource);
        }
      }
    }
    if (!targets.isEmpty()) {
      return targets;
    }
    targets.addAll(resolveConflictCandidates(request));
    return targets;
  }

  /**
   * 按入口距离解析冲突释放候选。
   *
   * <p>长单线 lookahead 可能同时覆盖多个 conflict。若只看最近的 primary conflict，当 blocker 所在列车排在后续 conflict
   * 队列中时会漏掉真实互锁。这里按 entryOrder 从近到远扫描所有候选，并以请求资源顺序补齐没有 entryOrder 的冲突资源，避免主冲突窗口切换导致稳定互卡。
   */
  private List<OccupancyResource> resolveConflictCandidates(OccupancyRequest request) {
    if (request == null) {
      return List.of();
    }
    Map<String, Integer> entryOrders = request.conflictEntryOrders();
    LinkedHashSet<OccupancyResource> candidates = new LinkedHashSet<>();
    if (entryOrders != null && !entryOrders.isEmpty()) {
      entryOrders.entrySet().stream()
          .filter(entry -> entry != null && entry.getKey() != null)
          .sorted(
              java.util.Comparator.comparingInt(
                  entry -> entry.getValue() != null ? entry.getValue() : Integer.MAX_VALUE))
          .forEach(
              entry -> {
                OccupancyResource conflict = OccupancyResource.forConflict(entry.getKey());
                if (isQueueableConflict(conflict)) {
                  candidates.add(conflict);
                }
              });
    }
    for (OccupancyResource resource : request.resourceList()) {
      if (isQueueableConflict(resource)) {
        candidates.add(resource);
      }
    }
    return List.copyOf(candidates);
  }

  private boolean containsConflictBlocker(List<OccupancyClaim> blockers) {
    if (blockers == null || blockers.isEmpty()) {
      return false;
    }
    for (OccupancyClaim claim : blockers) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      if (claim.resource().kind() == ResourceKind.CONFLICT) {
        return true;
      }
    }
    return false;
  }

  private boolean hasVerifiedConflictReleaseHint(
      OccupancyRequest request, OccupancyResource conflict) {
    if (request == null
        || conflict == null
        || OccupancyResourceResolver.isInterlockingConflict(conflict)
        || request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return false;
    }
    ConflictReleaseHint hint = request.conflictReleaseHints().get(conflict.key());
    if (hint == null || !hint.verifiedFor(conflict.key())) {
      return false;
    }
    if (hint.kind() == ConflictClearingEvidenceKind.VERIFIED_SWITCHER_OCCUPANT) {
      return verifiedSwitcherDrainClaims(request).contains(conflict);
    }
    return true;
  }

  private boolean hasVerifiedSwitcherOccupantHint(
      OccupancyRequest request, OccupancyResource conflict) {
    return conflict != null && verifiedSwitcherDrainClaims(request).contains(conflict);
  }

  private Set<OccupancyResource> verifiedSwitcherDrainClaims(OccupancyRequest request) {
    return VerifiedSwitcherDrainClaims.resolve(
        request,
        new VerifiedSwitcherDrainClaims.VerificationSnapshot(snapshotClaims(), version(), -1L));
  }

  /**
   * 计算冲突释放时不能写入的、且被本车有效 release lock 精确覆盖的 CONFLICT 资源。
   *
   * <p>即使一个请求同时包含多个冲突资源，也只能跳过“hint、release lock 与 blocker key 三者完全一致”的资源。这样前一道岔的短期锁不会顺带绕过后续新出现的道岔。
   */
  private Set<OccupancyResource> resolveBlockedResourcesForPartialAcquire(
      OccupancyRequest request, OccupancyDecision decision, Instant now) {
    if (request == null || decision == null || decision.blockers().isEmpty()) {
      return Set.of();
    }
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    for (OccupancyClaim blocker : decision.blockers()) {
      if (blocker == null || blocker.resource() == null) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), request.trainName())) {
        continue;
      }
      if (!activeReleaseCoversConflict(request, blocker.resource(), now)) {
        continue;
      }
      resources.add(blocker.resource());
    }
    return Set.copyOf(resources);
  }

  /**
   * 查找未被当前 release lock 精确覆盖的外部冲突 blocker。
   *
   * <p>这是 acquire 前的防御性复核：canEnter 的 winner 选择即使未来发生回归，也不能把同一次 {@code conflictRelease} 扩张到另一个
   * conflict key。
   */
  private Optional<OccupancyClaim> firstConflictBlockerOutsideActiveRelease(
      OccupancyRequest request, List<OccupancyClaim> blockers, Instant now) {
    if (request == null || blockers == null || blockers.isEmpty()) {
      return Optional.empty();
    }
    for (OccupancyClaim blocker : blockers) {
      if (blocker == null
          || blocker.resource() == null
          || blocker.resource().kind() != ResourceKind.CONFLICT
          || TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), request.trainName())) {
        continue;
      }
      if (!activeReleaseCoversConflict(request, blocker.resource(), now)) {
        return Optional.of(blocker);
      }
    }
    return Optional.empty();
  }

  /** 判断指定冲突是否同时具备本请求的已验证 hint 与本车尚未过期的同 key release lock。 */
  private boolean activeReleaseCoversConflict(
      OccupancyRequest request, OccupancyResource conflict, Instant now) {
    if (request == null
        || conflict == null
        || conflict.kind() != ResourceKind.CONFLICT
        || OccupancyResourceResolver.isInterlockingConflict(conflict)
        || !hasVerifiedConflictReleaseHint(request, conflict)) {
      return false;
    }
    DeadlockReleaseLock lock = deadlockReleaseLocks.get(conflict.key());
    return lock != null && !lock.isExpired(now) && lock.matches(request.trainName());
  }

  /**
   * 校验阻塞列车是否都在同一冲突队列内。
   *
   * <p>用于死锁放行锁生效时的安全校验：允许跳过“队头判断”，但仍需确保阻塞来源来自同一冲突队列。switcher 当前 owner 在成功 acquire 后会离开等待队列，因此同一个
   * switcher resource 上仍保有路径签名的 claim 也属于该冲突；该例外不适用于无方向 single claim。
   */
  private boolean areBlockersInQueue(
      List<OccupancyClaim> blockers,
      ConflictQueue queue,
      String trainName,
      OccupancyResource conflict) {
    if (blockers == null || blockers.isEmpty() || queue == null) {
      return false;
    }
    for (OccupancyClaim claim : blockers) {
      if (claim == null || claim.trainName() == null) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        continue;
      }
      if (!conflict.equals(claim.resource())) {
        return false;
      }
      if (!queue.contains(claim.trainName())
          && !isDirectionalConflictClaim(claim)
          && !isSignedSwitcherClaimForConflict(claim, conflict)) {
        return false;
      }
    }
    return true;
  }

  private boolean isSignedSwitcherClaimForConflict(
      OccupancyClaim claim, OccupancyResource conflict) {
    return claim != null
        && conflict != null
        && isSwitcherConflictResource(conflict)
        && conflict.equals(claim.resource())
        && switcherClaimSignature(conflict, claim.trainName()).isPresent();
  }

  private boolean isDirectionalConflictClaim(OccupancyClaim claim) {
    return claim != null
        && claim.resource() != null
        && claim.resource().kind() == ResourceKind.CONFLICT
        && claim.corridorDirection().isPresent();
  }

  /**
   * 判定当前阻塞是否包含“对向列车”。
   *
   * <p>仅在单线冲突资源上生效：冲突区放行的目标是解开对向会车死锁，不应用于同向跟驰场景。 若存在同向阻塞列车，说明请求侧前方仍有列车，不应优先放行。 对向方向无法判定时（例如 UNKNOWN
   * 或队列中缺少方向），按安全侧拒绝放行，避免把同向前后车误判为会车死锁。
   */
  private boolean hasOppositeDirectionBlockerInQueue(
      OccupancyRequest request,
      List<OccupancyClaim> blockers,
      OccupancyResource conflict,
      ConflictQueue queue) {
    if (!isSingleCorridorConflict(conflict)) {
      return true;
    }
    CorridorDirection requestDirection = queueDirectionFor(request, conflict);
    if (requestDirection == CorridorDirection.UNKNOWN) {
      return false;
    }
    boolean hasOppositeDirectionBlocker = false;
    for (OccupancyClaim blocker : blockers) {
      if (blocker == null || blocker.trainName() == null) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), request.trainName())) {
        continue;
      }
      Optional<CorridorDirection> blockerDirection = queue.directionOf(blocker.trainName());
      if (blockerDirection.isEmpty()
          && blocker.resource() != null
          && blocker.resource().equals(conflict)) {
        blockerDirection = blocker.corridorDirection();
      }
      if (blockerDirection.isEmpty() || blockerDirection.get() == CorridorDirection.UNKNOWN) {
        return false;
      }
      if (blockerDirection.get() == requestDirection) {
        return false;
      }
      if (isOppositeDirection(requestDirection, blockerDirection.get())) {
        hasOppositeDirectionBlocker = true;
      }
    }
    return hasOppositeDirectionBlocker;
  }

  private boolean isOppositeDirection(
      CorridorDirection firstDirection, CorridorDirection secondDirection) {
    return (firstDirection == CorridorDirection.A_TO_B
            && secondDirection == CorridorDirection.B_TO_A)
        || (firstDirection == CorridorDirection.B_TO_A
            && secondDirection == CorridorDirection.A_TO_B);
  }

  /**
   * 冲突区放行：当两侧列车互相占用节点导致阻塞时，尝试放行**全局队头**列车进入冲突区。
   *
   * <p>采用"单侧放行 + 稳定性锁定"策略：
   *
   * <ul>
   *   <li>仅放行全局队头（不区分方向的最早到达者），另一侧必须等待
   *   <li>一旦放行某车，锁定该决策一段时间，避免信号乒乓
   *   <li>锁定期间对手车请求直接拒绝
   * </ul>
   *
   * <p>仅当不存在冲突资源占用，且阻塞列车均在同一冲突队列中时生效。
   */
  private OccupancyDecision tryResolveConflictDeadlock(
      OccupancyRequest request, List<OccupancyClaim> blockers, Instant now) {
    if (request == null || blockers == null || blockers.isEmpty()) {
      return null;
    }
    if (request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return null;
    }
    if (firstHardBlocker(blockers, request.trainName()) != null) {
      return null;
    }
    if (!containsConflictBlocker(blockers)) {
      return null;
    }
    // 优先检查：列车是否持有当前 blocker 所属冲突的放行锁（避免主冲突切换导致信号乒乓）
    OccupancyDecision heldLockDecision = tryResolveByHeldLock(request, blockers, now);
    if (heldLockDecision != null) {
      return heldLockDecision;
    }
    List<OccupancyResource> candidates = resolveConflictCandidates(request);
    if (candidates.isEmpty()) {
      return null;
    }
    for (OccupancyResource conflict : candidates) {
      ConflictQueue queue = queues.get(conflict);
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      // 检查是否有其他车持有该冲突的锁
      DeadlockReleaseLock existingLock = deadlockReleaseLocks.get(conflict.key());
      if (existingLock != null && !existingLock.isExpired(now)) {
        if (!existingLock.matches(request.trainName())) {
          // 其他车持有锁，当前车必须等待
          continue;
        }
        // 当前车持有锁（已在 tryResolveByHeldLock 处理，理论上不会到这里）
      }
      // 普通候选必须是全局队头；已认证的 switcher 实体 occupant 优先清空道口。
      if (!queue.isHeadAny(request.trainName(), now)
          && !hasVerifiedSwitcherOccupantHint(request, conflict)) {
        continue;
      }
      if (!hasVerifiedConflictReleaseHint(request, conflict)) {
        continue;
      }
      if (!areBlockersInQueue(blockers, queue, request.trainName(), conflict)) {
        continue;
      }
      if (!hasOppositeDirectionBlockerInQueue(request, blockers, conflict, queue)) {
        continue;
      }
      // 放行并写入锁定
      Instant expiresAt = now.plus(DEADLOCK_RELEASE_LOCK_TTL);
      rememberDeadlockReleaseLock(conflict.key(), request.trainName(), expiresAt);
      SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
      return new OccupancyDecision(true, now, signal, List.copyOf(blockers), true);
    }
    return null;
  }

  /** 写入会改变冲突 winner 的放行锁，并同步推进占用快照版本。 */
  void rememberDeadlockReleaseLock(String conflictKey, String trainName, Instant expiresAt) {
    DeadlockReleaseLock next = new DeadlockReleaseLock(trainName, expiresAt);
    DeadlockReleaseLock previous = deadlockReleaseLocks.put(conflictKey, next);
    if (!next.equals(previous)) {
      traceQueueLifecycle(
          OccupancyResource.forConflict(conflictKey),
          next.trainName(),
          null,
          null,
          null,
          null,
          null,
          null,
          "DEADLOCK_RELEASE_LOCK",
          previous == null ? "acquire" : "update",
          "deadlock-release-winner:previous="
              + (previous == null ? "-" : safeLifecycleValue(previous.trainName())),
          next.expiresAt());
      version.incrementAndGet();
    }
  }

  /**
   * 检查列车是否持有当前外部 blocker 所属冲突的有效放行锁。
   *
   * <p>当列车请求多个冲突资源时，每次 tick 的“主冲突”可能因 blockers 变化而切换。此方法在确定主冲突之前扫描请求中的 release lock，但只有 hint、lock
   * 与全部外部 blocker 都精确指向同一个 conflict key 时才续用；前一道岔的锁不能放行后一道岔。
   */
  private OccupancyDecision tryResolveByHeldLock(
      OccupancyRequest request, List<OccupancyClaim> blockers, Instant now) {
    if (request == null || request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return null;
    }
    if (firstHardBlocker(blockers, request.trainName()) != null
        || !containsConflictBlocker(blockers)) {
      return null;
    }
    Map<String, Integer> entryOrders = request.conflictEntryOrders();
    if (entryOrders == null || entryOrders.isEmpty()) {
      return null;
    }
    for (String conflictKey : entryOrders.keySet()) {
      DeadlockReleaseLock lock = deadlockReleaseLocks.get(conflictKey);
      if (lock == null || lock.isExpired(now)) {
        continue;
      }
      if (!lock.matches(request.trainName())) {
        // 其他车持有该冲突的锁，当前车不能被任何锁放行
        continue;
      }
      if (!hasVerifiedConflictReleaseHint(request, OccupancyResource.forConflict(conflictKey))) {
        continue;
      }
      ConflictQueue queue = queues.get(OccupancyResource.forConflict(conflictKey));
      if (!areBlockersInQueue(
          blockers, queue, request.trainName(), OccupancyResource.forConflict(conflictKey))) {
        continue;
      }
      // 当前车持有该冲突的锁，且所有 blocker 仍严格属于同一个冲突区；同 key 内的 owner
      // 可凭方向或 switcher 路径签名在离开等待队列后继续被识别。
      SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
      return new OccupancyDecision(true, now, signal, List.copyOf(blockers), true);
    }
    return null;
  }

  /**
   * 预览模式下的冲突区放行：不写入锁定/队列，仅基于现有队列状态与 entryOrder 判断是否可放行。
   *
   * <p>用于 ETA/候选站台评估，避免预览逻辑与真实放行决策偏离。
   */
  private OccupancyDecision tryResolveConflictDeadlockPreview(
      OccupancyRequest request, List<OccupancyClaim> blockers, Instant now) {
    if (request == null || blockers == null || blockers.isEmpty()) {
      return null;
    }
    if (request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return null;
    }
    if (firstHardBlocker(blockers, request.trainName()) != null) {
      return null;
    }
    if (!containsConflictBlocker(blockers)) {
      return null;
    }
    List<OccupancyResource> candidates = resolveConflictCandidates(request);
    if (candidates.isEmpty()) {
      return null;
    }
    for (OccupancyResource conflict : candidates) {
      ConflictQueue queue = queues.get(conflict);
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      // 检查放行锁（只读）
      DeadlockReleaseLock existingLock = deadlockReleaseLocks.get(conflict.key());
      if (existingLock != null && !existingLock.isExpired(now)) {
        if (!existingLock.matches(request.trainName())) {
          continue;
        }
        // 当前车持有锁：跳过队头判断，但仍需确保阻塞来源在同一队列中。
        if (!areBlockersInQueue(blockers, queue, request.trainName(), conflict)) {
          continue;
        }
        if (!hasVerifiedConflictReleaseHint(request, conflict)) {
          continue;
        }
        SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
        return new OccupancyDecision(true, now, signal, List.copyOf(blockers), true);
      }
      // 普通候选必须是全局队头；已认证的 switcher 实体 occupant 优先清空道口。
      CorridorDirection direction = queueDirectionFor(request, conflict);
      if (!queue.wouldBeHeadAny(
              request.trainName(),
              direction,
              request.priority(),
              queueEntryOrderFor(request, conflict),
              now)
          && !hasVerifiedSwitcherOccupantHint(request, conflict)) {
        continue;
      }
      if (!areBlockersInQueue(blockers, queue, request.trainName(), conflict)) {
        continue;
      }
      if (!hasVerifiedConflictReleaseHint(request, conflict)) {
        continue;
      }
      if (!hasOppositeDirectionBlockerInQueue(request, blockers, conflict, queue)) {
        continue;
      }
      SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
      return new OccupancyDecision(true, now, signal, List.copyOf(blockers), true);
    }
    return null;
  }

  private void enqueueWaiting(
      OccupancyRequest request, Set<OccupancyResource> queueTargets, Instant now) {
    if (queueTargets == null || queueTargets.isEmpty()) {
      return;
    }
    for (OccupancyResource resource : queueTargets) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      CorridorDirection direction = queueDirectionFor(request, resource);
      ConflictQueue queue = queues.computeIfAbsent(resource, unused -> new ConflictQueue());
      QueueTouchResult queueTouch =
          touchQueueWithDirectionTrace(request, resource, queue, direction, now, "enqueueWaiting");
      commitQueueTouchChange(request, resource, now, queueTouch);
    }
  }

  /**
   * 提交队列刷新产生的仲裁语义变化。
   *
   * <p>首次入队的请求仍在当前完整授权链中，无需自我唤醒；既有条目的方向、优先级、入口顺序或道岔路径签名变化却可能把 winner 交给另一列车，因此必须在 version
   * 提交后通知竞争者进入下一 tick 重评估。
   */
  private void commitQueueTouchChange(
      OccupancyRequest request,
      OccupancyResource resource,
      Instant now,
      QueueTouchResult queueTouch) {
    if (queueTouch == null || !queueTouch.stateChanged()) {
      return;
    }
    version.incrementAndGet();
    if (queueTouch.queueHeadChanged()) {
      publishQueueChangedEvent(
          request.trainName(), List.of(resource), queueTouch.eligibleTrainNames(), now);
    }
  }

  private boolean retainQueuePosition(
      OccupancyResource resource, String trainName, OccupancyRequest queueRequest) {
    if (resource == null
        || trainName == null
        || trainName.isBlank()
        || queueRequest == null
        || !TrainNameNormalizer.sameLogicalTrain(queueRequest.trainName(), trainName)
        || !queueRequest.resourceList().contains(resource)
        || !isQueueableConflict(resource)) {
      return false;
    }
    ConflictQueue queue = queues.computeIfAbsent(resource, unused -> new ConflictQueue());
    CorridorDirection direction = queueDirectionFor(queueRequest, resource);
    touchQueueWithDirectionTrace(
        queueRequest,
        resource,
        queue,
        direction,
        queueRequest.now(),
        "recoverableAuthorityRelease");
    return true;
  }

  private QueueTouchResult touchQueueWithDirectionTrace(
      OccupancyRequest request,
      OccupancyResource resource,
      ConflictQueue queue,
      CorridorDirection requestedDirection,
      Instant now,
      String source) {
    if (request == null || resource == null || queue == null) {
      return new QueueTouchResult(
          requestedDirection == null ? CorridorDirection.UNKNOWN : requestedDirection,
          false,
          false,
          List.of());
    }
    CorridorDirection safeRequested =
        requestedDirection == null ? CorridorDirection.UNKNOWN : requestedDirection;
    CorridorDirection effectiveDirection = safeRequested;
    Optional<CorridorDirection> previous = queue.directionOf(request.trainName());
    if (previous.isPresent()
        && previous.get() != CorridorDirection.UNKNOWN
        && safeRequested == CorridorDirection.UNKNOWN) {
      effectiveDirection = previous.get();
      emitQueueDirectionTrace(
          request, resource, previous.get(), safeRequested, effectiveDirection, source);
    } else if (previous.isPresent() && previous.get() != safeRequested) {
      emitQueueDirectionTrace(
          request, resource, previous.get(), safeRequested, effectiveDirection, source);
    }
    Optional<OccupancyQueueEntry> previousEntry = queue.entryFor(request.trainName());
    Optional<OccupancyQueueEntry> previousQueueHead = queue.headAny(now);
    boolean queueChanged =
        queue.touch(
            request.trainName(),
            effectiveDirection,
            now,
            request.priority(),
            queueEntryOrderFor(request, resource));
    if (queueChanged) {
      OccupancyQueueEntry finalEntry = queue.entryFor(request.trainName()).orElse(null);
      traceQueueLifecycle(
          resource,
          finalEntry,
          "WAITING_TRAIN",
          previousEntry.isEmpty() ? "enqueue" : "update",
          source,
          null);
    }
    boolean signatureChanged = rememberSwitcherQueueSignature(request, resource);
    boolean stateChanged = queueChanged || signatureChanged;
    return new QueueTouchResult(
        effectiveDirection,
        stateChanged,
        previousEntry.isPresent(),
        newlyEligibleQueueHeads(previousQueueHead, queue.headAny(now)));
  }

  private CorridorDirection effectiveQueueDirectionForPreview(
      OccupancyRequest request,
      OccupancyResource resource,
      ConflictQueue queue,
      CorridorDirection requestedDirection) {
    CorridorDirection safeRequested =
        requestedDirection == null ? CorridorDirection.UNKNOWN : requestedDirection;
    if (request == null || resource == null || queue == null) {
      return safeRequested;
    }
    Optional<CorridorDirection> previous = queue.directionOf(request.trainName());
    if (previous.isPresent()
        && previous.get() != CorridorDirection.UNKNOWN
        && safeRequested == CorridorDirection.UNKNOWN) {
      emitQueueDirectionTrace(
          request, resource, previous.get(), safeRequested, previous.get(), "canEnterPreview");
      return previous.get();
    }
    return safeRequested;
  }

  private void emitQueueDirectionTrace(
      OccupancyRequest request,
      OccupancyResource resource,
      CorridorDirection oldDirection,
      CorridorDirection requestedDirection,
      CorridorDirection effectiveDirection,
      String source) {
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                SignalComputationTrace.Source.OCCUPANCY,
                SignalAspect.STOP)
            .primaryReason("QUEUE_DIRECTION_CHANGED")
            .field("conflictKey", resource.key())
            .field("oldDirection", oldDirection)
            .field("requestedDirection", requestedDirection)
            .field("newDirection", effectiveDirection)
            .field(
                "directionSource",
                requestedDirection == CorridorDirection.UNKNOWN
                        && effectiveDirection != CorridorDirection.UNKNOWN
                    ? DirectionSource.HELD_DIRECTION_FALLBACK
                    : queueDirectionResolutionFor(request, resource).source())
            .field("source", source)
            .field("reason", "preserve-known-direction-before-unknown-overwrite")
            .request(request));
  }

  private void purgeExpiredQueueEntries(Instant now) {
    if (now == null) {
      return;
    }
    // 清理过期的冲突放行锁
    if (!deadlockReleaseLocks.isEmpty()) {
      int releaseLockRemoved = 0;
      List<OccupancyResource> changedLockResources = new ArrayList<>();
      Iterator<Map.Entry<String, DeadlockReleaseLock>> releaseLockIterator =
          deadlockReleaseLocks.entrySet().iterator();
      while (releaseLockIterator.hasNext()) {
        Map.Entry<String, DeadlockReleaseLock> entry = releaseLockIterator.next();
        DeadlockReleaseLock lock = entry.getValue();
        if (lock != null && !lock.isExpired(now)) {
          continue;
        }
        traceQueueLifecycle(
            OccupancyResource.forConflict(entry.getKey()),
            lock == null ? null : lock.trainName(),
            null,
            null,
            null,
            null,
            null,
            null,
            "DEADLOCK_RELEASE_LOCK",
            "expire",
            "ttl-expired",
            lock == null ? null : lock.expiresAt());
        releaseLockIterator.remove();
        changedLockResources.add(OccupancyResource.forConflict(entry.getKey()));
        releaseLockRemoved++;
      }
      if (releaseLockRemoved > 0) {
        version.incrementAndGet();
        publishQueueChangedEvent(
            "*", changedLockResources, queueHeadsForResources(changedLockResources, now), now);
      }
    }
    // 清理过期的队列条目
    if (queues.isEmpty()) {
      return;
    }
    Iterator<Map.Entry<OccupancyResource, ConflictQueue>> iterator = queues.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<OccupancyResource, ConflictQueue> entry = iterator.next();
      ConflictQueue queue = entry.getValue();
      if (queue == null) {
        iterator.remove();
        continue;
      }
      Optional<OccupancyQueueEntry> previousQueueHead = queue.headAny(now);
      List<OccupancyQueueEntry> expiredEntries = queue.purgeExpired(now, QUEUE_ENTRY_TTL);
      if (!expiredEntries.isEmpty()) {
        expiredEntries.forEach(
            expired ->
                traceQueueLifecycle(
                    entry.getKey(),
                    expired,
                    "WAITING_TRAIN",
                    "expire",
                    "ttl-expired",
                    expired.lastSeen().plus(QUEUE_ENTRY_TTL)));
        staleQueueCleanupCount.addAndGet(expiredEntries.size());
        version.incrementAndGet();
        publishQueueChangedEvent(
            "*",
            List.of(entry.getKey()),
            newlyEligibleQueueHeads(previousQueueHead, queue.headAny(now)),
            now);
      }
      if (queue.isEmpty()) {
        iterator.remove();
      }
    }
    pruneDetachedSwitcherQueueSignatures();
  }

  private OccupancyDecision traceDecision(
      String reason, OccupancyRequest request, OccupancyDecision decision) {
    SignalDecisionInputType inputTypeBeforeDrainClassification =
        SignalDecisionInputClassifier.classify(request);
    boolean releaseHintVerified = hasAnyVerifiedConflictReleaseHint(request);
    boolean drainAuthorityPresent =
        request != null
            && request.purpose() == AuthorizationPurpose.CONFLICT_CLEARING
            && releaseHintVerified
            && decision != null
            && decision.conflictRelease();
    boolean drainLeader =
        decision != null
            && decision.conflictRelease()
            && request != null
            && request.purpose() == AuthorizationPurpose.CONFLICT_CLEARING;
    SignalDecisionInputType inputType =
        SignalDecisionInputClassifier.classify(
            request,
            new SignalDecisionInputClassifier.DrainClassificationContext(
                drainAuthorityPresent,
                drainAuthorityPresent,
                releaseHintVerified,
                releaseHintVerified,
                releaseHintVerified,
                false,
                hasOnlyTopologyExitHints(request)));
    boolean drainAuthorityInconsistent =
        drainAuthorityPresent && inputType == SignalDecisionInputType.DRAIN_THROUGH && !drainLeader;
    SignalAspect computedSignal = decision != null ? decision.signal() : SignalAspect.STOP;
    boolean publishSuppressed =
        SignalDecisionInputClassifier.isProceedLike(computedSignal)
            && !SignalDecisionInputClassifier.mayPublishProceed(
                request,
                inputType,
                drainLeader,
                drainAuthorityPresent,
                decision != null && decision.allowed() && !drainAuthorityInconsistent,
                false,
                SignalComputationTrace.TokenState.NONE,
                false);
    SignalAspect traceSignal = computedSignal;
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request != null ? request.trainName() : "-",
                request != null ? request.trainName() : "-",
                SignalComputationTrace.Source.OCCUPANCY,
                traceSignal)
            .primaryReason(reason)
            .field("publicationTrace", "SMART_SIGNAL_PUBLICATION_TRACE")
            .field("publicationAuthority", "TRACE_ONLY")
            .field("physicalPublished", false)
            .field("occupancyVersion", version())
            .field("staleQueueCleanupCount", staleQueueCleanupCount())
            .field("inputTypeBeforeDrainClassification", inputTypeBeforeDrainClassification)
            .field("inputTypeAfterDrainClassification", inputType)
            .field("signalDecisionInputType", inputType)
            .field("computedAspect", computedSignal)
            .field("publishedAspect", publishSuppressed ? "SUPPRESSED" : traceSignal.name())
            .field("publishSuppressed", publishSuppressed)
            .field(
                "zoneMembership",
                request != null && request.purpose() == AuthorizationPurpose.CONFLICT_CLEARING
                    ? "INSIDE_ZONE"
                    : "-")
            .field("drainLeader", drainLeader)
            .field("canEnterConflictRelease", decision != null && decision.conflictRelease())
            .field("canEnterReleaseLeader", drainLeader)
            .field("releaseHintVerified", releaseHintVerified)
            .field("drainAuthorityActive", drainAuthorityPresent)
            .field("drainAuthorityLeader", drainLeader)
            .field("drainAuthorityFresh", drainAuthorityPresent)
            .field("drainAuthorityZoneMatches", releaseHintVerified)
            .field("drainGateApplied", inputType == SignalDecisionInputType.DRAIN_THROUGH)
            .field(
                "drainGateSkippedReason",
                inputType == SignalDecisionInputType.DRAIN_THROUGH
                    ? "-"
                    : drainGateSkippedReason(request, drainAuthorityPresent, releaseHintVerified))
            .field(
                "drainAuthorityId",
                request == null || request.conflictReleaseHints().isEmpty()
                    ? "-"
                    : request.conflictReleaseHints().keySet())
            .field("incident", drainAuthorityInconsistent ? "DRAIN_AUTHORITY_INCONSISTENT" : "-")
            .request(request)
            .decision(decision, request));
    traceSwitcherBlockerReads(reason, request, decision);
    publishLiveBlockerSnapshot(reason, request, decision);
    return decision;
  }

  private void publishLiveBlockerSnapshot(
      String source, OccupancyRequest request, OccupancyDecision decision) {
    if (request == null
        || decision == null
        || decision.allowed()
        || decision.blockers().isEmpty()) {
      return;
    }
    try {
      liveBlockerSnapshotListener.onBlockedDecision(
          request.trainName(), decision, request, request.now(), source);
    } catch (RuntimeException ignored) {
      // 阻塞快照是诊断旁路，不能反向影响占用判定。
    }
  }

  private static boolean hasAnyVerifiedConflictReleaseHint(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return false;
    }
    for (ConflictReleaseHint hint : request.conflictReleaseHints().values()) {
      if (hint != null && hint.verifiedFor(hint.conflictKey())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasOnlyTopologyExitHints(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return false;
    }
    for (ConflictReleaseHint hint : request.conflictReleaseHints().values()) {
      if (hint == null || hint.kind() != ConflictClearingEvidenceKind.TOPOLOGY_EXIT_HINT) {
        return false;
      }
    }
    return true;
  }

  private static String drainGateSkippedReason(
      OccupancyRequest request, boolean drainAuthorityPresent, boolean releaseHintVerified) {
    if (request == null) {
      return "request-missing";
    }
    if (request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return "not-conflict-clearing";
    }
    if (hasOnlyTopologyExitHints(request)) {
      return "topology-exit-hint-only";
    }
    if (!releaseHintVerified) {
      return "release-hint-unverified";
    }
    if (!drainAuthorityPresent) {
      return "drain-authority-inactive";
    }
    return "not-drain-through";
  }

  private int removeFromQueuesForResources(String trainName, List<OccupancyResource> resources) {
    return removeFromQueuesForResources(trainName, resources, true);
  }

  /**
   * 移除指定资源上的排队条目。
   *
   * <p>新 claim 已在当前调用栈取得资源时，后继队首尚未 eligible，调用方必须传入 {@code false}，避免“admit 后撤队”伪装成放行事件。
   */
  private int removeFromQueuesForResources(
      String trainName, List<OccupancyResource> resources, boolean publishQueueEligibility) {
    if (trainName == null || trainName.isBlank()) {
      return 0;
    }
    if (resources == null || resources.isEmpty()) {
      return removeFromQueuesForTrain(trainName);
    }
    int removed = 0;
    Instant now = Instant.now();
    List<OccupancyResource> changedResources = new ArrayList<>();
    Set<String> eligibleTrainNames = new LinkedHashSet<>();
    for (OccupancyResource resource : resources) {
      ConflictQueue queue = queues.get(resource);
      if (queue == null) {
        continue;
      }
      Optional<OccupancyQueueEntry> previousQueueHead = queue.headAny(now);
      OccupancyQueueEntry removedEntry = queue.entryFor(trainName).orElse(null);
      if (queue.remove(trainName)) {
        traceQueueLifecycle(
            resource,
            removedEntry,
            "WAITING_TRAIN",
            "remove",
            "remove-from-queues-for-resources",
            null);
        removed++;
        changedResources.add(resource);
        if (publishQueueEligibility) {
          eligibleTrainNames.addAll(newlyEligibleQueueHeads(previousQueueHead, queue.headAny(now)));
        }
      }
      forgetSwitcherQueueSignature(resource, trainName);
      if (queue.isEmpty()) {
        queues.remove(resource);
      }
    }
    publishQueueChangedEvent(trainName, changedResources, List.copyOf(eligibleTrainNames), now);
    return removed;
  }

  private int removeFromQueuesForTrain(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return 0;
    }
    int removed = 0;
    Instant now = Instant.now();
    List<OccupancyResource> changedResources = new ArrayList<>();
    Set<String> eligibleTrainNames = new LinkedHashSet<>();
    Iterator<Map.Entry<OccupancyResource, ConflictQueue>> iterator = queues.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<OccupancyResource, ConflictQueue> entry = iterator.next();
      ConflictQueue queue = entry.getValue();
      if (queue == null) {
        iterator.remove();
        continue;
      }
      Optional<OccupancyQueueEntry> previousQueueHead = queue.headAny(now);
      OccupancyQueueEntry removedEntry = queue.entryFor(trainName).orElse(null);
      if (queue.remove(trainName)) {
        traceQueueLifecycle(
            entry.getKey(),
            removedEntry,
            "WAITING_TRAIN",
            "remove",
            "remove-from-queues-for-train",
            null);
        removed++;
        changedResources.add(entry.getKey());
        eligibleTrainNames.addAll(newlyEligibleQueueHeads(previousQueueHead, queue.headAny(now)));
      }
      forgetSwitcherQueueSignature(entry.getKey(), trainName);
      if (queue.isEmpty()) {
        iterator.remove();
      }
    }
    publishQueueChangedEvent(trainName, changedResources, List.copyOf(eligibleTrainNames), now);
    return removed;
  }

  /** 释放指定列车持有的冲突区放行锁。 */
  private int releaseDeadlockLocksForTrain(String trainName) {
    if (trainName == null || trainName.isBlank() || deadlockReleaseLocks.isEmpty()) {
      return 0;
    }
    int removed = 0;
    List<OccupancyResource> changedResources = new ArrayList<>();
    Iterator<Map.Entry<String, DeadlockReleaseLock>> iterator =
        deadlockReleaseLocks.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, DeadlockReleaseLock> entry = iterator.next();
      DeadlockReleaseLock lock = entry.getValue();
      if (lock == null || !lock.matches(trainName)) {
        continue;
      }
      traceQueueLifecycle(
          OccupancyResource.forConflict(entry.getKey()),
          lock.trainName(),
          null,
          null,
          null,
          null,
          null,
          null,
          "DEADLOCK_RELEASE_LOCK",
          "release",
          "release-by-train",
          lock.expiresAt());
      iterator.remove();
      changedResources.add(OccupancyResource.forConflict(entry.getKey()));
      removed++;
    }
    Instant now = Instant.now();
    publishQueueChangedEvent(
        trainName, changedResources, queueHeadsForResources(changedResources, now), now);
    return removed;
  }

  /**
   * 将排队位次投影为诊断 blocker。
   *
   * <p>该 blocker 不代表物理占用或已授予的行车权，必须保留 {@link ClaimRole#QUEUE_POSITION} 来源，避免 advisory 与 Smart
   * Dispatcher 把失败方队列误判为反向 hard authority。
   */
  private OccupancyClaim createQueueBlocker(OccupancyResource resource, OccupancyQueueEntry entry) {
    Optional<CorridorDirection> direction =
        entry.direction() == CorridorDirection.UNKNOWN
            ? Optional.empty()
            : Optional.of(entry.direction());
    return new OccupancyClaim(
        resource,
        entry.trainName(),
        Optional.empty(),
        entry.firstSeen(),
        Duration.ZERO,
        direction,
        ClaimRole.QUEUE_POSITION);
  }

  private OccupancyClaim findClaim(List<OccupancyClaim> list, String trainName) {
    if (list == null || trainName == null) {
      return null;
    }
    for (OccupancyClaim claim : list) {
      if (claim != null && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return claim;
      }
    }
    return null;
  }

  private boolean hasOtherLogicalClaim(List<OccupancyClaim> list, String trainName) {
    if (list == null || list.isEmpty()) {
      return false;
    }
    for (OccupancyClaim claim : list) {
      if (claim == null) {
        continue;
      }
      if (!TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasOtherQueueEntry(OccupancyResource resource, String trainName) {
    if (resource == null || !isQueueableConflict(resource)) {
      return false;
    }
    ConflictQueue queue = queues.get(resource);
    if (queue == null || queue.isEmpty()) {
      return false;
    }
    for (String queuedTrain : queue.allTrainNames()) {
      if (!TrainNameNormalizer.sameLogicalTrain(queuedTrain, trainName)) {
        return true;
      }
    }
    return false;
  }

  // ========== 事件发布 ==========

  /**
   * 发布占用获取事件。
   *
   * <p>收集受影响的列车（等待这些资源的列车），通知它们重新评估信号。
   */
  private void publishAcquiredEvent(
      OccupancyRequest request, List<OccupancyResource> acquiredResources, Instant now) {
    if (eventBus == null) {
      return;
    }
    List<OccupancyResource> resources =
        acquiredResources == null ? List.of() : List.copyOf(acquiredResources);
    if (resources.isEmpty()) {
      return;
    }
    List<String> affectedTrains = collectAffectedTrains(resources, request.trainName());
    OccupancyAcquiredEvent event =
        new OccupancyAcquiredEvent(now, request.trainName(), resources, affectedTrains);
    eventBus.publish(event);
  }

  /** 发布占用释放事件。 */
  private void publishReleasedEvent(
      String trainName, List<OccupancyResource> resources, Instant now) {
    if (eventBus == null) {
      return;
    }
    OccupancyReleasedEvent event = new OccupancyReleasedEvent(now, trainName, resources);
    eventBus.publish(event);
  }

  /** 发布包含精确队首资格变化的 Gate Queue 事件；空资格列表仅用于审计。 */
  private void publishQueueChangedEvent(
      String trainName,
      List<OccupancyResource> resources,
      List<String> eligibleTrainNames,
      Instant now) {
    if (eventBus == null || resources == null || resources.isEmpty()) {
      return;
    }
    eventBus.publish(new OccupancyQueueChangedEvent(now, trainName, resources, eligibleTrainNames));
  }

  /**
   * 返回队首变化后获得新仲裁机会的逻辑列车名。
   *
   * <p>同一逻辑列车仍为队首时，它没有获得新事实，不能因自己的状态刷新再次唤醒完整授权。
   */
  private static List<String> newlyEligibleQueueHeads(
      Optional<OccupancyQueueEntry> previousHead, Optional<OccupancyQueueEntry> currentHead) {
    if (currentHead.isEmpty()) {
      return List.of();
    }
    String currentTrainName = currentHead.get().trainName();
    if (currentTrainName == null || currentTrainName.isBlank()) {
      return List.of();
    }
    if (previousHead.isPresent()
        && TrainNameNormalizer.sameLogicalTrain(previousHead.get().trainName(), currentTrainName)) {
      return List.of();
    }
    return List.of(currentTrainName);
  }

  /** 返回指定资源当前可被仲裁的队首，供解除 deadlock release lock 后精确唤醒。 */
  private List<String> queueHeadsForResources(List<OccupancyResource> resources, Instant now) {
    if (resources == null || resources.isEmpty()) {
      return List.of();
    }
    Set<String> heads = new LinkedHashSet<>();
    for (OccupancyResource resource : resources) {
      ConflictQueue queue = queues.get(resource);
      if (queue == null) {
        continue;
      }
      queue
          .headAny(now)
          .map(OccupancyQueueEntry::trainName)
          .filter(trainName -> trainName != null && !trainName.isBlank())
          .ifPresent(heads::add);
    }
    return List.copyOf(heads);
  }

  /**
   * 收集等待指定资源的列车名单（排除自己）。
   *
   * <p>这些列车是占用变化的"受影响方"，需要重新评估信号。
   */
  private List<String> collectAffectedTrains(
      List<OccupancyResource> resources, String excludeTrain) {
    Set<String> affected = new LinkedHashSet<>();
    for (OccupancyResource resource : resources) {
      if (resource == null) {
        continue;
      }
      // 从队列中收集等待该资源的列车
      ConflictQueue queue = queues.get(resource);
      if (queue != null) {
        affected.addAll(queue.allTrainNames());
      }
      // 从现有占用中收集（用于冲突检测）
      List<OccupancyClaim> existing = claims.get(resource);
      if (existing != null) {
        for (OccupancyClaim claim : existing) {
          if (claim != null && claim.trainName() != null) {
            affected.add(claim.trainName());
          }
        }
      }
    }
    // 排除自己（大小写不敏感）
    if (excludeTrain != null) {
      affected.removeIf(name -> TrainNameNormalizer.sameLogicalTrain(name, excludeTrain));
    }
    return new ArrayList<>(affected);
  }

  private static final class ConflictQueue {

    private final LinkedHashMap<String, OccupancyQueueEntry> forward = new LinkedHashMap<>();
    private final LinkedHashMap<String, OccupancyQueueEntry> backward = new LinkedHashMap<>();
    private final LinkedHashMap<String, OccupancyQueueEntry> neutral = new LinkedHashMap<>();
    private long nextEnqueueSequence;

    boolean touch(
        String trainName, CorridorDirection direction, Instant now, int priority, int entryOrder) {
      if (trainName == null || trainName.isBlank() || now == null) {
        return false;
      }
      String key = normalize(trainName);
      CorridorDirection safeDirection = direction;
      if (safeDirection == null) {
        safeDirection = CorridorDirection.UNKNOWN;
      }
      LinkedHashMap<String, OccupancyQueueEntry> target = mapFor(safeDirection);
      OccupancyQueueEntry existing = null;
      boolean bucketChanged = false;
      List<LinkedHashMap<String, OccupancyQueueEntry>> directionBuckets =
          List.of(forward, backward, neutral);
      for (LinkedHashMap<String, OccupancyQueueEntry> entries : directionBuckets) {
        OccupancyQueueEntry candidate = entries.get(key);
        existing = pickOlder(existing, candidate);
        if (candidate != null && entries != target) {
          entries.remove(key);
          bucketChanged = true;
        }
      }
      int stableEntryOrder = resolveStableEntryOrder(existing, entryOrder);
      boolean schedulingChanged =
          existing == null
              || bucketChanged
              || existing.direction() != safeDirection
              || existing.priority() != priority
              || existing.entryOrder() != stableEntryOrder;
      target.put(
          key,
          new OccupancyQueueEntry(
              existing != null ? existing.trainName() : trainName,
              safeDirection,
              existing != null ? existing.firstSeen() : now,
              now,
              priority,
              stableEntryOrder,
              existing != null ? existing.enqueueSequence() : allocateEnqueueSequence()));
      return schedulingChanged;
    }

    boolean remove(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      String key = normalize(trainName);
      boolean removed = forward.remove(key) != null;
      removed |= backward.remove(key) != null;
      removed |= neutral.remove(key) != null;
      return removed;
    }

    Optional<OccupancyQueueEntry> detach(String trainName) {
      Optional<OccupancyQueueEntry> existing = entryFor(trainName);
      existing.ifPresent(unused -> remove(trainName));
      return existing;
    }

    void restore(OccupancyQueueEntry entry) {
      if (entry == null) {
        return;
      }
      remove(entry.trainName());
      String key = normalize(entry.trainName());
      mapFor(entry.direction()).put(key, entry);
      observeEnqueueSequence(entry.enqueueSequence());
    }

    boolean rename(String currentTrainName, String nextTrainName) {
      Optional<OccupancyQueueEntry> existing = detach(currentTrainName);
      if (existing.isEmpty()) {
        return false;
      }
      OccupancyQueueEntry entry = existing.get();
      restore(
          new OccupancyQueueEntry(
              nextTrainName,
              entry.direction(),
              entry.firstSeen(),
              entry.lastSeen(),
              entry.priority(),
              entry.entryOrder(),
              entry.enqueueSequence()));
      return true;
    }

    private Optional<OccupancyQueueEntry> entryFor(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return Optional.empty();
      }
      String key = normalize(trainName);
      OccupancyQueueEntry entry = forward.get(key);
      if (entry == null) {
        entry = backward.get(key);
      }
      if (entry == null) {
        entry = neutral.get(key);
      }
      return Optional.ofNullable(entry);
    }

    boolean contains(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      String key = normalize(trainName);
      return forward.containsKey(key) || backward.containsKey(key) || neutral.containsKey(key);
    }

    Optional<CorridorDirection> directionOf(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return Optional.empty();
      }
      String key = normalize(trainName);
      OccupancyQueueEntry entry = forward.get(key);
      if (entry == null) {
        entry = backward.get(key);
      }
      if (entry == null) {
        entry = neutral.get(key);
      }
      return entry == null ? Optional.empty() : Optional.ofNullable(entry.direction());
    }

    /** 获取队列中所有列车名。 */
    Set<String> allTrainNames() {
      Set<String> names = new LinkedHashSet<>();
      for (OccupancyQueueEntry entry : forward.values()) {
        names.add(entry.trainName());
      }
      for (OccupancyQueueEntry entry : backward.values()) {
        names.add(entry.trainName());
      }
      for (OccupancyQueueEntry entry : neutral.values()) {
        names.add(entry.trainName());
      }
      return names;
    }

    boolean isHeadForDirection(
        String trainName, CorridorDirection direction, Instant arbitrationTime) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      return headEntry(direction, arbitrationTime)
          .map(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName))
          .orElse(false);
    }

    boolean isHeadAny(String trainName, Instant arbitrationTime) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      return headAny(arbitrationTime)
          .map(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName))
          .orElse(false);
    }

    Optional<OccupancyQueueEntry> headEntry(CorridorDirection direction, Instant arbitrationTime) {
      LinkedHashMap<String, OccupancyQueueEntry> target = mapFor(direction);
      if (target.isEmpty()) {
        return Optional.empty();
      }
      return target.values().stream()
          .sorted((first, second) -> compareEntries(first, second, arbitrationTime))
          .findFirst();
    }

    Optional<OccupancyQueueEntry> headEntryWithCandidate(
        CorridorDirection direction,
        OccupancyQueueEntry candidate,
        int candidatePriority,
        int candidateEntryOrder,
        Instant arbitrationTime) {
      LinkedHashMap<String, OccupancyQueueEntry> target = mapFor(direction);
      OccupancyQueueEntry best = null;
      for (OccupancyQueueEntry entry : target.values()) {
        if (best == null || compareEntries(entry, best, arbitrationTime) < 0) {
          best = entry;
        }
      }
      if (candidate != null) {
        OccupancyQueueEntry normalizedCandidate =
            candidate.priority() == candidatePriority
                    && candidate.entryOrder() == candidateEntryOrder
                ? candidate
                : new OccupancyQueueEntry(
                    candidate.trainName(),
                    candidate.direction(),
                    candidate.firstSeen(),
                    candidate.lastSeen(),
                    candidatePriority,
                    candidateEntryOrder,
                    candidate.enqueueSequence());
        if (best == null || compareEntries(normalizedCandidate, best, arbitrationTime) < 0) {
          best = normalizedCandidate;
        }
      }
      return Optional.ofNullable(best);
    }

    boolean wouldBeHeadForDirection(
        String trainName, CorridorDirection direction, int priority, int entryOrder, Instant now) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      Instant time = now != null ? now : Instant.now();
      OccupancyQueueEntry candidate =
          candidateEntry(trainName, direction, time, priority, entryOrder);
      return headEntryWithCandidate(direction, candidate, priority, entryOrder, time)
          .map(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName))
          .orElse(false);
    }

    boolean wouldBeHeadAny(
        String trainName, CorridorDirection direction, int priority, int entryOrder, Instant now) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      Instant time = now != null ? now : Instant.now();
      OccupancyQueueEntry candidate =
          candidateEntry(trainName, direction, time, priority, entryOrder);
      Optional<OccupancyQueueEntry> head =
          pickEarlier(
              headEntryWithCandidate(
                  CorridorDirection.A_TO_B,
                  direction == CorridorDirection.A_TO_B ? candidate : null,
                  priority,
                  entryOrder,
                  time),
              headEntryWithCandidate(
                  CorridorDirection.B_TO_A,
                  direction == CorridorDirection.B_TO_A ? candidate : null,
                  priority,
                  entryOrder,
                  time),
              time);
      head =
          pickEarlier(
              head,
              headEntryWithCandidate(
                  CorridorDirection.UNKNOWN,
                  direction == CorridorDirection.UNKNOWN ? candidate : null,
                  priority,
                  entryOrder,
                  time),
              time);
      return head.map(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName))
          .orElse(false);
    }

    /**
     * 比较两个冲突候选的实际仲裁顺序。
     *
     * <p>priority
     * 只提供有上限的“时间优势”，首次等待时间持续推进老化；因此持续刷新且仍在等待的列车最终会排在后来到达的高优先级列车之前，避免连续高优先级流量造成永久饥饿。排序键在入队后固定，不会随
     * tick 往返翻转；相同键再按基础 priority、入口距离、首次等待时间、稳定到达序号与规范列车名确定顺序。
     */
    int compareEntries(
        OccupancyQueueEntry first, OccupancyQueueEntry second, Instant arbitrationTime) {
      long deadlineFirst = arbitrationDeadlineMillis(first);
      long deadlineSecond = arbitrationDeadlineMillis(second);
      if (deadlineFirst != deadlineSecond) {
        return Long.compare(deadlineFirst, deadlineSecond);
      }
      if (first.priority() != second.priority()) {
        return Integer.compare(second.priority(), first.priority());
      }
      if (first.entryOrder() != second.entryOrder()) {
        return Integer.compare(first.entryOrder(), second.entryOrder());
      }
      int firstSeen = first.firstSeen().compareTo(second.firstSeen());
      if (firstSeen != 0) {
        return firstSeen;
      }
      if (first.enqueueSequence() != second.enqueueSequence()) {
        return Long.compare(first.enqueueSequence(), second.enqueueSequence());
      }
      return normalize(first.trainName()).compareTo(normalize(second.trainName()));
    }

    /** 构造只读仲裁候选；已入队列车必须复用首见时间与到达序号。 */
    OccupancyQueueEntry candidateEntry(
        String trainName, CorridorDirection direction, Instant now, int priority, int entryOrder) {
      Instant safeNow = now == null ? Instant.now() : now;
      return entryFor(trainName)
          .map(
              existing ->
                  new OccupancyQueueEntry(
                      existing.trainName(),
                      direction == null ? CorridorDirection.UNKNOWN : direction,
                      existing.firstSeen(),
                      safeNow,
                      priority,
                      Math.min(existing.entryOrder(), entryOrder),
                      existing.enqueueSequence()))
          .orElseGet(
              () ->
                  new OccupancyQueueEntry(
                      trainName,
                      direction == null ? CorridorDirection.UNKNOWN : direction,
                      safeNow,
                      safeNow,
                      priority,
                      entryOrder,
                      nextEnqueueSequence()));
    }

    long nextEnqueueSequence() {
      return nextEnqueueSequence;
    }

    private long allocateEnqueueSequence() {
      long allocated = nextEnqueueSequence;
      if (nextEnqueueSequence < Long.MAX_VALUE) {
        nextEnqueueSequence++;
      }
      return allocated;
    }

    private void observeEnqueueSequence(long sequence) {
      if (sequence == Long.MAX_VALUE) {
        nextEnqueueSequence = Long.MAX_VALUE;
        return;
      }
      nextEnqueueSequence = Math.max(nextEnqueueSequence, sequence + 1L);
    }

    private long arbitrationDeadlineMillis(OccupancyQueueEntry entry) {
      if (entry == null) {
        return Long.MAX_VALUE;
      }
      long pointAdvantageMillis = Math.max(1L, QUEUE_PRIORITY_POINT_ADVANTAGE.toMillis());
      long maxAdvantageMillis = Math.max(0L, QUEUE_MAX_PRIORITY_ADVANTAGE.toMillis());
      long rawAdvantage;
      try {
        rawAdvantage = Math.multiplyExact((long) entry.priority(), pointAdvantageMillis);
      } catch (ArithmeticException ignored) {
        rawAdvantage = entry.priority() >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
      }
      long boundedAdvantage =
          Math.max(-maxAdvantageMillis, Math.min(maxAdvantageMillis, rawAdvantage));
      try {
        return Math.subtractExact(entry.firstSeen().toEpochMilli(), boundedAdvantage);
      } catch (ArithmeticException ignored) {
        return boundedAdvantage >= 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
      }
    }

    /**
     * 计算稳定的冲突入口序号。
     *
     * <p>仅在列车当前仍处于队列中时沿用更小的 entryOrder；旧条目已移除/过期时会重新采用本次值。
     */
    private int resolveStableEntryOrder(OccupancyQueueEntry existing, int entryOrder) {
      if (existing == null) {
        return entryOrder;
      }
      return Math.min(existing.entryOrder(), entryOrder);
    }

    boolean hasHigherPriorityAny(String trainName, int priority, Instant now) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      String key = normalize(trainName);
      Instant safeNow = now == null ? Instant.now() : now;
      OccupancyQueueEntry requester =
          entryFor(trainName)
              .orElseGet(
                  () ->
                      new OccupancyQueueEntry(
                          trainName,
                          CorridorDirection.UNKNOWN,
                          safeNow,
                          safeNow,
                          priority,
                          Integer.MAX_VALUE));
      for (OccupancyQueueEntry other : snapshotEntries(safeNow)) {
        if (other == null || normalize(other.trainName()).equals(key)) {
          continue;
        }
        if (compareEntries(other, requester, safeNow) < 0) {
          return true;
        }
      }
      return false;
    }

    boolean hasHigherPriorityOutside(
        String trainName, CorridorDirection direction, int priority, Instant now) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      String key = normalize(trainName);
      Instant safeNow = now == null ? Instant.now() : now;
      OccupancyQueueEntry requester =
          entryFor(trainName)
              .orElseGet(
                  () ->
                      new OccupancyQueueEntry(
                          trainName,
                          direction == null ? CorridorDirection.UNKNOWN : direction,
                          safeNow,
                          safeNow,
                          priority,
                          Integer.MAX_VALUE));
      List<LinkedHashMap<String, OccupancyQueueEntry>> targets =
          List.of(forward, backward, neutral);
      LinkedHashMap<String, OccupancyQueueEntry> same = mapFor(direction);
      for (LinkedHashMap<String, OccupancyQueueEntry> target : targets) {
        if (target == same) {
          continue;
        }
        for (Map.Entry<String, OccupancyQueueEntry> entry : target.entrySet()) {
          if (entry.getKey() == null || entry.getKey().equals(key) || entry.getValue() == null) {
            continue;
          }
          if (compareEntries(entry.getValue(), requester, safeNow) < 0) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * 是否存在指定方向以外的排队条目。
     *
     * <p>单线冲突本身只互斥对向列车；队列中全是同向列车时，不应把 conflict 队列当成额外闭塞块串行化。真正的同向追踪距离由 NODE/EDGE 硬占用负责。
     */
    boolean hasEntriesOutside(CorridorDirection direction) {
      if (direction == CorridorDirection.A_TO_B) {
        return !backward.isEmpty() || !neutral.isEmpty();
      }
      if (direction == CorridorDirection.B_TO_A) {
        return !forward.isEmpty() || !neutral.isEmpty();
      }
      return !forward.isEmpty() || !backward.isEmpty();
    }

    boolean hasEntriesOutside(CorridorDirection direction, String trainName) {
      if (direction == CorridorDirection.A_TO_B) {
        return hasAnyOtherTrain(backward, trainName) || hasAnyOtherTrain(neutral, trainName);
      }
      if (direction == CorridorDirection.B_TO_A) {
        return hasAnyOtherTrain(forward, trainName) || hasAnyOtherTrain(neutral, trainName);
      }
      return hasAnyOtherTrain(forward, trainName) || hasAnyOtherTrain(backward, trainName);
    }

    boolean hasAnyOtherTrain(String trainName) {
      return hasAnyOtherTrain(forward, trainName)
          || hasAnyOtherTrain(backward, trainName)
          || hasAnyOtherTrain(neutral, trainName);
    }

    Optional<OccupancyQueueEntry> firstOtherEntry(String trainName) {
      Optional<OccupancyQueueEntry> entry = firstOtherEntry(forward, trainName);
      if (entry.isPresent()) {
        return entry;
      }
      entry = firstOtherEntry(backward, trainName);
      if (entry.isPresent()) {
        return entry;
      }
      return firstOtherEntry(neutral, trainName);
    }

    Optional<OccupancyQueueEntry> firstOtherEntryOutside(
        CorridorDirection direction, String trainName) {
      if (direction == CorridorDirection.A_TO_B) {
        return firstOtherEntry(backward, trainName).or(() -> firstOtherEntry(neutral, trainName));
      }
      if (direction == CorridorDirection.B_TO_A) {
        return firstOtherEntry(forward, trainName).or(() -> firstOtherEntry(neutral, trainName));
      }
      return firstOtherEntry(forward, trainName).or(() -> firstOtherEntry(backward, trainName));
    }

    private boolean hasAnyOtherTrain(
        LinkedHashMap<String, OccupancyQueueEntry> entries, String trainName) {
      if (entries == null || entries.isEmpty()) {
        return false;
      }
      for (OccupancyQueueEntry entry : entries.values()) {
        if (entry != null && !TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName)) {
          return true;
        }
      }
      return false;
    }

    private Optional<OccupancyQueueEntry> firstOtherEntry(
        LinkedHashMap<String, OccupancyQueueEntry> entries, String trainName) {
      if (entries == null || entries.isEmpty()) {
        return Optional.empty();
      }
      for (OccupancyQueueEntry entry : entries.values()) {
        if (entry != null && !TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName)) {
          return Optional.of(entry);
        }
      }
      return Optional.empty();
    }

    Optional<OccupancyQueueEntry> blockingEntry(
        CorridorDirection direction, Instant arbitrationTime) {
      if (direction == CorridorDirection.UNKNOWN) {
        return headAny(arbitrationTime);
      }
      Optional<OccupancyQueueEntry> direct = headEntry(direction, arbitrationTime);
      if (direct.isPresent()) {
        return direct;
      }
      return headAny(arbitrationTime);
    }

    Optional<OccupancyQueueEntry> headAny(Instant arbitrationTime) {
      Optional<OccupancyQueueEntry> candidate =
          headEntry(CorridorDirection.A_TO_B, arbitrationTime);
      candidate =
          pickEarlier(
              candidate, headEntry(CorridorDirection.B_TO_A, arbitrationTime), arbitrationTime);
      candidate =
          pickEarlier(
              candidate, headEntry(CorridorDirection.UNKNOWN, arbitrationTime), arbitrationTime);
      return candidate;
    }

    boolean isEmpty() {
      return forward.isEmpty() && backward.isEmpty() && neutral.isEmpty();
    }

    List<OccupancyQueueEntry> snapshotEntries(Instant arbitrationTime) {
      List<OccupancyQueueEntry> entries = new ArrayList<>();
      entries.addAll(forward.values());
      entries.addAll(backward.values());
      entries.addAll(neutral.values());
      entries.sort((first, second) -> compareEntries(first, second, arbitrationTime));
      return List.copyOf(entries);
    }

    List<OccupancyQueueEntry> purgeExpired(Instant now, Duration ttl) {
      if (now == null || ttl == null || ttl.isNegative()) {
        return List.of();
      }
      List<OccupancyQueueEntry> removed = new ArrayList<>();
      purgeExpired(forward, now, ttl, removed);
      purgeExpired(backward, now, ttl, removed);
      purgeExpired(neutral, now, ttl, removed);
      return List.copyOf(removed);
    }

    private void purgeExpired(
        LinkedHashMap<String, OccupancyQueueEntry> map,
        Instant now,
        Duration ttl,
        List<OccupancyQueueEntry> removed) {
      Iterator<Map.Entry<String, OccupancyQueueEntry>> iterator = map.entrySet().iterator();
      while (iterator.hasNext()) {
        Map.Entry<String, OccupancyQueueEntry> entry = iterator.next();
        OccupancyQueueEntry value = entry.getValue();
        if (value == null) {
          iterator.remove();
          continue;
        }
        if (value.lastSeen().plus(ttl).isBefore(now)) {
          iterator.remove();
          removed.add(value);
        }
      }
    }

    private static OccupancyQueueEntry pickOlder(
        OccupancyQueueEntry current, OccupancyQueueEntry candidate) {
      if (candidate == null) {
        return current;
      }
      if (current == null) {
        return candidate;
      }
      return candidate.firstSeen().isBefore(current.firstSeen()) ? candidate : current;
    }

    private LinkedHashMap<String, OccupancyQueueEntry> mapFor(CorridorDirection direction) {
      if (direction == CorridorDirection.B_TO_A) {
        return backward;
      }
      if (direction == CorridorDirection.A_TO_B) {
        return forward;
      }
      return neutral;
    }

    private static String normalize(String trainName) {
      return TrainNameNormalizer.normalizeKey(trainName);
    }

    private Optional<OccupancyQueueEntry> pickEarlier(
        Optional<OccupancyQueueEntry> current,
        Optional<OccupancyQueueEntry> candidate,
        Instant arbitrationTime) {
      if (candidate == null || candidate.isEmpty()) {
        return current;
      }
      if (current == null || current.isEmpty()) {
        return candidate;
      }
      // 跨方向比较也要遵循 priority 优先，其次 FIFO（与 headEntry 的规则一致）。
      if (compareEntries(candidate.get(), current.get(), arbitrationTime) < 0) {
        return candidate;
      }
      return current;
    }
  }

  private record SectionDirectionMatch(
      OccupancyResource section, CorridorDirection direction, String evidence) {}

  /** 队列刷新结果：区分首次入队、既有条目语义变化与只更新 lastSeen 的心跳。 */
  private record QueueTouchResult(
      CorridorDirection direction,
      boolean stateChanged,
      boolean existingEntryChanged,
      List<String> eligibleTrainNames) {

    boolean queueHeadChanged() {
      return existingEntryChanged && !eligibleTrainNames.isEmpty();
    }
  }

  /** 同一列车刷新既有 claim 后最终保留的方向、角色与诊断原因。 */
  private record ClaimRefresh(
      Optional<CorridorDirection> direction, ClaimRole role, String reason) {}

  /** 原子折返预检期间暂存的本车授权状态。 */
  private record DetachedAuthorityState(
      List<OccupancyClaim> claims,
      Map<OccupancyResource, OccupancyQueueEntry> queues,
      Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> claimSignatures,
      Map<SwitcherClaimKey, DirectedTraversalContext.SwitcherPathSignature> queueSignatures) {}

  /**
   * 冲突区放行锁：记录被放行的列车与锁定过期时间。
   *
   * @param trainName 被放行的列车名
   * @param expiresAt 锁定过期时间
   */
  private record DeadlockReleaseLock(String trainName, Instant expiresAt) {
    boolean isExpired(Instant now) {
      return now != null && now.isAfter(expiresAt);
    }

    boolean matches(String name) {
      return TrainNameNormalizer.sameLogicalTrain(trainName, name);
    }
  }
}
