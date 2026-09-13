package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 统一解析运行时调度优先级。
 *
 * <p>该组件是 periodic runtime dispatch 与 event-driven signal re-evaluation 的共享入口。它只负责把人工 {@code
 * FTA_PRIORITY}、route 身份、route operation type 与 {@link DispatchPriorityPolicy} 组合为最终数值；不改变
 * Occupancy Queue 排序、不绕过 hard blockers，也不授予额外 movement authority。
 */
public final class DispatchPriorityResolver {

  private static final String TAG_MANUAL_PRIORITY = "FTA_PRIORITY";
  private static final String TAG_OPERATOR_LEGACY = "FTA_OPERATOR";
  private static final String TAG_LINE_LEGACY = "FTA_LINE";
  private static final String TAG_ROUTE_LEGACY = "FTA_ROUTE";

  private final StorageManager storageManager;
  private final Function<String, Optional<RouteProgressRegistry.RouteProgressEntry>> progressLookup;
  private final Consumer<String> debugLogger;
  private final ConcurrentMap<String, Integer> operatorPriorityCache = new ConcurrentHashMap<>();

  /**
   * 每列车上一次解析出的优先级签名。
   *
   * <p>优先级每 tick 都会重解析，但只有<b>结果变化</b>才是新证据。生产端在此去重后，该 trace 才能进入诊断门的免预算白名单： 实服日志里它此前只留下 12
   * 条，涉事列车一条都没有，导致队列仲裁无法归因。
   */
  private final ConcurrentMap<String, String> resolvedPrioritySignatures =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<UUID, RouteLookup> routeLookupByUuid = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, RouteLookup> routeLookupByCode = new ConcurrentHashMap<>();

