package org.fetarute.fetaruteTCAddon.dispatcher.graph.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService.RailGraphSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService.RailGraphStaleState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeStorageSynchronizer;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * rail_nodes 增量同步：在建牌/拆牌时按单节点 upsert/delete 更新存储，并在节点集合变化时标记旧图失效。
 *
 * <p>注意：这里只同步“节点牌子类节点”（waypoint/autostation/depot）。轨道拓扑/边变化仍需运维执行 /fta graph build。
 *
 * <p>失效分两级：变化的节点都没有交路在用（{@link GraphNodeUsage}）时只打标记、旧图继续在用；任何一个在用或判不了， 旧图移出内存。两种都会通知 {@link
 * GraphStaleListener}。
 *
 * <p>每次增删只做单行读写；签名比对（整表读 + SHA）与在用判定按世界合并到下一 tick 做一次。WorldEdit 一次拆掉 K 块牌子时， 开销是 N log N + K 而不是 K
 * 次整表比对。
 *
 * <p>写库失败时牌子与内存登记已经变了、rail_nodes 没跟上：控制台告警，并按同样的在用规则把该世界的图标为失效（{@link
 * #UNSYNCED_SIGNATURE}）。这个标记在重建图之前不会因为之后某次签名比对一致而撤销——库里签名一致不代表与世界里的牌子一致； 它也写进库里的快照记录，重启后照样判为失效。
 */
public final class RailNodeIncrementalSync implements SignNodeStorageSynchronizer {

  /** 写库失败标出的失效状态里"当前签名"一栏：库读不了，不知道真实签名。 */
  static final String UNSYNCED_SIGNATURE = "未同步";

  private static final Logger LOGGER = Logger.getLogger("FetaruteTCAddon");

  private final StorageManager storageManager;
  private final RailGraphService railGraphService;
  private final Consumer<String> debugLogger;
  private final GraphStaleListener staleListener;
  private final GraphNodeUsage nodeUsage;
  private final Consumer<Runnable> checkScheduler;
  private final Map<UUID, PendingCheck> pendingChecks = new LinkedHashMap<>();

  /** 写库失败过、图被标为失效且尚未重建的世界。 */
  private final Set<UUID> unsyncedWorlds = new HashSet<>();

  /** 写库失败后，"快照已失效"还没能写进库的世界。 */
  private final Set<UUID> unpersistedWorlds = new HashSet<>();

  public RailNodeIncrementalSync(
      StorageManager storageManager,
      RailGraphService railGraphService,
      Consumer<String> debugLogger) {
    this(storageManager, railGraphService, debugLogger, GraphStaleListener.noop());
  }

  public RailNodeIncrementalSync(
      StorageManager storageManager,
      RailGraphService railGraphService,
      Consumer<String> debugLogger,
      GraphStaleListener staleListener) {
    this(
        storageManager, railGraphService, debugLogger, staleListener, GraphNodeUsage.alwaysInUse());
  }

  public RailNodeIncrementalSync(
      StorageManager storageManager,
      RailGraphService railGraphService,
      Consumer<String> debugLogger,
      GraphStaleListener staleListener,
      GraphNodeUsage nodeUsage) {
    this(storageManager, railGraphService, debugLogger, staleListener, nodeUsage, Runnable::run);
  }

  /**
   * @param checkScheduler 签名比对的调度：生产环境排到下一 tick，把同一 tick 的变更合并成一次；测试可直接执行
   */
  public RailNodeIncrementalSync(
      StorageManager storageManager,
      RailGraphService railGraphService,
      Consumer<String> debugLogger,
      GraphStaleListener staleListener,
      GraphNodeUsage nodeUsage,
      Consumer<Runnable> checkScheduler) {
    this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.staleListener = staleListener != null ? staleListener : GraphStaleListener.noop();
    this.nodeUsage = nodeUsage != null ? nodeUsage : GraphNodeUsage.alwaysInUse();
    this.checkScheduler = checkScheduler != null ? checkScheduler : Runnable::run;
  }

  @Override
  public void upsert(Block block, SignNodeDefinition definition) {
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(definition, "definition");
    World world = block.getWorld();
    UUID worldId = world.getUID();
    Location location = block.getLocation();
    RailNodeRecord record =
        new RailNodeRecord(
            worldId,
            definition.nodeId(),
            definition.nodeType(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ(),
            definition.trainCartsDestination(),
            definition.waypointMetadata());

    provider()
        .ifPresent(
            provider -> {
              try {
                Written written =
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              List<GraphStaleListener.NodeChange> changes = new ArrayList<>();
                              boolean unchanged = false;
                              for (RailNodeRecord existing :
                                  provider
                                      .railNodes()
                                      .listByPosition(
                                          worldId, record.x(), record.y(), record.z())) {
                                if (existing.nodeId().equals(record.nodeId())) {
                                  unchanged = true;
                                } else {
                                  // 同一位置原来的节点被顶掉：它的移除也要判在不在用。
                                  changes.add(removalOf(existing));
                                }
                              }
                              changes.add(
                                  new GraphStaleListener.NodeChange(
                                      definition, record.x(), record.y(), record.z(), false));
                              provider
                                  .railNodes()
                                  .deleteByPosition(worldId, record.x(), record.y(), record.z());
                              provider.railNodes().upsert(record);
                              return new Written(!unchanged || changes.size() > 1, changes);
                            });
                enqueueCheck(world, written);
              } catch (Exception ex) {
                markUnsynced(
                    provider,
                    world,
                    "upsert",
                    record.nodeId(),
                    changesAfterFailedUpsert(provider, record, definition),
                    ex);
              }
            });
  }

  @Override
  public void delete(Block block, SignNodeDefinition definition) {
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(definition, "definition");
    World world = block.getWorld();
    UUID worldId = world.getUID();
    GraphStaleListener.NodeChange change =
        new GraphStaleListener.NodeChange(
            definition, block.getX(), block.getY(), block.getZ(), true);

    provider()
        .ifPresent(
            provider -> {
              try {
                int removed =
                    provider
                        .transactionManager()
                        .execute(() -> provider.railNodes().delete(worldId, definition.nodeId()));
                enqueueCheck(world, new Written(removed > 0, List.of(change)));
              } catch (Exception ex) {
                markUnsynced(provider, world, "delete", definition.nodeId(), List.of(change), ex);
              }
            });
  }

  /** 写库失败后要报的变更：新节点，加上同一位置原来的节点——它在世界里被顶掉了，也要判在不在用。原来的节点读不到就判不了，按在用处理。 */
  private List<GraphStaleListener.NodeChange> changesAfterFailedUpsert(
      StorageProvider provider, RailNodeRecord record, SignNodeDefinition definition) {
    GraphStaleListener.NodeChange added =
        new GraphStaleListener.NodeChange(definition, record.x(), record.y(), record.z(), false);
    List<GraphStaleListener.NodeChange> changes = new ArrayList<>();
    try {
      for (RailNodeRecord existing :
          provider
              .railNodes()
              .listByPosition(record.worldId(), record.x(), record.y(), record.z())) {
        if (!existing.nodeId().equals(record.nodeId())) {
          changes.add(removalOf(existing));
        }
      }
    } catch (RuntimeException readFailure) {
      added = added.withUsage(Optional.of("同一位置原来的节点读不到"));
    }
    changes.add(added);
    return changes;
  }

  /**
   * 写库失败：告警，并把该世界的图标为失效。
   *
   * <p>在用判定与签名不一致时相同：变化的节点都没有交路在用就只打标记、旧图继续在用，否则旧图移出内存。该世界还没有图时只告警——重建图会按世界里的牌子重写 rail_nodes。
   *
   * <p>失效同时写进库里的快照记录（{@link #persistUnsynced}），重启后从库载入时同样判为失效。
   */
  private void markUnsynced(
      StorageProvider provider,
      World world,
      String op,
      NodeId nodeId,
      List<GraphStaleListener.NodeChange> changes,
      Exception failure) {
    LOGGER.warning(
        "[FTA] 节点牌子写入存储失败，存储中的节点已与世界中的牌子不一致: op="
            + op
            + " node="
            + nodeId.value()
            + " world="
            + world.getName()
            + " error="
            + failure.getClass().getSimpleName()
            + ":"
            + failure.getMessage()
            + "；请执行 /fta graph build 重建调度图");
    try {
      provider
          .transactionManager()
          .execute(
              () -> {
                persistUnsynced(provider, world.getUID());
                return null;
              });
    } catch (RuntimeException persistFailure) {
      // 库还写不进去：下次签名比对时先补写。
      unpersistedWorlds.add(world.getUID());
    }
    Optional<RailGraphSnapshot> snapshot = railGraphService.getSnapshot(world);
    Optional<RailGraphStaleState> existing = railGraphService.getStaleState(world);
    if (snapshot.isEmpty() && existing.isEmpty()) {
      return;
    }
    unsyncedWorlds.add(world.getUID());
    GraphStaleListener.Level before = level(world);
    boolean snapshotServed = before != GraphStaleListener.Level.EVICTED && snapshot.isPresent();
    List<GraphStaleListener.NodeChange> judged = new ArrayList<>(changes.size());
    boolean retain = snapshotServed;
    for (GraphStaleListener.NodeChange change : changes) {
      GraphStaleListener.NodeChange withUsage =
          snapshotServed && change.usage().isEmpty()
              ? change.withUsage(usageOf(world, change))
              : change;
      retain &= withUsage.usage().isEmpty();
      judged.add(withUsage);
    }
    railGraphService.markStale(world, unsyncedStaleState(existing, snapshot, retain));
    GraphStaleListener.Level after = level(world);
    for (GraphStaleListener.NodeChange change : judged) {
      notifyListener(() -> staleListener.onStale(world, change, before, after));
    }
  }

  /**
   * 把库里的快照签名改成 {@link #UNSYNCED_SIGNATURE}：库里的快照与 rail_nodes 从此对不上，重启载入与之后的签名比对都判为失效，直到重建图整条重写快照。
   */
  private static void persistUnsynced(StorageProvider provider, UUID worldId) {
    RailGraphSnapshotRepository snapshots = provider.railGraphSnapshots();
    snapshots
        .findByWorld(worldId)
        .ifPresent(
            snapshot ->
                snapshots.save(
                    new RailGraphSnapshotRecord(
                        worldId,
                        snapshot.builtAt(),
                        snapshot.nodeCount(),
                        snapshot.edgeCount(),
                        UNSYNCED_SIGNATURE)));
  }

  private static RailGraphStaleState unsyncedStaleState(
      Optional<RailGraphStaleState> existing,
      Optional<RailGraphSnapshot> snapshot,
      boolean retain) {
    if (existing.isPresent()) {
      RailGraphStaleState state = existing.get();
      return new RailGraphStaleState(
          state.builtAt(),
          state.snapshotSignature(),
          state.currentSignature(),
          state.snapshotNodeCount(),
          state.snapshotEdgeCount(),
          state.currentNodeCount(),
          retain);
    }
    RailGraph graph = snapshot.orElseThrow().graph();
    int nodeCount = graph.nodes().size();
    return new RailGraphStaleState(
        snapshot.get().builtAt(),
        "",
        UNSYNCED_SIGNATURE,
        nodeCount,
        graph.edges().size(),
        nodeCount,
        retain);
  }

  /** 同一世界的变更攒到一起，下一 tick 做一次签名比对。 */
  private void enqueueCheck(World world, Written written) {
    UUID worldId = world.getUID();
    PendingCheck pending = pendingChecks.get(worldId);
    boolean first = pending == null;
    if (first) {
      pending = new PendingCheck(world);
      pendingChecks.put(worldId, pending);
    }
    pending.add(written);
    if (!first) {
      return;
    }
    try {
      checkScheduler.accept(() -> runCheck(worldId));
    } catch (RuntimeException ex) {
      // 插件停用期间调度器拒绝新任务：就地比对。
      runCheck(worldId);
    }
  }

  private void runCheck(UUID worldId) {
    PendingCheck pending = pendingChecks.remove(worldId);
    if (pending == null) {
      return;
    }
    provider()
        .ifPresent(
            provider -> {
              try {
                SignatureCheckResult check =
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              if (unpersistedWorlds.contains(worldId)) {
                                persistUnsynced(provider, worldId);
                              }
                              return checkSignature(provider, worldId);
                            });
                unpersistedWorlds.remove(worldId);
                // 这一批里有真实变更时只报真实的那些；全是空操作（如重复删除）时才报它们，免得静默移出旧图。
                applySignatureCheck(
                    provider,
                    pending.world,
                    new WriteResult(
                        pending.changed,
                        List.copyOf(pending.changed ? pending.changes : pending.noopChanges),
                        check));
              } catch (Exception ex) {
                debugLogger.accept(
                    "rail_nodes 签名比对失败: world=" + worldId + " msg=" + ex.getMessage());
              }
            });
  }

  private Optional<StorageProvider> provider() {
    if (!storageManager.isReady()) {
      return Optional.empty();
    }
    return storageManager.provider();
  }

  private SignatureCheckResult checkSignature(StorageProvider provider, UUID worldId) {
    Optional<RailGraphSnapshotRecord> snapshotOpt =
        provider.railGraphSnapshots().findByWorld(worldId);
    if (snapshotOpt.isEmpty()) {
      return SignatureCheckResult.noSnapshot();
    }
    RailGraphSnapshotRecord snapshot = snapshotOpt.get();
    List<RailNodeRecord> nodes = provider.railNodes().listByWorld(worldId);
    String currentSignature = RailGraphSignature.signatureForNodes(nodes);
    boolean mismatch =
        !snapshot.nodeSignature().isEmpty()
            && !currentSignature.isEmpty()
            && !snapshot.nodeSignature().equals(currentSignature);
    return new SignatureCheckResult(snapshotOpt, currentSignature, nodes.size(), mismatch);
  }

  private void applySignatureCheck(StorageProvider provider, World world, WriteResult result) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(world, "world");
    SignatureCheckResult check = result.check();
    if (check.snapshot().isEmpty()) {
      return;
    }
    RailGraphSnapshotRecord snapshot = check.snapshot().get();
    if (unsyncedWorlds.contains(world.getUID())
        && railGraphService.getStaleState(world).isEmpty()) {
      // 写库失败标出的失效已被重建图撤掉：重建按世界里的牌子重写了 rail_nodes。
      unsyncedWorlds.remove(world.getUID());
    }
    GraphStaleListener.Level before = level(world);

    if (check.mismatch()) {
      // 同一块牌子会被删两次（拆牌监听当 tick 一次，TC 下一 tick 核验时 destroy 再一次）：库没变，失效状态也不变。
      if (!result.changed() && before != GraphStaleListener.Level.NONE) {
        return;
      }
      boolean snapshotServed =
          before != GraphStaleListener.Level.EVICTED
              && railGraphService.getSnapshot(world).isPresent();
      List<GraphStaleListener.NodeChange> changes = new ArrayList<>();
      boolean retain = snapshotServed;
      for (GraphStaleListener.NodeChange change : result.changes()) {
        GraphStaleListener.NodeChange judged =
            snapshotServed ? change.withUsage(usageOf(world, change)) : change;
        retain &= judged.usage().isEmpty();
        changes.add(judged);
      }
      railGraphService.markStale(
          world,
          new RailGraphStaleState(
              snapshot.builtAt(),
              snapshot.nodeSignature(),
              check.currentSignature(),
              snapshot.nodeCount(),
              snapshot.edgeCount(),
              check.currentNodeCount(),
              retain));
      GraphStaleListener.Level after = level(world);
      for (GraphStaleListener.NodeChange change : changes) {
        notifyListener(() -> staleListener.onStale(world, change, before, after));
      }
      return;
    }

    if (unsyncedWorlds.contains(world.getUID())) {
      // 写库失败过：库里签名一致不代表与世界里的牌子一致，失效标记留到重建图为止。
      return;
    }
    // 若此前处于 stale 状态，且当前签名已恢复一致：还在供的旧图就是库里那份，撤标记即可；已移出的从库重新加载。
    boolean wasStale = before != GraphStaleListener.Level.NONE;
    if (before == GraphStaleListener.Level.RETAINED && railGraphService.clearRetainedStale(world)) {
      notifyListener(() -> staleListener.onRecovered(world));
      return;
    }
    if (railGraphService.getSnapshot(world).isEmpty() || wasStale) {
      railGraphService.loadFromStorage(provider, List.of(world));
      if (wasStale && railGraphService.getStaleState(world).isEmpty()) {
        notifyListener(() -> staleListener.onRecovered(world));
      }
    }
  }

  private GraphStaleListener.Level level(World world) {
    Optional<RailGraphStaleState> state = railGraphService.getStaleState(world);
    if (state.isEmpty()) {
      return GraphStaleListener.Level.NONE;
    }
    return railGraphService.isServingRetainedStaleSnapshot(world.getUID())
        ? GraphStaleListener.Level.RETAINED
        : GraphStaleListener.Level.EVICTED;
  }

  /** 判不了按在用处理：宁可移出旧图，也不让列车按可能已经不存在的节点跑。 */
  private Optional<String> usageOf(World world, GraphStaleListener.NodeChange change) {
    // 旧图里已有同名节点（换了位置、或拆掉后换个地方放回）：旧图的位置已经不对了。
    if (!change.removed()
        && railGraphService
            .getSnapshot(world)
            .flatMap(snapshot -> snapshot.graph().findNode(change.definition().nodeId()))
            .isPresent()) {
      return Optional.of("旧图里已有同名节点");
    }
    try {
      Optional<String> usage = nodeUsage.findUse(world, change.definition());
      return usage != null ? usage : Optional.of("交路判定无结果");
    } catch (RuntimeException ex) {
      debugLogger.accept("交路判定失败: node=" + change.definition().nodeId().value() + " " + ex);
      return Optional.of("交路判定失败");
    }
  }

  /** 告警失败不能让存储同步看起来失败：库已经写好了。 */
  private void notifyListener(Runnable notification) {
    try {
      notification.run();
    } catch (RuntimeException ex) {
      debugLogger.accept("调度图失效告警失败: " + ex);
    }
  }

  private static GraphStaleListener.NodeChange removalOf(RailNodeRecord record) {
    return new GraphStaleListener.NodeChange(
        new SignNodeDefinition(
            record.nodeId(),
            record.nodeType(),
            record.trainCartsDestination(),
            record.waypointMetadata()),
        record.x(),
        record.y(),
        record.z(),
        true);
  }

  /**
   * 一次写库带回的变更。
   *
   * @param changed 库里是否真的变了（删掉了行、写入了新节点或顶掉了旧节点）
   * @param changes 要判在不在用并报告的节点变更
   */
  private record Written(boolean changed, List<GraphStaleListener.NodeChange> changes) {}

  /** 一个世界在同一 tick 内攒下的变更。 */
  private static final class PendingCheck {
    private final World world;
    private final List<GraphStaleListener.NodeChange> changes = new ArrayList<>();
    private final List<GraphStaleListener.NodeChange> noopChanges = new ArrayList<>();
    private boolean changed;

    private PendingCheck(World world) {
      this.world = world;
    }

    private void add(Written written) {
      changed |= written.changed();
      (written.changed() ? changes : noopChanges).addAll(written.changes());
    }
  }

  /**
   * 合并后的一次签名比对输入。
   *
   * @param changed 这一批里库是否真的变了
   * @param changes 要判在不在用并报告的节点变更
   */
  private record WriteResult(
      boolean changed, List<GraphStaleListener.NodeChange> changes, SignatureCheckResult check) {}

  private record SignatureCheckResult(
      Optional<RailGraphSnapshotRecord> snapshot,
      String currentSignature,
      int currentNodeCount,
      boolean mismatch) {
    private SignatureCheckResult {
      Objects.requireNonNull(snapshot, "snapshot");
      currentSignature = currentSignature == null ? "" : currentSignature;
    }

    private static SignatureCheckResult noSnapshot() {
      return new SignatureCheckResult(Optional.empty(), "", 0, false);
    }
  }
}
