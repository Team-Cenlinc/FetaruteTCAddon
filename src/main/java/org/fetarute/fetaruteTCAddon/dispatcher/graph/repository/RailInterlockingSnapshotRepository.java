package org.fetarute.fetaruteTCAddon.dispatcher.graph.repository;

import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailInterlockingSnapshotRecord;

/** 世界级稀疏物理联锁快照仓库。 */
public interface RailInterlockingSnapshotRepository {

  /** 按世界读取稀疏快照；旧库或尚未完成认证 build 时返回 empty。 */
  Optional<RailInterlockingSnapshotRecord> findByWorld(UUID worldId);

  /** 保存或替换一个世界的完整稀疏快照。 */
  RailInterlockingSnapshotRecord save(RailInterlockingSnapshotRecord snapshot);

  /** 删除一个世界的稀疏快照。 */
  void delete(UUID worldId);
}
