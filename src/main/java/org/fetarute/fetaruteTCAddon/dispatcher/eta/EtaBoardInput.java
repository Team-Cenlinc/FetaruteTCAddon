package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.DutyContinuitySupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;

/**
 * 站牌重算的输入快照：在调用线程上把站牌要读的外部事实一次拷齐，交给后台线程算站牌。
 *
 * <p>为什么要拷：停车状态、实际节点、选台记录、在站记录、待命登记、发车队列、时刻表等钩子背后是主线程上随 tick 改动的注册表，
 * 线程安全没有保证；交路缓存也要与这些事实同一时刻读，否则交路重载后 实际节点与交路长度对不上。快照里的东西都不可变，后台线程只读它（另读调度图快照与限速覆盖，见 {@link
 * EtaCalculator}）。
 *
 * <p>与车站无关：拷的是全网列车与全部票据，同一时间窗内各站共用一份。拷的范围按 {@link EtaCalculator} 站牌路径的读法确定——
 *
 * <ul>
 *   <li>每列车：快照、停车状态、在站与否、当前下标的计划发车、当前下标起各下标的计划到达、实际节点、各 DYNAMIC 停靠的选台记录， 当前下标起各 DYNAMIC
 *       停靠的计划或暂定站台、跑完这一趟后的下一趟。
 *   <li>待命登记：全部候选，及其下一趟。
 *   <li>每张待发票据与预测票据：要等的车、各 DYNAMIC 停靠的计划站台与预先指定的站台、车型。先拷待发票据、后取预测—— 预测会刷新排班器记下的预测车次，与原来同步重算时的先后一致。
 *   <li>上述列车、票据与下一趟涉及的交路：定义、停靠表、归属，停靠表里各车站的目录条目，各站所属线路的规范写法。
 * </ul>
 *
 * <p>没拷到的键按“未接入”处理（与钩子返回空同一口径），并留调试日志。
 */
final class EtaBoardInput {

  private final long sequence;
  private final Instant capturedAt;
  private final Duration window;
  private final long graphVersion;
  private final long trainsVersion;
  private final EtaSources sources;

  private EtaBoardInput(
      long sequence,
      Instant capturedAt,
      Duration window,
      long graphVersion,
      long trainsVersion,
      EtaSources sources) {
    this.sequence = sequence;
    this.capturedAt = capturedAt;
    this.window = window;
    this.graphVersion = graphVersion;
    this.trainsVersion = trainsVersion;
    this.sources = sources;
  }

  /** 拷贝序号：越大越新。 */
  long sequence() {
    return sequence;
  }

  /** 拷贝时刻；后台按它作为“现在”算站牌，结果里的时刻都是绝对时刻。 */
  Instant capturedAt() {
    return capturedAt;
  }

  /** 预测票据的时间窗。 */
  Duration window() {
    return window;
  }

  /**
   * 拷贝时的调度图快照版本（{@link
   * org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService#snapshotVersion}）。
   */
  long graphVersion() {
    return graphVersion;
  }

  /**
   * 拷贝时的列车快照版本（{@link
   * org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore#version}）。
   */
  long trainsVersion() {
    return trainsVersion;
  }

  /** 冻结来源：只读这份快照。 */
  EtaSources sources() {
    return sources;
  }

  /**
   * 在调用线程上拷贝站牌输入。
   *
   * @param live 实时来源
   * @param sequence 拷贝序号
   * @param now 当前时刻
   * @param window 预测票据的时间窗
   * @param graphVersion 当前调度图快照版本
   * @param trainsVersion 当前列车快照版本
   * @param debugLogger 读取失败与缺键时留痕
   */
  static EtaBoardInput capture(
      EtaSources live,
      long sequence,
      Instant now,
      Duration window,
      long graphVersion,
      long trainsVersion,
      Consumer<String> debugLogger) {
    Objects.requireNonNull(live, "live");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(window, "window");
    Consumer<String> log = debugLogger == null ? message -> {} : debugLogger;
    Capture capture = new Capture(live, now, window, log);
    capture.run();
    return new EtaBoardInput(
        sequence, now, window, graphVersion, trainsVersion, capture.frozen(log));
  }

