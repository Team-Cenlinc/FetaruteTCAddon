package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 占用请求上下文：描述“列车想占用哪些资源”。
 *
 * <p>占用释放由事件驱动触发，不再依赖 travelTime。
 *
 * <p>corridorDirections 用于单线走廊的方向锁判定（同向跟驰、对向互斥）。
 *
 * <p>conflictEntryOrders 用于冲突区放行与死锁解除：记录列车在 lookahead 路径中“首次进入某冲突区”的边序号（越小越接近当前列车）。
 *
 * <p>purpose 标识请求来源；缺失来源只能退化为 {@link AuthorizationPurpose#RUNTIME_MOVE}，不得退化成 {@link
 * AuthorizationPurpose#CONFLICT_CLEARING}。冲突区释放还必须携带 conflictReleaseHints，证明列车已经在同一冲突区内且目标是清空出口。
 *
 * <p>unresolvedDirectionKeys 记录“语义解析器已明确判定不可确定方向”的单线冲突 key。它与 corridorDirections
 * 中单纯的缺键<b>不是</b>同一件事： 缺键可能只是该冲突不在本次计划内，可以继续由其它证据补齐；而出现在本集合中的 key 表示证据本身矛盾或不足，任何回退推断都不得再给出方向， 必须按
 * UNKNOWN 做 fail-closed 处理。二者若不区分，换向后列车会沿回退链取回自己的旧方向 claim，从而绕过对向屏障。
 */
public record OccupancyRequest(
    String trainName,
    Optional<RouteId> routeId,
    Instant now,
    List<OccupancyResource> resources,
    Map<String, CorridorDirection> corridorDirections,
    Map<String, Integer> conflictEntryOrders,
    int priority,
    AuthorizationPurpose purpose,
    Map<String, ConflictReleaseHint> conflictReleaseHints,
    Map<OccupancyResource, ResourceIntent> resourceIntents,
    Optional<DirectedTraversalContext> directedContext,
    Set<String> unresolvedDirectionKeys) {

  public OccupancyRequest {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(resources, "resources");
    Objects.requireNonNull(corridorDirections, "corridorDirections");
    Objects.requireNonNull(conflictEntryOrders, "conflictEntryOrders");
    purpose = purpose == null ? AuthorizationPurpose.RUNTIME_MOVE : purpose;
    Objects.requireNonNull(conflictReleaseHints, "conflictReleaseHints");
    Objects.requireNonNull(resourceIntents, "resourceIntents");
    directedContext = directedContext == null ? Optional.empty() : directedContext;
    if (trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
    resources = List.copyOf(resources);
    corridorDirections = Map.copyOf(corridorDirections);
    conflictEntryOrders = Map.copyOf(conflictEntryOrders);
    conflictReleaseHints = Map.copyOf(conflictReleaseHints);
    resourceIntents = Map.copyOf(resourceIntents);
    unresolvedDirectionKeys =
        unresolvedDirectionKeys == null ? Set.of() : Set.copyOf(unresolvedDirectionKeys);
  }

  /**
   * 返回该单线冲突的方向是否已被明确判定为不可确定。
   *
   * @param conflictKey 单线冲突 key
   * @return {@code true} 表示必须按 UNKNOWN fail-closed，不得再走任何方向回退
   */
  public boolean directionExplicitlyUnresolved(String conflictKey) {
    return conflictKey != null && unresolvedDirectionKeys.contains(conflictKey);
  }

  public OccupancyRequest(
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> resources,
      Map<String, CorridorDirection> corridorDirections,
      Map<String, Integer> conflictEntryOrders,
      int priority,
      AuthorizationPurpose purpose,
      Map<String, ConflictReleaseHint> conflictReleaseHints,
      Map<OccupancyResource, ResourceIntent> resourceIntents,
      Optional<DirectedTraversalContext> directedContext) {
    this(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        conflictReleaseHints,
        resourceIntents,
        directedContext,
        Set.of());
  }

  public OccupancyRequest(
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> resources,
      Map<String, CorridorDirection> corridorDirections,
      Map<String, Integer> conflictEntryOrders,
      int priority,
      AuthorizationPurpose purpose,
      Map<String, ConflictReleaseHint> conflictReleaseHints,
      Map<OccupancyResource, ResourceIntent> resourceIntents) {
    this(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        conflictReleaseHints,
        resourceIntents,
        Optional.empty());
  }

  public OccupancyRequest(
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> resources,
      Map<String, CorridorDirection> corridorDirections,
      Map<String, Integer> conflictEntryOrders,
      int priority,
      AuthorizationPurpose purpose,
      Map<String, ConflictReleaseHint> conflictReleaseHints) {
    this(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        conflictReleaseHints,
        Map.of());
  }

  public OccupancyRequest(
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> resources,
      Map<String, CorridorDirection> corridorDirections,
      Map<String, Integer> conflictEntryOrders,
      int priority,
      AuthorizationPurpose purpose) {
    this(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        Map.of());
  }

  public OccupancyRequest(
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> resources,
      Map<String, CorridorDirection> corridorDirections,
      Map<String, Integer> conflictEntryOrders,
      int priority) {
    this(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of());
  }

  public OccupancyRequest(
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> resources,
      Map<String, CorridorDirection> corridorDirections,
      int priority) {
    this(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        Map.of(),
        priority,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of());
  }

  public OccupancyRequest(
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> resources,
      Map<String, CorridorDirection> corridorDirections,
      int priority,
      AuthorizationPurpose purpose) {
    this(trainName, routeId, now, resources, corridorDirections, Map.of(), priority, purpose);
  }

  public OccupancyRequest(
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> resources,
      Map<String, CorridorDirection> corridorDirections) {
    this(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of());
  }

  public List<OccupancyResource> resourceList() {
    return resources;
  }

  /** 返回指定资源在本请求中的用途，旧调用点默认视作前进必须资源。 */
  public ResourceIntent intentFor(OccupancyResource resource) {
    if (resource == null) {
      return ResourceIntent.MOVEMENT_REQUIRED;
    }
    return resourceIntents.getOrDefault(resource, ResourceIntent.MOVEMENT_REQUIRED);
  }

  /** 返回指定资源 acquire 后应写入的 claim 角色。 */
  public ClaimRole claimRoleFor(OccupancyResource resource) {
    return ClaimRole.fromIntent(intentFor(resource));
  }

  /** 判断请求中是否包含任何前进必须资源。 */
  public boolean hasMovementRequiredResources() {
    for (OccupancyResource resource : resources) {
      if (intentFor(resource).hardAuthority()) {
        return true;
      }
    }
    return false;
  }

  /** 返回本请求携带的规范化行车计划快照。 */
  public Optional<MovementPlanSnapshot> movementPlanSnapshot() {
    return MovementPlanSnapshot.fromRequest(this);
  }

  /** 返回同一授权窗口但使用新列车身份的请求。 */
  public OccupancyRequest withTrainName(String nextTrainName) {
    return new OccupancyRequest(
        nextTrainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        conflictReleaseHints,
        resourceIntents,
        directedContext.map(context -> context.withTrainKey(nextTrainName)));
  }

  /** 返回同一资源集合但替换请求来源后的请求。 */
  public OccupancyRequest withPurpose(AuthorizationPurpose nextPurpose) {
    return new OccupancyRequest(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        nextPurpose,
        conflictReleaseHints,
        resourceIntents,
        directedContext.map(context -> context.withSource(nextPurpose.name())));
  }

  /** 返回同一资源集合但附加冲突清空证据后的请求。 */
  public OccupancyRequest withConflictReleaseHints(
      AuthorizationPurpose nextPurpose, Map<String, ConflictReleaseHint> hints) {
    return new OccupancyRequest(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        nextPurpose,
        hints == null ? Map.of() : hints,
        resourceIntents,
        directedContext.map(context -> context.withSource(nextPurpose.name())));
  }

  /** 返回同一资源集合但仅附加冲突清空证据，不改变请求来源或发布语义。 */
  public OccupancyRequest withConflictClearingEvidence(Map<String, ConflictReleaseHint> hints) {
    return new OccupancyRequest(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        hints == null ? Map.of() : hints,
        resourceIntents,
        directedContext);
  }

  /** 返回同一请求但替换资源意图映射。 */
  public OccupancyRequest withResourceIntents(Map<OccupancyResource, ResourceIntent> intents) {
    return new OccupancyRequest(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        conflictReleaseHints,
        intents == null ? Map.of() : intents,
        directedContext);
  }

  /** 返回同一行车计划与资源窗口，但使用新的刷新时间和调度优先级。 */
  public OccupancyRequest withSchedulingMetadata(Instant requestTime, int nextPriority) {
    return new OccupancyRequest(
        trainName,
        routeId,
        Objects.requireNonNull(requestTime, "requestTime"),
        resources,
        corridorDirections,
        conflictEntryOrders,
        nextPriority,
        purpose,
        conflictReleaseHints,
        resourceIntents,
        directedContext);
  }

  /** 返回同一资源窗口的 advisory lookahead 只读请求。 */
  public OccupancyRequest asLookaheadPreview() {
    java.util.Map<OccupancyResource, ResourceIntent> intents = new java.util.LinkedHashMap<>();
    for (OccupancyResource resource : resources) {
      if (resource != null) {
        intents.put(resource, ResourceIntent.LOOKAHEAD_PREVIEW);
      }
    }
    return withResourceIntents(intents).withDirectedSource("LOOKAHEAD_PREVIEW");
  }

  /**
   * 返回移除全部 advisory preview 资源后的可写请求。
   *
   * <p>LOOKAHEAD_PREVIEW 是只读风险查询，不能经由通用 acquire API 落入 claims、队列或版本状态。保留 directed context
   * 仅供剩余硬资源的方向与路径证明使用；所有按资源键索引的元数据同步收窄。
   */
  public OccupancyRequest withoutLookaheadPreviewResources() {
    List<OccupancyResource> writableResources =
        resources.stream()
            .filter(Objects::nonNull)
            .filter(resource -> intentFor(resource) != ResourceIntent.LOOKAHEAD_PREVIEW)
            .toList();
    if (writableResources.size() == resources.size()) {
      return this;
    }
    java.util.Set<String> writableKeys = new LinkedHashSet<>();
    Map<OccupancyResource, ResourceIntent> writableIntents = new LinkedHashMap<>();
    for (OccupancyResource resource : writableResources) {
      writableKeys.add(resource.key());
      ResourceIntent intent = resourceIntents.get(resource);
      if (intent != null) {
        writableIntents.put(resource, intent);
      }
    }
    Map<String, CorridorDirection> writableDirections = new LinkedHashMap<>();
    corridorDirections.forEach(
        (key, direction) -> {
          if (writableKeys.contains(key)) {
            writableDirections.put(key, direction);
          }
        });
    Map<String, Integer> writableEntryOrders = new LinkedHashMap<>();
    conflictEntryOrders.forEach(
        (key, order) -> {
          if (writableKeys.contains(key)) {
            writableEntryOrders.put(key, order);
          }
        });
    Map<String, ConflictReleaseHint> writableReleaseHints = new LinkedHashMap<>();
    conflictReleaseHints.forEach(
        (key, hint) -> {
          if (writableKeys.contains(key)) {
            writableReleaseHints.put(key, hint);
          }
        });
    return new OccupancyRequest(
        trainName,
        routeId,
        now,
        writableResources,
        writableDirections,
        writableEntryOrders,
        priority,
        purpose,
        writableReleaseHints,
        writableIntents,
        directedContext);
  }

  /** 返回同一请求但替换有向 traversal 上下文。 */
  public OccupancyRequest withDirectedContext(Optional<DirectedTraversalContext> context) {
    return new OccupancyRequest(
        trainName,
        routeId,
        now,
        resources,
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        conflictReleaseHints,
        resourceIntents,
        context == null ? Optional.empty() : context);
  }

  /** 返回同一请求但替换有向 traversal 上下文来源标签。 */
  public OccupancyRequest withDirectedSource(String source) {
    if (directedContext.isEmpty()) {
      return this;
    }
    return withDirectedContext(Optional.of(directedContext.get().withSource(source)));
  }

  /** 返回同一请求但替换有向 traversal 上下文中的占用版本。 */
  public OccupancyRequest withDirectedOccupancyVersion(long occupancyVersion) {
    if (directedContext.isEmpty()) {
      return this;
    }
    return withDirectedContext(
        Optional.of(directedContext.get().withOccupancyVersion(occupancyVersion)));
  }

  /** 返回同一请求但替换有向 traversal 上下文中的进度版本。 */
  public OccupancyRequest withDirectedProgressVersion(long progressVersion) {
    if (directedContext.isEmpty()) {
      return this;
    }
    return withDirectedContext(
        Optional.of(directedContext.get().withProgressVersion(progressVersion)));
  }
}
