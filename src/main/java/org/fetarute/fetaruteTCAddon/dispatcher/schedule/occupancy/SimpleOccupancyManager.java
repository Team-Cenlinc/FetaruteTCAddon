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

/**
 * 基于内存 Map 的占用管理器，适合作为“最小可用版本”。
 *
 * <p>占用释放由事件驱动触发：释放后即认为可重新判定，不依赖 releaseAt；headway 仅作为配置保留。
 *
 * <p>单线走廊冲突支持方向锁：同向允许多车跟驰，对向互斥。
 *
 * <p>道岔/单线冲突使用 FIFO Gate Queue 保障进入顺序。
 */
public final class SimpleOccupancyManager implements OccupancyManager, OccupancyQueueSupport {

  private static final Duration QUEUE_ENTRY_TTL = Duration.ofSeconds(30);

  private final HeadwayRule headwayRule;
  private final SignalAspectPolicy signalPolicy;
  private final Map<OccupancyResource, List<OccupancyClaim>> claims = new LinkedHashMap<>();
  private final Map<OccupancyResource, ConflictQueue> queues = new LinkedHashMap<>();

  /**
   * 构建占用管理器。
   *
   * @param headwayRule 追踪间隔策略
   * @param signalPolicy 信号优先级与放行策略
   */
  public SimpleOccupancyManager(HeadwayRule headwayRule, SignalAspectPolicy signalPolicy) {
    this.headwayRule = Objects.requireNonNull(headwayRule, "headwayRule");
    this.signalPolicy = signalPolicy != null ? signalPolicy : SignalAspectPolicy.defaultPolicy();
  }