  /** 拷贝过程：只在调用线程上用。 */
  private static final class Capture {
    private final EtaSources live;
    private final Instant now;
    private final Duration window;
    private final Consumer<String> log;

    private Map<String, TrainRuntimeSnapshot> trains = Map.of();
    private final Map<UUID, Optional<RouteDefinition>> definitions = new HashMap<>();
    private final Map<RouteId, List<RouteStop>> stops = new HashMap<>();
    private final Map<UUID, Optional<RouteDefinitionCache.RouteRecord>> records = new HashMap<>();
    private final Map<String, Optional<RuntimeStopState>> stopStates = new HashMap<>();
    private final Map<String, Map<Integer, Instant>> departures = new HashMap<>();
    private final Map<String, Map<Integer, Instant>> arrivals = new HashMap<>();
    private final Map<String, RouteNodes> effective = new HashMap<>();
    private final Map<String, RouteIndices> placed = new HashMap<>();
    private final Map<String, RoutePlatforms> planned = new HashMap<>();
    private final Map<String, Presence> presence = new HashMap<>();
    private final Map<String, Presence> remainders = new HashMap<>();
    private final Map<UUID, Optional<EtaService.ThroughRoute>> throughs = new HashMap<>();
    private final Map<String, Optional<DutyContinuitySupport.NextDeparture>> nextDepartures =
        new HashMap<>();
    private List<LayoverRegistry.LayoverCandidate> layovers = List.of();
    private List<SpawnTicket> pending = List.of();
    private Optional<List<SpawnTicket>> forecast = Optional.empty();
    private final Map<SpawnTicket, Optional<String>> awaited = new IdentityHashMap<>();
    private final Map<SpawnTicket, Map<Integer, String>> ticketPlanned = new IdentityHashMap<>();
    private final Map<SpawnTicket, Map<Integer, NodeId>> ticketPinned = new IdentityHashMap<>();
    private final Map<ConsistKey, TrainConfig> trainConfigs = new HashMap<>();
    private final Map<UUID, Optional<StationDirectory.StationEntry>> stations = new HashMap<>();
    private final Map<RouteLineChanges.LineRef, RouteLineChanges.LineRef> lines = new HashMap<>();
    private final Set<LineSource> lineSources = new LinkedHashSet<>();

    private Capture(EtaSources live, Instant now, Duration window, Consumer<String> log) {
      this.live = live;
      this.now = now;
      this.window = window;
      this.log = log;
    }

    private void run() {
      trains = Map.copyOf(live.trains().all());
      for (Map.Entry<String, TrainRuntimeSnapshot> entry : trains.entrySet()) {
        captureTrain(entry.getKey(), entry.getValue());
      }
      EtaSources.Layovers registry = live.layovers();
      if (registry != null) {
        layovers = List.copyOf(registry.all());
        for (LayoverRegistry.LayoverCandidate candidate : layovers) {
          captureNextDeparture(candidate.trainName());
        }
      }
      for (Optional<DutyContinuitySupport.NextDeparture> next :
          List.copyOf(nextDepartures.values())) {
        next.ifPresent(departure -> route(departure.routeId()).ifPresent(this::lineSourceOf));
      }
      pending = List.copyOf(live.tickets().pending());
      pending.forEach(this::captureTicket);
      forecast =
          live.tickets()
              .forecast(now, window, EtaCalculator.FORECAST_LIMIT_PER_SERVICE)
              .map(tickets -> Collections.unmodifiableList(new ArrayList<>(tickets)));
      forecast.ifPresent(tickets -> tickets.forEach(this::captureTicket));
      captureStations();
      captureLines();
    }

