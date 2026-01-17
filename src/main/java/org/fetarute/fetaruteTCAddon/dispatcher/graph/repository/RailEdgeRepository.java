package org.fetarute.fetaruteTCAddon.dispatcher.graph.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;

/** RailEdge（区间）仓库接口。 */
public interface RailEdgeRepository {

  List<RailEdgeRecord> listByWorld(UUID worldId);

  void replaceWorld(UUID worldId, Collection<RailEdgeRecord> edges);

  void deleteWorld(UUID worldId);
}
