package org.fetarute.fetaruteTCAddon.dispatcher.graph.repository;

import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;

/** RailGraph 快照元信息仓库（每世界一条）。 */
public interface RailGraphSnapshotRepository {

  Optional<RailGraphSnapshotRecord> findByWorld(UUID worldId);

  RailGraphSnapshotRecord save(RailGraphSnapshotRecord snapshot);

  void delete(UUID worldId);
}
