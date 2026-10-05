package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup.SpawnLocationList;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup.SpawnMode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDestinationResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainSpawnTagInitializer;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * TrainCarts 实际出车实现：查找锚点轨道并生成物理编组，再把可失败的 tags/warm-up 初始化延后交给上层事务执行。
 *
 * <p>注意：本类不负责闭塞门控与队列；上层 TicketAssigner 决定“何时允许 spawn”。
 */
public final class TrainCartsDepotSpawner implements DepotSpawner {

  private static final long DEPOT_CHUNK_TICKET_TICKS = 200L;
  private static final long OFFLINE_PROBE_COOLDOWN_MILLIS = 60_000L;
  private static final long OFFLINE_WARN_COOLDOWN_MILLIS = 600_000L;

  private final FetaruteTCAddon plugin;
  private final SignNodeRegistry signNodeRegistry;
  private final Consumer<String> debugLogger;
  private volatile org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager
      occupancyManager;
  private final Set<String> keepChunksLoadedWarnedPatterns = ConcurrentHashMap.newKeySet();
  private volatile ConsistArbiter consistArbiter = ConsistArbiter.NONE;

  /** 每个车库上次探测离线编组的时间；键是车库节点，数量受车库数限制。 */
  private final Map<String, Long> offlineProbeAtMillis = new ConcurrentHashMap<>();

  /** 每个车库上次就离线编组告警的时间。 */
  private final Map<String, Long> offlineWarnAtMillis = new ConcurrentHashMap<>();

  public TrainCartsDepotSpawner(
      FetaruteTCAddon plugin, SignNodeRegistry signNodeRegistry, Consumer<String> debugLogger) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.signNodeRegistry = Objects.requireNonNull(signNodeRegistry, "signNodeRegistry");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /**
   * 注入占用管理器（用于 DYNAMIC depot 时优先选择空闲轨道）。
   *
   * @param manager 占用管理器（可为 null）
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "OccupancyManager 是调度层共享服务句柄；Depot 选线需要读取同一运行时占用状态，不能复制。")
  public void setOccupancyManager(
      org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager manager) {
    this.occupancyManager = manager;
  }

  /**
   * 注入车型裁决：route 绑了编组方案时按方案选编组，并把车种等标签写到新车上。
   *
   * @param arbiter 车型裁决；为 null 时恢复旧规则
   */
  public void setConsistArbiter(ConsistArbiter arbiter) {
    this.consistArbiter = arbiter == null ? ConsistArbiter.NONE : arbiter;
  }

