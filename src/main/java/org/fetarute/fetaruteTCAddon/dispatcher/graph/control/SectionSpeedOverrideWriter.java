package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SectionSpeedService.SectionSpeedChange;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 负责把业务区间限速变更写入存储并同步到内存图服务。
 *
 * <p>{@link SectionSpeedService} 只计算“应该改哪些 edge”，本类集中处理 {@code rail_edge_overrides} 的读取、事务写入与内存
 * overlay 刷新，使命令与限速设置棍保持同一套写入语义。
 */
public final class SectionSpeedOverrideWriter {

  /** 读取一组 edge 当前已有的覆盖记录，优先存储，缺失时回退到内存 overlay。 */
  public Map<EdgeId, RailEdgeOverrideRecord> loadExisting(
      StorageProvider provider, RailGraphService service, UUID worldId, List<EdgeId> edgeIds) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeIds, "edgeIds");
    Map<EdgeId, RailEdgeOverrideRecord> existing = new HashMap<>();
    for (EdgeId edgeId : edgeIds) {
      if (edgeId == null) {
        continue;
      }
      EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
      Optional<RailEdgeOverrideRecord> record =
          provider.railEdgeOverrides().findByEdge(worldId, normalized);
      if (record.isEmpty() && service != null) {
        record = service.getEdgeOverride(worldId, normalized);
      }
      record.ifPresent(value -> existing.put(normalized, value));
    }
    return existing;
  }

  /** 在单个事务中提交变更，并在成功后刷新内存 overlay。 */
  public void apply(
      StorageProvider provider, RailGraphService service, UUID worldId, SectionSpeedChange change)
      throws Exception {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(change, "change");
    provider
        .transactionManager()
        .execute(
            () -> {
              for (RailEdgeOverrideRecord upsert : change.upserts()) {
                provider.railEdgeOverrides().upsert(upsert);
              }
              for (EdgeId delete : change.deletes()) {
                provider.railEdgeOverrides().delete(worldId, delete);
              }
              return null;
            });
    updateMemory(service, worldId, change);
  }

  private void updateMemory(RailGraphService service, UUID worldId, SectionSpeedChange change) {
    if (service == null) {
      return;
    }
    for (RailEdgeOverrideRecord upsert : change.upserts()) {
      service.putEdgeOverride(upsert);
    }
    for (EdgeId delete : change.deletes()) {
      service.deleteEdgeOverride(worldId, delete);
    }
  }
}
