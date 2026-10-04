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
      TaskKey key = entry.row().key();
      Optional<TaskBoardEntries.Trip> trip =
          timetables
              .get()
              .tripPlan(key.timetableId(), key.tripCode(), key.serviceDate())
              .flatMap(plan -> TaskTripSummary.of(stopsOf(plan), entry.row().stopSequence()))
              .map(
                  summary ->
                      new TaskBoardEntries.Trip(
                          stationName(plugin, summary.terminusCode(), summary.terminusNodeId()),
                          summary.stopCount(),
                          summary.runSeconds()));
      result.add(trip.map(entry::withTrip).orElse(entry));
    }
    return result;
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
