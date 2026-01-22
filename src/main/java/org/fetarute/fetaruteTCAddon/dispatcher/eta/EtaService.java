package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.cache.EtaCache;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.ArrivingClassifier;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.ClearanceModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.DwellModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.PathProgressModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.TravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.WaitEstimator;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModels;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteProgress;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainRuntimeState;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyPreviewSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnForecastSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;

/**
 * ETA 服务（唯一入口）。
 *
 * <p>职责：为 HUD/内部占位符提供结构化 ETA。
 *
 * <p>数据来源：
 *
 * <ul>
 *   <li>运行时列车快照：{@link TrainSnapshotStore}
 *   <li>未发车票据：{@link SpawnManager}/{@link TicketAssigner} 的队列快照
 * </ul>
 *
 * <p>约束：EtaService 不做运行时采样；只读 Snapshot + Graph/Route/Occupancy 快照 + Cache。
 *
 * <p>注意：站牌会合并未出票服务预测（见 {@link SpawnForecastSupport}）；若 SpawnMonitor 未运行或计划为空，站牌仍可能为空。
 *
 * <p>Layover：若起点存在待命列车，未发车 ETA 会使用候选的 readyAt 修正最早发车时间。
 */
public final class EtaService {

  private static final int FORECAST_LIMIT_PER_SERVICE = 5;

  private final TrainSnapshotStore snapshotStore;
  private final RailGraphService railGraphService;
  private final RouteDefinitionCache routeDefinitions;
  private final OccupancyManager occupancyManager;

  private final PathProgressModel pathProgressModel = new PathProgressModel();
  private final TravelTimeModel travelTimeModel =
      new TravelTimeModel(RailTravelTimeModels.constantSpeed(6.0));
  private final DwellModel dwellModel = new DwellModel();
  private final ClearanceModel clearanceModel = new ClearanceModel();
  private final ArrivingClassifier arrivingClassifier = new ArrivingClassifier();
  private final WaitEstimator waitEstimator;

  private final EtaCache<String, EtaResult> trainCache = new EtaCache<>(Duration.ofMillis(800));
  private final EtaCache<String, EtaResult> ticketCache = new EtaCache<>(Duration.ofMillis(1200));
  private final EtaCache<String, BoardResult> boardCache = new EtaCache<>(Duration.ofMillis(1500));

  private volatile SpawnManager spawnManager;
  private volatile TicketAssigner ticketAssigner;
  private volatile LayoverRegistry layoverRegistry;

  private final java.util.function.IntSupplier lookaheadEdges;
  private final java.util.function.IntSupplier minClearEdges;
  private final java.util.function.IntSupplier switcherZoneEdges;

  public EtaService(
      TrainSnapshotStore snapshotStore,
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      OccupancyManager occupancyManager,
      HeadwayRule headwayRule,
      java.util.function.IntSupplier lookaheadEdges,
      java.util.function.IntSupplier minClearEdges,
      java.util.function.IntSupplier switcherZoneEdges) {
    this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.waitEstimator = new WaitEstimator(Objects.requireNonNull(headwayRule, "headwayRule"), 30);
    this.lookaheadEdges = lookaheadEdges != null ? lookaheadEdges : () -> 2;
    this.minClearEdges = minClearEdges != null ? minClearEdges : () -> 0;
    this.switcherZoneEdges = switcherZoneEdges != null ? switcherZoneEdges : () -> 2;
  }

  /** 绑定票据来源（SpawnManager/TicketAssigner），用于未发车 ETA。 */
  public void attachTicketSources(SpawnManager spawnManager, TicketAssigner ticketAssigner) {
    this.spawnManager = spawnManager;
    this.ticketAssigner = ticketAssigner;
  }

  /** 绑定 LayoverRegistry，用于未发车 ETA 的待命时间修正。 */
  public void attachLayoverRegistry(LayoverRegistry layoverRegistry) {
    this.layoverRegistry = layoverRegistry;
  }

  /**
   * 查询指定列车的 ETA。
   *
   * @param trainName 列车名（trainId）
   * @param target 目标
   */
  public EtaResult getForTrain(String trainName, EtaTarget target) {
    Instant now = Instant.now();
    String key = trainName + "|" + (target == null ? "null" : target);
    return trainCache
        .getIfFresh(key, now)
        .orElseGet(
            () -> {
              EtaResult r = computeForTrain(trainName, target, now);
              trainCache.put(key, r, now);
              return r;
            });
  }