  public DispatchPriorityResolver(
      StorageManager storageManager,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      Consumer<String> debugLogger) {
    this.storageManager = storageManager;
    this.progressLookup =
        progressRegistry == null ? trainName -> Optional.empty() : progressRegistry::get;
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /** 解析指定列车当前请求应携带的 priority。 */
  public DispatchPriorityResolution resolve(
      String traceContext, String trainName, TrainProperties properties, RouteDefinition route) {
    return resolve(traceContext, trainName, properties, route, progressEntry(trainName));
  }

  /** 解析指定列车当前请求应携带的 priority，并显式复用调用方已经读取的 progress entry。 */
  public DispatchPriorityResolution resolve(
      String traceContext,
      String trainName,
      TrainProperties properties,
      RouteDefinition route,
      Optional<RouteProgressRegistry.RouteProgressEntry> progressEntry) {
    Optional<Integer> manualPriority = TrainTagHelper.readIntTag(properties, TAG_MANUAL_PRIORITY);
    if (manualPriority.isPresent()) {
      int priority = manualPriority.get();
      DispatchPriorityResolution resolution =
          new DispatchPriorityResolution(
              priority,
              DispatchPrioritySource.MANUAL_FTA_PRIORITY,
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              "manual",
              false,
              true,
              priority,
              0);
      traceResolved(traceContext, trainName, resolution);
      return resolution;
    }

    RouteIdentity identity = resolveRouteIdentity(properties, route, progressEntry);
    Optional<RouteLookup> lookupOpt = resolveRouteLookup(identity);
    Optional<RouteOperationType> operationType = lookupOpt.map(RouteLookup::operationType);
    int basePriority = resolveBasePriority(identity, lookupOpt);
    if (operationType.isEmpty()) {
      DispatchPriorityResolution resolution =
          new DispatchPriorityResolution(
              basePriority,
              identity.source(),
              identity.routeUuid(),
              identity.routeCodeText(),
              Optional.empty(),
              identity.routeKnown() ? "operation_type_missing" : "route_identity_missing",
              false,
              false,
              basePriority,
              0);
      traceResolved(traceContext, trainName, resolution);
      return resolution;
    }

    boolean depotExitContext = isDepotExitPriorityContext(properties, route);
    int policyAdjustment =
        DispatchPriorityPolicy.operationOffset(operationType.get(), depotExitContext);
    int priority =
        DispatchPriorityPolicy.runtimePriority(basePriority, operationType.get(), depotExitContext);
    DispatchPriorityResolution resolution =
        new DispatchPriorityResolution(
            priority,
            identity.source(),
            lookupOpt.map(RouteLookup::routeUuid).or(identity::routeUuid),
            lookupOpt.map(RouteLookup::routeCodeText).or(identity::routeCodeText),
            operationType,
            "-",
            depotExitContext,
            false,
            basePriority,
            policyAdjustment);
    traceResolved(traceContext, trainName, resolution);
    return resolution;
  }

  /** 按 UUID 查询 route operation type，供恢复/健康监控链路复用同一缓存。 */
  public Optional<RouteOperationType> resolveOperationType(UUID routeUuid) {
    if (routeUuid == null) {
      return Optional.empty();
    }
    return loadRouteByUuid(routeUuid).map(RouteLookup::operationType);
  }

  private Optional<RouteProgressRegistry.RouteProgressEntry> progressEntry(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    return progressLookup.apply(trainName);
  }

  private RouteIdentity resolveRouteIdentity(
      TrainProperties properties,
      RouteDefinition route,
      Optional<RouteProgressRegistry.RouteProgressEntry> progressEntryOpt) {
    if (progressEntryOpt != null && progressEntryOpt.isPresent()) {
      RouteProgressRegistry.RouteProgressEntry entry = progressEntryOpt.get();
      if (entry.routeUuid() != null) {
        return new RouteIdentity(
            DispatchPrioritySource.ROUTE_PROGRESS_UUID,
            Optional.of(entry.routeUuid()),
            routeCodeFromRouteId(entry.routeId()));
      }
    }
    Optional<UUID> routeUuid = readRouteUuid(properties);
    if (routeUuid.isPresent()) {
      return new RouteIdentity(
          DispatchPrioritySource.FTA_ROUTE_ID, routeUuid, routeCodeFromRoute(route));
    }
    Optional<RouteCode> routeDefinitionCode = routeCodeFromDefinition(route);
    if (routeDefinitionCode.isPresent()) {
      return new RouteIdentity(
          DispatchPrioritySource.ROUTE_DEFINITION_ID,
          Optional.empty(),
          routeDefinitionCode.map(RouteCode::asText));
    }
    Optional<RouteCode> tagCode = routeCodeFromTags(properties);
    if (tagCode.isPresent()) {
      return new RouteIdentity(
          DispatchPrioritySource.ROUTE_CODE_TAGS, Optional.empty(), tagCode.map(RouteCode::asText));
    }
    return RouteIdentity.unknown();
  }

  private Optional<RouteLookup> resolveRouteLookup(RouteIdentity identity) {
    if (identity == null || !identity.routeKnown()) {
      return Optional.empty();
    }
    Optional<RouteLookup> byUuid = identity.routeUuid().flatMap(this::loadRouteByUuid);
    if (byUuid.isPresent()) {
      return byUuid;
    }
    return identity.routeCode().flatMap(this::loadRouteByCode);
  }

  private int resolveBasePriority(RouteIdentity identity, Optional<RouteLookup> lookupOpt) {
    if (lookupOpt != null && lookupOpt.isPresent()) {
      return lookupOpt.get().operatorPriority();
    }
    if (identity == null) {
      return 0;
    }
    return identity
        .routeCode()
        .map(RouteCode::operator)
        .flatMap(this::loadOperatorPriority)
        .orElse(0);
  }

  private Optional<RouteLookup> loadRouteByUuid(UUID routeUuid) {
    if (routeUuid == null) {
      return Optional.empty();
    }
    RouteLookup cached = routeLookupByUuid.get(routeUuid);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<StorageProvider> providerOpt = readyProvider();
    if (providerOpt.isEmpty()) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    Optional<RouteLookup> loaded =
        provider
            .routes()
            .findById(routeUuid)
            .flatMap(route -> lookupRouteWithLineAndOperator(provider, route));
    loaded.ifPresent(lookup -> routeLookupByUuid.putIfAbsent(routeUuid, lookup));
    return loaded;
  }

  private Optional<RouteLookup> loadRouteByCode(RouteCode routeCode) {
    if (routeCode == null) {
      return Optional.empty();
    }
    String key = routeCode.cacheKey();
    RouteLookup cached = routeLookupByCode.get(key);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<StorageProvider> providerOpt = readyProvider();
    if (providerOpt.isEmpty()) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    Optional<RouteLookup> loaded = findRouteByCode(provider, routeCode);
    loaded.ifPresent(
        lookup -> {
          routeLookupByCode.putIfAbsent(key, lookup);
          routeLookupByUuid.putIfAbsent(lookup.routeUuid(), lookup);
        });
    return loaded;
  }

  private Optional<RouteLookup> findRouteByCode(StorageProvider provider, RouteCode routeCode) {
    if (provider == null || routeCode == null) {
      return Optional.empty();
    }
    for (Company company : provider.companies().listAll()) {
      if (company == null) {
        continue;
      }
      Optional<Operator> operatorOpt =
          provider.operators().findByCompanyAndCode(company.id(), routeCode.operator());
      if (operatorOpt.isEmpty()) {
        continue;
      }
      Operator operator = operatorOpt.get();
      Optional<Line> lineOpt =
          provider.lines().findByOperatorAndCode(operator.id(), routeCode.line());
      if (lineOpt.isEmpty()) {
        return Optional.empty();
      }
      Line line = lineOpt.get();
      return provider
          .routes()
          .findByLineAndCode(line.id(), routeCode.route())
          .flatMap(route -> lookupRoute(operator, line, route));
    }
    return Optional.empty();
  }

  private Optional<RouteLookup> lookupRouteWithLineAndOperator(
      StorageProvider provider, Route route) {
    if (provider == null || route == null) {
      return Optional.empty();
    }
    Optional<Line> lineOpt = provider.lines().findById(route.lineId());
    if (lineOpt.isEmpty()) {
      return Optional.empty();
    }
    Line line = lineOpt.get();
    Optional<Operator> operatorOpt = provider.operators().findById(line.operatorId());
    if (operatorOpt.isEmpty()) {
      return Optional.empty();
    }
    return lookupRoute(operatorOpt.get(), line, route);
  }

  private Optional<RouteLookup> lookupRoute(Operator operator, Line line, Route route) {
    if (operator == null || line == null || route == null) {
      return Optional.empty();
    }
    RouteCode routeCode = new RouteCode(operator.code(), line.code(), route.code());
    return Optional.of(
        new RouteLookup(
            route.id(), routeCode, route.operationType(), operator.priority(), routeCode.asText()));
  }

  private Optional<Integer> loadOperatorPriority(String operatorCode) {
    String operator = normalize(operatorCode);
    if (operator == null) {
      return Optional.empty();
    }
    Integer cached = operatorPriorityCache.get(operator.toLowerCase(Locale.ROOT));
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<StorageProvider> providerOpt = readyProvider();
    if (providerOpt.isEmpty()) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    for (Company company : provider.companies().listAll()) {
      if (company == null) {
        continue;
      }
      Optional<Operator> operatorOpt =
          provider.operators().findByCompanyAndCode(company.id(), operator);
      if (operatorOpt.isPresent()) {
        int priority = operatorOpt.get().priority();
        operatorPriorityCache.putIfAbsent(operator.toLowerCase(Locale.ROOT), priority);
        return Optional.of(priority);
      }
    }
    return Optional.empty();
  }

  private Optional<StorageProvider> readyProvider() {
    if (storageManager == null || !storageManager.isReady()) {
      return Optional.empty();
    }
    return storageManager.provider();
  }

  private static Optional<UUID> readRouteUuid(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID)
        .flatMap(DispatchPriorityResolver::parseUuid);
  }