    private void captureTrain(String trainName, TrainRuntimeSnapshot snap) {
      if (trainName == null || snap == null) {
        return;
      }
      int index = snap.routeIndex();
      if (live.stopStates() != null) {
        stopStates.put(
            trainName, safe(() -> live.stopStates().apply(trainName), "STOP_STATE", trainName));
      }
      if (live.atStation() != null && snap.routeId() != null) {
        String routeKey = snap.routeId().value();
        presence.put(
            trainName,
            new Presence(routeKey, index, live.atStation().at(trainName, routeKey, index)));
      }
      if (snap.routeId() != null) {
        String routeKey = snap.routeId().value();
        try {
          remainders.put(
              trainName,
              new Presence(
                  routeKey,
                  index,
                  live.divisionRemainders().remainsAt(trainName, routeKey, index)));
        } catch (RuntimeException ex) {
          log.accept("ETA_DIVISION_REMAINDER_READ_FAILED train=" + trainName + " error=" + ex);
        }
      }
      if (live.plannedDepartures() != null) {
        safe(() -> live.plannedDepartures().apply(trainName, index), "PLANNED_DEPARTURE", trainName)
            .ifPresent(
                at -> departures.computeIfAbsent(trainName, k -> new HashMap<>()).put(index, at));
      }
      captureNextDeparture(trainName);
      if (snap.routeUuid() == null) {
        return;
      }
      Optional<RouteDefinition> routeOpt = route(snap.routeUuid());
      if (routeOpt.isEmpty()) {
        return;
      }
      RouteDefinition route = routeOpt.get();
      lineSourceOf(route);
      List<RouteStop> routeStops = stops.getOrDefault(route.id(), List.of());
      if (live.plannedArrivals() != null) {
        Map<Integer, Instant> byIndex = new HashMap<>();
        for (int i = Math.max(0, index); i < route.waypoints().size(); i++) {
          int at = i;
          safe(() -> live.plannedArrivals().apply(trainName, at), "PLANNED_ARRIVAL", trainName)
              .ifPresent(time -> byIndex.put(at, time));
        }
        arrivals.put(trainName, byIndex);
      }
      if (live.effectiveWaypoints() != null) {
        try {
          List<NodeId> resolved = live.effectiveWaypoints().apply(trainName, route);
          effective.put(
              trainName,
              new RouteNodes(
                  route.id(),
                  resolved == null
                      ? null
                      : Collections.unmodifiableList(new ArrayList<>(resolved))));
        } catch (RuntimeException ex) {
          log.accept("ETA_EFFECTIVE_WAYPOINTS_READ_FAILED train=" + trainName + " error=" + ex);
        }
      }
      if (live.placedStops() != null) {
        Set<Integer> indices = new HashSet<>();
        for (int i = 0; i < routeStops.size(); i++) {
          if (DynamicStopMatcher.isDynamicStop(routeStops.get(i))) {
            try {
              if (live.placedStops().placed(trainName, route, i)) {
                indices.add(i);
              }
            } catch (RuntimeException ex) {
              log.accept(
                  "ETA_PLACED_STOP_READ_FAILED train="
                      + trainName
                      + " index="
                      + i
                      + " error="
                      + ex);
            }
          }
        }
        placed.put(trainName, new RouteIndices(route.id(), Set.copyOf(indices)));
      }
      if (live.plannedPlatforms() != null) {
        Map<Integer, NodeId> nodes = new HashMap<>();
        for (int i = Math.max(0, index); i < routeStops.size(); i++) {
          if (!DynamicStopMatcher.isDynamicStop(routeStops.get(i))) {
            continue;
          }
          try {
            Optional<NodeId> node = live.plannedPlatforms().planned(trainName, route, i);
            if (node != null && node.isPresent()) {
              nodes.put(i, node.get());
            }
          } catch (RuntimeException ex) {
            log.accept("ETA_PLANNED_PLATFORM_READ_FAILED train=" + trainName + " error=" + ex);
          }
        }
        planned.put(trainName, new RoutePlatforms(route.id(), nodes));
      }
    }

