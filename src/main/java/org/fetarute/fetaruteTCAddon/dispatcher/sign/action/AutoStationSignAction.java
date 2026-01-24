package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.standard.StandardProperties;
import com.bergerkiller.bukkit.tc.properties.standard.type.ExitOffset;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeStorageSynchronizer;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * AutoStation 牌子（站点行为节点）。
 *
 * <p>用于承载“停站/开关门/站台行为”等语义，因此只接受站点本体（4 段 {@code Operator:S:Station:Track}）。
 * 站咽喉属于图节点（Waypoint）职责，不应使用 AutoStation 牌子注册。
 *
 * <p>行为触发依赖列车 {@code FTA_ROUTE_ID} tag 与 RouteStop：仅在 STOP/TERMINATE 时停站， {@code dwellSeconds}
 * 缺失时默认 20 秒。开门失败将跳过关门动作，避免“未开门先关门”的误触发。
 */
public final class AutoStationSignAction extends AbstractNodeSignAction {

  private static final int DEFAULT_DWELL_SECONDS = 20;
  private static final String TAG_ROUTE_ID = "FTA_ROUTE_ID";
  private static final String TAG_DOOR_FIRST_STOP_DONE = "FTA_DOOR_FIRST_STOP_DONE";
  private static final String TAG_RUN_AT = "FTA_RUN_AT";
  private static final long DOOR_OPEN_DELAY_TICKS = 20L;
  private static final long DOOR_OPEN_FIRST_DELAY_TICKS = 60L;
  private static final long TICK_MILLIS = 50L;
  private static final int STOP_WAIT_TIMEOUT_TICKS = 200;
  private static final int STOP_STABLE_TICKS = 1;
  private static final int DOOR_OPEN_RETRY_INTERVAL_TICKS = 5;
  private static final long DOOR_CLOSE_EARLY_TICKS = 100L;
  private static final int DOOR_OPEN_MAX_ATTEMPTS = 12;
  private static final long DOOR_OPEN_MAX_RETRY_WINDOW_TICKS = 160L;
  private static final double EXIT_OFFSET_DISTANCE_BLOCKS = 3.0;

  private final FetaruteTCAddon plugin;

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

  /**
   * AutoStation 执行入口：仅在列车进入牌子触发一次。
   *
   * <p>无 RouteId 或无匹配 RouteStop 时视为 waypoint 直接通过。停站时先居中停稳，再延迟一定 tick 开门；dwell 从开门后开始计时。
   */
  @Override
  public void execute(SignActionEvent info) {
    if (!info.isAction(SignActionType.GROUP_ENTER)) {
      return;
    }
    if (!info.hasGroup() || plugin == null) {
      return;
    }
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      return;
    }
    Optional<StorageProvider> providerOpt = plugin.getStorageManager().provider();
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    TrainProperties properties = info.getGroup().getProperties();
    Optional<UUID> routeIdOpt = readRouteId(properties);
    if (routeIdOpt.isEmpty()) {
      return;
    }
    UUID routeId = routeIdOpt.get();
    Optional<SignNodeDefinition> definitionOpt =
        registry.get(info.getBlock()).or(() -> parseDefinition(info));
    if (definitionOpt.isEmpty()) {
      return;
    }
    SignNodeDefinition definition = definitionOpt.get();
    Optional<Integer> dwellOpt = resolveDwellSeconds(provider, routeIdOpt.get(), definition);
    if (dwellOpt.isEmpty()) {
      return;
    }
    int dwellSeconds = dwellOpt.get();
    if (dwellSeconds <= 0) {
      return;
    }

    var group = info.getGroup();
    String trainName = safeTrainName(info);
    String stopSessionId = shortUuid(UUID.randomUUID());
    com.bergerkiller.bukkit.tc.Station station = new com.bergerkiller.bukkit.tc.Station(info);
    group.getActions().launchReset();
    station.centerTrain();

