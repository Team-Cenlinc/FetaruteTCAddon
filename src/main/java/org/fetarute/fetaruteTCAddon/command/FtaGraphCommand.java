package org.fetarute.fetaruteTCAddon.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphMerger;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.ChunkLoadOptions;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildCompletion;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildContinuation;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildJob;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildJob.BuildMode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildOutcome;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailGraphMultiSourceExplorerSession;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.integration.tcc.TccSelectionResolver;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;

/**
 * 调度图诊断与运维命令：/fta graph build|continue|status|cancel|info|delete。
 *
 * <p>图构建分为两个阶段：
 *
 * <ul>
 *   <li>discover_nodes：发现节点（扫描牌子；HERE 模式会沿轨道连通性扩展触达更多区块）
 *   <li>explore_edges：在轨道方块图上用多源 Dijkstra 计算节点之间区间距离
 * </ul>
 *
 * <p>重要约束：默认不会主动加载区块；未加载区块会被视为不可达，因此线上运维应先预加载线路区域再执行 build。
 *
 * <p>如需“沿轨道自动加载区块”，可使用 {@code --loadChunks}（仅 HERE 支持；建议配合 {@code --maxChunks} 控制加载范围）。
 *
 * <p>续跑（continue）：当 HERE + {@code --loadChunks} 达到 {@code maxChunks} 限制时，build 会进入“暂停”并缓存 {@link
 * RailGraphBuildContinuation}，可用 {@code /fta graph continue} 继续扩张。该缓存仅在内存中保存，服务器重启/插件重载后会失效。
 *
 * <p>清理（delete）：{@code /fta graph delete} 会清空内存快照与持久化快照（SQL），并移除该世界的续跑缓存； {@code /fta graph delete
 * here} 则只删除玩家附近所在的连通分量，便于局部重建。
 *
 * <p>HERE 模式起点优先级：
 *
 * <ul>
 *   <li>若指定 {@code --tcc}：TCC 编辑器选中节点/轨道
 *   <li>否则：附近节点牌子 → 玩家脚下附近轨道
 * </ul>
 */
public final class FtaGraphCommand {

  private final FetaruteTCAddon plugin;

  /** 世界维度的构建任务：同一世界同一时间只允许一个 build/continue 任务运行。 */
  private final ConcurrentMap<UUID, RailGraphBuildJob> jobs = new ConcurrentHashMap<>();

  /**
   * build 续跑缓存：仅用于 HERE 模式的“按轨道扩张”。
   *
   * <p>注意：该缓存只存在内存中，重启/重载后会丢失；因此命令层会在 build 完成/清理时主动移除，避免误用旧状态。
   */
  private final ConcurrentMap<GraphBuildCacheKey, RailGraphBuildContinuation> continuations =
      new ConcurrentHashMap<>();

  public FtaGraphCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  /**
   * 续跑缓存的 key：按世界 + 发起者隔离。
   *
   * <p>原因：HERE 模式依赖玩家位置，且多个玩家可能并行在不同区域进行 build；若只按 world 维度缓存 continuation，会导致玩家之间互相覆盖。
   *
   * <p>控制台/非玩家统一视为“匿名发起者”（ownerId 为空）。
   */
  private record GraphBuildCacheKey(UUID worldId, Optional<UUID> ownerId) {
    private GraphBuildCacheKey {
      Objects.requireNonNull(worldId, "worldId");
      ownerId = ownerId != null ? ownerId : Optional.empty();
    }
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
    CommandFlag<Void> loadChunksFlag = CommandFlag.builder("loadChunks").build();
    var maxChunksFlag =
        CommandFlag.<CommandSender>builder("maxChunks")
            .withComponent(
                CommandComponent.<CommandSender, Integer>builder(
                        "maxChunks", IntegerParser.integerParser())
                    .suggestionProvider(CommandSuggestionProviders.placeholder("<n>")))
            .build();
    var maxConcurrentLoadsFlag =
        CommandFlag.<CommandSender>builder("maxConcurrentLoads")
            .withComponent(
                CommandComponent.<CommandSender, Integer>builder(
                        "maxConcurrentLoads", IntegerParser.integerParser())
                    .suggestionProvider(CommandSuggestionProviders.placeholder("<n>")))
            .build();
    CommandFlag<Void> allFlag = CommandFlag.builder("all").build();
    CommandFlag<Void> hereFlag = CommandFlag.builder("here").build();
    CommandFlag<Void> tccFlag = CommandFlag.builder("tcc").build();

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
            .flag(loadChunksFlag)
            .flag(maxChunksFlag)
            .flag(maxConcurrentLoadsFlag)
            .flag(allFlag)
            .flag(hereFlag)
            .flag(tccFlag)
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

