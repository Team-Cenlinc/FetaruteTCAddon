package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup.SpawnLocationList;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup.SpawnMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * TrainCarts 实际出车实现：复用 /fta depot spawn 的核心逻辑（查找锚点轨道 → spawn → 写 tags）。
 *
 * <p>注意：本类不负责闭塞门控与队列；上层 TicketAssigner 决定“何时允许 spawn”。
 */
public final class TrainCartsDepotSpawner implements DepotSpawner {

  private static final String ROUTE_SPAWN_PATTERN_KEY = "spawn_train_pattern";
  private static final long DEPOT_CHUNK_TICKET_TICKS = 200L;
  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private final FetaruteTCAddon plugin;
  private final SignNodeRegistry signNodeRegistry;
  private final Consumer<String> debugLogger;
  private volatile org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager
      occupancyManager;

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
  public void setOccupancyManager(
      org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager manager) {
    this.occupancyManager = manager;
  }

  @Override
  public Optional<MinecartGroup> spawn(
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
    String depotSpec = service.depotNodeId();
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

    Optional<String> routePattern = routeSpawnPattern(route);
    Optional<String> signPattern = readDepotPattern(sign);
    String pattern = firstNonBlank(routePattern.orElse(null), signPattern.orElse(null));
    if (pattern == null) {
      debugLogger.accept(
          "自动发车失败: 缺少 spawn pattern route=" + route.code() + " depot=" + depotId.value());
      return Optional.empty();
    }

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
    Optional<MinecartGroup> spawnedOpt = spawnAtAnchors(world, spawnable, anchors);
    if (spawnedOpt.isEmpty()) {
      debugLogger.accept("自动发车失败: spawn 失败 node=" + depotId.value());
      return Optional.empty();
    }

    MinecartGroup group = spawnedOpt.get();
    if (group.getProperties() != null) {
      group.getProperties().clearDestinationRoute();
      group.getProperties().clearDestination();
      group.getProperties().setTrainName(trainName);
      addTags(group.getProperties(), ticket.id(), service, depotId, pattern, route, provider, now);
      TrainTagHelper.writeTag(group.getProperties(), RouteProgressRegistry.TAG_ROUTE_INDEX, "0");
      TrainTagHelper.writeTag(
          group.getProperties(),
          RouteProgressRegistry.TAG_ROUTE_UPDATED_AT,
          String.valueOf((now == null ? Instant.now() : now).toEpochMilli()));
    }

    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () ->
                org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationDoorController
                    .warmUpDoorAnimations(group),
            2L);
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () ->
                org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationDoorController
                    .warmUpDoorAnimations(group),
            10L);

    return Optional.of(group);
  }

  private static Optional<SignNodeRegistry.SignNodeInfo> findDepotNode(
      SignNodeRegistry registry, NodeId nodeId) {
    return registry.snapshotInfos().values().stream()
        .filter(info -> info != null && info.definition() != null)
        .filter(info -> nodeId.equals(info.definition().nodeId()))
        .filter(info -> info.definition().nodeType() == NodeType.DEPOT)
        .findFirst();
  }

  private static Optional<String> routeSpawnPattern(Route route) {
    if (route == null) {
      return Optional.empty();
    }
    Object value = route.metadata().get(ROUTE_SPAWN_PATTERN_KEY);
    if (value instanceof String raw) {
      String normalized = normalizeSpawnPattern(raw);
      if (normalized != null) {
        return Optional.of(normalized);
      }
    }
    return Optional.empty();
  }

  private static Optional<String> readDepotPattern(Sign sign) {
    if (sign == null) {
      return Optional.empty();
    }
    return readDepotPatternFromSide(sign, Side.FRONT)
        .or(() -> readDepotPatternFromSide(sign, Side.BACK));
  }

  private static Optional<String> readDepotPatternFromSide(Sign sign, Side side) {
    SignSide view = sign.getSide(side);
    String header = PLAIN_TEXT.serialize(view.line(0)).trim();
    SignActionHeader parsed = SignActionHeader.parse(header);
    if (parsed == null || (!parsed.isTrain() && !parsed.isCart())) {
      return Optional.empty();
    }
    String type = PLAIN_TEXT.serialize(view.line(1)).trim().toLowerCase(Locale.ROOT);
    if (!"depot".equals(type)) {
      return Optional.empty();
    }
    String rawPattern = PLAIN_TEXT.serialize(view.line(3));
    String normalized = normalizeSpawnPattern(rawPattern);
    if (normalized == null) {
      return Optional.empty();
    }
    return Optional.of(normalized);
  }

  private static String normalizeSpawnPattern(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String raw : values) {
      if (raw == null) {
        continue;
      }
      String trimmed = raw.trim();
      if (!trimmed.isEmpty()) {
        return trimmed;
      }
    }
    return null;
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
    Instant ts = now == null ? Instant.now() : now;
    Map<String, String> tags = new HashMap<>();
    tags.put("FTA_RUN_ID", runId.toString());
    tags.put("FTA_ROUTE_ID", service.routeId().toString());
    tags.put("FTA_ROUTE_CODE", service.routeCode());
    tags.put("FTA_LINE_CODE", service.lineCode());
    tags.put("FTA_OPERATOR_CODE", service.operatorCode());
    tags.put("FTA_PATTERN", route.patternType().name());
    tags.put("FTA_DEPOT_ID", depotId != null ? depotId.value() : "");
    tags.put("FTA_SPAWN_PATTERN", spawnPattern);
    tags.put("FTA_RUN_AT", String.valueOf(ts.toEpochMilli()));

    resolveDestinationInfo(provider, route)
        .ifPresent(
            dest -> {
              tags.put("FTA_DEST_CODE", dest.code());
              tags.put("FTA_DEST_NAME", dest.name());
            });

    List<String> out = new ArrayList<>();
    for (Map.Entry<String, String> entry : tags.entrySet()) {
      String value = sanitizeTagValue(entry.getValue());
      if (value.isEmpty()) {
        continue;
      }
      out.add(entry.getKey() + "=" + value);
    }
    if (!out.isEmpty()) {
      properties.addTags(out.toArray(new String[0]));
    }
  }

  private static Optional<DestinationInfo> resolveDestinationInfo(
      StorageProvider provider, Route route) {
    if (provider == null || route == null) {
      return Optional.empty();
    }
    List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
    if (stops.isEmpty()) {
      return Optional.empty();
    }
    RouteStop candidate = null;
    for (RouteStop stop : stops) {
      if (stop != null && stop.passType() == RouteStopPassType.TERMINATE) {
        candidate = stop;
      }
    }
    if (candidate == null) {
      for (RouteStop stop : stops) {
        if (stop != null && stop.passType() == RouteStopPassType.STOP) {
          candidate = stop;
        }
      }
    }
    if (candidate == null) {
      candidate = stops.get(stops.size() - 1);
    }

    // 优先从 stationId 解析
    if (candidate.stationId().isPresent()) {
      Optional<Station> stationOpt = provider.stations().findById(candidate.stationId().get());
      if (stationOpt.isPresent()) {
        Station station = stationOpt.get();
        return Optional.of(new DestinationInfo(station.name(), station.code()));
      }
    }

    // 尝试从 DYNAMIC 规范解析（支持 "DYNAMIC:..." 或 "DSTY DYNAMIC:..." 格式）
    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(candidate);
    if (dynamicSpec.isPresent() && dynamicSpec.get().isStation()) {
      DynamicStopMatcher.DynamicSpec spec = dynamicSpec.get();
      return Optional.of(new DestinationInfo(spec.nodeName(), spec.nodeName()));
    }

    // 从 waypointNodeId 解析
    if (candidate.waypointNodeId().isPresent()) {
      String node = candidate.waypointNodeId().get();
      // 尝试解析站点格式 OP:S:STATION:TRACK
      String[] parts = node.split(":", -1);
      if (parts.length >= 4 && "S".equalsIgnoreCase(parts[1])) {
        String stationName = parts[2];
        return Optional.of(new DestinationInfo(stationName, stationName));
      }
      return Optional.of(new DestinationInfo(node, node));
    }

    // fallback: 使用 route name
    return Optional.of(new DestinationInfo(route.name(), route.code()));
  }

  private static String sanitizeTagValue(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    String normalized = trimmed.replace('=', '-').replace('|', '-');
    return normalized.replaceAll("\\s+", "_");
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

  private record DestinationInfo(String name, String code) {}
}
