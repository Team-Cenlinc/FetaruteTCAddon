package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainLoad;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsVocabulary;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeed;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsGeo;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsTime;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Alert;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.AlertCause;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.AlertEffect;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.AlertSeverity;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Entity;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.EntitySelector;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.FeedMessage;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Header;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.OccupancyStatus;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Position;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.StopScheduleRelationship;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.StopTimeEvent;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.StopTimeUpdate;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TimeRange;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TranslatedString;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Translation;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TripDescriptor;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TripScheduleRelationship;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TripUpdate;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.VehicleDescriptor;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.VehiclePosition;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.VehicleStopStatus;

/**
 * 把一份静态包（{@link GtfsFeed}）与一版运行时快照算成三种 GTFS-Realtime 报文。
 *
 * <p>三类记录的口径：
 *
 * <ul>
 *   <li><b>TripUpdates</b>：运行中车次给上一站的发车偏差、下一站的预计到达、动态选台与越站/半途取消的 {@code SKIPPED}；未开出的整趟取消另发 {@code
 *       CANCELED}。回库车越过运营终点之后不再发布。
 *   <li><b>VehiclePositions</b>：车头实际坐标与朝向（采样时记下），绑了车次就填 {@code trip_id} + {@code
 *       start_date}，没绑车次只填乘客线路；载客率取各节车座位与在座。
 *   <li><b>ServiceAlerts</b>：站台屏公告按运营商/线路/车站成组表达；线路运行状况只出停运、部分停运、严重延误与取消。
 * </ul>
 *
 * <p>直通运转的列车在同一时刻只属于一段 GTFS 车次：到达换线站（进度下标 ≥ 换线站下标）之后用 {@code ~2} 那一段。
 *
 * <p>所有查询都只读内存快照，可在 web 线程调用；单条记录算不出来时跳过它并记一条告警（同一条一轮只报一次，整轮最多五条），不让整份 feed
 * 失败。只有报文头都拼不出来这类整体错误才会让接口回 500。
 */
public final class GtfsRealtimeBuilder {

  /** 线路运行状况里 {@code Detail.FirstTrain} 的本地时刻写法。 */
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  /** 一轮生成里最多报几条坏记录。 */
  private static final int MAX_SKIP_WARNINGS = 5;

  private final GtfsFeed feed;
  private final GtfsRealtimeSnapshot snapshot;
  private final Instant now;
  private final Function<String, String> texts;
  private final Consumer<String> warn;
  private final ZoneId zone;

  /** 这一轮已经报过的坏记录：同一条只报一次，一轮最多 {@value #MAX_SKIP_WARNINGS} 条，避免刷屏。 */
  private final Set<String> warnedSkips = new HashSet<>();

  private final Map<String, TimetableApi.TrainAssignment> assignmentsByTrain = new HashMap<>();
  private final Map<String, TrainRuntimeSnapshot> trainsByName = new HashMap<>();
  private final Set<String> outOfService = new HashSet<>();
  private final Map<TripKey, TimetableApi.CancelledTrip> cancellations = new HashMap<>();

  /**
   * @param feed 静态包（提供 trip_id、stop_id 与版本号）
   * @param snapshot 运行时快照
   * @param now 生成时刻
   * @param texts 语言文件读取（线路运行状况文案复用站台屏）；可为 null，缺失时退回键名
   * @param warn 坏记录的告警出口；可为 null
   */
  public GtfsRealtimeBuilder(
      GtfsFeed feed,
      GtfsRealtimeSnapshot snapshot,
      Instant now,
      Function<String, String> texts,
      Consumer<String> warn) {
    this.feed = Objects.requireNonNull(feed, "feed");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.now = Objects.requireNonNull(now, "now");
    this.texts = texts == null ? key -> "" : texts;
    this.warn = warn == null ? message -> {} : warn;
    this.zone = feed.zone();
    index();
  }

  /** 兼容调用：不接告警出口（测试与只关心报文内容的调用方）。 */
  public GtfsRealtimeBuilder(
      GtfsFeed feed, GtfsRealtimeSnapshot snapshot, Instant now, Function<String, String> texts) {
    this(feed, snapshot, now, texts, null);
  }

  private void index() {
    for (TimetableApi.TrainAssignment assignment : snapshot.assignments()) {
      assignmentsByTrain.put(key(assignment.trainName()), assignment);
    }
    for (Map.Entry<String, TrainRuntimeSnapshot> entry : snapshot.trains().entrySet()) {
      trainsByName.put(key(entry.getKey()), entry.getValue());
    }
    for (String train : snapshot.outOfServiceTrains()) {
      outOfService.add(key(train));
    }
    for (TimetableApi.CancelledTrip cancellation : snapshot.cancellations()) {
      cancellations.put(
          new TripKey(cancellation.tripId(), cancellation.serviceDate()), cancellation);
    }
  }

