package org.fetarute.fetaruteTCAddon.dispatcher.graph.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService.RailGraphStaleState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
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
 */
public final class RailNodeIncrementalSync implements SignNodeStorageSynchronizer {

  private final StorageManager storageManager;
  private final RailGraphService railGraphService;
  private final Consumer<String> debugLogger;
  private final GraphStaleListener staleListener;
  private final GraphNodeUsage nodeUsage;

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
    this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.staleListener = staleListener != null ? staleListener : GraphStaleListener.noop();
    this.nodeUsage = nodeUsage != null ? nodeUsage : GraphNodeUsage.alwaysInUse();
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
                WriteResult result =
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              List<GraphStaleListener.NodeChange> changes = new ArrayList<>();
                              boolean unchanged = false;
                              for (RailNodeRecord existing :
                                  provider.railNodes().listByWorld(worldId)) {
                                if (!samePosition(existing, record)) {
                                  continue;
                                }
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
                              return new WriteResult(
                                  !unchanged || changes.size() > 1,
                                  changes,
                                  checkSignature(provider, worldId));
                            });
                applySignatureCheck(provider, world, result);
              } catch (Exception ex) {
                debugLogger.accept(
                    "rail_nodes 增量同步失败: op=upsert node="
                        + record.nodeId().value()
                        + " msg="
                        + ex.getMessage());
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
                WriteResult result =
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              boolean existed =
                                  provider.railNodes().listByWorld(worldId).stream()
                                      .anyMatch(node -> definition.nodeId().equals(node.nodeId()));
                              provider.railNodes().delete(worldId, definition.nodeId());
                              return new WriteResult(
                                  existed, List.of(change), checkSignature(provider, worldId));
                            });
                applySignatureCheck(provider, world, result);
              } catch (Exception ex) {
                debugLogger.accept(
                    "rail_nodes 增量同步失败: op=delete node="
                        + definition.nodeId().value()
                        + " msg="
                        + ex.getMessage());
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

    // 若此前处于 stale 状态，且当前签名已恢复一致，则尝试重新加载快照到内存。
    boolean wasStale = before != GraphStaleListener.Level.NONE;
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

  private static boolean samePosition(RailNodeRecord a, RailNodeRecord b) {
    return a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
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
   * 一次写库的结果。
   *
   * @param changed 库里是否真的变了（删掉了行、写入了新节点或顶掉了旧节点）
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
