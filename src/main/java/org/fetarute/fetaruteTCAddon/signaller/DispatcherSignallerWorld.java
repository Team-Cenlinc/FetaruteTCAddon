package org.fetarute.fetaruteTCAddon.signaller;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerPlatformDesk;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableAssignment;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;

/** 信号员模块看到的外部世界：从插件主类取控制区目录、调度图、占用、列车进度、时刻表与在线玩家。 */
final class DispatcherSignallerWorld implements SignallerWorld {

  private final FetaruteTCAddon plugin;
  private final BiFunction<String, String, Optional<Integer>> lineColors;
  private final Supplier<SignallerPlatformDesk> platforms;

  /**
   * @param plugin 插件主类
   * @param lineColors （运营商代码, 线路代码）→ 线路色
   * @param platforms 改停股道（调度服务重建后会换）
   */
  DispatcherSignallerWorld(
      FetaruteTCAddon plugin,
      BiFunction<String, String, Optional<Integer>> lineColors,
      Supplier<SignallerPlatformDesk> platforms) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.lineColors = Objects.requireNonNull(lineColors, "lineColors");
    this.platforms = Objects.requireNonNull(platforms, "platforms");
  }

  @Override
  public Optional<Zone> zone(String operator, String zone) {
    if (operator == null || zone == null) {
      return Optional.empty();
    }
    return plugin
        .getControlZoneDirectory()
        .find(operator, zone)
        .map(
            found ->
                new Zone(
                    found.operatorCode(),
                    found.code(),
                    found.zone().name(),
                    found.members().stream()
                        .map(member -> new YardArea.Member(member.kind(), member.code()))
                        .toList()));
  }

  @Override
  public Map<UUID, RailGraph> graphs() {
    RailGraphService service = plugin.getRailGraphService();
    if (service == null) {
      return Map.of();
    }
    Map<UUID, RailGraph> out = new HashMap<>();
    service.snapshotAll().forEach((world, snapshot) -> out.put(world, snapshot.graph()));
    return out;
  }

  @Override
  public Optional<RailGraph> graph(UUID worldId) {
    RailGraphService service = plugin.getRailGraphService();
    return service == null || worldId == null
        ? Optional.empty()
        : service.getSnapshot(worldId).map(RailGraphService.RailGraphSnapshot::graph);
  }

  @Override
  public List<OccupancyClaim> claims() {
    OccupancyManager manager = plugin.getOccupancyManager();
    return manager == null ? List.of() : manager.snapshotClaims();
  }

  @Override
  public Map<String, RouteProgressRegistry.RouteProgressEntry> progress() {
    return plugin
        .getRuntimeDispatchService()
        .map(RuntimeDispatchService::snapshotProgressEntries)
        .orElse(Map.of());
  }

  @Override
  public Optional<RouteDefinition> route(UUID routeUuid) {
    return plugin.findRouteDefinitionById(routeUuid);
  }

  @Override
  public boolean dwelling(String trainName) {
    boolean dwell =
        plugin.getDwellRegistry().map(r -> r.remainingSeconds(trainName).isPresent()).orElse(false);
    return dwell
        || plugin
            .getRuntimeDispatchService()
            .map(dispatch -> dispatch.hasDepartureGate(trainName))
            .orElse(false);
  }

  @Override
  public TrainInfo info(String trainName) {
    Optional<TimetableService> timetable = plugin.getTimetableService();
    String label =
        timetable
            .flatMap(service -> service.assignmentOf(trainName))
            .map(TimetableAssignment::tripCode)
            .filter(code -> code != null && !code.isBlank())
            .orElse(trainName);
    OptionalLong delay =
        timetable
            .filter(service -> service.assignmentOf(trainName).isPresent())
            .map(service -> service.currentDelaySeconds(trainName))
            .orElse(OptionalLong.empty());
    return new TrainInfo(label, lineColor(trainName), delay);
  }

  private Optional<Integer> lineColor(String trainName) {
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    if (properties == null) {
      return Optional.empty();
    }
    Optional<String> operator =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_OPERATOR_CODE);
    Optional<String> line =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_LINE_CODE);
    if (operator.isEmpty() || line.isEmpty()) {
      return Optional.empty();
    }
    return lineColors.apply(operator.get(), line.get());
  }

  /** 走调度的唤醒入口，下一拍重评：发许可可能发生在这列车自己的信号周期里（闸当场代排），当场重评会重入。 */
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
  public SignallerPlatformDesk platforms() {
    SignallerPlatformDesk desk = platforms.get();
    return desk == null ? SignallerPlatformDesk.NONE : desk;
  }

  @Override
  public Optional<PlayerSpot> player(UUID player) {
    Player online = player == null ? null : Bukkit.getPlayer(player);
    if (online == null || !online.isOnline()) {
      return Optional.empty();
    }
    return Optional.of(
        new PlayerSpot(
            online.getName(),
            online.getWorld().getUID(),
            online.getLocation().getX(),
            online.getLocation().getY(),
            online.getLocation().getZ(),
            !online.isDead()));
  }
}
