package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;

/** RailEdgeOverride（区间运维覆盖：限速/封锁等）仓库接口。 */
public interface RailEdgeOverrideRepository {

  Optional<RailEdgeOverrideRecord> findByEdge(UUID worldId, EdgeId edgeId);

  List<RailEdgeOverrideRecord> listByWorld(UUID worldId);

  void upsert(RailEdgeOverrideRecord override);

  void delete(UUID worldId, EdgeId edgeId);

  void deleteWorld(UUID worldId);
}