  @Override
  public Optional<DepotSpawner.MaterializedSpawn> spawn(
      StorageProvider provider, SpawnTicket ticket, String trainName, Instant now) {
    if (provider == null || ticket == null || ticket.service() == null || trainName == null) {
      return Optional.empty();
    }
    SpawnService service = ticket.service();
    Optional<Route> routeOpt = provider.routes().findById(service.routeId());
    if (routeOpt.isEmpty()) {
      return Optional.empty();
    }
    Route route = routeOpt.get();

    // 解析 depot nodeId（可能是 DYNAMIC）
    String depotSpec =
        ticket.selectedDepotNodeId().filter(s -> !s.isBlank()).orElse(service.depotNodeId());
    Optional<DepotInfo> depotInfoOpt = resolveDepotInfo(depotSpec);
    if (depotInfoOpt.isEmpty()) {
      debugLogger.accept("自动发车失败: 未找到 depot 牌子 spec=" + depotSpec);
      return Optional.empty();
    }
    DepotInfo depotInfo = depotInfoOpt.get();
    NodeId depotId = depotInfo.nodeId;
    World world = Bukkit.getWorld(depotInfo.worldId());
    if (world == null) {
      debugLogger.accept("自动发车失败: depot 世界未加载 worldId=" + depotInfo.worldId());
      return Optional.empty();
    }
    loadNearbyChunks(world, depotInfo.x(), depotInfo.z(), 4, plugin, DEPOT_CHUNK_TICKET_TICKS);
    Block signBlock = world.getBlockAt(depotInfo.x(), depotInfo.y(), depotInfo.z());
    if (!(signBlock.getState() instanceof Sign sign)) {
      debugLogger.accept("自动发车失败: depot 方块不是牌子 @ " + depotInfo.locationText());
      return Optional.empty();
    }

    ConsistArbiter.SpawnChoice choice = chooseConsist(ticket);
    if (choice.kind() == ConsistArbiter.SpawnChoice.Kind.BLOCKED) {
      debugLogger.accept("自动发车失败: 编组方案里没有能出的车型 route=" + route.code() + " " + choice.reason());
      return Optional.empty();
    }
    // 编组来源：票上按编组方案选定的车型 > route 的 spawn_train_pattern > 车库牌子第 4 行（兜底）
    Optional<String> patternOpt =
        choice
            .pattern()
            .or(() -> DepotSpawnPattern.fromRoute(route))
            .or(() -> DepotSpawnPattern.fromSign(sign));
    if (patternOpt.isEmpty()) {
      debugLogger.accept(
          "自动发车失败: 缺少 spawn pattern route=" + route.code() + " depot=" + depotId.value());
      return Optional.empty();
    }
    String pattern = patternOpt.get();

    TrainCarts trainCarts = TrainCarts.plugin;
    if (trainCarts == null) {
      return Optional.empty();
    }
    SpawnableGroup spawnable = SpawnableGroup.parse(trainCarts, pattern);
    if (spawnable == null || spawnable.getMembers().isEmpty()) {
      debugLogger.accept("自动发车失败: pattern 无效 pattern=" + pattern);
      return Optional.empty();
    }

    TrainCartsRailBlockAccess access = new TrainCartsRailBlockAccess(world);
    Set<RailBlockPos> anchors = findAnchorRails(access, depotInfo);
    if (anchors.isEmpty()) {
      debugLogger.accept("自动发车失败: depot 附近无轨道 node=" + depotId.value());
      return Optional.empty();
    }
    warnAboutOfflineGroupsNearDepot(world, anchors, depotId);
    Optional<MinecartGroup> spawnedOpt = spawnAtAnchors(world, spawnable, anchors);
    if (spawnedOpt.isEmpty()) {
      debugLogger.accept("自动发车失败: spawn 失败 node=" + depotId.value());
      return Optional.empty();
    }

    MinecartGroup group = spawnedOpt.get();
    // 必须在本 tick 内（TrainCarts 首个物理 tick 之前）设置，否则未开启常驻加载的出库车会立刻被卸载。
    warnAboutKeepChunksLoaded(group.getProperties(), pattern, depotId);
    return Optional.of(
        new DepotSpawner.MaterializedSpawn(
            group,
            () -> {
              initializeMaterializedSpawn(
                  group, ticket, service, depotId, pattern, route, provider, trainName, now);
              if (group.getProperties() != null) {
                choice
                    .tags()
                    .forEach(
                        (key, value) -> TrainTagHelper.writeTag(group.getProperties(), key, value));
              }
            }));
  }

  /** 问车型裁决；裁决本身出错时按旧规则取编组，不因为它停发。票上指定了车型的除外：改出别的车型就对不上表了。 */
  private ConsistArbiter.SpawnChoice chooseConsist(SpawnTicket ticket) {
    try {
      return consistArbiter.chooseSpawn(ticket);
    } catch (RuntimeException | LinkageError ex) {
      if (ticket.consist().isPresent()) {
        debugLogger.accept("车型裁决异常，指定车型的票不出车: ticket=" + ticket.id() + " error=" + ex);
        return ConsistArbiter.SpawnChoice.blocked("consist-arbiter-error");
      }
      debugLogger.accept("车型裁决异常，按旧规则取编组: ticket=" + ticket.id() + " error=" + ex);
      return ConsistArbiter.SpawnChoice.legacy();
    }
  }