  /**
   * 预排班/未发车 ETA。
   *
   * <p>说明：仅基于已生成的票据（pending queue），不会读取 SpawnPlan 本身。
   */
  public EtaResult getForTicket(String ticketId) {
    Instant now = Instant.now();
    String key = ticketId == null ? "null" : ticketId.trim();
    return ticketCache
        .getIfFresh(key, now)
        .orElseGet(
            () -> {
              EtaResult r = computeForTicket(ticketId, now);
              ticketCache.put(key, r, now);
              return r;
            });
  }

  /**
   * 站牌列表：基于运行中列车 + 未发车票据聚合输出。
   *
   * <p>注意：stationId 支持两种输入：
   *
   * <ul>
   *   <li><code>StationCode</code>
   *   <li><code>Operator:StationCode</code>
   * </ul>
   *
   * <p>限制：
   *
   * <ul>
   *   <li>站牌会合并已生成票据与未出票预测（若 SpawnManager 支持预测）。
   *   <li>仅保留 ETA 落在 horizon 窗口内的行，窗口外会被过滤。
   *   <li>站点匹配依赖 RouteDefinition 的节点序列，若站点未映射到图节点会被忽略。
   * </ul>
   */
  public BoardResult getBoard(String stationId, String lineId, Duration horizon) {
    Instant now = Instant.now();
    String key =
        (stationId == null ? "null" : stationId.trim())
            + "|"
            + (lineId == null ? "null" : lineId.trim())
            + "|"
            + (horizon == null ? "null" : horizon.toString());
    return boardCache
        .getIfFresh(key, now)
        .orElseGet(
            () -> {
              BoardResult r = computeBoard(stationId, lineId, horizon, now);
              boardCache.put(key, r, now);
              return r;
            });
  }

  /**
   * 站牌列表（推荐形式）：显式传入 operator + stationCode，避免同名站点冲突。
   *
   * @param operator 运营商 code（全局唯一）
   * @param stationCode 站点 code（可能跨 operator 重名）
   */
  public BoardResult getBoard(
      String operator, String stationCode, String lineId, Duration horizon) {
    String stationId =
        operator == null || operator.isBlank()
            ? stationCode
            : operator.trim() + ":" + (stationCode == null ? "" : stationCode.trim());
    return getBoard(stationId, lineId, horizon);
  }

  /** 查询运行时采样快照（用于调试输出）。 */
  public Optional<TrainRuntimeSnapshot> getRuntimeSnapshot(String trainName) {
    return snapshotStore.getSnapshot(trainName);
  }

  /** 获取当前采样到的列车名集合（用于补全）。 */
  public Set<String> snapshotTrainNames() {
    return Set.copyOf(snapshotStore.snapshot().keySet());
  }

  /**
   * 供 /fta eta 命令 tab 补全使用：返回可见的 ticketId 候选。
   *
   * <p>来源包含：
   *
   * <ul>
   *   <li>运行中列车已绑定的 FTA_TICKET_ID
   *   <li>SpawnManager/TicketAssigner 队列中的待发车票据
   * </ul>
   */
  public List<String> suggestTicketIds() {
    Set<String> out = new HashSet<>();
    for (var entry : snapshotStore.snapshot().entrySet()) {
      TrainRuntimeSnapshot snap = entry.getValue();
      if (snap == null) {
        continue;
      }
      snap.ticketId().ifPresent(out::add);
    }
    for (SpawnTicket ticket : collectPendingTickets()) {
      if (ticket != null && ticket.id() != null) {
        out.add(ticket.id().toString());
      }
    }
    return out.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
  }

  /** 供 /fta eta 命令 tab 补全使用：返回已知 lineId 候选（来自运行中列车与待发车票据）。 */
  public List<String> suggestLineIds() {
    Set<String> out = new HashSet<>();
    for (TrainRuntimeSnapshot snap : snapshotStore.snapshot().values()) {
      if (snap == null) {
        continue;
      }
      routeDefinitions
          .findById(snap.routeUuid())
          .flatMap(RouteDefinition::metadata)
          .map(RouteMetadata::lineId)
          .filter(id -> id != null && !id.isBlank())
          .ifPresent(out::add);
    }
    for (SpawnTicket ticket : collectPendingTickets()) {
      if (ticket == null || ticket.service() == null) {
        continue;
      }
      String line = ticket.service().lineCode();
      if (line != null && !line.isBlank()) {
        out.add(line);
      }
    }
    return out.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
  }

