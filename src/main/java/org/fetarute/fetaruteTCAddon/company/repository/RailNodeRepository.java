package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;

/** RailNode（节点牌子）仓库接口。 */
public interface RailNodeRepository {

  List<RailNodeRecord> listByWorld(UUID worldId);

  void replaceWorld(UUID worldId, Collection<RailNodeRecord> nodes);

  void deleteWorld(UUID worldId);
}