    private void captureNextDeparture(String trainName) {
      DutyContinuitySupport continuity = live.continuity();
      if (continuity == null || trainName == null || nextDepartures.containsKey(trainName)) {
        return;
      }
      Optional<DutyContinuitySupport.NextDeparture> next = continuity.nextDepartureOf(trainName);
      nextDepartures.put(trainName, next == null ? Optional.empty() : next);
    }

    private void captureTicket(SpawnTicket ticket) {
      if (ticket == null || ticket.service() == null || ticket.service().routeId() == null) {
        return;
      }
      UUID routeUuid = ticket.service().routeId();
      DutyContinuitySupport continuity = live.continuity();
      if (continuity != null) {
        Optional<String> vehicle = continuity.awaitedVehicleOf(ticket);
        awaited.put(ticket, vehicle == null ? Optional.empty() : vehicle);
      }
      ConfigManager.ConfigView config = live.config();
      if (config != null) {
        Optional<String> consist = ticket.consist() == null ? Optional.empty() : ticket.consist();
        trainConfigs.computeIfAbsent(
            new ConsistKey(routeUuid, consist),
            key -> live.ticketTrainConfigs().resolve(routeUuid, consist, config, now));
      }
      Optional<RouteDefinition> routeOpt = route(routeUuid);
      if (routeOpt.isEmpty()) {
        return;
      }
      RouteDefinition route = routeOpt.get();
      lineSourceOf(route);
      RouteLineChanges.LineRef.of(ticket.service().operatorCode(), ticket.service().lineCode())
          .ifPresent(line -> lineSources.add(new LineSource(route.id(), line)));
      List<RouteStop> routeStops = stops.getOrDefault(route.id(), List.of());
      Map<Integer, String> plannedNodes = new HashMap<>();
      Map<Integer, NodeId> pinnedNodes = new HashMap<>();
      for (int i = 0; i < routeStops.size(); i++) {
        if (!DynamicStopMatcher.isDynamicStop(routeStops.get(i))) {
          continue;
        }
        int at = i;
        if (continuity != null) {
          Optional<String> node = continuity.plannedPlatformOf(ticket, at);
          if (node != null && node.isPresent()) {
            plannedNodes.put(at, node.get());
          }
        }
        if (live.ticketPlatforms() != null) {
          try {
            Optional<NodeId> node = live.ticketPlatforms().pinned(ticket, at);
            if (node != null && node.isPresent()) {
              pinnedNodes.put(at, node.get());
            }
          } catch (RuntimeException ex) {
            log.accept("ETA_TICKET_PLATFORM_READ_FAILED ticket=" + ticket.id() + " error=" + ex);
          }
        }
      }
      ticketPlanned.put(ticket, plannedNodes);
      ticketPinned.put(ticket, pinnedNodes);
    }

    /** 交路定义；顺带拷停靠表与归属。 */
    private Optional<RouteDefinition> route(UUID routeUuid) {
      Optional<RouteDefinition> known = definitions.get(routeUuid);
      if (known != null) {
        return known;
      }
      Optional<RouteDefinition> found = live.routes().findById(routeUuid);
      Optional<RouteDefinition> definition = found == null ? Optional.empty() : found;
      definitions.put(routeUuid, definition);
      records.put(routeUuid, live.routes().findRecord(routeUuid));
      definition.ifPresent(
          route ->
              stops.computeIfAbsent(route.id(), id -> List.copyOf(live.routes().listStops(id))));
      // 联挂之后的去向：站牌要读主干的运营终点，主干交路也一起拷
      Optional<EtaService.ThroughRoute> through = Optional.empty();
      try {
        Optional<EtaService.ThroughRoute> read = live.throughRoutes().apply(routeUuid);
        through = read == null ? Optional.empty() : read;
      } catch (RuntimeException ex) {
        log.accept("ETA_THROUGH_ROUTE_READ_FAILED route=" + routeUuid + " error=" + ex);
      }
      throughs.put(routeUuid, through);
      through.ifPresent(trunk -> route(trunk.route()).ifPresent(this::lineSourceOf));
      return definition;
    }