  private static Optional<UUID> parseUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  private static Optional<String> routeCodeFromRoute(RouteDefinition route) {
    return routeCodeFromDefinition(route).map(RouteCode::asText);
  }

  private static Optional<String> routeCodeFromRouteId(RouteId routeId) {
    if (routeId == null) {
      return Optional.empty();
    }
    return routeCodeFromText(routeId.value()).map(RouteCode::asText);
  }

  private static Optional<RouteCode> routeCodeFromDefinition(RouteDefinition route) {
    if (route == null) {
      return Optional.empty();
    }
    Optional<RouteMetadata> metadata = route.metadata();
    if (metadata.isPresent()) {
      RouteMetadata meta = metadata.get();
      Optional<RouteCode> code = RouteCode.of(meta.operator(), meta.lineId(), meta.serviceId());
      if (code.isPresent()) {
        return code;
      }
    }
    if (route.id() == null) {
      return Optional.empty();
    }
    return routeCodeFromText(route.id().value());
  }

  private static Optional<RouteCode> routeCodeFromTags(TrainProperties properties) {
    Optional<String> operator =
        readFirstTag(properties, RouteProgressRegistry.TAG_OPERATOR_CODE, TAG_OPERATOR_LEGACY);
    Optional<String> line =
        readFirstTag(properties, RouteProgressRegistry.TAG_LINE_CODE, TAG_LINE_LEGACY);
    Optional<String> route =
        readFirstTag(properties, RouteProgressRegistry.TAG_ROUTE_CODE, TAG_ROUTE_LEGACY);
    if (operator.isEmpty() || line.isEmpty() || route.isEmpty()) {
      return Optional.empty();
    }
    return RouteCode.of(operator.get(), line.get(), route.get());
  }

  private static Optional<RouteCode> routeCodeFromText(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String[] parts = raw.trim().split(":");
    if (parts.length < 3) {
      return Optional.empty();
    }
    return RouteCode.of(parts[0], parts[1], parts[2]);
  }

