package org.fetarute.fetaruteTCAddon.dispatcher.graph.sync;

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
 * <p>图失效与恢复都会通知 {@link GraphStaleListener}：失效会移除该世界的内存快照，必须让运维知道。
 */
public final class RailNodeIncrementalSync implements SignNodeStorageSynchronizer {

  private final StorageManager storageManager;
  private final RailGraphService railGraphService;
  private final Consumer<String> debugLogger;
  private final GraphStaleListener staleListener;

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
    this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.staleListener = staleListener != null ? staleListener : GraphStaleListener.noop();
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
                SignatureCheckResult check =
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              provider
                                  .railNodes()
                                  .deleteByPosition(worldId, record.x(), record.y(), record.z());
                              provider.railNodes().upsert(record);
                              return checkSignature(provider, worldId);
                            });
                applySignatureCheck(
                    provider,
                    world,
                    worldId,
                    check,
                    new GraphStaleListener.NodeChange(
                        definition, record.x(), record.y(), record.z(), false));
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
                DeleteResult result =
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              boolean existed =
                                  provider.railNodes().listByWorld(worldId).stream()
                                      .anyMatch(node -> definition.nodeId().equals(node.nodeId()));
                              provider.railNodes().delete(worldId, definition.nodeId());
                              return new DeleteResult(existed, checkSignature(provider, worldId));
                            });
                // 同一块牌子会被删两次（拆牌监听当 tick 一次，TC 下一 tick 核验时 destroy 再一次）：只报真正删掉行的那次。
                applySignatureCheck(
                    provider, world, worldId, result.check(), result.existed() ? change : null);
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

  /** {@code change} 为 null 表示库里没有实际变化：照常校验签名，但不报告失效。 */
  private void applySignatureCheck(
      StorageProvider provider,
      World world,
      UUID worldId,
      SignatureCheckResult check,
      GraphStaleListener.NodeChange change) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(check, "check");

    if (check.snapshot().isEmpty()) {
      return;
    }
    RailGraphSnapshotRecord snapshot = check.snapshot().get();
    boolean wasStale = railGraphService.getStaleState(world).isPresent();

    if (check.mismatch()) {
      railGraphService.markStale(
          world,
          new RailGraphStaleState(
              snapshot.builtAt(),
              snapshot.nodeSignature(),
              check.currentSignature(),
              snapshot.nodeCount(),
              snapshot.edgeCount(),
              check.currentNodeCount()));
      if (change != null) {
        notifyListener(() -> staleListener.onStale(world, change, wasStale));
      }
      return;
    }

    // 若此前处于 stale 状态，且当前签名已恢复一致，则尝试重新加载快照到内存。
    if (railGraphService.getSnapshot(world).isEmpty() || wasStale) {
      railGraphService.loadFromStorage(provider, List.of(world));
      if (wasStale && railGraphService.getStaleState(world).isEmpty()) {
        notifyListener(() -> staleListener.onRecovered(world));
      }
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

  private record DeleteResult(boolean existed, SignatureCheckResult check) {}

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
