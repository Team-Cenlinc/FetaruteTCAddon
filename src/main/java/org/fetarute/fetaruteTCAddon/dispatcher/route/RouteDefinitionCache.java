package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher.DynamicSpec;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * RouteDefinition 的内存缓存，负责从数据库恢复线路节点序列，并提供 code 组合检索。
 *
 * <p>RouteStop 会按 sequence 排序；优先使用 {@code waypointNodeId}，其次使用 Station.graphNodeId。
 */
public final class RouteDefinitionCache {

  private final Consumer<String> debugLogger;
  private final ConcurrentMap<UUID, RouteDefinition> cache = new ConcurrentHashMap<>();
  private final ConcurrentMap<RouteCodeKey, RouteDefinition> codeCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, List<RouteStop>> stopCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, RouteEntry> entryCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, UUID> uuidByRouteKey = new ConcurrentHashMap<>();
  private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

  public RouteDefinitionCache(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /** 根据 routeId 获取缓存定义。 */
  public Optional<RouteDefinition> findById(UUID routeId) {
    Objects.requireNonNull(routeId, "routeId");
    return Optional.ofNullable(cache.get(routeId));
  }

  /** 根据 operator/line/route code 获取缓存定义。 */
  public Optional<RouteDefinition> findByCodes(
      String operatorCode, String lineCode, String routeCode) {
    RouteCodeKey key = RouteCodeKey.of(operatorCode, lineCode, routeCode);
    if (key == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(codeCache.get(key));
  }

  /** 返回 RouteDefinition 全量快照（只读）。 */
  public Map<UUID, RouteDefinition> snapshot() {
    return Map.copyOf(cache);
  }

  /**
   * 交路所属的运营商、线路与交路实体（与 {@link #findById} 同时写入、同时移除）。
   *
   * <p>运营类型（{@code pattern_type}）、交路阶段（{@code operation_type}）与所属线路都从这里取，不必再查库。
   */
  public Optional<RouteRecord> findRecord(UUID routeId) {
    if (routeId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(entryCache.get(routeId)).map(RouteEntry::record);
  }

  /**
   * 全部交路条目：每条交路的定义、归属与停靠表是同一次写入的，彼此一致。
   *
   * <p>要同时用到这三样时读这里，不要分别调 {@link #snapshot}、{@link #findRecord}、{@link #listStops}——
   * 三次读之间缓存可能被刷新，拿到的会是不同版本。
   */
  public Collection<RouteEntry> entries() {
    return List.copyOf(entryCache.values());
  }

  /** 由 {@link RouteDefinition#id()} 反查交路 UUID。 */
  public Optional<UUID> findUuid(RouteId routeId) {
    if (routeId == null || routeId.value() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(uuidByRouteKey.get(normalizeRouteId(routeId.value())));
  }

  /**
   * 注册缓存变更监听：{@link #reload}、{@link #refresh}、{@link #remove}、{@link #clear} 之后同步回调。
   *
   * <p>车站索引靠它在交路变化时立即重算停靠线路；回调里抛的异常只记日志，不影响缓存本身。
   */
  public void addChangeListener(Runnable listener) {
    if (listener != null) {
      changeListeners.add(listener);
    }
  }

  /** 移除变更监听。 */
  public void removeChangeListener(Runnable listener) {
    changeListeners.remove(listener);
  }

  /** 获取指定线路的 RouteStop 列表（按 sequence 排序）。 */
  /**
   * 获取 Route 的有效 RouteStop 列表（与 waypoints 索引对齐）。
   *
   * <p>返回的列表只包含有 nodeId 的 RouteStop，索引与 {@link RouteDefinition#waypoints()} 一一对应。
   */
  public List<RouteStop> listStops(RouteId routeId) {
    if (routeId == null || routeId.value() == null) {
      return List.of();
    }
    return stopCache.getOrDefault(normalizeRouteId(routeId.value()), List.of());
  }

  /**
   * 按 waypoints 索引获取对应的 RouteStop。
   *
   * <p>索引与 {@link RouteDefinition#waypoints()} 对齐：{@code findStop(routeId, i)} 返回 {@code
   * waypoints().get(i)} 对应的 RouteStop。
   *
   * @param routeId 路线 ID
   * @param waypointIndex waypoints 数组的索引
   * @return 对应的 RouteStop，找不到时返回 empty
   */
  public Optional<RouteStop> findStop(RouteId routeId, int waypointIndex) {
    if (routeId == null || waypointIndex < 0) {
      return Optional.empty();
    }
    List<RouteStop> stops = listStops(routeId);
    if (waypointIndex >= stops.size()) {
      return Optional.empty();
    }
    return Optional.ofNullable(stops.get(waypointIndex));
  }

  /** 清空所有缓存。 */
  public void clear() {
    cache.clear();
    codeCache.clear();
    stopCache.clear();
    entryCache.clear();
    uuidByRouteKey.clear();
    fireChanged();
  }

  /**
   * 从数据库加载所有 Route 与 RouteStop，构建节点序列缓存。
   *
   * <p>仅保留节点数量不少于 2 的线路。
   */
  /**
   * 从数据库加载所有 Route 与 RouteStop，构建节点序列缓存。
   *
   * <p>仅保留节点数量不少于 2 的线路。
   */
  public void reload(StorageProvider provider) {
    Objects.requireNonNull(provider, "provider");

    CompanyRepository companies = provider.companies();
    OperatorRepository operators = provider.operators();
    LineRepository lines = provider.lines();
    RouteRepository routes = provider.routes();
    RouteStopRepository routeStops = provider.routeStops();
    StationRepository stations = provider.stations();

    // 先在旁边建好，再整体替换：重载期间其他线程读到的是新旧混合，但不会读到空缓存或半条交路。
    Staging staging = new Staging();
    int loaded = 0;
    for (var company : companies.listAll()) {
      if (company == null) {
        continue;
      }
      for (Operator operator : operators.listByCompany(company.id())) {
        if (operator == null) {
          continue;
        }
        for (Line line : lines.listByOperator(operator.id())) {
          if (line == null) {
            continue;
          }
          List<Route> byLine = routes.listByLine(line.id());
          for (Route route : byLine) {
            if (route == null) {
              continue;
            }
            List<RouteStop> stops = routeStops.listByRoute(route.id());
            Optional<RouteDefinition> definitionOpt =
                buildDefinition(operator, line, route, stops, stations);
            if (definitionOpt.isEmpty()) {
              continue;
            }
            RouteDefinition definition = definitionOpt.get();
            // 使用 filterStopsWithNodeId 保持 stopCache 索引与 waypoints 对齐
            List<RouteStop> aligned =
                List.copyOf(filterStopsWithNodeId(sortedStops(stops), stations));
            staging.put(definition, operator, line, route, aligned);
            loaded++;
          }
        }
      }
    }
    replaceContents(stopCache, staging.stops);
    replaceContents(cache, staging.definitions);
    replaceContents(codeCache, staging.codes);
    replaceContents(uuidByRouteKey, staging.uuids);
    replaceContents(entryCache, staging.entries);
    debugLogger.accept("加载 RouteDefinition 缓存完成: routes=" + loaded);
    fireChanged();
  }

  /**
   * 按指定 Route 增量刷新缓存。
   *
   * <p>若节点数量不足，会移除已有缓存。
   */
  /**
   * 按指定 Route 增量刷新缓存。
   *
   * <p>若节点数量不足，会移除已有缓存。
   */
  public Optional<RouteDefinition> refresh(
      StorageProvider provider, Operator operator, Line line, Route route) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(line, "line");
    Objects.requireNonNull(route, "route");
    List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
    StationRepository stations = provider.stations();
    Optional<RouteDefinition> definitionOpt =
        buildDefinition(operator, line, route, stops, stations);
    RouteCodeKey codeKey = RouteCodeKey.of(operator.code(), line.code(), route.code());
    if (definitionOpt.isPresent()) {
      RouteDefinition definition = definitionOpt.get();
      cache.put(route.id(), definition);
      if (codeKey != null) {
        codeCache.put(codeKey, definition);
      }
      // 使用 filterStopsWithNodeId 保持 stopCache 索引与 waypoints 对齐
      List<RouteStop> aligned = List.copyOf(filterStopsWithNodeId(sortedStops(stops), stations));
      String routeKey = normalizeRouteId(definition.id().value());
      stopCache.put(routeKey, aligned);
      uuidByRouteKey.put(routeKey, route.id());
      entryCache.put(
          route.id(),
          new RouteEntry(route.id(), definition, new RouteRecord(operator, line, route), aligned));
    } else {
      cache.remove(route.id());
      if (codeKey != null) {
        codeCache.remove(codeKey);
      }
      String routeKey =
          normalizeRouteId(
              RouteCodeKey.formatRouteId(operator.code(), line.code(), route.code(), route.id()));
      stopCache.remove(routeKey);
      entryCache.remove(route.id());
      uuidByRouteKey.remove(routeKey);
    }
    fireChanged();
    return definitionOpt;
  }

  /** 从缓存中移除指定 Route 定义。 */
  /** 从缓存中移除指定 Route 定义。 */
  public void remove(Operator operator, Line line, Route route) {
    if (operator == null || line == null || route == null) {
      return;
    }
    cache.remove(route.id());
    RouteCodeKey codeKey = RouteCodeKey.of(operator.code(), line.code(), route.code());
    if (codeKey != null) {
      codeCache.remove(codeKey);
    }
    String routeKey =
        normalizeRouteId(
            RouteCodeKey.formatRouteId(operator.code(), line.code(), route.code(), route.id()));
    stopCache.remove(routeKey);
    entryCache.remove(route.id());
    uuidByRouteKey.remove(routeKey);
    fireChanged();
  }

  private static <K, V> void replaceContents(ConcurrentMap<K, V> target, Map<K, V> next) {
    target.keySet().retainAll(next.keySet());
    target.putAll(next);
  }

  /** 重载时在旁边建的新内容。 */
  private static final class Staging {
    private final Map<UUID, RouteDefinition> definitions = new HashMap<>();
    private final Map<RouteCodeKey, RouteDefinition> codes = new HashMap<>();
    private final Map<String, List<RouteStop>> stops = new HashMap<>();
    private final Map<String, UUID> uuids = new HashMap<>();
    private final Map<UUID, RouteEntry> entries = new HashMap<>();

    private void put(
        RouteDefinition definition,
        Operator operator,
        Line line,
        Route route,
        List<RouteStop> aligned) {
      definitions.put(route.id(), definition);
      RouteCodeKey codeKey = RouteCodeKey.of(operator.code(), line.code(), route.code());
      if (codeKey != null) {
        codes.put(codeKey, definition);
      }
      String routeKey = normalizeRouteId(definition.id().value());
      stops.put(routeKey, aligned);
      uuids.put(routeKey, route.id());
      entries.put(
          route.id(),
          new RouteEntry(route.id(), definition, new RouteRecord(operator, line, route), aligned));
    }
  }

  /**
   * 一条交路的定义、归属与停靠表（同一次写入）。
   *
   * @param routeId 交路 UUID
   * @param definition 交路定义
   * @param record 归属与交路实体
   * @param stops 与 {@code definition.waypoints()} 下标对齐的停靠表（即 {@link #listStops} 的返回值）
   */
  public record RouteEntry(
      UUID routeId, RouteDefinition definition, RouteRecord record, List<RouteStop> stops) {
    public RouteEntry {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(definition, "definition");
      Objects.requireNonNull(record, "record");
      stops = List.copyOf(stops);
    }
  }

  private void fireChanged() {
    for (Runnable listener : changeListeners) {
      try {
        listener.run();
      } catch (RuntimeException ex) {
        debugLogger.accept("RouteDefinition 变更监听失败: " + ex);
      }
    }
  }

  /**
   * 交路的归属与实体快照。
   *
   * @param operator 所属运营商
   * @param line 所属线路
   * @param route 交路实体（含 {@code patternType} 运营类型与 {@code operationType} 交路阶段）
   */
  public record RouteRecord(Operator operator, Line line, Route route) {
    public RouteRecord {
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(line, "line");
      Objects.requireNonNull(route, "route");
    }
  }

  /**
   * 构建单条 RouteDefinition，并统计解析过程中的异常情况用于日志输出。
   *
   * <p>优先使用 waypoint nodeId；若为站点，则读取 Station.graphNodeId。
   */
  private Optional<RouteDefinition> buildDefinition(
      Operator operator,
      Line line,
      Route route,
      List<RouteStop> stops,
      StationRepository stations) {
    if (operator == null || line == null || route == null) {
      return Optional.empty();
    }
    if (stops == null || stops.isEmpty()) {
      debugLogger.accept(
          "跳过线路定义(无停靠): route="
              + route.code()
              + " id="
              + route.id()
              + " op="
              + operator.code()
              + " line="
              + line.code());
      return Optional.empty();
    }
    List<RouteStop> sorted = sortedStops(stops);
    List<NodeId> nodes = new ArrayList<>();
    int totalStops = sorted.size();
    int waypointStops = 0;
    int stationStops = 0;
    int dynamicStops = 0;
    int missingStation = 0;
    int missingStationGraph = 0;
    for (RouteStop stop : sorted) {
      if (stop == null) {
        continue;
      }
      Optional<NodeId> nodeIdOpt = resolveNodeId(stop, stations);
      if (stop.waypointNodeId().isPresent()) {
        waypointStops++;
      } else if (stop.stationId().isPresent()) {
        stationStops++;
        if (nodeIdOpt.isEmpty()) {
          Optional<Station> stationOpt = stations.findById(stop.stationId().get());
          if (stationOpt.isEmpty()) {
            missingStation++;
          } else if (stationOpt.get().graphNodeId().isEmpty()) {
            missingStationGraph++;
          }
        }
      } else if (DynamicStopMatcher.isDynamicStop(stop)) {
        dynamicStops++;
      }
      nodeIdOpt.ifPresent(nodes::add);
    }
    if (nodes.size() < 2) {
      debugLogger.accept(
          "跳过线路定义(节点不足): route="
              + route.code()
              + " id="
              + route.id()
              + " op="
              + operator.code()
              + " line="
              + line.code()
              + " stops="
              + totalStops
              + " resolved="
              + nodes.size()
              + " waypointStops="
              + waypointStops
              + " stationStops="
              + stationStops
              + " dynamicStops="
              + dynamicStops
              + " missingStation="
              + missingStation
              + " missingStationGraph="
              + missingStationGraph);
      return Optional.empty();
    }
    RouteId routeKey =
        RouteId.of(
            RouteCodeKey.formatRouteId(operator.code(), line.code(), route.code(), route.id()));
    RouteLifecycleMode lifecycleMode = RouteDefinition.resolveMode(sorted);
    RouteMetadata metadata =
        RouteMetadata.of(operator.code(), line.code(), route.code(), route.name());
    return Optional.of(new RouteDefinition(routeKey, nodes, Optional.of(metadata), lifecycleMode));
  }

  private List<RouteStop> sortedStops(List<RouteStop> stops) {
    if (stops == null || stops.isEmpty()) {
      return List.of();
    }
    List<RouteStop> sorted = new ArrayList<>(stops);
    sorted.sort(Comparator.comparingInt(RouteStop::sequence));
    return sorted;
  }

  /**
   * 过滤并返回只有有效 nodeId 的 RouteStop（与 waypoints 索引对齐）。
   *
   * <p>RouteStop 只有在 waypointNodeId 存在或 stationId 对应的 Station 有 graphNodeId 或 DYNAMIC 可解析时， 才会加入
   * waypoints；此处使用相同的判定逻辑以保持索引一致。
   *
   * @param stops 原始 RouteStop 列表（已排序）
   * @param stations Station 仓库（用于解析 stationId → graphNodeId）
   * @return 与 waypoints 索引对齐的有效 RouteStop 列表
   */
  private List<RouteStop> filterStopsWithNodeId(List<RouteStop> stops, StationRepository stations) {
    if (stops == null || stops.isEmpty()) {
      return List.of();
    }
    List<RouteStop> result = new ArrayList<>();
    for (RouteStop stop : stops) {
      if (stop == null) {
        continue;
      }
      Optional<NodeId> nodeIdOpt = resolveNodeId(stop, stations);
      if (nodeIdOpt.isPresent()) {
        result.add(stop);
      }
    }
    return result;
  }

  private Optional<NodeId> resolveNodeId(RouteStop stop, StationRepository stations) {
    if (stop == null) {
      return Optional.empty();
    }
    if (stop.waypointNodeId().isPresent()) {
      return Optional.of(NodeId.of(stop.waypointNodeId().get()));
    }
    if (stop.stationId().isPresent()) {
      Optional<Station> stationOpt = stations.findById(stop.stationId().get());
      if (stationOpt.isEmpty()) {
        return Optional.empty();
      }
      Station station = stationOpt.get();
      if (station.graphNodeId().isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(NodeId.of(station.graphNodeId().get()));
    }
    // 尝试从 DYNAMIC 指令解析占位 NodeId
    Optional<DynamicSpec> specOpt = DynamicStopMatcher.parseDynamicSpec(stop);
    if (specOpt.isPresent()) {
      DynamicSpec spec = specOpt.get();
      // 生成占位 NodeId：OP:S:STATION:fromTrack 或 OP:D:DEPOT:fromTrack
      String placeholder =
          spec.operatorCode()
              + ":"
              + spec.nodeType()
              + ":"
              + spec.nodeName()
              + ":"
              + spec.fromTrack();
      return Optional.of(NodeId.of(placeholder));
    }
    return Optional.empty();
  }

  private record RouteCodeKey(String operatorCode, String lineCode, String routeCode) {

    static RouteCodeKey of(String operatorCode, String lineCode, String routeCode) {
      String operator = normalize(operatorCode);
      String line = normalize(lineCode);
      String route = normalize(routeCode);
      if (operator == null || line == null || route == null) {
        return null;
      }
      return new RouteCodeKey(operator, line, route);
    }

    static String formatRouteId(
        String operatorCode, String lineCode, String routeCode, UUID fallback) {
      String operator = trim(operatorCode);
      String line = trim(lineCode);
      String route = trim(routeCode);
      if (operator == null || line == null || route == null) {
        return fallback != null ? fallback.toString() : "UNKNOWN";
      }
      return operator + ":" + line + ":" + route;
    }

    private static String normalize(String raw) {
      String trimmed = trim(raw);
      if (trimmed == null) {
        return null;
      }
      return trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    private static String trim(String raw) {
      if (raw == null) {
        return null;
      }
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) {
        return null;
      }
      return trimmed;
    }

    @Override
    public String toString() {
      return operatorCode + ":" + lineCode + ":" + routeCode;
    }
  }

  private static String normalizeRouteId(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    return raw.trim().toLowerCase(java.util.Locale.ROOT);
  }
}
