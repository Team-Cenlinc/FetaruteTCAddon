package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.CarriageDetails;
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
 *       start_date}，没绑车次只填乘客线路；载客率取各节车座位与在座。出库途中、还没到第一个载客站的车不发布位置。
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

  /** 推算后续车次会不会晚开，只看表定发车在这么久之内的。 */
  static final Duration FOLLOWING_HORIZON = Duration.ofHours(3);

  private final GtfsFeed feed;
  private final GtfsRealtimeSnapshot snapshot;
  private final Instant now;
  private final Function<String, String> texts;
  private final Consumer<String> warn;
  private final ZoneId zone;
  private final FrequencyStarts starts;

  /** 这一轮已经报过的坏记录：同一条只报一次，一轮最多 {@value #MAX_SKIP_WARNINGS} 条，避免刷屏。 */
  private final Set<String> warnedSkips = new HashSet<>();

  private final Map<String, TimetableApi.TrainAssignment> assignmentsByTrain = new HashMap<>();
  private final Map<String, TrainRuntimeSnapshot> trainsByName = new HashMap<>();
  private final Set<String> outOfService = new HashSet<>();

  /** 出库途中、还没到第一个载客站的车：不发布车辆位置（到站预测照常）。 */
  private final Set<String> beforeService = new HashSet<>();

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
    this(feed, snapshot, now, texts, warn, FrequencyStarts.unpinned());
  }

  /**
   * @param starts 按间隔发车车次的起点发车时刻登记（同一辆车的同一趟一经报出就不再变）
   */
  public GtfsRealtimeBuilder(
      GtfsFeed feed,
      GtfsRealtimeSnapshot snapshot,
      Instant now,
      Function<String, String> texts,
      Consumer<String> warn,
      FrequencyStarts starts) {
    this.starts = Objects.requireNonNull(starts, "starts");
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
    for (String train : snapshot.beforeServiceTrains()) {
      beforeService.add(key(train));
    }
    for (TimetableApi.CancelledTrip cancellation : snapshot.cancellations()) {
      cancellations.put(
          new TripKey(cancellation.tripId(), cancellation.serviceDate()), cancellation);
    }
  }

  /** 生成 TripUpdates。 */
  public FeedMessage tripUpdates() {
    Map<String, Entity> entities = new TreeMap<>();
    Set<String> running = new HashSet<>();
    List<Entity> downstream = new ArrayList<>();
    List<TimetableApi.TrainAssignment> assignments =
        snapshot.assignments().stream()
            .sorted(Comparator.comparing(TimetableApi.TrainAssignment::trainName))
            .toList();
    for (TimetableApi.TrainAssignment assignment : assignments) {
      try {
        tripEntity(assignment, running, downstream)
            .ifPresent(entity -> entities.putIfAbsent(entity.id(), entity));
      } catch (RuntimeException ex) {
        skip("train:" + assignment.trainName(), ex);
      }
    }
    for (String train : sortedTrains()) {
      try {
        headwayTripEntity(train).ifPresent(entity -> entities.putIfAbsent(entity.id(), entity));
      } catch (RuntimeException ex) {
        skip("headway:" + train, ex);
      }
    }
    for (TimetableApi.CancelledTrip cancellation : snapshot.cancellations()) {
      try {
        cancellationEntities(cancellation, running, entities);
      } catch (RuntimeException ex) {
        skip("cancellation:" + cancellation.tripId(), ex);
      }
    }
    // 车所在段之后的各段、交路里接下来的车次：都是推算，正在跑的车与取消登记优先
    for (Entity entity : downstream) {
      if (!running.contains(entity.id())) {
        entities.putIfAbsent(entity.id(), entity);
      }
    }
    return message(entities.values());
  }

  /**
   * 运行中车次的更新。车所在的那一段登记进 {@code running}（按分段 ID + 日期）：直通车的其它段照常按取消登记发布。
   *
   * <p>一条站点更新都给不出来（上一站不在静态包里、预测也还没算出）时不发这一条：未取消的车次至少要有一条站点更新。 车所在段之后的各段、交路里接下来会晚开的车次，推算结果放进 {@code
   * downstream}。
   */
  private Optional<Entity> tripEntity(
      TimetableApi.TrainAssignment assignment, Set<String> running, List<Entity> downstream) {
    if (outOfService(assignment.trainName())) {
      return Optional.empty();
    }
    Optional<GtfsFeed.Trip> trip = tripFor(assignment);
    if (trip.isEmpty()) {
      return Optional.empty();
    }
    String id = entityId("tu", trip.get(), assignment.serviceDate());
    running.add(id);
    Run run = currentRun(trip.get(), assignment);
    if (run.arrivalAtSegmentEnd().isPresent()) {
      addDownstream(assignment, trip.get(), run.arrivalAtSegmentEnd().getAsLong(), downstream);
    } else {
      arrivedAtTripEnd(assignment, trip.get())
          .ifPresent(arrival -> addFollowing(assignment, arrival, downstream));
    }
    if (run.update().stopTimeUpdates().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Entity(id, Optional.of(run.update()), Optional.empty(), Optional.empty()));
  }

  private void cancellationEntities(
      TimetableApi.CancelledTrip cancellation, Set<String> running, Map<String, Entity> entities) {
    for (GtfsFeed.Trip segment : feed.trips(cancellation.timetableId(), cancellation.tripCode())) {
      String id = entityId("tu", segment, cancellation.serviceDate());
      if (running.contains(id) || entities.containsKey(id)) {
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
    for (String train : sortedTrains()) {
      try {
        vehicleEntity(train).ifPresent(entity -> entities.put(entity.id(), entity));
      } catch (RuntimeException ex) {
        skip("vehicle:" + train, ex);
      }
    }
    return message(entities.values());
  }

  private Optional<Entity> vehicleEntity(String train) {
    if (outOfService(train) || beforeService.contains(key(train))) {
      return Optional.empty();
    }
    TrainRuntimeSnapshot runtime = snapshot.trains().get(train);
    if (runtime == null) {
      return Optional.empty();
    }
    TimetableApi.TrainAssignment assignment = assignmentsByTrain.get(key(train));
    Optional<GtfsFeed.Trip> trip = Optional.ofNullable(assignment).flatMap(this::tripFor);
    Optional<GtfsFeed.Trip> headway = trip.isPresent() ? Optional.empty() : headwayTripOf(runtime);
    Optional<TripDescriptor> descriptor =
        trip.map(
                value ->
                    descriptor(value, assignment.serviceDate(), TripScheduleRelationship.SCHEDULED))
            .or(() -> headway.map(value -> headwayDescriptor(train, runtime, value)))
            .or(() -> routeOnlyDescriptor(runtime));
    String label = trip.map(GtfsFeed.Trip::shortName).filter(name -> !name.isBlank()).orElse(train);
    Optional<VehicleDescriptor> vehicle =
        Optional.of(new VehicleDescriptor(train, Optional.of(label)));
    StopProgress progress =
        headway.isPresent()
            ? headwayProgress(runtime, headway.get())
            : stopProgress(runtime, trip, Optional.ofNullable(assignment));
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
            occupancy.percentage(),
            carriages(train, runtime.load()));
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

  /** 车所在这一段的更新，以及推算到这一段终点的预计到达（推算不出时为空）。 */
  private Run currentRun(GtfsFeed.Trip trip, TimetableApi.TrainAssignment assignment) {
    OptionalInt delay = toOptionalInt(assignment.currentDelaySeconds());
    OptionalInt dwell =
        train(assignment.trainName())
            .flatMap(TrainRuntimeSnapshot::dwellRemainingSec)
            .map(OptionalInt::of)
            .orElse(OptionalInt.empty());
    Set<Integer> skipped = skippedOf(trip, assignment.serviceDate());
    int firstCancelled = firstCancelledOf(trip, assignment.serviceDate());
    Map<Integer, StopTimeUpdate> updates = new TreeMap<>();
    addLastStopUpdate(updates, trip, assignment, delay, dwell);
    OptionalLong arrivalAtEnd = addPredictions(updates, trip, assignment, skipped, firstCancelled);
    addSkippedStops(updates, trip, skipped, firstCancelled);
    TripUpdate update =
        new TripUpdate(
            descriptor(trip, assignment.serviceDate(), TripScheduleRelationship.SCHEDULED),
            Optional.of(
                new VehicleDescriptor(assignment.trainName(), Optional.of(trip.shortName()))),
            List.copyOf(updates.values()),
            now.getEpochSecond(),
            delay);
    return new Run(update, arrivalAtEnd);
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
      // 停站中：剩余停站时间与表定发车取较晚者——按表等点时车不会早于表定开出
      long scheduled =
          serviceDayBase(assignment.serviceDate()).getEpochSecond()
              + stopTime.get().departureSecondOfDay();
      departure =
          Optional.of(
              new StopTimeEvent(
                  OptionalInt.empty(),
                  OptionalLong.of(Math.max(now.getEpochSecond() + dwell.getAsInt(), scheduled))));
    } else if (delay.isPresent()) {
      departure = Optional.of(new StopTimeEvent(delay, OptionalLong.empty()));
    } else {
      // 既没有到发时刻也没有偏差：这一条不带信息，规范也不允许 SCHEDULED 的站点更新空着到发
      return;
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

  /**
   * 下一站起这一段剩下各站的预计到发：下一站按 ETA 的预计晚点，往后按表定站间时分推；按表等点，车不早于表定发车开出（早到的车在站上等）。
   *
   * <p>表定站间时分与 ETA 用的是同一个走行模型（编表按运行曲线算），所以往后推不必每站再算一次路径。
   *
   * @return 推算到这一段终点的预计到达；下一站没有预测时为空
   */
  private OptionalLong addPredictions(
      Map<Integer, StopTimeUpdate> updates,
      GtfsFeed.Trip trip,
      TimetableApi.TrainAssignment assignment,
      Set<Integer> skipped,
      int firstCancelled) {
    if (assignment.nextStopSequence().isEmpty()) {
      return OptionalLong.empty();
    }
    int sequence = assignment.nextStopSequence().get();
    int index = indexOf(trip, sequence);
    if (index < 0) {
      return OptionalLong.empty();
    }
    GtfsFeed.StopTime stopTime = trip.stopTimes().get(index);
    Optional<String> staticStopId = Optional.of(stopTime.stopId());
    // 只认已选定的股道：动态站台未选台时车次绑定给的是占位股道（与真实起始股道同名），不能当成实际站台
    Optional<String> actualStopId = stopIdForNode(placedNode(assignment.trainName(), sequence));
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
      return OptionalLong.empty();
    }
    long base = serviceDayBase(assignment.serviceDate()).getEpochSecond();
    long arrival =
        base + stopTime.arrivalSecondOfDay() + assignment.projectedDelaySeconds().getAsLong();
    Propagated propagated =
        propagate(trip, base, index, arrival, true, skipped, firstCancelled, true);
    for (StopTimeUpdate update : propagated.updates()) {
      StopTimeUpdate written = update;
      if (update.stopSequence() == sequence
          && update.scheduleRelationship() == StopScheduleRelationship.SCHEDULED) {
        written =
            new StopTimeUpdate(
                sequence,
                update.arrival(),
                update.departure(),
                stopId,
                StopScheduleRelationship.SCHEDULED,
                assignedStopId);
      }
      updates.put(written.stopSequence(), written);
    }
    return OptionalLong.of(propagated.lastArrival());
  }

  private void addSkippedStops(
      Map<Integer, StopTimeUpdate> updates,
      GtfsFeed.Trip trip,
      Set<Integer> skipped,
      int firstCancelled) {
    for (GtfsFeed.StopTime stopTime : trip.stopTimes()) {
      int sequence = stopTime.stopSequence();
      if (!skipped.contains(sequence) && sequence < firstCancelled) {
        continue;
      }
      updates.put(sequence, skippedUpdate(stopTime));
    }
  }

  /**
   * 车所在段之后的推算：直通车的后几段接着往后推；交路里接下来的车次，前一趟预计到终点加终点的计划停站晚于表定发车时，按晚开往后推。
   *
   * <p>后续车次只看 {@link #FOLLOWING_HORIZON} 之内、而且确实会晚开的：准点的车次不用发更新，前一趟准点后面也就都准点。
   */
  private void addDownstream(
      TimetableApi.TrainAssignment assignment,
      GtfsFeed.Trip current,
      long arrivalAtSegmentEnd,
      List<Entity> downstream) {
    long arrival = arrivalAtSegmentEnd;
    long base = serviceDayBase(assignment.serviceDate()).getEpochSecond();
    Set<Integer> skipped = skippedOf(current, assignment.serviceDate());
    int firstCancelled = firstCancelledOf(current, assignment.serviceDate());
    for (GtfsFeed.Trip segment : feed.trips(assignment.timetableId(), assignment.tripCode())) {
      if (segment.segment() <= current.segment()) {
        continue;
      }
      Propagated propagated =
          propagate(segment, base, 0, arrival, true, skipped, firstCancelled, true);
      downstream.add(
          predictedEntity(
              segment,
              assignment.serviceDate(),
              assignment.trainName(),
              propagated,
              OptionalInt.empty()));
      arrival = propagated.lastArrival();
    }
    addFollowing(assignment, arrival, downstream);
  }

  /**
   * 车已经停在本趟最后一站（没有下一站了）时它的实际到达：表定到达加上一次到发的偏差。车在终点停站、待命时正是终点站候车的乘客 最想知道下一趟会不会晚开的时候。
   *
   * @return 实际到达（epoch 秒）；车不在本趟最后一站、或没有偏差记录时为空
   */
  private OptionalLong arrivedAtTripEnd(
      TimetableApi.TrainAssignment assignment, GtfsFeed.Trip trip) {
    if (assignment.nextStopSequence().isPresent()
        || assignment.lastStopSequence().isEmpty()
        || assignment.currentDelaySeconds().isEmpty()) {
      return OptionalLong.empty();
    }
    List<GtfsFeed.Trip> segments = feed.trips(assignment.timetableId(), assignment.tripCode());
    List<GtfsFeed.StopTime> stops = trip.stopTimes();
    if (segments.isEmpty()
        || !segments.get(segments.size() - 1).id().equals(trip.id())
        || stops.isEmpty()
        || stops.get(stops.size() - 1).stopSequence() != assignment.lastStopSequence().get()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(
        serviceDayBase(assignment.serviceDate()).getEpochSecond()
            + stops.get(stops.size() - 1).arrivalSecondOfDay()
            + assignment.currentDelaySeconds().getAsLong());
  }

  /**
   * 交路里接下来的车次：前一趟到最后一个载客站（预计或实际）加准备时间晚于表定发车时，按晚开往后推；最早也不会早于现在。
   *
   * @param arrival 前一趟到最后一个载客站的时刻（epoch 秒）
   */
  private void addFollowing(
      TimetableApi.TrainAssignment assignment, long arrival, List<Entity> downstream) {
    long horizon = now.getEpochSecond() + FOLLOWING_HORIZON.toSeconds();
    for (GtfsRealtimeSnapshot.FollowingTrip next :
        snapshot
            .followingTrips()
            .following(assignment.timetableId(), assignment.tripCode(), assignment.serviceDate())) {
      List<GtfsFeed.Trip> segments = feed.trips(assignment.timetableId(), next.tripCode());
      if (segments.isEmpty() || segments.get(0).stopTimes().isEmpty()) {
        return;
      }
      long nextBase = serviceDayBase(next.serviceDate()).getEpochSecond();
      long scheduled = nextBase + segments.get(0).stopTimes().get(0).departureSecondOfDay();
      long ready = Math.max(arrival + next.readySeconds(), now.getEpochSecond());
      if (ready <= scheduled || scheduled > horizon) {
        return;
      }
      long time = ready;
      boolean startsWithArrival = false;
      for (GtfsFeed.Trip segment : segments) {
        Propagated propagated =
            propagate(
                segment, nextBase, 0, time, startsWithArrival, Set.of(), Integer.MAX_VALUE, true);
        downstream.add(
            predictedEntity(
                segment,
                next.serviceDate(),
                assignment.trainName(),
                propagated,
                startsWithArrival
                    ? OptionalInt.empty()
                    : toOptionalInt(OptionalLong.of(ready - scheduled))));
        time = propagated.lastArrival();
        startsWithArrival = true;
      }
      arrival = time;
    }
  }

  private Entity predictedEntity(
      GtfsFeed.Trip segment,
      LocalDate serviceDate,
      String trainName,
      Propagated propagated,
      OptionalInt delay) {
    String id = entityId("tu", segment, serviceDate);
    TripUpdate update =
        new TripUpdate(
            descriptor(segment, serviceDate, TripScheduleRelationship.SCHEDULED),
            Optional.of(new VehicleDescriptor(trainName, Optional.of(segment.shortName()))),
            propagated.updates(),
            now.getEpochSecond(),
            delay);
    return new Entity(id, Optional.of(update), Optional.empty(), Optional.empty());
  }

  /**
   * 从一段的第 {@code from} 个停靠起往后推预计到发。
   *
   * <p>每站：发车 = max(表定发车, 到达 + 表定停站)，按表等点的车不早于表定开出；下一站到达 = 发车 + 表定站间时分。越站或取消的站不停，也不等点。
   *
   * @param time {@code startsWithArrival} 为 true 时是第一站的预计到达；为 false 时是车最早能从第一站开出的时刻（始发站只写发车）
   * @param holdForTimetable 按表等点（不早于表定发车）；按间隔发车的模板车次没有表定时刻，不等点
   */
  private static Propagated propagate(
      GtfsFeed.Trip segment,
      long base,
      int from,
      long time,
      boolean startsWithArrival,
      Set<Integer> skipped,
      int firstCancelled,
      boolean holdForTimetable) {
    List<GtfsFeed.StopTime> stops = segment.stopTimes();
    List<StopTimeUpdate> out = new ArrayList<>();
    long arrival = time;
    long lastArrival = time;
    for (int i = from; i < stops.size(); i++) {
      GtfsFeed.StopTime stop = stops.get(i);
      boolean origin = i == from && !startsWithArrival;
      boolean passing =
          skipped.contains(stop.stopSequence()) || stop.stopSequence() >= firstCancelled;
      long scheduledDeparture =
          holdForTimetable ? base + stop.departureSecondOfDay() : Long.MIN_VALUE;
      long departure;
      if (origin) {
        departure = Math.max(scheduledDeparture, time);
        arrival = departure;
      } else if (passing) {
        departure = arrival;
      } else {
        departure =
            Math.max(
                scheduledDeparture,
                arrival + stop.departureSecondOfDay() - stop.arrivalSecondOfDay());
      }
      out.add(
          passing
              ? skippedUpdate(stop)
              : new StopTimeUpdate(
                  stop.stopSequence(),
                  origin ? Optional.empty() : Optional.of(at(arrival)),
                  Optional.of(at(departure)),
                  Optional.of(stop.stopId()),
                  StopScheduleRelationship.SCHEDULED,
                  Optional.empty()));
      lastArrival = arrival;
      if (i + 1 < stops.size()) {
        arrival = departure + stops.get(i + 1).arrivalSecondOfDay() - stop.departureSecondOfDay();
      }
    }
    return new Propagated(List.copyOf(out), lastArrival);
  }

  private static StopTimeEvent at(long epochSecond) {
    return new StopTimeEvent(OptionalInt.empty(), OptionalLong.of(epochSecond));
  }

  private static StopTimeUpdate skippedUpdate(GtfsFeed.StopTime stopTime) {
    return new StopTimeUpdate(
        stopTime.stopSequence(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(stopTime.stopId()),
        StopScheduleRelationship.SKIPPED,
        Optional.empty());
  }

  private static int indexOf(GtfsFeed.Trip trip, int stopSequence) {
    List<GtfsFeed.StopTime> stops = trip.stopTimes();
    for (int i = 0; i < stops.size(); i++) {
      if (stops.get(i).stopSequence() == stopSequence) {
        return i;
      }
    }
    return -1;
  }

  private Set<Integer> skippedOf(GtfsFeed.Trip trip, LocalDate serviceDate) {
    return snapshot.skippedStops().skipped(trip.timetableId(), trip.tripUuid(), serviceDate);
  }

  /** 半途取消从哪一站起不再停；没有半途取消时为 {@link Integer#MAX_VALUE}。 */
  private int firstCancelledOf(GtfsFeed.Trip trip, LocalDate serviceDate) {
    TimetableApi.CancelledTrip cancellation =
        cancellations.get(new TripKey(trip.tripUuid(), serviceDate));
    return cancellation != null
            && cancellation.scope()
                == org.fetarute.fetaruteTCAddon.api.event.TimetableTripCancelledEvent.Scope.PARTIAL
        ? cancellation.firstCancelledStopSequence()
        : Integer.MAX_VALUE;
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
    if (trip.firstStopSequence() >= cancellation.firstCancelledStopSequence()) {
      // 这一段一站都不再停（直通车在前一段就不开了）：整段取消，而不是逐站 SKIPPED 却标着 SCHEDULED
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

  private List<String> sortedTrains() {
    List<String> names = new ArrayList<>(snapshot.trains().keySet());
    names.sort(Comparator.naturalOrder());
    return names;
  }

  /** 没绑车次、跑在按间隔发车交路上的车所在的模板车次段。 */
  private Optional<GtfsFeed.Trip> headwayTripOf(TrainRuntimeSnapshot runtime) {
    return feed.headwayTripForRouteIndex(runtime.routeUuid(), runtime.routeIndex());
  }

  /**
   * 按间隔发车的车次（{@code frequencies.txt} 的 {@code exact_times=0}）：{@code
   * schedule_relationship=UNSCHEDULED}， 起点发车时刻按第一次报出时的推算登记下来，之后不再改（规范要求一经确定不可变）。
   */
  private TripDescriptor headwayDescriptor(
      String train, TrainRuntimeSnapshot runtime, GtfsFeed.Trip trip) {
    Instant start = starts.pin(train + "|" + trip.id(), proposedStart(runtime, trip));
    ZonedDateTime local = start.atZone(zone);
    return new TripDescriptor(
        Optional.of(trip.id()),
        Optional.empty(),
        Optional.of(GtfsTime.timeOfDay(local.toLocalTime().toSecondOfDay())),
        Optional.of(GtfsTime.date(local.toLocalDate())),
        TripScheduleRelationship.UNSCHEDULED);
  }

  /** 推算这一趟从本段首站开出的时刻：现在减去模板里首站到上一个经过的停靠的时分；还没到首站时就是现在。 */
  private Instant proposedStart(TrainRuntimeSnapshot runtime, GtfsFeed.Trip trip) {
    int elapsed = 0;
    int origin = trip.stopTimes().get(0).departureSecondOfDay();
    for (GtfsFeed.StopTime stop : trip.stopTimes()) {
      if (stop.stopSequence() <= runtime.routeIndex()) {
        elapsed = stop.departureSecondOfDay() - origin;
      }
    }
    return now.minusSeconds(Math.max(0, elapsed));
  }

  /**
   * 按间隔发车的车：下一站按 ETA，往后按模板站间时分推（没有表定时刻，不等点）。
   *
   * <p>同一个模板车次同时有几辆车在跑，实体 ID 带上列车名区分。越过运营终点的回库车不发布。
   */
  private Optional<Entity> headwayTripEntity(String train) {
    if (outOfService(train) || assignmentsByTrain.containsKey(key(train))) {
      return Optional.empty();
    }
    TrainRuntimeSnapshot runtime = snapshot.trains().get(train);
    if (runtime == null) {
      return Optional.empty();
    }
    Optional<GtfsFeed.Trip> trip = headwayTripOf(runtime);
    if (trip.isEmpty()) {
      return Optional.empty();
    }
    int index = -1;
    List<GtfsFeed.StopTime> stops = trip.get().stopTimes();
    for (int i = 0; i < stops.size(); i++) {
      if (stops.get(i).stopSequence() > runtime.routeIndex()) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      return Optional.empty();
    }
    OptionalLong eta = snapshot.stopEtas().eta(train, stops.get(index).stopSequence());
    if (eta.isEmpty()) {
      return Optional.empty();
    }
    Propagated propagated =
        propagate(trip.get(), 0L, index, eta.getAsLong(), true, Set.of(), Integer.MAX_VALUE, false);
    TripUpdate update =
        new TripUpdate(
            headwayDescriptor(train, runtime, trip.get()),
            Optional.of(new VehicleDescriptor(train, Optional.of(train))),
            propagated.updates(),
            now.getEpochSecond(),
            OptionalInt.empty());
    String id = "tu:" + trip.get().id() + ":" + train;
    return Optional.of(new Entity(id, Optional.of(update), Optional.empty(), Optional.empty()));
  }

  /** 按间隔发车的车在模板里的位置：停在一个停靠上为 STOPPED_AT，否则驶向下一个停靠。 */
  private static StopProgress headwayProgress(TrainRuntimeSnapshot runtime, GtfsFeed.Trip trip) {
    int index = runtime.routeIndex();
    Optional<GtfsFeed.StopTime> here = trip.stopTime(index);
    if (runtime.dwellRemainingSec().isPresent() && here.isPresent()) {
      return new StopProgress(
          OptionalInt.of(index), Optional.of(here.get().stopId()), VehicleStopStatus.STOPPED_AT);
    }
    for (GtfsFeed.StopTime stop : trip.stopTimes()) {
      if (stop.stopSequence() > index) {
        return new StopProgress(
            OptionalInt.of(stop.stopSequence()),
            Optional.of(stop.stopId()),
            VehicleStopStatus.IN_TRANSIT_TO);
      }
    }
    return StopProgress.none();
  }

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
   * 行进方向换成 GTFS 的 bearing（正北为 0、顺时针、[0, 360)）。世界坐标里 +x 向东、+z 向南，所以正北是 −z。
   *
   * @param headingX 运动方向的 x 分量
   * @param headingZ 运动方向的 z 分量
   * @return 两个分量都接近 0（从没动过）时为空
   */
  static OptionalDouble bearing(double headingX, double headingZ) {
    if (!Double.isFinite(headingX)
        || !Double.isFinite(headingZ)
        || Math.abs(headingX) + Math.abs(headingZ) < 1e-9) {
      return OptionalDouble.empty();
    }
    double degrees = Math.toDegrees(Math.atan2(headingX, -headingZ));
    double normalized = ((degrees % 360.0) + 360.0) % 360.0;
    return OptionalDouble.of(normalized >= 359.9995 ? 0.0 : normalized);
  }

  private Optional<Position> position(TrainRuntimeSnapshot runtime) {
    return runtime
        .position()
        .map(
            sampled ->
                new Position(
                    GtfsGeo.latitude(sampled.z()),
                    GtfsGeo.longitude(sampled.x()),
                    bearing(sampled.headingX(), sampled.headingZ()),
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
    // 只报静态包里有的停靠：没有车站的停车点不在 stop_times 里，序号落在本段首末之间也不算
    boolean lastPublished = last >= 0 && value.stopTime(last).isPresent();
    if (runtime.dwellRemainingSec().isPresent() && lastPublished) {
      return new StopProgress(
          OptionalInt.of(last),
          stopIdForNode(bound.lastStopNodeId())
              .or(() -> value.stopTime(last).map(GtfsFeed.StopTime::stopId)),
          VehicleStopStatus.STOPPED_AT);
    }
    if (bound.nextStopSequence().isPresent()) {
      int next = bound.nextStopSequence().get();
      if (value.stopTime(next).isPresent()) {
        boolean incoming =
            runtime
                .currentNodeId()
                .map(NodeId::value)
                .filter(node -> bound.nextStopNodeId().filter(node::equals).isPresent())
                .isPresent();
        return new StopProgress(
            OptionalInt.of(next),
            stopIdForNode(placedNode(bound.trainName(), next))
                .or(() -> value.stopTime(next).map(GtfsFeed.StopTime::stopId)),
            incoming ? VehicleStopStatus.INCOMING_AT : VehicleStopStatus.IN_TRANSIT_TO);
      }
    }
    if (lastPublished) {
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
    return occupancy(
        trainLoad.seats(), trainLoad.cars().stream().mapToInt(TrainLoad.Car::occupied).sum());
  }

  /** 各节车的载客（车头为第 1 节）；读不到车辆模型时为空。没有座位的车（如动力车）照样列出，只是不写载客率。 */
  private static List<CarriageDetails> carriages(String train, Optional<TrainLoad> load) {
    if (load.isEmpty()) {
      return List.of();
    }
    List<TrainLoad.Car> cars = load.get().cars();
    List<CarriageDetails> out = new ArrayList<>(cars.size());
    for (int i = 0; i < cars.size(); i++) {
      TrainLoad.Car car = cars.get(i);
      Occupancy occupancy = occupancy(car.seats(), car.occupied());
      int sequence = i + 1;
      out.add(
          new CarriageDetails(
              train + "#" + sequence,
              Optional.of(Integer.toString(sequence)),
              occupancy.status(),
              occupancy.percentage(),
              sequence));
    }
    return out;
  }

  /** 座位数与在座人数换成 GTFS 的载客状态与百分比；没有座位时两者都为空。 */
  private static Occupancy occupancy(int seats, int occupied) {
    if (seats <= 0) {
      return Occupancy.none();
    }
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

  /**
   * 此刻在按间隔发车交路上跑的车与它们所在的模板车次（{@link FrequencyStarts} 的键）。登记表按它清理：车离开、换了交路的登记
   * 随之作废，同一辆车以后再跑这条交路时重新推算起点发车。
   */
  public Set<String> headwayKeys() {
    Set<String> keys = new HashSet<>();
    for (Map.Entry<String, TrainRuntimeSnapshot> entry : snapshot.trains().entrySet()) {
      String train = entry.getKey();
      if (outOfService(train) || assignmentsByTrain.containsKey(key(train))) {
        continue;
      }
      headwayTripOf(entry.getValue()).ifPresent(trip -> keys.add(train + "|" + trip.id()));
    }
    return keys;
  }

  /** 列车在这一站已选定的股道；动态站台未选台时为空。 */
  private Optional<String> placedNode(String trainName, int stopIndex) {
    return snapshot.placedStops().placed(trainName, stopIndex);
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

  /**
   * 车所在这一段的更新。
   *
   * @param arrivalAtSegmentEnd 推算到这一段终点的预计到达（epoch 秒）；下一站没有预测时为空
   */
  private record Run(TripUpdate update, OptionalLong arrivalAtSegmentEnd) {}

  /**
   * 一段的推算结果。
   *
   * @param updates 各站的预计到发（越站、取消的站为 SKIPPED）
   * @param lastArrival 这一段最后一站的预计到达（epoch 秒）
   */
  private record Propagated(List<StopTimeUpdate> updates, long lastArrival) {}

  /**
   * 按间隔发车车次的起点发车时刻登记：GTFS-Realtime 要求 {@code exact_times=0} 的车次一经报出起点发车时刻就不再变，
   * 而每次生成都是新的构建器，所以登记放在构建器外面（{@link GtfsRealtimeService}）。
   */
  @FunctionalInterface
  public interface FrequencyStarts {

    /**
     * @param key 列车与模板车次
     * @param proposed 这一次推算的起点发车
     * @return 登记过的时刻；第一次时登记并返回 {@code proposed}
     */
    Instant pin(String key, Instant proposed);

    /** 不登记：每次都用这一次的推算（测试与一次性生成）。 */
    static FrequencyStarts unpinned() {
      return (key, proposed) -> proposed;
    }
  }

  /** 车次实例键：车次 UUID + 服务日。 */
  private record TripKey(UUID tripId, LocalDate serviceDate) {}

  /** 车辆当前所处区间：上一站/下一站的序号与 stop_id，以及 GTFS 的车辆停站状态。 */
  private record StopProgress(
      OptionalInt stopSequence, Optional<String> stopId, VehicleStopStatus status) {

    static StopProgress none() {
      return new StopProgress(
          OptionalInt.empty(), Optional.empty(), VehicleStopStatus.IN_TRANSIT_TO);
    }
  }

  /** 车辆载客状态；没有载客数据时两者都为空。 */
  private record Occupancy(Optional<OccupancyStatus> status, OptionalInt percentage) {

    static Occupancy none() {
      return new Occupancy(Optional.empty(), OptionalInt.empty());
    }
  }
}
