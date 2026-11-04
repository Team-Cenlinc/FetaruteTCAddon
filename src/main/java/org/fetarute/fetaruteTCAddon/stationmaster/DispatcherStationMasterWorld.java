package org.fetarute.fetaruteTCAddon.stationmaster;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardPhase;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainNameFormatter;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableAssignment;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.drive.driver.StationStopPoints;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardSource;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatLocator;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.fetarute.fetaruteTCAddon.drive.session.ManagedTrains;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/** 站长模块看到的外部世界：从插件主类取在线玩家、调度图上的车站股道、时刻表、列车与乘务员。 */
final class DispatcherStationMasterWorld implements StationMasterWorld {

  /** 列车离车站多近时改用列车自己的走向定进站方向（格）。 */
  private static final double NEAR_BLOCKS = 64.0;

  /** 车内广播标题的淡入、停留与淡出。 */
  private static final Title.Times CABIN_TITLE_TIMES =
      Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(500));

  private final FetaruteTCAddon plugin;

  DispatcherStationMasterWorld(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  @Override
  public Optional<PlayerSpot> player(UUID player) {
    Player online = player == null ? null : Bukkit.getPlayer(player);
    if (online == null || !online.isOnline()) {
      return Optional.empty();
    }
    Location location = online.getLocation();
    double lookX = location.getDirection().getX();
    double lookZ = location.getDirection().getZ();
    double length = Math.sqrt(lookX * lookX + lookZ * lookZ);
    return Optional.of(
        new PlayerSpot(
            online.getName(),
            spot(location),
            !online.isDead(),
            length < 1e-6 ? 0.0 : lookX / length,
            length < 1e-6 ? 0.0 : lookZ / length));
  }

  @Override
  public List<Station> stations() {
    return TaskBoardSource.stations(plugin).stream()
        .map(station -> new Station(station.operatorCode(), station.stationCode(), station.name()))
        .toList();
  }

  @Override
  public Optional<Station> nearestStation(UUID player) {
    Player online = player == null ? null : Bukkit.getPlayer(player);
    if (online == null) {
      return Optional.empty();
    }
    return TaskBoardSource.nearestStation(plugin, online.getLocation())
        .map(station -> new Station(station.operatorCode(), station.stationCode(), station.name()));
  }

  @Override
  public List<Track> tracks(Station station) {
    RailGraphService graphs = plugin.getRailGraphService();
    if (graphs == null || station == null) {
      return List.of();
    }
    List<Track> tracks = new ArrayList<>();
    graphs
        .snapshotAll()
        .forEach(
            (worldId, snapshot) -> {
              for (RailNode node : snapshot.graph().nodes()) {
                if (node.type() != NodeType.STATION) {
                  continue;
                }
                Optional<WaypointMetadata> meta = node.waypointMetadata();
                if (meta.isEmpty()
                    || meta.get().kind() != WaypointKind.STATION
                    || !meta.get().operator().equalsIgnoreCase(station.operator())
                    || !meta.get().originStation().equalsIgnoreCase(station.code())) {
                  continue;
                }
                tracks.add(
                    new Track(
                        node.id(),
                        meta.get().trackNumber(),
                        worldId,
                        node.worldPosition().getX(),
                        node.worldPosition().getY(),
                        node.worldPosition().getZ()));
              }
            });
    tracks.sort(Comparator.comparingInt(Track::number));
    return List.copyOf(tracks);
  }

  @Override
  public List<Arrival> arrivals(Station station, Duration horizon) {
    EtaService eta = plugin.getEtaService();
    if (eta == null || station == null) {
      return List.of();
    }
    List<Arrival> arrivals = new ArrayList<>();
    for (BoardResult.BoardRow row :
        eta.getBoard(station.operator(), station.code(), null, horizon).rows()) {
      if (row.passing()
          || row.platformPending()
          || row.trainName().isEmpty()
          || (row.phase() != BoardPhase.EN_ROUTE && row.phase() != BoardPhase.ARRIVING)) {
        continue;
      }
      trackNumber(row.platform())
          .ifPresent(track -> arrivals.add(new Arrival(row.trainName().get(), track, row.eta())));
    }
    arrivals.sort(Comparator.comparing(Arrival::eta));
    return arrivals;
  }

  private static Optional<Integer> trackNumber(String platform) {
    try {
      return Optional.of(Integer.parseInt(platform.trim()));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  @Override
  public List<Timetable> timetables() {
    return plugin
        .getTimetableService()
        .map(TimetableService::publishedTimetables)
        .orElse(List.of());
  }

  @Override
  public List<Spot> cars(String trainName) {
    Optional<MinecartGroup> group = group(trainName);
    if (group.isEmpty()) {
      return List.of();
    }
    List<Spot> cars = new ArrayList<>(group.get().size());
    for (MinecartMember<?> member : group.get()) {
      cars.add(spot(member.getEntity().getLocation()));
    }
    return cars;
  }

  @Override
  public boolean trainExists(String trainName) {
    return group(trainName).isPresent();
  }

  @Override
  public Optional<TrainNear> train(String trainName) {
    return group(trainName)
        .map(
            group -> {
              List<Spot> cars = new ArrayList<>(group.size());
              for (MinecartMember<?> member : group) {
                cars.add(spot(member.getEntity().getLocation()));
              }
              return new TrainNear(group.getProperties().getTrainName(), cars, group.isMoving());
            });
  }

  @Override
  public Optional<StandPoint> standPoint(String trainName, NodeId node) {
    DriveSessionManager drive = plugin.getDriveSessionManager();
    Optional<MinecartGroup> found = group(trainName);
    if (drive == null || found.isEmpty() || found.get().getWorld() == null) {
      return Optional.empty();
    }
    MinecartGroup group = found.get();
    Vector head = group.head().getEntity().getLocation().toVector();
    return nodePosition(node)
        .flatMap(
            sign -> {
              // 还在远处时列车的走向说明不了进站方向：按车站相对列车的方位定正反；开到近处才用列车自己的走向。
              Vector toStation = sign.clone().subtract(head).setY(0.0);
              Vector own = StopAlignment.travel(group);
              Vector travel =
                  own != null && toStation.lengthSquared() <= NEAR_BLOCKS * NEAR_BLOCKS
                      ? own
                      : toStation;
              return drive
                  .stationStopPoints()
                  .lookup(node, group.getWorld(), travel, group.size(), Bukkit.getCurrentTick());
            })
        .flatMap(point -> standPoint(point, group));
  }

  private static Optional<StandPoint> standPoint(
      StationStopPoints.StopPoint point, MinecartGroup group) {
    Vector forward = point.forward();
    double length = forward == null ? 0.0 : Math.hypot(forward.getX(), forward.getZ());
    if (!(length > 1.0e-6)) {
      return Optional.empty();
    }
    double forwardX = forward.getX() / length;
    double forwardZ = forward.getZ() / length;
    double front = StopAlignment.frontOffsetBlocks(group);
    double body = StopAlignment.bodyLengthBlocks(group) + 2.0 * front;
    double ahead = point.reference() == StopAlignment.Reference.HEAD ? 0.0 : body / 2.0;
    Vector stop = point.point();
    return Optional.of(
        new StandPoint(
            new Spot(
                point.worldId(),
                stop.getX() + forwardX * ahead,
                stop.getY(),
                stop.getZ() + forwardZ * ahead),
            forwardX,
            forwardZ,
            body));
  }

  /** 车站节点的位置；调度图里没有时为空。 */
  private Optional<Vector> nodePosition(NodeId node) {
    RailGraphService graphs = plugin.getRailGraphService();
    if (graphs == null || node == null) {
      return Optional.empty();
    }
    return graphs.snapshotAll().values().stream()
        .flatMap(snapshot -> snapshot.graph().findNode(node).stream())
        .map(RailNode::worldPosition)
        .findFirst();
  }

  @Override
  public String trainLabel(String trainName) {
    return TrainNameFormatter.sequence(trainName)
        .or(
            () ->
                plugin
                    .getTimetableService()
                    .flatMap(service -> service.assignmentOf(trainName))
                    .map(TimetableAssignment::tripCode)
                    .filter(code -> code != null && !code.isBlank()))
        .orElse(trainName);
  }

  @Override
  public List<UUID> crew(String trainName) {
    var drive = plugin.getDriveSessionManager();
    if (drive == null || trainName == null) {
      return List.of();
    }
    // 乘务员各按自己的车名认车：改名、联挂过渡期名字可能对不上，逐个名字问一遍。
    Set<UUID> crew = new LinkedHashSet<>();
    for (String name : plugin.trainNames(trainName)) {
      drive.driverSide().driverOf(name).ifPresent(crew::add);
      drive.guards().flatMap(guards -> guards.guardOfTrainName(name)).ifPresent(crew::add);
    }
    return List.copyOf(crew);
  }

  @Override
  public CrewOnTrain crewOn(String trainName) {
    var drive = plugin.getDriveSessionManager();
    if (drive == null || trainName == null) {
      return CrewOnTrain.AUTO;
    }
    Set<String> names = plugin.trainNames(trainName);
    boolean guard =
        names.stream()
            .anyMatch(
                name ->
                    drive.guards().flatMap(guards -> guards.guardOfTrainName(name)).isPresent());
    if (guard) {
      return CrewOnTrain.GUARD;
    }
    return names.stream()
        .flatMap(name -> drive.driverSide().driverOf(name).stream())
        .findFirst()
        .map(id -> drive.driverSide().driverAto(id) ? CrewOnTrain.ATO : CrewOnTrain.DRIVER)
        .orElse(CrewOnTrain.AUTO);
  }

  /** 走调度的唤醒入口（与信号员共用），下一拍重评，不在当前调用栈里重入。 */
  @Override
  public void reevaluate(String trainName) {
    if (trainName == null) {
      return;
    }
    plugin
        .getRuntimeDispatchService()
        .ifPresent(dispatch -> dispatch.signallerGates().wake(trainName));
  }

  @Override
  public List<TrainNear> trainsNear(Spot center, double range) {
    List<TrainNear> near = new ArrayList<>();
    for (MinecartGroup group : MinecartGroupStore.getGroups()) {
      if (group == null || !group.isValid() || !ManagedTrains.isFtaManaged(group.getProperties())) {
        continue;
      }
      List<Spot> cars = new ArrayList<>(group.size());
      boolean close = false;
      for (MinecartMember<?> member : group) {
        Spot car = spot(member.getEntity().getLocation());
        cars.add(car);
        close |= car.near(center, range);
      }
      if (close) {
        near.add(new TrainNear(group.getProperties().getTrainName(), cars, group.isMoving()));
      }
    }
    return near;
  }

  @Override
  public Emergency emergencyStop(String trainName) {
    DriveSessionManager drive = plugin.getDriveSessionManager();
    Optional<MinecartGroup> group = group(trainName);
    if (drive == null || group.isEmpty()) {
      return Emergency.UNAVAILABLE;
    }
    if (!group.get().isMoving()) {
      return Emergency.NOT_MOVING;
    }
    return drive.platformEmergency(group.get()) ? Emergency.BRAKING : Emergency.DRIVER;
  }

  @Override
  public void refreshSignal(String trainName) {
    group(trainName)
        .ifPresent(
            group ->
                plugin
                    .getRuntimeDispatchService()
                    .ifPresent(dispatch -> dispatch.refreshSignal(group)));
  }

  @Override
  public boolean lastPassengerStop(String trainName, NodeId node) {
    TrainProperties properties = trainName == null ? null : TrainPropertiesStore.get(trainName);
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (properties == null || cache.isEmpty()) {
      return false;
    }
    Optional<RouteDefinition> route =
        TrainTagHelper.readUuidTag(properties, RouteProgressRegistry.TAG_ROUTE_ID)
            .flatMap(cache.get()::findById);
    if (route.isEmpty()) {
      return false;
    }
    int index =
        TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX).orElse(-1);
    return PassengerClearing.lastPassengerStop(
        cache.get().listStops(route.get().id()), index, node);
  }

  @Override
  public List<UUID> riders(String trainName) {
    if (trainName == null) {
      return List.of();
    }
    List<UUID> crew = crew(trainName);
    List<UUID> riders = new ArrayList<>();
    for (Player online : Bukkit.getOnlinePlayers()) {
      if (crew.contains(online.getUniqueId())) {
        continue;
      }
      SeatLocator.locate(online)
          .filter(seat -> seat.trainName().equals(trainName))
          .ifPresent(seat -> riders.add(online.getUniqueId()));
    }
    return riders;
  }

  @Override
  public void announceClearing(String trainName) {
    LocaleManager locale = plugin.getLocaleManager();
    for (UUID rider : riders(trainName)) {
      Player online = Bukkit.getPlayer(rider);
      if (online == null || locale == null) {
        continue;
      }
      online.sendMessage(locale.component("stationmaster.clearing.cabin.chat"));
      online.showTitle(
          Title.title(
              locale.component("stationmaster.clearing.cabin.title"),
              locale.component("stationmaster.clearing.cabin.subtitle"),
              CABIN_TITLE_TIMES));
      online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.0f);
    }
  }

  @Override
  public void sendToPlatform(UUID rider, NodeId node) {
    Player online = rider == null ? null : Bukkit.getPlayer(rider);
    DriveSessionManager drive = plugin.getDriveSessionManager();
    if (online == null) {
      return;
    }
    online.leaveVehicle();
    if (drive != null) {
      drive.tasks().platformLocation(node).ifPresent(online::teleport);
    }
  }

  /** 列车名对应的有效列车；不在了时为空。 */
  static Optional<MinecartGroup> group(String trainName) {
    return Optional.ofNullable(trainName)
        .map(TrainPropertiesStore::get)
        .map(TrainProperties::getHolder)
        .filter(MinecartGroup::isValid);
  }

  private static Spot spot(Location location) {
    return new Spot(
        location.getWorld().getUID(), location.getX(), location.getY(), location.getZ());
  }
}