    private void lineSourceOf(RouteDefinition route) {
      route
          .metadata()
          .flatMap(RouteLineChanges.LineRef::of)
          .ifPresent(line -> lineSources.add(new LineSource(route.id(), line)));
    }

    private void captureStations() {
      for (List<RouteStop> routeStops : stops.values()) {
        for (RouteStop stop : routeStops) {
          stop.stationId()
              .ifPresent(
                  id -> {
                    if (!stations.containsKey(id)) {
                      Optional<StationDirectory.StationEntry> entry =
                          live.stationLookup().apply(id);
                      stations.put(id, entry == null ? Optional.empty() : entry);
                    }
                  });
        }
      }
    }

    private void captureLines() {
      for (LineSource source : lineSources) {
        List<RouteLineChanges.LineRef> refs = new ArrayList<>();
        refs.add(source.line());
        refs.addAll(
            RouteLineChanges.linesByIndex(
                stops.getOrDefault(source.routeId(), List.of()), source.line()));
        for (RouteLineChanges.LineRef ref : refs) {
          lines.computeIfAbsent(ref, live.lineCanonicalizer());
        }
      }
    }

    /** 读一个钩子；读取失败按空处理并留痕（与计算时现读同一口径）。 */
    private <T> Optional<T> safe(
        java.util.function.Supplier<Optional<T>> read, String kind, String trainName) {
      try {
        Optional<T> value = read.get();
        return value == null ? Optional.empty() : value;
      } catch (RuntimeException ex) {
        log.accept("ETA_" + kind + "_READ_FAILED train=" + trainName + " error=" + ex);
        return Optional.empty();
      }
    }

    private EtaSources frozen(Consumer<String> log) {
      Frozen data = new Frozen(this, log);
      return new EtaSources(
          data.routes(),
          data.trains(),
          live.stopStates() == null ? null : data::stopState,
          live.plannedDepartures() == null ? null : data::plannedDeparture,
          live.plannedHoldCap(),
          live.plannedArrivals() == null ? null : data::plannedArrival,
          live.effectiveWaypoints() == null ? null : data::effectiveWaypoints,
          live.placedStops() == null ? null : data::placed,
          live.plannedPlatforms() == null ? null : data::plannedPlatform,
          live.ticketPlatforms() == null ? null : data::pinnedPlatform,
          live.atStation() == null ? null : data::atStation,
          data::canonicalLine,
          data::station,
          live.config(),
          data::trainConfig,
          data.tickets(),
          live.continuity() == null ? null : data.continuity(),
          live.layovers() == null ? null : data.layovers(),
          data::through,
          data::remainsAt);
    }
  }

  /** 冻结后的数据：只读，可跨线程。 */
  private static final class Frozen {
    private final Duration window;
    private final Consumer<String> log;
    private final Map<String, TrainRuntimeSnapshot> trains;
    private final Map<UUID, Optional<RouteDefinition>> definitions;
    private final Map<RouteId, List<RouteStop>> stops;
    private final Map<UUID, Optional<RouteDefinitionCache.RouteRecord>> records;
    private final Map<String, Optional<RuntimeStopState>> stopStates;
    private final Map<String, Map<Integer, Instant>> departures;
    private final Map<String, Map<Integer, Instant>> arrivals;
    private final Map<String, RouteNodes> effective;
    private final Map<String, RouteIndices> placed;
    private final Map<String, RoutePlatforms> planned;
    private final Map<String, Presence> presence;
    private final Map<String, Presence> remainders;
    private final Map<UUID, Optional<EtaService.ThroughRoute>> throughs;
    private final Map<String, Optional<DutyContinuitySupport.NextDeparture>> nextDepartures;
    private final List<LayoverRegistry.LayoverCandidate> layovers;
    private final Map<String, LayoverRegistry.LayoverCandidate> layoversByName;
    private final List<SpawnTicket> pending;
    private final Optional<List<SpawnTicket>> forecast;
    private final Map<SpawnTicket, Optional<String>> awaited;
    private final Map<SpawnTicket, Map<Integer, String>> ticketPlanned;
    private final Map<SpawnTicket, Map<Integer, NodeId>> ticketPinned;
    private final Map<ConsistKey, TrainConfig> trainConfigs;
    private final Map<UUID, Optional<StationDirectory.StationEntry>> stations;
    private final Map<RouteLineChanges.LineRef, RouteLineChanges.LineRef> lines;

