package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    Optional<DirectedTraversalContext> directedContext) {

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