  /** 生成 TripUpdates。 */
  public FeedMessage tripUpdates() {
    Map<String, Entity> entities = new TreeMap<>();
    Set<TripKey> running = new HashSet<>();
    List<TimetableApi.TrainAssignment> assignments =
        snapshot.assignments().stream()
            .sorted(Comparator.comparing(TimetableApi.TrainAssignment::trainName))
            .toList();
    for (TimetableApi.TrainAssignment assignment : assignments) {
      try {
        tripEntity(assignment, running)
            .ifPresent(entity -> entities.putIfAbsent(entity.id(), entity));
      } catch (RuntimeException ex) {
        skip("train:" + assignment.trainName(), ex);
      }
    }
    for (TimetableApi.CancelledTrip cancellation : snapshot.cancellations()) {
      try {
        cancellationEntities(cancellation, running, entities);
      } catch (RuntimeException ex) {
        skip("cancellation:" + cancellation.tripId(), ex);
      }
    }
    return message(entities.values());
  }

  private Optional<Entity> tripEntity(
      TimetableApi.TrainAssignment assignment, Set<TripKey> running) {
    if (outOfService(assignment.trainName())) {
      return Optional.empty();
    }
    Optional<GtfsFeed.Trip> trip = tripFor(assignment);
    if (trip.isEmpty()) {
      return Optional.empty();
    }
    running.add(new TripKey(trip.get().tripUuid(), assignment.serviceDate()));
    TripUpdate update = tripUpdate(trip.get(), assignment);
    String id = entityId("tu", trip.get(), assignment.serviceDate());
    return Optional.of(new Entity(id, Optional.of(update), Optional.empty(), Optional.empty()));
  }

  private void cancellationEntities(
      TimetableApi.CancelledTrip cancellation, Set<TripKey> running, Map<String, Entity> entities) {
    if (running.contains(new TripKey(cancellation.tripId(), cancellation.serviceDate()))) {
      return;
    }
    for (GtfsFeed.Trip segment : feed.trips(cancellation.timetableId(), cancellation.tripCode())) {
      String id = entityId("tu", segment, cancellation.serviceDate());
      if (entities.containsKey(id)) {
        continue;
      }
      cancellationUpdate(segment, cancellation)
          .ifPresent(
              update ->
                  entities.put(
                      id, new Entity(id, Optional.of(update), Optional.empty(), Optional.empty())));
    }
  }

  /** 生成 VehiclePositions。 */
  public FeedMessage vehiclePositions() {
    Map<String, Entity> entities = new TreeMap<>();
    List<String> names = new ArrayList<>(snapshot.trains().keySet());
    names.sort(Comparator.naturalOrder());
    for (String train : names) {
      try {
        vehicleEntity(train).ifPresent(entity -> entities.put(entity.id(), entity));
      } catch (RuntimeException ex) {
        skip("vehicle:" + train, ex);
      }
    }
    return message(entities.values());
  }

  private Optional<Entity> vehicleEntity(String train) {
    if (outOfService(train)) {
      return Optional.empty();
    }
    TrainRuntimeSnapshot runtime = snapshot.trains().get(train);
    if (runtime == null) {
      return Optional.empty();
    }
    TimetableApi.TrainAssignment assignment = assignmentsByTrain.get(key(train));
    Optional<GtfsFeed.Trip> trip = Optional.ofNullable(assignment).flatMap(this::tripFor);
    Optional<TripDescriptor> descriptor =
        trip.map(
                value ->
                    descriptor(value, assignment.serviceDate(), TripScheduleRelationship.SCHEDULED))
            .or(() -> routeOnlyDescriptor(runtime));
    String label = trip.map(GtfsFeed.Trip::shortName).filter(name -> !name.isBlank()).orElse(train);
    Optional<VehicleDescriptor> vehicle =
        Optional.of(new VehicleDescriptor(train, Optional.of(label)));
    StopProgress progress = stopProgress(runtime, trip, Optional.ofNullable(assignment));
    Occupancy occupancy = occupancy(runtime.load());
    VehiclePosition position =
        new VehiclePosition(
            descriptor,
            vehicle,
            position(runtime),
            progress.stopSequence(),
            progress.stopId(),
            progress.status(),
            runtime.updatedAt().getEpochSecond(),
            occupancy.status(),
            occupancy.percentage());
    String id = "vp:" + train;
    return Optional.of(new Entity(id, Optional.empty(), Optional.of(position), Optional.empty()));
  }

