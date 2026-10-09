package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.FetaruteApi;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.display.pids.PidsNearby;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;

/** 任务板的数据：玩家附近的车站（与站台屏同一口径）与它即将发出的车次。 */
public final class TaskBoardSource {

  /** 最多查多少条发车。 */
  private static final int DEPARTURE_LIMIT = 300;

  /**
   * 一个车站。
   *
   * @param operatorCode 运营商代码
   * @param stationCode 站码
   * @param name 站名
   */
  public record Station(String operatorCode, String stationCode, String name) {}

  private TaskBoardSource() {}

  /** 玩家附近（站台屏的半径内）最近的车站；不在车站附近时为空。 */
  public static Optional<Station> nearestStation(FetaruteTCAddon plugin, Location location) {
    if (location == null || location.getWorld() == null) {
      return Optional.empty();
    }
    Optional<List<GraphApi.ApiNode>> nodes =
        FetaruteApi.get()
            .flatMap(api -> api.graph().getSnapshot(location.getWorld().getUID()))
            .map(GraphApi.GraphSnapshot::nodes);
    if (nodes.isEmpty()) {
      return Optional.empty();
    }
    PidsScreen.Position center =
        new PidsScreen.Position(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    List<PidsStationKey> stations =
        PidsNearby.of(nodes.get(), center, PidsNearby.RADIUS).stations();
    if (stations.isEmpty()) {
      return Optional.empty();
    }
    PidsStationKey key = stations.get(0);
    String name =
        plugin
            .getStationDirectory()
            .flatMap(
                directory ->
                    directory.snapshot().findStation(key.operatorCode(), key.stationCode()))
            .map(StationDirectory.StationEntry::name)
            .orElse(key.stationCode());
    return Optional.of(new Station(key.operatorCode(), key.stationCode(), name));
  }

  /** 车站目录里的全部车站；目录未就绪时为空。 */
  public static List<Station> stations(FetaruteTCAddon plugin) {
    return plugin
        .getStationDirectory()
        .map(
            directory ->
                directory.snapshot().stations().stream()
                    .map(entry -> new Station(entry.operator().code(), entry.code(), entry.name()))
                    .toList())
        .orElse(List.of());
  }

  /**
   * 给任务板上的车次补上行程概要：终点站、停站数与按表的运行时长。查不到停靠表的保持原样。
   *
   * @param entries 已筛选好的任务板条目（只为要显示的这些查停靠表）
   */
  public static List<TaskBoardEntries.Entry> withTrips(
      FetaruteTCAddon plugin, List<TaskBoardEntries.Entry> entries) {
    Optional<TimetableService> timetables = plugin.getTimetableService();
    if (timetables.isEmpty()) {
      return entries;
    }
    List<TaskBoardEntries.Entry> result = new ArrayList<>(entries.size());
    for (TaskBoardEntries.Entry entry : entries) {
      Optional<TaskBoardEntries.Trip> trip =
          tripOf(plugin, timetables.get(), entry.row().key(), entry.row().stopSequence());
      result.add(trip.map(entry::withTrip).orElse(entry));
    }
    return result;
  }

  /**
   * 一个车次从某一站起的行程概要：终点站、停站数与按表的运行时长。
   *
   * @param fromSequence 从哪一站（停靠序号）起算
   * @return 查不到停靠表时为空
   */
  public static Optional<TaskBoardEntries.Trip> tripOf(
      FetaruteTCAddon plugin, TimetableService timetables, TaskKey key, int fromSequence) {
    return timetables
        .tripPlan(key.timetableId(), key.tripCode(), key.serviceDate())
        .flatMap(plan -> TaskTripSummary.of(stopsOf(plan), fromSequence))
        .map(
            summary ->
                new TaskBoardEntries.Trip(
                    stationName(plugin, summary.terminusCode(), summary.terminusNodeId()),
                    summary.stopCount(),
                    summary.runSeconds()));
  }

  /**
   * 列车跑的车次当作驾驶员当场接下的任务：从停靠序号 {@code takeoverSequence} 起（不停的站往后顺延到第一个停车站）开到终点站。
   *
   * @param trainName 担当的列车
   * @param source 来源标记（见 {@link DriverTask#SOURCE_TAKEOVER}、{@link DriverTask#SOURCE_CONTINUATION}）
   * @return 查不到停靠表、或从这一站起已没有停车站时为空
   */
  public static Optional<DriverTaskManager.TaskSpec> tripSpec(
      FetaruteTCAddon plugin,
      TimetableService timetables,
      TaskKey key,
      int takeoverSequence,
      String trainName,
      String source) {
    return timetables
        .tripPlan(key.timetableId(), key.tripCode(), key.serviceDate())
        .flatMap(
            plan ->
                plan.stops().stream()
                    .filter(stop -> stop.stops() && stop.stopSequence() >= takeoverSequence)
                    .findFirst()
                    .map(
                        takeover -> {
                          String code = takeover.stationCode().orElse("");
                          String operator =
                              takeover
                                  .nodeId()
                                  .flatMap(RouteTerminals::stationIdentityOfNode)
                                  .map(RouteTerminals.StationRef::operatorCode)
                                  .orElse("");
                          return new DriverTaskManager.TaskSpec(
                              key,
                              plan.routeCode(),
                              operator,
                              code,
                              stationName(plugin, code, takeover.nodeId()),
                              takeover.nodeId().orElse(null),
                              takeover.stopSequence(),
                              takeover.departure().or(takeover::arrival).orElseGet(Instant::now),
                              trainName,
                              -1,
                              null,
                              null,
                              false,
                              source,
                              java.util.Map.of(),
                              true);
                        }));
  }

  /**
   * 驾驶证路考或练习的区间任务：从任务板上这一班的接班站起，开过 {@code stops} 个停车站后下车。
   *
   * @param station 接班站
   * @param stops 要开过几个停车站
   * @param source 来源（路考 {@link DriverTask#SOURCE_EXAM}，练习 {@link DriverTask#SOURCE_TRAINING}）
   * @param metadata 附加数据（考的是哪一级）
   * @return 查不到停靠表、或这一班后面的停车站不够时为空
   */
  public static Optional<DriverTaskManager.TaskSpec> examSpec(
      FetaruteTCAddon plugin,
      TimetableService timetables,
      TaskBoardEntries.Row row,
      Station station,
      int stops,
      String source,
      java.util.Map<String, String> metadata) {
    TaskKey key = row.key();
    return timetables
        .tripPlan(key.timetableId(), key.tripCode(), key.serviceDate())
        .flatMap(
            plan -> {
              List<TimetableService.PlannedStop> ahead =
                  plan.stops().stream()
                      .filter(stop -> stop.stops() && stop.stopSequence() > row.stopSequence())
                      .toList();
              if (stops < 1 || ahead.size() < stops) {
                return Optional.empty();
              }
              TimetableService.PlannedStop handover = ahead.get(stops - 1);
              String code = handover.stationCode().orElse("");
              return Optional.of(
                  new DriverTaskManager.TaskSpec(
                      key,
                      plan.routeCode(),
                      station.operatorCode(),
                      station.stationCode(),
                      station.name(),
                      row.nodeId(),
                      row.stopSequence(),
                      row.plannedDeparture(),
                      row.trainName(),
                      handover.stopSequence(),
                      code,
                      stationName(plugin, code, handover.nodeId()),
                      false,
                      source,
                      metadata,
                      true));
            });
  }

  /**
   * 驾驶证练习的区间任务：玩家自己选的列车从停靠序号 {@code fromSequence} 起（不停的站往后顺延到第一个停车站）接班，开过 {@code stops} 个停车站后下车。
   *
   * @param trainName 担当的列车
   * @param stops 要开过几个停车站
   * @param source 来源（练习 {@link DriverTask#SOURCE_TRAINING}）
   * @param metadata 附加数据（练的是哪一级）
   * @return 查不到停靠表、或接班站之后的停车站不够时为空
   */
  public static Optional<DriverTaskManager.TaskSpec> intervalSpec(
      FetaruteTCAddon plugin,
      TimetableService timetables,
      TaskKey key,
      int fromSequence,
      String trainName,
      int stops,
      String source,
      java.util.Map<String, String> metadata) {
    return timetables
        .tripPlan(key.timetableId(), key.tripCode(), key.serviceDate())
        .flatMap(
            plan -> {
              Optional<TimetableService.PlannedStop> takeover = takeoverOf(plan, fromSequence);
              if (takeover.isEmpty()) {
                return Optional.empty();
              }
              List<TimetableService.PlannedStop> ahead = stopsAfter(plan, takeover.get());
              if (stops < 1 || ahead.size() < stops) {
                return Optional.empty();
              }
              TimetableService.PlannedStop board = takeover.get();
              TimetableService.PlannedStop handover = ahead.get(stops - 1);
              String code = board.stationCode().orElse("");
              String handoverCode = handover.stationCode().orElse("");
              String operator =
                  board
                      .nodeId()
                      .flatMap(RouteTerminals::stationIdentityOfNode)
                      .map(RouteTerminals.StationRef::operatorCode)
                      .orElse("");
              return Optional.of(
                  new DriverTaskManager.TaskSpec(
                      key,
                      plan.routeCode(),
                      operator,
                      code,
                      stationName(plugin, code, board.nodeId()),
                      board.nodeId().orElse(null),
                      board.stopSequence(),
                      board.departure().or(board::arrival).orElseGet(Instant::now),
                      trainName,
                      handover.stopSequence(),
                      handoverCode,
                      stationName(plugin, handoverCode, handover.nodeId()),
                      false,
                      source,
                      metadata,
                      true));
            });
  }

  /**
   * 从停靠序号 {@code fromSequence} 起接班（不停的站往后顺延），接班站之后还有几个停车站。
   *
   * @return 查不到停靠表、或从这里起已没有停车站时为空
   */
  public static java.util.OptionalInt stopsAhead(
      TimetableService timetables, TaskKey key, int fromSequence) {
    return timetables
        .tripPlan(key.timetableId(), key.tripCode(), key.serviceDate())
        .flatMap(plan -> takeoverOf(plan, fromSequence).map(board -> stopsAfter(plan, board)))
        .map(ahead -> java.util.OptionalInt.of(ahead.size()))
        .orElseGet(java.util.OptionalInt::empty);
  }

  private static Optional<TimetableService.PlannedStop> takeoverOf(
      TimetableService.TripPlan plan, int fromSequence) {
    return plan.stops().stream()
        .filter(stop -> stop.stops() && stop.stopSequence() >= fromSequence)
        .findFirst();
  }

  private static List<TimetableService.PlannedStop> stopsAfter(
      TimetableService.TripPlan plan, TimetableService.PlannedStop board) {
    return plan.stops().stream()
        .filter(stop -> stop.stops() && stop.stopSequence() > board.stopSequence())
        .toList();
  }

  /**
   * 给玩家看的一班车，与站台屏同一口径：哪条线、开往哪里、从几号站台、几点发车。车次号是内部编号，玩家认不出，只作附注。
   *
   * @param line 线路名；查不到时为空
   * @param destination 终点站名；查不到时为空
   * @param platform 接班站的站台号；不是股道节点时为 {@code -}
   * @param time 接班站计划发车时刻（服务器时区，时:分）
   */
  public record TripLabel(String line, String destination, String platform, String time) {}

  private static final java.time.format.DateTimeFormatter LABEL_CLOCK =
      java.time.format.DateTimeFormatter.ofPattern("HH:mm")
          .withZone(java.time.ZoneId.systemDefault());

  /** 一个任务所派的那一班车的说明。 */
  public static TripLabel label(
      FetaruteTCAddon plugin, TimetableService timetables, DriverTaskManager.TaskSpec spec) {
    Optional<TimetableService.TripPlan> plan =
        timetables.tripPlan(
            spec.key().timetableId(), spec.key().tripCode(), spec.key().serviceDate());
    // 查不到终点站时写交班站：玩家至少知道车往哪个方向开。
    String destination =
        tripOf(plugin, timetables, spec.key(), spec.takeoverStopSequence())
            .map(TaskBoardEntries.Trip::destination)
            .filter(name -> !name.isBlank())
            .orElse(spec.handoverStationName() == null ? "" : spec.handoverStationName());
    return new TripLabel(
        lineName(plugin, plan).orElse(""),
        destination,
        platformOf(plugin, timetables, plan, spec),
        LABEL_CLOCK.format(spec.plannedDeparture()));
  }

  /** 车次所属线路的名称（按交路所属的线路）；查不到时为空，不拿内部的交路代码顶替。 */
  private static Optional<String> lineName(
      FetaruteTCAddon plugin, Optional<TimetableService.TripPlan> plan) {
    return plan.flatMap(
        found ->
            plugin
                .getRouteDefinitionCache()
                .flatMap(cache -> cache.findRecord(found.routeId()))
                .map(record -> record.line().name())
                .filter(name -> !name.isBlank()));
  }

  /** 接班站的站台号：动态站台按编表排定的计划股道；动态站台没排上时不写（任务里的节点只是范围内第一条股道的占位，不是要停的站台）；固定站台按任务的节点。 */
  private static String platformOf(
      FetaruteTCAddon plugin,
      TimetableService timetables,
      Optional<TimetableService.TripPlan> plan,
      DriverTaskManager.TaskSpec spec) {
    Optional<String> planned =
        plan.flatMap(
            found -> timetables.plannedPlatform(found.tripId(), spec.takeoverStopSequence()));
    if (planned.isPresent()) {
      return RouteTerminals.platformOf(planned.get());
    }
    boolean dynamic =
        plan.flatMap(
                found ->
                    plugin
                        .getRouteDefinitionCache()
                        .flatMap(
                            cache ->
                                cache
                                    .findById(found.routeId())
                                    .flatMap(
                                        route ->
                                            cache.findStop(
                                                route.id(), spec.takeoverStopSequence()))))
            .map(org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher::isDynamicStop)
            .orElse(false);
    return dynamic ? "-" : RouteTerminals.platformOf(spec.takeoverNodeId());
  }

  private static List<TaskTripSummary.Stop> stopsOf(TimetableService.TripPlan plan) {
    List<TaskTripSummary.Stop> stops = new ArrayList<>(plan.stops().size());
    for (TimetableService.PlannedStop stop : plan.stops()) {
      stops.add(
          new TaskTripSummary.Stop(
              stop.stopSequence(),
              stop.stationCode(),
              stop.nodeId(),
              stop.stops(),
              stop.arrival(),
              stop.departure()));
    }
    return stops;
  }

  /** 站码对应的站名：运营商按节点确定；查不到时用站码。 */
  private static String stationName(
      FetaruteTCAddon plugin, String stationCode, Optional<String> nodeId) {
    String operator =
        nodeId
            .flatMap(RouteTerminals::stationIdentityOfNode)
            .map(RouteTerminals.StationRef::operatorCode)
            .orElse("");
    return plugin
        .getStationDirectory()
        .flatMap(directory -> directory.snapshot().findStation(operator, stationCode))
        .map(StationDirectory.StationEntry::name)
        .orElse(stationCode);
  }

  /**
   * 这个车站在时间窗内的发车（未经筛选）。
   *
   * @param windowMinutes 往后看多少分钟
   */
  public static List<TaskBoardEntries.Row> departures(
      FetaruteTCAddon plugin, Station station, Instant now, int windowMinutes) {
    return departures(plugin, station, now, Duration.ofMinutes(windowMinutes));
  }

  /**
   * 这个车站在时间窗内的发车（未经筛选）。
   *
   * @param station 车站；运营商为空时不按运营商区分（同站码的车站都算）
   * @param from 从这个时刻起（往前多看一小段，正在停站的车不会漏掉）
   * @param window 往后看多久
   */
  public static List<TaskBoardEntries.Row> departures(
      FetaruteTCAddon plugin, Station station, Instant from, Duration window) {
    Optional<TimetableApi> api = DriverTaskManager.timetables();
    if (api.isEmpty()) {
      return List.of();
    }
    List<TimetableApi.Departure> departures =
        api.get()
            .departuresAt(
                null,
                station.stationCode(),
                from.minus(TaskBoardEntries.DEPARTED_GRACE),
                window.plus(TaskBoardEntries.DEPARTED_GRACE),
                DEPARTURE_LIMIT);
    Collection<TimetableApi.TrainAssignment> assignments = api.get().listAssignments();
    List<TaskBoardEntries.Row> rows = new ArrayList<>();
    for (TimetableApi.Departure departure : departures) {
      if (!atStation(departure, station)) {
        continue;
      }
      TaskKey key =
          new TaskKey(departure.timetableId(), departure.tripCode(), departure.serviceDate());
      TimetableApi.TrainAssignment assignment = null;
      for (TimetableApi.TrainAssignment candidate : assignments) {
        if (key.matches(candidate.timetableId(), candidate.tripCode(), candidate.serviceDate())) {
          assignment = candidate;
          break;
        }
      }
      String train = assignment == null ? null : assignment.trainName();
      boolean dwelling =
          assignment != null
              && assignment.lastStopSequence().orElse(-1) == departure.stopSequence()
              && (plugin
                      .getDwellRegistry()
                      .map(r -> r.remainingSeconds(train).isPresent())
                      .orElse(false)
                  || plugin
                      .getRuntimeDispatchService()
                      .map(d -> d.hasDepartureGate(train))
                      .orElse(false));
      rows.add(
          new TaskBoardEntries.Row(
              key,
              departure.routeId(),
              departure.routeCode(),
              departure.stopSequence(),
              departure.nodeId().orElse(null),
              departure.plannedDeparture(),
              departure.terminating(),
              departure.cancelled(),
              train,
              dwelling));
    }
    return rows;
  }

  /** 发车是不是本站的：站码相同的车站可能属于不同运营商，按站台节点核对。 */
  private static boolean atStation(TimetableApi.Departure departure, Station station) {
    return departure
        .nodeId()
        .flatMap(RouteTerminals::stationIdentityOfNode)
        .filter(
            ref ->
                station.operatorCode() == null
                    || ref.operatorCode().equalsIgnoreCase(station.operatorCode()))
        .filter(ref -> ref.stationCode().equalsIgnoreCase(station.stationCode()))
        .isPresent();
  }
}