    AutoStationDoorDirection doorDirection = AutoStationDoorDirection.parse(info.getLine(3));
    FacingResult facingResult = resolveFacingDirectionResult(info);
    BlockFace facingDirection = facingResult.face();
    String facingSource = facingResult.source();
    AutoStationDoorController.DoorChimeSettings chimeSettings = resolveChimeSettings();
    AutoStationDoorController.DoorSession session =
        AutoStationDoorController.plan(group, facingDirection, doorDirection, chimeSettings);
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
              + "s, door="
              + doorDirection
              + ", facing="
              + facingDirection
              + ", source="
              + facingSource
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
          "AutoStation 跳过: 无法判定开门方向 (door="
              + doorDirection
              + ", facing="
              + facingDirection
              + ", source="
              + facingSource
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
        facingSource,
        chimeSettings,
        session,
        firstStop);
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
      String facingSource,
      AutoStationDoorController.DoorChimeSettings chimeSettings,
      AutoStationDoorController.DoorSession session,
      boolean firstStop) {
    if (plugin == null || info == null || !info.hasGroup()) {
      return;
    }
    MinecartGroup group = info.getGroup();
    if (group == null) {
      return;
    }
    if (firstStop) {
      Bukkit.getScheduler()
          .runTaskLater(plugin, () -> AutoStationDoorController.warmUpDoorAnimations(group), 2L);
      Bukkit.getScheduler()
          .runTaskLater(plugin, () -> AutoStationDoorController.warmUpDoorAnimations(group), 10L);
      Bukkit.getScheduler()
          .runTaskLater(
              plugin, () -> AutoStationDoorController.warmUpDoorAnimations(group, true), 20L);
    }
    new org.bukkit.scheduler.BukkitRunnable() {
      private int waitedTicks = 0;

      private int stoppedTicks = 0;

      @Override
      public void run() {
        if (!group.isValid()) {
          cancel();
          return;
        }
        if (!group.isMoving()) {
          stoppedTicks++;
          if (stoppedTicks >= STOP_STABLE_TICKS) {
            cancel();
            handleStop(
                info,
                definition,
                trainName,
                routeId,
                stopSessionId,
                dwellSeconds,
                doorDirection,
                facingDirection,
                facingSource,
                chimeSettings,
                session,
                false,
                firstStop);
            return;
          }
        } else {
          stoppedTicks = 0;
        }
        waitedTicks++;
        if (waitedTicks >= STOP_WAIT_TIMEOUT_TICKS) {
          cancel();
          handleStop(
              info,
              definition,
              trainName,
              routeId,
              stopSessionId,
              dwellSeconds,
              doorDirection,
              facingDirection,
              facingSource,
              chimeSettings,
              session,
              true,
              firstStop);
        }
      }
    }.runTaskTimer(plugin, 1L, 1L);
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
      String facingSource,
      AutoStationDoorController.DoorChimeSettings chimeSettings,
      AutoStationDoorController.DoorSession session,
      boolean timedOut,
      boolean firstStop) {
    MinecartGroup group = info == null ? null : info.getGroup();
    if (group == null || !group.isValid()) {
      return;
    }
    FetaruteTCAddon plugin = this.plugin;
    if (plugin == null) {
      return;
    }
    if (dwellSeconds > 0 && !group.isMoving()) {
      plugin.getDwellRegistry().ifPresent(registry -> registry.start(trainName, dwellSeconds));
    }
    TrainProperties properties = group.getProperties();
    ExitOffsetState exitOffsetState = new ExitOffsetState(properties);
    com.bergerkiller.bukkit.tc.actions.GroupActionWaitState waitState =
        group.getActions().addActionWaitState();
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
              + ", door="
              + doorDirection
              + ", attachments="
              + summarizeAttachments(group)
              + " @ "
              + locationText(info));
    }
    long dwellTicks = Math.max(0L, dwellSeconds * 20L);
    String location = locationText(info);
    new org.bukkit.scheduler.BukkitRunnable() {
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
      private BlockFace cachedFacing = null;
      private String cachedAnimations = null;
      private String cachedPlanSummary = null;
      private AutoStationDoorController.DoorSession cachedSession = null;

      @Override
      public void run() {
        if (!group.isValid()) {
          exitOffsetState.restore();
          waitState.stop();
          cancel();
          return;
        }
        ticksSinceStop++;
        if (opened) {
          ticksSinceOpen++;
          boolean legacy = cachedSession != null && cachedSession.usesLegacyDoorAnimation();
          long closeStartTick;
          long closeSoundTick;
          if (legacy) {
            long closeDuration =
                cachedSession == null ? -1L : cachedSession.estimatedCloseDurationTicks();
            if (closeDuration <= 0L) {
              closeDuration = DOOR_CLOSE_EARLY_TICKS;
            }
            closeStartTick = Math.max(0L, dwellTicks - closeDuration);
            closeSoundTick =
                closeStartTick + AutoStationDoorController.legacyCloseSoundDelayTicks();
            if (closeSoundTick > dwellTicks) {
              closeSoundTick = dwellTicks;
            }
          } else {
            closeStartTick =
                dwellTicks > DOOR_CLOSE_EARLY_TICKS
                    ? dwellTicks - DOOR_CLOSE_EARLY_TICKS
                    : dwellTicks;
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
                    + ", door="
                    + doorDirection
                    + ", t="
                    + ticksSinceOpen
                    + "/"
                    + dwellTicks
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
                  legacy ? cachedSession.startCloseAnimation() : cachedSession.close(true);
            }
            closeStarted = true;
          }
          if (legacy
              && closeAnimationTriggered
              && !closeSoundPlayed
              && ticksSinceOpen >= closeSoundTick) {
            closeSoundPlayed = true;
            if (cachedSession != null) {
              cachedSession.playCloseSound();
            }
          }
          if (ticksSinceOpen >= dwellTicks) {
            // 每 20 tick (1秒) 检查一次发车门控，避免刷屏与性能浪费。
            if ((ticksSinceOpen - dwellTicks) % 20 != 0) {
              return;
            }

            boolean canDepart = true;
            if (plugin.getRuntimeDispatchService().isPresent()) {
              // 检查出站门控（闭塞/占用）
              canDepart =
                  plugin.getRuntimeDispatchService().get().checkDeparture(group, definition);
            }

            if (canDepart) {
              exitOffsetState.restore();
              waitState.stop();
              cancel();
              plugin.getDwellRegistry().ifPresent(registry -> registry.clear(trainName));
              if (properties != null) {
                try {
                  com.bergerkiller.bukkit.tc.Station station =
                      new com.bergerkiller.bukkit.tc.Station(info);
                  BlockFace launchFace = station.getNextDirectionFace();
                  if (launchFace != null && AutoStationDoorController.isCardinal(launchFace)) {
                    org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper.writeTag(
                        properties, "FTA_LAUNCH_DIR", launchFace.name());
                  }
                } catch (Throwable ignored) {
                  // 回退到调度控车逻辑
                }
              }
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
        String resolvedSource = facingResult.face() == null ? facingSource : facingResult.source();
        String animations = summarizeDoorAnimations(group);
        boolean shouldRebuild =
            cachedSession == null
                || cachedFacing != resolvedFacing
                || !java.util.Objects.equals(cachedAnimations, animations);
        if (shouldRebuild) {
          cachedFacing = resolvedFacing;
          cachedAnimations = animations;
          cachedSession =
              AutoStationDoorController.plan(group, resolvedFacing, doorDirection, chimeSettings);
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
                    + ", door="
                    + doorDirection
                    + ", facing="
                    + resolvedFacing
                    + ", source="
                    + resolvedSource
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
              .flatMap(face -> buildExitOffset(face, EXIT_OFFSET_DISTANCE_BLOCKS))
              .ifPresent(exitOffsetState::apply);
        }
        if (cachedSession == null) {
          return;
        }
        if (!cachedSession.hasActions()) {
          return;
        }
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
                    + ", door="
                    + doorDirection
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
          waitState.stop();
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
                    + ", door="
                    + doorDirection
                    + ", attempt="
                    + openAttempts
                    + ", facing="
                    + resolvedFacing
                    + ", source="
                    + resolvedSource
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
                + ", door="
                + doorDirection
                + ", facing="
                + resolvedFacing
                + ", source="
                + resolvedSource
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

  /**
   * 根据 RouteStop 解析停站时长。
   *
   * <p>仅 STOP/TERMINATE 才返回时长；PASS 直接视为不停车。
   */
  private static Optional<Integer> resolveDwellSeconds(
      StorageProvider provider, UUID routeId, SignNodeDefinition definition) {
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
    RouteStop match = findStop(stops, definition, stationOpt);
    if (match == null) {
      return Optional.empty();
    }
    if (match.passType() == RouteStopPassType.PASS) {
      return Optional.empty();
    }
    int dwell = match.dwellSeconds().orElse(DEFAULT_DWELL_SECONDS);
    return Optional.of(dwell);
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
   * <p>优先站点 ID 匹配，其次 nodeId。
   */
  private static RouteStop findStop(
      List<RouteStop> stops, SignNodeDefinition definition, Optional<Station> stationOpt) {
    UUID stationId = stationOpt.map(Station::id).orElse(null);
    String nodeId = definition.nodeId().value();
    for (RouteStop stop : stops) {
      if (stop == null) {
        continue;
      }
      if (stationId != null
          && stop.stationId().isPresent()
          && stationId.equals(stop.stationId().get())) {
        return stop;
      }
      if (stop.waypointNodeId().isPresent()
          && nodeId.equalsIgnoreCase(stop.waypointNodeId().get())) {
        return stop;
      }
    }
    return null;
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
    return "unknown";
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
    BlockFace face = toCardinalFace(head.getOrientationForward());
    if (face != null) {
      return new FacingResult(face, "orientation");
    }
    face = head.getDirectionTo();
    if (AutoStationDoorController.isCardinal(face)) {
      return new FacingResult(face, "direction_to");
    }
    face = head.getDirectionFrom();
    if (AutoStationDoorController.isCardinal(face)) {
      return new FacingResult(face, "direction_from");
    }
    face = head.getDirection();
    if (AutoStationDoorController.isCardinal(face)) {
      return new FacingResult(face, "direction");
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

  private static Optional<ExitOffset> buildExitOffset(BlockFace face, double distance) {
    if (face == null || !Double.isFinite(distance) || distance <= 0.0) {
      return Optional.empty();
    }
    double x = 0.0;
    double z = 0.0;
    switch (face) {
      case EAST -> z = +distance;
      case WEST -> z = -distance;
      case SOUTH -> x = +distance;
      case NORTH -> x = -distance;
      default -> {
        return Optional.empty();
      }
    }
    return Optional.of(ExitOffset.create(x, 0.0, z, Float.NaN, Float.NaN));
  }

  private static final class ExitOffsetState {
    private final TrainProperties properties;
    private final ExitOffset original;
    private boolean applied;

    private ExitOffsetState(TrainProperties properties) {
      this.properties = properties;
      this.original = properties == null ? null : properties.get(StandardProperties.EXIT_OFFSET);
    }

    boolean applied() {
      return applied;
    }

    void apply(ExitOffset offset) {
      if (properties == null || offset == null) {
        return;
      }
      properties.set(StandardProperties.EXIT_OFFSET, offset);
      applied = true;
    }

    void restore() {
      if (!applied || properties == null) {
        return;
      }
      ExitOffset target = original != null ? original : StandardProperties.EXIT_OFFSET.getDefault();
      properties.set(StandardProperties.EXIT_OFFSET, target);
      applied = false;
    }
  }

  private record FacingResult(BlockFace face, String source) {
    private static FacingResult empty() {
      return new FacingResult(null, "unknown");
    }
  }

  private static BlockFace toCardinalFace(org.bukkit.util.Vector vector) {
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
    if (Math.abs(x) >= Math.abs(z)) {
      return x >= 0.0 ? BlockFace.EAST : BlockFace.WEST;
    }
    return z >= 0.0 ? BlockFace.SOUTH : BlockFace.NORTH;
  }
}
