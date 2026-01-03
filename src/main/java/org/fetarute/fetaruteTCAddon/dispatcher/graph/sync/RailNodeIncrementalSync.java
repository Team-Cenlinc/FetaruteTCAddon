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
 */
public final class RailNodeIncrementalSync implements SignNodeStorageSynchronizer {

  private final StorageManager storageManager;
  private final RailGraphService railGraphService;
  private final Consumer<String> debugLogger;

  public RailNodeIncrementalSync(
      StorageManager storageManager,
      RailGraphService railGraphService,
      Consumer<String> debugLogger) {
    this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
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
                applySignatureCheck(provider, world, worldId, check);
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

    provider()
        .ifPresent(
            provider -> {
              try {
                SignatureCheckResult check =
                    provider
                        .transactionManager()
                        .execute(
                            () -> {
                              provider.railNodes().delete(worldId, definition.nodeId());
                              return checkSignature(provider, worldId);
                            });
                applySignatureCheck(provider, world, worldId, check);
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

  private void applySignatureCheck(
      StorageProvider provider, World world, UUID worldId, SignatureCheckResult check) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(check, "check");

    if (check.snapshot().isEmpty()) {
      return;
    }
    RailGraphSnapshotRecord snapshot = check.snapshot().get();

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
      return;
    }

    // 若此前处于 stale 状态，且当前签名已恢复一致，则尝试重新加载快照到内存。
    if (railGraphService.getSnapshot(world).isEmpty()
        || railGraphService.getStaleState(world).isPresent()) {
      railGraphService.loadFromStorage(provider, List.of(world));
    }
  }

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
