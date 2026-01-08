package org.fetarute.fetaruteTCAddon.command;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
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
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideLister;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.RailSpeed;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailGraphMultiSourceExplorerSession;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
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
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * 调度图诊断与运维命令：/fta graph build|continue|status|cancel|info|delete|query|path|component|sign。
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
 * <p>图查询（query/path/component）：基于内存快照做可达性、最短路径与连通分量诊断；ETA 采用“按边限速优先、否则默认速度”的估算策略，默认速度来源为 {@code
 * config.yml} 的 {@code graph.default-speed-blocks-per-second}（单位 blocks/s）。
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
  private static final Pattern SPEED_PATTERN =
      Pattern.compile(
          "^\\s*(?<value>[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*(?<unit>kmh|km/h|kph|bps|bpt)?\\s*$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern TTL_PATTERN = Pattern.compile("(?i)(\\d+)([smhd])");

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
    CommandFlag<Void> confirmFlag = CommandFlag.builder("confirm").build();

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
            .literal("set")
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

                  handleSignSet(
                      player,
                      locale,
                      "waypoint",
                      NodeType.WAYPOINT,
                      ctx.get("id"),
                      "command.graph.sign.waypoint.invalid-id",
                      "command.graph.sign.waypoint.success");
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("sign")
            .literal("set")
            .literal("autostation")
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
                  handleSignSet(
                      player,
                      locale,
                      "autostation",
                      NodeType.STATION,
                      ctx.get("id"),
                      "command.graph.sign.autostation.invalid-id",
                      "command.graph.sign.autostation.success");
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("sign")
            .literal("set")
            .literal("depot")
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
                  handleSignSet(
                      player,
                      locale,
                      "depot",
                      NodeType.DEPOT,
                      ctx.get("id"),
                      "command.graph.sign.depot.invalid-id",
                      "command.graph.sign.depot.success");
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
                  Instant now = Instant.now();
                  RailGraph graph = graphWithEdgeOverrides(world.getUID(), snapshot.graph(), now);

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

    SuggestionProvider<CommandSender> nodeIdSuggestions = graphNodeIdSuggestions("\"<nodeId>\"");
    SuggestionProvider<CommandSender> speedSuggestions = speedSuggestions("<speed>");
    SuggestionProvider<CommandSender> ttlSuggestions = ttlSuggestions("<ttl>");
    var nodeFilterFlag =
        CommandFlag.<CommandSender>builder("node")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "node", StringParser.quotedStringParser())
                    .suggestionProvider(nodeIdSuggestions))
            .build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("speed")
            .literal("set")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("speed", StringParser.stringParser(), speedSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant now = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(world.getUID(), snapshotOpt.get().graph(), now);

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  if (graph.findNode(a).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", a.value())));
                    return;
                  }
                  if (graph.findNode(b).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", b.value())));
                    return;
                  }
                  EdgeId edgeId = EdgeId.undirected(a, b);
                  if (!graphHasEdge(graph, edgeId)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.not-adjacent",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }
                  Optional<RailSpeed> speedOpt = parseSpeedArg(((String) ctx.get("speed")).trim());
                  if (speedOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.invalid-speed",
                            Map.of("raw", String.valueOf(ctx.get("speed")))));
                    return;
                  }
                  RailSpeed speed = speedOpt.get();

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord current =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  RailEdgeOverrideRecord next =
                      new RailEdgeOverrideRecord(
                          world.getUID(),
                          edgeId,
                          OptionalDouble.of(speed.blocksPerSecond()),
                          current != null
                              ? current.tempSpeedLimitBlocksPerSecond()
                              : OptionalDouble.empty(),
                          current != null ? current.tempSpeedLimitUntil() : Optional.empty(),
                          current != null && current.blockedManual(),
                          current != null ? current.blockedUntil() : Optional.empty(),
                          Instant.now());

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              provider.railEdgeOverrides().upsert(next);
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    service.putEdgeOverride(next);
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.set.success",
                          Map.of(
                              "a",
                              a.value(),
                              "b",
                              b.value(),
                              "speed",
                              speed.formatWithAllUnits())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("speed")
            .literal("clear")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  EdgeId edgeId = EdgeId.undirected(a, b);

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord current =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  if (current == null || current.speedLimitBlocksPerSecond().isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.clear.none",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }

                  RailEdgeOverrideRecord next =
                      new RailEdgeOverrideRecord(
                          world.getUID(),
                          edgeId,
                          OptionalDouble.empty(),
                          current.tempSpeedLimitBlocksPerSecond(),
                          current.tempSpeedLimitUntil(),
                          current.blockedManual(),
                          current.blockedUntil(),
                          Instant.now());

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              if (next.isEmpty()) {
                                provider.railEdgeOverrides().delete(world.getUID(), edgeId);
                              } else {
                                provider.railEdgeOverrides().upsert(next);
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    if (next.isEmpty()) {
                      service.deleteEdgeOverride(world.getUID(), edgeId);
                    } else {
                      service.putEdgeOverride(next);
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.clear.success",
                          Map.of("a", a.value(), "b", b.value())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("speed")
            .literal("temp")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("speed", StringParser.stringParser(), speedSuggestions)
            .required("ttl", StringParser.stringParser(), ttlSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant now = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(world.getUID(), snapshotOpt.get().graph(), now);

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  if (graph.findNode(a).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", a.value())));
                    return;
                  }
                  if (graph.findNode(b).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", b.value())));
                    return;
                  }
                  EdgeId edgeId = EdgeId.undirected(a, b);
                  if (!graphHasEdge(graph, edgeId)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.not-adjacent",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }

                  Optional<RailSpeed> speedOpt = parseSpeedArg(((String) ctx.get("speed")).trim());
                  if (speedOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.invalid-speed",
                            Map.of("raw", String.valueOf(ctx.get("speed")))));
                    return;
                  }
                  Optional<Duration> ttlOpt = parseTtlArg(((String) ctx.get("ttl")).trim());
                  if (ttlOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.invalid-ttl",
                            Map.of("raw", String.valueOf(ctx.get("ttl")))));
                    return;
                  }
                  RailSpeed speed = speedOpt.get();
                  Duration ttl = ttlOpt.get();
                  Instant until = now.plus(ttl);

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord current =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  RailEdgeOverrideRecord next =
                      new RailEdgeOverrideRecord(
                          world.getUID(),
                          edgeId,
                          current != null
                              ? current.speedLimitBlocksPerSecond()
                              : OptionalDouble.empty(),
                          OptionalDouble.of(speed.blocksPerSecond()),
                          Optional.of(until),
                          current != null && current.blockedManual(),
                          current != null ? current.blockedUntil() : Optional.empty(),
                          now);

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              provider.railEdgeOverrides().upsert(next);
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    service.putEdgeOverride(next);
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.temp.success",
                          Map.of(
                              "a",
                              a.value(),
                              "b",
                              b.value(),
                              "speed",
                              speed.formatWithAllUnits(),
                              "ttl",
                              formatDuration(ttl))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("speed")
            .literal("temp-clear")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  EdgeId edgeId = EdgeId.undirected(a, b);

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord current =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  if (current == null || current.tempSpeedLimitBlocksPerSecond().isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.temp-clear.none",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }

                  RailEdgeOverrideRecord next =
                      new RailEdgeOverrideRecord(
                          world.getUID(),
                          edgeId,
                          current.speedLimitBlocksPerSecond(),
                          OptionalDouble.empty(),
                          Optional.empty(),
                          current.blockedManual(),
                          current.blockedUntil(),
                          Instant.now());

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              if (next.isEmpty()) {
                                provider.railEdgeOverrides().delete(world.getUID(), edgeId);
                              } else {
                                provider.railEdgeOverrides().upsert(next);
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    if (next.isEmpty()) {
                      service.deleteEdgeOverride(world.getUID(), edgeId);
                    } else {
                      service.putEdgeOverride(next);
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.temp-clear.success",
                          Map.of("a", a.value(), "b", b.value())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("speed")
            .literal("path")
            .permission("fetarute.graph.edge")
            .flag(confirmFlag)
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("speed", StringParser.stringParser(), speedSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant now = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(world.getUID(), snapshotOpt.get().graph(), now);

                  NodeId from = NodeId.of(normalizeNodeIdArg(ctx.get("from")));
                  NodeId to = NodeId.of(normalizeNodeIdArg(ctx.get("to")));
                  if (graph.findNode(from).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", from.value())));
                    return;
                  }
                  if (graph.findNode(to).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", to.value())));
                    return;
                  }

                  Optional<RailSpeed> speedOpt = parseSpeedArg(((String) ctx.get("speed")).trim());
                  if (speedOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.invalid-speed",
                            Map.of("raw", String.valueOf(ctx.get("speed")))));
                    return;
                  }
                  RailSpeed speed = speedOpt.get();

                  RailGraphPathFinder pathFinder = new RailGraphPathFinder();
                  RailGraphPathFinder.Options options =
                      new RailGraphPathFinder.Options(
                          RailGraphPathFinder.Options.shortestDistance().costModel(), true);
                  Optional<RailGraphPath> pathOpt =
                      pathFinder.shortestPath(graph, from, to, options);
                  if (pathOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.unreachable",
                            Map.of("from", from.value(), "to", to.value())));
                    return;
                  }

                  RailGraphPath path = pathOpt.get();
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.path.preview",
                            Map.of(
                                "from",
                                from.value(),
                                "to",
                                to.value(),
                                "edges",
                                String.valueOf(path.edges().size()),
                                "speed",
                                speed.formatWithAllUnits())));
                    sender.sendMessage(locale.component("command.common.confirm-required"));
                    return;
                  }

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  RailGraphService service = plugin.getRailGraphService();

                  List<RailEdgeOverrideRecord> updates = new ArrayList<>();
                  for (RailEdge edge : path.edges()) {
                    if (edge == null) {
                      continue;
                    }
                    EdgeId edgeId = EdgeId.undirected(edge.id().a(), edge.id().b());
                    RailEdgeOverrideRecord current =
                        service != null
                            ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                            : null;
                    RailEdgeOverrideRecord next =
                        new RailEdgeOverrideRecord(
                            world.getUID(),
                            edgeId,
                            OptionalDouble.of(speed.blocksPerSecond()),
                            current != null
                                ? current.tempSpeedLimitBlocksPerSecond()
                                : OptionalDouble.empty(),
                            current != null ? current.tempSpeedLimitUntil() : Optional.empty(),
                            current != null && current.blockedManual(),
                            current != null ? current.blockedUntil() : Optional.empty(),
                            now);
                    updates.add(next);
                  }

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              for (RailEdgeOverrideRecord update : updates) {
                                provider.railEdgeOverrides().upsert(update);
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    for (RailEdgeOverrideRecord update : updates) {
                      service.putEdgeOverride(update);
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.path.success",
                          Map.of(
                              "from",
                              from.value(),
                              "to",
                              to.value(),
                              "edges",
                              String.valueOf(updates.size()),
                              "speed",
                              speed.formatWithAllUnits())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("speed")
            .literal("path")
            .literal("temp")
            .permission("fetarute.graph.edge")
            .flag(confirmFlag)
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("speed", StringParser.stringParser(), speedSuggestions)
            .required("ttl", StringParser.stringParser(), ttlSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant now = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(world.getUID(), snapshotOpt.get().graph(), now);

                  NodeId from = NodeId.of(normalizeNodeIdArg(ctx.get("from")));
                  NodeId to = NodeId.of(normalizeNodeIdArg(ctx.get("to")));
                  if (graph.findNode(from).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", from.value())));
                    return;
                  }
                  if (graph.findNode(to).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", to.value())));
                    return;
                  }

                  Optional<RailSpeed> speedOpt = parseSpeedArg(((String) ctx.get("speed")).trim());
                  if (speedOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.invalid-speed",
                            Map.of("raw", String.valueOf(ctx.get("speed")))));
                    return;
                  }
                  Optional<Duration> ttlOpt = parseTtlArg(((String) ctx.get("ttl")).trim());
                  if (ttlOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.invalid-ttl",
                            Map.of("raw", String.valueOf(ctx.get("ttl")))));
                    return;
                  }
                  RailSpeed speed = speedOpt.get();
                  Duration ttl = ttlOpt.get();

                  RailGraphPathFinder pathFinder = new RailGraphPathFinder();
                  RailGraphPathFinder.Options options =
                      new RailGraphPathFinder.Options(
                          RailGraphPathFinder.Options.shortestDistance().costModel(), true);
                  Optional<RailGraphPath> pathOpt =
                      pathFinder.shortestPath(graph, from, to, options);
                  if (pathOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.unreachable",
                            Map.of("from", from.value(), "to", to.value())));
                    return;
                  }

                  RailGraphPath path = pathOpt.get();
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.path.temp.preview",
                            Map.of(
                                "from",
                                from.value(),
                                "to",
                                to.value(),
                                "edges",
                                String.valueOf(path.edges().size()),
                                "speed",
                                speed.formatWithAllUnits(),
                                "ttl",
                                formatDuration(ttl))));
                    sender.sendMessage(locale.component("command.common.confirm-required"));
                    return;
                  }

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  RailGraphService service = plugin.getRailGraphService();

                  Instant until = now.plus(ttl);
                  List<RailEdgeOverrideRecord> updates = new ArrayList<>();
                  for (RailEdge edge : path.edges()) {
                    if (edge == null) {
                      continue;
                    }
                    EdgeId edgeId = EdgeId.undirected(edge.id().a(), edge.id().b());
                    RailEdgeOverrideRecord current =
                        service != null
                            ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                            : null;
                    RailEdgeOverrideRecord next =
                        new RailEdgeOverrideRecord(
                            world.getUID(),
                            edgeId,
                            current != null
                                ? current.speedLimitBlocksPerSecond()
                                : OptionalDouble.empty(),
                            OptionalDouble.of(speed.blocksPerSecond()),
                            Optional.of(until),
                            current != null && current.blockedManual(),
                            current != null ? current.blockedUntil() : Optional.empty(),
                            now);
                    updates.add(next);
                  }

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              for (RailEdgeOverrideRecord update : updates) {
                                provider.railEdgeOverrides().upsert(update);
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    for (RailEdgeOverrideRecord update : updates) {
                      service.putEdgeOverride(update);
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.path.temp.success",
                          Map.of(
                              "from",
                              from.value(),
                              "to",
                              to.value(),
                              "edges",
                              String.valueOf(updates.size()),
                              "speed",
                              speed.formatWithAllUnits(),
                              "ttl",
                              formatDuration(ttl))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("speed")
            .literal("list")
            .permission("fetarute.graph.edge")
            .flag(nodeFilterFlag)
            .optional("page", IntegerParser.integerParser())
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  List<RailEdgeOverrideRecord> overrides;
                  try {
                    overrides = provider.railEdgeOverrides().listByWorld(world.getUID());
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  Instant now = Instant.now();
                  RailGraphService service = plugin.getRailGraphService();
                  overrides =
                      cleanupExpiredTtlOverrides(provider, world.getUID(), service, now, overrides);
                  String nodeRaw = ctx.flags().getValue(nodeFilterFlag, null);
                  Optional<NodeId> nodeFilter =
                      nodeRaw == null || nodeRaw.isBlank()
                          ? Optional.empty()
                          : Optional.of(NodeId.of(normalizeNodeIdArg(nodeRaw)));
                  List<RailEdgeOverrideRecord> filtered =
                      EdgeOverrideLister.filter(
                          overrides,
                          now,
                          new EdgeOverrideLister.Query(
                              EdgeOverrideLister.Kind.SPEED, false, nodeFilter));
                  if (filtered.isEmpty()) {
                    sender.sendMessage(locale.component("command.graph.edge.speed.list.empty"));
                    return;
                  }

                  int page = ctx.optional("page").map(Integer.class::cast).orElse(1);
                  ListPage<RailEdgeOverrideRecord> pageResult = paginate(filtered, page, 10);

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.list.header",
                          Map.of(
                              "world", world.getName(), "count", String.valueOf(filtered.size()))));
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.list.page",
                          Map.of(
                              "page",
                              String.valueOf(pageResult.page()),
                              "pages",
                              String.valueOf(pageResult.totalPages()))));

                  for (RailEdgeOverrideRecord record : pageResult.items()) {
                    EdgeId id = EdgeId.undirected(record.edgeId().a(), record.edgeId().b());
                    String speedText =
                        RailSpeed.ofBlocksPerSecond(
                                record.speedLimitBlocksPerSecond().getAsDouble())
                            .formatWithAllUnits();
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.list.entry",
                            Map.of(
                                "a",
                                id.a().value(),
                                "b",
                                id.b().value(),
                                "speed",
                                speedText,
                                "updated_at",
                                record.updatedAt().toString())));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("restrict")
            .literal("list")
            .permission("fetarute.graph.edge")
            .flag(nodeFilterFlag)
            .optional("page", IntegerParser.integerParser())
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  List<RailEdgeOverrideRecord> overrides;
                  try {
                    overrides = provider.railEdgeOverrides().listByWorld(world.getUID());
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  Instant now = Instant.now();
                  RailGraphService service = plugin.getRailGraphService();
                  overrides =
                      cleanupExpiredTtlOverrides(provider, world.getUID(), service, now, overrides);
                  String nodeRaw = ctx.flags().getValue(nodeFilterFlag, null);
                  Optional<NodeId> nodeFilter =
                      nodeRaw == null || nodeRaw.isBlank()
                          ? Optional.empty()
                          : Optional.of(NodeId.of(normalizeNodeIdArg(nodeRaw)));
                  List<RailEdgeOverrideRecord> filtered =
                      EdgeOverrideLister.filter(
                          overrides,
                          now,
                          new EdgeOverrideLister.Query(
                              EdgeOverrideLister.Kind.RESTRICT, false, nodeFilter));
                  if (filtered.isEmpty()) {
                    sender.sendMessage(locale.component("command.graph.edge.restrict.list.empty"));
                    return;
                  }

                  int page = ctx.optional("page").map(Integer.class::cast).orElse(1);
                  ListPage<RailEdgeOverrideRecord> pageResult = paginate(filtered, page, 10);

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.restrict.list.header",
                          Map.of(
                              "world", world.getName(), "count", String.valueOf(filtered.size()))));
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.restrict.list.page",
                          Map.of(
                              "page",
                              String.valueOf(pageResult.page()),
                              "pages",
                              String.valueOf(pageResult.totalPages()))));

                  for (RailEdgeOverrideRecord record : pageResult.items()) {
                    EdgeId id = EdgeId.undirected(record.edgeId().a(), record.edgeId().b());
                    Instant until = record.tempSpeedLimitUntil().orElse(null);
                    boolean active = until != null && now.isBefore(until);
                    String statusText = locale.text("command.graph.edge.list.status.active");
                    String untilText = until != null ? until.toString() : "-";
                    String remainingText =
                        active ? formatDuration(Duration.between(now, until)) : "-";
                    String speedText =
                        RailSpeed.ofBlocksPerSecond(
                                record.tempSpeedLimitBlocksPerSecond().getAsDouble())
                            .formatWithAllUnits();
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.restrict.list.entry",
                            Map.of(
                                "a",
                                id.a().value(),
                                "b",
                                id.b().value(),
                                "speed",
                                speedText,
                                "status",
                                statusText,
                                "until",
                                untilText,
                                "remaining",
                                remainingText,
                                "updated_at",
                                record.updatedAt().toString())));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("restrict")
            .literal("set")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("speed", StringParser.stringParser(), speedSuggestions)
            .required("ttl", StringParser.stringParser(), ttlSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant now = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(world.getUID(), snapshotOpt.get().graph(), now);

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  if (graph.findNode(a).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", a.value())));
                    return;
                  }
                  if (graph.findNode(b).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", b.value())));
                    return;
                  }
                  EdgeId edgeId = EdgeId.undirected(a, b);
                  if (!graphHasEdge(graph, edgeId)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.not-adjacent",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }

                  Optional<RailSpeed> speedOpt = parseSpeedArg(((String) ctx.get("speed")).trim());
                  if (speedOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.invalid-speed",
                            Map.of("raw", String.valueOf(ctx.get("speed")))));
                    return;
                  }
                  Optional<Duration> ttlOpt = parseTtlArg(((String) ctx.get("ttl")).trim());
                  if (ttlOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.invalid-ttl",
                            Map.of("raw", String.valueOf(ctx.get("ttl")))));
                    return;
                  }
                  RailSpeed speed = speedOpt.get();
                  Duration ttl = ttlOpt.get();
                  Instant until = now.plus(ttl);

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord current =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  RailEdgeOverrideRecord next =
                      new RailEdgeOverrideRecord(
                          world.getUID(),
                          edgeId,
                          current != null
                              ? current.speedLimitBlocksPerSecond()
                              : OptionalDouble.empty(),
                          OptionalDouble.of(speed.blocksPerSecond()),
                          Optional.of(until),
                          current != null && current.blockedManual(),
                          current != null ? current.blockedUntil() : Optional.empty(),
                          now);

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              provider.railEdgeOverrides().upsert(next);
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    service.putEdgeOverride(next);
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.temp.success",
                          Map.of(
                              "a",
                              a.value(),
                              "b",
                              b.value(),
                              "speed",
                              speed.formatWithAllUnits(),
                              "ttl",
                              formatDuration(ttl))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("restrict")
            .literal("clear")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  EdgeId edgeId = EdgeId.undirected(a, b);

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord current =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  if (current == null || current.tempSpeedLimitBlocksPerSecond().isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.temp-clear.none",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }

                  RailEdgeOverrideRecord next =
                      new RailEdgeOverrideRecord(
                          world.getUID(),
                          edgeId,
                          current.speedLimitBlocksPerSecond(),
                          OptionalDouble.empty(),
                          Optional.empty(),
                          current.blockedManual(),
                          current.blockedUntil(),
                          Instant.now());

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              if (next.isEmpty()) {
                                provider.railEdgeOverrides().delete(world.getUID(), edgeId);
                              } else {
                                provider.railEdgeOverrides().upsert(next);
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    if (next.isEmpty()) {
                      service.deleteEdgeOverride(world.getUID(), edgeId);
                    } else {
                      service.putEdgeOverride(next);
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.temp-clear.success",
                          Map.of("a", a.value(), "b", b.value())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("restrict")
            .literal("path")
            .permission("fetarute.graph.edge")
            .flag(confirmFlag)
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("speed", StringParser.stringParser(), speedSuggestions)
            .required("ttl", StringParser.stringParser(), ttlSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant now = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(world.getUID(), snapshotOpt.get().graph(), now);

                  NodeId from = NodeId.of(normalizeNodeIdArg(ctx.get("from")));
                  NodeId to = NodeId.of(normalizeNodeIdArg(ctx.get("to")));
                  if (graph.findNode(from).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", from.value())));
                    return;
                  }
                  if (graph.findNode(to).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", to.value())));
                    return;
                  }

                  Optional<RailSpeed> speedOpt = parseSpeedArg(((String) ctx.get("speed")).trim());
                  if (speedOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.invalid-speed",
                            Map.of("raw", String.valueOf(ctx.get("speed")))));
                    return;
                  }
                  Optional<Duration> ttlOpt = parseTtlArg(((String) ctx.get("ttl")).trim());
                  if (ttlOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.invalid-ttl",
                            Map.of("raw", String.valueOf(ctx.get("ttl")))));
                    return;
                  }
                  RailSpeed speed = speedOpt.get();
                  Duration ttl = ttlOpt.get();

                  RailGraphPathFinder pathFinder = new RailGraphPathFinder();
                  RailGraphPathFinder.Options options =
                      new RailGraphPathFinder.Options(
                          RailGraphPathFinder.Options.shortestDistance().costModel(), true);
                  Optional<RailGraphPath> pathOpt =
                      pathFinder.shortestPath(graph, from, to, options);
                  if (pathOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.unreachable",
                            Map.of("from", from.value(), "to", to.value())));
                    return;
                  }

                  RailGraphPath path = pathOpt.get();
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.path.temp.preview",
                            Map.of(
                                "from",
                                from.value(),
                                "to",
                                to.value(),
                                "edges",
                                String.valueOf(path.edges().size()),
                                "speed",
                                speed.formatWithAllUnits(),
                                "ttl",
                                formatDuration(ttl))));
                    sender.sendMessage(locale.component("command.common.confirm-required"));
                    return;
                  }

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  RailGraphService service = plugin.getRailGraphService();

                  Instant until = now.plus(ttl);
                  List<RailEdgeOverrideRecord> updates = new ArrayList<>();
                  for (RailEdge edge : path.edges()) {
                    if (edge == null) {
                      continue;
                    }
                    EdgeId edgeId = EdgeId.undirected(edge.id().a(), edge.id().b());
                    RailEdgeOverrideRecord current =
                        service != null
                            ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                            : null;
                    RailEdgeOverrideRecord next =
                        new RailEdgeOverrideRecord(
                            world.getUID(),
                            edgeId,
                            current != null
                                ? current.speedLimitBlocksPerSecond()
                                : OptionalDouble.empty(),
                            OptionalDouble.of(speed.blocksPerSecond()),
                            Optional.of(until),
                            current != null && current.blockedManual(),
                            current != null ? current.blockedUntil() : Optional.empty(),
                            now);
                    updates.add(next);
                  }

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              for (RailEdgeOverrideRecord update : updates) {
                                provider.railEdgeOverrides().upsert(update);
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    for (RailEdgeOverrideRecord update : updates) {
                      service.putEdgeOverride(update);
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.speed.path.temp.success",
                          Map.of(
                              "from",
                              from.value(),
                              "to",
                              to.value(),
                              "edges",
                              String.valueOf(updates.size()),
                              "speed",
                              speed.formatWithAllUnits(),
                              "ttl",
                              formatDuration(ttl))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("block")
            .literal("set")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant nowForGraph = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(
                          world.getUID(), snapshotOpt.get().graph(), nowForGraph);

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  if (graph.findNode(a).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", a.value())));
                    return;
                  }
                  if (graph.findNode(b).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", b.value())));
                    return;
                  }
                  EdgeId edgeId = EdgeId.undirected(a, b);
                  if (!graphHasEdge(graph, edgeId)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.not-adjacent",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord current =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  Instant now = Instant.now();
                  RailEdgeOverrideRecord next =
                      new RailEdgeOverrideRecord(
                          world.getUID(),
                          edgeId,
                          current != null
                              ? current.speedLimitBlocksPerSecond()
                              : OptionalDouble.empty(),
                          current != null
                              ? current.tempSpeedLimitBlocksPerSecond()
                              : OptionalDouble.empty(),
                          current != null ? current.tempSpeedLimitUntil() : Optional.empty(),
                          true,
                          current != null ? current.blockedUntil() : Optional.empty(),
                          now);

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              provider.railEdgeOverrides().upsert(next);
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    service.putEdgeOverride(next);
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.block.set.success",
                          Map.of("a", a.value(), "b", b.value())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("block")
            .literal("clear")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  EdgeId edgeId = EdgeId.undirected(a, b);

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord current =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  if (current == null
                      || (!current.blockedManual() && current.blockedUntil().isEmpty())) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.clear.none",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }

                  Instant now = Instant.now();
                  RailEdgeOverrideRecord next =
                      new RailEdgeOverrideRecord(
                          world.getUID(),
                          edgeId,
                          current.speedLimitBlocksPerSecond(),
                          current.tempSpeedLimitBlocksPerSecond(),
                          current.tempSpeedLimitUntil(),
                          false,
                          Optional.empty(),
                          now);

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              if (next.isEmpty()) {
                                provider.railEdgeOverrides().delete(world.getUID(), edgeId);
                              } else {
                                provider.railEdgeOverrides().upsert(next);
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    if (next.isEmpty()) {
                      service.deleteEdgeOverride(world.getUID(), edgeId);
                    } else {
                      service.putEdgeOverride(next);
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.block.clear.success",
                          Map.of("a", a.value(), "b", b.value())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("block")
            .literal("temp")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("ttl", StringParser.stringParser(), ttlSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant nowForGraph = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(
                          world.getUID(), snapshotOpt.get().graph(), nowForGraph);

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  if (graph.findNode(a).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", a.value())));
                    return;
                  }
                  if (graph.findNode(b).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", b.value())));
                    return;
                  }
                  EdgeId edgeId = EdgeId.undirected(a, b);
                  if (!graphHasEdge(graph, edgeId)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.not-adjacent",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }

                  Optional<Duration> ttlOpt = parseTtlArg(((String) ctx.get("ttl")).trim());
                  if (ttlOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.invalid-ttl",
                            Map.of("raw", String.valueOf(ctx.get("ttl")))));
                    return;
                  }
                  Duration ttl = ttlOpt.get();

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord current =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  Instant now = Instant.now();
                  Instant until = now.plus(ttl);
                  RailEdgeOverrideRecord next =
                      new RailEdgeOverrideRecord(
                          world.getUID(),
                          edgeId,
                          current != null
                              ? current.speedLimitBlocksPerSecond()
                              : OptionalDouble.empty(),
                          current != null
                              ? current.tempSpeedLimitBlocksPerSecond()
                              : OptionalDouble.empty(),
                          current != null ? current.tempSpeedLimitUntil() : Optional.empty(),
                          current != null && current.blockedManual(),
                          Optional.of(until),
                          now);

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              provider.railEdgeOverrides().upsert(next);
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    service.putEdgeOverride(next);
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.block.temp.success",
                          Map.of("a", a.value(), "b", b.value(), "ttl", formatDuration(ttl))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("block")
            .literal("temp-clear")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  EdgeId edgeId = EdgeId.undirected(a, b);

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord current =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  if (current == null || current.blockedUntil().isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.temp-clear.none",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }

                  Instant now = Instant.now();
                  RailEdgeOverrideRecord next =
                      new RailEdgeOverrideRecord(
                          world.getUID(),
                          edgeId,
                          current.speedLimitBlocksPerSecond(),
                          current.tempSpeedLimitBlocksPerSecond(),
                          current.tempSpeedLimitUntil(),
                          current.blockedManual(),
                          Optional.empty(),
                          now);

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              if (next.isEmpty()) {
                                provider.railEdgeOverrides().delete(world.getUID(), edgeId);
                              } else {
                                provider.railEdgeOverrides().upsert(next);
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    if (next.isEmpty()) {
                      service.deleteEdgeOverride(world.getUID(), edgeId);
                    } else {
                      service.putEdgeOverride(next);
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.block.temp-clear.success",
                          Map.of("a", a.value(), "b", b.value())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("block")
            .literal("path")
            .permission("fetarute.graph.edge")
            .flag(confirmFlag)
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant nowForGraph = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(
                          world.getUID(), snapshotOpt.get().graph(), nowForGraph);

                  NodeId from = NodeId.of(normalizeNodeIdArg(ctx.get("from")));
                  NodeId to = NodeId.of(normalizeNodeIdArg(ctx.get("to")));
                  if (graph.findNode(from).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", from.value())));
                    return;
                  }
                  if (graph.findNode(to).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", to.value())));
                    return;
                  }

                  RailGraphPathFinder pathFinder = new RailGraphPathFinder();
                  RailGraphPathFinder.Options options =
                      new RailGraphPathFinder.Options(
                          RailGraphPathFinder.Options.shortestDistance().costModel(), true);
                  Optional<RailGraphPath> pathOpt =
                      pathFinder.shortestPath(graph, from, to, options);
                  if (pathOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.unreachable",
                            Map.of("from", from.value(), "to", to.value())));
                    return;
                  }

                  RailGraphPath path = pathOpt.get();
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.path.preview",
                            Map.of(
                                "from",
                                from.value(),
                                "to",
                                to.value(),
                                "edges",
                                String.valueOf(path.edges().size()))));
                    sender.sendMessage(locale.component("command.common.confirm-required"));
                    return;
                  }

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  RailGraphService service = plugin.getRailGraphService();

                  Instant now = Instant.now();
                  List<RailEdgeOverrideRecord> updates = new ArrayList<>();
                  for (RailEdge edge : path.edges()) {
                    if (edge == null) {
                      continue;
                    }
                    EdgeId edgeId = EdgeId.undirected(edge.id().a(), edge.id().b());
                    RailEdgeOverrideRecord current =
                        service != null
                            ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                            : null;
                    RailEdgeOverrideRecord next =
                        new RailEdgeOverrideRecord(
                            world.getUID(),
                            edgeId,
                            current != null
                                ? current.speedLimitBlocksPerSecond()
                                : OptionalDouble.empty(),
                            current != null
                                ? current.tempSpeedLimitBlocksPerSecond()
                                : OptionalDouble.empty(),
                            current != null ? current.tempSpeedLimitUntil() : Optional.empty(),
                            true,
                            current != null ? current.blockedUntil() : Optional.empty(),
                            now);
                    updates.add(next);
                  }

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              for (RailEdgeOverrideRecord update : updates) {
                                provider.railEdgeOverrides().upsert(update);
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    for (RailEdgeOverrideRecord update : updates) {
                      service.putEdgeOverride(update);
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.block.path.success",
                          Map.of(
                              "from",
                              from.value(),
                              "to",
                              to.value(),
                              "edges",
                              String.valueOf(updates.size()))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("block")
            .literal("path")
            .literal("temp")
            .permission("fetarute.graph.edge")
            .flag(confirmFlag)
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("ttl", StringParser.stringParser(), ttlSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant nowForGraph = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(
                          world.getUID(), snapshotOpt.get().graph(), nowForGraph);

                  NodeId from = NodeId.of(normalizeNodeIdArg(ctx.get("from")));
                  NodeId to = NodeId.of(normalizeNodeIdArg(ctx.get("to")));
                  if (graph.findNode(from).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", from.value())));
                    return;
                  }
                  if (graph.findNode(to).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", to.value())));
                    return;
                  }

                  Optional<Duration> ttlOpt = parseTtlArg(((String) ctx.get("ttl")).trim());
                  if (ttlOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.invalid-ttl",
                            Map.of("raw", String.valueOf(ctx.get("ttl")))));
                    return;
                  }
                  Duration ttl = ttlOpt.get();

                  RailGraphPathFinder pathFinder = new RailGraphPathFinder();
                  RailGraphPathFinder.Options options =
                      new RailGraphPathFinder.Options(
                          RailGraphPathFinder.Options.shortestDistance().costModel(), true);
                  Optional<RailGraphPath> pathOpt =
                      pathFinder.shortestPath(graph, from, to, options);
                  if (pathOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.unreachable",
                            Map.of("from", from.value(), "to", to.value())));
                    return;
                  }

                  RailGraphPath path = pathOpt.get();
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.path.temp.preview",
                            Map.of(
                                "from",
                                from.value(),
                                "to",
                                to.value(),
                                "edges",
                                String.valueOf(path.edges().size()),
                                "ttl",
                                formatDuration(ttl))));
                    sender.sendMessage(locale.component("command.common.confirm-required"));
                    return;
                  }

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  RailGraphService service = plugin.getRailGraphService();

                  Instant now = Instant.now();
                  Instant until = now.plus(ttl);
                  List<RailEdgeOverrideRecord> updates = new ArrayList<>();
                  for (RailEdge edge : path.edges()) {
                    if (edge == null) {
                      continue;
                    }
                    EdgeId edgeId = EdgeId.undirected(edge.id().a(), edge.id().b());
                    RailEdgeOverrideRecord current =
                        service != null
                            ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                            : null;
                    RailEdgeOverrideRecord next =
                        new RailEdgeOverrideRecord(
                            world.getUID(),
                            edgeId,
                            current != null
                                ? current.speedLimitBlocksPerSecond()
                                : OptionalDouble.empty(),
                            current != null
                                ? current.tempSpeedLimitBlocksPerSecond()
                                : OptionalDouble.empty(),
                            current != null ? current.tempSpeedLimitUntil() : Optional.empty(),
                            current != null && current.blockedManual(),
                            Optional.of(until),
                            now);
                    updates.add(next);
                  }

                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              for (RailEdgeOverrideRecord update : updates) {
                                provider.railEdgeOverrides().upsert(update);
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    for (RailEdgeOverrideRecord update : updates) {
                      service.putEdgeOverride(update);
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.block.path.temp.success",
                          Map.of(
                              "from",
                              from.value(),
                              "to",
                              to.value(),
                              "edges",
                              String.valueOf(updates.size()),
                              "ttl",
                              formatDuration(ttl))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("block")
            .literal("list")
            .permission("fetarute.graph.edge")
            .flag(nodeFilterFlag)
            .optional("page", IntegerParser.integerParser())
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  List<RailEdgeOverrideRecord> overrides;
                  try {
                    overrides = provider.railEdgeOverrides().listByWorld(world.getUID());
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  Instant now = Instant.now();
                  RailGraphService service = plugin.getRailGraphService();
                  overrides =
                      cleanupExpiredTtlOverrides(provider, world.getUID(), service, now, overrides);
                  String nodeRaw = ctx.flags().getValue(nodeFilterFlag, null);
                  Optional<NodeId> nodeFilter =
                      nodeRaw == null || nodeRaw.isBlank()
                          ? Optional.empty()
                          : Optional.of(NodeId.of(normalizeNodeIdArg(nodeRaw)));
                  List<RailEdgeOverrideRecord> filtered =
                      EdgeOverrideLister.filter(
                          overrides,
                          now,
                          new EdgeOverrideLister.Query(
                              EdgeOverrideLister.Kind.BLOCK, false, nodeFilter));
                  if (filtered.isEmpty()) {
                    sender.sendMessage(locale.component("command.graph.edge.block.list.empty"));
                    return;
                  }

                  int page = ctx.optional("page").map(Integer.class::cast).orElse(1);
                  ListPage<RailEdgeOverrideRecord> pageResult = paginate(filtered, page, 10);

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.block.list.header",
                          Map.of(
                              "world", world.getName(), "count", String.valueOf(filtered.size()))));
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.block.list.page",
                          Map.of(
                              "page",
                              String.valueOf(pageResult.page()),
                              "pages",
                              String.valueOf(pageResult.totalPages()))));

                  for (RailEdgeOverrideRecord record : pageResult.items()) {
                    EdgeId id = EdgeId.undirected(record.edgeId().a(), record.edgeId().b());
                    Instant until = record.blockedUntil().orElse(null);
                    boolean ttlActive = until != null && now.isBefore(until);
                    boolean manual = record.blockedManual();
                    String modeText;
                    if (manual && until != null) {
                      modeText = locale.text("command.graph.edge.block.list.mode.both");
                    } else if (manual) {
                      modeText = locale.text("command.graph.edge.block.list.mode.manual");
                    } else {
                      modeText = locale.text("command.graph.edge.block.list.mode.ttl");
                    }

                    String statusText = locale.text("command.graph.edge.list.status.active");
                    String untilText = until != null ? until.toString() : "-";
                    String remainingText =
                        ttlActive ? formatDuration(Duration.between(now, until)) : "-";

                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.block.list.entry",
                            Map.of(
                                "a",
                                id.a().value(),
                                "b",
                                id.b().value(),
                                "mode",
                                modeText,
                                "status",
                                statusText,
                                "until",
                                untilText,
                                "remaining",
                                remainingText,
                                "updated_at",
                                record.updatedAt().toString())));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("get")
            .permission("fetarute.graph.edge")
            .required("a", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("b", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  Instant now = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(world.getUID(), snapshotOpt.get().graph(), now);

                  NodeId a = NodeId.of(normalizeNodeIdArg(ctx.get("a")));
                  NodeId b = NodeId.of(normalizeNodeIdArg(ctx.get("b")));
                  if (graph.findNode(a).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", a.value())));
                    return;
                  }
                  if (graph.findNode(b).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", b.value())));
                    return;
                  }
                  EdgeId edgeId = EdgeId.undirected(a, b);
                  RailEdge edge = findEdgeOrNull(graph, edgeId);
                  if (edge == null) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.not-adjacent",
                            Map.of("a", a.value(), "b", b.value())));
                    return;
                  }

                  RailGraphService service = plugin.getRailGraphService();
                  RailEdgeOverrideRecord override =
                      service != null
                          ? service.getEdgeOverride(world.getUID(), edgeId).orElse(null)
                          : null;
                  if (override != null) {
                    override =
                        cleanupExpiredTtlOverride(provider, world.getUID(), service, now, override)
                            .orElse(null);
                  }

                  double defaultSpeed = defaultSpeedBlocksPerSecond();
                  double effectiveSpeed =
                      service != null
                          ? service.effectiveSpeedLimitBlocksPerSecond(
                              world.getUID(), edge, now, defaultSpeed)
                          : (edge.baseSpeedLimit() > 0.0 ? edge.baseSpeedLimit() : defaultSpeed);
                  String effectiveSpeedText =
                      RailSpeed.ofBlocksPerSecond(effectiveSpeed).formatWithAllUnits();
                  boolean blocked = graph.isBlocked(edge.id());
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.get.edge.header",
                          Map.of(
                              "a",
                              a.value(),
                              "b",
                              b.value(),
                              "len_blocks",
                              String.valueOf(edge.lengthBlocks()),
                              "blocked",
                              blocked
                                  ? locale.text("command.common.yes")
                                  : locale.text("command.common.no"),
                              "effective_speed",
                              effectiveSpeedText)));

                  double baseFromEdge = edge.baseSpeedLimit();
                  double base =
                      Double.isFinite(baseFromEdge) && baseFromEdge > 0.0
                          ? baseFromEdge
                          : defaultSpeed;
                  String baseText = RailSpeed.ofBlocksPerSecond(base).formatWithAllUnits();
                  String speedLimitText =
                      override != null && override.speedLimitBlocksPerSecond().isPresent()
                          ? RailSpeed.ofBlocksPerSecond(
                                  override.speedLimitBlocksPerSecond().getAsDouble())
                              .formatWithAllUnits()
                          : "-";
                  String restrictText = "-";
                  String restrictUntilText = "-";
                  String restrictRemainingText = "-";
                  if (override != null && override.isTempSpeedActive(now)) {
                    restrictText =
                        RailSpeed.ofBlocksPerSecond(
                                override.tempSpeedLimitBlocksPerSecond().getAsDouble())
                            .formatWithAllUnits();
                    Instant until = override.tempSpeedLimitUntil().orElse(null);
                    if (until != null) {
                      restrictUntilText = until.toString();
                      restrictRemainingText = formatDuration(Duration.between(now, until));
                    }
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.get.edge.speed",
                          Map.of(
                              "base_speed",
                              baseText,
                              "speed_limit",
                              speedLimitText,
                              "restrict_speed",
                              restrictText,
                              "restrict_until",
                              restrictUntilText,
                              "restrict_remaining",
                              restrictRemainingText,
                              "effective_speed",
                              effectiveSpeedText)));

                  String manualText =
                      override != null && override.blockedManual()
                          ? locale.text("command.common.yes")
                          : locale.text("command.common.no");
                  String blockUntilText = "-";
                  String blockRemainingText = "-";
                  if (override != null && override.blockedUntil().isPresent()) {
                    Instant until = override.blockedUntil().get();
                    blockUntilText = until.toString();
                    if (now.isBefore(until)) {
                      blockRemainingText = formatDuration(Duration.between(now, until));
                    }
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.get.edge.block",
                          Map.of(
                              "blocked_manual",
                              manualText,
                              "blocked_until",
                              blockUntilText,
                              "blocked_remaining",
                              blockRemainingText)));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("get")
            .literal("adjacent")
            .permission("fetarute.graph.edge")
            .required("node", StringParser.quotedStringParser(), nodeIdSuggestions)
            .optional("page", IntegerParser.integerParser())
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant now = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(world.getUID(), snapshotOpt.get().graph(), now);

                  NodeId node = NodeId.of(normalizeNodeIdArg(ctx.get("node")));
                  if (graph.findNode(node).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", node.value())));
                    return;
                  }

                  List<RailEdge> edges = new ArrayList<>();
                  for (RailEdge edge : graph.edgesFrom(node)) {
                    if (edge != null) {
                      edges.add(edge);
                    }
                  }
                  if (edges.isEmpty()) {
                    sender.sendMessage(locale.component("command.graph.edge.get.adjacent.empty"));
                    return;
                  }

                  int page = ctx.optional("page").map(Integer.class::cast).orElse(1);
                  ListPage<RailEdge> pageResult = paginate(edges, page, 10);

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.get.adjacent.header",
                          Map.of("node", node.value(), "count", String.valueOf(edges.size()))));
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.get.adjacent.page",
                          Map.of(
                              "page",
                              String.valueOf(pageResult.page()),
                              "pages",
                              String.valueOf(pageResult.totalPages()))));

                  double defaultSpeed = defaultSpeedBlocksPerSecond();
                  RailGraphService service = plugin.getRailGraphService();
                  for (RailEdge edge : pageResult.items()) {
                    NodeId other = node.equals(edge.from()) ? edge.to() : edge.from();
                    EdgeId id = EdgeId.undirected(edge.from(), edge.to());
                    boolean blocked = graph.isBlocked(edge.id());
                    double effectiveSpeed =
                        service != null
                            ? service.effectiveSpeedLimitBlocksPerSecond(
                                world.getUID(), edge, now, defaultSpeed)
                            : (edge.baseSpeedLimit() > 0.0 ? edge.baseSpeedLimit() : defaultSpeed);
                    String speedText =
                        RailSpeed.ofBlocksPerSecond(effectiveSpeed).formatWithAllUnits();

                    String flags = "-";
                    if (service != null) {
                      RailEdgeOverrideRecord o =
                          service.getEdgeOverride(world.getUID(), id).orElse(null);
                      if (o != null) {
                        StringBuilder sb = new StringBuilder();
                        if (o.speedLimitBlocksPerSecond().isPresent()) {
                          sb.append('S');
                        }
                        if (o.isTempSpeedActive(now)) {
                          sb.append('R');
                        }
                        if (o.isBlockedEffective(now)) {
                          sb.append('B');
                        }
                        if (!sb.isEmpty()) {
                          flags = sb.toString();
                        }
                      }
                    }

                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.get.adjacent.entry",
                            Map.of(
                                "a",
                                id.a().value(),
                                "b",
                                id.b().value(),
                                "other",
                                other != null ? other.value() : "-",
                                "len_blocks",
                                String.valueOf(edge.lengthBlocks()),
                                "speed",
                                speedText,
                                "blocked",
                                blocked
                                    ? locale.text("command.common.yes")
                                    : locale.text("command.common.no"),
                                "flags",
                                flags)));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("get")
            .literal("path")
            .permission("fetarute.graph.edge")
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .optional("page", IntegerParser.integerParser())
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant now = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(world.getUID(), snapshotOpt.get().graph(), now);

                  NodeId from = NodeId.of(normalizeNodeIdArg(ctx.get("from")));
                  NodeId to = NodeId.of(normalizeNodeIdArg(ctx.get("to")));
                  if (graph.findNode(from).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", from.value())));
                    return;
                  }
                  if (graph.findNode(to).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", to.value())));
                    return;
                  }

                  RailGraphPathFinder pathFinder = new RailGraphPathFinder();
                  Optional<RailGraphPath> pathOpt =
                      pathFinder.shortestPath(
                          graph, from, to, RailGraphPathFinder.Options.shortestDistance());
                  if (pathOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.unreachable",
                            Map.of("from", from.value(), "to", to.value())));
                    return;
                  }
                  RailGraphPath path = pathOpt.get();

                  int page = ctx.optional("page").map(Integer.class::cast).orElse(1);
                  ListPage<RailEdge> pageResult = paginate(path.edges(), page, 10);

                  double defaultSpeed = defaultSpeedBlocksPerSecond();
                  RailTravelTimeModel timeModel =
                      edgeSpeedTimeModel(world.getUID(), now, defaultSpeed);
                  Optional<Duration> etaOpt =
                      timeModel.pathTravelTime(graph, path.nodes(), path.edges());
                  String etaText = etaOpt.map(this::formatDuration).orElse("-");

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.get.path.header",
                          Map.of(
                              "from",
                              from.value(),
                              "to",
                              to.value(),
                              "hops",
                              String.valueOf(path.edges().size()),
                              "distance_blocks",
                              String.valueOf(path.totalLengthBlocks()),
                              "eta",
                              etaText)));
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.get.path.page",
                          Map.of(
                              "page",
                              String.valueOf(pageResult.page()),
                              "pages",
                              String.valueOf(pageResult.totalPages()))));

                  RailGraphService service = plugin.getRailGraphService();
                  for (int i = 0; i < pageResult.items().size(); i++) {
                    RailEdge edge = pageResult.items().get(i);
                    if (edge == null) {
                      continue;
                    }
                    EdgeId id = EdgeId.undirected(edge.from(), edge.to());
                    boolean blocked = graph.isBlocked(edge.id());
                    double effectiveSpeed =
                        service != null
                            ? service.effectiveSpeedLimitBlocksPerSecond(
                                world.getUID(), edge, now, defaultSpeed)
                            : (edge.baseSpeedLimit() > 0.0 ? edge.baseSpeedLimit() : defaultSpeed);
                    String speedText =
                        RailSpeed.ofBlocksPerSecond(effectiveSpeed).formatWithAllUnits();

                    String flags = "-";
                    if (service != null) {
                      RailEdgeOverrideRecord o =
                          service.getEdgeOverride(world.getUID(), id).orElse(null);
                      if (o != null) {
                        StringBuilder sb = new StringBuilder();
                        if (o.speedLimitBlocksPerSecond().isPresent()) {
                          sb.append('S');
                        }
                        if (o.isTempSpeedActive(now)) {
                          sb.append('R');
                        }
                        if (o.isBlockedEffective(now)) {
                          sb.append('B');
                        }
                        if (!sb.isEmpty()) {
                          flags = sb.toString();
                        }
                      }
                    }

                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.get.path.entry",
                            Map.of(
                                "seq",
                                String.valueOf((pageResult.page() - 1) * 10 + i + 1),
                                "a",
                                id.a().value(),
                                "b",
                                id.b().value(),
                                "len_blocks",
                                String.valueOf(edge.lengthBlocks()),
                                "speed",
                                speedText,
                                "blocked",
                                blocked
                                    ? locale.text("command.common.yes")
                                    : locale.text("command.common.no"),
                                "flags",
                                flags)));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("overrides")
            .literal("orphan")
            .literal("list")
            .permission("fetarute.graph.edge")
            .flag(nodeFilterFlag)
            .optional("page", IntegerParser.integerParser())
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  List<RailEdgeOverrideRecord> overrides;
                  try {
                    overrides = provider.railEdgeOverrides().listByWorld(world.getUID());
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  Instant now = Instant.now();
                  RailGraphService service = plugin.getRailGraphService();
                  overrides =
                      cleanupExpiredTtlOverrides(provider, world.getUID(), service, now, overrides);
                  String nodeRaw = ctx.flags().getValue(nodeFilterFlag, null);
                  Optional<NodeId> nodeFilter =
                      nodeRaw == null || nodeRaw.isBlank()
                          ? Optional.empty()
                          : Optional.of(NodeId.of(normalizeNodeIdArg(nodeRaw)));

                  List<RailEdgeOverrideRecord> orphans =
                      EdgeOverrideLister.orphanOverrides(overrides, snapshotOpt.get().graph());
                  if (nodeFilter.isPresent()) {
                    orphans =
                        EdgeOverrideLister.filter(
                            orphans,
                            Instant.now(),
                            new EdgeOverrideLister.Query(
                                EdgeOverrideLister.Kind.ANY, true, nodeFilter));
                  }
                  if (orphans.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.graph.edge.overrides.orphan.empty"));
                    return;
                  }

                  int page = ctx.optional("page").map(Integer.class::cast).orElse(1);
                  ListPage<RailEdgeOverrideRecord> pageResult = paginate(orphans, page, 10);

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.overrides.orphan.header",
                          Map.of(
                              "world", world.getName(), "count", String.valueOf(orphans.size()))));
                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.overrides.orphan.page",
                          Map.of(
                              "page",
                              String.valueOf(pageResult.page()),
                              "pages",
                              String.valueOf(pageResult.totalPages()))));

                  for (RailEdgeOverrideRecord record : pageResult.items()) {
                    EdgeId id = EdgeId.undirected(record.edgeId().a(), record.edgeId().b());
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.overrides.orphan.entry",
                            Map.of(
                                "a",
                                id.a().value(),
                                "b",
                                id.b().value(),
                                "updated_at",
                                record.updatedAt().toString())));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("edge")
            .literal("overrides")
            .literal("orphan")
            .literal("cleanup")
            .permission("fetarute.graph.edge")
            .flag(confirmFlag)
            .flag(nodeFilterFlag)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }

                  Optional<StorageProvider> providerOpt = requireStorageProvider(sender, locale);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();

                  List<RailEdgeOverrideRecord> overrides;
                  try {
                    overrides = provider.railEdgeOverrides().listByWorld(world.getUID());
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  Instant now = Instant.now();
                  RailGraphService service = plugin.getRailGraphService();
                  overrides =
                      cleanupExpiredTtlOverrides(provider, world.getUID(), service, now, overrides);
                  String nodeRaw = ctx.flags().getValue(nodeFilterFlag, null);
                  Optional<NodeId> nodeFilter =
                      nodeRaw == null || nodeRaw.isBlank()
                          ? Optional.empty()
                          : Optional.of(NodeId.of(normalizeNodeIdArg(nodeRaw)));

                  List<RailEdgeOverrideRecord> orphans =
                      EdgeOverrideLister.orphanOverrides(overrides, snapshotOpt.get().graph());
                  if (nodeFilter.isPresent()) {
                    orphans =
                        EdgeOverrideLister.filter(
                            orphans,
                            Instant.now(),
                            new EdgeOverrideLister.Query(
                                EdgeOverrideLister.Kind.ANY, true, nodeFilter));
                  }
                  if (orphans.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.graph.edge.overrides.orphan.empty"));
                    return;
                  }

                  List<RailEdgeOverrideRecord> orphansToDelete = List.copyOf(orphans);
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.overrides.orphan.cleanup.preview",
                            Map.of(
                                "world",
                                world.getName(),
                                "count",
                                String.valueOf(orphansToDelete.size()))));
                    sender.sendMessage(locale.component("command.common.confirm-required"));
                    return;
                  }

                  UUID worldId = world.getUID();
                  try {
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              for (RailEdgeOverrideRecord orphan : orphansToDelete) {
                                provider.railEdgeOverrides().delete(worldId, orphan.edgeId());
                              }
                              return null;
                            });
                  } catch (Exception ex) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.edge.speed.storage-failed",
                            Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
                    return;
                  }

                  if (service != null) {
                    for (RailEdgeOverrideRecord orphan : orphansToDelete) {
                      service.deleteEdgeOverride(worldId, orphan.edgeId());
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.edge.overrides.orphan.cleanup.success",
                          Map.of(
                              "world",
                              world.getName(),
                              "count",
                              String.valueOf(orphansToDelete.size()))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("query")
            .permission("fetarute.graph.query")
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant now = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(world.getUID(), snapshotOpt.get().graph(), now);

                  NodeId from = NodeId.of(normalizeNodeIdArg(ctx.get("from")));
                  NodeId to = NodeId.of(normalizeNodeIdArg(ctx.get("to")));
                  if (graph.findNode(from).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", from.value())));
                    return;
                  }
                  if (graph.findNode(to).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", to.value())));
                    return;
                  }

                  RailGraphPathFinder pathFinder = new RailGraphPathFinder();
                  Optional<RailGraphPath> pathOpt =
                      pathFinder.shortestPath(
                          graph, from, to, RailGraphPathFinder.Options.shortestDistance());
                  if (pathOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.unreachable",
                            Map.of("from", from.value(), "to", to.value())));
                    return;
                  }
                  RailGraphPath path = pathOpt.get();
                  double defaultSpeed = defaultSpeedBlocksPerSecond();
                  RailTravelTimeModel timeModel =
                      edgeSpeedTimeModel(world.getUID(), now, defaultSpeed);
                  Optional<Duration> etaOpt =
                      timeModel.pathTravelTime(graph, path.nodes(), path.edges());
                  String etaText = etaOpt.map(this::formatDuration).orElse("-");

                  String speedText = RailSpeed.ofBlocksPerSecond(defaultSpeed).formatWithAllUnits();
                  if (etaOpt.isPresent()
                      && path.totalLengthBlocks() > 0
                      && !etaOpt.get().isZero()
                      && etaOpt.get().toMillis() > 0) {
                    double seconds = etaOpt.get().toMillis() / 1000.0;
                    double avgSpeed = path.totalLengthBlocks() / seconds;
                    if (Double.isFinite(avgSpeed) && avgSpeed > 0.0) {
                      speedText = RailSpeed.ofBlocksPerSecond(avgSpeed).formatWithAllUnits();
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.graph.query.result",
                          Map.of(
                              "from",
                              from.value(),
                              "to",
                              to.value(),
                              "hops",
                              String.valueOf(path.edges().size()),
                              "distance_blocks",
                              String.valueOf(path.totalLengthBlocks()),
                              "speed",
                              speedText,
                              "eta",
                              etaText)));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("path")
            .permission("fetarute.graph.query")
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .optional("page", IntegerParser.integerParser())
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant nowForGraph = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(
                          world.getUID(), snapshotOpt.get().graph(), nowForGraph);

                  NodeId from = NodeId.of(normalizeNodeIdArg(ctx.get("from")));
                  NodeId to = NodeId.of(normalizeNodeIdArg(ctx.get("to")));
                  if (graph.findNode(from).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", from.value())));
                    return;
                  }
                  if (graph.findNode(to).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", to.value())));
                    return;
                  }

                  RailGraphPathFinder pathFinder = new RailGraphPathFinder();
                  Optional<RailGraphPath> pathOpt =
                      pathFinder.shortestPath(
                          graph, from, to, RailGraphPathFinder.Options.shortestDistance());
                  if (pathOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.unreachable",
                            Map.of("from", from.value(), "to", to.value())));
                    return;
                  }

                  int page = ctx.optional("page").map(Integer.class::cast).orElse(1);
                  sendPathPage(
                      locale, sender, world.getUID(), nowForGraph, graph, pathOpt.get(), page);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("graph")
            .literal("component")
            .permission("fetarute.graph.query")
            .required("node", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  World world = resolveWorld(sender);
                  if (world == null) {
                    sender.sendMessage("未找到可用世界");
                    return;
                  }
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                      requireGraphSnapshot(sender, world, locale);
                  if (snapshotOpt.isEmpty()) {
                    return;
                  }
                  Instant nowForGraph = Instant.now();
                  RailGraph graph =
                      graphWithEdgeOverrides(
                          world.getUID(), snapshotOpt.get().graph(), nowForGraph);

                  NodeId seed = NodeId.of(normalizeNodeIdArg(ctx.get("node")));
                  if (graph.findNode(seed).isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.graph.query.node-not-found", Map.of("node", seed.value())));
                    return;
                  }

                  ComponentStats stats = componentStats(graph, seed);
                  sender.sendMessage(
                      locale.component(
                          "command.graph.component.result",
                          Map.of(
                              "seed",
                              seed.value(),
                              "nodes",
                              String.valueOf(stats.nodeCount()),
                              "edges",
                              String.valueOf(stats.edgeCount()),
                              "blocked_edges",
                              String.valueOf(stats.blockedEdgeCount()))));
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

  /**
   * 输出 {@code /fta graph} 二级帮助。
   *
   * <p>帮助条目为可点击/可悬浮文本：
   *
   * <ul>
   *   <li>按权限过滤：仅展示调用者可用的子命令
   *   <li>按 sender 类型过滤：控制台不展示 player-only 子命令
   * </ul>
   */
  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.graph.help.header"));

    if (sender.hasPermission("fetarute.graph.build")) {
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-build"),
          ClickEvent.suggestCommand("/fta graph build "),
          locale.component("command.graph.help.hover-build"));
    }
    if (sender.hasPermission("fetarute.graph.continue")) {
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-continue"),
          ClickEvent.suggestCommand("/fta graph continue "),
          locale.component("command.graph.help.hover-continue"));
    }
    if (sender.hasPermission("fetarute.graph.status")) {
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-status"),
          ClickEvent.runCommand("/fta graph status"),
          locale.component("command.graph.help.hover-status"));
    }
    if (sender.hasPermission("fetarute.graph.cancel")) {
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-cancel"),
          ClickEvent.runCommand("/fta graph cancel"),
          locale.component("command.graph.help.hover-cancel"));
    }
    if (sender.hasPermission("fetarute.graph.info")) {
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-info"),
          ClickEvent.runCommand("/fta graph info"),
          locale.component("command.graph.help.hover-info"));
    }
    if (sender.hasPermission("fetarute.graph.delete")) {
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-delete"),
          ClickEvent.suggestCommand("/fta graph delete "),
          locale.component("command.graph.help.hover-delete"));
      if (sender instanceof Player) {
        sendHelpEntry(
            sender,
            locale.component("command.graph.help.entry-delete-here"),
            ClickEvent.runCommand("/fta graph delete here"),
            locale.component("command.graph.help.hover-delete-here"));
      }
    }
    if (sender.hasPermission("fetarute.graph.sign") && sender instanceof Player) {
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-sign-set"),
          ClickEvent.suggestCommand("/fta graph sign set "),
          locale.component("command.graph.help.hover-sign-set"));
    }
    if (sender.hasPermission("fetarute.graph.edge")) {
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-edge-speed"),
          ClickEvent.suggestCommand("/fta graph edge speed "),
          locale.component("command.graph.help.hover-edge-speed"));
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-edge-restrict"),
          ClickEvent.suggestCommand("/fta graph edge restrict "),
          locale.component("command.graph.help.hover-edge-restrict"));
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-edge-block"),
          ClickEvent.suggestCommand("/fta graph edge block "),
          locale.component("command.graph.help.hover-edge-block"));
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-edge-overrides"),
          ClickEvent.suggestCommand("/fta graph edge overrides orphan "),
          locale.component("command.graph.help.hover-edge-overrides"));
    }
    if (sender.hasPermission("fetarute.graph.query")) {
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-query"),
          ClickEvent.suggestCommand("/fta graph query "),
          locale.component("command.graph.help.hover-query"));
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-path"),
          ClickEvent.suggestCommand("/fta graph path "),
          locale.component("command.graph.help.hover-path"));
      sendHelpEntry(
          sender,
          locale.component("command.graph.help.entry-component"),
          ClickEvent.suggestCommand("/fta graph component "),
          locale.component("command.graph.help.hover-component"));
    }

    sender.sendMessage(locale.component("command.graph.help.footer"));
  }

  /** 输出一条可点击/可悬浮的帮助条目。 */
  private void sendHelpEntry(
      CommandSender sender, Component text, ClickEvent clickEvent, Component hoverText) {
    Objects.requireNonNull(sender, "sender");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(clickEvent, "clickEvent");
    Objects.requireNonNull(hoverText, "hoverText");
    sender.sendMessage(text.clickEvent(clickEvent).hoverEvent(HoverEvent.showText(hoverText)));
  }

  /**
   * 获取指定世界的调度图内存快照；若不存在则输出缺失/失效提示并返回 empty。
   *
   * <p>用于 query/path/component 等只读诊断命令，避免调用方重复处理 stale/missing 分支。
   */
  private Optional<RailGraphService.RailGraphSnapshot> requireGraphSnapshot(
      CommandSender sender, World world, LocaleManager locale) {
    Objects.requireNonNull(sender, "sender");
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(locale, "locale");
    RailGraphService service = plugin.getRailGraphService();
    if (service == null) {
      sender.sendMessage(locale.component("command.graph.info.missing"));
      return Optional.empty();
    }
    Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = service.getSnapshot(world);
    if (snapshotOpt.isPresent()) {
      return snapshotOpt;
    }
    service
        .getStaleState(world)
        .ifPresentOrElse(
            stale -> sender.sendMessage(staleInfoMessage(locale, stale)),
            () -> sender.sendMessage(locale.component("command.graph.info.missing")));
    return Optional.empty();
  }

  /** 获取存储后端 provider（不可用则输出提示并返回 empty）。 */
  private Optional<StorageProvider> requireStorageProvider(
      CommandSender sender, LocaleManager locale) {
    Objects.requireNonNull(sender, "sender");
    Objects.requireNonNull(locale, "locale");
    var storageManager = plugin.getStorageManager();
    if (storageManager == null || !storageManager.isReady()) {
      sender.sendMessage(locale.component("error.storage-unavailable"));
      return Optional.empty();
    }
    return storageManager.provider();
  }

  /**
   * NodeId 参数补全：从“当前世界内存快照”枚举节点并做前缀过滤。
   *
   * <p>约束与策略：
   *
   * <ul>
   *   <li>不访问存储后端：仅依赖内存快照，避免 tab 补全阻塞主线程/刷 SQL
   *   <li>候选数量最多 20 条：避免刷屏
   *   <li>快照不存在则仅返回占位符
   * </ul>
   */
  private SuggestionProvider<CommandSender> graphNodeIdSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input != null ? input.lastRemainingToken() : "";
          prefix = prefix.trim();
          if (!prefix.isEmpty() && (prefix.charAt(0) == '"' || prefix.charAt(0) == '\'')) {
            prefix = prefix.substring(1);
          }
          if (!prefix.isEmpty()
              && (prefix.charAt(prefix.length() - 1) == '"'
                  || prefix.charAt(prefix.length() - 1) == '\'')) {
            prefix = prefix.substring(0, prefix.length() - 1);
          }
          prefix = prefix.trim().toLowerCase(Locale.ROOT);

          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank() && placeholder != null && !placeholder.isBlank()) {
            suggestions.add(placeholder);
          }

          World world = resolveWorld(ctx.sender());
          if (world == null) {
            return suggestions;
          }
          RailGraphService service = plugin.getRailGraphService();
          if (service == null) {
            return suggestions;
          }
          Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = service.getSnapshot(world);
          if (snapshotOpt.isEmpty()) {
            return suggestions;
          }

          List<String> nodeIds = new ArrayList<>();
          for (RailNode node : snapshotOpt.get().graph().nodes()) {
            if (node == null || node.id() == null) {
              continue;
            }
            String raw = node.id().value();
            if (raw == null) {
              continue;
            }
            String lower = raw.toLowerCase(Locale.ROOT);
            if (prefix.isBlank() || lower.startsWith(prefix)) {
              nodeIds.add('"' + raw + '"');
            }
          }
          nodeIds.stream().distinct().sorted().limit(20).forEach(suggestions::add);
          return suggestions;
        });
  }

  /** 速度参数补全：输出占位符与常用单位示例（kmh/bps/bpt）。 */
  private static SuggestionProvider<CommandSender> speedSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input != null ? input.lastRemainingToken() : "";
          prefix = prefix.trim().toLowerCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add(placeholder);
          }
          List<String> candidates = List.of("80kmh", "40kmh", "8bps", "0.4bpt");
          for (String candidate : candidates) {
            if (prefix.isBlank() || candidate.startsWith(prefix)) {
              suggestions.add(candidate);
            }
          }
          return suggestions;
        });
  }

  /** TTL 参数补全：输出占位符与常用时长示例（支持 s/m/h/d）。 */
  private static SuggestionProvider<CommandSender> ttlSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input != null ? input.lastRemainingToken() : "";
          prefix = prefix.trim().toLowerCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add(placeholder);
          }
          List<String> candidates = List.of("90s", "5m", "15m", "1h");
          for (String candidate : candidates) {
            if (prefix.isBlank() || candidate.startsWith(prefix)) {
              suggestions.add(candidate);
            }
          }
          return suggestions;
        });
  }

  /**
   * 解析速度参数并统一为 blocks/s。
   *
   * <p>支持：{@code 80kmh} / {@code 8bps} / {@code 0.4bpt}；省略单位时默认视为 {@code kmh}。
   */
  private static Optional<RailSpeed> parseSpeedArg(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    Matcher matcher = SPEED_PATTERN.matcher(raw);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String valueText = matcher.group("value");
    String unit = matcher.group("unit");
    if (valueText == null || valueText.isBlank()) {
      return Optional.empty();
    }
    double value;
    try {
      value = Double.parseDouble(valueText);
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
    if (!Double.isFinite(value) || value <= 0.0) {
      return Optional.empty();
    }

    String normalizedUnit = unit != null ? unit.trim().toLowerCase(Locale.ROOT) : "kmh";
    try {
      return switch (normalizedUnit) {
        case "kmh", "km/h", "kph" -> Optional.of(RailSpeed.ofKilometersPerHour(value));
        case "bps" -> Optional.of(RailSpeed.ofBlocksPerSecond(value));
        case "bpt" -> Optional.of(RailSpeed.ofBlocksPerTick(value));
        default -> Optional.empty();
      };
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  /** 解析 TTL 参数：支持 {@code 90s}、{@code 1m}、{@code 2h}、{@code 1d}，以及组合形式（如 {@code 1h30m}）。 */
  private static Optional<Duration> parseTtlArg(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    Matcher matcher = TTL_PATTERN.matcher(trimmed);
    int index = 0;
    Duration total = Duration.ZERO;
    boolean matchedAny = false;
    while (matcher.find()) {
      if (matcher.start() != index) {
        return Optional.empty();
      }
      matchedAny = true;
      long value;
      try {
        value = Long.parseLong(matcher.group(1));
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }
      if (value <= 0) {
        return Optional.empty();
      }
      char unit = matcher.group(2).toLowerCase(Locale.ROOT).charAt(0);
      try {
        total =
            switch (unit) {
              case 's' -> total.plusSeconds(value);
              case 'm' -> total.plusMinutes(value);
              case 'h' -> total.plusHours(value);
              case 'd' -> total.plusDays(value);
              default -> total;
            };
      } catch (ArithmeticException ex) {
        return Optional.empty();
      }
      index = matcher.end();
    }
    if (!matchedAny || index != trimmed.length()) {
      return Optional.empty();
    }
    return total.isZero() ? Optional.empty() : Optional.of(total);
  }

  /** 判断图中是否存在某条“直接区间边”。 */
  private static boolean graphHasEdge(RailGraph graph, EdgeId edgeId) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(edgeId, "edgeId");
    for (RailEdge edge : graph.edgesFrom(edgeId.a())) {
      if (edge != null && edge.id() != null && edge.id().equals(edgeId)) {
        return true;
      }
    }
    return false;
  }

  private static RailEdge findEdgeOrNull(RailGraph graph, EdgeId edgeId) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(edgeId, "edgeId");
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    for (RailEdge edge : graph.edgesFrom(normalized.a())) {
      if (edge == null) {
        continue;
      }
      EdgeId id = edge.id();
      EdgeId candidate;
      if (id != null) {
        candidate = EdgeId.undirected(id.a(), id.b());
      } else {
        NodeId from = edge.from();
        NodeId to = edge.to();
        if (from == null || to == null) {
          continue;
        }
        candidate = EdgeId.undirected(from, to);
      }
      if (candidate.equals(normalized)) {
        return edge;
      }
    }
    return null;
  }

  /**
   * 读取图查询的默认速度（blocks/s）。
   *
   * <p>用于 {@code /fta graph query/path} 的 ETA 估算：ETA = shortestDistanceBlocks / speed。
   */
  private double defaultSpeedBlocksPerSecond() {
    ConfigManager.GraphSettings defaults = ConfigManager.GraphSettings.defaults();
    ConfigManager configManager = plugin.getConfigManager();
    if (configManager == null) {
      return defaults.defaultSpeedBlocksPerSecond();
    }
    ConfigManager.ConfigView view = configManager.current();
    if (view == null || view.graphSettings() == null) {
      return defaults.defaultSpeedBlocksPerSecond();
    }
    double speed = view.graphSettings().defaultSpeedBlocksPerSecond();
    if (!Double.isFinite(speed) || speed <= 0.0) {
      return defaults.defaultSpeedBlocksPerSecond();
    }
    return speed;
  }

  /**
   * 将 Duration 格式化为简短中文文本。
   *
   * <p>输出示例：{@code 59秒}、{@code 2分10秒}、{@code 1小时5分}。
   */
  private String formatDuration(Duration duration) {
    if (duration == null || duration.isNegative()) {
      return "-";
    }
    long seconds = duration.toSeconds();
    if (seconds < 60) {
      return seconds + "秒";
    }
    long minutes = seconds / 60;
    long remainSeconds = seconds % 60;
    if (minutes < 60) {
      return remainSeconds == 0 ? minutes + "分" : minutes + "分" + remainSeconds + "秒";
    }
    long hours = minutes / 60;
    long remainMinutes = minutes % 60;
    return remainMinutes == 0 ? hours + "小时" : hours + "小时" + remainMinutes + "分";
  }

  private Optional<RailEdgeOverrideRecord> cleanupExpiredTtlOverride(
      StorageProvider provider,
      UUID worldId,
      RailGraphService service,
      Instant now,
      RailEdgeOverrideRecord record) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(record, "record");

    if (!worldId.equals(record.worldId())) {
      return Optional.of(record);
    }

    boolean changed = false;
    OptionalDouble speedLimit = record.speedLimitBlocksPerSecond();
    OptionalDouble tempSpeed = record.tempSpeedLimitBlocksPerSecond();
    Optional<Instant> tempUntil = record.tempSpeedLimitUntil();
    boolean blockedManual = record.blockedManual();
    Optional<Instant> blockedUntil = record.blockedUntil();

    if (tempUntil.isPresent() && !now.isBefore(tempUntil.get())) {
      tempSpeed = OptionalDouble.empty();
      tempUntil = Optional.empty();
      changed = true;
    }

    if (blockedUntil.isPresent() && !now.isBefore(blockedUntil.get())) {
      blockedUntil = Optional.empty();
      changed = true;
    }

    if (!changed) {
      return Optional.of(record);
    }

    RailEdgeOverrideRecord updated =
        new RailEdgeOverrideRecord(
            worldId,
            record.edgeId(),
            speedLimit,
            tempSpeed,
            tempUntil,
            blockedManual,
            blockedUntil,
            record.updatedAt());
    try {
      provider
          .transactionManager()
          .execute(
              () -> {
                if (updated.isEmpty()) {
                  provider.railEdgeOverrides().delete(worldId, record.edgeId());
                } else {
                  provider.railEdgeOverrides().upsert(updated);
                }
                return null;
              });
    } catch (Exception ex) {
      return Optional.of(record);
    }

    if (service != null) {
      if (updated.isEmpty()) {
        service.deleteEdgeOverride(worldId, record.edgeId());
      } else {
        service.putEdgeOverride(updated);
      }
    }
    return updated.isEmpty() ? Optional.empty() : Optional.of(updated);
  }

  /**
   * 清理已过期的 TTL 运维覆盖，避免 rail_edge_overrides 由于临时限速/临时封锁堆积。
   *
   * <p>策略：仅在命令查询/list/get 场景做“被动清理”，不会在运行时循环中触发写库。
   */
  private List<RailEdgeOverrideRecord> cleanupExpiredTtlOverrides(
      StorageProvider provider,
      UUID worldId,
      RailGraphService service,
      Instant now,
      List<RailEdgeOverrideRecord> overrides) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(overrides, "overrides");

    List<RailEdgeOverrideRecord> upserts = new ArrayList<>();
    List<EdgeId> deletes = new ArrayList<>();
    List<RailEdgeOverrideRecord> normalized = new ArrayList<>(overrides.size());

    for (RailEdgeOverrideRecord record : overrides) {
      if (record == null || record.edgeId() == null || record.worldId() == null) {
        continue;
      }
      if (!worldId.equals(record.worldId())) {
        normalized.add(record);
        continue;
      }

      boolean changed = false;
      OptionalDouble speedLimit = record.speedLimitBlocksPerSecond();
      OptionalDouble tempSpeed = record.tempSpeedLimitBlocksPerSecond();
      Optional<Instant> tempUntil = record.tempSpeedLimitUntil();
      boolean blockedManual = record.blockedManual();
      Optional<Instant> blockedUntil = record.blockedUntil();

      if (tempUntil.isPresent() && !now.isBefore(tempUntil.get())) {
        if (tempSpeed.isPresent() || tempUntil.isPresent()) {
          tempSpeed = OptionalDouble.empty();
          tempUntil = Optional.empty();
          changed = true;
        }
      }

      if (blockedUntil.isPresent() && !now.isBefore(blockedUntil.get())) {
        blockedUntil = Optional.empty();
        changed = true;
      }

      RailEdgeOverrideRecord effective = record;
      if (changed) {
        effective =
            new RailEdgeOverrideRecord(
                worldId,
                record.edgeId(),
                speedLimit,
                tempSpeed,
                tempUntil,
                blockedManual,
                blockedUntil,
                record.updatedAt());
        if (effective.isEmpty()) {
          deletes.add(record.edgeId());
        } else {
          upserts.add(effective);
        }
      }

      if (!effective.isEmpty()) {
        normalized.add(effective);
      }
    }

    if (upserts.isEmpty() && deletes.isEmpty()) {
      return overrides;
    }

    List<RailEdgeOverrideRecord> upsertsCopy = List.copyOf(upserts);
    List<EdgeId> deletesCopy = List.copyOf(deletes);
    try {
      provider
          .transactionManager()
          .execute(
              () -> {
                for (RailEdgeOverrideRecord upsert : upsertsCopy) {
                  provider.railEdgeOverrides().upsert(upsert);
                }
                for (EdgeId delete : deletesCopy) {
                  provider.railEdgeOverrides().delete(worldId, delete);
                }
                return null;
              });
    } catch (Exception ex) {
      // 清理失败不影响读操作；仅避免堆积的 best-effort。
      return overrides;
    }

    if (service != null) {
      for (RailEdgeOverrideRecord upsert : upsertsCopy) {
        service.putEdgeOverride(upsert);
      }
      for (EdgeId delete : deletesCopy) {
        service.deleteEdgeOverride(worldId, delete);
      }
    }
    return normalized;
  }

  /** 简单分页结果。 */
  private record ListPage<T>(int page, int totalPages, List<T> items) {}

  private static <T> ListPage<T> paginate(List<T> items, int page, int pageSize) {
    Objects.requireNonNull(items, "items");
    int effectivePageSize = Math.max(1, pageSize);
    int totalEntries = items.size();
    int totalPages = Math.max(1, (totalEntries + effectivePageSize - 1) / effectivePageSize);
    int effectivePage = Math.max(1, Math.min(page, totalPages));
    int startIndex = (effectivePage - 1) * effectivePageSize;
    int endIndex = Math.min(totalEntries, startIndex + effectivePageSize);
    return new ListPage<>(effectivePage, totalPages, items.subList(startIndex, endIndex));
  }

  /**
   * 分页输出最短路径，并为玩家提供“点击传送”与“点击翻页”交互。
   *
   * <p>注意：路径中节点坐标来自图快照记录的 sign 坐标，不保证在轨道上；仅用于诊断定位。
   */
  private void sendPathPage(
      LocaleManager locale,
      CommandSender sender,
      UUID worldId,
      Instant now,
      RailGraph graph,
      RailGraphPath path,
      int page) {
    Objects.requireNonNull(locale, "locale");
    Objects.requireNonNull(sender, "sender");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(path, "path");

    int pageSize = 10;
    int totalEntries = path.nodes().size();
    int totalPages = Math.max(1, (totalEntries + pageSize - 1) / pageSize);
    int effectivePage = Math.max(1, Math.min(page, totalPages));
    int startIndex = (effectivePage - 1) * pageSize;
    int endIndex = Math.min(totalEntries, startIndex + pageSize);

    double defaultSpeed = defaultSpeedBlocksPerSecond();
    RailTravelTimeModel timeModel = edgeSpeedTimeModel(worldId, now, defaultSpeed);
    Optional<Duration> etaOpt = timeModel.pathTravelTime(graph, path.nodes(), path.edges());
    String etaText = etaOpt.map(this::formatDuration).orElse("-");

    sender.sendMessage(
        locale.component(
            "command.graph.path.header",
            Map.of(
                "from",
                path.from().value(),
                "to",
                path.to().value(),
                "hops",
                String.valueOf(path.edges().size()),
                "distance_blocks",
                String.valueOf(path.totalLengthBlocks()),
                "eta",
                etaText)));
    sender.sendMessage(
        locale.component(
            "command.graph.path.page",
            Map.of("page", String.valueOf(effectivePage), "pages", String.valueOf(totalPages))));

    for (int i = startIndex; i < endIndex; i++) {
      NodeId nodeId = path.nodes().get(i);
      String idText = nodeId != null ? nodeId.value() : "-";

      String locationText = "-";
      net.kyori.adventure.text.Component locationComponent =
          net.kyori.adventure.text.Component.text(locationText);
      Optional<RailNode> nodeOpt = nodeId != null ? graph.findNode(nodeId) : Optional.empty();
      if (nodeOpt.isPresent()) {
        Vector pos = nodeOpt.get().worldPosition();
        if (pos != null) {
          int x = pos.getBlockX();
          int y = pos.getBlockY();
          int z = pos.getBlockZ();
          locationText = x + " " + y + " " + z;
          locationComponent = net.kyori.adventure.text.Component.text(locationText);
          if (sender instanceof Player) {
            String tp = "/tp " + x + " " + y + " " + z;
            locationComponent =
                locationComponent
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(tp))
                    .hoverEvent(
                        net.kyori.adventure.text.event.HoverEvent.showText(
                            net.kyori.adventure.text.Component.text(tp)));
          }
        }
      }

      sender.sendMessage(
          locale.component(
              "command.graph.path.entry",
              net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.builder()
                  .resolver(
                      net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                          "seq", String.valueOf(i + 1)))
                  .resolver(
                      net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                          "id", idText))
                  .resolver(
                      net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(
                          "location", locationComponent))
                  .build()));
    }

    sender.sendMessage(buildPathNav(locale, path, effectivePage, totalPages));
  }

  /** 构建 {@code /fta graph path} 的分页导航（上一页/下一页）。 */
  private Component buildPathNav(
      LocaleManager locale, RailGraphPath path, int page, int totalPages) {
    net.kyori.adventure.text.Component pageText =
        net.kyori.adventure.text.Component.text("")
            .append(locale.component("command.graph.path.nav.hint"));

    net.kyori.adventure.text.Component prev =
        page > 1
            ? locale
                .component("command.graph.path.nav.prev")
                .clickEvent(
                    net.kyori.adventure.text.event.ClickEvent.runCommand(
                        "/fta graph path "
                            + path.from().value()
                            + " "
                            + path.to().value()
                            + " "
                            + (page - 1)))
            : locale.component("command.graph.path.nav.prev-disabled");
    net.kyori.adventure.text.Component next =
        page < totalPages
            ? locale
                .component("command.graph.path.nav.next")
                .clickEvent(
                    net.kyori.adventure.text.event.ClickEvent.runCommand(
                        "/fta graph path "
                            + path.from().value()
                            + " "
                            + path.to().value()
                            + " "
                            + (page + 1)))
            : locale.component("command.graph.path.nav.next-disabled");

    return net.kyori.adventure.text.Component.empty()
        .append(prev)
        .append(net.kyori.adventure.text.Component.space())
        .append(next)
        .append(net.kyori.adventure.text.Component.space())
        .append(pageText);
  }

  /**
   * 以“按边限速优先、否则默认速度”的规则构建 ETA 模型。
   *
   * <p>速度来源：edge.baseSpeedLimit（未来可由建图阶段填充）→ rail_edge_overrides.speed_limit_bps →
   * rail_edge_overrides.temp_speed_limit_bps（若 TTL 仍有效）→ graph.default-speed-blocks-per-second。
   */
  private RailTravelTimeModel edgeSpeedTimeModel(
      UUID worldId, Instant now, double defaultSpeedBlocksPerSecond) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(now, "now");
    if (!Double.isFinite(defaultSpeedBlocksPerSecond) || defaultSpeedBlocksPerSecond <= 0.0) {
      throw new IllegalArgumentException("defaultSpeedBlocksPerSecond 必须为正数");
    }

    RailGraphService service = plugin.getRailGraphService();
    return (graph, edge, from, to) -> {
      if (edge == null) {
        return Optional.empty();
      }
      int lengthBlocks = edge.lengthBlocks();
      if (lengthBlocks <= 0) {
        return Optional.empty();
      }

      double speedBlocksPerSecond;
      if (service != null) {
        speedBlocksPerSecond =
            service.effectiveSpeedLimitBlocksPerSecond(
                worldId, edge, now, defaultSpeedBlocksPerSecond);
      } else {
        double baseFromEdge = edge.baseSpeedLimit();
        speedBlocksPerSecond =
            Double.isFinite(baseFromEdge) && baseFromEdge > 0.0
                ? baseFromEdge
                : defaultSpeedBlocksPerSecond;
      }
      if (!Double.isFinite(speedBlocksPerSecond) || speedBlocksPerSecond <= 0.0) {
        return Optional.empty();
      }

      double seconds = lengthBlocks / speedBlocksPerSecond;
      if (!Double.isFinite(seconds) || seconds < 0.0) {
        return Optional.empty();
      }
      long millis = (long) Math.round(seconds * 1000.0);
      if (millis < 0) {
        return Optional.empty();
      }
      return Optional.of(Duration.ofMillis(millis));
    };
  }

  private RailGraph graphWithEdgeOverrides(UUID worldId, RailGraph graph, Instant now) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(now, "now");

    RailGraphService service = plugin.getRailGraphService();
    if (service == null) {
      return graph;
    }
    Map<EdgeId, RailEdgeOverrideRecord> overrides = service.edgeOverrides(worldId);
    if (overrides.isEmpty()) {
      return graph;
    }
    return new EdgeOverrideRailGraph(graph, overrides, now);
  }

  /**
   * 计算以 seed 为起点的连通分量统计信息。
   *
   * <p>实现为基于图邻接表的 BFS：
   *
   * <ul>
   *   <li>nodeCount：分量内节点数
   *   <li>edgeCount：分量内边数（按 EdgeId 去重）
   *   <li>blockedEdgeCount：其中被标记为 blocked 的边数
   * </ul>
   */
  private static ComponentStats componentStats(RailGraph graph, NodeId seed) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(seed, "seed");

    Set<NodeId> visited = new HashSet<>();
    ArrayDeque<NodeId> queue = new ArrayDeque<>();
    visited.add(seed);
    queue.add(seed);

    Set<org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId> edges = new HashSet<>();
    while (!queue.isEmpty()) {
      NodeId current = queue.poll();
      for (RailEdge edge : graph.edgesFrom(current)) {
        if (edge == null) {
          continue;
        }
        edges.add(edge.id());
        NodeId neighbor = current.equals(edge.from()) ? edge.to() : edge.from();
        if (neighbor != null && visited.add(neighbor)) {
          queue.add(neighbor);
        }
      }
    }

    int blockedEdges = 0;
    for (org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId edgeId : edges) {
      if (edgeId != null && graph.isBlocked(edgeId)) {
        blockedEdges++;
      }
    }
    return new ComponentStats(visited.size(), edges.size(), blockedEdges);
  }

  /** 连通分量统计结果（nodes/edges/blockedEdges）。 */
  private record ComponentStats(int nodeCount, int edgeCount, int blockedEdgeCount) {}

  /**
   * 写入节点牌子内容：统一填充 {@code [train]} 头 + action + nodeId。
   *
   * <p>校验规则：
   *
   * <ul>
   *   <li>{@code waypoint}：只接受区间点/咽喉（5 段 interval 或 5 段 S/D throat）
   *   <li>{@code autostation}：只接受站点本体（4 段 S）
   *   <li>{@code depot}：只接受车库本体（4 段 D）
   * </ul>
   *
   * @param actionName SignAction 名称（写入牌子第 2 行）
   * @param nodeType 注册用的节点类型（用于复用解析器）
   */
  private void handleSignSet(
      Player player,
      LocaleManager locale,
      String actionName,
      NodeType nodeType,
      String rawId,
      String invalidLocaleKey,
      String successLocaleKey) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(locale, "locale");
    Objects.requireNonNull(actionName, "actionName");
    Objects.requireNonNull(nodeType, "nodeType");
    Objects.requireNonNull(invalidLocaleKey, "invalidLocaleKey");
    Objects.requireNonNull(successLocaleKey, "successLocaleKey");

    String normalizedId = normalizeNodeIdArg(rawId);
    Optional<SignNodeDefinition> defOpt =
        SignTextParser.parseWaypointLike(normalizedId, nodeType)
            .filter(
                definition ->
                    definition
                        .waypointMetadata()
                        .map(
                            metadata -> {
                              WaypointKind kind = metadata.kind();
                              if (kind == null) {
                                return false;
                              }
                              if (nodeType == NodeType.WAYPOINT) {
                                return kind == WaypointKind.INTERVAL
                                    || kind == WaypointKind.STATION_THROAT
                                    || kind == WaypointKind.DEPOT_THROAT;
                              }
                              if (nodeType == NodeType.STATION) {
                                return kind == WaypointKind.STATION;
                              }
                              if (nodeType == NodeType.DEPOT) {
                                return kind == WaypointKind.DEPOT;
                              }
                              return false;
                            })
                        .orElse(false));
    if (defOpt.isEmpty()) {
      player.sendMessage(locale.component(invalidLocaleKey, Map.of("id", normalizedId)));
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
    side.line(1, Component.text(actionName));
    side.line(2, Component.text(defOpt.get().nodeId().value()));
    side.line(3, Component.empty());
    sign.update(true, false);

    player.sendMessage(locale.component(successLocaleKey, Map.of("id", normalizedId)));
  }

  /**
   * 规范化 NodeId 命令参数：去掉首尾空白，并容忍一对包裹引号。
   *
   * <p>原因：玩家侧命令输入有时会尝试用引号包裹含 {@code :} 的节点 ID；这里在不改变实际 NodeId 语义的前提下做一次兼容处理。
   */
  private static String normalizeNodeIdArg(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    if (trimmed.length() >= 2) {
      char first = trimmed.charAt(0);
      char last = trimmed.charAt(trimmed.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return trimmed.substring(1, trimmed.length() - 1).trim();
      }
    }
    return trimmed;
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
                provider.railEdgeOverrides().deleteWorld(worldId);
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