  private void initializeMaterializedSpawn(
      MinecartGroup group,
      SpawnTicket ticket,
      SpawnService service,
      NodeId depotId,
      String pattern,
      Route route,
      StorageProvider provider,
      String trainName,
      Instant now) {
    if (group.getProperties() != null) {
      initializeSpawnOwner(group.getProperties(), trainName);
      group.getProperties().clearDestinationRoute();
      group.getProperties().clearDestination();
      addTags(group.getProperties(), ticket.id(), service, depotId, pattern, route, provider, now);
      TrainTagHelper.writeTag(group.getProperties(), RouteProgressRegistry.TAG_ROUTE_INDEX, "0");
      TrainTagHelper.writeTag(
          group.getProperties(),
          RouteProgressRegistry.TAG_ROUTE_UPDATED_AT,
          String.valueOf((now == null ? Instant.now() : now).toEpochMilli()));
    }
  }

  /**
   * 出库点附近还有离线（已卸载）编组时留下证据，但仍然放行。
   *
   * <p>TrainCarts 的占用检查只看已加载的车，看不到躺在离线存储里的车。新车在同一锚点生成后，那些车苏醒时会同坐标复原。
   * 这里只按区块粒度观测，不拦截：拦截一旦误判会让整个车库永久停发，而该现象的根因（出库车未常驻加载）已在 {@link #ensureKeepChunksLoaded}
   * 处理，本层留待有实测数据再决定是否升级为拦截。
   */
  private void warnAboutOfflineGroupsNearDepot(
      World world, Set<RailBlockPos> anchors, NodeId depotId) {
    try {
      TrainCarts trainCarts = TrainCarts.plugin;
      if (trainCarts == null || trainCarts.getOfflineGroups() == null) {
        return;
      }
      // 快照要加锁并拷贝全部离线编组，而出库重试可能每几秒一次：按车库冷却后再取。
      long now = System.currentTimeMillis();
      Long lastProbe = offlineProbeAtMillis.get(depotId.value());
      if (lastProbe != null
          && now - lastProbe >= 0L
          && now - lastProbe < OFFLINE_PROBE_COOLDOWN_MILLIS) {
        return;
      }
      offlineProbeAtMillis.put(depotId.value(), now);
      List<int[]> blocks = new java.util.ArrayList<>();
      for (RailBlockPos anchor : anchors) {
        blocks.add(new int[] {anchor.x(), anchor.z()});
      }
      Set<Long> footprint = OfflineSpawnFootprint.chunksAround(blocks, 2);
      List<OfflineSpawnFootprint.OfflineGroupView> views = new java.util.ArrayList<>();
      com.bergerkiller.bukkit.common.offline.OfflineWorld offlineWorld =
          com.bergerkiller.bukkit.common.offline.OfflineWorld.of(world);
      for (com.bergerkiller.bukkit.tc.offline.train.OfflineGroupWorld groupWorld :
          trainCarts.getOfflineGroups().createSnapshot()) {
        if (!offlineWorld.equals(groupWorld.getWorld())) {
          continue;
        }
        for (com.bergerkiller.bukkit.tc.offline.train.OfflineGroup group : groupWorld) {
          Set<Long> chunks = new java.util.HashSet<>();
          for (com.bergerkiller.bukkit.tc.offline.train.OfflineMember member : group.members) {
            chunks.add(OfflineSpawnFootprint.chunkKey(member.cx, member.cz));
          }
          views.add(new OfflineSpawnFootprint.OfflineGroupView(group.name, chunks));
        }
      }
      List<String> nearby = OfflineSpawnFootprint.groupsIn(views, footprint);
      Long lastWarn = offlineWarnAtMillis.get(depotId.value());
      boolean warnDue =
          lastWarn == null || now - lastWarn < 0L || now - lastWarn >= OFFLINE_WARN_COOLDOWN_MILLIS;
      if (!nearby.isEmpty() && warnDue) {
        offlineWarnAtMillis.put(depotId.value(), now);
        plugin
            .getLogger()
            .warning(
                "出库点附近存在离线（已卸载）的编组，新出库车可能与其叠放；它们苏醒时会同坐标复原。请确认是否为遗留的幽灵车并清理 depot="
                    + depotId.value()
                    + " groups="
                    + nearby);
      }
    } catch (RuntimeException | LinkageError ex) {
      debugLogger.accept("出库点离线编组探测失败 depot=" + depotId.value() + " error=" + ex);
    }
  }