  private static Optional<String> readFirstTag(
      TrainProperties properties, String primaryKey, String fallbackKey) {
    Optional<String> primary = TrainTagHelper.readTagValue(properties, primaryKey);
    return primary.isPresent() ? primary : TrainTagHelper.readTagValue(properties, fallbackKey);
  }

  private static boolean isDepotExitPriorityContext(
      TrainProperties properties, RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return false;
    }
    int currentIndex =
        TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX).orElse(0);
    int boundedIndex = Math.max(0, Math.min(currentIndex, route.waypoints().size() - 1));
    NodeId current = route.waypoints().get(boundedIndex);
    NodeId next =
        boundedIndex + 1 < route.waypoints().size()
            ? route.waypoints().get(boundedIndex + 1)
            : null;
    return nodeLooksLikeDepot(current) || nodeLooksLikeDepot(next);
  }

  private static boolean nodeLooksLikeDepot(NodeId nodeId) {
    return nodeId != null && nodeId.value().toUpperCase(Locale.ROOT).contains(":D:");
  }

  private void traceResolved(
      String traceContext, String trainName, DispatchPriorityResolution resolution) {
    String signature =
        resolution.priority()
            + "|"
            + resolution.source()
            + "|"
            + resolution.routeCode().orElse("-")
            + "|"
            + resolution.fallbackReason();
    String key = normalize(trainName);
    if (signature.equals(resolvedPrioritySignatures.put(key, signature))) {
      return;
    }
    SignalComputationTrace.emitRaw(
        "SMART_PRIORITY_RESOLVED context="
            + safe(traceContext)
            + " train="
            + safe(trainName)
            + " priority="
            + resolution.priority()
            + " source="
            + resolution.source()
            + " routeUuid="
            + resolution.routeUuid().map(UUID::toString).orElse("-")
            + " routeCode="
            + resolution.routeCode().orElse("-")
            + " operationType="
            + resolution.operationType().map(Enum::name).orElse("-")
            + " depotExitContext="
            + resolution.depotExitContext()
            + " manualPriorityPresent="
            + resolution.manualPriorityPresent()
            + " policyBasePriority="
            + resolution.policyBasePriority()
            + " policyAdjustment="
            + resolution.policyAdjustment()
            + " fallbackReason="
            + resolution.fallbackReason(),
        debugLogger);
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? "-" : value.trim();
  }

  private static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record RouteIdentity(
      DispatchPrioritySource source, Optional<UUID> routeUuid, Optional<String> routeCodeText) {

    private RouteIdentity {
      source = source == null ? DispatchPrioritySource.DEFAULT_UNKNOWN : source;
      routeUuid = routeUuid == null ? Optional.empty() : routeUuid;
      routeCodeText =
          routeCodeText == null ? Optional.empty() : normalizeRouteCodeText(routeCodeText);
    }

    static RouteIdentity unknown() {
      return new RouteIdentity(
          DispatchPrioritySource.DEFAULT_UNKNOWN, Optional.empty(), Optional.empty());
    }

    boolean routeKnown() {
      return routeUuid.isPresent() || routeCodeText.isPresent();
    }

    Optional<RouteCode> routeCode() {
      return routeCodeText.flatMap(DispatchPriorityResolver::routeCodeFromText);
    }

    private static Optional<String> normalizeRouteCodeText(Optional<String> value) {
      if (value.isEmpty()) {
        return Optional.empty();
      }
      String trimmed = value.get() == null ? "" : value.get().trim();
      return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }
  }

  private record RouteLookup(
      UUID routeUuid,
      RouteCode routeCode,
      RouteOperationType operationType,
      int operatorPriority,
      String routeCodeText) {

    private RouteLookup {
      Objects.requireNonNull(routeUuid, "routeUuid");
      Objects.requireNonNull(routeCode, "routeCode");
      Objects.requireNonNull(operationType, "operationType");
      routeCodeText =
          routeCodeText == null || routeCodeText.isBlank() ? routeCode.asText() : routeCodeText;
    }
  }

  private record RouteCode(String operator, String line, String route) {

    private RouteCode {
      operator = normalizeRequired(operator);
      line = normalizeRequired(line);
      route = normalizeRequired(route);
    }

    static Optional<RouteCode> of(String operator, String line, String route) {
      if (normalize(operator) == null || normalize(line) == null || normalize(route) == null) {
        return Optional.empty();
      }
      return Optional.of(new RouteCode(operator, line, route));
    }

    String asText() {
      return operator + ":" + line + ":" + route;
    }

    String cacheKey() {
      return asText().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRequired(String raw) {
      String normalized = normalize(raw);
      if (normalized == null) {
        throw new IllegalArgumentException("route code 不能为空");
      }
      return normalized;
    }
  }
}