  /** 生成 ServiceAlerts。 */
  public FeedMessage alerts() {
    Map<String, Entity> entities = new TreeMap<>();
    addBulletinAlerts(entities);
    addLineStatusAlerts(entities);
    return message(entities.values());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // TripUpdates
  // ─────────────────────────────────────────────────────────────────────────────

  private Optional<GtfsFeed.Trip> tripFor(TimetableApi.TrainAssignment assignment) {
    int progress = assignment.lastStopSequence().orElse(-1);
    if (progress < 0) {
      progress = Math.max(0, assignment.nextStopSequence().orElse(0) - 1);
    }
    return feed.tripForRouteIndex(assignment.timetableId(), assignment.tripCode(), progress);
  }

  private TripUpdate tripUpdate(GtfsFeed.Trip trip, TimetableApi.TrainAssignment assignment) {
    OptionalInt delay = toOptionalInt(assignment.currentDelaySeconds());
    Optional<TimetableApi.CancelledTrip> cancellation =
        Optional.ofNullable(
            cancellations.get(new TripKey(trip.tripUuid(), assignment.serviceDate())));
    OptionalInt dwell =
        train(assignment.trainName())
            .flatMap(TrainRuntimeSnapshot::dwellRemainingSec)
            .map(OptionalInt::of)
            .orElse(OptionalInt.empty());
    Map<Integer, StopTimeUpdate> updates = new TreeMap<>();
    addLastStopUpdate(updates, trip, assignment, delay, dwell);
    addNextStopUpdate(updates, trip, assignment);
    addSkippedStops(updates, trip, assignment, cancellation);
    return new TripUpdate(
        descriptor(trip, assignment.serviceDate(), TripScheduleRelationship.SCHEDULED),
        Optional.of(new VehicleDescriptor(assignment.trainName(), Optional.of(trip.shortName()))),
        List.copyOf(updates.values()),
        now.getEpochSecond(),
        delay);
  }

  private void addLastStopUpdate(
      Map<Integer, StopTimeUpdate> updates,
      GtfsFeed.Trip trip,
      TimetableApi.TrainAssignment assignment,
      OptionalInt delay,
      OptionalInt dwell) {
    if (assignment.lastStopSequence().isEmpty()) {
      return;
    }
    int sequence = assignment.lastStopSequence().get();
    Optional<GtfsFeed.StopTime> stopTime = trip.stopTime(sequence);
    if (stopTime.isEmpty()) {
      return;
    }
    // 已经发生的事情按静态停靠点写；assigned_stop_id 表达的是「接下来会停哪」，不用于回溯
    Optional<String> stopId = Optional.of(stopTime.get().stopId());
    Optional<StopTimeEvent> departure;
    if (dwell.isPresent()) {
      departure =
          Optional.of(
              new StopTimeEvent(
                  OptionalInt.empty(), OptionalLong.of(now.getEpochSecond() + dwell.getAsInt())));
    } else if (delay.isPresent()) {
      departure = Optional.of(new StopTimeEvent(delay, OptionalLong.empty()));
    } else {
      departure = Optional.empty();
    }
    updates.put(
        sequence,
        new StopTimeUpdate(
            sequence,
            Optional.empty(),
            departure,
            stopId,
            StopScheduleRelationship.SCHEDULED,
            Optional.empty()));
  }

  private void addNextStopUpdate(
      Map<Integer, StopTimeUpdate> updates,
      GtfsFeed.Trip trip,
      TimetableApi.TrainAssignment assignment) {
    if (assignment.nextStopSequence().isEmpty()) {
      return;
    }
    int sequence = assignment.nextStopSequence().get();
    Optional<GtfsFeed.StopTime> stopTime = trip.stopTime(sequence);
    if (stopTime.isEmpty()) {
      return;
    }
    Optional<String> staticStopId = Optional.of(stopTime.get().stopId());
    Optional<String> actualStopId = stopIdForNode(assignment.nextStopNodeId());
    Optional<String> assignedStopId = actualStopId.filter(id -> !id.equals(staticStopId.get()));
    // 换台时规范首选只写 stop_sequence + assigned_stop_id，不写 stop_id：两者都写时它们必须相同，
    // 而把实际股道写进 stop_id 会让消费者按静态 stop_times 对不上这一站
    Optional<String> stopId = assignedStopId.isPresent() ? Optional.empty() : staticStopId;
    if (assignment.projectedDelaySeconds().isEmpty()) {
      if (assignedStopId.isPresent()) {
        // 还没算出到站预测，但站台已经选定：规范要求此时用 NO_DATA 只带 assigned_stop_id
        updates.put(
            sequence,
            new StopTimeUpdate(
                sequence,
                Optional.empty(),
                Optional.empty(),
                stopId,
                StopScheduleRelationship.NO_DATA,
                assignedStopId));
      }
      return;
    }
    long projected = assignment.projectedDelaySeconds().getAsLong();
    long base = serviceDayBase(assignment.serviceDate()).getEpochSecond();
    updates.put(
        sequence,
        new StopTimeUpdate(
            sequence,
            Optional.of(
                new StopTimeEvent(
                    OptionalInt.empty(),
                    OptionalLong.of(base + stopTime.get().arrivalSecondOfDay() + projected))),
            Optional.of(
                new StopTimeEvent(
                    OptionalInt.empty(),
                    OptionalLong.of(base + stopTime.get().departureSecondOfDay() + projected))),
            stopId,
            StopScheduleRelationship.SCHEDULED,
            assignedStopId));
  }

  private void addSkippedStops(
      Map<Integer, StopTimeUpdate> updates,
      GtfsFeed.Trip trip,
      TimetableApi.TrainAssignment assignment,
      Optional<TimetableApi.CancelledTrip> cancellation) {
    Set<Integer> skipped =
        snapshot
            .skippedStops()
            .skipped(trip.timetableId(), trip.tripUuid(), assignment.serviceDate());
    boolean partial =
        cancellation.isPresent()
            && cancellation.get().scope()
                == org.fetarute.fetaruteTCAddon.api.event.TimetableTripCancelledEvent.Scope.PARTIAL;
    for (GtfsFeed.StopTime stopTime : trip.stopTimes()) {
      int sequence = stopTime.stopSequence();
      boolean cancelledHere =
          partial && cancellation.get().firstCancelledStopSequence() <= sequence;
      if (!skipped.contains(sequence) && !cancelledHere) {
        continue;
      }
      updates.put(
          sequence,
          new StopTimeUpdate(
              sequence,
              Optional.empty(),
              Optional.empty(),
              Optional.of(stopTime.stopId()),
              StopScheduleRelationship.SKIPPED,
              Optional.empty()));
    }
  }

  private Optional<TripUpdate> cancellationUpdate(
      GtfsFeed.Trip trip, TimetableApi.CancelledTrip cancellation) {
    Optional<VehicleDescriptor> vehicle =
        cancellation
            .trainName()
            .map(name -> new VehicleDescriptor(name, Optional.of(trip.shortName())));
    if (cancellation.scope()
        == org.fetarute.fetaruteTCAddon.api.event.TimetableTripCancelledEvent.Scope.FULL) {
      return Optional.of(
          new TripUpdate(
              descriptor(trip, cancellation.serviceDate(), TripScheduleRelationship.CANCELED),
              vehicle,
              List.of(),
              now.getEpochSecond(),
              OptionalInt.empty()));
    }
    List<StopTimeUpdate> updates = new ArrayList<>();
    for (GtfsFeed.StopTime stopTime : trip.stopTimes()) {
      if (stopTime.stopSequence() < cancellation.firstCancelledStopSequence()) {
        continue;
      }
      updates.add(
          new StopTimeUpdate(
              stopTime.stopSequence(),
              Optional.empty(),
              Optional.empty(),
              Optional.of(stopTime.stopId()),
              StopScheduleRelationship.SKIPPED,
              Optional.empty()));
    }
    if (updates.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new TripUpdate(
            descriptor(trip, cancellation.serviceDate(), TripScheduleRelationship.SCHEDULED),
            vehicle,
            updates,
            now.getEpochSecond(),
            OptionalInt.empty()));
  }

  private TripDescriptor descriptor(
      GtfsFeed.Trip trip, LocalDate serviceDate, TripScheduleRelationship relationship) {
    return new TripDescriptor(
        Optional.of(trip.id()),
        Optional.empty(),
        Optional.of(GtfsTime.timeOfDay(trip.startSecondOfDay())),
        Optional.of(GtfsTime.date(serviceDate)),
        relationship);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // VehiclePositions
  // ─────────────────────────────────────────────────────────────────────────────

  private Optional<TripDescriptor> routeOnlyDescriptor(TrainRuntimeSnapshot runtime) {
    Optional<RouteLineChanges.LineRef> line = lineOf(runtime);
    return line.flatMap(ref -> feed.route(ref.operatorCode(), ref.lineCode()))
        .map(
            route ->
                new TripDescriptor(
                    Optional.empty(),
                    Optional.of(route.id()),
                    Optional.empty(),
                    Optional.empty(),
                    TripScheduleRelationship.SCHEDULED));
  }

  /** 乘客看到的线路：线路标签优先，没有标签时从交路 ID（{@code 运营商:线路:交路}）取前两段。 */
  private static Optional<RouteLineChanges.LineRef> lineOf(TrainRuntimeSnapshot runtime) {
    if (runtime.lineTag().isPresent()) {
      return runtime.lineTag();
    }
    String[] parts = runtime.routeId().value().split(":", 3);
    return parts.length < 2 ? Optional.empty() : RouteLineChanges.LineRef.of(parts[0], parts[1]);
  }

  /**
   * Minecraft 的 yaw（0 向南、90 向西）换成 GTFS 的 bearing（正北顺时针、0–360）。
   *
   * <p>采样读的是实体原始 yaw，可能落在 [-180, 180] 之外，直接取模会得到负数；用 {@link Math#floorMod} 归一。
   */
  static double bearing(float yaw) {
    return Math.floorMod((long) Math.round(yaw + 180.0), 360L);
  }

  private Optional<Position> position(TrainRuntimeSnapshot runtime) {
    return runtime
        .position()
        .map(
            sampled ->
                new Position(
                    GtfsGeo.latitude(sampled.z()),
                    GtfsGeo.longitude(sampled.x()),
                    OptionalDouble.of(bearing(sampled.yaw())),
                    runtime.currentSpeedBps().isPresent()
                        ? OptionalDouble.of(runtime.currentSpeedBps().getAsDouble())
                        : OptionalDouble.empty()));
  }

  private StopProgress stopProgress(
      TrainRuntimeSnapshot runtime,
      Optional<GtfsFeed.Trip> trip,
      Optional<TimetableApi.TrainAssignment> assignment) {
    if (trip.isEmpty() || assignment.isEmpty()) {
      return StopProgress.none();
    }
    GtfsFeed.Trip value = trip.get();
    TimetableApi.TrainAssignment bound = assignment.get();
    int last = bound.lastStopSequence().orElse(-1);
    if (runtime.dwellRemainingSec().isPresent() && last >= 0 && value.covers(last)) {
      return new StopProgress(
          OptionalInt.of(last),
          stopIdForNode(bound.lastStopNodeId())
              .or(() -> value.stopTime(last).map(GtfsFeed.StopTime::stopId)),
          VehicleStopStatus.STOPPED_AT);
    }
    if (bound.nextStopSequence().isPresent()) {
      int next = bound.nextStopSequence().get();
      if (value.covers(next)) {
        boolean incoming =
            runtime
                .currentNodeId()
                .map(NodeId::value)
                .filter(node -> bound.nextStopNodeId().filter(node::equals).isPresent())
                .isPresent();
        return new StopProgress(
            OptionalInt.of(next),
            stopIdForNode(bound.nextStopNodeId())
                .or(() -> value.stopTime(next).map(GtfsFeed.StopTime::stopId)),
            incoming ? VehicleStopStatus.INCOMING_AT : VehicleStopStatus.IN_TRANSIT_TO);
      }
    }
    if (last >= 0 && value.covers(last)) {
      return new StopProgress(
          OptionalInt.of(last),
          stopIdForNode(bound.lastStopNodeId())
              .or(() -> value.stopTime(last).map(GtfsFeed.StopTime::stopId)),
          VehicleStopStatus.IN_TRANSIT_TO);
    }
    return StopProgress.none();
  }

  private Occupancy occupancy(Optional<TrainLoad> load) {
    if (load.isEmpty()) {
      return Occupancy.none();
    }
    TrainLoad trainLoad = load.get();
    int seats = trainLoad.seats();
    if (seats <= 0) {
      return Occupancy.none();
    }
    int occupied = trainLoad.cars().stream().mapToInt(TrainLoad.Car::occupied).sum();
    int percentage = Math.max(0, Math.min(100, (int) Math.round(occupied * 100.0 / seats)));
    int vacant = Math.max(0, seats - occupied);
    OccupancyStatus status;
    if (occupied == 0) {
      status = OccupancyStatus.EMPTY;
    } else if (vacant * 2 >= seats) {
      status = OccupancyStatus.MANY_SEATS_AVAILABLE;
    } else if (vacant > 0) {
      status = OccupancyStatus.FEW_SEATS_AVAILABLE;
    } else {
      status = OccupancyStatus.FULL;
    }
    return new Occupancy(Optional.of(status), OptionalInt.of(percentage));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Alerts
  // ─────────────────────────────────────────────────────────────────────────────

  private void addBulletinAlerts(Map<String, Entity> entities) {
    List<PidsBulletin> bulletins =
        snapshot.bulletins().stream()
            .filter(bulletin -> bulletin.status(now) != PidsBulletin.Status.ENDED)
            .sorted(Comparator.comparing(bulletin -> bulletin.id().toString()))
            .toList();
    for (PidsBulletin bulletin : bulletins) {
      try {
        bulletinEntity(bulletin).ifPresent(entity -> entities.put(entity.id(), entity));
      } catch (RuntimeException ex) {
        skip("bulletin:" + bulletin.id(), ex);
      }
    }
  }

  private Optional<Entity> bulletinEntity(PidsBulletin bulletin) {
    Optional<GtfsFeed.Agency> agency = feed.agency(bulletin.operatorCode());
    if (agency.isEmpty()) {
      return Optional.empty();
    }
    String agencyId = agency.get().id();
    List<GtfsFeed.Route> lines = new ArrayList<>();
    for (String lineCode : bulletin.lines()) {
      feed.route(bulletin.operatorCode(), lineCode).ifPresent(lines::add);
    }
    List<GtfsFeed.Stop> stations = new ArrayList<>();
    for (String stationCode : bulletin.stations()) {
      feed.station(bulletin.operatorCode(), stationCode).ifPresent(stations::add);
    }
    boolean targeted = !bulletin.lines().isEmpty() || !bulletin.stations().isEmpty();
    List<EntitySelector> informed = bulletinSelectors(agencyId, lines, stations);
    if (informed.isEmpty()) {
      if (targeted) {
        // 限定到线路/车站的公告，若这些对象都不在本包里，宁可不出，也不能降级成全网告警
        return Optional.empty();
      }
      informed.add(new EntitySelector(Optional.of(agencyId), Optional.empty(), Optional.empty()));
    }
    Optional<TranslatedString> header = translated(bulletin.title());
    if (header.isEmpty()) {
      return Optional.empty();
    }
    String id = "alert:bulletin:" + bulletin.id();
    Alert alert =
        new Alert(
            activePeriods(bulletin.startsAt(), bulletin.endsAt()),
            informed,
            AlertCause.UNKNOWN_CAUSE,
            AlertEffect.UNKNOWN_EFFECT,
            header,
            translated(bulletin.body()),
            bulletin.important() ? AlertSeverity.WARNING : AlertSeverity.INFO);
    return Optional.of(new Entity(id, Optional.empty(), Optional.empty(), Optional.of(alert)));
  }

  /**
   * 公告的作用对象。
   *
   * <p>站台屏的公告同时写了线路与车站时，两者是「且」——只在这些线路停靠这些车站的屏幕上出现。同一个 {@link EntitySelector} 里写了 route 与 stop
   * 也按「且」解释，所以同时限定时要逐对组合，不能把线路选择器与车站选择器并排放（那是并集，会放宽到整条线或整座站）。
   */
  private static List<EntitySelector> bulletinSelectors(
      String agencyId, List<GtfsFeed.Route> lines, List<GtfsFeed.Stop> stations) {
    if (!lines.isEmpty() && !stations.isEmpty()) {
      Set<EntitySelector> selectors = new LinkedHashSet<>();
      for (GtfsFeed.Route route : lines) {
        for (GtfsFeed.Stop station : stations) {
          selectors.add(
              new EntitySelector(
                  Optional.of(agencyId), Optional.of(route.id()), Optional.of(station.id())));
        }
      }
      return List.copyOf(selectors);
    }
    List<EntitySelector> selectors = new ArrayList<>(lines.size() + stations.size());
    for (GtfsFeed.Route route : lines) {
      selectors.add(
          new EntitySelector(Optional.of(agencyId), Optional.of(route.id()), Optional.empty()));
    }
    for (GtfsFeed.Stop station : stations) {
      selectors.add(
          new EntitySelector(Optional.of(agencyId), Optional.empty(), Optional.of(station.id())));
    }
    return selectors;
  }

  private void addLineStatusAlerts(Map<String, Entity> entities) {
    PidsVocabulary vocabulary = new PidsVocabulary(texts);
    for (GtfsFeed.Route route : feed.routes()) {
      try {
        lineStatusEntity(route, vocabulary).ifPresent(entity -> entities.put(entity.id(), entity));
      } catch (RuntimeException ex) {
        skip("line-status:" + route.operatorCode() + ":" + route.lineCode(), ex);
      }
    }
  }

  private Optional<Entity> lineStatusEntity(GtfsFeed.Route route, PidsVocabulary vocabulary) {
    Optional<PidsLineStatus> statusOptional =
        snapshot.lineStatus().apply(route.operatorCode(), route.lineCode());
    if (statusOptional.isEmpty()) {
      return Optional.empty();
    }
    PidsLineStatus status = statusOptional.get();
    if (!alertable(status.condition())) {
      return Optional.empty();
    }
    Optional<GtfsFeed.Agency> agency = feed.agency(route.operatorCode());
    if (agency.isEmpty()) {
      return Optional.empty();
    }
    String agencyId = agency.get().id();
    List<EntitySelector> informed = new ArrayList<>();
    if (status.condition() == PidsLineStatus.Condition.PART_SUSPENDED
        && status.detail() instanceof PidsLineStatus.Detail.Closed closed
        && closed.section() != null) {
      informed.addAll(sectionSelectors(agencyId, route.id(), closed.section()));
    }
    if (informed.isEmpty()) {
      if (status.condition() == PidsLineStatus.Condition.PART_SUSPENDED) {
        // 标不出断点就不发：按整条线停运报比不报更误导
        return Optional.empty();
      }
      informed.add(
          new EntitySelector(Optional.of(agencyId), Optional.of(route.id()), Optional.empty()));
    }
    PidsView.Names condition = vocabulary.condition(status.condition());
    String lineName = route.longName();
    String lineSecondary = route.secondaryName().orElse(lineName);
    TranslatedString header =
        translatedString(
            new PidsView.Names(
                lineName + "：" + condition.primary(),
                lineSecondary + ": " + condition.secondary()));
    Optional<PidsView.Names> detail = detailText(status, vocabulary);
    String id =
        "alert:line-status:"
            + route.operatorCode()
            + ":"
            + route.lineCode()
            + ":"
            + status.condition().name();
    Alert alert =
        new Alert(
            List.of(),
            informed,
            status.condition() == PidsLineStatus.Condition.SUSPENDED
                ? AlertCause.MAINTENANCE
                : AlertCause.UNKNOWN_CAUSE,
            effect(status.condition()),
            Optional.of(header),
            detail.map(GtfsRealtimeBuilder::translatedString),
            status.condition() == PidsLineStatus.Condition.CANCELLATIONS
                ? AlertSeverity.INFO
                : AlertSeverity.WARNING);
    return Optional.of(new Entity(id, Optional.empty(), Optional.empty(), Optional.of(alert)));
  }

  private static boolean alertable(PidsLineStatus.Condition condition) {
    return switch (condition) {
      case SUSPENDED, PART_SUSPENDED, SEVERE_DELAYS, CANCELLATIONS -> true;
      case MINOR_DELAYS, GOOD, ENDED, NO_TRAINS -> false;
    };
  }

  private static AlertEffect effect(PidsLineStatus.Condition condition) {
    return switch (condition) {
      case SUSPENDED, PART_SUSPENDED -> AlertEffect.NO_SERVICE;
      case SEVERE_DELAYS -> AlertEffect.SIGNIFICANT_DELAYS;
      case CANCELLATIONS -> AlertEffect.REDUCED_SERVICE;
      case MINOR_DELAYS, GOOD, ENDED, NO_TRAINS -> AlertEffect.UNKNOWN_EFFECT;
    };
  }

  private List<EntitySelector> sectionSelectors(
      String agencyId,
      String routeId,
      org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory.Section section) {
    List<EntitySelector> out = new ArrayList<>(2);
    for (String stationKey : List.of(section.from(), section.to())) {
      stationSelector(agencyId, routeId, stationKey).ifPresent(out::add);
    }
    return out;
  }

  private Optional<EntitySelector> stationSelector(
      String agencyId, String routeId, String stationKey) {
    String[] parts = stationKey.split(":", 2);
    if (parts.length < 2) {
      return Optional.empty();
    }
    return feed.station(parts[0], parts[1])
        .map(
            station ->
                new EntitySelector(
                    Optional.of(agencyId), Optional.of(routeId), Optional.of(station.id())));
  }

  private Optional<PidsView.Names> detailText(PidsLineStatus status, PidsVocabulary vocabulary) {
    if (status.condition() == PidsLineStatus.Condition.SUSPENDED) {
      return Optional.of(vocabulary.maintenance());
    }
    return switch (status.detail()) {
      case PidsLineStatus.Detail.None none -> Optional.empty();
      case PidsLineStatus.Detail.Late late -> Optional.of(vocabulary.lateUpTo(late.minutes()));
      case PidsLineStatus.Detail.Cancelled cancelled -> Optional.of(
          vocabulary.cancelledTrips(cancelled.trips()));
      case PidsLineStatus.Detail.Closed closed -> Optional.of(
          vocabulary.sectionClosed(
              stationNames(closed.section().from()), stationNames(closed.section().to())));
      case PidsLineStatus.Detail.FirstTrain first -> Optional.of(
          vocabulary.firstTrain(CLOCK.format(first.at().atZone(zone))));
    };
  }

  private PidsView.Names stationNames(String stationKey) {
    String[] parts = stationKey.split(":", 2);
    if (parts.length < 2) {
      return new PidsView.Names(stationKey, "");
    }
    return feed.station(parts[0], parts[1])
        .map(station -> new PidsView.Names(station.name(), station.secondaryName().orElse("")))
        .orElseGet(() -> new PidsView.Names(parts[1], ""));
  }

  private static List<TimeRange> activePeriods(
      Optional<Instant> startsAt, Optional<Instant> endsAt) {
    if (startsAt.isEmpty() && endsAt.isEmpty()) {
      return List.of();
    }
    return List.of(new TimeRange(toOptionalLong(startsAt), toOptionalLong(endsAt)));
  }

  private static OptionalLong toOptionalLong(Optional<Instant> value) {
    return value == null || value.isEmpty()
        ? OptionalLong.empty()
        : OptionalLong.of(value.get().getEpochSecond());
  }

  private Optional<TranslatedString> translated(PidsBulletin.Text text) {
    if (text == null || text.primary().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(translatedString(new PidsView.Names(text.primary(), text.secondary())));
  }

  private static TranslatedString translatedString(PidsView.Names names) {
    List<Translation> translations = new ArrayList<>(2);
    translations.add(new Translation(names.primary(), Optional.of("zh")));
    if (!names.secondary().isBlank() && !names.secondary().equalsIgnoreCase(names.primary())) {
      translations.add(new Translation(names.secondary(), Optional.of("en")));
    }
    return new TranslatedString(translations);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 工具
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * 记一条坏记录并继续生成：同一条一轮只报一次，整轮最多 {@link #MAX_SKIP_WARNINGS} 条。
   *
   * <p>只影响这一条记录，其它车次、车辆、告警照常发布；告警里只带记录 key 与异常类型，不拼异常消息，避免同一原因每轮都换一个字符串。
   */
  private void skip(String key, RuntimeException ex) {
    if (warnedSkips.size() >= MAX_SKIP_WARNINGS || !warnedSkips.add(key)) {
      return;
    }
    warn.accept("GTFS-RT 跳过 " + key + "：" + ex.getClass().getSimpleName());
  }

  private FeedMessage message(Collection<Entity> entities) {
    return new FeedMessage(
        new Header(GtfsRealtimeFeed.VERSION_2, now.getEpochSecond(), Optional.of(feed.version())),
        List.copyOf(entities));
  }

  private Optional<TrainRuntimeSnapshot> train(String trainName) {
    return Optional.ofNullable(trainsByName.get(key(trainName)));
  }

  private boolean outOfService(String trainName) {
    return outOfService.contains(key(trainName));
  }

  private Optional<String> stopIdForNode(Optional<String> nodeId) {
    if (nodeId == null || nodeId.isEmpty()) {
      return Optional.empty();
    }
    String node = nodeId.get();
    return feed.stopIdOfNode(node)
        .or(
            () ->
                RouteTerminals.stationRefOfNode(node)
                    .flatMap(ref -> feed.station(ref.operatorCode(), ref.stationCode()))
                    .flatMap(station -> feed.dynamicStopId(station.id())));
  }

  private Instant serviceDayBase(LocalDate date) {
    return date.atStartOfDay(zone).toInstant();
  }

  private static OptionalInt toOptionalInt(OptionalLong value) {
    if (value == null || value.isEmpty()) {
      return OptionalInt.empty();
    }
    long seconds = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value.getAsLong()));
    return OptionalInt.of((int) seconds);
  }

  private static String entityId(String prefix, GtfsFeed.Trip trip, LocalDate serviceDate) {
    return prefix + ":" + trip.id() + ":" + GtfsTime.date(serviceDate);
  }

  private static String key(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private record TripKey(UUID tripId, LocalDate serviceDate) {}

  private record StopProgress(
      OptionalInt stopSequence, Optional<String> stopId, VehicleStopStatus status) {

    static StopProgress none() {
      return new StopProgress(
          OptionalInt.empty(), Optional.empty(), VehicleStopStatus.IN_TRANSIT_TO);
    }
  }

  private record Occupancy(Optional<OccupancyStatus> status, OptionalInt percentage) {

    static Occupancy none() {
      return new Occupancy(Optional.empty(), OptionalInt.empty());
    }
  }
}