    private Frozen(Capture capture, Consumer<String> log) {
      this.window = capture.window;
      this.log = log;
      this.trains = capture.trains;
      this.definitions = Collections.unmodifiableMap(new HashMap<>(capture.definitions));
      this.stops = Collections.unmodifiableMap(new HashMap<>(capture.stops));
      this.records = Collections.unmodifiableMap(new HashMap<>(capture.records));
      this.stopStates = Collections.unmodifiableMap(new HashMap<>(capture.stopStates));
      this.departures = Collections.unmodifiableMap(new HashMap<>(capture.departures));
      this.arrivals = Collections.unmodifiableMap(new HashMap<>(capture.arrivals));
      this.effective = Collections.unmodifiableMap(new HashMap<>(capture.effective));
      this.placed = Collections.unmodifiableMap(new HashMap<>(capture.placed));
      this.planned = Collections.unmodifiableMap(new HashMap<>(capture.planned));
      this.presence = Collections.unmodifiableMap(new HashMap<>(capture.presence));
      this.remainders = Collections.unmodifiableMap(new HashMap<>(capture.remainders));
      this.throughs = Collections.unmodifiableMap(new HashMap<>(capture.throughs));
      this.nextDepartures = Collections.unmodifiableMap(new HashMap<>(capture.nextDepartures));
      this.layovers = capture.layovers;
      Map<String, LayoverRegistry.LayoverCandidate> byName = new HashMap<>();
      for (LayoverRegistry.LayoverCandidate candidate : capture.layovers) {
        byName.putIfAbsent(candidate.trainName(), candidate);
      }
      this.layoversByName = Collections.unmodifiableMap(byName);
      this.pending = capture.pending;
      this.forecast = capture.forecast;
      this.awaited = Collections.unmodifiableMap(new IdentityHashMap<>(capture.awaited));
      this.ticketPlanned =
          Collections.unmodifiableMap(new IdentityHashMap<>(capture.ticketPlanned));
      this.ticketPinned = Collections.unmodifiableMap(new IdentityHashMap<>(capture.ticketPinned));
      this.trainConfigs = Collections.unmodifiableMap(new HashMap<>(capture.trainConfigs));
      this.stations = Collections.unmodifiableMap(new HashMap<>(capture.stations));
      this.lines = Collections.unmodifiableMap(new HashMap<>(capture.lines));
    }

    private void miss(String what) {
      log.accept("ETA_BOARD_INPUT_MISS " + what);
    }

    EtaSources.Routes routes() {
      return new EtaSources.Routes() {
        @Override
        public Optional<RouteDefinition> findById(UUID routeId) {
          Objects.requireNonNull(routeId, "routeId");
          Optional<RouteDefinition> found = definitions.get(routeId);
          if (found == null) {
            miss("route=" + routeId);
            return Optional.empty();
          }
          return found;
        }

        @Override
        public List<RouteStop> listStops(RouteId routeId) {
          List<RouteStop> found = routeId == null ? null : stops.get(routeId);
          return found == null ? List.of() : found;
        }

        @Override
        public Optional<RouteDefinitionCache.RouteRecord> findRecord(UUID routeId) {
          if (routeId == null) {
            return Optional.empty();
          }
          Optional<RouteDefinitionCache.RouteRecord> found = records.get(routeId);
          return found == null ? Optional.empty() : found;
        }
      };
    }

