package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** RailNode（节点牌子）仓库接口。 */
public interface RailNodeRepository {

  List<RailNodeRecord> listByWorld(UUID worldId);

  void upsert(RailNodeRecord node);

  void delete(UUID worldId, NodeId nodeId);

  void deleteByPosition(UUID worldId, int x, int y, int z);

  void replaceWorld(UUID worldId, Collection<RailNodeRecord> nodes);

  void deleteWorld(UUID worldId);
}