  /** 出库后开启常驻加载；开启了、或开启失败且仍未常驻，都按 pattern 去重后告警一次。 */
  private void warnAboutKeepChunksLoaded(
      com.bergerkiller.bukkit.tc.properties.TrainProperties properties,
      String pattern,
      NodeId depotId) {
    warnIfKeepChunksLoadedOnlyWhenMoving();
    if (ensureKeepChunksLoaded(properties)) {
      if (keepChunksLoadedWarnedPatterns.add(pattern)) {
        plugin
            .getLogger()
            .warning(
                "出库车的 spawn pattern 未开启 keepChunksLoaded，已强制开启（否则 TrainCarts 会在首个物理 tick 卸载出库车，"
                    + "冻结在出库口并被后续班次叠放）。请在该存档中开启常驻加载以消除本告警 pattern="
                    + pattern
                    + " depot="
                    + depotId.value());
      }
      return;
    }
    if (!keepsChunksLoaded(properties) && keepChunksLoadedWarnedPatterns.add("failed|" + pattern)) {
      plugin
          .getLogger()
          .warning(
              "无法为出库车开启 keepChunksLoaded，该车可能被 TrainCarts 卸载并冻结在出库口 pattern="
                  + pattern
                  + " depot="
                  + depotId.value());
    }
  }

  /**
   * TrainCarts 配置 {@code keepChunksLoadedOnlyWhenMoving=true} 时，静止且不在等待动作中的车 {@code canUnload()} 仍为
   * true： 刚出库的车在首个物理 tick 恰好是这种状态，强制常驻加载会静默失效。只在首次出库时告警一次。
   */
  private void warnIfKeepChunksLoadedOnlyWhenMoving() {
    try {
      if (com.bergerkiller.bukkit.tc.TCConfig.keepChunksLoadedOnlyWhenMoving
          && keepChunksLoadedWarnedPatterns.add("only-when-moving")) {
        plugin
            .getLogger()
            .warning(
                "TrainCarts 配置 keepChunksLoadedOnlyWhenMoving=true：静止的出库车仍会被卸载，强制常驻加载对刚出库的车不会生效。"
                    + "请在 TrainCarts 的 config.yml 中将其改为 false。");
      }
    } catch (RuntimeException | LinkageError ex) {
      debugLogger.accept("读取 TrainCarts keepChunksLoadedOnlyWhenMoving 失败 error=" + ex);
    }
  }