    EtaSources.Trains trains() {
      return new EtaSources.Trains() {
        @Override
        public Optional<TrainRuntimeSnapshot> get(String trainName) {
          if (trainName == null || trainName.isBlank()) {
            return Optional.empty();
          }
          return Optional.ofNullable(trains.get(trainName));
        }

        @Override
        public Map<String, TrainRuntimeSnapshot> all() {
          return trains;
        }
      };
    }

    Optional<RuntimeStopState> stopState(String trainName) {
      Optional<RuntimeStopState> state = trainName == null ? null : stopStates.get(trainName);
      return state == null ? Optional.empty() : state;
    }

    Optional<Instant> plannedDeparture(String trainName, Integer index) {
      return indexed(departures, trainName, index, "departure");
    }

    Optional<Instant> plannedArrival(String trainName, Integer index) {
      return indexed(arrivals, trainName, index, "arrival");
    }

    private Optional<Instant> indexed(
        Map<String, Map<Integer, Instant>> source, String trainName, Integer index, String kind) {
      if (trainName == null || index == null) {
        return Optional.empty();
      }
      Map<Integer, Instant> byIndex = source.get(trainName);
      if (byIndex == null && !trains.containsKey(trainName)) {
        miss(kind + " train=" + trainName);
      }
      return byIndex == null ? Optional.empty() : Optional.ofNullable(byIndex.get(index));
    }

    List<NodeId> effectiveWaypoints(String trainName, RouteDefinition route) {
      RouteNodes nodes = trainName == null ? null : effective.get(trainName);
      return nodes != null && route != null && nodes.routeId().equals(route.id())
          ? nodes.nodes()
          : null;
    }

    boolean placed(String trainName, RouteDefinition route, int index) {
      RouteIndices indices = trainName == null ? null : placed.get(trainName);
      return indices != null
          && route != null
          && indices.routeId().equals(route.id())
          && indices.indices().contains(index);
    }

    Optional<NodeId> plannedPlatform(String trainName, RouteDefinition route, int index) {
      RoutePlatforms platforms = trainName == null ? null : planned.get(trainName);
      if (platforms == null || route == null || !platforms.routeId().equals(route.id())) {
        return Optional.empty();
      }
      return Optional.ofNullable(platforms.nodes().get(index));
    }

    Optional<NodeId> pinnedPlatform(SpawnTicket ticket, int index) {
      Map<Integer, NodeId> nodes = ticketPinned.get(ticket);
      return nodes == null ? Optional.empty() : Optional.ofNullable(nodes.get(index));
    }

    boolean atStation(String trainName, String routeKey, int routeIndex) {
      Presence fact = trainName == null ? null : presence.get(trainName);
      return fact != null
          && fact.routeIndex() == routeIndex
          && Objects.equals(fact.routeKey(), routeKey)
          && fact.atStation();
    }

    boolean remainsAt(String trainName, String routeKey, int routeIndex) {
      Presence fact = trainName == null ? null : remainders.get(trainName);
      return fact != null
          && fact.routeIndex() == routeIndex
          && Objects.equals(fact.routeKey(), routeKey)
          && fact.atStation();
    }

    Optional<EtaService.ThroughRoute> through(UUID routeUuid) {
      Optional<EtaService.ThroughRoute> found = routeUuid == null ? null : throughs.get(routeUuid);
      if (found == null) {
        if (routeUuid != null) {
          miss("through route=" + routeUuid);
        }
        return Optional.empty();
      }
      return found;
    }

    RouteLineChanges.LineRef canonicalLine(RouteLineChanges.LineRef line) {
      RouteLineChanges.LineRef canonical = line == null ? null : lines.get(line);
      if (canonical == null) {
        if (line != null) {
          miss("line=" + line);
        }
        return line;
      }
      return canonical;
    }

    Optional<StationDirectory.StationEntry> station(UUID stationId) {
      Optional<StationDirectory.StationEntry> entry =
          stationId == null ? null : stations.get(stationId);
      if (entry == null) {
        if (stationId != null) {
          miss("station=" + stationId);
        }
        return Optional.empty();
      }
      return entry;
    }

