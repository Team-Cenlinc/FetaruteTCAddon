package org.fetarute.fetaruteTCAddon.command;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildJob;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildJob.BuildMode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailGraphMultiSourceExplorerSession;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.integration.tcc.TccSelectionResolver;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.IntegerParser;

/**
 * 调度图诊断命令：/fta graph build|status|cancel|info。
 *
 * <p>图构建分为两个阶段：
 *
 * <ul>
 *   <li>discover_nodes：发现节点（扫描牌子；HERE 模式会沿轨道连通性扩展触达更多区块）
 *   <li>explore_edges：在轨道方块图上用多源 Dijkstra 计算节点之间区间距离
 * </ul>
 *
 * <p>重要约束：构建过程不会主动加载区块；未加载区块会被视为不可达，因此线上运维应先预加载线路区域再执行 build。
 *
 * <p>HERE 模式起点优先级：TCC 编辑器选中节点/轨道 → 附近节点牌子 → 玩家脚下附近轨道。
 */
public final class FtaGraphCommand {

  private final FetaruteTCAddon plugin;
  private final ConcurrentMap<UUID, RailGraphBuildJob> jobs = new ConcurrentHashMap<>();

  public FtaGraphCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  public void register(CommandManager<CommandSender> manager) {
    CommandFlag<Void> syncFlag = CommandFlag.builder("sync").build();
    var tickBudgetMsFlag =
        CommandFlag.<CommandSender>builder("tickBudgetMs")
            .withComponent(
                CommandComponent.<CommandSender, Integer>builder(
                        "tickBudgetMs", IntegerParser.integerParser())
                    .suggestionProvider(CommandSuggestionProviders.placeholder("<ms>")))
            .build();
    CommandFlag<Void> allFlag = CommandFlag.builder("all").build();
    CommandFlag<Void> hereFlag = CommandFlag.builder("here").build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .permission("fetarute.graph")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("build")
            .flag(syncFlag)
            .flag(tickBudgetMsFlag)
            .flag(allFlag)
            .flag(hereFlag)
            .permission("fetarute.graph.build")
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  UUID worldId = world.getUID();
                  if (jobs.containsKey(worldId)) {
                    sender.sendMessage(locale.component("command.graph.build.running"));
                    return;
                  }

                  boolean forceAll = ctx.flags().isPresent(allFlag);
                  boolean forceHere = ctx.flags().isPresent(hereFlag);
                  if (forceAll && forceHere) {
                    sender.sendMessage("参数冲突：不能同时指定 --all 与 --here");
                    return;
                  }
                  if (forceHere && !(sender instanceof Player)) {
                    sender.sendMessage("控制台无法使用 here 模式");
                    return;
                  }

                  BuildMode mode =
                      sender instanceof Player
                          ? (forceAll ? BuildMode.ALL : BuildMode.HERE)
                          : BuildMode.ALL;
                  if (forceHere && sender instanceof Player) {
                    mode = BuildMode.HERE;
                  } else if (forceAll) {
                    mode = BuildMode.ALL;
                  }

                  RailNodeRecord seedNode = null;
                  Set<RailBlockPos> seedRails = Set.of();
                  List<RailNodeRecord> preseedNodes = List.of();
                  if (mode == BuildMode.HERE) {
                    if (!(sender instanceof Player player)) {
                      sender.sendMessage("控制台无法使用 here 模式");
                      return;
                    }
                    TrainCartsRailBlockAccess railAccess = new TrainCartsRailBlockAccess(world);
                    Optional<TccSelectionResolver.TccCoasterSelection> tccSelectionOpt =
                        TccSelectionResolver.findSelectedCoaster(player);
                    if (tccSelectionOpt.isPresent()) {
                      TccSelectionResolver.TccCoasterSelection tccSelection = tccSelectionOpt.get();
                      seedRails = railAccess.findNearestRailBlocks(tccSelection.seedRail(), 2);
                      if (!tccSelection.coasterNodes().isEmpty()) {
                        UUID wid = world.getUID();
                        String worldName = world.getName();
                        preseedNodes =
                            tccSelection.coasterNodes().stream()
                                .map(
                                    pos ->
                                        new RailNodeRecord(
                                            wid,
                                            NodeId.of(
                                                "TCCNODE:"
                                                    + worldName
                                                    + ":"
                                                    + pos.x()
                                                    + ":"
                                                    + pos.y()
                                                    + ":"
                                                    + pos.z()),
                                            NodeType.WAYPOINT,
                                            pos.x(),
                                            pos.y(),
                                            pos.z(),
                                            Optional.empty(),
                                            Optional.empty()))
                                .toList();
                      }
                    }
                    Optional<RailNodeRecord> seedOpt = findNearbyNodeSign(player, 4);
                    if (seedRails.isEmpty() && seedOpt.isPresent()) {
                      seedNode = seedOpt.get();
                      seedRails =
                          railAccess.findNearestRailBlocks(
                              new RailBlockPos(seedNode.x(), seedNode.y(), seedNode.z()), 2);
                    } else if (seedRails.isEmpty()) {
                      seedRails =
                          railAccess.findNearestRailBlocks(
                              new RailBlockPos(
                                  player.getLocation().getBlockX(),
                                  player.getLocation().getBlockY(),
                                  player.getLocation().getBlockZ()),
                              2);
                    }
                    if (seedRails.isEmpty()) {
                      sender.sendMessage(locale.component("command.graph.build.no-start-node"));
                      return;
                    }
                  }

                  Integer tickBudgetMsValue = ctx.flags().getValue(tickBudgetMsFlag, 1);
                  int tickBudgetMs = tickBudgetMsValue != null ? tickBudgetMsValue : 1;
                  boolean sync = ctx.flags().isPresent(syncFlag);
                  if (sync) {
                    try {
                      long startNanos = System.nanoTime();
                      RailGraphBuildResult result =
                          buildSync(world, mode, seedNode, seedRails, preseedNodes);
                      onBuildSuccess(world, result);
                      long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                      sender.sendMessage(
                          locale.component(
                              "command.graph.build.success",
                              Map.of(
                                  "world",
                                  world.getName(),
                                  "nodes",
                                  String.valueOf(result.graph().nodes().size()),
                                  "edges",
                                  String.valueOf(result.graph().edges().size()),
                                  "took_ms",
                                  String.valueOf(tookMs))));
                      sendMissingSwitcherWarnings(sender, world, result);
                    } catch (IllegalStateException ex) {
                      sender.sendMessage(locale.component("command.graph.build.no-nodes"));
                    }
                    return;
                  }

                  long startNanos = System.nanoTime();
                  RailGraphBuildJob job =
                      new RailGraphBuildJob(
                          plugin,
                          world,
                          mode,
                          seedNode,
                          seedRails,
                          preseedNodes,
                          tickBudgetMs,
                          result -> {
                            onBuildSuccess(world, result);
                            long tookMs =
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                            plugin
                                .getServer()
                                .getScheduler()
                                .runTask(
                                    plugin,
                                    () -> {
                                      sender.sendMessage(
                                          locale.component(
                                              "command.graph.build.success",
                                              Map.of(
                                                  "world",
                                                  world.getName(),
                                                  "nodes",
                                                  String.valueOf(result.graph().nodes().size()),
                                                  "edges",
                                                  String.valueOf(result.graph().edges().size()),
                                                  "took_ms",
                                                  String.valueOf(tookMs))));
                                      sendMissingSwitcherWarnings(sender, world, result);
                                    });
                            jobs.remove(worldId);
                          },
                          ex -> {
                            plugin.getLogger().warning("调度图构建失败: " + ex.getMessage());
                            plugin
                                .getServer()
                                .getScheduler()
                                .runTask(
                                    plugin,
                                    () ->
                                        sender.sendMessage(
                                            locale.component(
                                                "command.graph.build.failed",
                                                Map.of(
                                                    "error",
                                                    ex.getMessage() != null
                                                        ? ex.getMessage()
                                                        : ""))));
                            jobs.remove(worldId);
                          },
                          plugin.getLoggerManager()::debug);
                  if (jobs.putIfAbsent(worldId, job) != null) {
                    sender.sendMessage(locale.component("command.graph.build.running"));
                    return;
                  }
                  job.start();
                  sender.sendMessage(locale.component("command.graph.build.started"));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("status")
            .permission("fetarute.graph.status")
            .handler(
                ctx -> {
                  World world = resolveWorld(ctx.sender());
                  if (world == null) {
                    ctx.sender().sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  RailGraphBuildJob job = jobs.get(world.getUID());
                  if (job == null) {
                    ctx.sender().sendMessage(locale.component("command.graph.status.idle"));
                    return;
                  }
                  job.getStatus()
                      .ifPresentOrElse(
                          status ->
                              ctx.sender()
                                  .sendMessage(
                                      locale.component(
                                          "command.graph.status.running",
                                          Map.of(
                                              "phase",
                                              status.phase(),
                                              "visited",
                                              String.valueOf(status.visitedRailBlocks()),
                                              "queue",
                                              String.valueOf(status.queueSize()),
                                              "processed",
                                              String.valueOf(status.processedSteps()),
                                              "nodes",
                                              String.valueOf(status.nodesFound())))),
                          () ->
                              ctx.sender()
                                  .sendMessage(locale.component("command.graph.status.idle")));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("cancel")
            .permission("fetarute.graph.cancel")
            .handler(
                ctx -> {
                  World world = resolveWorld(ctx.sender());
                  if (world == null) {
                    ctx.sender().sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  UUID worldId = world.getUID();
                  RailGraphBuildJob job = jobs.remove(worldId);
                  if (job == null) {
                    ctx.sender().sendMessage(locale.component("command.graph.cancel.none"));
                    return;
                  }
                  job.cancel();
                  ctx.sender().sendMessage(locale.component("command.graph.cancel.success"));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("info")
            .permission("fetarute.graph.info")
            .handler(
                ctx -> {
                  World world = resolveWorld(ctx.sender());
                  if (world == null) {
                    ctx.sender().sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  RailGraphService service = plugin.getRailGraphService();
                  RailGraph graph =
                      service
                          .getSnapshot(world)
                          .map(RailGraphService.RailGraphSnapshot::graph)
                          .orElse(null);
                  if (graph == null) {
                    ctx.sender().sendMessage(locale.component("command.graph.info.missing"));
                    return;
                  }
                  ctx.sender()
                      .sendMessage(
                          locale.component(
                              "command.graph.info.header",
                              Map.of(
                                  "world",
                                  world.getName(),
                                  "nodes",
                                  String.valueOf(graph.nodes().size()),
                                  "edges",
                                  String.valueOf(graph.edges().size()))));

                  List<RailEdge> top =
                      graph.edges().stream()
                          .sorted(Comparator.comparingInt(RailEdge::lengthBlocks).reversed())
                          .limit(10)
                          .toList();
                  for (RailEdge edge : top) {
                    ctx.sender()
                        .sendMessage(
                            locale.component(
                                "command.graph.info.edge",
                                Map.of(
                                    "a",
                                    edge.from().value(),
                                    "b",
                                    edge.to().value(),
                                    "len",
                                    String.valueOf(edge.lengthBlocks()))));
                  }
                }));
  }

  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.graph.help"));
  }

  /**
   * 同步构建调度图（仅用于调试/维护）。
   *
   * <p>注意：该方法会在主线程一次性跑完整个 discovery + explore，可能导致卡服；生产环境建议使用分段任务 {@link RailGraphBuildJob}。
   *
   * @param mode 构建模式
   * @param seedNode HERE 模式下的“节点牌子 seed”（可为 null）
   * @param seedRails HERE 模式下的“轨道锚点 seed”（不可为 null）
   * @param preseedNodes HERE 模式下可注入的预置节点列表（用于 TCC 无牌子线网）
   */
  private RailGraphBuildResult buildSync(
      World world,
      BuildMode mode,
      RailNodeRecord seedNode,
      Set<RailBlockPos> seedRails,
      List<RailNodeRecord> preseedNodes) {
    // 同步模式：主线程一次性跑完整个 BFS（可能卡服，仅用于维护/调试）。
    TrainCartsRailBlockAccess access = new TrainCartsRailBlockAccess(world);

    Map<String, RailNodeRecord> byNodeId = new HashMap<>();
    if (preseedNodes != null) {
      for (RailNodeRecord node : preseedNodes) {
        if (node == null) {
          continue;
        }
        byNodeId.putIfAbsent(node.nodeId().value(), node);
      }
    }
    List<RailNodeRecord> nodes;
    if (mode == BuildMode.HERE) {
      if (seedNode != null) {
        byNodeId.put(seedNode.nodeId().value(), seedNode);
      }
      Set<RailBlockPos> anchors = seedRails != null ? seedRails : Set.of();
      if (anchors.isEmpty() && seedNode != null) {
        anchors =
            access.findNearestRailBlocks(
                new RailBlockPos(seedNode.x(), seedNode.y(), seedNode.z()), 2);
      }
      if (anchors.isEmpty()) {
        throw new IllegalStateException("HERE 模式缺少起始轨道锚点");
      }
      var discovery =
          new org.fetarute.fetaruteTCAddon.dispatcher.graph.build.ConnectedRailNodeDiscoverySession(
              world, anchors, access, plugin.getLoggerManager()::debug);
      while (!discovery.isDone()) {
        discovery.step(System.nanoTime() + 1_000_000_000L, byNodeId);
      }
      var visitedRails = discovery.visitedRails();
      nodes = filterNodesInComponent(List.copyOf(byNodeId.values()), visitedRails, access);
    } else {
      for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
        if (chunk == null) {
          continue;
        }
        for (BlockState state : chunk.getTileEntities()) {
          if (!(state instanceof Sign sign)) {
            continue;
          }
          NodeSignDefinitionParser.parse(sign)
              .or(() -> SwitcherSignDefinitionParser.parse(sign))
              .ifPresent(
                  def -> {
                    int x = sign.getLocation().getBlockX();
                    int y = sign.getLocation().getBlockY();
                    int z = sign.getLocation().getBlockZ();
                    if (def.nodeType() == NodeType.SWITCHER) {
                      RailBlockPos railPos =
                          SwitcherSignDefinitionParser.tryParseRailPos(def.nodeId())
                              .orElse(new RailBlockPos(x, y, z));
                      x = railPos.x();
                      y = railPos.y();
                      z = railPos.z();
                    }
                    RailNodeRecord record =
                        new RailNodeRecord(
                            world.getUID(),
                            def.nodeId(),
                            def.nodeType(),
                            x,
                            y,
                            z,
                            def.trainCartsDestination(),
                            def.waypointMetadata());
                    RailNodeRecord existing = byNodeId.get(def.nodeId().value());
                    if (existing == null) {
                      byNodeId.put(def.nodeId().value(), record);
                      return;
                    }
                    if (existing.nodeType() == NodeType.SWITCHER
                        && def.nodeType() == NodeType.SWITCHER
                        && existing.trainCartsDestination().isEmpty()
                        && def.trainCartsDestination().isPresent()) {
                      byNodeId.put(def.nodeId().value(), record);
                    }
                  });
        }
      }
      nodes = List.copyOf(byNodeId.values());
    }

    Map<org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId, java.util.Set<RailBlockPos>>
        anchorsByNode = new HashMap<>();
    Set<RailBlockPos> railsWithSwitchers = new HashSet<>();
    for (RailNodeRecord node : nodes) {
      var anchors = access.findNearestRailBlocks(new RailBlockPos(node.x(), node.y(), node.z()), 2);
      if (!anchors.isEmpty()) {
        anchorsByNode.put(node.nodeId(), anchors);
      }
      if (node.nodeType() == NodeType.SWITCHER
          && node.trainCartsDestination()
              .filter(SwitcherSignDefinitionParser.SWITCHER_SIGN_MARKER::equals)
              .isPresent()) {
        addRailsAround(
            railsWithSwitchers, access, new RailBlockPos(node.x(), node.y(), node.z()), 2);
      }
    }

    Set<RailBlockPos> missingSwitchers = new HashSet<>();
    RailGraphMultiSourceExplorerSession session =
        new RailGraphMultiSourceExplorerSession(
            anchorsByNode,
            access,
            4096,
            junction -> {
              if (!railsWithSwitchers.contains(junction)) {
                missingSwitchers.add(junction);
              }
            });
    while (!session.isDone()) {
      session.step(50_000);
    }
    var edgeLengths = session.edgeLengths();

    Map<
            org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId,
            org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode>
        nodesById = new HashMap<>();
    for (RailNodeRecord node : nodes) {
      var railNode =
          new org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode(
              node.nodeId(),
              node.nodeType(),
              new org.bukkit.util.Vector(node.x(), node.y(), node.z()),
              node.trainCartsDestination(),
              node.waypointMetadata());
      nodesById.put(railNode.id(), railNode);
    }
    Map<
            org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId,
            org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge>
        edgesById = new HashMap<>();
    for (var entry : edgeLengths.entrySet()) {
      var edgeId = entry.getKey();
      int lengthBlocks = entry.getValue();
      var a = nodesById.get(edgeId.a());
      var b = nodesById.get(edgeId.b());
      if (a == null || b == null) {
        continue;
      }
      edgesById.put(
          edgeId,
          new org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge(
              edgeId,
              edgeId.a(),
              edgeId.b(),
              lengthBlocks,
              0.0,
              true,
              java.util.Optional.of(
                  new org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdgeMetadata(
                      a.waypointMetadata(), b.waypointMetadata()))));
    }
    var finalGraph =
        new org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph(
            nodesById, edgesById, java.util.Set.of());
    var builtAt = java.time.Instant.now();
    String signature =
        org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature.signatureForNodes(
            nodes);
    return new RailGraphBuildResult(
        finalGraph, builtAt, signature, nodes, List.copyOf(missingSwitchers));
  }

  private void onBuildSuccess(World world, RailGraphBuildResult result) {
    RailGraphService service = plugin.getRailGraphService();
    service.putSnapshot(world, result.graph(), result.builtAt());
    persistGraph(world, result);
  }

  private void persistGraph(World world, RailGraphBuildResult result) {
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      return;
    }
    Optional<StorageProvider> providerOpt = plugin.getStorageManager().provider();
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    java.time.Instant builtAt = result.builtAt();
    java.util.UUID worldId = world.getUID();

    List<RailEdgeRecord> edges =
        result.graph().edges().stream()
            .map(
                edge ->
                    new RailEdgeRecord(
                        worldId,
                        edge.id(),
                        edge.lengthBlocks(),
                        edge.baseSpeedLimit(),
                        edge.bidirectional()))
            .toList();
    List<RailNodeRecord> nodes = result.nodes();

    try {
      provider
          .transactionManager()
          .execute(
              () -> {
                provider.railNodes().replaceWorld(worldId, nodes);
                provider.railEdges().replaceWorld(worldId, edges);
                provider
                    .railGraphSnapshots()
                    .save(
                        new RailGraphSnapshotRecord(
                            worldId, builtAt, nodes.size(), edges.size(), result.nodeSignature()));
                return null;
              });
    } catch (Exception ex) {
      plugin.getLogger().warning("持久化调度图失败: " + ex.getMessage());
    }
  }

  private World resolveWorld(CommandSender sender) {
    if (sender instanceof Player player) {
      return player.getWorld();
    }
    List<World> worlds = plugin.getServer().getWorlds();
    return worlds.isEmpty() ? null : worlds.get(0);
  }

  private Optional<RailNodeRecord> findNearbyNodeSign(Player player, int radius) {
    Objects.requireNonNull(player, "player");
    if (radius < 0) {
      throw new IllegalArgumentException("radius 不能为负");
    }
    World world = player.getWorld();
    UUID worldId = world.getUID();
    int baseX = player.getLocation().getBlockX();
    int baseY = player.getLocation().getBlockY();
    int baseZ = player.getLocation().getBlockZ();

    RailNodeRecord best = null;
    int bestDist = Integer.MAX_VALUE;

    for (int dy = -2; dy <= 2; dy++) {
      int y = baseY + dy;
      for (int dx = -radius; dx <= radius; dx++) {
        int x = baseX + dx;
        for (int dz = -radius; dz <= radius; dz++) {
          int z = baseZ + dz;
          if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            continue;
          }
          BlockState state = world.getBlockAt(x, y, z).getState();
          if (!(state instanceof Sign sign)) {
            continue;
          }
          Optional<org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition> defOpt =
              NodeSignDefinitionParser.parse(sign);
          if (defOpt.isEmpty()) {
            continue;
          }
          var def = defOpt.get();
          int dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
          if (dist < bestDist) {
            bestDist = dist;
            best =
                new RailNodeRecord(
                    worldId,
                    def.nodeId(),
                    def.nodeType(),
                    sign.getLocation().getBlockX(),
                    sign.getLocation().getBlockY(),
                    sign.getLocation().getBlockZ(),
                    def.trainCartsDestination(),
                    def.waypointMetadata());
          }
        }
      }
    }

    return Optional.ofNullable(best);
  }

  private static List<RailNodeRecord> filterNodesInComponent(
      List<RailNodeRecord> discovered,
      Set<RailBlockPos> visitedRails,
      TrainCartsRailBlockAccess access) {
    List<RailNodeRecord> filtered = new java.util.ArrayList<>();
    for (RailNodeRecord node : discovered) {
      var anchors = access.findNearestRailBlocks(new RailBlockPos(node.x(), node.y(), node.z()), 2);
      if (anchors.isEmpty()) {
        continue;
      }
      if (anchors.stream().anyMatch(visitedRails::contains)) {
        filtered.add(node);
      }
    }
    return List.copyOf(filtered);
  }

  /**
   * 在构建完成后输出“道岔附近缺少 switcher 牌子”的运维提示。
   *
   * <p>该提示仅影响诊断与运维流程，不影响图本身的可用性：
   *
   * <ul>
   *   <li>在 TrainCarts 线网中，建议为关键分叉放置 switcher/tag 牌子，便于运维封锁与行为对齐
   *   <li>在 TCC 线网中通常不依赖牌子，本插件会生成自动 switcher 节点；因此若没有牌子也可能出现提示，可按需忽略
   * </ul>
   */
  private void sendMissingSwitcherWarnings(
      CommandSender sender, World world, RailGraphBuildResult result) {
    if (result == null || result.missingSwitcherJunctions().isEmpty()) {
      return;
    }

    LocaleManager locale = plugin.getLocaleManager();
    int count = result.missingSwitcherJunctions().size();
    sender.sendMessage(
        locale.component(
            "command.graph.build.missing-switcher.header",
            Map.of("count", String.valueOf(count), "world", world.getName())));

    int limit = Math.min(10, count);
    for (int i = 0; i < limit; i++) {
      RailBlockPos pos = result.missingSwitcherJunctions().get(i);
      String tp = "/tp " + pos.x() + " " + pos.y() + " " + pos.z();

      net.kyori.adventure.text.Component location =
          net.kyori.adventure.text.Component.text(pos.x() + " " + pos.y() + " " + pos.z());
      if (sender instanceof Player) {
        location =
            location
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(tp))
                .hoverEvent(
                    net.kyori.adventure.text.event.HoverEvent.showText(
                        net.kyori.adventure.text.Component.text(tp)));
      }

      sender.sendMessage(
          locale.component(
              "command.graph.build.missing-switcher.entry",
              net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.builder()
                  .resolver(
                      net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(
                          "location", location))
                  .build()));
    }

    if (count > limit) {
      sender.sendMessage(
          locale.component(
              "command.graph.build.missing-switcher.more",
              Map.of("more", String.valueOf(count - limit))));
    }
  }

  private static void addRailsAround(
      Set<RailBlockPos> out, TrainCartsRailBlockAccess access, RailBlockPos center, int radius) {
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          RailBlockPos candidate = center.offset(dx, dy, dz);
          if (access.isRail(candidate)) {
            out.add(candidate);
          }
        }
      }
    }
  }
}
