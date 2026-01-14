package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

  public RouteDefinitionCache(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  public Optional<RouteDefinition> findById(UUID routeId) {
    Objects.requireNonNull(routeId, "routeId");
    return Optional.ofNullable(cache.get(routeId));
  }

  public Optional<RouteDefinition> findByCodes(
      String operatorCode, String lineCode, String routeCode) {
    RouteCodeKey key = RouteCodeKey.of(operatorCode, lineCode, routeCode);
    if (key == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(codeCache.get(key));
  }

  public Map<UUID, RouteDefinition> snapshot() {
    return Map.copyOf(cache);
  }

  public void clear() {
    cache.clear();
    codeCache.clear();
  }

  /**
   * 从数据库加载所有 Route 与 RouteStop，构建节点序列缓存。
   *
   * <p>仅保留节点数量不少于 2 的线路。
   */
  public void reload(StorageProvider provider) {
    Objects.requireNonNull(provider, "provider");
    cache.clear();
    codeCache.clear();

    CompanyRepository companies = provider.companies();
    OperatorRepository operators = provider.operators();
    LineRepository lines = provider.lines();
    RouteRepository routes = provider.routes();
    RouteStopRepository routeStops = provider.routeStops();
    StationRepository stations = provider.stations();

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
            RouteCodeKey codeKey = RouteCodeKey.of(operator.code(), line.code(), route.code());
            cache.put(route.id(), definition);
            if (codeKey != null) {
              codeCache.put(codeKey, definition);
            }
            loaded++;
          }
        }
      }
    }
    debugLogger.accept("加载 RouteDefinition 缓存完成: routes=" + loaded);
  }

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
    Optional<RouteDefinition> definitionOpt =
        buildDefinition(operator, line, route, stops, provider.stations());
    RouteCodeKey codeKey = RouteCodeKey.of(operator.code(), line.code(), route.code());
    if (definitionOpt.isPresent()) {
      RouteDefinition definition = definitionOpt.get();
      cache.put(route.id(), definition);
      if (codeKey != null) {
        codeCache.put(codeKey, definition);
      }
    } else {
      cache.remove(route.id());
      if (codeKey != null) {
        codeCache.remove(codeKey);
      }
    }
    return definitionOpt;
  }

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
  }

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
    List<RouteStop> sorted = new ArrayList<>(stops);
    sorted.sort(Comparator.comparingInt(RouteStop::sequence));
    List<NodeId> nodes = new ArrayList<>();
    int totalStops = sorted.size();
    int waypointStops = 0;
    int stationStops = 0;
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
              + " missingStation="
              + missingStation
              + " missingStationGraph="
              + missingStationGraph);
      return Optional.empty();
    }
    RouteId routeKey =
        RouteId.of(
            RouteCodeKey.formatRouteId(operator.code(), line.code(), route.code(), route.id()));
    return Optional.of(new RouteDefinition(routeKey, nodes, Optional.empty()));
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
  }
}