  private static boolean keepsChunksLoaded(
      com.bergerkiller.bukkit.tc.properties.TrainProperties properties) {
    try {
      return properties != null && properties.isKeepingChunksLoaded();
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }

  /**
   * 保证出库车常驻加载区块。
   *
   * <p>TrainCarts 对未开启 keepChunksLoaded 的车，只要其 5x5 区块区内有未加载区块就会立刻卸载；出库口附近无人时车在第一个物理 tick 就被冻结。FTA
   * 的占用模型假设受管列车一直被模拟，因此不依赖存档配置，出库时一律开启。
   *
   * <p>本方法在返回 {@code MaterializedSpawn} 之前调用，而物理编组此刻已经存在：按出库事务约定，可失败的初始化不得冒泡，否则出库会被当作抛异常重试， 无 tag
   * 的编组成为堵在出库锚点的无主幽灵车。因此任何 {@link RuntimeException} / {@link LinkageError} 都在此吞掉并按“未开启”返回。
   *
   * @return 本次是否由本方法开启（false 表示原本就是开启的，或读取/设置失败）
   */
  public static boolean ensureKeepChunksLoaded(
      com.bergerkiller.bukkit.tc.properties.TrainProperties properties) {
    try {
      if (properties == null || properties.isKeepingChunksLoaded()) {
        return false;
      }
      properties.setKeepChunksLoaded(true);
      return true;
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }

  /**
   * 初始化新生成列车的运行时 owner。
   *
   * <p>此操作发生在上层用正式列车名提交发车授权之前；TrainCarts 名称与 FTA owner tag 必须作为同一个初始化边界写入，避免 spawn pattern 中继承的旧
   * tag 被首个信号 tick 误判为手动改名。
   */
  static void initializeSpawnOwner(
      com.bergerkiller.bukkit.tc.properties.TrainProperties properties, String trainName) {
    Objects.requireNonNull(properties, "properties");
    if (trainName == null || trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
    String owner = trainName.trim();
    TrainSpawnTagInitializer.initializeOwner(properties, owner);
  }

  private static Optional<SignNodeRegistry.SignNodeInfo> findDepotNode(
      SignNodeRegistry registry, NodeId nodeId) {
    return registry.snapshotInfos().values().stream()
        .filter(info -> info != null && info.definition() != null)
        .filter(info -> nodeId.equals(info.definition().nodeId()))
        .filter(info -> info.definition().nodeType() == NodeType.DEPOT)
        .findFirst();
  }

  private static Set<RailBlockPos> findAnchorRails(
      TrainCartsRailBlockAccess access, DepotInfo depotInfo) {
    RailBlockPos center = new RailBlockPos(depotInfo.x(), depotInfo.y(), depotInfo.z());
    Set<RailBlockPos> anchors = access.findNearestRailBlocks(center, 2);
    if (!anchors.isEmpty()) {
      return anchors;
    }
    return access.findNearestRailBlocks(center, 4);
  }

  private static Optional<MinecartGroup> spawnAtAnchors(
      World world, SpawnableGroup spawnable, Set<RailBlockPos> anchors) {
    for (RailBlockPos anchor : anchors) {
      Optional<MinecartGroup> spawned = spawnAtAnchor(world, spawnable, anchor);
      if (spawned.isPresent()) {
        return spawned;
      }
    }
    return Optional.empty();
  }

  private static Optional<MinecartGroup> spawnAtAnchor(
      World world, SpawnableGroup spawnable, RailBlockPos anchor) {
    if (world == null || spawnable == null || anchor == null) {
      return Optional.empty();
    }
    Block railBlock = world.getBlockAt(anchor.x(), anchor.y(), anchor.z());
    RailPiece piece = RailPiece.create(railBlock);
    if (piece == null || piece.isNone()) {
      return Optional.empty();
    }
    RailState state = RailState.getSpawnState(piece);
    if (state == null) {
      return Optional.empty();
    }
    Vector direction = state.motionVector();
    if (direction == null) {
      return Optional.empty();
    }
    SpawnLocationList locations = findSpawnLocations(spawnable, piece, direction);
    if (locations == null) {
      return Optional.empty();
    }
    locations.loadChunks();
    if (locations.isOccupied()) {
      return Optional.empty();
    }
    MinecartGroup group = spawnable.spawn(locations);
    return Optional.ofNullable(group);
  }

  /**
   * 加载 depot 周边区块，并持有短期 chunk ticket 防止立刻卸载。
   *
   * <p>该方法会在 {@code holdTicks} 后自动释放 ticket。
   */
  private static void loadNearbyChunks(
      World world,
      int blockX,
      int blockZ,
      int blockRadius,
      org.bukkit.plugin.Plugin plugin,
      long holdTicks) {
    if (world == null || blockRadius < 0) {
      return;
    }
    int chunkRadius = Math.max(0, (blockRadius + 15) >> 4);
    int baseChunkX = blockX >> 4;
    int baseChunkZ = blockZ >> 4;
    java.util.Set<Long> ticketed = new java.util.HashSet<>();
    for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
      for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
        int cx = baseChunkX + dx;
        int cz = baseChunkZ + dz;
        if (!world.isChunkLoaded(cx, cz)) {
          world.getChunkAt(cx, cz);
        }
        if (plugin != null && holdTicks > 0L) {
          if (world.addPluginChunkTicket(cx, cz, plugin)) {
            ticketed.add((((long) cx) << 32) ^ (cz & 0xffffffffL));
          }
        }
      }
    }
    if (plugin != null && holdTicks > 0L && !ticketed.isEmpty()) {
      Bukkit.getScheduler()
          .runTaskLater(
              plugin,
              () -> {
                for (long key : ticketed) {
                  int cx = (int) (key >> 32);
                  int cz = (int) key;
                  world.removePluginChunkTicket(cx, cz, plugin);
                }
              },
              holdTicks);
    }
  }

  private static SpawnLocationList findSpawnLocations(
      SpawnableGroup spawnable, RailPiece piece, Vector direction) {
    SpawnLocationList locations = spawnable.findSpawnLocations(piece, direction, SpawnMode.DEFAULT);
    if (locations != null && locations.can_move) {
      return locations;
    }
    Vector reversed = direction.clone().multiply(-1.0);
    SpawnLocationList reversedLocations =
        spawnable.findSpawnLocations(piece, reversed, SpawnMode.DEFAULT);
    if (reversedLocations != null && reversedLocations.can_move) {
      return reversedLocations;
    }
    return locations != null ? locations : reversedLocations;
  }

  private static void addTags(
      com.bergerkiller.bukkit.tc.properties.TrainProperties properties,
      UUID runId,
      SpawnService service,
      NodeId depotId,
      String spawnPattern,
      Route route,
      StorageProvider provider,
      Instant now) {
    if (properties == null || runId == null || service == null || route == null) {
      return;
    }
    TrainSpawnTagInitializer.replaceLifecycleTags(
        properties, spawnTags(runId, service, depotId, spawnPattern, route, provider, now));
  }

  /**
   * 出库车的生命周期标签。
   *
   * <p>线路标签（{@code FTA_OPERATOR_CODE}/{@code FTA_LINE_CODE}）是列车对乘客运营的线路：首站有效 CHANGE
   * 的目标（定义书第一站之前的起步线路，见 {@link RouteLineChanges#entryLine}），没有时为交路自身的线路。 CHANGE
   * 是“抵达该站后”执行的，出库车没有抵达首站，所以要在这里就写成目标线路；交路代码与管理归属（交路组、时刻表）不受影响。
   */
  static Map<String, String> spawnTags(
      UUID runId,
      SpawnService service,
      NodeId depotId,
      String spawnPattern,
      Route route,
      StorageProvider provider,
      Instant now) {
    Instant ts = now == null ? Instant.now() : now;
    List<RouteStop> stops =
        provider == null ? List.of() : provider.routeStops().listByRoute(route.id());
    RouteLineChanges.LineRef line =
        RouteLineChanges.entryLine(
            stops, 0, new RouteLineChanges.LineRef(service.operatorCode(), service.lineCode()));
    Map<String, String> tags = new HashMap<>();
    tags.put("FTA_RUN_ID", runId.toString());
    tags.put(TrainSpawnTagInitializer.TAG_TRAIN_UID, runId.toString());
    tags.put("FTA_ROUTE_ID", service.routeId().toString());
    tags.put("FTA_ROUTE_CODE", service.routeCode());
    tags.put("FTA_LINE_CODE", line.lineCode());
    tags.put("FTA_OPERATOR_CODE", line.operatorCode());
    tags.put("FTA_PATTERN", route.patternType().name());
    tags.put("FTA_DEPOT_ID", depotId != null ? depotId.value() : "");
    tags.put(TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING, "true");
    tags.put(TrainSpawnTagInitializer.TAG_MATERIALIZED_ROLLBACK_PENDING, "true");
    tags.put("FTA_SPAWN_PATTERN", spawnPattern);
    tags.put("FTA_RUN_AT", String.valueOf(ts.toEpochMilli()));
    tags.put("FTA_DEST_CODE", "");
    tags.put("FTA_DEST_NAME", "");

    RouteDestinationResolver.resolve(
            stops, Optional.ofNullable(provider), route.name(), route.code())
        .ifPresent(
            dest -> {
              tags.put("FTA_DEST_CODE", dest.code());
              tags.put("FTA_DEST_NAME", dest.name());
            });
    return tags;
  }

  /**
   * 解析 depot 规范并返回 DepotInfo。
   *
   * <p>支持：
   *
   * <ul>
   *   <li>普通 nodeId：如 "SURC:D:OFL:1"
   *   <li>DYNAMIC 规范：如 "DYNAMIC:SURC:D:OFL" 或 "DYNAMIC:SURC:D:OFL:[1:3]"
   * </ul>
   */
  private Optional<DepotInfo> resolveDepotInfo(String depotSpec) {
    if (depotSpec == null || depotSpec.isBlank()) {
      return Optional.empty();
    }

    // 检查是否是 DYNAMIC depot
    if (SpawnDirectiveParser.isDynamicTarget(depotSpec)) {
      return resolveDynamicDepotInfo(depotSpec);
    }

    // 普通 depot：精确匹配 nodeId
    NodeId nodeId = NodeId.of(depotSpec);
    return findDepotNode(signNodeRegistry, nodeId)
        .map(
            info ->
                new DepotInfo(
                    nodeId, info.worldId(), info.x(), info.y(), info.z(), info.locationText()));
  }

  /**
   * 为 DYNAMIC depot 规范查找可用的 depot 轨道。
   *
   * <p>解析 "DYNAMIC:OP:D:DEPOT" 或 "DYNAMIC:OP:D:DEPOT:[1:3]" 格式， 选择第一个可用（空闲）的轨道。
   */
  private Optional<DepotInfo> resolveDynamicDepotInfo(String dynamicSpec) {
    // 解析 DYNAMIC:OP:D:DEPOT 或 DYNAMIC:OP:D:DEPOT:[1:3]
    if (dynamicSpec == null
        || !dynamicSpec.toUpperCase(java.util.Locale.ROOT).startsWith("DYNAMIC:")) {
      return Optional.empty();
    }
    String rest = dynamicSpec.substring("DYNAMIC:".length());
    String[] parts = rest.split(":", 4);
    if (parts.length < 3) {
      return Optional.empty();
    }
    String operatorCode = parts[0].trim();
    String nodeType = parts[1].trim(); // "D" for depot
    String nodeName = parts[2].trim();

    // 解析轨道范围
    int fromTrack = 1;
    int toTrack = 99;
    if (parts.length >= 4) {
      String rangeStr = parts[3].trim();
      Optional<TrackRange> rangeOpt = parseTrackRange(rangeStr);
      if (rangeOpt.isPresent()) {
        fromTrack = rangeOpt.get().from();
        toTrack = rangeOpt.get().to();
      }
    }

    // 构建 nodeId 前缀用于匹配
    String nodeIdPrefix = operatorCode + ":" + nodeType + ":" + nodeName + ":";

    // 查找所有匹配的 depot 节点
    List<SignNodeRegistry.SignNodeInfo> candidates =
        signNodeRegistry.snapshotInfos().values().stream()
            .filter(info -> info != null && info.definition() != null)
            .filter(info -> info.definition().nodeType() == NodeType.DEPOT)
            .filter(
                info -> {
                  String nodeIdValue = info.definition().nodeId().value();
                  return nodeIdValue != null
                      && nodeIdValue
                          .toUpperCase(java.util.Locale.ROOT)
                          .startsWith(nodeIdPrefix.toUpperCase(java.util.Locale.ROOT));
                })
            .toList();

    if (candidates.isEmpty()) {
      debugLogger.accept("DYNAMIC depot: 未找到匹配节点 spec=" + dynamicSpec + " prefix=" + nodeIdPrefix);
      return Optional.empty();
    }

    // 按轨道号排序并过滤范围
    final int fFrom = fromTrack;
    final int fTo = toTrack;
    List<SignNodeRegistry.SignNodeInfo> inRange =
        candidates.stream()
            .filter(
                info -> {
                  int track = extractTrackNumber(info.definition().nodeId().value());
                  return track >= fFrom && track <= fTo;
                })
            .sorted(
                java.util.Comparator.comparingInt(
                    info -> extractTrackNumber(info.definition().nodeId().value())))
            .toList();

    if (inRange.isEmpty()) {
      debugLogger.accept(
          "DYNAMIC depot: 范围内无节点 spec="
              + dynamicSpec
              + " range=["
              + fromTrack
              + ":"
              + toTrack
              + "]");
      return Optional.empty();
    }

    // 占用检查：优先选择空闲轨道
    var mgr = this.occupancyManager;
    SignNodeRegistry.SignNodeInfo selected = null;
    if (mgr != null) {
      // Pass 1: 优先选择未被占用的轨道
      for (SignNodeRegistry.SignNodeInfo info : inRange) {
        NodeId nodeId = info.definition().nodeId();
        if (!mgr.isNodeOccupied(nodeId)) {
          selected = info;
          debugLogger.accept(
              "DYNAMIC depot: 选择空闲轨道 spec="
                  + dynamicSpec
                  + " selected="
                  + nodeId.value()
                  + " (free)");
          break;
        }
      }
    }

    // Pass 2: 无空闲轨道或无 occupancyManager 时，选择第一个（排队等待）
    if (selected == null) {
      selected = inRange.get(0);
      debugLogger.accept(
          "DYNAMIC depot: 选择轨道 spec="
              + dynamicSpec
              + " selected="
              + selected.definition().nodeId()
              + (mgr != null ? " (all occupied, fallback)" : ""));
    }

    return Optional.of(
        new DepotInfo(
            selected.definition().nodeId(),
            selected.worldId(),
            selected.x(),
            selected.y(),
            selected.z(),
            selected.locationText()));
  }

  /**
   * 解析轨道范围字符串。
   *
   * @param rangeStr 如 "[1:3]" 或 "1"
   * @return TrackRange 或 empty
   */
  private static Optional<TrackRange> parseTrackRange(String rangeStr) {
    if (rangeStr == null || rangeStr.isBlank()) {
      return Optional.empty();
    }
    String trimmed = rangeStr.trim();
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
      String inner = trimmed.substring(1, trimmed.length() - 1);
      int colonIdx = inner.indexOf(':');
      if (colonIdx > 0) {
        try {
          int from = Integer.parseInt(inner.substring(0, colonIdx).trim());
          int to = Integer.parseInt(inner.substring(colonIdx + 1).trim());
          return Optional.of(new TrackRange(from, to));
        } catch (NumberFormatException e) {
          return Optional.empty();
        }
      }
    }
    // 单个数字
    try {
      int track = Integer.parseInt(trimmed);
      return Optional.of(new TrackRange(track, track));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /**
   * 从 nodeId 中提取轨道号。
   *
   * @param nodeId 如 "SURC:D:OFL:1"
   * @return 轨道号，解析失败返回 Integer.MAX_VALUE
   */
  private static int extractTrackNumber(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return Integer.MAX_VALUE;
    }
    int lastColon = nodeId.lastIndexOf(':');
    if (lastColon < 0 || lastColon >= nodeId.length() - 1) {
      return Integer.MAX_VALUE;
    }
    try {
      return Integer.parseInt(nodeId.substring(lastColon + 1).trim());
    } catch (NumberFormatException e) {
      return Integer.MAX_VALUE;
    }
  }

  private record TrackRange(int from, int to) {}

  private record DepotInfo(NodeId nodeId, UUID worldId, int x, int y, int z, String locationText) {}
}