  private EtaResult computeForTrain(String trainName, EtaTarget target, Instant now) {
    if (trainName == null || trainName.isBlank()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_VEHICLE));
    }
    Optional<TrainRuntimeSnapshot> snapOpt = snapshotStore.getSnapshot(trainName);
    if (snapOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_VEHICLE));
    }
    TrainRuntimeSnapshot snap = snapOpt.get();

    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(snap.routeUuid());
    if (routeOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_ROUTE));
    }
    RouteDefinition route = routeOpt.get();

    RailGraph graph =
        railGraphService
            .getSnapshot(snap.worldId())
            .map(
                org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService.RailGraphSnapshot
                    ::graph)
            .orElse(null);
    if (graph == null) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
    }

    Optional<TargetSelection> targetSelOpt =
        resolveTargetSelection(route, snap.routeIndex(), target);
    if (targetSelOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_TARGET));
    }
    TargetSelection targetSel = targetSelOpt.get();

    Optional<PathProgressModel.PathProgress> progressOpt =
        pathProgressModel.remainingToNode(graph, route, snap.routeIndex(), targetSel.nodeId());
    if (progressOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
    }

    PathProgressModel.PathProgress progress = progressOpt.get();
    Optional<Integer> travelSecOpt =
        travelTimeModel.estimateTravelSec(
            graph, progress.remainingNodes(), progress.remainingEdges());
    if (travelSecOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
    }

    int travelSec = travelSecOpt.get();
    int dwellSec = dwellModel.dwellSec(snap.dwellRemainingSec().orElse(null)).orElse(0);

    OccupancyDecision decision = buildAndCanEnter(graph, route, trainName, snap.routeIndex(), now);
    ClearanceModel.Clearance clearance = clearanceModel.classify(decision);

    Optional<OccupancyQueueSnapshot> queueSnapshot = Optional.empty();
    if (occupancyManager instanceof OccupancyQueueSupport qs && !decision.allowed()) {
      // MVP：取包含本车的队列快照（若找不到则取第一个）。
      queueSnapshot =
          qs.snapshotQueues().stream()
              .filter(
                  s ->
                      s.entries().stream().anyMatch(e -> e.trainName().equalsIgnoreCase(trainName)))
              .findFirst();
      if (queueSnapshot.isEmpty()) {
        queueSnapshot = qs.snapshotQueues().stream().findFirst();
      }
    }
    WaitEstimator.WaitEstimate wait =
        waitEstimator.estimate(trainName, decision, queueSnapshot, now);

    int waitSec = wait.waitSec();
    List<EtaReason> reasons = new ArrayList<>();
    reasons.addAll(clearance.reasons());
    reasons.addAll(wait.reasons());

    int remainingEdgeCount = progress.remainingEdgeCount();
    ArrivingClassifier.Arriving arriving =
        arrivingClassifier.classify(remainingEdgeCount, clearance.hardStop());
    if (!isApproachTarget(targetSel.nodeId()) && arriving.arriving()) {
      arriving = new ArrivingClassifier.Arriving(false, EtaConfidence.LOW);
    }

    long etaMillis = now.plusSeconds((long) travelSec + dwellSec + waitSec).toEpochMilli();
    int minutesRounded =
        (int) Math.max(0L, Math.round(((long) travelSec + dwellSec + waitSec) / 60.0));

    String statusText = arriving.arriving() ? "Arriving" : minutesRounded + "m";
    if (waitSec > 60) {
      statusText = "Delayed " + (waitSec / 60) + "m";
    }

    EtaConfidence conf = arriving.confidence();
    return new EtaResult(
        arriving.arriving(),
        statusText,
        etaMillis,
        minutesRounded,
        travelSec,
        dwellSec,
        waitSec,
        reasons,
        conf);
  }

  private EtaResult computeForTicket(String ticketId, Instant now) {
    if (ticketId == null || ticketId.isBlank()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_VEHICLE));
    }
    Optional<String> trainOpt = findTrainByTicketId(ticketId);
    if (trainOpt.isPresent()) {
      return computeForTrain(trainOpt.get(), EtaTarget.nextStop(), now);
    }
    Optional<SpawnTicket> ticketOpt = findSpawnTicket(ticketId);
    if (ticketOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_VEHICLE));
    }
    return computeForSpawnTicket(ticketOpt.get(), EtaTarget.nextStop(), now);
  }

  private BoardResult computeBoard(String stationId, String lineId, Duration horizon, Instant now) {
    if (stationId == null || stationId.isBlank()) {
      return new BoardResult(List.of());
    }
    Duration window = horizon == null ? Duration.ofMinutes(10) : horizon;
    Instant cutoff = now.plus(window);
    List<BoardRowEntry> rows = new ArrayList<>();

    for (var entry : snapshotStore.snapshot().entrySet()) {
      String trainName = entry.getKey();
      TrainRuntimeSnapshot snap = entry.getValue();
      if (trainName == null || snap == null) {
        continue;
      }
      Optional<RouteDefinition> routeOpt = routeDefinitions.findById(snap.routeUuid());
      if (routeOpt.isEmpty()) {
        continue;
      }
      RouteDefinition route = routeOpt.get();
      if (!lineMatches(route, lineId)) {
        continue;
      }
      EtaTarget stationTarget = new EtaTarget.Station(stationId);
      Optional<TargetSelection> targetOpt =
          resolveTargetSelection(route, snap.routeIndex(), stationTarget);
      if (targetOpt.isEmpty()) {
        continue;
      }
      EtaResult result = computeForTrain(trainName, stationTarget, now);
      if (result.etaEpochMillis() <= 0L || result.etaEpochMillis() > cutoff.toEpochMilli()) {
        continue;
      }
      TargetSelection target = targetOpt.get();
      String lineName = resolveLineName(route);
      String routeId = route.id().value();
      DestinationInfo destInfo = resolveDestinationInfo(route);
      String platform = resolvePlatform(target.nodeId());
      rows.add(
          new BoardRowEntry(
              result.etaEpochMillis(),
              new BoardResult.BoardRow(
                  lineName,
                  routeId,
                  destInfo.label(),
                  destInfo.destinationId(),
                  platform,
                  result.statusText(),
                  result.reasons())));
    }

    List<SpawnTicket> pendingTickets = collectPendingTickets();
    Set<String> reservedSlots = new HashSet<>();
    for (SpawnTicket ticket : pendingTickets) {
      RouteDefinition route = resolveRouteDefinition(ticket).orElse(null);
      buildTicketSlotKey(ticket, route).ifPresent(reservedSlots::add);
    }

    for (SpawnTicket ticket : pendingTickets) {
      buildBoardRowForTicket(ticket, stationId, lineId, now, cutoff).ifPresent(rows::add);
    }

    SpawnManager manager = this.spawnManager;
    if (manager instanceof SpawnForecastSupport forecastSupport) {
      Set<String> seenSlots = new HashSet<>(reservedSlots);
      List<SpawnTicket> forecastTickets =
          forecastSupport.snapshotForecast(now, window, FORECAST_LIMIT_PER_SERVICE);
      for (SpawnTicket ticket : forecastTickets) {
        RouteDefinition route = resolveRouteDefinition(ticket).orElse(null);
        Optional<String> slotKeyOpt = buildTicketSlotKey(ticket, route);
        if (slotKeyOpt.isPresent() && !seenSlots.add(slotKeyOpt.get())) {
          continue;
        }
        buildBoardRowForTicket(ticket, stationId, lineId, now, cutoff).ifPresent(rows::add);
      }
    }

    rows.sort(Comparator.comparingLong(BoardRowEntry::etaMillis));
    List<BoardResult.BoardRow> out = new ArrayList<>();
    for (BoardRowEntry entry : rows) {
      out.add(entry.row());
    }
    return new BoardResult(out);
  }

  private Optional<BoardRowEntry> buildBoardRowForTicket(
      SpawnTicket ticket, String stationId, String lineId, Instant now, Instant cutoff) {
    if (ticket == null || ticket.service() == null) {
      return Optional.empty();
    }
    if (lineId != null
        && !lineId.isBlank()
        && !ticket.service().lineCode().equalsIgnoreCase(lineId)) {
      return Optional.empty();
    }
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(ticket);
    if (routeOpt.isEmpty()) {
      return Optional.empty();
    }
    RouteDefinition route = routeOpt.get();
    EtaTarget stationTarget = new EtaTarget.Station(stationId);
    Optional<TargetSelection> targetOpt = resolveTargetSelection(route, -1, stationTarget);
    if (targetOpt.isEmpty()) {
      return Optional.empty();
    }
    EtaResult result = computeForSpawnTicket(ticket, stationTarget, now);
    if (result.etaEpochMillis() <= 0L || result.etaEpochMillis() > cutoff.toEpochMilli()) {
      return Optional.empty();
    }
    TargetSelection target = targetOpt.get();
    String lineName = ticket.service().lineCode();
    String routeId = route.id().value();
    DestinationInfo destInfo = resolveDestinationInfo(route);
    String platform = resolvePlatform(target.nodeId());
    return Optional.of(
        new BoardRowEntry(
            result.etaEpochMillis(),
            new BoardResult.BoardRow(
                lineName,
                routeId,
                destInfo.label(),
                destInfo.destinationId(),
                platform,
                result.statusText(),
                result.reasons())));
  }

  private Optional<String> buildTicketSlotKey(SpawnTicket ticket, RouteDefinition route) {
    if (ticket == null || ticket.service() == null || ticket.service().key() == null) {
      return Optional.empty();
    }
    Instant departAt =
        route != null ? resolveTicketDepartTime(ticket, route) : resolveTicketDepartTime(ticket);
    if (departAt == null) {
      return Optional.empty();
    }
    return Optional.of(ticket.service().key().routeId() + "|" + departAt.toEpochMilli());
  }

  private Optional<RouteDefinition> resolveRouteDefinition(SpawnTicket ticket) {
    if (ticket == null || ticket.service() == null) {
      return Optional.empty();
    }
    return routeDefinitions.findById(ticket.service().routeId());
  }

  private Optional<String> findTrainByTicketId(String ticketId) {
    if (ticketId == null || ticketId.isBlank()) {
      return Optional.empty();
    }
    for (var entry : snapshotStore.snapshot().entrySet()) {
      TrainRuntimeSnapshot snap = entry.getValue();
      if (snap == null) {
        continue;
      }
      Optional<String> snapTicket = snap.ticketId();
      if (snapTicket.isPresent() && snapTicket.get().equalsIgnoreCase(ticketId)) {
        return Optional.of(entry.getKey());
      }
    }
    return Optional.empty();
  }

  private Optional<SpawnTicket> findSpawnTicket(String ticketId) {
    if (ticketId == null || ticketId.isBlank()) {
      return Optional.empty();
    }
    UUID id;
    try {
      id = UUID.fromString(ticketId.trim());
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
    for (SpawnTicket ticket : collectPendingTickets()) {
      if (ticket != null && id.equals(ticket.id())) {
        return Optional.of(ticket);
      }
    }
    return Optional.empty();
  }

  private List<SpawnTicket> collectPendingTickets() {
    Set<UUID> seen = new HashSet<>();
    List<SpawnTicket> out = new ArrayList<>();
    SpawnManager manager = this.spawnManager;
    if (manager != null) {
      for (SpawnTicket ticket : manager.snapshotQueue()) {
        if (ticket == null) {
          continue;
        }
        if (seen.add(ticket.id())) {
          out.add(ticket);
        }
      }
    }
    TicketAssigner assigner = this.ticketAssigner;
    if (assigner != null) {
      for (SpawnTicket ticket : assigner.snapshotPendingTickets()) {
        if (ticket == null) {
          continue;
        }
        if (seen.add(ticket.id())) {
          out.add(ticket);
        }
      }
    }
    return out;
  }

  private EtaResult computeForSpawnTicket(SpawnTicket ticket, EtaTarget target, Instant now) {
    if (ticket == null || ticket.service() == null) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_VEHICLE));
    }
    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(ticket.service().routeId());
    if (routeOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_ROUTE));
    }
    RouteDefinition route = routeOpt.get();
    Optional<TargetSelection> targetSelOpt = resolveTargetSelection(route, -1, target);
    if (targetSelOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_TARGET));
    }
    TargetSelection targetSel = targetSelOpt.get();
    int remainingEdgeCount = 0;
    int travelSec = 0;
    int dwellSec = 0;

    if (targetSel.index() > 0) {
      Optional<RailGraph> graphOpt = resolveGraphForRouteSegment(route, 0, targetSel.index());
      if (graphOpt.isEmpty()) {
        return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
      }
      RailGraph graph = graphOpt.get();
      Optional<PathProgressModel.PathProgress> progressOpt =
          pathProgressModel.remainingToNode(graph, route, 0, targetSel.nodeId());
      if (progressOpt.isEmpty()) {
        return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
      }
      PathProgressModel.PathProgress progress = progressOpt.get();
      Optional<Integer> travelSecOpt =
          travelTimeModel.estimateTravelSec(
              graph, progress.remainingNodes(), progress.remainingEdges());
      if (travelSecOpt.isEmpty()) {
        return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
      }
      travelSec = travelSecOpt.get();
      remainingEdgeCount = progress.remainingEdgeCount();
    }

    Instant departAt = resolveTicketDepartTime(ticket, route);
    long waitSecLong = Math.max(0L, Duration.between(now, departAt).getSeconds());
    int waitSec = waitSecLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) waitSecLong;
    List<EtaReason> reasons = new ArrayList<>();
    if (waitSec > 0) {
      reasons.add(EtaReason.WAIT);
    }

    ArrivingClassifier.Arriving arriving = arrivingClassifier.classify(remainingEdgeCount, false);
    if (waitSec > 0 || !isApproachTarget(targetSel.nodeId())) {
      arriving = new ArrivingClassifier.Arriving(false, EtaConfidence.LOW);
    }

    long etaMillis = now.plusSeconds((long) travelSec + dwellSec + waitSec).toEpochMilli();
    int minutesRounded =
        (int) Math.max(0L, Math.round(((long) travelSec + dwellSec + waitSec) / 60.0));

    String statusText = minutesRounded + "m";
    if (waitSec > 60) {
      statusText = "Scheduled " + (waitSec / 60) + "m";
    }

    return new EtaResult(
        arriving.arriving(),
        statusText,
        etaMillis,
        minutesRounded,
        travelSec,
        dwellSec,
        waitSec,
        reasons,
        EtaConfidence.LOW);
  }

  private Optional<RailGraph> resolveGraphForRouteSegment(
      RouteDefinition route, int startIndex, int targetIndex) {
    if (route == null) {
      return Optional.empty();
    }
    List<NodeId> waypoints = route.waypoints();
    if (startIndex < 0 || targetIndex >= waypoints.size() || startIndex >= targetIndex) {
      return Optional.empty();
    }
    List<NodeId> segment = waypoints.subList(startIndex, targetIndex + 1);
    Optional<UUID> worldIdOpt = railGraphService.findWorldIdForPath(segment);
    if (worldIdOpt.isEmpty()) {
      return Optional.empty();
    }
    return railGraphService
        .getSnapshot(worldIdOpt.get())
        .map(
            org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService.RailGraphSnapshot
                ::graph);
  }

  private Instant resolveTicketDepartTime(SpawnTicket ticket) {
    if (ticket == null) {
      return Instant.EPOCH;
    }
    Instant due = ticket.dueAt();
    Instant notBefore = ticket.notBefore();
    if (notBefore != null && due != null && notBefore.isAfter(due)) {
      return notBefore;
    }
    return due != null ? due : Instant.EPOCH;
  }

  private Instant resolveTicketDepartTime(SpawnTicket ticket, RouteDefinition route) {
    Instant base = resolveTicketDepartTime(ticket);
    Optional<Instant> readyAt = resolveLayoverReadyAt(route);
    if (readyAt.isPresent() && readyAt.get().isAfter(base)) {
      return readyAt.get();
    }
    return base;
  }

  private Optional<Instant> resolveLayoverReadyAt(RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return Optional.empty();
    }
    LayoverRegistry registry = this.layoverRegistry;
    if (registry == null) {
      return Optional.empty();
    }
    NodeId startNode = route.waypoints().get(0);
    if (startNode == null) {
      return Optional.empty();
    }
    List<LayoverRegistry.LayoverCandidate> candidates = registry.findCandidates(startNode.value());
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    Instant readyAt = candidates.get(0).readyAt();
    return readyAt == null ? Optional.empty() : Optional.of(readyAt);
  }

  private record TargetSelection(NodeId nodeId, int index) {
    private TargetSelection {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  private Optional<TargetSelection> resolveTargetSelection(
      RouteDefinition route, int currentIndex, EtaTarget target) {
    if (route == null) {
      return Optional.empty();
    }
    List<NodeId> waypoints = route.waypoints();
    if (waypoints.isEmpty()) {
      return Optional.empty();
    }
    int startIndex = Math.max(-1, currentIndex);
    if (target == null || target instanceof EtaTarget.NextStop) {
      int next = startIndex + 1;
      if (next >= waypoints.size()) {
        return Optional.empty();
      }
      return Optional.of(new TargetSelection(waypoints.get(next), next));
    }
    if (target instanceof EtaTarget.PlatformNode pn) {
      return findNodeTarget(waypoints, startIndex + 1, pn.nodeId());
    }
    if (target instanceof EtaTarget.Station station) {
      return findStationTarget(waypoints, startIndex + 1, station.stationId(), route);
    }
    return Optional.empty();
  }

  private Optional<TargetSelection> findNodeTarget(
      List<NodeId> waypoints, int startIndex, NodeId target) {
    if (target == null || waypoints == null) {
      return Optional.empty();
    }
    for (int i = Math.max(0, startIndex); i < waypoints.size(); i++) {
      NodeId node = waypoints.get(i);
      if (node != null && node.equals(target)) {
        return Optional.of(new TargetSelection(node, i));
      }
    }
    return Optional.empty();
  }

  private Optional<TargetSelection> findStationTarget(
      List<NodeId> waypoints, int startIndex, String stationId, RouteDefinition route) {
    if (stationId == null || stationId.isBlank() || waypoints == null) {
      return Optional.empty();
    }
    StationKey key = parseStationKey(stationId, route);
    Optional<TargetSelection> exact = findExactNode(waypoints, startIndex, stationId);
    if (exact.isPresent()) {
      return exact;
    }
    Optional<TargetSelection> station =
        findStationByKind(waypoints, startIndex, key, WaypointKind.STATION);
    if (station.isPresent()) {
      return station;
    }
    return findStationByKind(waypoints, startIndex, key, WaypointKind.STATION_THROAT);
  }

  private Optional<TargetSelection> findExactNode(
      List<NodeId> waypoints, int startIndex, String rawId) {
    for (int i = Math.max(0, startIndex); i < waypoints.size(); i++) {
      NodeId node = waypoints.get(i);
      if (node == null) {
        continue;
      }
      if (node.value().equalsIgnoreCase(rawId)) {
        return Optional.of(new TargetSelection(node, i));
      }
    }
    return Optional.empty();
  }

  private Optional<TargetSelection> findStationByKind(
      List<NodeId> waypoints, int startIndex, StationKey key, WaypointKind kind) {
    for (int i = Math.max(0, startIndex); i < waypoints.size(); i++) {
      NodeId node = waypoints.get(i);
      if (node == null) {
        continue;
      }
      Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(node);
      if (metaOpt.isEmpty()) {
        continue;
      }
      WaypointMetadata meta = metaOpt.get();
      if (meta.kind() != kind) {
        continue;
      }
      if (!meta.originStation().equalsIgnoreCase(key.station())) {
        continue;
      }
      if (key.operator().isPresent() && !meta.operator().equalsIgnoreCase(key.operator().get())) {
        continue;
      }
      if (key.operator().isEmpty() && key.routeOperator().isPresent()) {
        if (!meta.operator().equalsIgnoreCase(key.routeOperator().get())) {
          continue;
        }
      }
      return Optional.of(new TargetSelection(node, i));
    }
    return Optional.empty();
  }

  private Optional<WaypointMetadata> parseWaypointMetadata(NodeId nodeId) {
    if (nodeId == null) {
      return Optional.empty();
    }
    return SignTextParser.parseWaypointLike(nodeId.value(), NodeType.WAYPOINT)
        .flatMap(SignNodeDefinition::waypointMetadata);
  }

  private boolean isApproachTarget(NodeId nodeId) {
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(nodeId);
    if (metaOpt.isEmpty()) {
      return false;
    }
    WaypointKind kind = metaOpt.get().kind();
    return kind == WaypointKind.STATION
        || kind == WaypointKind.STATION_THROAT
        || kind == WaypointKind.DEPOT
        || kind == WaypointKind.DEPOT_THROAT;
  }

  private StationKey parseStationKey(String raw, RouteDefinition route) {
    String trimmed = raw == null ? "" : raw.trim();
    String operator = null;
    String station = trimmed;
    int idx = trimmed.indexOf(':');
    if (idx > 0 && idx < trimmed.length() - 1) {
      operator = trimmed.substring(0, idx).trim();
      station = trimmed.substring(idx + 1).trim();
    }
    if (operator != null && !operator.isBlank()) {
      return new StationKey(Optional.of(operator), station, Optional.empty());
    }
    Optional<String> routeOp = resolveRouteOperator(route);
    return new StationKey(Optional.empty(), station, routeOp);
  }

  private Optional<String> resolveRouteOperator(RouteDefinition route) {
    if (route == null) {
      return Optional.empty();
    }
    Optional<RouteMetadata> metaOpt = route.metadata();
    if (metaOpt.isPresent() && metaOpt.get().operator() != null) {
      String op = metaOpt.get().operator();
      if (!op.isBlank()) {
        return Optional.of(op);
      }
    }
    return parseRouteId(route.id()).map(RouteCodeParts::operator);
  }

  private record StationKey(
      Optional<String> operator, String station, Optional<String> routeOperator) {
    private StationKey {
      operator = operator == null ? Optional.empty() : operator;
      routeOperator = routeOperator == null ? Optional.empty() : routeOperator;
    }
  }

  private boolean lineMatches(RouteDefinition route, String lineId) {
    if (lineId == null || lineId.isBlank()) {
      return true;
    }
    String expected = lineId.trim();
    if (route == null) {
      return false;
    }
    Optional<RouteMetadata> metaOpt = route.metadata();
    if (metaOpt.isPresent() && metaOpt.get().lineId() != null) {
      return metaOpt.get().lineId().equalsIgnoreCase(expected);
    }
    return parseRouteId(route.id())
        .map(parts -> parts.line().equalsIgnoreCase(expected))
        .orElse(false);
  }

  private String resolveLineName(RouteDefinition route) {
    if (route == null) {
      return "-";
    }
    Optional<RouteMetadata> metaOpt = route.metadata();
    if (metaOpt.isPresent() && metaOpt.get().lineId() != null) {
      return metaOpt.get().lineId();
    }
    return parseRouteId(route.id()).map(RouteCodeParts::line).orElse(route.id().value());
  }

  private String resolveDestination(RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return "-";
    }
    Optional<RouteMetadata> metaOpt = route.metadata();
    if (metaOpt.isPresent() && metaOpt.get().displayName().isPresent()) {
      return metaOpt.get().displayName().get();
    }
    Optional<RouteCodeParts> partsOpt = parseRouteId(route.id());
    if (partsOpt.isPresent()) {
      return partsOpt.get().route();
    }
    NodeId last = route.waypoints().get(route.waypoints().size() - 1);
    Optional<WaypointMetadata> meta = parseWaypointMetadata(last);
    if (meta.isPresent()) {
      return meta.get().originStation();
    }
    return last.value();
  }

  private DestinationInfo resolveDestinationInfo(RouteDefinition route) {
    String label = resolveDestination(route);
    Optional<String> destinationId = resolveDestinationId(route);
    return new DestinationInfo(label, destinationId);
  }

  private Optional<String> resolveDestinationId(RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return Optional.empty();
    }
    NodeId last = route.waypoints().get(route.waypoints().size() - 1);
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(last);
    if (metaOpt.isEmpty()) {
      return Optional.empty();
    }
    WaypointMetadata meta = metaOpt.get();
    String operator = meta.operator();
    if (operator == null || operator.isBlank()) {
      return Optional.empty();
    }
    String stationCode;
    if (meta.kind() == WaypointKind.INTERVAL && meta.destinationStation().isPresent()) {
      stationCode = meta.destinationStation().get();
    } else {
      stationCode = meta.originStation();
    }
    if (stationCode == null || stationCode.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(operator + ":" + stationCode);
  }

  private String resolvePlatform(NodeId nodeId) {
    if (nodeId == null) {
      return "-";
    }
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(nodeId);
    if (metaOpt.isPresent()) {
      return String.valueOf(metaOpt.get().trackNumber());
    }
    return "-";
  }

  private Optional<RouteCodeParts> parseRouteId(RouteId routeId) {
    if (routeId == null || routeId.value() == null) {
      return Optional.empty();
    }
    String[] parts = routeId.value().split(":");
    if (parts.length < 3) {
      return Optional.empty();
    }
    return Optional.of(new RouteCodeParts(parts[0].trim(), parts[1].trim(), parts[2].trim()));
  }

  private record RouteCodeParts(String operator, String line, String route) {}

  private record BoardRowEntry(long etaMillis, BoardResult.BoardRow row) {}

  private record DestinationInfo(String label, Optional<String> destinationId) {
    private DestinationInfo {
      label = label == null || label.isBlank() ? "-" : label;
      destinationId = destinationId == null ? Optional.empty() : destinationId;
    }
  }

  private OccupancyDecision buildAndCanEnter(
      RailGraph graph, RouteDefinition route, String trainName, int routeIndex, Instant now) {
    try {
      int lookahead = Math.max(1, lookaheadEdges.getAsInt());
      int minClear = Math.max(0, minClearEdges.getAsInt());
      int switcherZone = Math.max(0, switcherZoneEdges.getAsInt());
      OccupancyRequestBuilder builder =
          new OccupancyRequestBuilder(graph, lookahead, minClear, switcherZone);
      TrainRuntimeState state =
          new MinimalTrainRuntimeState(trainName, route.id(), Math.max(0, routeIndex), now);
      return builder
          .build(state, route, now)
          .map(
              request -> {
                if (occupancyManager instanceof OccupancyPreviewSupport preview) {
                  return preview.canEnterPreview(request);
                }
                return occupancyManager.canEnter(request);
              })
          .orElse(new OccupancyDecision(true, now, SignalAspect.PROCEED, List.of()));
    } catch (Throwable t) {
      return new OccupancyDecision(true, now, SignalAspect.PROCEED, List.of());
    }
  }

  private static final class MinimalTrainRuntimeState implements TrainRuntimeState {

    private final String trainName;
    private final org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId routeId;
    private final int currentIndex;
    private final Instant updatedAt;

    private MinimalTrainRuntimeState(
        String trainName,
        org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId routeId,
        int currentIndex,
        Instant updatedAt) {
      this.trainName = trainName;
      this.routeId = routeId;
      this.currentIndex = currentIndex;
      this.updatedAt = updatedAt;
    }

    @Override
    public String trainName() {
      return trainName;
    }

    @Override
    public RouteProgress routeProgress() {
      return new RouteProgress() {
        @Override
        public org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId routeId() {
          return routeId;
        }

        @Override
        public int currentIndex() {
          return currentIndex;
        }

        @Override
        public Optional<NodeId> nextTarget() {
          return Optional.empty();
        }
      };
    }

    @Override
    public Optional<NodeId> occupiedNode() {
      return Optional.empty();
    }

    @Override
    public Optional<Instant> estimatedArrivalTime() {
      return Optional.empty();
    }

    @Override
    public Instant lastUpdatedAt() {
      return updatedAt;
    }
  }
}
