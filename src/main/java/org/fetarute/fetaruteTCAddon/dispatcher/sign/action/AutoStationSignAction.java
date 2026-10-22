package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.coupler.CoupleService;
import org.fetarute.fetaruteTCAddon.coupler.LinkedStops;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainCartsRuntimeHandle;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DoorCars;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopMarkIndex;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopMarks;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeStorageSynchronizer;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * AutoStation 牌子（站点行为节点）。
 *
 * <p>用于承载“停站/开关门/站台行为”等语义，因此只接受站点本体（4 段 {@code Operator:S:Station:Track}）。
 * 站咽喉属于图节点（Waypoint）职责，不应使用 AutoStation 牌子注册。
 *
 * <p>行为触发依赖列车 {@code FTA_ROUTE_ID} tag 与 RouteStop：仅在 STOP/TERMINATE 时停站， {@code dwellSeconds} 缺失时取
 * {@link RouteStop#DEFAULT_DWELL_SECONDS}。开门失败将跳过关门动作，避免“未开门先关门”的误触发。
 */
public final class AutoStationSignAction extends AbstractNodeSignAction {

  private static final String TAG_ROUTE_ID = "FTA_ROUTE_ID";
  private static final String UNKNOWN_TRAIN_NAME = "unknown";
  private static final String TAG_DOOR_FIRST_STOP_DONE = "FTA_DOOR_FIRST_STOP_DONE";
  private static final String TAG_RUN_AT = "FTA_RUN_AT";
  private static final long DOOR_OPEN_DELAY_TICKS = 20L;

  /**
   * 停稳到开门的秒数（中途站）。编表的车站停车开销 = 居中刹停 + 本值，时刻表据此把"停稳"换算回"压牌"。
   *
   * @return 开门延迟秒数
   */
  public static int doorOpenDelaySeconds() {
    return (int) (DOOR_OPEN_DELAY_TICKS / 20L);
  }

  private static final long DOOR_OPEN_FIRST_DELAY_TICKS = 60L;
  private static final long TICK_MILLIS = 50L;
  private static final int STOP_WAIT_TIMEOUT_TICKS = 200;

  /** 驾驶员控车时等停稳的上限：驾驶员可能要慢慢对位。 */
  private static final int DRIVER_STOP_WAIT_TIMEOUT_TICKS = 2400;

  private static final double TICKS_PER_SECOND = 20.0;

  /** 开往停车位置标时，等停稳多留的 tick（起步与最后制动）。 */
  private static final int MARK_SETTLE_TICKS = 40;

  /** 驾驶员停妥后迟迟不开门，过这么久由站台代为开关门。 */
  private static final long DRIVER_DOOR_TIMEOUT_TICKS = 600L;

  /** 车掌开关门时站台的兜底：车掌那边的时限（含报告延长）到了会代开门，这么久还没开说明这列车开不了门，由站台接手。 */
  private static final long GUARD_DOOR_BACKSTOP_TICKS = 2400L;

  private static final int STOP_STABLE_TICKS = 1;

  /**
   * 自动运行的车要连续停稳多少 tick、且对位动作都走完了，才算停妥：对位进站是“保持进站速度 + 制动停到标”两段起步动作，衔接处会有一拍静止， 只看一拍会当成停妥、停在标前
   * （衔接处对位动作还排着，由此认出）；再多看两拍防信号某一拍的抖动。多一拍就多一拍停站，不往上加。
   */
  private static final int AUTO_STOP_STABLE_TICKS = 3;

  /** 自动运行的车停妥后离停车点还差多远（格）就前移再停：与驾驶员的“停准”同一档。 */
  private static final double AUTO_REALIGN_SHORT_BLOCKS =
      org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopWindow.DEFAULTS.accurateBlocks();

  /** 停短后最多前移几次；仍到不了（多半被信号挡住）就照原地停站，不卡在站外。 */
  private static final int AUTO_REALIGN_ATTEMPTS = 3;

  /** 车已静止、对位动作却还挂着（调度把限速压到 0 等，动作走不完）：静止这么多 tick 就照常停妥，不等超时。 */
  private static final int AUTO_PENDING_SETTLE_TICKS = 20;

  /** 限速低于这个值（格/tick）时不前移：调度正压着车，前移也走不动。 */
  private static final double MIN_REALIGN_SPEED_LIMIT = 0.001;

  private static final int DOOR_OPEN_RETRY_INTERVAL_TICKS = 5;
  private static final long DOOR_CLOSE_EARLY_TICKS = 100L;

  /** 晚点压缩停站时，关门动画开始前车门至少全开多久（tick）。 */
  private static final long DOOR_MIN_OPEN_TICKS = 60L;

  /**
   * 压缩停站的车门下限（秒）的缺省：非旧式动画的车，关门动画提前 {@link #DOOR_CLOSE_EARLY_TICKS} 开始，之前车门至少全开 {@link
   * #DOOR_MIN_OPEN_TICKS}，按秒向上取整。编表按它估运行时能压到多短（旧式动画按实测时长，只会更长）。
   */
  public static final int DEFAULT_DOOR_FLOOR_SECONDS =
      (int) ((DOOR_CLOSE_EARLY_TICKS + DOOR_MIN_OPEN_TICKS + 19L) / 20L);

  private static final int DOOR_OPEN_MAX_ATTEMPTS = 12;
  private static final long DOOR_OPEN_MAX_RETRY_WINDOW_TICKS = 160L;
  private static final double EXIT_OFFSET_DISTANCE_BLOCKS = 3.0;
  private static final double HORIZONTAL_DIAGONAL_MIN_RATIO = Math.tan(Math.PI / 8.0);

  /** 第 4 行缺少开门方向的告警记录：按服务器进程计，同一节点只告警一次。 */
  private static final MissingDoorDirectionWarnings MISSING_DOOR_DIRECTION_WARNINGS =
      new MissingDoorDirectionWarnings();

  private final FetaruteTCAddon plugin;

  /** 同一辆车在同一站台几拍之内的重复触发（联挂合并编组时）只开一个停站。 */
  private final StopTriggerDedup stopTriggers = new StopTriggerDedup();

  public AutoStationSignAction(
      FetaruteTCAddon plugin,
      SignNodeRegistry registry,
      Consumer<String> debugLogger,
      LocaleManager locale,
      SignNodeStorageSynchronizer storageSync) {
    super("autostation", registry, NodeType.STATION, debugLogger, locale, storageSync);
    this.plugin = plugin;
  }

  public AutoStationSignAction(
      SignNodeRegistry registry, Consumer<String> debugLogger, LocaleManager locale) {
    this(null, registry, debugLogger, locale, SignNodeStorageSynchronizer.noop());
  }

  @Override
  protected Optional<SignNodeDefinition> parseDefinition(SignActionEvent info) {
    Optional<SignNodeDefinition> parsed = Optional.empty();
    if (info != null && info.getTrackedSign() != null) {
      parsed = NodeSignDefinitionParser.parse(info.getTrackedSign());
    }
    if (parsed.isEmpty() && info != null) {
      parsed = NodeSignDefinitionParser.parse(info.getSign());
    }
    if (parsed.isEmpty() && info != null) {
      String line2 = info.getLine(2);
      String line3 = info.getLine(3);
      parsed = parseFromLines(line2, line3);
    }
    return parsed.filter(
        definition ->
            definition
                .waypointMetadata()
                .map(metadata -> metadata.kind() == WaypointKind.STATION)
                .orElse(false));
  }

  /** 建牌：节点注册后，第 4 行缺少有效开门方向时当场提醒建牌者（显式写 NONE 的不提醒）。 */
  @Override
  public boolean build(SignChangeActionEvent event) {
    boolean handled = super.build(event);
    if (handled
        && !event.isCancelled()
        && event.getPlayer() != null
        && locale() != null
        && registry.get(event.getBlock()).isPresent()
        && AutoStationDoorDirection.isMissing(event.getLine(3))) {
      event.getPlayer().sendMessage(locale().component("sign.autostation-door-missing"));
    }
    return handled;
  }

  private Optional<SignNodeDefinition> parseFromLines(String line2, String line3) {
    Optional<SignNodeDefinition> parsed = SignTextParser.parseWaypointLike(line2, NodeType.STATION);
    if (parsed.isPresent()) {
      return parsed;
    }
    parsed = SignTextParser.parseWaypointLike(line3, NodeType.STATION);
    if (parsed.isPresent()) {
      return parsed;
    }
    if (line2 != null && line3 != null) {
      return SignTextParser.parseWaypointLike(line2.trim() + line3.trim(), NodeType.STATION);
    }
    return Optional.empty();
  }

  /** 第 4 行未写有效开门方向的站台：停站时写 WARN 提示补上方向，同一节点只提示一次（显式写 NONE 的不提示）。 */
  private void warnMissingDoorDirection(
      SignNodeDefinition definition, String doorLine, SignActionEvent info) {
    if (plugin == null || plugin.getLoggerManager() == null) {
      return;
    }
    MISSING_DOOR_DIRECTION_WARNINGS
        .onStop(definition.nodeId().value(), doorLine, locationText(info))
        .ifPresent(plugin.getLoggerManager()::warn);
  }

  /** 超级电容车停站结束降弓；排了降弓时记一笔调试日志，便于从日志核对。 */
  private void lowerPantograph(MinecartGroup group, String trainName, String stopSessionId) {
    int lowered = SupercapPantograph.lowerIfRaised(plugin, group);
    if (lowered > 0) {
      debug(
          "AutoStation 降弓: train="
              + trainName
              + ", sid="
              + stopSessionId
              + ", attachments="
              + lowered);
    }
  }

  /** 停站最多为等下一班延多久（秒）：不超过发车扣留上限——停站挂的发车门控有兜底失效，停站不能比它长；再往后关门在站台待命。 */
  private static final int MAX_HELD_DWELL_SECONDS =
      (int)
          org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopCoordinator.HOLD_CEILING
              .toSeconds();

  private boolean couplingInProgress() {
    return plugin
        .getRuntimeDispatchService()
        .map(dispatch -> dispatch.coupling().operations().inProgress())
        .orElse(false);
  }

  /** 刚在本站解下、等着接下一班的车离那一班计划发车还有几秒；不是这样的车时为 0。 */
  private static int secondsUntilHeldDeparture(
      FetaruteTCAddon plugin, String dispatchName, String trainName) {
    return plugin
        .getCouplerService()
        .flatMap(
            coupler ->
                coupler
                    .decoupling()
                    .holdDoorsUntil(dispatchName)
                    .or(() -> coupler.decoupling().holdDoorsUntil(trainName)))
        .map(until -> java.time.Duration.between(java.time.Instant.now(), until).getSeconds())
        .map(seconds -> (int) Math.max(0L, Math.min(MAX_HELD_DWELL_SECONDS, seconds)))
        .orElse(0);
  }

  /** 这列车是刚在这块站台拆开后留下的一段：往前开时车头会再压过本站牌子，这一站已经停过。只问解编服务（拆开时记下站台，车的进度一变或车不在了就撤）， 其余列车照常停。 */
  private boolean justSplitHere(TrainProperties properties, String trainName, NodeId platform) {
    String dispatchName =
        readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME).orElse(trainName);
    return plugin
        .getCouplerService()
        .map(
            coupler ->
                coupler.decoupling().splitHere(dispatchName, platform)
                    || coupler.decoupling().splitHere(trainName, platform))
        .orElse(false);
  }

  /**
   * AutoStation 执行入口。
   *
   * <p>兼容 {@code [train]} 与 {@code [cart]} 两种牌子：
   *
   * <ul>
   *   <li>{@code [train]}：使用 {@link SignActionType#GROUP_ENTER} 触发一次。
   *   <li>{@code [cart]}：TrainCarts 只会触发 {@link SignActionType#MEMBER_ENTER}，这里仅处理车头触发一次。
   * </ul>
   *
   * <p>无 RouteId 或无匹配 RouteStop 时视为直接通过。停站时先居中停稳，再延迟一定 tick 开门；dwell 从开门后开始计时。
   */
  @Override
  public void execute(SignActionEvent info) {
    if (info == null) {
      return;
    }
    boolean groupEnter = info.isAction(SignActionType.GROUP_ENTER);
    boolean memberEnter = info.isAction(SignActionType.MEMBER_ENTER);
    if (!groupEnter && !memberEnter) {
      return;
    }
    if (!info.hasGroup() || plugin == null) {
      return;
    }
    if (couplingInProgress()) {
      // 拆分/联挂调用里同步发出的站牌事件：拆下的一半还是临时车名，拆挂服务改好名后自己重新触发本站牌子
      debug("AutoStation 跳过: 拆挂进行中 (train=" + safeTrainName(info) + ") @ " + locationText(info));
      return;
    }
    if (memberEnter && !shouldHandleMemberEnter(info)) {
      return;
    }
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      debug("AutoStation 跳过: 存储未就绪 @ " + locationText(info));
      return;
    }
    Optional<StorageProvider> providerOpt = plugin.getStorageManager().provider();
    if (providerOpt.isEmpty()) {
      debug("AutoStation 跳过: StorageProvider 缺失 @ " + locationText(info));
      return;
    }
    StorageProvider provider = providerOpt.get();
    TrainProperties properties = info.getGroup().getProperties();
    Optional<UUID> routeIdOpt = readRouteId(properties);
    if (routeIdOpt.isEmpty()) {
      debug(
          "AutoStation 跳过: 缺少 FTA_ROUTE_ID (train="
              + safeTrainName(info)
              + ") @ "
              + locationText(info));
      return;
    }
    UUID routeId = routeIdOpt.get();
    Optional<Integer> routeIndexOpt =
        readIntTagValue(properties, RouteProgressRegistry.TAG_ROUTE_INDEX);
    Optional<SignNodeDefinition> definitionOpt =
        registry.get(info.getBlock()).or(() -> parseDefinition(info));
    if (definitionOpt.isEmpty()) {
      debug(
          "AutoStation 跳过: 无法解析节点 (train="
              + safeTrainName(info)
              + ", route="
              + shortUuid(routeId)
              + ") @ "
              + locationText(info));
      return;
    }
    SignNodeDefinition definition = definitionOpt.get();
    Optional<Integer> dwellOpt =
        resolveDwellSeconds(provider, routeIdOpt.get(), definition, routeIndexOpt);
    if (dwellOpt.isEmpty()) {
      debug(
          "AutoStation 跳过: 未匹配 RouteStop (train="
              + safeTrainName(info)
              + ", route="
              + shortUuid(routeId)
              + ", node="
              + definition.nodeId().value()
              + ", idx="
              + routeIndexOpt.map(String::valueOf).orElse("-")
              + ") @ "
              + locationText(info));
      return;
    }
    int dwellSeconds = dwellOpt.get();
    if (dwellSeconds <= 0) {
      debug(
          "AutoStation 跳过: dwell<=0 (train="
              + safeTrainName(info)
              + ", route="
              + shortUuid(routeId)
              + ", node="
              + definition.nodeId().value()
              + ", dwell="
              + dwellSeconds
              + ") @ "
              + locationText(info));
      return;
    }

    var group = info.getGroup();
    String trainName = safeTrainName(info);
    if (plugin
        .getCouplerService()
        .map(coupler -> coupler.coupling().approaching(trainName))
        .orElse(false)) {
      // 联挂进站中：挂上后对合并的列车重新触发这块牌子
      debug("AutoStation 跳过: 联挂进站中 (train=" + trainName + ") @ " + locationText(info));
      return;
    }
    if (justSplitHere(properties, trainName, definition.nodeId())) {
      debug(
          "AutoStation 跳过: 本站刚拆开 (train="
              + trainName
              + ", node="
              + definition.nodeId().value()
              + ") @ "
              + locationText(info));
      return;
    }
    LinkedStops.Arrival linked =
        plugin
            .getCouplerService()
            .map(coupler -> coupler.coupling().linkedArrival(trainName, definition.nodeId()))
            .orElse(LinkedStops.Arrival.NONE);
    if (linked == LinkedStops.Arrival.DUPLICATE) {
      debug(
          "AutoStation 跳过: 挂上后重复触发 (train="
              + trainName
              + ", node="
              + definition.nodeId().value()
              + ") @ "
              + locationText(info));
      return;
    }
    if (!stopTriggers.claim(trainName, definition.nodeId(), Bukkit.getCurrentTick())) {
      debug(
          "AutoStation 跳过: 同一站台重复触发 (train="
              + trainName
              + ", node="
              + definition.nodeId().value()
              + ") @ "
              + locationText(info));
      return;
    }
    String stopSessionId = shortUuid(UUID.randomUUID());
    boolean driverControlled = driverControlled(properties);
    // 车上有车掌：车门归车掌。自动运行的车照样由站台对位停车，只把停站与站台侧交给车掌。
    boolean guardDoors = !driverControlled && guardOperatesDoors(properties);
    Optional<CoupleService.AwaitedJoin> awaited =
        linked == LinkedStops.Arrival.FIRST_STOP
            ? Optional.empty()
            : awaitedJoin(trainName, routeId, definition.nodeId(), group);
    // 驾驶员控车时停车点照本车节数，由驾驶员自己停；进站途中交还自动运行时再按挂上以后的节数对位。
    Optional<StopMarks.Selected> mark =
        stopMarkFor(info, driverControlled ? Optional.empty() : awaited);
    // 刚挂上、先到的车已经按挂上后的整列停好（车头在整列节数的停车位置标处，或没有标志时整列中点在车站牌子附近）：原地停站；
    // 没停好时有标志照常开往标志，没有标志平稳补到中点
    boolean justLinked = linked == LinkedStops.Arrival.FIRST_STOP;
    StopMarkSign held = justLinked ? heldMark(info, group, mark) : null;
    CouplingStop couplingStop =
        new CouplingStop(
            justLinked,
            justLinked && (mark.isPresent() ? held != null : mergedCenterNearSign(info, group)),
            held,
            awaited);
    String doorLine = info.getLine(3);
    AutoStationDoorDirection doorDirection = AutoStationDoorDirection.parse(doorLine);
    warnMissingDoorDirection(definition, doorLine, info);
    DriverStationStop driverStop = null;
    int alignTicks = 0;
    Alignment alignment = null;
    // 开关门的车厢按车头对准的停车位置标选（此刻的车头就是行进方向上的第一节）；按车站牌子居中时全车开门。
    DoorCars doorCars;
    if (driverControlled) {
      // 驾驶员控车时由驾驶员自己停车：不对位，也不能清掉控车动作；把停车点与站台侧交给驾驶员。
      // 刚挂上时原地停站：停车点就是车头此刻的位置。
      doorCars =
          AutoStationDoorController.doorCars(
              group,
              couplingStop.inPlace()
                  ? couplingStop.heldMark()
                  : mark.map(selected -> selected.mark().sign()).orElse(null));
      driverStop =
          beginDriverStop(
              info, properties, definition, doorDirection, mark, doorCars, couplingStop.inPlace());
    } else {
      alignment = alignForStop(info, group, mark, couplingStop);
      alignTicks = alignment.ticks();
      doorCars =
          AutoStationDoorController.doorCars(
              group, couplingStop.inPlace() ? couplingStop.heldMark() : alignment.mark());
      if (guardDoors) {
        driverStop =
            beginDriverStop(
                info, properties, definition, doorDirection, Optional.empty(), doorCars, false);
      }
    }

    FacingResult facingResult = resolveFacingDirectionResult(info);
    BlockFace facingDirection = facingResult.face();
    String facingSource = facingResult.source();
    AutoStationDoorController.DoorChimeSettings chimeSettings = resolveChimeSettings();
    AutoStationDoorController.DoorSession session =
        AutoStationDoorController.plan(
            group, facingDirection, facingResult.vector(), doorDirection, chimeSettings, doorCars);
    String planSummary = session.debugSummary();
    boolean firstStop = isFirstStop(properties);
    if (session.hasActions()) {
      String animationSummary = summarizeDoorAnimations(group);
      debug(
          "AutoStation 停站: nodeId="
              + definition.nodeId().value()
              + ", train="
              + trainName
              + ", route="
              + shortUuid(routeId)
              + ", sid="
              + stopSessionId
              + ", dwell="
              + dwellSeconds
              + "s, worldDoorDirection="
              + doorDirection
              + ", facing="
              + facingDirection
              + ", source="
              + facingSource
              + ", facingVector="
              + formatFacingVector(facingResult.vector())
              + ", plan="
              + planSummary
              + ", animations="
              + animationSummary
              + ", attachments="
              + summarizeAttachments(group)
              + " @ "
              + locationText(info));
    } else {
      debug(
          "AutoStation 跳过: 无法判定开门世界方向 (worldDoorDirection="
              + doorDirection
              + ", facing="
              + facingDirection
              + ", source="
              + facingSource
              + ", facingVector="
              + formatFacingVector(facingResult.vector())
              + ", train="
              + trainName
              + ", route="
              + shortUuid(routeId)
              + ", sid="
              + stopSessionId
              + ", plan="
              + planSummary
              + ", attachments="
              + summarizeAttachments(group)
              + ") @ "
              + locationText(info));
    }
    scheduleDoorSequence(
        info,
        definition,
        trainName,
        routeId,
        stopSessionId,
        dwellSeconds,
        doorDirection,
        facingDirection,
        facingResult.vector(),
        facingSource,
        chimeSettings,
        session,
        firstStop,
        driverControlled ? DRIVER_STOP_WAIT_TIMEOUT_TICKS : STOP_WAIT_TIMEOUT_TICKS + alignTicks,
        driverStop,
        driverControlled,
        alignment,
        couplingStop);
  }

  /**
   * 自动运行对位的结果。
   *
   * @param ticks 开往标志预计还要多少 tick（等停稳时多留这么久）；按 TrainCarts 居中或就地停住时为 0
   * @param selected 车头对准的停车位置标；按车站牌子居中时为 {@code null}
   */
  private record Alignment(int ticks, StopMarks.Selected selected) {

    /** 车头对准的停车位置标；按车站牌子居中时为 {@code null}。 */
    StopMarkSign mark() {
      return selected == null ? null : selected.mark().sign();
    }
  }

  /**
   * 联挂站上的停站。
   *
   * @param linked 刚在这条股道挂上（挂上后第一次停站）
   * @param inPlace 刚挂上、先到的车已经按挂上后的整列停好（车头在整列节数的停车位置标处，或没有标志时整列中点在车站牌子附近）：原地停站，不对位、不挪车
   * @param heldMark 原地停站时车头对准的停车位置标，按它选开门的车厢；没有时为 {@code null}（全车开门）
   * @param awaited 先到、要停着等对方挂上来：挂上以后整列几节、停在哪；不等时为空
   */
  private record CouplingStop(
      boolean linked,
      boolean inPlace,
      StopMarkSign heldMark,
      Optional<CoupleService.AwaitedJoin> awaited) {}

  /** 自动运行的对位：刚挂上的原地停或平稳补到中点，其余照常（见 {@link #alignTrain}）。 */
  private Alignment alignForStop(
      SignActionEvent info,
      MinecartGroup group,
      Optional<StopMarks.Selected> mark,
      CouplingStop couplingStop) {
    if (couplingStop.inPlace()) {
      return holdInPlace(group);
    }
    if (couplingStop.linked() && mark.isEmpty()) {
      return centerSmoothly(info, group);
    }
    return alignTrain(info, mark, couplingStop.awaited());
  }

  /** 刚挂上、整列中点在车站牌子附近（差不到停准的范围，或已越过牌子）：不再挪车。量不出时为 false。 */
  private static boolean mergedCenterNearSign(SignActionEvent info, MinecartGroup group) {
    org.bukkit.Location center = info.getCenterLocation();
    if (center == null || center.getWorld() == null || group == null || group.isEmpty()) {
      return false;
    }
    double offset =
        StopAlignment.groupOffset(
            group, center.getWorld().getUID(), center.toVector(), StopAlignment.Reference.CENTER);
    return !Double.isNaN(offset) && offset >= -AUTO_REALIGN_SHORT_BLOCKS;
  }

  /**
   * 刚挂上、整列中点离车站牌子还差得多：平稳往前补到中点（与开往停车位置标同一套起步与制动）。不交给 TrainCarts 居中：它挑“中间那节车”对准牌子再按整列车身修正，
   * 挂上后整列会往前猛窜半个到一个车距，中间那节已过牌子时修正方向还会算反。量不出时才照 TrainCarts 居中。
   */
  private Alignment centerSmoothly(SignActionEvent info, MinecartGroup group) {
    org.bukkit.Location center = info.getCenterLocation();
    double offset =
        center == null || center.getWorld() == null
            ? Double.NaN
            : StopAlignment.groupOffset(
                group,
                center.getWorld().getUID(),
                center.toVector(),
                StopAlignment.Reference.CENTER);
    if (Double.isNaN(offset)) {
      centerTrain(info);
      return new Alignment(0, null);
    }
    return new Alignment(Math.max(0, launchHeadBy(group, -offset)), null);
  }

  /**
   * 先到联挂站、等对方挂上来、股道上没有停车位置标：照挂上以后的整列中点对准车站牌子停，第一节车厢中心停在牌子前方 {@code lead} 格（见 {@link
   * CoupleService.AwaitedJoin#midpointLeadBlocks}），挂上以后不必再挪。
   *
   * @return 预计还要多少 tick；量不出车长或方向时为 -1（照常按车站牌子居中）
   */
  private int launchToMergedCenter(SignActionEvent info, MinecartGroup group, double lead) {
    org.bukkit.Location center = info.getCenterLocation();
    if (center == null || group.isEmpty() || Double.isNaN(lead)) {
      return -1;
    }
    double ahead =
        StopAlignment.signedOffset(
            StopAlignment.head(group),
            center.toVector(),
            group.head().getRailTracker().getMotionVector());
    if (Double.isNaN(ahead)) {
      return -1;
    }
    return launchHeadBy(group, lead - ahead);
  }

  /** 先到联挂站、要停着等对方挂上来时挂上以后整列几节、停在哪（见 {@link CoupleService#awaitedJoin}）；不等时为空。 */
  private Optional<CoupleService.AwaitedJoin> awaitedJoin(
      String trainName, UUID routeId, NodeId node, MinecartGroup group) {
    return plugin
        .getCouplerService()
        .flatMap(coupler -> coupler.coupling().awaitedJoin(trainName, routeId, node, group));
  }

  /** 各节车厢的实体 UUID，按编组次序从车头排起；取不到实体的为 {@code null}。 */
  private static List<UUID> cartsFromHead(MinecartGroup group) {
    List<UUID> carts = new ArrayList<>(group.size());
    for (MinecartMember<?> member : group) {
      carts.add(
          member == null || member.getEntity() == null ? null : member.getEntity().getUniqueId());
    }
    return carts;
  }

  /** 刚挂上时原地停站：只把还在溜的车停住，不开往任何停车点。 */
  private static Alignment holdInPlace(MinecartGroup group) {
    if (group != null && group.isMoving()) {
      group.getActions().launchReset();
      group.stop();
    }
    return new Alignment(0, null);
  }

  /** 刚挂上时车头是不是已经停在整列节数的停车位置标处（先到的车按挂上后的整列停好）：是就返回那块标志，否则为 {@code null}。 */
  private static StopMarkSign heldMark(
      SignActionEvent info, MinecartGroup group, Optional<StopMarks.Selected> mark) {
    if (mark.isEmpty()) {
      return null;
    }
    double offset = markOffset(info, group, mark.get());
    return Math.abs(offset) <= AUTO_REALIGN_SHORT_BLOCKS ? mark.get().mark().sign() : null;
  }

  /**
   * 自动运行对位：股道上有对应节数的停车位置标时让车头最前端停在标志处，否则按 TrainCarts 把列车中心停在牌子处。停车位置标已经选好（见 {@link #stopMarkFor}）。
   *
   * @return 开往标志预计还要多少 tick，以及车头对准了哪块标志
   */
  private Alignment alignTrain(
      SignActionEvent info,
      Optional<StopMarks.Selected> mark,
      Optional<CoupleService.AwaitedJoin> awaited) {
    MinecartGroup group = info.getGroup();
    if (mark.isPresent() && group != null) {
      StopMarks.Mark selected = mark.get().mark();
      org.bukkit.block.Block markRail =
          info.getWorld().getBlockAt(selected.rail().x(), selected.rail().y(), selected.rail().z());
      int ticks = launchHeadTo(group, markRail);
      if (ticks >= 0) {
        return new Alignment(ticks, mark.get());
      }
      Vector motion = group.head().getRailTracker().getMotionVector();
      double offset =
          StopAlignment.signedOffset(StopAlignment.front(group), selected.point(), motion);
      if (!(offset < -StopMarks.BEHIND_TOLERANCE_BLOCKS)) {
        // 车头已到或已过标志（进站途中交还时可能如此）：就地停住，不退回去按车站牌子居中。
        group.getActions().launchReset();
        group.stop();
        return new Alignment(0, mark.get());
      }
    }
    if (group != null
        && mark.isEmpty()
        && awaited.isPresent()
        && awaited.get().mergedCars() > group.size()) {
      // 先到联挂站、等对方挂上来、没有停车位置标：照挂上以后的整列中点对准牌子停
      int ticks = launchToMergedCenter(info, group, awaited.get().midpointLeadBlocks());
      if (ticks >= 0) {
        return new Alignment(ticks, null);
      }
    }
    if (group != null && !group.isMoving() && centerPastSign(info, group)) {
      // 停着的车中心已过车站牌子（拆开后解下的前一段、挂上后的整列重新触发本站牌子）：按牌子居中要往回开，
      // TrainCarts 起步那一拍就把整列调头，驾驶员与车掌判车门左右跟着反。就地停住，不往回挪。
      group.getActions().launchReset();
      group.stop();
      return new Alignment(0, null);
    }
    centerTrain(info);
    return new Alignment(0, null);
  }

  /** 列车中心沿此刻的前进方向已越过车站牌子（见 {@link StopAlignment#centerPastStopPoint}）；量不出时为 false。 */
  private static boolean centerPastSign(SignActionEvent info, MinecartGroup group) {
    org.bukkit.Location center = info.getCenterLocation();
    if (center == null || center.getWorld() == null) {
      return false;
    }
    return StopAlignment.centerPastStopPoint(
        StopAlignment.groupOffset(
            group, center.getWorld().getUID(), center.toVector(), StopAlignment.Reference.CENTER));
  }

  /**
   * 这列车在本站该停的停车位置标；没有标志、节数对不上或量不出方向时为空。
   *
   * @param awaited 先到联挂站、等对方挂上来时挂上以后整列几节：有这个节数的标志时停在它那里，给后到的车留出位置，没有时照本车节数选
   */
  private Optional<StopMarks.Selected> stopMarkFor(
      SignActionEvent info, Optional<CoupleService.AwaitedJoin> awaited) {
    if (plugin == null || !info.hasRails() || !info.hasGroup()) {
      return Optional.empty();
    }
    StopMarkIndex index = plugin.getStopMarkIndex();
    org.bukkit.Location center = info.getCenterLocation();
    MinecartGroup group = info.getGroup();
    if (index == null || center == null || group == null || group.isEmpty()) {
      return Optional.empty();
    }
    // 车头压在车站牌子上：取车头此刻在轨道上的行进方向，比车尾指向车头的连线准（列车后半截可能还在弯道上）。
    return index.select(
        info.getRails(),
        center.toVector(),
        group.head().getRailTracker().getMotionVector(),
        group.size(),
        awaited.map(join -> OptionalInt.of(join.mergedCars())).orElse(OptionalInt.empty()));
  }

  /**
   * 把车头最前端沿轨道送到标志所在的轨道：从第一节车厢中心沿轨道量到标志，少走半个车体长度（{@link
   * StopAlignment#frontOffsetBlocks}），保持进站速度开到标志前，再按本车的常用制动减速度停下（见 {@link StopMarks#approach}）。
   *
   * @return 预计还要多少 tick；前方沿轨道找不到这段轨道时为 -1（不倒车）
   */
  private int launchHeadTo(MinecartGroup group, org.bukkit.block.Block markRail) {
    MinecartMember<?> head = group.head();
    com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint walk =
        new com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint(
            head.getRailTracker().getState().clone());
    if (!walk.moveFindRail(markRail, 2.0 * StopMarks.SEARCH_BLOCKS)) {
      return -1;
    }
    // 走的是第一节车厢中心到标志的距离；停的是车头最前端，少走半个车体长度。
    return launchHeadBy(group, walk.movedTotal - StopAlignment.frontOffsetBlocks(group));
  }

  /**
   * 让第一节车厢沿行进方向再走 {@code distance} 格停下：保持进站速度开到制动点，再按本车的常用制动减速度停下（见 {@link StopMarks#approach}）。
   * 不倒车：{@code distance} 不为正时就地停住。
   *
   * @return 预计还要多少 tick
   */
  private int launchHeadBy(MinecartGroup group, double distance) {
    MinecartMember<?> head = group.head();
    group.getActions().launchReset();
    if (!(distance > 0.01)) {
      group.stop();
      return 0;
    }
    ConfigManager.ConfigView config = plugin.getConfigManager().current();
    double decelBps2 =
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver()
            .resolve(group.getProperties(), config)
            .decelBps2();
    StopMarks.Approach approach =
        StopMarks.approach(
            distance,
            head.getRealSpeedLimited(),
            config.runtimeSettings().approachSpeedBps() / TICKS_PER_SECOND,
            decelBps2 / (TICKS_PER_SECOND * TICKS_PER_SECOND));
    BlockFace direction =
        com.bergerkiller.bukkit.tc.Util.vecToFace(head.getRailTracker().getMotionVector(), false);
    if (approach.holdBlocks() > 0.0) {
      head.getActions().addActionLaunch(direction, approach.holdBlocks(), approach.holdBpt());
      head.getActions().addActionLaunch(approach.brakeBlocks(), 0.0);
    } else {
      head.getActions().addActionLaunch(direction, approach.brakeBlocks(), 0.0);
    }
    return approach.ticks() + MARK_SETTLE_TICKS;
  }

  /** 越站：驾驶员越过停车点太多，本站不停、不开门。到站与发车照常记下（交路进度往前走，晚点照算），这趟车的这一站在站台屏上显示为取消， 出站不扣车，下一拍调度按新进度重新控车。 */
  private void skipStation(
      SignActionEvent info,
      SignNodeDefinition definition,
      String trainName,
      MinecartGroup group,
      DriverStationStop stop) {
    stop.markSkipped();
    if (plugin == null) {
      return;
    }
    plugin
        .getRuntimeDispatchService()
        .ifPresent(
            dispatch -> {
              // 本站不停：多模车涉及内燃的换模推迟到下一个停车点
              Runnable arrival = () -> dispatch.handleStationArrival(group, definition);
              plugin
                  .getPowerModeSwitcher()
                  .ifPresentOrElse(power -> power.passThrough(arrival), arrival);
              dispatch.stationStops().handleDeparture(group, definition);
            });
    readIntTagValue(group.getProperties(), RouteProgressRegistry.TAG_ROUTE_INDEX)
        .ifPresent(
            index ->
                plugin.getTimetableService().ifPresent(table -> table.skipStop(trainName, index)));
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () ->
                plugin
                    .getRuntimeDispatchService()
                    .ifPresent(dispatch -> dispatch.refreshSignal(group)));
    debug(
        "AutoStation 越站: nodeId="
            + definition.nodeId().value()
            + ", train="
            + trainName
            + String.format(Locale.ROOT, ", 越过 %.2f 格", stop.offsetBlocks())
            + " @ "
            + locationText(info));
  }

  private static void centerTrain(SignActionEvent info) {
    com.bergerkiller.bukkit.tc.Station station = new com.bergerkiller.bukkit.tc.Station(info);
    info.getGroup().getActions().launchReset();
    station.centerTrain();
  }

  /**
   * 驾驶员控车进站：股道上有对应节数的停车位置标时以标志的轨道中心为车头停车点，否则以牌子轨道中心为列车中心停车点，连同站台侧交给驾驶员。 取不到停车点时返回 {@code
   * null}（按普通停站处理）。
   */
  private DriverStationStop beginDriverStop(
      SignActionEvent info,
      TrainProperties properties,
      SignNodeDefinition definition,
      AutoStationDoorDirection doorDirection,
      Optional<StopMarks.Selected> mark,
      DoorCars doorCars,
      boolean inPlace) {
    org.bukkit.Location center = info.getCenterLocation();
    if (center == null || center.getWorld() == null) {
      return null;
    }
    MinecartGroup group = info.getGroup();
    boolean held = inPlace && group != null && !group.isEmpty();
    String stationName =
        plugin
            .getStationDirectory()
            .flatMap(directory -> directory.snapshot().stationOfNode(definition.nodeId().value()))
            .map(org.fetarute.fetaruteTCAddon.company.api.StationDirectory.StationEntry::name)
            .orElse(definition.nodeId().value());
    DriverStationStop stop =
        new DriverStationStop(
            definition.nodeId(),
            stationName,
            center.getWorld().getUID(),
            held
                ? StopAlignment.front(group)
                : mark.map(selected -> selected.mark().point()).orElse(center.toVector()),
            held || mark.isPresent()
                ? StopAlignment.Reference.HEAD
                : StopAlignment.Reference.CENTER,
            doorDirection.toBlockFace().orElse(null),
            doorDirection == AutoStationDoorDirection.BOTH,
            doorDirection != AutoStationDoorDirection.NONE);
    stop.setDoorCars(doorCars);
    plugin.getControlAuthority().beginStationStop(properties, stop);
    return stop;
  }

  /** 驾驶员或车掌开关门的停站，等发车那一步这一拍怎么办。 */
  enum CrewDeparture {
    /** 还扣着。 */
    HOLD,
    /** 放出出站许可，等驾驶员起步（驾驶员控车）。 */
    RELEASE_TO_DRIVER,
    /** 与自动运行同一放行动作，列车立即开走（车掌开关门的自动运行列车）。 */
    RELEASE_AUTOMATIC
  }

  /**
   * 驾驶员或车掌开关门的停站，等发车那一步依次过三道：出站门控、车掌的发车信号、ATO 驾驶员的确认发车。
   *
   * <p>车上有车掌时，出站门控不放行也要问车掌（他据此暂停计时）；ATO 驾驶员只在车掌放行之后才问，提示与计时从那时开始。
   *
   * @param canDepart 出站门控此刻是否放行
   * @param guardDoors 车上有车掌
   * @param guardHolds 问车掌是否还扣着（参数为出站门控此刻是否放行）
   * @param driverControlled 驾驶员在控车
   * @param atoHolds 问 ATO 驾驶员是否还扣着
   */
  static CrewDeparture crewDeparture(
      boolean canDepart,
      boolean guardDoors,
      java.util.function.Predicate<Boolean> guardHolds,
      boolean driverControlled,
      java.util.function.BooleanSupplier atoHolds) {
    if (guardDoors && guardHolds.test(canDepart)) {
      return CrewDeparture.HOLD;
    }
    if (!canDepart) {
      return CrewDeparture.HOLD;
    }
    if (driverControlled) {
      return CrewDeparture.RELEASE_TO_DRIVER;
    }
    return atoHolds.getAsBoolean() ? CrewDeparture.HOLD : CrewDeparture.RELEASE_AUTOMATIC;
  }

  /** 车上是否有车掌在岗（车门归车掌）。 */
  private boolean guardOperatesDoors(TrainProperties properties) {
    ControlAuthority authority = plugin == null ? null : plugin.getControlAuthority();
    return authority != null && authority.guardOperatesDoors(properties);
  }

  /** 列车此刻是否由驾驶员控制（驾驶员控车时站台不替它停车、对位、加等待动作）。 */
  private boolean driverControlled(TrainProperties properties) {
    ControlAuthority authority = plugin == null ? null : plugin.getControlAuthority();
    return authority != null && authority.isDriverControlled(properties);
  }

  /**
   * 压缩停站不能短于车门走完一个开关过程：关门动画开始之前车门至少全开 {@link #DOOR_MIN_OPEN_TICKS}。
   *
   * <p>关门动画在停站结束前开始（legacy 动画按其实测时长，其余按 {@link #DOOR_CLOSE_EARLY_TICKS}）； 晚点压缩到 10 秒时，长的 legacy
   * 关门动画会让车门刚开就关。只对压缩生效，计划停站本身不受影响。
   */
  private static int doorFloorSeconds(AutoStationDoorController.DoorSession session) {
    if (session != null && session.usesLegacyDoorAnimation()) {
      long estimated = session.estimatedCloseDurationTicks();
      if (estimated > 0L) {
        return (int) ((estimated + DOOR_MIN_OPEN_TICKS + 19L) / 20L);
      }
    }
    return DEFAULT_DOOR_FLOOR_SECONDS;
  }

  /**
   * MEMBER_ENTER 触发的去重规则：
   *
   * <ul>
   *   <li>仅处理 {@code [cart]} 牌子：{@code [train]} 牌子应由 GROUP_ENTER 触发，避免重复执行。
   *   <li>仅处理车头触发：避免长编组每节车厢重复触发。
   * </ul>
   */
  private boolean shouldHandleMemberEnter(SignActionEvent info) {
    if (info == null || !info.hasGroup() || !info.hasMember()) {
      return false;
    }
    MinecartGroup group = info.getGroup();
    MinecartMember<?> member = info.getMember();
    if (group == null || member == null || group.head() != member) {
      return false;
    }
    SignActionHeader header =
        info.getTrackedSign() != null ? info.getTrackedSign().getHeader() : null;
    if (header == null) {
      header = SignActionHeader.parse(info.getLine(0));
    }
    return header != null && header.isCart();
  }

  private void scheduleDoorSequence(
      SignActionEvent info,
      SignNodeDefinition definition,
      String trainName,
      UUID routeId,
      String stopSessionId,
      int dwellSeconds,
      AutoStationDoorDirection doorDirection,
      BlockFace facingDirection,
      Vector facingVector,
      String facingSource,
      AutoStationDoorController.DoorChimeSettings chimeSettings,
      AutoStationDoorController.DoorSession session,
      boolean firstStop,
      int stopWaitTimeoutTicks,
      DriverStationStop driverStop,
      boolean driverControlledAtEntry,
      Alignment initialAlignment,
      CouplingStop couplingStop) {
    if (plugin == null || info == null || !info.hasGroup()) {
      return;
    }
    MinecartGroup group = info.getGroup();
    if (group == null) {
      return;
    }
    new org.bukkit.scheduler.BukkitRunnable() {
      private int waitedTicks = 0;

      /** 进站时是驾驶员在停车（停站对象跟踪对标与越站）；车掌开关门的自动运行列车由站台对位，不跟踪。 */
      private boolean driverStopping = driverControlledAtEntry;

      private int stoppedTicks = 0;

      /** 静止但对位动作还挂着的连续 tick 数。 */
      private int pendingStillTicks = 0;

      private int realignAttempts = 0;

      /** 最近一次自动对位：停短前移时朝同一块标志、按同一口径量。 */
      private Alignment alignment = initialAlignment;

      private int timeoutTicks = stopWaitTimeoutTicks;

      private DriverStationStop stop = driverStop;

      private AutoStationDoorController.DoorSession doorSession = session;

      @Override
      public void run() {
        if (!group.isValid()) {
          if (stop != null) {
            stop.end();
          }
          cancel();
          return;
        }
        if (stop != null && driverStopping && !driverControlled(group.getProperties())) {
          // 进站途中交还了自动运行：按自动运行对位停车，开关门的车厢按这次对位重新选。车上有车掌时停站仍交给车掌开关门。
          driverStopping = false;
          if (!guardOperatesDoors(group.getProperties())) {
            stop.end();
            stop = null;
          }
          alignment =
              alignForStop(info, group, stopMarkFor(info, couplingStop.awaited()), couplingStop);
          timeoutTicks = waitedTicks + STOP_WAIT_TIMEOUT_TICKS + alignment.ticks();
          doorSession =
              AutoStationDoorController.plan(
                  group,
                  facingDirection,
                  facingVector,
                  doorDirection,
                  chimeSettings,
                  AutoStationDoorController.doorCars(
                      group, couplingStop.inPlace() ? couplingStop.heldMark() : alignment.mark()));
        }
        if (stop != null && driverStopping) {
          stop.updateOffset(
              StopAlignment.groupOffset(group, stop.worldId(), stop.stopPoint(), stop.reference()));
          if (stop.window().classify(stop.offsetBlocks()) == StopAlignment.Outcome.SKIPPED) {
            // 越过停车点太多：越站，本站不停，列车继续开。
            cancel();
            skipStation(info, definition, trainName, group, stop);
            return;
          }
        }
        if (!driverStopping && !group.isMoving()) {
          dropStaleLaunches(group, trainName);
        }
        // 自动运行的车对位动作还没走完（对位进站途中）时不算停着；静止够久了照样算
        boolean alignmentPending = !driverStopping && stillToMove(group);
        pendingStillTicks = !group.isMoving() && alignmentPending ? pendingStillTicks + 1 : 0;
        if (!group.isMoving()
            && (!alignmentPending || pendingStillTicks >= AUTO_PENDING_SETTLE_TICKS)) {
          stoppedTicks++;
          // 驾驶员停得太靠前时等他前移，不当作停妥。
          boolean aligned =
              stop == null
                  || !driverStopping
                  || stop.window().classify(stop.offsetBlocks()) != StopAlignment.Outcome.SHORT;
          int stable = driverStopping ? STOP_STABLE_TICKS : AUTO_STOP_STABLE_TICKS;
          if (stoppedTicks >= stable && aligned) {
            Optional<Alignment> again = Optional.empty();
            double offset = Double.NaN;
            if (!driverStopping
                && realignAttempts < AUTO_REALIGN_ATTEMPTS
                && alignment != null
                && alignment.selected() != null
                && group.getProperties().getSpeedLimit() >= MIN_REALIGN_SPEED_LIMIT) {
              offset = markOffset(info, group, alignment.selected());
              if (offset < -AUTO_REALIGN_SHORT_BLOCKS) {
                // 停短了（多半是进站途中被刹了一下）：朝同一块标志前移再停；沿轨道找不到标志就地停妥，不退回去居中
                again = realignToMark(info, alignment.selected());
              }
            }
            if (again.isPresent()) {
              realignAttempts++;
              stoppedTicks = 0;
              pendingStillTicks = 0;
              alignment = again.get();
              timeoutTicks =
                  Math.max(
                      timeoutTicks, waitedTicks + STOP_WAIT_TIMEOUT_TICKS + again.get().ticks());
              debug(
                  "AutoStation 停短前移: train="
                      + trainName
                      + String.format(Locale.ROOT, ", offset=%.2f", offset)
                      + ", attempt="
                      + realignAttempts
                      + " @ "
                      + locationText(info));
              return;
            }
            cancel();
            if (!driverStopping) {
              debugStopOffset(info, group, trainName, alignment, waitedTicks, false);
            }
            handleStop(
                info,
                definition,
                trainName,
                routeId,
                stopSessionId,
                dwellSeconds,
                doorDirection,
                facingDirection,
                facingVector,
                facingSource,
                chimeSettings,
                doorSession,
                false,
                firstStop,
                stop);
            return;
          }
        } else {
          stoppedTicks = 0;
        }
        waitedTicks++;
        if (waitedTicks >= timeoutTicks) {
          cancel();
          if (!driverStopping) {
            debugStopOffset(info, group, trainName, alignment, waitedTicks, true);
          }
          handleStop(
              info,
              definition,
              trainName,
              routeId,
              stopSessionId,
              dwellSeconds,
              doorDirection,
              facingDirection,
              facingVector,
              facingSource,
              chimeSettings,
              doorSession,
              true,
              firstStop,
              stop);
        }
      }
    }.runTaskTimer(plugin, 1L, 1L);
  }

  /** 停短前移：朝同一块停车位置标再开过去；沿轨道前方找不到那段轨道时为空（不倒车、不改按牌子居中）。 */
  private Optional<Alignment> realignToMark(SignActionEvent info, StopMarks.Selected selected) {
    MinecartGroup group = info.getGroup();
    if (group == null || group.isEmpty()) {
      return Optional.empty();
    }
    StopMarks.Mark mark = selected.mark();
    int ticks =
        launchHeadTo(
            group, info.getWorld().getBlockAt(mark.rail().x(), mark.rail().y(), mark.rail().z()));
    return ticks < 0 ? Optional.empty() : Optional.of(new Alignment(ticks, selected));
  }

  /** 车头离停车位置标多远：越过为正、未到为负；与对位同一块标志、同一口径（车头最前端）。量不出时为 {@code NaN}。 */
  private static double markOffset(
      SignActionEvent info, MinecartGroup group, StopMarks.Selected selected) {
    org.bukkit.Location center = info.getCenterLocation();
    if (center == null || center.getWorld() == null || group == null || group.isEmpty()) {
      return Double.NaN;
    }
    return StopAlignment.groupOffset(
        group, center.getWorld().getUID(), selected.mark().point(), StopAlignment.Reference.HEAD);
  }

  /** 列车或车头还排着对位动作（等待与调度的发车动作以外的动作）：对位进站途中。 */
  private static boolean stillToMove(MinecartGroup group) {
    if (pendingMove(group.getActions())) {
      return true;
    }
    MinecartMember<?> head = group.isEmpty() ? null : group.head();
    return head != null && pendingMove(head.getActions());
  }

  private static boolean pendingMove(
      com.bergerkiller.bukkit.tc.controller.components.ActionTracker actions) {
    return actions != null
        && actions.getScheduledActions().stream()
            .anyMatch(
                action ->
                    !(action instanceof com.bergerkiller.bukkit.tc.actions.WaitAction)
                        && !action.hasTag(TrainCartsRuntimeHandle.ACTION_TAG_LAUNCH));
  }

  private static boolean pendingLaunch(
      com.bergerkiller.bukkit.tc.controller.components.ActionTracker actions) {
    return actions != null
        && actions.getScheduledActions().stream()
            .anyMatch(action -> action.hasTag(TrainCartsRuntimeHandle.ACTION_TAG_LAUNCH));
  }

  /**
   * 对位进站时排进来的调度发车动作（进站途中信号变化或重算信号时调度会下发）：对位走完、车停住时清掉，不让它在停站前把车开走。 停站开始后调度本来也会清队列， 这里只是提前到车停住的那一拍。
   */
  private void dropStaleLaunches(MinecartGroup group, String trainName) {
    if (stillToMove(group)) {
      return;
    }
    MinecartMember<?> head = group.isEmpty() ? null : group.head();
    if (!pendingLaunch(group.getActions()) && (head == null || !pendingLaunch(head.getActions()))) {
      return;
    }
    group.getActions().clear();
    if (head != null) {
      head.getActions().clear();
    }
    group.stop();
    debug("AutoStation 清掉进站途中的发车动作: train=" + trainName);
  }

  /** 自动运行的车停妥（或等停稳超时）时离停车点多远：越过为正、未到为负，按停车位置标对车头、否则按车站牌子对列车中心。查欠标用，只在调试日志开着时算。 */
  private void debugStopOffset(
      SignActionEvent info,
      MinecartGroup group,
      String trainName,
      Alignment alignment,
      int waitedTicks,
      boolean timedOut) {
    if (group == null
        || group.isEmpty()
        || plugin == null
        || plugin.getLoggerManager() == null
        || !plugin.getLoggerManager().debugEnabled()) {
      return;
    }
    StopMarks.Selected selected = alignment == null ? null : alignment.selected();
    double offset;
    if (selected != null) {
      offset = markOffset(info, group, selected);
    } else {
      org.bukkit.Location center = info.getCenterLocation();
      offset =
          center == null || center.getWorld() == null
              ? Double.NaN
              : StopAlignment.groupOffset(
                  group,
                  center.getWorld().getUID(),
                  center.toVector(),
                  StopAlignment.Reference.CENTER);
    }
    debug(
        "AutoStation 停妥: train="
            + trainName
            + String.format(Locale.ROOT, ", offset=%.2f", offset)
            + ", ref="
            + (selected != null ? "mark" : "sign")
            + ", cars="
            + group.size()
            + ", waited="
            + waitedTicks
            + (timedOut ? ", timedOut" : "")
            + " @ "
            + locationText(info));
  }

  private void handleStop(
      SignActionEvent info,
      SignNodeDefinition definition,
      String trainName,
      UUID routeId,
      String stopSessionId,
      int dwellSeconds,
      AutoStationDoorDirection doorDirection,
      BlockFace facingDirection,
      Vector facingVector,
      String facingSource,
      AutoStationDoorController.DoorChimeSettings chimeSettings,
      AutoStationDoorController.DoorSession session,
      boolean timedOut,
      boolean firstStop,
      DriverStationStop driverStop) {
    MinecartGroup group = info == null ? null : info.getGroup();
    if (group == null || !group.isValid()) {
      if (driverStop != null) {
        driverStop.end();
      }
      return;
    }
    FetaruteTCAddon plugin = this.plugin;
    if (plugin == null) {
      return;
    }
    // 先按计划停站登记：到站处理期间信号 tick 靠它保持 STOP；晚点压缩在到站进度提交后再覆盖。
    boolean dwellRegistered = dwellSeconds > 0 && !group.isMoving();
    if (dwellRegistered) {
      plugin.getDwellRegistry().ifPresent(registry -> registry.start(trainName, dwellSeconds));
    }
    TrainProperties properties = group.getProperties();
    // 一次新的停站开始：驾驶侧上一站没放行就结束的发车确认状态作废。
    ControlAuthority authority = plugin.getControlAuthority();
    if (authority != null) {
      authority.stationStopStarted(properties);
    }
    // 进入 WaitState 前先获取发车许可锁，避免信号 tick 在门控放行前提前发车。
    plugin
        .getRuntimeDispatchService()
        .ifPresent(
            dispatch ->
                dispatch.acquireDepartureGate(trainName, stopSessionId, "autostation_dwell"));
    DoorCars doorCars = session == null ? DoorCars.ALL : session.cars();
    StationExitState exitOffsetState = StationExitState.forDoorCars(group, doorCars);
    // 注意：先推进运行时到站状态，再添加 WaitState。常规中间站会在 departure gate 持有期间延迟写入
    // TrainCarts destination；终点/DSTY 等非延迟路径仍需要后续 stop + WaitState 兜住物理动作。
    if (timedOut) {
      debug(
          "AutoStation 停站超时: nodeId="
              + definition.nodeId().value()
              + ", train="
              + trainName
              + ", route="
              + shortUuid(routeId)
              + ", sid="
              + stopSessionId
              + ", worldDoorDirection="
              + doorDirection
              + ", attachments="
              + summarizeAttachments(group)
              + " @ "
              + locationText(info));
    }
    // 停车后推进 routeIndex；中间站下一跳 destination 由离站授权 tick 提交
    plugin
        .getRuntimeDispatchService()
        .ifPresent(dispatch -> dispatch.handleStationArrival(group, definition));
    // 晚点的车压缩停站：必须在到站进度提交之后（按本站序号查计划发车）、排关门时刻之前定下来。
    int effectiveDwellSeconds =
        plugin
                .getRuntimeDispatchService()
                .map(
                    dispatch ->
                        dispatch
                            .stationStops()
                            .dwellSecondsFor(
                                group,
                                definition,
                                dwellSeconds,
                                firstStop,
                                firstStop ? DOOR_OPEN_FIRST_DELAY_TICKS : DOOR_OPEN_DELAY_TICKS,
                                doorFloorSeconds(session)))
                .orElse(dwellSeconds)
            // 多模车在这一站做涉及内燃的换模：多停换模时间，加在晚点压缩之后，不会被压掉。
            // 换模器按调度的车名记（到站处理刚写进标签），改名过渡期可能与 TrainCarts 车名不同
            + plugin
                .getPowerModeSwitcher()
                .map(
                    power ->
                        power.takeStationDwell(
                            readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME)
                                .orElse(trainName)))
                .orElse(0)
            // 解编 DECP、联挂 COPL 那一站多停解编、联挂时间，同样加在压缩之后
            + plugin
                .getCouplerService()
                .map(
                    coupler -> {
                      String name =
                          readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME)
                              .orElse(trainName);
                      return coupler.decoupling().takeStationDwell(name)
                          + coupler.coupling().takeStationDwell(name);
                    })
                .orElse(0);
    // 刚在本站解下、等着接下一班的车：停站延到那一班的计划发车，车门开到快发车再关，不先关门干等
    effectiveDwellSeconds =
        Math.max(
            effectiveDwellSeconds,
            secondsUntilHeldDeparture(
                plugin,
                readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME).orElse(trainName),
                trainName));
    int registeredDwell = effectiveDwellSeconds;
    if (dwellRegistered && registeredDwell != dwellSeconds) {
      plugin.getDwellRegistry().ifPresent(registry -> registry.start(trainName, registeredDwell));
    }
    // 非延迟路径可能已经改写 destination；通过 stop() 强制停止并添加 WaitState。
    // 驾驶员控车时不加：停车由驾驶员负责，等待动作会挡在控车动作后面；发车仍由门控与信号把住。
    Runnable finalWaitState;
    if (driverControlled(properties)) {
      finalWaitState = () -> {};
    } else {
      group.stop();
      finalWaitState = group.getActions().addActionWaitState()::stop;
    }
    // 停妥要等稳定几拍，门控挂上时调度那一拍“由动转停”的巡检多半已经过去；巡检不再唤醒静止又没有停因的车，
    // 这一站的停车保持（终点还有待命只保持车身）就一直跑不到。下一拍补一次信号，让调度看到门控、记下停因；
    // 门锁已经不在时不补，免得这一拍走普通路径把车放走。
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () ->
                plugin
                    .getRuntimeDispatchService()
                    .filter(dispatch -> dispatch.hasDepartureGate(trainName))
                    .ifPresent(dispatch -> dispatch.refreshSignal(group)));
    long dwellTicks = Math.max(0L, effectiveDwellSeconds * 20L);
    // 到站时的车厢：按停车位置标选开门车厢时，开门计划只含这几节，停站中挂上来的另行开门
    List<UUID> carsAtStop = cartsFromHead(group);
    String location = locationText(info);
    // 驾驶员仍在控车：车门由驾驶员开关，停站从开门起算，出站许可放出后等列车起步才记发车。
    // 车上有车掌时车门由车掌开关（自动运行的车也一样），放行前还要等车掌的发车信号。
    boolean driverDoorsAtStop =
        driverStop != null
            && driverStop.active()
            && (driverControlled(properties) || guardOperatesDoors(properties));
    if (driverStop != null) {
      if (driverDoorsAtStop) {
        driverStop.markStopped();
      } else {
        driverStop.end();
      }
    }
    new org.bukkit.scheduler.BukkitRunnable() {
      private boolean driverDoors = driverDoorsAtStop;

      /** 驾驶员停站；停站中途由驾驶员接管时换成新建的那一个。 */
      private DriverStationStop stationStop = driverStop;

      /** 车门是停站中途由站台交给驾驶员的（站台开的门）。 */
      private boolean doorsFromStation = false;

      private boolean departureReleased = false;
      private long ticksSinceStop = 0L;
      private long ticksSinceOpen = 0L;
      private long lastOpenAttemptTick = -9999L;
      private int openAttempts = 0;
      private long actionableSinceTick = -1L;
      private boolean gaveUpOpen = false;
      private boolean gaveUpLogged = false;
      private boolean opened = false;
      private boolean closeStarted = false;
      private boolean closeAnimationTriggered = false;
      private boolean closeSoundPlayed = false;

      /** 关门动画排在门附件的队里没轮到的 tick 数（前面有牌子排的动画在播）：车门还开着，发车跟着往后推。 */
      private long closeDelayTicks = 0L;

      private String cachedAnimations = null;
      private String cachedPlanSummary = null;
      private AutoStationDoorController.DoorSession cachedSession = null;

      /** 开门计划已经含了哪些车厢；没开过门时为 {@code null}。停站中挂上来的车厢不在里面（见 {@link #openJoinedCars}）。 */
      private Set<UUID> openedCarts = null;

      /** 停站中、车门开着时挂上来的车厢：一起开门，到点与整列一起关。 */
      private final List<AutoStationDoorController.DoorSession> joinedSessions = new ArrayList<>();

      /** 此刻开着门的车厢：到站时选的，并上停站中挂上来、跟着开门的。车门交给驾驶员或车掌时按它交。 */
      private DoorCars openDoorCars = doorCars;

      /** 开门后至少停到第几 tick：主干先到、开着门等回程支线车，或挂上来的车厢开门后照常停站。 */
      private long heldOpenTicks = 0L;

      /** 挂上来的车厢开门没成功的次数；与整列开门同一上限，到了不再试。 */
      private int joinedOpenAttempts = 0;

      /** 挂上来的车厢不早于开门后第几 tick 再试开门。 */
      private long joinedRetryAtTick = 0L;

      /**
       * 开门计划用的车厢：全车开门、而开门前整列就多了车厢（开门前有车挂上来）时只含到站时的那几节，挂上来的按 {@link #openJoinedCars}
       * 另判，与开着门时挂上来同一口径。
       */
      private DoorCars planDoorCars() {
        return doorCars.all() && group.size() > carsAtStop.size()
            ? doorCars.restrictTo(new HashSet<>(carsAtStop))
            : doorCars;
      }

      /** 一直规划不出开门动作（车上没有门附件、判不出开门侧）从第几 tick 起；规划得出时为 -1。 */
      private long noActionSinceTick = -1L;

      /** 开门计划建立时整列的车厢（按编组次序）。 */
      private List<UUID> plannedCarts = List.of();

      /**
       * 停站延长时一并延长停站登记：停滞检测据此认作受控停站，车内屏与到站预测照旧显示在站。
       *
       * @param atLeastSeconds 至少登记多少秒（开着门等回程支线车时取联挂扣留的截止时刻）
       */
      private void registerHeldDwell(long atLeastSeconds) {
        long remaining = dwell() - ticksSinceOpen;
        long seconds = Math.max((remaining + 19L) / 20L, atLeastSeconds);
        if (seconds <= 0L) {
          return;
        }
        plugin
            .getDwellRegistry()
            .ifPresent(registry -> registry.start(trainName, (int) Math.min(seconds, 3600L)));
      }

      /** 联挂扣留还有几秒到截止（开着门等回程支线车时的停站登记用）；没有扣留时为 0。 */
      private long couplingHoldSeconds() {
        return plugin
            .getCouplerService()
            .flatMap(
                coupler ->
                    coupler
                        .coupling()
                        .couplingHold(
                            readTagValue(
                                    group.getProperties(), RouteProgressRegistry.TAG_TRAIN_NAME)
                                .orElse(trainName)))
            .map(until -> java.time.Duration.between(java.time.Instant.now(), until).getSeconds())
            .orElse(0L);
      }

      /** 这一拍的停站时长（tick）：计划的与 {@link #heldOpenTicks} 取长。 */
      private long dwell() {
        return Math.max(dwellTicks, heldOpenTicks);
      }

      /** 主干先到、停在站台上等回程支线车挂上来：车门开着等。 */
      private boolean awaitingFeeder() {
        return plugin
            .getCouplerService()
            .map(
                coupler ->
                    coupler
                        .coupling()
                        .awaitsFeeder(
                            readTagValue(
                                    group.getProperties(), RouteProgressRegistry.TAG_TRAIN_NAME)
                                .orElse(trainName)))
            .orElse(false);
      }

      /** 车门开着时整列多了车厢（停站中别的车挂上来）：照同一块停车位置标、同一侧给挂上来的那几节开门，关门时一起关。门已经在关或关上了就不动，整列车门一个样。 */
      private void openJoinedCars() {
        if (openedCarts == null
            || group.size() <= openedCarts.size()
            || ticksSinceOpen < joinedRetryAtTick) {
          return;
        }
        List<UUID> fromHead = cartsFromHead(group);
        Set<UUID> joined = new HashSet<>();
        for (UUID cart : fromHead) {
          if (cart != null && !openedCarts.contains(cart)) {
            joined.add(cart);
          }
        }
        if (joined.isEmpty()) {
          return;
        }
        // 开着门的车厢改记成明确的这几节：挂上来、还没跟着开门的车厢不交给驾驶员或车掌
        openDoorCars = openDoorCars.restrictTo(openedCarts);
        FacingResult facing = resolveFacingDirectionResult(info);
        DoorCars joinedCars = doorCars.joined(fromHead, joined);
        AutoStationDoorController.DoorSession extra =
            AutoStationDoorController.plan(
                group,
                facing.face() == null ? facingDirection : facing.face(),
                facing.vector() == null ? facingVector : facing.vector(),
                doorDirection,
                chimeSettings,
                joinedCars);
        boolean didOpen = extra.hasActions() && extra.open();
        // 选中了要开门的车厢却没开成（还判不出开门侧、动画没触发）：隔几拍再试，与整列开门同一上限；一节都没选中（标志对不上整列节数）就不再试
        if (!didOpen && !joinedCars.none() && ++joinedOpenAttempts < DOOR_OPEN_MAX_ATTEMPTS) {
          joinedRetryAtTick = ticksSinceOpen + DOOR_OPEN_RETRY_INTERVAL_TICKS;
          return;
        }
        openedCarts.addAll(joined);
        joinedOpenAttempts = 0;
        if (didOpen) {
          joinedSessions.add(extra);
          openDoorCars = openDoorCars.plus(joinedCars);
          List<UUID> exits = new ArrayList<>();
          for (MinecartMember<?> member : group) {
            if (member != null
                && member.getProperties() != null
                && AutoStationDoorController.opensDoors(joinedCars, member)) {
              exits.add(member.getProperties().getUUID());
            }
          }
          exitOffsetState.add(exits);
          // 挂上来的车厢开门后照常停站，乘客有时间上下
          heldOpenTicks = Math.max(heldOpenTicks, ticksSinceOpen + dwellSeconds * 20L);
          registerHeldDwell(0L);
        }
        debug(
            "AutoStation 挂上来的车厢开门: nodeId="
                + definition.nodeId().value()
                + ", train="
                + trainName
                + ", sid="
                + stopSessionId
                + ", joined="
                + joined.size()
                + ", opened="
                + didOpen
                + ", plan="
                + extra.debugSummary()
                + " @ "
                + location);
      }

      /**
       * 关门：挂上来的车厢与整列一起关，各按自己的门动画；旧式门动画的提示音另到时候播（见 {@link #playJoinedCloseSound}）。
       *
       * @return 有旧式门动画开始关
       */
      private boolean closeJoinedCars() {
        boolean legacyStarted = false;
        for (AutoStationDoorController.DoorSession extra : joinedSessions) {
          if (extra.usesLegacyDoorAnimation()) {
            legacyStarted |= extra.startCloseAnimation();
          } else {
            extra.close(true);
          }
        }
        return legacyStarted;
      }

      private void playJoinedCloseSound() {
        for (AutoStationDoorController.DoorSession extra : joinedSessions) {
          if (extra.usesLegacyDoorAnimation()) {
            extra.playCloseSound();
          }
        }
      }

      /** 挂上来的车厢里有旧式门动画（关门要提前开始）。 */
      private boolean joinedLegacy() {
        for (AutoStationDoorController.DoorSession extra : joinedSessions) {
          if (extra.usesLegacyDoorAnimation()) {
            return true;
          }
        }
        return false;
      }

      /** 旧式门动画关门要多久（tick）：整列与挂上来的车厢取长；量不出时为 -1。 */
      private long legacyCloseDuration(boolean mainLegacy) {
        long longest = mainLegacy ? cachedSession.estimatedCloseDurationTicks() : -1L;
        for (AutoStationDoorController.DoorSession extra : joinedSessions) {
          if (extra.usesLegacyDoorAnimation()) {
            longest = Math.max(longest, extra.estimatedCloseDurationTicks());
          }
        }
        return longest;
      }

      /** 挂上来的车厢关门动画还排着没轮到。 */
      private boolean joinedClosePending() {
        for (AutoStationDoorController.DoorSession extra : joinedSessions) {
          if (extra.closePending()) {
            return true;
          }
        }
        return false;
      }

      /**
       * 驾驶员控车的停站：等驾驶员开站台侧的门，停站从开门起算；时间到等驾驶员关门，再按出站门控放行；列车起步时才记发车。
       *
       * @return 本拍已处理，不再走自动开关门
       */
      private boolean driverTick() {
        if (departureReleased) {
          if (stopSessionSuperseded(trainName, routeId, group.getProperties())) {
            // 已放行但列车被别的流程接手（终点待命复用改名开下一趟）：只收尾，不再按本站报发车。
            if (stationStop != null) {
              stationStop.end();
            }
            cancel();
            return true;
          }
          if (group.isMoving()) {
            plugin
                .getRuntimeDispatchService()
                .ifPresent(dispatch -> dispatch.stationStops().handleDeparture(group, definition));
            if (stationStop != null) {
              stationStop.end();
            }
            cancel();
          }
          return true;
        }
        if (!driverDoors) {
          return false;
        }
        if (!driverControlled(group.getProperties())
            && !guardOperatesDoors(group.getProperties())) {
          // 停站中交还了自动运行（车掌离岗）：剩下的开关门与发车按自动运行处理，驾驶员或车掌开着的门随会话结束关上。
          driverDoors = false;
          stationStop.end();
          if (doorsFromStation) {
            // 站台交出去的门已由驾驶员关上（或随驾驶结束关上）：不再按原计时重放关门动画。
            closeStarted = true;
          }
          return false;
        }
        if (!opened) {
          if (!stationStop.doorsRequired() || stationStop.correctDoorsOpen()) {
            opened = true;
            ticksSinceOpen = 0L;
            closeStarted = true;
            applyExitOffset();
            stationStop.setPhase(DriverStationStop.Phase.DWELL);
          } else if (ticksSinceStop
              >= (guardOperatesDoors(group.getProperties())
                  ? GUARD_DOOR_BACKSTOP_TICKS
                  : DRIVER_DOOR_TIMEOUT_TICKS)) {
            // 驾驶员迟迟不开门：由站台开关门，停站照常。车掌的开门时限由车掌那边代开，这里只兜底开不了门的情况。
            driverDoors = false;
            stationStop.markDoorsTakenOver();
            stationStop.end();
            debug(
                "AutoStation 驾驶员未开门，改由站台开关门: nodeId="
                    + definition.nodeId().value()
                    + ", train="
                    + trainName
                    + ", sid="
                    + stopSessionId);
            return false;
          } else {
            return true;
          }
        }
        ticksSinceOpen++;
        stationStop.setDwellRemainingTicks(dwell() - ticksSinceOpen);
        if (ticksSinceOpen < dwell()) {
          return true;
        }
        if (stationStop.anyDoorOpen()) {
          stationStop.setPhase(DriverStationStop.Phase.CLOSE_DOORS);
          return true;
        }
        stationStop.setPhase(DriverStationStop.Phase.WAIT_DEPARTURE);
        if ((ticksSinceOpen - dwell()) % 20 != 0) {
          return true;
        }
        if (stopSessionSuperseded(trainName, routeId, group.getProperties())) {
          plugin
              .getRuntimeDispatchService()
              .ifPresent(dispatch -> dispatch.releaseDepartureGate(trainName, stopSessionId));
          exitOffsetState.restore();
          finalWaitState.run();
          plugin.getDwellRegistry().ifPresent(registry -> registry.clear(trainName));
          lowerPantograph(group, trainName, stopSessionId);
          stationStop.end();
          cancel();
          return true;
        }
        boolean canDepart =
            plugin
                .getRuntimeDispatchService()
                .map(dispatch -> dispatch.checkDeparture(group, definition))
                .orElse(true);
        TrainProperties current = group.getProperties();
        ControlAuthority authority = plugin.getControlAuthority();
        CrewDeparture step =
            crewDeparture(
                canDepart,
                guardOperatesDoors(current),
                exitOpen -> authority.holdForGuard(current, exitOpen),
                driverControlled(current),
                () -> authority.holdDeparture(current));
        if (step == CrewDeparture.HOLD) {
          return true;
        }
        if (step == CrewDeparture.RELEASE_AUTOMATIC) {
          // 车掌开关门的自动运行列车：与自动运行同一放行动作，列车立即开走。
          plugin
              .getRuntimeDispatchService()
              .ifPresent(
                  dispatch -> {
                    dispatch.stationStops().handleDeparture(group, definition);
                    dispatch.releaseDepartureGate(trainName, stopSessionId);
                  });
          exitOffsetState.restore();
          finalWaitState.run();
          plugin.getDwellRegistry().ifPresent(registry -> registry.clear(trainName));
          Bukkit.getScheduler()
              .runTask(
                  plugin,
                  () ->
                      plugin
                          .getRuntimeDispatchService()
                          .ifPresent(dispatch -> dispatch.refreshSignal(group)));
          lowerPantograph(group, trainName, stopSessionId);
          // 车掌据此开始出站监视；列车开出后由车掌那边结束这一站。
          stationStop.setPhase(DriverStationStop.Phase.DEPART);
          cancel();
          return true;
        }
        plugin
            .getRuntimeDispatchService()
            .ifPresent(dispatch -> dispatch.releaseDepartureGate(trainName, stopSessionId));
        exitOffsetState.restore();
        finalWaitState.run();
        plugin.getDwellRegistry().ifPresent(registry -> registry.clear(trainName));
        Bukkit.getScheduler()
            .runTask(
                plugin,
                () ->
                    plugin
                        .getRuntimeDispatchService()
                        .ifPresent(dispatch -> dispatch.refreshSignal(group)));
        departureReleased = true;
        lowerPantograph(group, trainName, stopSessionId);
        stationStop.setPhase(DriverStationStop.Phase.DEPART);
        return true;
      }

      /**
       * 停站中途被人工驾驶的驾驶员接管、车门已由站台打开、还没开始关门：车门交给驾驶员。站台不再按时关门，改按驾驶员停站推进——停站计时照旧，
       * 时间到等驾驶员关门，再放出出站许可。这一站不是驾驶员停的车，不计对标成绩。
       *
       * @return 本拍已交接
       */
      private boolean handOverDoorsToDriver() {
        if (!shouldHandOverDoors(
            driverDoors,
            departureReleased,
            opened,
            closeStarted,
            doorDirection != AutoStationDoorDirection.NONE,
            plugin.getControlAuthority().driverOperatesDoors(group.getProperties())
                || guardOperatesDoors(group.getProperties()))) {
          return false;
        }
        DriverStationStop handed =
            beginDriverStop(
                info,
                group.getProperties(),
                definition,
                doorDirection,
                Optional.empty(),
                openDoorCars,
                false);
        if (handed == null) {
          return false;
        }
        handed.setPhase(DriverStationStop.Phase.DWELL);
        handed.setDwellRemainingTicks(dwell() - ticksSinceOpen);
        handed.handOverOpenDoors();
        stationStop = handed;
        driverDoors = true;
        doorsFromStation = true;
        debug(
            "AutoStation 停站中途由驾驶员接管，车门交给驾驶员: nodeId="
                + definition.nodeId().value()
                + ", train="
                + trainName
                + ", sid="
                + stopSessionId
                + ", t="
                + ticksSinceOpen
                + "/"
                + dwell());
        return true;
      }

      /** 乘客下车点：按牌子写的开门方位，落到站台一侧。 */
      private void applyExitOffset() {
        if (exitOffsetState.applied()) {
          return;
        }
        resolveExitFace(doorDirection)
            .ifPresent(exit -> exitOffsetState.apply(exit, EXIT_OFFSET_DISTANCE_BLOCKS));
      }

      @Override
      public void run() {
        if (!group.isValid()) {
          plugin
              .getRuntimeDispatchService()
              .ifPresent(dispatch -> dispatch.releaseDepartureGate(trainName, stopSessionId));
          exitOffsetState.restore();
          finalWaitState.run();
          if (stationStop != null) {
            stationStop.end();
          }
          cancel();
          return;
        }
        ticksSinceStop++;
        // 停站任务还活着、还没放行：每秒续一次门锁。持锁超时只收回没人还的锁，停得久的车（等车掌、等联挂、解下的车等下一班）
        // 不能被超时清掉门锁、再被信号 tick 放走
        if (!departureReleased && ticksSinceStop % 20 == 0) {
          plugin
              .getRuntimeDispatchService()
              .ifPresent(dispatch -> dispatch.renewDepartureGate(trainName, stopSessionId));
        }
        if (driverTick()) {
          return;
        }
        if (handOverDoorsToDriver()) {
          return;
        }
        if (!opened && doorDirection == AutoStationDoorDirection.NONE) {
          opened = true;
          ticksSinceOpen = 0L;
          closeStarted = true;
          cachedSession = session;
          cachedPlanSummary = session == null ? null : session.debugSummary();
        }
        if (opened) {
          ticksSinceOpen++;
          if (!closeStarted) {
            openJoinedCars();
          }
          boolean mainLegacy = cachedSession != null && cachedSession.usesLegacyDoorAnimation();
          boolean legacy = mainLegacy || joinedLegacy();
          long closeDuration = DOOR_CLOSE_EARLY_TICKS;
          if (legacy) {
            long estimated = legacyCloseDuration(mainLegacy);
            if (estimated > 0L) {
              closeDuration = estimated;
            }
          }
          if (!closeStarted && joinedOpenAttempts > 0) {
            // 挂上来的车厢还在重试开门：关门时刻跟着往后推
            heldOpenTicks = Math.max(heldOpenTicks, ticksSinceOpen + closeDuration + 1L);
          } else if (!closeStarted && awaitingFeeder()) {
            // 主干先到、开着门等回程支线车：关门时刻跟着往后推，挂上了或不等了再关
            heldOpenTicks = Math.max(heldOpenTicks, ticksSinceOpen + closeDuration + 1L);
            if (ticksSinceOpen % 20L == 0L) {
              registerHeldDwell(couplingHoldSeconds() + (closeDuration + 19L) / 20L);
            }
          }
          long dwellNow = dwell();
          long closeStartTick;
          long closeSoundTick;
          if (legacy) {
            closeStartTick = Math.max(0L, dwellNow - closeDuration);
            closeSoundTick =
                closeStartTick + AutoStationDoorController.legacyCloseSoundDelayTicks();
            if (closeSoundTick > dwellNow) {
              closeSoundTick = dwellNow;
            }
          } else {
            closeStartTick =
                dwellNow > DOOR_CLOSE_EARLY_TICKS ? dwellNow - DOOR_CLOSE_EARLY_TICKS : dwellNow;
            closeSoundTick = closeStartTick;
          }
          if (!closeStarted && ticksSinceOpen >= closeStartTick) {
            debug(
                "AutoStation 关门: nodeId="
                    + definition.nodeId().value()
                    + ", train="
                    + trainName
                    + ", route="
                    + shortUuid(routeId)
                    + ", sid="
                    + stopSessionId
                    + ", worldDoorDirection="
                    + doorDirection
                    + ", t="
                    + ticksSinceOpen
                    + "/"
                    + dwellNow
                    + (legacy
                        ? ", closeStart=" + closeStartTick + ", closeSound=" + closeSoundTick
                        : "")
                    + ", plan="
                    + (cachedPlanSummary == null ? "-" : cachedPlanSummary)
                    + ", attachments="
                    + summarizeAttachments(group)
                    + " @ "
                    + location);
            if (cachedSession != null) {
              closeAnimationTriggered =
                  mainLegacy ? cachedSession.startCloseAnimation() : cachedSession.close(true);
            }
            closeAnimationTriggered |= closeJoinedCars();
            closeStarted = true;
          }
          if (legacy
              && closeAnimationTriggered
              && !closeSoundPlayed
              && ticksSinceOpen >= closeSoundTick) {
            closeSoundPlayed = true;
            if (cachedSession != null && mainLegacy) {
              cachedSession.playCloseSound();
            }
            playJoinedCloseSound();
          }
          if (closeStarted
              && ((cachedSession != null && cachedSession.closePending())
                  || joinedClosePending())) {
            closeDelayTicks++;
          }
          long departAtTick = dwellNow + closeDelayTicks;
          if (ticksSinceOpen >= departAtTick) {
            // 每 20 tick (1秒) 检查一次发车门控，避免刷屏与性能浪费。
            if ((ticksSinceOpen - departAtTick) % 20 != 0) {
              return;
            }
            if (stopSessionSuperseded(trainName, routeId, group.getProperties())) {
              // 本次停站已由别的流程接手（终点待命复用改名、改派新交路后自己发车）：只收尾，不再替它判发车。
              debug(
                  "AutoStation 停站会话已被接管: nodeId="
                      + definition.nodeId().value()
                      + ", train="
                      + trainName
                      + ", currentTrain="
                      + group.getProperties().getTrainName()
                      + ", route="
                      + shortUuid(routeId)
                      + ", sid="
                      + stopSessionId);
              plugin
                  .getRuntimeDispatchService()
                  .ifPresent(dispatch -> dispatch.releaseDepartureGate(trainName, stopSessionId));
              exitOffsetState.restore();
              finalWaitState.run();
              plugin.getDwellRegistry().ifPresent(registry -> registry.clear(trainName));
              // 停站时间已到（终点站转入待命等）：超级电容车充电升起的受电弓在这里降下。
              lowerPantograph(group, trainName, stopSessionId);
              cancel();
              return;
            }

            boolean canDepart = true;
            if (plugin.getRuntimeDispatchService().isPresent()) {
              // 检查出站门控（闭塞/占用）
              canDepart =
                  plugin.getRuntimeDispatchService().get().checkDeparture(group, definition);
            }
            // ATO 下车上的驾驶员要先确认发车（等太久自动放行）；扣着等换端的车由驾驶侧放行，不在这里等确认。
            if (canDepart
                && !driverControlled(group.getProperties())
                && plugin.getControlAuthority().holdDeparture(group.getProperties())) {
              canDepart = false;
            }

            if (canDepart && driverControlled(group.getProperties())) {
              // 本站停站中途被驾驶员接管：放出出站许可，等列车真正起步时才记发车（见 driverTick）。
              plugin
                  .getRuntimeDispatchService()
                  .ifPresent(dispatch -> dispatch.releaseDepartureGate(trainName, stopSessionId));
              exitOffsetState.restore();
              finalWaitState.run();
              plugin.getDwellRegistry().ifPresent(registry -> registry.clear(trainName));
              Bukkit.getScheduler()
                  .runTask(
                      plugin,
                      () ->
                          plugin
                              .getRuntimeDispatchService()
                              .ifPresent(dispatch -> dispatch.refreshSignal(group)));
              departureReleased = true;
              lowerPantograph(group, trainName, stopSessionId);
              return;
            }
            if (canDepart) {
              plugin
                  .getRuntimeDispatchService()
                  .ifPresent(
                      dispatch -> {
                        // 这里是列车真正开走的唯一时刻：门控放行、门锁松开、WaitState 结束。
                        // 时刻表录制取的就是这个时刻，不是 checkDeparture 第一次被问的时刻。
                        dispatch.stationStops().handleDeparture(group, definition);
                        dispatch.releaseDepartureGate(trainName, stopSessionId);
                      });
              exitOffsetState.restore();
              finalWaitState.run();
              cancel();
              lowerPantograph(group, trainName, stopSessionId);
              plugin.getDwellRegistry().ifPresent(registry -> registry.clear(trainName));
              Bukkit.getScheduler()
                  .runTask(
                      plugin,
                      () ->
                          plugin
                              .getRuntimeDispatchService()
                              .ifPresent(dispatch -> dispatch.refreshSignal(group)));
            }
          }
          return;
        }

        long openDelayTicks = firstStop ? DOOR_OPEN_FIRST_DELAY_TICKS : DOOR_OPEN_DELAY_TICKS;
        if (ticksSinceStop < openDelayTicks) {
          return;
        }
        if (gaveUpOpen) {
          return;
        }
        if (ticksSinceStop - lastOpenAttemptTick < DOOR_OPEN_RETRY_INTERVAL_TICKS) {
          return;
        }
        lastOpenAttemptTick = ticksSinceStop;

        FacingResult facingResult = resolveFacingDirectionResult(info);
        BlockFace resolvedFacing =
            facingResult.face() == null ? facingDirection : facingResult.face();
        Vector resolvedVector =
            facingResult.vector() == null ? facingVector : facingResult.vector();
        String resolvedSource = facingResult.face() == null ? facingSource : facingResult.source();
        String animations = summarizeDoorAnimations(group);
        boolean shouldRebuild =
            cachedSession == null
                || !java.util.Objects.equals(cachedAnimations, animations)
                // 计划建好以后有车挂上来：重建，开门计划与“已含哪些车厢”按同一份整列
                || group.size() > plannedCarts.size();
        if (shouldRebuild) {
          cachedAnimations = animations;
          plannedCarts = cartsFromHead(group);
          cachedSession =
              AutoStationDoorController.plan(
                  group,
                  resolvedFacing,
                  resolvedVector,
                  doorDirection,
                  chimeSettings,
                  planDoorCars());
          String nextPlan = cachedSession.debugSummary();
          if (cachedPlanSummary == null || !cachedPlanSummary.equals(nextPlan)) {
            debug(
                "AutoStation 计划更新: nodeId="
                    + definition.nodeId().value()
                    + ", train="
                    + trainName
                    + ", route="
                    + shortUuid(routeId)
                    + ", sid="
                    + stopSessionId
                    + ", attempt="
                    + openAttempts
                    + ", worldDoorDirection="
                    + doorDirection
                    + ", facing="
                    + resolvedFacing
                    + ", source="
                    + resolvedSource
                    + ", facingVector="
                    + formatFacingVector(resolvedVector)
                    + ", plan="
                    + nextPlan
                    + ", animations="
                    + animations
                    + ", attachments="
                    + summarizeAttachments(group)
                    + " @ "
                    + location);
          }
          cachedPlanSummary = nextPlan;
        }
        if (!exitOffsetState.applied()) {
          resolveExitFace(doorDirection)
              .ifPresent(face -> exitOffsetState.apply(face, EXIT_OFFSET_DISTANCE_BLOCKS));
        }
        if (cachedSession == null) {
          return;
        }
        if (!cachedSession.hasActions()) {
          if (noActionSinceTick < 0L) {
            noActionSinceTick = ticksSinceStop;
          }
          if (ticksSinceStop - noActionSinceTick
              >= Math.max(DOOR_OPEN_MAX_RETRY_WINDOW_TICKS, dwellTicks)) {
            // 一直规划不出开门动作（车上没有门附件、判不出开门侧）：等满一个停站时间（门附件晚到的照样能开），
            // 再按不开门的停站照常发车，不卡在站里。停站从本该开门的那一刻算起，不再多停一整段
            debug(
                "AutoStation 开门计划没有动作，按不开门停站: nodeId="
                    + definition.nodeId().value()
                    + ", train="
                    + trainName
                    + ", sid="
                    + stopSessionId
                    + ", plan="
                    + (cachedPlanSummary == null ? "-" : cachedPlanSummary)
                    + " @ "
                    + location);
            opened = true;
            ticksSinceOpen = Math.max(0L, ticksSinceStop - openDelayTicks);
            closeStarted = true;
          }
          return;
        }
        noActionSinceTick = -1L;
        if (actionableSinceTick < 0L) {
          actionableSinceTick = ticksSinceStop;
        }
        long retryWindow = ticksSinceStop - actionableSinceTick;
        if (retryWindow >= DOOR_OPEN_MAX_RETRY_WINDOW_TICKS
            || openAttempts >= DOOR_OPEN_MAX_ATTEMPTS) {
          if (!gaveUpLogged && doorDirection != AutoStationDoorDirection.NONE) {
            gaveUpLogged = true;
            debug(
                "AutoStation 开门放弃: 超过最大重试窗口 (attempts="
                    + openAttempts
                    + ", windowTicks="
                    + retryWindow
                    + ", windowFromTick="
                    + actionableSinceTick
                    + ", worldDoorDirection="
                    + doorDirection
                    + ", facing="
                    + resolvedFacing
                    + ", source="
                    + resolvedSource
                    + ", facingVector="
                    + formatFacingVector(resolvedVector)
                    + ", train="
                    + trainName
                    + ", route="
                    + shortUuid(routeId)
                    + ", sid="
                    + stopSessionId
                    + ", plan="
                    + (cachedPlanSummary == null ? "-" : cachedPlanSummary)
                    + ", animations="
                    + (cachedAnimations == null ? summarizeDoorAnimations(group) : cachedAnimations)
                    + ", attachments="
                    + summarizeAttachments(group)
                    + ") @ "
                    + location);
          }
          gaveUpOpen = true;
          // 放弃开门后本任务不再运行，而发车门控是在 handleStop 里、进 WaitState 之前取的。
          // 这里若不释放，门控就永久留在 departureGates 里：列车不仅自己走不了，
          // 它身上的预约还会一直挡住别人，且没有任何一条路径会再来收拾。
          // 另外两处 cancel（组失效、正常发车）都先释放了门控，这里必须对称。
          plugin
              .getRuntimeDispatchService()
              .ifPresent(dispatch -> dispatch.releaseDepartureGate(trainName, stopSessionId));
          exitOffsetState.restore();
          finalWaitState.run();
          cancel();
          return;
        }
        openAttempts++;
        debug(
            "AutoStation 开门尝试: nodeId="
                + definition.nodeId().value()
                + ", train="
                + trainName
                + ", route="
                + shortUuid(routeId)
                + ", sid="
                + stopSessionId
                + ", attempt="
                + openAttempts
                + ", tickSinceStop="
                + ticksSinceStop
                + ", spawnAgeMs="
                + readRunAgeMillis(group.getProperties())
                + ", worldDoorDirection="
                + doorDirection
                + ", facing="
                + resolvedFacing
                + ", source="
                + resolvedSource
                + ", facingVector="
                + formatFacingVector(resolvedVector)
                + ", plan="
                + (cachedPlanSummary == null ? "-" : cachedPlanSummary)
                + ", animations="
                + (cachedAnimations == null ? summarizeDoorAnimations(group) : cachedAnimations)
                + ", attachments="
                + summarizeAttachments(group)
                + ", transforms="
                + AutoStationDoorController.sampleDoorTransformSummary(group)
                + " @ "
                + location);
        boolean didOpen = cachedSession.open();
        if (!didOpen) {
          if (openAttempts == 1 && doorDirection != AutoStationDoorDirection.NONE) {
            debug(
                "AutoStation 开门尝试未触发: nodeId="
                    + definition.nodeId().value()
                    + ", train="
                    + trainName
                    + ", route="
                    + shortUuid(routeId)
                    + ", sid="
                    + stopSessionId
                    + ", worldDoorDirection="
                    + doorDirection
                    + ", attempt="
                    + openAttempts
                    + ", facing="
                    + resolvedFacing
                    + ", source="
                    + resolvedSource
                    + ", facingVector="
                    + formatFacingVector(resolvedVector)
                    + ", plan="
                    + cachedSession.debugSummary()
                    + ", animations="
                    + animations
                    + ", attachments="
                    + summarizeAttachments(group)
                    + " @ "
                    + location);
          }
          return;
        }
        opened = true;
        ticksSinceOpen = 0L;
        closeStarted = false;
        closeAnimationTriggered = false;
        closeSoundPlayed = false;
        // 全车开门时开门计划按此刻整列建，否则只含到站时选的那几节
        openedCarts = new HashSet<>(cachedSession.cars().all() ? plannedCarts : carsAtStop);
        openDoorCars = cachedSession.cars();
        String planSummary = cachedSession.debugSummary();
        cachedPlanSummary = planSummary;
        if (firstStop) {
          markFirstStopDone(group.getProperties());
        }
        debug(
            "AutoStation 开门: nodeId="
                + definition.nodeId().value()
                + ", train="
                + trainName
                + ", route="
                + shortUuid(routeId)
                + ", sid="
                + stopSessionId
                + ", worldDoorDirection="
                + doorDirection
                + ", facing="
                + resolvedFacing
                + ", source="
                + resolvedSource
                + ", facingVector="
                + formatFacingVector(resolvedVector)
                + ", plan="
                + planSummary
                + ", animations="
                + animations
                + ", attempts="
                + openAttempts
                + ", attachments="
                + summarizeAttachments(group)
                + " @ "
                + location);
      }
    }.runTaskTimer(plugin, 1L, 1L);
  }

  /**
   * 汇总列车附件树绑定状态：用于定位“刚从 depot spawn 首站不开门”等未 attach 场景。
   *
   * <p>此处只统计每节车厢的 {@code member.getAttachments().isAttached()}，不展开子树。
   */
  private static String summarizeAttachments(MinecartGroup group) {
    if (group == null) {
      return "members=0,attached=0";
    }
    int members = 0;
    int attached = 0;
    for (MinecartMember<?> member : group) {
      if (member == null) {
        continue;
      }
      members++;
      if (member.getAttachments() != null && member.getAttachments().isAttached()) {
        attached++;
      }
    }
    return "members=" + members + ",attached=" + attached;
  }

  /** 汇总列车已有的门动画名称，用于 debug 输出。 */
  private static String summarizeDoorAnimations(MinecartGroup group) {
    if (group == null) {
      return "none";
    }
    Collection<String> names = group.getAnimationNames();
    if (names == null || names.isEmpty()) {
      return "none";
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String name : names) {
      String normalized = normalizeDoorAnimationName(name);
      if (normalized == null) {
        continue;
      }
      counts.putIfAbsent(normalized, 0);
    }
    if (counts.isEmpty()) {
      return "none";
    }
    for (MinecartMember<?> member : group) {
      if (member == null) {
        continue;
      }
      if (member.getAttachments() == null || !member.getAttachments().isAttached()) {
        continue;
      }
      Attachment root = member.getAttachments().getRootAttachment();
      if (root == null) {
        continue;
      }
      collectDoorAnimationCounts(root, counts);
    }
    List<String> entries = new java.util.ArrayList<>();
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      entries.add(entry.getKey() + "(" + entry.getValue() + ")");
    }
    return String.join("/", entries);
  }

  private static void collectDoorAnimationCounts(
      Attachment attachment, Map<String, Integer> counts) {
    if (attachment == null || counts == null || counts.isEmpty()) {
      return;
    }
    Collection<String> names = attachment.getAnimationNames();
    if (names != null && !names.isEmpty()) {
      Set<String> hit = new HashSet<>();
      for (String name : names) {
        String normalized = normalizeDoorAnimationName(name);
        if (normalized == null || !counts.containsKey(normalized)) {
          continue;
        }
        hit.add(normalized);
      }
      for (String key : hit) {
        counts.put(key, counts.get(key) + 1);
      }
    }
    for (Attachment child : attachment.getChildren()) {
      collectDoorAnimationCounts(child, counts);
    }
  }

  private static String normalizeDoorAnimationName(String name) {
    if (name == null) {
      return null;
    }
    String normalized = name.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "doorl" -> "doorL";
      case "doorr" -> "doorR";
      case "doorl10" -> "doorL10";
      case "doorr10" -> "doorR10";
      default -> null;
    };
  }

  /** 将牌子坐标转成简短日志文本。 */
  private static String locationText(SignActionEvent info) {
    if (info == null || info.getBlock() == null) {
      return "unknown";
    }
    return info.getBlock().getWorld().getName()
        + " "
        + info.getBlock().getX()
        + " "
        + info.getBlock().getY()
        + " "
        + info.getBlock().getZ();
  }

  /**
   * 这次停站是否已被别的流程接手：停站时的列车改了名，或改派到了另一条交路。
   *
   * <p>终点待命复用会把列车改名、换上新交路并自行发车，停站任务却不知道，仍每秒以"从本站发车"替它判一次门控。
   * 复用后的列车开走后，这个判定会一直替它刷新在本站单线区段上的排队位，对向进站的列车被长期挡住，而它早已远离本站。
   *
   * <p>只认确凿证据：当前名字或交路读不到时不算接手，停站任务照旧运行。
   *
   * @param stoppedTrainName 停站时的列车名
   * @param stoppedRouteId 停站时的交路
   * @param current 列车当前属性
   * @return 已被接手时为 {@code true}
   */
  /**
   * 停站中途接管时要不要把站台开着的车门交给驾驶员：人工驾驶的驾驶员已接管、站台已开门且还没开始关门、还没放行、本站要开门。
   *
   * @param driverDoors 已经由驾驶员开关门
   * @param departureReleased 已放出出站许可
   * @param opened 站台已开门
   * @param closeStarted 站台已开始关门
   * @param doorsAtThisStop 本站要开门
   * @param driverOperatesDoors 车上的驾驶员亲手开关车门（人工驾驶）
   */
  static boolean shouldHandOverDoors(
      boolean driverDoors,
      boolean departureReleased,
      boolean opened,
      boolean closeStarted,
      boolean doorsAtThisStop,
      boolean driverOperatesDoors) {
    return !driverDoors
        && !departureReleased
        && opened
        && !closeStarted
        && doorsAtThisStop
        && driverOperatesDoors;
  }

  static boolean stopSessionSuperseded(
      String stoppedTrainName, UUID stoppedRouteId, TrainProperties current) {
    if (current == null) {
      return false;
    }
    String currentName = current.getTrainName();
    if (stoppedTrainName != null
        && !UNKNOWN_TRAIN_NAME.equals(stoppedTrainName)
        && currentName != null
        && !currentName.isBlank()
        && !currentName.equals(stoppedTrainName)) {
      return true;
    }
    return stoppedRouteId != null
        && readRouteId(current).filter(routeId -> !routeId.equals(stoppedRouteId)).isPresent();
  }

  /**
   * 从列车 tag 中解析 {@code FTA_ROUTE_ID}。
   *
   * <p>格式为 {@code FTA_ROUTE_ID=<uuid>}，否则返回空。
   */
  private static Optional<UUID> readRouteId(TrainProperties properties) {
    if (properties == null || !properties.hasTags()) {
      return Optional.empty();
    }
    for (String tag : properties.getTags()) {
      if (tag == null) {
        continue;
      }
      String trimmed = tag.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int idx = trimmed.indexOf('=');
      if (idx <= 0) {
        continue;
      }
      String key = trimmed.substring(0, idx).trim();
      if (!TAG_ROUTE_ID.equalsIgnoreCase(key)) {
        continue;
      }
      String value = trimmed.substring(idx + 1).trim();
      try {
        return Optional.of(UUID.fromString(value));
      } catch (IllegalArgumentException ex) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static boolean isFirstStop(TrainProperties properties) {
    return !hasTagKey(properties, TAG_DOOR_FIRST_STOP_DONE);
  }

  private static void markFirstStopDone(TrainProperties properties) {
    if (properties == null) {
      return;
    }
    if (hasTagKey(properties, TAG_DOOR_FIRST_STOP_DONE)) {
      return;
    }
    properties.addTags(TAG_DOOR_FIRST_STOP_DONE + "=1");
  }

  private static boolean hasTagKey(TrainProperties properties, String key) {
    if (properties == null || key == null || key.isBlank() || !properties.hasTags()) {
      return false;
    }
    for (String tag : properties.getTags()) {
      if (tag == null) {
        continue;
      }
      String trimmed = tag.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int idx = trimmed.indexOf('=');
      String current = idx > 0 ? trimmed.substring(0, idx).trim() : trimmed;
      if (key.equalsIgnoreCase(current)) {
        return true;
      }
    }
    return false;
  }

  private static long readRunAgeMillis(TrainProperties properties) {
    Optional<String> valueOpt = readTagValue(properties, TAG_RUN_AT);
    if (valueOpt.isEmpty()) {
      return -1L;
    }
    try {
      long runAt = Long.parseLong(valueOpt.get());
      long now = System.currentTimeMillis();
      return now >= runAt ? now - runAt : -1L;
    } catch (NumberFormatException ex) {
      return -1L;
    }
  }

  private static Optional<String> readTagValue(TrainProperties properties, String key) {
    if (properties == null || key == null || key.isBlank() || !properties.hasTags()) {
      return Optional.empty();
    }
    for (String tag : properties.getTags()) {
      if (tag == null) {
        continue;
      }
      String trimmed = tag.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int idx = trimmed.indexOf('=');
      if (idx <= 0) {
        continue;
      }
      String current = trimmed.substring(0, idx).trim();
      if (!key.equalsIgnoreCase(current)) {
        continue;
      }
      String value = trimmed.substring(idx + 1).trim();
      if (!value.isEmpty()) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  private static Optional<Integer> readIntTagValue(TrainProperties properties, String key) {
    Optional<String> rawOpt = readTagValue(properties, key);
    if (rawOpt.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Integer.parseInt(rawOpt.get().trim()));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  /**
   * 根据 RouteStop 解析停站时长。
   *
   * <p>仅 STOP/TERMINATE 才返回时长；PASS 直接视为不停车。
   */
  private static Optional<Integer> resolveDwellSeconds(
      StorageProvider provider,
      UUID routeId,
      SignNodeDefinition definition,
      Optional<Integer> routeIndexOpt) {
    if (provider == null || routeId == null || definition == null) {
      return Optional.empty();
    }
    Optional<Route> routeOpt = provider.routes().findById(routeId);
    if (routeOpt.isEmpty()) {
      return Optional.empty();
    }
    Route route = routeOpt.get();
    Optional<Line> lineOpt = provider.lines().findById(route.lineId());
    if (lineOpt.isEmpty()) {
      return Optional.empty();
    }
    Line line = lineOpt.get();
    WaypointMetadata metadata = definition.waypointMetadata().orElse(null);
    if (metadata == null || metadata.kind() != WaypointKind.STATION) {
      return Optional.empty();
    }
    String stationCode = metadata.originStation();
    Optional<Station> stationOpt =
        provider.stations().findByOperatorAndCode(line.operatorId(), stationCode);

    List<RouteStop> stops = provider.routeStops().listByRoute(routeId);
    if (stops.isEmpty()) {
      return Optional.empty();
    }
    return findStop(stops, definition, stationOpt, stationCode, routeIndexOpt)
        .filter(stop -> stop.passType() != RouteStopPassType.PASS)
        .map(RouteStop::plannedDwellSeconds);
  }

  /** 读取 AutoStation 提示音配置，缺失则返回禁用配置。 */
  private AutoStationDoorController.DoorChimeSettings resolveChimeSettings() {
    if (plugin == null || plugin.getConfigManager() == null) {
      return AutoStationDoorController.DoorChimeSettings.none();
    }
    ConfigManager.ConfigView view = plugin.getConfigManager().current();
    if (view == null || view.autoStationSettings() == null) {
      return AutoStationDoorController.DoorChimeSettings.none();
    }
    ConfigManager.AutoStationSettings settings = view.autoStationSettings();
    return AutoStationDoorController.DoorChimeSettings.fromConfig(
        settings.doorCloseSound(), settings.doorCloseSoundVolume(), settings.doorCloseSoundPitch());
  }

  /**
   * 在 RouteStop 中按站点 ID 或 waypoint nodeId 进行匹配。
   *
   * <p>每个动态停靠先验证允许集合，再按站点 ID、节点与站码匹配，禁止元数据容错绕过股道资格。
   *
   * @return 本次实际到站对应的停靠；没有合法匹配时为空，不触发停站动作。
   */
  static Optional<RouteStop> findStop(
      List<RouteStop> stops,
      SignNodeDefinition definition,
      Optional<Station> stationOpt,
      String stationCode,
      Optional<Integer> routeIndexOpt) {
    UUID stationId = stationOpt.map(Station::id).orElse(null);
    NodeId nodeId = definition.nodeId();
    String nodeIdValue = nodeId.value();
    for (RouteStop stop : stops) {
      if (stop == null || !DynamicStopMatcher.allowsStopNode(nodeId, stop)) {
        continue;
      }
      if (stationId != null
          && stop.stationId().isPresent()
          && stationId.equals(stop.stationId().get())) {
        return Optional.of(stop);
      }
      if (stop.waypointNodeId().isPresent()
          && nodeIdValue.equalsIgnoreCase(stop.waypointNodeId().get())) {
        return Optional.of(stop);
      }
      // DYNAMIC 范围匹配
      if (DynamicStopMatcher.matchesStop(nodeId, stop)) {
        return Optional.of(stop);
      }
    }

    // fallback: RouteStop 使用 station 的 waypoint_node_id（含 track），但列车实际停靠在同站其他股道时，
    // 精确 nodeId 无法匹配，导致 AutoStation 误判为 PASS。这里尝试按站码匹配（忽略 track）并用 routeIndex 消歧义。
    if (stationCode == null || stationCode.isBlank()) {
      return Optional.empty();
    }
    String expectedOperator =
        definition.waypointMetadata().map(WaypointMetadata::operator).orElse(null);
    List<RouteStop> candidates = new java.util.ArrayList<>();
    for (RouteStop stop : stops) {
      if (stop == null
          || stop.passType() == RouteStopPassType.PASS
          || !DynamicStopMatcher.allowsStopNode(nodeId, stop)) {
        continue;
      }
      if (stop.waypointNodeId().isEmpty()) {
        continue;
      }
      String raw = stop.waypointNodeId().get();
      Optional<SignNodeDefinition> parsed = SignTextParser.parseWaypointLike(raw, NodeType.STATION);
      if (parsed.isEmpty()) {
        continue;
      }
      Optional<WaypointMetadata> metaOpt = parsed.get().waypointMetadata();
      if (metaOpt.isEmpty() || metaOpt.get().kind() != WaypointKind.STATION) {
        continue;
      }
      WaypointMetadata meta = metaOpt.get();
      if (expectedOperator != null && !expectedOperator.equalsIgnoreCase(meta.operator())) {
        continue;
      }
      if (!stationCode.equalsIgnoreCase(meta.originStation())) {
        continue;
      }
      candidates.add(stop);
    }
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    if (candidates.size() == 1) {
      return Optional.of(candidates.get(0));
    }
    if (routeIndexOpt == null || routeIndexOpt.isEmpty()) {
      return Optional.of(candidates.get(0));
    }
    int idx = routeIndexOpt.get();
    RouteStop best = null;
    int bestDelta = Integer.MAX_VALUE;
    for (RouteStop cand : candidates) {
      int seq = cand.sequence();
      int delta = seq >= idx ? (seq - idx) : Integer.MAX_VALUE / 2;
      if (delta < bestDelta) {
        bestDelta = delta;
        best = cand;
      }
    }
    return Optional.of(best != null ? best : candidates.get(0));
  }

  private static String shortUuid(UUID uuid) {
    if (uuid == null) {
      return "unknown";
    }
    String value = uuid.toString();
    return value.length() <= 8 ? value : value.substring(0, 8);
  }

  private static String safeTrainName(SignActionEvent event) {
    try {
      if (event != null && event.hasGroup()) {
        MinecartGroup group = event.getGroup();
        if (group != null) {
          TrainProperties properties = group.getProperties();
          if (properties != null) {
            String name = properties.getTrainName();
            if (name != null && !name.isEmpty()) {
              return name;
            }
          }
        }
      }
      if (event != null) {
        String rcName = event.getRCName();
        if (rcName != null && !rcName.isEmpty()) {
          return rcName;
        }
      }
    } catch (Throwable ignored) {
      // 忽略
    }
    return UNKNOWN_TRAIN_NAME;
  }

  /**
   * 推断列车朝向（车头面向方向），用于判定左右门。
   *
   * <p>优先使用车体/模型朝向（{@code orientationForward}），避免折返/倒车导致“行进方向”翻转；仅在无法取到朝向时才回退到方向字段。
   */
  private static FacingResult resolveFacingDirectionResult(SignActionEvent info) {
    if (info == null || !info.hasGroup() || info.getGroup().head() == null) {
      return FacingResult.empty();
    }
    var head = info.getGroup().head();
    Vector orientationVector =
        AutoStationDoorController.normalizeHorizontalVector(head.getOrientationForward());
    BlockFace face = toHorizontalFace(orientationVector);
    if (face != null) {
      return new FacingResult(face, orientationVector, "orientation");
    }
    face = head.getDirectionTo();
    if (AutoStationDoorController.isHorizontalCompass(face)) {
      return new FacingResult(
          face, AutoStationDoorController.horizontalVector(face), "direction_to");
    }
    face = head.getDirectionFrom();
    if (AutoStationDoorController.isHorizontalCompass(face)) {
      return new FacingResult(
          face, AutoStationDoorController.horizontalVector(face), "direction_from");
    }
    face = head.getDirection();
    if (AutoStationDoorController.isHorizontalCompass(face)) {
      return new FacingResult(face, AutoStationDoorController.horizontalVector(face), "direction");
    }
    return FacingResult.empty();
  }

  private static Optional<BlockFace> resolveExitFace(AutoStationDoorDirection doorDirection) {
    if (doorDirection != null) {
      Optional<BlockFace> face = doorDirection.toBlockFace();
      if (face.isPresent()) {
        return face;
      }
    }
    return Optional.empty();
  }

  /**
   * 停站期间的下车点：开门时给开门的车厢登记牌子写的下车方位（{@link StationExitPoints}），发车前撤销。
   *
   * <p>停车位置标只开其中几节车厢的门时，其余车厢可能停在站台外（隧道、墙边），那里的乘客照 TrainCarts 原本的落点下车，不推向站台一侧。
   */
  static final class StationExitState {
    private final List<UUID> cars;
    private boolean applied;

    /** 登记过的下车方位；还没登记时为 {@code null}。 */
    private BlockFace exitFace;

    private double distance;

    /**
     * @param cars 开门的车厢（车厢属性的 UUID）
     */
    StationExitState(List<UUID> cars) {
      this.cars = new ArrayList<>(cars);
    }

    /** 按停站时开门的车厢建立。 */
    static StationExitState forDoorCars(MinecartGroup group, DoorCars doorCars) {
      List<UUID> cars = new ArrayList<>();
      if (group != null) {
        for (MinecartMember<?> member : group) {
          if (member != null
              && AutoStationDoorController.opensDoors(doorCars, member)
              && member.getProperties() != null) {
            cars.add(member.getProperties().getUUID());
          }
        }
      }
      return new StationExitState(cars);
    }

    boolean applied() {
      return applied;
    }

    /** 开门的车厢下车时落到座位的 {@code exitFace} 一侧 {@code distance} 格外。 */
    void apply(BlockFace exitFace, double distance) {
      this.exitFace = exitFace;
      this.distance = distance;
      for (UUID cart : cars) {
        applied |= StationExitPoints.open(this, cart, exitFace, distance);
      }
    }

    /** 停站中挂上来、跟着开门的车厢：同样登记下车方位（已经登记过的当场登记），发车前一起撤销。 */
    void add(List<UUID> joined) {
      for (UUID cart : joined) {
        if (cart == null || cars.contains(cart)) {
          continue;
        }
        cars.add(cart);
        if (exitFace != null) {
          applied |= StationExitPoints.open(this, cart, exitFace, distance);
        }
      }
    }

    void restore() {
      if (!applied) {
        return;
      }
      cars.forEach(cart -> StationExitPoints.close(this, cart));
      applied = false;
    }
  }

  private static String formatFacingVector(Vector vector) {
    Vector normalized = AutoStationDoorController.normalizeHorizontalVector(vector);
    if (normalized == null) {
      return "none";
    }
    return String.format(Locale.ROOT, "(%.3f,%.3f)", normalized.getX(), normalized.getZ());
  }

  private record FacingResult(BlockFace face, Vector vector, String source) {
    private static FacingResult empty() {
      return new FacingResult(null, null, "unknown");
    }
  }

  /**
   * 将车体朝向向量解析为水平八向。
   *
   * <p>直线附近仍归入 N/E/S/W；当次轴分量达到 22.5 度扇区阈值时保留 NE/NW/SE/SW，供斜向站台开门侧判定使用。
   */
  static BlockFace toHorizontalFace(Vector vector) {
    if (vector == null) {
      return null;
    }
    double x = vector.getX();
    double z = vector.getZ();
    if (!Double.isFinite(x) || !Double.isFinite(z)) {
      return null;
    }
    if (Math.abs(x) < 1.0e-6 && Math.abs(z) < 1.0e-6) {
      return null;
    }
    double absX = Math.abs(x);
    double absZ = Math.abs(z);
    if (absX <= 1.0e-6) {
      return z >= 0.0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }
    if (absZ <= 1.0e-6) {
      return x >= 0.0 ? BlockFace.EAST : BlockFace.WEST;
    }
    double ratio = Math.min(absX, absZ) / Math.max(absX, absZ);
    if (ratio >= HORIZONTAL_DIAGONAL_MIN_RATIO) {
      if (x >= 0.0 && z < 0.0) {
        return BlockFace.NORTH_EAST;
      }
      if (x >= 0.0) {
        return BlockFace.SOUTH_EAST;
      }
      if (z >= 0.0) {
        return BlockFace.SOUTH_WEST;
      }
      return BlockFace.NORTH_WEST;
    }
    if (absX > absZ) {
      return x >= 0.0 ? BlockFace.EAST : BlockFace.WEST;
    }
    return z >= 0.0 ? BlockFace.SOUTH : BlockFace.NORTH;
  }
}