                  boolean useTcc = ctx.flags().isPresent(tccFlag);
                  if (useTcc && !(sender instanceof Player)) {
                    sender.sendMessage(locale.component("command.graph.build.tcc-player-only"));
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

                    if (useTcc) {
                      Optional<RailBlockPos> tccSeedOpt =
                          TccSelectionResolver.findSelectedRailBlock(player);
                      if (tccSeedOpt.isEmpty()) {
                        sender.sendMessage(
                            locale.component("command.graph.build.no-tcc-selection"));
                        return;
                      }
                      RailBlockPos selected = tccSeedOpt.get();
                      // TCC 选中位置并不一定等价于“可遍历的轨道方块锚点”（例如某些自定义轨道会把实际 rail piece
                      // 映射到相邻方块）。因此先尝试精确匹配，失败时再做小半径兜底映射，避免 --tcc 莫名无法起步。
                      seedRails = railAccess.findNearestRailBlocks(selected, 0);
                      if (seedRails.isEmpty()) {
                        seedRails = railAccess.findNearestRailBlocks(selected, 2);
                      }
                      if (seedRails.isEmpty()) {
                        sender.sendMessage(
                            locale.component("command.graph.build.no-tcc-selection"));
                        return;
                      }
                    }
                    Optional<RailNodeRecord> seedOpt = findNearbyNodeSign(player, 4);
                    if (!useTcc && seedRails.isEmpty() && seedOpt.isPresent()) {
                      seedNode = seedOpt.get();
                      seedRails =
                          railAccess.findNearestRailBlocks(
                              new RailBlockPos(seedNode.x(), seedNode.y(), seedNode.z()), 2);
                    } else if (!useTcc && seedRails.isEmpty()) {
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

                  boolean loadChunks = ctx.flags().isPresent(loadChunksFlag);
                  if (loadChunks && mode != BuildMode.HERE) {
                    sender.sendMessage(
                        locale.component("command.graph.build.load-chunks-here-only"));
                    return;
                  }
                  Integer maxChunksValue = ctx.flags().getValue(maxChunksFlag, 256);
                  int maxChunks = maxChunksValue != null ? maxChunksValue : 256;
                  Integer maxConcurrentLoadsValue = ctx.flags().getValue(maxConcurrentLoadsFlag, 4);
                  int maxConcurrentLoads =
                      maxConcurrentLoadsValue != null ? maxConcurrentLoadsValue : 4;
                  ChunkLoadOptions chunkLoadOptions =
                      loadChunks
                          ? new ChunkLoadOptions(true, maxChunks, maxConcurrentLoads)
                          : ChunkLoadOptions.disabled();
                  GraphBuildCacheKey cacheKey = cacheKey(worldId, sender);

                  boolean sync = ctx.flags().isPresent(syncFlag);
                  if (sync) {
                    try {
                      long startNanos = System.nanoTime();
                      RailGraphBuildResult result =
                          buildSync(world, mode, seedNode, seedRails, preseedNodes);
                      RailGraphBuildOutcome outcome =
                          new RailGraphBuildOutcome(
                              result,
                              RailGraphBuildCompletion.PARTIAL_UNLOADED_CHUNKS,
                              Optional.empty());
                      AppliedGraphBuild applied =
                          applyBuildSuccess(world, outcome.result(), outcome.completion());
                      long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                      sender.sendMessage(
                          locale.component(
                              "command.graph.build.success",
                              Map.of(
                                  "world",
                                  world.getName(),
                                  "nodes",
                                  String.valueOf(applied.result().graph().nodes().size()),
                                  "edges",
                                  String.valueOf(applied.result().graph().edges().size()),
                                  "took_ms",
                                  String.valueOf(tookMs))));
                      applied
                          .merge()
                          .ifPresent(merge -> sender.sendMessage(mergeBuildMessage(locale, merge)));
                      sendMissingSwitcherWarnings(sender, world, applied.result());
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
                          chunkLoadOptions,
                          outcome -> {
                            AppliedGraphBuild applied =
                                applyBuildSuccess(world, outcome.result(), outcome.completion());
                            outcome
                                .continuation()
                                .ifPresentOrElse(
                                    cont -> continuations.put(cacheKey, cont),
                                    () -> continuations.remove(cacheKey));
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
                                                  String.valueOf(
                                                      applied.result().graph().nodes().size()),
                                                  "edges",
                                                  String.valueOf(
                                                      applied.result().graph().edges().size()),
                                                  "took_ms",
                                                  String.valueOf(tookMs))));
                                      applied
                                          .merge()
                                          .ifPresent(
                                              merge ->
                                                  sender.sendMessage(
                                                      mergeBuildMessage(locale, merge)));
                                      outcome
                                          .continuation()
                                          .ifPresent(
                                              cont ->
                                                  sender.sendMessage(
                                                      locale.component(
                                                          "command.graph.build.paused",
                                                          Map.of(
                                                              "pending_chunks",
                                                              String.valueOf(
                                                                  cont.discoverySession()
                                                                      .pendingChunksToLoad())))));
                                      sendMissingSwitcherWarnings(sender, world, applied.result());
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
                                    () -> {
                                      if (ex instanceof IllegalStateException) {
                                        sender.sendMessage(
                                            locale.component("command.graph.build.no-nodes"));
                                        return;
                                      }
                                      sender.sendMessage(
                                          locale.component(
                                              "command.graph.build.failed",
                                              Map.of(
                                                  "error",
                                                  ex.getMessage() != null ? ex.getMessage() : "")));
                                    });
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
            .literal("continue")
            .flag(tickBudgetMsFlag)
            .flag(maxChunksFlag)
            .flag(maxConcurrentLoadsFlag)
            .permission("fetarute.graph.continue")
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

                  GraphBuildCacheKey cacheKey = cacheKey(worldId, sender);
                  RailGraphBuildContinuation continuation = continuations.get(cacheKey);
                  if (continuation == null) {
                    sender.sendMessage(locale.component("command.graph.continue.none"));
                    return;
                  }

                  Integer tickBudgetMsValue = ctx.flags().getValue(tickBudgetMsFlag, 1);
                  int tickBudgetMs = tickBudgetMsValue != null ? tickBudgetMsValue : 1;
                  Integer maxChunksValue = ctx.flags().getValue(maxChunksFlag, 256);
                  int maxChunks = maxChunksValue != null ? maxChunksValue : 256;
                  Integer maxConcurrentLoadsValue = ctx.flags().getValue(maxConcurrentLoadsFlag, 4);
                  int maxConcurrentLoads =
                      maxConcurrentLoadsValue != null ? maxConcurrentLoadsValue : 4;

                  ChunkLoadOptions chunkLoadOptions =
                      new ChunkLoadOptions(true, maxChunks, maxConcurrentLoads);

                  long startNanos = System.nanoTime();
                  RailGraphBuildJob job =
                      new RailGraphBuildJob(
                          plugin,
                          world,
                          continuation,
                          tickBudgetMs,
                          chunkLoadOptions,
                          outcome -> {
                            AppliedGraphBuild applied =
                                applyBuildSuccess(world, outcome.result(), outcome.completion());
                            outcome
                                .continuation()
                                .ifPresentOrElse(
                                    cont -> continuations.put(cacheKey, cont),
                                    () -> continuations.remove(cacheKey));
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
                                                  String.valueOf(
                                                      applied.result().graph().nodes().size()),
                                                  "edges",
                                                  String.valueOf(
                                                      applied.result().graph().edges().size()),
                                                  "took_ms",
                                                  String.valueOf(tookMs))));
                                      applied
                                          .merge()
                                          .ifPresent(
                                              merge ->
                                                  sender.sendMessage(
                                                      mergeBuildMessage(locale, merge)));
                                      outcome
                                          .continuation()
                                          .ifPresent(
                                              cont ->
                                                  sender.sendMessage(
                                                      locale.component(
                                                          "command.graph.build.paused",
                                                          Map.of(
                                                              "pending_chunks",
                                                              String.valueOf(
                                                                  cont.discoverySession()
                                                                      .pendingChunksToLoad())))));
                                      sendMissingSwitcherWarnings(sender, world, applied.result());
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
                                    () -> {
                                      if (ex instanceof IllegalStateException) {
                                        sender.sendMessage(
                                            locale.component("command.graph.build.no-nodes"));
                                        return;
                                      }
                                      sender.sendMessage(
                                          locale.component(
                                              "command.graph.build.failed",
                                              Map.of(
                                                  "error",
                                                  ex.getMessage() != null ? ex.getMessage() : "")));
                                    });
                            jobs.remove(worldId);
                          },
                          plugin.getLoggerManager()::debug);
                  if (jobs.putIfAbsent(worldId, job) != null) {
                    sender.sendMessage(locale.component("command.graph.build.running"));
                    return;
                  }
                  job.start();
                  sender.sendMessage(locale.component("command.graph.continue.started"));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("sign")
            .literal("waypoint")
            .senderType(Player.class)
            .permission("fetarute.graph.sign")
            .required(
                "id",
                StringParser.greedyStringParser(),
                CommandSuggestionProviders.placeholder("<nodeId>"))
            .handler(
                ctx -> {
                  Player player = (Player) ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();

                  String rawId = ctx.get("id");
                  Optional<SignNodeDefinition> defOpt =
                      SignTextParser.parseWaypointLike(rawId, NodeType.WAYPOINT)
                          .filter(
                              definition ->
                                  definition
                                      .waypointMetadata()
                                      .map(
                                          metadata ->
                                              metadata.kind()
                                                      == org.fetarute
                                                          .fetaruteTCAddon
                                                          .dispatcher
                                                          .node
                                                          .WaypointKind
                                                          .INTERVAL
                                                  || metadata.kind()
                                                      == org.fetarute
                                                          .fetaruteTCAddon
                                                          .dispatcher
                                                          .node
                                                          .WaypointKind
                                                          .STATION_THROAT
                                                  || metadata.kind()
                                                      == org.fetarute
                                                          .fetaruteTCAddon
                                                          .dispatcher
                                                          .node
                                                          .WaypointKind
                                                          .DEPOT_THROAT)
                                      .orElse(false));
                  if (defOpt.isEmpty()) {
                    player.sendMessage(
                        locale.component(
                            "command.graph.sign.waypoint.invalid-id", Map.of("id", rawId)));
                    return;
                  }

                  org.bukkit.block.Block target = player.getTargetBlockExact(5);
                  if (target == null) {
                    player.sendMessage(locale.component("command.graph.sign.no-target"));
                    return;
                  }
                  org.bukkit.block.BlockState state = target.getState();
                  if (!(state instanceof Sign sign)) {
                    player.sendMessage(locale.component("command.graph.sign.not-a-sign"));
                    return;
                  }

                  var side = sign.getSide(Side.FRONT);
                  side.line(0, Component.text("[train]"));
                  side.line(1, Component.text("waypoint"));
                  side.line(2, Component.text(defOpt.get().nodeId().value()));
                  side.line(3, Component.empty());
                  sign.update(true, false);

                  player.sendMessage(
                      locale.component("command.graph.sign.waypoint.success", Map.of("id", rawId)));
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
            .literal("delete")
            .permission("fetarute.graph.delete")
            .handler(
                ctx -> {
                  World world = resolveWorld(ctx.sender());
                  if (world == null) {
                    ctx.sender().sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  UUID worldId = world.getUID();
                  if (jobs.containsKey(worldId)) {
                    ctx.sender().sendMessage(locale.component("command.graph.build.running"));
                    return;
                  }

                  boolean hadSnapshot = plugin.getRailGraphService().getSnapshot(world).isPresent();
                  boolean deletedFromStorage = deleteGraphFromStorage(world);
                  plugin.getRailGraphService().clearSnapshot(world);
                  continuations.keySet().removeIf(key -> worldId.equals(key.worldId()));

                  if (!hadSnapshot && !deletedFromStorage) {
                    ctx.sender().sendMessage(locale.component("command.graph.delete.none"));
                    return;
                  }
                  ctx.sender()
                      .sendMessage(
                          locale.component(
                              "command.graph.delete.success", Map.of("world", world.getName())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("delete")
            .literal("here")
            .senderType(Player.class)
            .permission("fetarute.graph.delete")
            .handler(
                ctx -> {
                  Player player = (Player) ctx.sender();
                  World world = player.getWorld();
                  LocaleManager locale = plugin.getLocaleManager();
                  UUID worldId = world.getUID();
                  if (jobs.containsKey(worldId)) {
                    player.sendMessage(locale.component("command.graph.build.running"));
                    return;
                  }

                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      plugin.getRailGraphService().getSnapshot(world);
                  if (snapshotOpt.isEmpty()) {
                    plugin
                        .getRailGraphService()
                        .getStaleState(world)
                        .ifPresentOrElse(
                            stale -> player.sendMessage(staleInfoMessage(locale, stale)),
                            () ->
                                player.sendMessage(locale.component("command.graph.info.missing")));
                    return;
                  }
                  RailGraphService.RailGraphSnapshot snapshot = snapshotOpt.get();
                  RailGraph graph = snapshot.graph();

                  Optional<RailNode> seedOpt =
                      findNearestGraphNode(
                          graph,
                          new Vector(
                              player.getLocation().getBlockX(),
                              player.getLocation().getBlockY(),
                              player.getLocation().getBlockZ()),
                          32);
                  if (seedOpt.isEmpty()) {
                    player.sendMessage(locale.component("command.graph.delete.here.no-seed"));
                    return;
                  }
                  NodeId seed = seedOpt.get().id();

                  RailGraphMerger.RemoveResult removed =
                      RailGraphMerger.removeComponents(graph, Set.of(seed));
                  RailGraph nextGraph = removed.graph();
                  if (removed.totalNodes() <= 0) {
                    deleteGraphFromStorage(world);
                    plugin.getRailGraphService().clearSnapshot(world);
                    continuations.keySet().removeIf(key -> worldId.equals(key.worldId()));
                  } else {
                    java.time.Instant now = java.time.Instant.now();
                    plugin.getRailGraphService().putSnapshot(world, nextGraph, now);
                    List<RailNodeRecord> nodes = nodeRecordsFromGraph(worldId, nextGraph);
                    String signature = RailGraphSignature.signatureForNodes(nodes);
                    persistGraph(
                        world,
                        new RailGraphBuildResult(nextGraph, now, signature, nodes, List.of()));
                  }

                  player.sendMessage(
                      locale.component(
                          "command.graph.delete.here.success",
                          Map.of(
                              "seed",
                              seed.value(),
                              "removed_components",
                              String.valueOf(removed.removedComponentCount()),
                              "removed_nodes",
                              String.valueOf(removed.removedNodes()),
                              "removed_edges",
                              String.valueOf(removed.removedEdges()),
                              "total_nodes",
                              String.valueOf(removed.totalNodes()),
                              "total_edges",
                              String.valueOf(removed.totalEdges()))));
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
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      service.getSnapshot(world);
                  if (snapshotOpt.isEmpty()) {
                    service
                        .getStaleState(world)
                        .ifPresentOrElse(
                            stale -> ctx.sender().sendMessage(staleInfoMessage(locale, stale)),
                            () ->
                                ctx.sender()
                                    .sendMessage(locale.component("command.graph.info.missing")));
                    return;
                  }
                  RailGraphService.RailGraphSnapshot snapshot = snapshotOpt.get();
                  RailGraph graph = snapshot.graph();

                  int blockedEdges = 0;
                  for (RailEdge edge : graph.edges()) {
                    if (edge == null) {
                      continue;
                    }
                    if (graph.isBlocked(edge.id())) {
                      blockedEdges++;
                    }
                  }

                  int waypointNodes = 0;
                  int stationNodes = 0;
                  int depotNodes = 0;
                  int destinationNodes = 0;
                  int switcherNodes = 0;
                  int switcherSignNodes = 0;
                  int switcherAutoNodes = 0;
                  int isolatedNodes = 0;
                  for (RailNode node : graph.nodes()) {
                    if (node == null) {
                      continue;
                    }
                    NodeType type = node.type();
                    if (type == null) {
                      continue;
                    }
                    switch (type) {
                      case WAYPOINT -> waypointNodes++;
                      case STATION -> stationNodes++;
                      case DEPOT -> depotNodes++;
                      case DESTINATION -> destinationNodes++;
                      case SWITCHER -> {
                        switcherNodes++;
                        // Switcher 节点不用于 TC destination，因此复用 trainCartsDestination 字段存放内部 marker，
                        // 用于区分“来自 switcher 牌子”与“自动分叉生成”（见 SwitcherSignDefinitionParser）。
                        if (node.trainCartsDestination()
                            .filter(SwitcherSignDefinitionParser.SWITCHER_SIGN_MARKER::equals)
                            .isPresent()) {
                          switcherSignNodes++;
                        } else {
                          switcherAutoNodes++;
                        }
                      }
                    }
                    if (graph.edgesFrom(node.id()).isEmpty()) {
                      isolatedNodes++;
                    }
                  }

                  int componentCount = 0;
                  Set<NodeId> visited = new HashSet<>();
                  ArrayDeque<NodeId> queue = new ArrayDeque<>();
                  for (RailNode node : graph.nodes()) {
                    if (node == null) {
                      continue;
                    }
                    NodeId nodeId = node.id();
                    if (nodeId == null || !visited.add(nodeId)) {
                      continue;
                    }
                    componentCount++;
                    queue.add(nodeId);
                    while (!queue.isEmpty()) {
                      NodeId current = queue.poll();
                      for (RailEdge edge : graph.edgesFrom(current)) {
                        if (edge == null) {
                          continue;
                        }
                        NodeId neighbor = current.equals(edge.from()) ? edge.to() : edge.from();
                        if (neighbor != null && visited.add(neighbor)) {
                          queue.add(neighbor);
                        }
                      }
                    }
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
                  ctx.sender()
                      .sendMessage(
                          locale.component(
                              "command.graph.info.meta",
                              Map.of(
                                  "built_at",
                                  snapshot.builtAt().toString(),
                                  "components",
                                  String.valueOf(componentCount),
                                  "blocked_edges",
                                  String.valueOf(blockedEdges),
                                  "isolated_nodes",
                                  String.valueOf(isolatedNodes))));
                  ctx.sender()
                      .sendMessage(
                          locale.component(
                              "command.graph.info.nodes",
                              Map.of(
                                  "waypoint",
                                  String.valueOf(waypointNodes),
                                  "station",
                                  String.valueOf(stationNodes),
                                  "depot",
                                  String.valueOf(depotNodes),
                                  "destination",
                                  String.valueOf(destinationNodes),
                                  "switcher",
                                  String.valueOf(switcherNodes))));
                  ctx.sender()
                      .sendMessage(
                          locale.component(
                              "command.graph.info.switcher",
                              Map.of(
                                  "switcher_sign",
                                  String.valueOf(switcherSignNodes),
                                  "switcher_auto",
                                  String.valueOf(switcherAutoNodes))));

                  List<RailEdge> top =
                      graph.edges().stream()
                          .sorted(Comparator.comparingInt(RailEdge::lengthBlocks).reversed())
                          .limit(10)
                          .toList();
                  if (!top.isEmpty()) {
                    ctx.sender()
                        .sendMessage(locale.component("command.graph.info.edge-top-header"));
                  }
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

  private static Component staleInfoMessage(
      LocaleManager locale, RailGraphService.RailGraphStaleState stale) {
    Objects.requireNonNull(locale, "locale");
    Objects.requireNonNull(stale, "stale");
    return locale.component(
        "command.graph.info.stale",
        Map.of(
            "built_at",
            stale.builtAt().toString(),
            "snapshot_sig",
            shortSignature(stale.snapshotSignature()),
            "current_sig",
            shortSignature(stale.currentSignature()),
            "snapshot_nodes",
            String.valueOf(stale.snapshotNodeCount()),
            "snapshot_edges",
            String.valueOf(stale.snapshotEdgeCount()),
            "current_nodes",
            String.valueOf(stale.currentNodeCount())));
  }

  private static String shortSignature(String signature) {
    if (signature == null || signature.isEmpty()) {
      return "";
    }
    if (signature.length() <= 12) {
      return signature;
    }
    return signature.substring(0, 12);
  }

  private GraphBuildCacheKey cacheKey(UUID worldId, CommandSender sender) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(sender, "sender");
    Optional<UUID> ownerId =
        sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty();
    return new GraphBuildCacheKey(worldId, ownerId);
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
              world,
              anchors,
              access,
              plugin.getLoggerManager()::debug,
              ChunkLoadOptions.disabled());
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

    if (nodes.isEmpty() || nodes.stream().noneMatch(FtaGraphCommand::isSignalNode)) {
      throw new IllegalStateException("未扫描到任何本插件节点牌子");
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

  /**
   * 将一次 build 的结果写入内存快照，并根据“完整性”选择合并策略后落库。
   *
   * <p>当 {@link RailGraphBuildCompletion#canReplaceComponents()} 为 true 时，可认为本次 build 尽可能覆盖了相关连通分量，
   * 因此允许执行“替换连通分量”（删除旧分量再写入新分量）。
   *
   * <p>当 build 结果可能缺失（未加载区块/达到 maxChunks/区块加载失败）时，使用 upsert 以避免误删旧图数据。
   *
   * @return 最终写入的图结果，以及可选的 merge 统计（用于命令回显）
   */
  private AppliedGraphBuild applyBuildSuccess(
      World world, RailGraphBuildResult result, RailGraphBuildCompletion completion) {
    RailGraphService service = plugin.getRailGraphService();

    Optional<RailGraph> baseGraph =
        service.getSnapshot(world).map(RailGraphService.RailGraphSnapshot::graph);
    if (baseGraph.isEmpty()) {
      baseGraph = loadGraphFromStorage(world);
    }

    if (baseGraph.isPresent()) {
      RailGraphMerger.MergeResult merge =
          completion != null && completion.canReplaceComponents()
              ? RailGraphMerger.appendOrReplaceComponents(baseGraph.get(), result.graph())
              : RailGraphMerger.upsert(baseGraph.get(), result.graph());
      List<RailNodeRecord> mergedNodes = nodeRecordsFromGraph(world.getUID(), merge.graph());
      String signature = RailGraphSignature.signatureForNodes(mergedNodes);
      RailGraphBuildResult merged =
          new RailGraphBuildResult(
              merge.graph(),
              result.builtAt(),
              signature,
              mergedNodes,
              result.missingSwitcherJunctions());
      service.putSnapshot(world, merged.graph(), merged.builtAt());
      persistGraph(world, merged);
      return new AppliedGraphBuild(merged, Optional.of(merge));
    }

    service.putSnapshot(world, result.graph(), result.builtAt());
    persistGraph(world, result);
    return new AppliedGraphBuild(result, Optional.empty());
  }

  /**
   * 尝试从存储加载旧图作为 merge base。
   *
   * <p>用途：当图处于 stale 状态时，内存快照会被清空；若此时执行 HERE build 并直接 replace，会把其他连通分量误删。 因此这里在需要时从 SQL
   * 读取上一版快照（即使签名不一致）用于合并。
   */
  private Optional<RailGraph> loadGraphFromStorage(World world) {
    Objects.requireNonNull(world, "world");
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      return Optional.empty();
    }
    Optional<StorageProvider> providerOpt = plugin.getStorageManager().provider();
    if (providerOpt.isEmpty()) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    UUID worldId = world.getUID();
    try {
      List<RailNodeRecord> nodes = provider.railNodes().listByWorld(worldId);
      List<RailEdgeRecord> edges = provider.railEdges().listByWorld(worldId);
      if (nodes.isEmpty() && edges.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(RailGraphService.buildGraphFromRecords(nodes, edges));
    } catch (Exception ex) {
      plugin
          .getLogger()
          .warning("从存储加载调度图失败: world=" + world.getName() + " msg=" + ex.getMessage());
      return Optional.empty();
    }
  }

  private static Component mergeBuildMessage(
      LocaleManager locale, RailGraphMerger.MergeResult merge) {
    String action =
        switch (merge.action()) {
          case APPEND -> "append";
          case UPSERT -> "upsert";
          case REPLACE_COMPONENTS -> "replace";
        };
    return locale.component(
        "command.graph.build.merged",
        Map.of(
            "action",
            action,
            "replaced_components",
            String.valueOf(merge.replacedComponentCount()),
            "removed_nodes",
            String.valueOf(merge.removedNodes()),
            "removed_edges",
            String.valueOf(merge.removedEdges()),
            "total_nodes",
            String.valueOf(merge.totalNodes()),
            "total_edges",
            String.valueOf(merge.totalEdges())));
  }

  /**
   * 将内存图转换为可持久化的节点记录列表。
   *
   * <p>注意：这里会按 nodeId 排序，确保签名计算/持久化输出稳定（deterministic），避免因迭代顺序不稳定导致的“重复快照”。
   */
  private static List<RailNodeRecord> nodeRecordsFromGraph(UUID worldId, RailGraph graph) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(graph, "graph");
    List<RailNodeRecord> records = new ArrayList<>();
    for (RailNode node : graph.nodes()) {
      if (node == null) {
        continue;
      }
      Vector pos = node.worldPosition();
      records.add(
          new RailNodeRecord(
              worldId,
              node.id(),
              node.type(),
              pos.getBlockX(),
              pos.getBlockY(),
              pos.getBlockZ(),
              node.trainCartsDestination(),
              node.waypointMetadata()));
    }
    records.sort(Comparator.comparing(r -> r.nodeId().value()));
    return List.copyOf(records);
  }

  private record AppliedGraphBuild(
      RailGraphBuildResult result, Optional<RailGraphMerger.MergeResult> merge) {
    private AppliedGraphBuild {
      Objects.requireNonNull(result, "result");
      Objects.requireNonNull(merge, "merge");
    }
  }

  /**
   * 把调度图快照写入存储（SQL）。
   *
   * <p>实现为“按世界 replace”：每次写入会覆盖该世界原有的 rail_nodes/rail_edges/rail_graph_snapshots 记录。
   *
   * <p>当存储未就绪（例如启动失败回退为占位存储）时，该方法会直接 no-op。
   */
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

  /**
   * 从存储后端删除指定世界的调度图快照。
   *
   * @return 存储中是否存在该世界的快照记录（用于命令提示“没有可删的快照”）
   */
  private boolean deleteGraphFromStorage(World world) {
    Objects.requireNonNull(world, "world");
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      return false;
    }
    Optional<StorageProvider> providerOpt = plugin.getStorageManager().provider();
    if (providerOpt.isEmpty()) {
      return false;
    }
    StorageProvider provider = providerOpt.get();
    UUID worldId = world.getUID();
    try {
      return provider
          .transactionManager()
          .execute(
              () -> {
                boolean existed = provider.railGraphSnapshots().findByWorld(worldId).isPresent();
                provider.railNodes().deleteWorld(worldId);
                provider.railEdges().deleteWorld(worldId);
                provider.railGraphSnapshots().delete(worldId);
                return existed;
              });
    } catch (Exception ex) {
      plugin.getLogger().warning("删除调度图失败: " + ex.getMessage());
      return false;
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

  /**
   * 在图中寻找距离指定坐标最近的节点。
   *
   * <p>用途：{@code /fta graph delete here} 需要先找到玩家附近的一个“种子节点”，再删除其所在的连通分量。
   *
   * <p>注意：这里仅使用节点记录的世界坐标做欧氏距离比较（squared distance）；不做寻路/不访问轨道方块。
   */
  private static Optional<RailNode> findNearestGraphNode(
      RailGraph graph, Vector center, int maxDistanceBlocks) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(center, "center");
    if (maxDistanceBlocks <= 0) {
      throw new IllegalArgumentException("maxDistanceBlocks 必须为正数");
    }

    double maxDistanceSquared = maxDistanceBlocks * (double) maxDistanceBlocks;
    RailNode best = null;
    double bestDistanceSquared = Double.POSITIVE_INFINITY;
    for (RailNode node : graph.nodes()) {
      if (node == null) {
        continue;
      }
      Vector nodePos = node.worldPosition();
      if (nodePos == null) {
        continue;
      }
      double dx = nodePos.getX() - center.getX();
      double dy = nodePos.getY() - center.getY();
      double dz = nodePos.getZ() - center.getZ();
      double distanceSquared = dx * dx + dy * dy + dz * dz;
      if (!Double.isFinite(distanceSquared) || distanceSquared > maxDistanceSquared) {
        continue;
      }
      if (distanceSquared < bestDistanceSquared) {
        bestDistanceSquared = distanceSquared;
        best = node;
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

  private static boolean isSignalNode(RailNodeRecord node) {
    if (node == null) {
      return false;
    }
    NodeType type = node.nodeType();
    return type == NodeType.WAYPOINT || type == NodeType.STATION || type == NodeType.DEPOT;
  }

  /**
   * 在构建完成后输出“道岔附近缺少 switcher 牌子”的运维提示。
   *
   * <p>该提示仅影响诊断与运维流程，不影响图本身的可用性：
   *
   * <ul>
   *   <li>在 TrainCarts 线网中，建议为关键分叉放置 switcher 牌子，便于运维封锁与行为对齐
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