  @Override
  /**
   * 预判是否允许进入指定资源集合（不写入状态）。
   *
   * <p>用于运行时“尝试放行”的决策预演。
   */
  public synchronized OccupancyDecision canEnter(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    Instant now = request.now();
    purgeExpiredQueueEntries(now);
    List<OccupancyClaim> blockers = new ArrayList<>();
    Set<OccupancyResource> blockedResources = new LinkedHashSet<>();
    boolean blockedByNonQueueable = false;
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
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
        if (claim.trainName().equalsIgnoreCase(request.trainName())) {
          continue;
        }
        if (isSingleCorridorConflict(resource)
            && isSameCorridorDirection(request, resource, claim)) {
          continue;
        }
        blockers.add(claim);
        blockedResources.add(resource);
        if (!isQueueableConflict(resource)) {
          blockedByNonQueueable = true;
        }
      }
    }
    if (!blockers.isEmpty()) {
      if (!blockedByNonQueueable) {
        enqueueWaiting(request, blockedResources, now);
      }
      return new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers));
    }

    boolean queueBlocked = false;
    for (OccupancyResource resource : request.resourceList()) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      if (findClaim(claims.get(resource), request.trainName()) != null) {
        continue;
      }
      CorridorDirection direction = queueDirectionFor(request, resource);
      ConflictQueue queue = queues.computeIfAbsent(resource, unused -> new ConflictQueue());
      queue.touch(request.trainName(), direction, now, request.priority());
      if (!isQueueAllowed(request.trainName(), resource, direction, queue)) {
        queueBlocked = true;
        queue
            .blockingEntry(direction)
            .map(entry -> createQueueBlocker(resource, entry))
            .ifPresent(blockers::add);
      }
    }
    if (queueBlocked) {
      return new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers));
    }
    SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
    return new OccupancyDecision(true, now, signal, List.copyOf(blockers));
  }

  @Override
  /**
   * 获取占用：将申请列车写入资源占用与队列状态。
   *
   * <p>若不可进入则返回拒绝决策，并写入排队快照供诊断使用。
   */
  public synchronized OccupancyDecision acquire(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    OccupancyDecision decision = canEnter(request);
    if (!decision.allowed()) {
      return decision;
    }
    Instant now = request.now();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      Duration headway = headwayRule.headwayFor(request.routeId(), resource);
      List<OccupancyClaim> existing = claims.computeIfAbsent(resource, unused -> new ArrayList<>());
      OccupancyClaim current = findClaim(existing, request.trainName());
      Optional<CorridorDirection> direction = resolveCorridorDirection(request, resource);
      if (current != null) {
        Duration nextHeadway =
            current.headway().compareTo(headway) >= 0 ? current.headway() : headway;
        existing.remove(current);
        existing.add(
            new OccupancyClaim(
                resource,
                current.trainName(),
                request.routeId(),
                current.acquiredAt(),
                nextHeadway,
                direction));
        continue;
      }
      existing.add(
          new OccupancyClaim(
              resource, request.trainName(), request.routeId(), now, headway, direction));
    }
    removeFromQueuesForResources(request.trainName(), request.resourceList());
    return decision;
  }

  @Override
  /**
   * 查询某资源当前占用（只返回首个占用者）。
   *
   * <p>用于诊断，不保证公平队列顺序。
   */
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

  @Override
  /**
   * 获取全部占用快照。
   *
   * <p>用于诊断，不建议高频调用。
   */
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
  /**
   * 获取排队快照。
   *
   * <p>用于诊断单线走廊方向与排队情况。
   */
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
              resource, activeDirection, activeClaims, queue.snapshotEntries()));
    }
    return List.copyOf(snapshots);
  }

  @Override
  /**
   * 按列车名释放全部占用资源。
   *
   * @return 实际释放的占用数量
   */
  public synchronized int releaseByTrain(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return 0;
    }
    int removed = 0;
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
        if (claim != null && claim.trainName().equalsIgnoreCase(trainName)) {
          claimIterator.remove();
          removed++;
        }
      }
      if (list.isEmpty()) {
        iterator.remove();
      }
    }
    removeFromQueuesForTrain(trainName);
    return removed;
  }

  @Override
  /**
   * 释放指定资源上的单个列车占用。
   *
   * @return 是否存在并成功移除
   */
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
      boolean removed =
          list.removeIf(claim -> claim != null && claim.trainName().equalsIgnoreCase(expected));
      if (list.isEmpty()) {
        claims.remove(resource);
      }
      removeFromQueuesForResources(expected, List.of(resource));
      return removed;
    }
    claims.remove(resource);
    return true;
  }

  // 事件反射式释放不需要“基于时间”的清理。

  private boolean isSameCorridorDirection(
      OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim) {
    Optional<CorridorDirection> requestDirection = resolveCorridorDirection(request, resource);
    Optional<CorridorDirection> claimDirection = claim.corridorDirection();
    if (requestDirection.isEmpty() || claimDirection.isEmpty()) {
      return false;
    }
    return requestDirection.get() == claimDirection.get();
  }

  private Optional<CorridorDirection> resolveCorridorDirection(
      OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return Optional.empty();
    }
    if (!isSingleCorridorConflict(resource)) {
      return Optional.empty();
    }
    CorridorDirection direction = request.corridorDirections().get(resource.key());
    if (direction == null || direction == CorridorDirection.UNKNOWN) {
      return Optional.empty();
    }
    return Optional.of(direction);
  }

  private boolean isSingleCorridorConflict(OccupancyResource resource) {
    return resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("single:")
        && !resource.key().contains(":cycle:");
  }

  private boolean isQueueableConflict(OccupancyResource resource) {
    if (resource == null || resource.kind() != ResourceKind.CONFLICT) {
      return false;
    }
    return resource.key().startsWith("single:") || resource.key().startsWith("switcher:");
  }

  private boolean isQueueAllowed(
      String trainName,
      OccupancyResource resource,
      CorridorDirection direction,
      ConflictQueue queue) {
    if (queue == null || queue.isEmpty()) {
      return true;
    }
    if (!resource.key().startsWith("single:") || resource.key().contains(":cycle:")) {
      return queue.isHeadAny(trainName);
    }
    Optional<CorridorDirection> activeDirection = activeDirectionFor(resource);
    if (activeDirection.isEmpty()) {
      return queue.isHeadAny(trainName);
    }
    CorridorDirection active = activeDirection.get();
    if (direction == CorridorDirection.UNKNOWN || direction != active) {
      return false;
    }
    return queue.isHeadForDirection(trainName, direction);
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
    if (resource == null) {
      return CorridorDirection.UNKNOWN;
    }
    if (!resource.key().startsWith("single:") || resource.key().contains(":cycle:")) {
      return CorridorDirection.UNKNOWN;
    }
    CorridorDirection direction = request.corridorDirections().get(resource.key());
    if (direction == null) {
      return CorridorDirection.UNKNOWN;
    }
    return direction;
  }

  private void enqueueWaiting(
      OccupancyRequest request, Set<OccupancyResource> blockedResources, Instant now) {
    if (blockedResources == null || blockedResources.isEmpty()) {
      return;
    }
    for (OccupancyResource resource : blockedResources) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      CorridorDirection direction = queueDirectionFor(request, resource);
      ConflictQueue queue = queues.computeIfAbsent(resource, unused -> new ConflictQueue());
      queue.touch(request.trainName(), direction, now, request.priority());
    }
  }

  private void purgeExpiredQueueEntries(Instant now) {
    if (now == null || queues.isEmpty()) {
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
      queue.purgeExpired(now, QUEUE_ENTRY_TTL);
      if (queue.isEmpty()) {
        iterator.remove();
      }
    }
  }

  private void removeFromQueuesForResources(String trainName, List<OccupancyResource> resources) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    if (resources == null || resources.isEmpty()) {
      removeFromQueuesForTrain(trainName);
      return;
    }
    for (OccupancyResource resource : resources) {
      ConflictQueue queue = queues.get(resource);
      if (queue == null) {
        continue;
      }
      queue.remove(trainName);
      if (queue.isEmpty()) {
        queues.remove(resource);
      }
    }
  }

  private void removeFromQueuesForTrain(String trainName) {
    if (trainName == null || trainName.isBlank()) {
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
      queue.remove(trainName);
      if (queue.isEmpty()) {
        iterator.remove();
      }
    }
  }

  private OccupancyClaim createQueueBlocker(OccupancyResource resource, OccupancyQueueEntry entry) {
    Optional<CorridorDirection> direction =
        entry.direction() == CorridorDirection.UNKNOWN
            ? Optional.empty()
            : Optional.of(entry.direction());
    return new OccupancyClaim(
        resource, entry.trainName(), Optional.empty(), entry.firstSeen(), Duration.ZERO, direction);
  }

  private OccupancyClaim findClaim(List<OccupancyClaim> list, String trainName) {
    if (list == null || trainName == null) {
      return null;
    }
    for (OccupancyClaim claim : list) {
      if (claim != null && claim.trainName().equalsIgnoreCase(trainName)) {
        return claim;
      }
    }
    return null;
  }

  private static final class ConflictQueue {

    private final LinkedHashMap<String, OccupancyQueueEntry> forward = new LinkedHashMap<>();
    private final LinkedHashMap<String, OccupancyQueueEntry> backward = new LinkedHashMap<>();
    private final LinkedHashMap<String, OccupancyQueueEntry> neutral = new LinkedHashMap<>();
    private final Map<String, Integer> priorities = new java.util.HashMap<>();

    void touch(String trainName, CorridorDirection direction, Instant now, int priority) {
      if (trainName == null || trainName.isBlank() || now == null) {
        return;
      }
      String key = normalize(trainName);
      priorities.put(key, priority);
      LinkedHashMap<String, OccupancyQueueEntry> target = mapFor(direction);
      OccupancyQueueEntry existing = target.get(key);
      if (existing == null) {
        target.put(key, new OccupancyQueueEntry(trainName, direction, now, now));
        return;
      }
      target.put(
          key, new OccupancyQueueEntry(existing.trainName(), direction, existing.firstSeen(), now));
    }

    void remove(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return;
      }
      String key = normalize(trainName);
      forward.remove(key);
      backward.remove(key);
      neutral.remove(key);
      priorities.remove(key);
    }

    boolean isHeadForDirection(String trainName, CorridorDirection direction) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      return headEntry(direction)
          .map(entry -> entry.trainName().equalsIgnoreCase(trainName))
          .orElse(false);
    }

    boolean isHeadAny(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      return headAny().map(entry -> entry.trainName().equalsIgnoreCase(trainName)).orElse(false);
    }

    Optional<OccupancyQueueEntry> headEntry(CorridorDirection direction) {
      LinkedHashMap<String, OccupancyQueueEntry> target = mapFor(direction);
      if (target.isEmpty()) {
        return Optional.empty();
      }
      // 排序规则：priority 倒序，其次 firstSeen 正序
      return target.values().stream().sorted(this::compareEntries).findFirst();
    }

    private int compareEntries(OccupancyQueueEntry a, OccupancyQueueEntry b) {
      int pA = priorities.getOrDefault(normalize(a.trainName()), 0);
      int pB = priorities.getOrDefault(normalize(b.trainName()), 0);
      if (pA != pB) {
        return Integer.compare(pB, pA); // 优先级更高者优先
      }
      return a.firstSeen().compareTo(b.firstSeen()); // 首见更早者优先（FIFO）
    }

    Optional<OccupancyQueueEntry> blockingEntry(CorridorDirection direction) {
      if (direction == CorridorDirection.UNKNOWN) {
        return headAny();
      }
      Optional<OccupancyQueueEntry> direct = headEntry(direction);
      if (direct.isPresent()) {
        return direct;
      }
      return headAny();
    }

    Optional<OccupancyQueueEntry> headAny() {
      Optional<OccupancyQueueEntry> candidate = headEntry(CorridorDirection.A_TO_B);
      candidate = pickEarlier(candidate, headEntry(CorridorDirection.B_TO_A));
      candidate = pickEarlier(candidate, headEntry(CorridorDirection.UNKNOWN));
      return candidate;
    }

    boolean isEmpty() {
      return forward.isEmpty() && backward.isEmpty() && neutral.isEmpty();
    }

    List<OccupancyQueueEntry> snapshotEntries() {
      List<OccupancyQueueEntry> entries = new ArrayList<>();
      entries.addAll(forward.values());
      entries.addAll(backward.values());
      entries.addAll(neutral.values());
      entries.sort(
          (left, right) -> {
            int compare = left.firstSeen().compareTo(right.firstSeen());
            if (compare != 0) {
              return compare;
            }
            return left.trainName().compareToIgnoreCase(right.trainName());
          });
      return List.copyOf(entries);
    }

    void purgeExpired(Instant now, Duration ttl) {
      if (now == null || ttl == null || ttl.isNegative()) {
        return;
      }
      purgeExpired(forward, now, ttl);
      purgeExpired(backward, now, ttl);
      purgeExpired(neutral, now, ttl);
    }

    private void purgeExpired(
        LinkedHashMap<String, OccupancyQueueEntry> map, Instant now, Duration ttl) {
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
        }
      }
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
      return trainName.trim().toLowerCase(Locale.ROOT);
    }

    private Optional<OccupancyQueueEntry> pickEarlier(
        Optional<OccupancyQueueEntry> current, Optional<OccupancyQueueEntry> candidate) {
      if (candidate == null || candidate.isEmpty()) {
        return current;
      }
      if (current == null || current.isEmpty()) {
        return candidate;
      }
      if (candidate.get().firstSeen().isBefore(current.get().firstSeen())) {
        return candidate;
      }
      return current;
    }
  }
}
