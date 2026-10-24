package org.fetarute.fetaruteTCAddon.signaller;

import com.bergerkiller.bukkit.tc.actions.Action;
import com.bergerkiller.bukkit.tc.actions.WaitAction;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StopSkips;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerTrainDesk;
import org.fetarute.fetaruteTCAddon.display.pids.PidsService;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSession;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatLocator;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;

/**
 * 信号员对列车的处置（{@link SignallerTrainDesk} 的插件侧实现）。
 *
 * <p>越站交给调度的 {@link StopSkips}。取消车次核对现场（车上有没有人、是否停稳在站台、是否联挂中）， 删车前先结束车上的驾驶员与车掌（任务记为中断），
 * 把车上的人送到本站站台，再按调度的删车入口删车：交路转空缺并派替补，剩余停站登记取消。只在主线程调用。
 */
final class PluginTrainDesk implements SignallerTrainDesk {

  /** 删车、任务记录里写的原因。 */
  static final String CANCEL_REASON = "signaller-cancel";

  private static final Title.Times TITLE_TIMES =
      Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(500));

  private final FetaruteTCAddon plugin;
  private final Consumer<String> debug;

  PluginTrainDesk(FetaruteTCAddon plugin, Consumer<String> debug) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.debug = debug == null ? message -> {} : debug;
  }

  // ─── 越站 ──────────────────────────────────────────────────────────────────

  @Override
  public Optional<Skip> skip(String trainName) {
    return stopSkips()
        .flatMap(skips -> skips.candidate(trainName))
        .map(
            candidate ->
                new Skip(
                    candidate.node(),
                    candidate.ordered(),
                    candidate.refusal().map(PluginTrainDesk::refusal)));
  }

  @Override
  public Optional<NodeId> skipping(String trainName) {
    return stopSkips().flatMap(skips -> skips.orderedStation(trainName));
  }

  @Override
  public Optional<SkipRefusal> orderSkip(String trainName) {
    Optional<StopSkips> skips = stopSkips();
    if (skips.isEmpty()) {
      return Optional.of(SkipRefusal.NO_STOP);
    }
    Optional<NodeId> station = skips.get().candidate(trainName).map(StopSkips.Candidate::node);
    StopSkips.Result result = skips.get().order(trainName);
    if (result.outcome() != StopSkips.Outcome.ORDERED) {
      // 没给原因的拒绝：找不到下一个停车站
      return Optional.of(
          result.refusal().map(PluginTrainDesk::refusal).orElse(SkipRefusal.NO_STOP));
    }
    changed(trainName);
    station.ifPresent(node -> announceSkip(trainName, node, false));
    return Optional.empty();
  }

  @Override
  public Optional<SkipRefusal> revokeSkip(String trainName) {
    Optional<StopSkips> skips = stopSkips();
    if (skips.isEmpty()) {
      return Optional.empty();
    }
    Optional<NodeId> station = skips.get().candidate(trainName).map(StopSkips.Candidate::node);
    StopSkips.Result result = skips.get().revoke(trainName);
    if (result.outcome() == StopSkips.Outcome.REVOKED) {
      changed(trainName);
      station.ifPresent(node -> announceSkip(trainName, node, true));
    }
    return result.refusal().map(PluginTrainDesk::refusal);
  }

  /**
   * 越站令下了（或撤了）：车上所有人收到车内广播（聊天、副标题与提示音），驾驶员另收一条调度命令（动作栏与提示音）。
   *
   * @param revoked 撤销越站（恢复停车）
   */
  private void announceSkip(String trainName, NodeId station, boolean revoked) {
    Optional<Found> found = find(trainName);
    if (found.isEmpty()) {
      return;
    }
    String name =
        plugin
            .getStationDirectory()
            .flatMap(directory -> directory.snapshot().stationOfNode(station.value()))
            .map(entry -> entry.station().name())
            .orElse(station.value());
    Map<String, String> values = Map.of("station", name, "train", found.get().logicalName());
    String key = revoked ? "signaller.skip.cabin.restored" : "signaller.skip.cabin.ordered";
    var locale = plugin.getLocaleManager();
    for (Player rider : riders(found.get().tcName())) {
      rider.sendMessage(locale.component(key + "-chat", values));
      rider.showTitle(
          Title.title(
              net.kyori.adventure.text.Component.empty(),
              locale.component(key + "-subtitle", values),
              TITLE_TIMES));
      rider.playSound(rider.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.0f);
    }
    DriveSessionManager drive = plugin.getDriveSessionManager();
    if (drive != null) {
      var side = drive.driverSide();
      side.driverOf(found.get().tcName())
          .ifPresent(
              driver ->
                  side.notifyDriver(
                      driver,
                      revoked ? "signaller.skip.driver.restored" : "signaller.skip.driver.ordered",
                      values,
                      org.fetarute.fetaruteTCAddon.drive.sound.DriveCue.SIGNAL_RESTRICTIVE));
    }
  }

  /** 越站令变了：这列车立即按新的停车点重评，站牌与 ETA 重算。 */
  private void changed(String trainName) {
    plugin
        .getRuntimeDispatchService()
        .ifPresent(dispatch -> dispatch.signallerGates().wake(trainName));
    if (plugin.getEtaService() != null) {
      plugin.getEtaService().invalidateTrainEta(trainName);
      // 站牌与站台屏都有几秒的缓存：临近刹车点撤销时，屏上还写“通过”、广播还在播通过，车却停下
      plugin.getEtaService().invalidateBoards();
    }
    plugin.getPidsService().ifPresent(PidsService::invalidateSnapshots);
  }

  private Optional<StopSkips> stopSkips() {
    return plugin.getRuntimeDispatchService().map(dispatch -> dispatch.stationStops().stopSkips());
  }

  private static SkipRefusal refusal(StopSkips.Refusal refusal) {
    return switch (refusal) {
      case TERMINAL -> SkipRefusal.TERMINAL;
      case DIRECTIVE, REVERSAL -> SkipRefusal.DIRECTIVE;
      case BRAKING -> SkipRefusal.BRAKING;
      case CALL_DESTINATION -> SkipRefusal.CALL_DESTINATION;
      case CREW_BOARDING -> SkipRefusal.CREW_BOARDING;
    };
  }

  // ─── 取消车次 ──────────────────────────────────────────────────────────────

  @Override
  public Scene scene(String trainName) {
    Optional<Found> found = find(trainName);
    if (found.isEmpty()) {
      return new Scene(false, false, false);
    }
    Found train = found.get();
    boolean players = RuntimeDispatchService.hasPlayerPassengers(train.properties());
    MinecartGroup group = train.group();
    boolean atPlatform =
        settled(group.isMoving(), group.getActions().getScheduledActions())
            && stationStop(train.logicalName()).isPresent();
    boolean coupled =
        plugin
            .getCouplerService()
            .map(
                coupler ->
                    coupler.decoupling().dividesHere(train.logicalName())
                        || coupler.decoupling().forecast(train.logicalName()).isPresent()
                        || coupler.coupling().holds(train.logicalName())
                        || coupler.coupling().approaching(train.logicalName()))
            .orElse(false);
    return new Scene(players, atPlatform, coupled);
  }

  @Override
  public void announceCancel(String trainName) {
    Optional<Found> found = find(trainName);
    if (found.isEmpty()) {
      return;
    }
    String station = stationName(found.get().logicalName()).orElse("");
    Map<String, String> values = Map.of("station", station);
    var locale = plugin.getLocaleManager();
    for (Player rider : riders(found.get().tcName())) {
      rider.showTitle(
          Title.title(
              locale.component("signaller.cancel.cabin.title", values),
              locale.component("signaller.cancel.cabin.subtitle", values),
              TITLE_TIMES));
      rider.sendMessage(locale.component("signaller.cancel.cabin.chat", values));
      rider.playSound(rider.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
    }
  }

  @Override
  public void cancel(String trainName) {
    Optional<Found> found = find(trainName);
    if (found.isEmpty()) {
      return;
    }
    Found train = found.get();
    Map<String, String> values = Map.of("train", train.logicalName());
    DriveSessionManager drive = plugin.getDriveSessionManager();
    if (drive != null) {
      // 先结束驾驶员、再结束车掌：反过来会多给驾驶员一条“车掌离岗”
      drive.endForCancel(train.tcName(), CANCEL_REASON, "drive.end.signaller-cancel", values);
      drive
          .guards()
          .ifPresent(
              guards ->
                  guards
                      .guardOfTrainName(train.tcName())
                      .ifPresent(
                          guard ->
                              guards.stop(
                                  guard,
                                  GuardSession.EndReason.TRAIN_GONE,
                                  CANCEL_REASON,
                                  "drive.guard.end.signaller-cancel")));
    }
    // 删车后进度就没了，跑的哪条交路、停在哪一站要先记下（日志用）
    Optional<String> where = stationName(train.logicalName());
    String route =
        plugin
            .getRouteProgressRegistry()
            .flatMap(progress -> progress.get(train.logicalName()))
            .map(entry -> entry.routeId().value())
            .orElse("-");
    // 删车前送人：删车晚一拍才真正删掉编组，留在座位上的人会被就地放下
    Optional<Location> landing =
        drive == null
            ? Optional.empty()
            : stationStop(train.logicalName())
                .map(stop -> NodeId.of(stop.nodeId()))
                .flatMap(node -> drive.tasks().platformLocation(node));
    int moved = 0;
    for (Player rider : riders(train.tcName())) {
      rider.leaveVehicle();
      landing.ifPresent(rider::teleport);
      moved++;
    }
    // 删车前记下这一班与当前晚点：删车即发车次取消，晚点车本站的计划到达多半已过，取消行要顺延显示
    plugin
        .getPidsService()
        .ifPresent(
            pids -> {
              pids.holdCancelled(train.logicalName());
              if (!train.tcName().equalsIgnoreCase(train.logicalName())) {
                pids.holdCancelled(train.tcName());
              }
            });
    boolean destroyed =
        plugin
            .getRuntimeDispatchService()
            .map(dispatch -> dispatch.destroyTrainByName(train.resolvedName(), CANCEL_REASON))
            .orElse(false);
    plugin
        .getLogger()
        .info(
            "信号楼取消车次 "
                + train.logicalName()
                + "（交路 "
                + route
                + where.map(name -> "，" + name + "站）").orElse("，区间）")
                + "：送下车 "
                + moved
                + " 名玩家"
                + (destroyed ? "，已删车" : "，删车未成"));
    debug.accept("SIGNALLER_TRIP_CANCELLED train=" + train.logicalName() + " moved=" + moved);
  }

  // ─── 查找 ──────────────────────────────────────────────────────────────────

  /**
   * 一列在调度里的车。
   *
   * @param resolvedName 调度解析出的车名（删车用）
   * @param tcName TrainCarts 列车名（座位、驾驶与值乘会话都按它认）
   * @param logicalName 调度逻辑名（进度、到站记录、拆挂都按它认）
   */
  private record Found(
      String resolvedName,
      String tcName,
      String logicalName,
      TrainProperties properties,
      MinecartGroup group) {}

  private Optional<Found> find(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    Optional<RuntimeDispatchService> dispatch = plugin.getRuntimeDispatchService();
    if (dispatch.isEmpty()) {
      return Optional.empty();
    }
    RuntimeDispatchService.RuntimeTrainResolution resolution =
        dispatch
            .get()
            .resolveRuntimeTrainForHealth(
                trainName, RuntimeDispatchService.RuntimeTrainResolvePurpose.GET_STATE);
    TrainProperties properties = resolution.properties();
    MinecartGroup group = properties == null ? null : properties.getHolder();
    if (group == null || !group.isValid()) {
      return Optional.empty();
    }
    String resolved = resolution.resolvedName().isBlank() ? trainName : resolution.resolvedName();
    String logical = dispatch.get().resolveTrackedTrainName(properties).orElse(resolved);
    return Optional.of(new Found(resolved, properties.getTrainName(), logical, properties, group));
  }

  /** 车停稳了：不在动，动作队列里只有等待（人工驾驶期间驾驶动作一直挂在队列里，也不算要动）。 */
  static boolean settled(boolean moving, java.util.Collection<? extends Action> scheduled) {
    return settled(moving, scheduled, DriveSessionManager::isDriveAction);
  }

  /**
   * 同 {@link #settled(boolean, java.util.Collection)}。
   *
   * @param driving 认人工驾驶的驾驶动作
   */
  static boolean settled(
      boolean moving,
      java.util.Collection<? extends Action> scheduled,
      java.util.function.Predicate<Action> driving) {
    return !moving
        && scheduled.stream()
            .allMatch(action -> action instanceof WaitAction || driving.test(action));
  }

  /**
   * 车此刻停着的那一站（已到站、还没发车）：在站记录要对上车当前的交路与进度。折返复用会换交路而不经过 AutoStation 发车， 只看最近一次到站会把停在区间红灯前的车当成还在上一站。
   */
  private Optional<StationPresenceTracker.Presence> stationStop(String logicalName) {
    Optional<RouteProgressRegistry.RouteProgressEntry> entry =
        plugin.getRouteProgressRegistry().flatMap(progress -> progress.get(logicalName));
    if (entry.isEmpty()) {
      return Optional.empty();
    }
    return plugin
        .getStationPresence()
        .flatMap(
            tracker ->
                tracker.presenceAt(
                    logicalName, entry.get().routeId().value(), entry.get().currentIndex()));
  }

  private Optional<String> stationName(String logicalName) {
    return stationStop(logicalName)
        .flatMap(
            stop ->
                plugin
                    .getStationDirectory()
                    .flatMap(directory -> directory.snapshot().stationOfNode(stop.nodeId())))
        .map(StationDirectory.StationEntry::station)
        .map(station -> station.name());
  }

  /** 坐在这列车上的在线玩家（乘客、驾驶员、车掌）。 */
  private static java.util.List<Player> riders(String tcName) {
    java.util.List<Player> out = new java.util.ArrayList<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (SeatLocator.locate(player).filter(seat -> seat.trainName().equals(tcName)).isPresent()) {
        out.add(player);
      }
    }
    return out;
  }
}