    TrainConfig trainConfig(
        UUID routeUuid, Optional<String> consist, ConfigManager.ConfigView config, Instant now) {
      TrainConfig known = trainConfigs.get(new ConsistKey(routeUuid, consist));
      if (known != null) {
        return known;
      }
      miss("consist route=" + routeUuid);
      ConfigManager.TrainConfigSettings settings = config.trainConfigSettings();
      ConfigManager.TrainTypeSettings motion = settings.forType(settings.defaultTrainType());
      return new TrainConfig(settings.defaultTrainType(), motion.accelBps2(), motion.decelBps2());
    }

    EtaSources.Tickets tickets() {
      return new EtaSources.Tickets() {
        @Override
        public List<SpawnTicket> pending() {
          return pending;
        }

        @Override
        public Optional<List<SpawnTicket>> forecast(
            Instant now, Duration requested, int maxPerService) {
          if (forecast.isPresent() && !Objects.equals(requested, window)) {
            miss("forecast window=" + requested);
            return Optional.empty();
          }
          return forecast;
        }
      };
    }

    DutyContinuitySupport continuity() {
      return new DutyContinuitySupport() {
        @Override
        public Optional<NextDeparture> nextDepartureOf(String trainName) {
          Optional<NextDeparture> next = trainName == null ? null : nextDepartures.get(trainName);
          if (next == null) {
            miss("next-departure train=" + trainName);
            return Optional.empty();
          }
          return next;
        }

        @Override
        public Optional<String> awaitedVehicleOf(SpawnTicket ticket) {
          Optional<String> vehicle = awaited.get(ticket);
          return vehicle == null ? Optional.empty() : vehicle;
        }

        @Override
        public Optional<String> plannedPlatformOf(SpawnTicket ticket, int stopIndex) {
          Map<Integer, String> nodes = ticketPlanned.get(ticket);
          return nodes == null ? Optional.empty() : Optional.ofNullable(nodes.get(stopIndex));
        }
      };
    }

    EtaSources.Layovers layovers() {
      return new EtaSources.Layovers() {
        @Override
        public List<LayoverRegistry.LayoverCandidate> all() {
          return layovers;
        }

        @Override
        public Optional<LayoverRegistry.LayoverCandidate> get(String trainName) {
          return trainName == null
              ? Optional.empty()
              : Optional.ofNullable(layoversByName.get(trainName));
        }

        @Override
        public List<LayoverRegistry.LayoverCandidate> candidatesAt(String terminalKey) {
          if (terminalKey == null) {
            return List.of();
          }
          return layovers.stream()
              .filter(c -> TerminalKeyResolver.matches(c.terminalKey(), terminalKey))
              .sorted(Comparator.comparing(LayoverRegistry.LayoverCandidate::readyAt))
              .toList();
        }
      };
    }
  }

  /** 列车在某交路上的实际节点（钩子返回 null 时 nodes 为 null）。 */
  private record RouteNodes(RouteId routeId, List<NodeId> nodes) {}

  /** 列车在某交路上已定站台的 DYNAMIC 下标。 */
  private record RouteIndices(RouteId routeId, Set<Integer> indices) {}

  /** 列车在某交路上各 DYNAMIC 下标的计划或暂定站台。 */
  private record RoutePlatforms(RouteId routeId, Map<Integer, NodeId> nodes) {
    private RoutePlatforms {
      nodes = Map.copyOf(nodes);
    }
  }

  /** 列车拷贝时的一个是非（是否停在本站、是否分行后留下的一段），及当时的交路与下标。 */
  private record Presence(String routeKey, int routeIndex, boolean atStation) {}

  /** 未发车票据的车型键。 */
  private record ConsistKey(UUID routeUuid, Optional<String> consist) {}

  /** 要拷线路规范写法的交路与它的线路。 */
  private record LineSource(RouteId routeId, RouteLineChanges.LineRef line) {}
}
