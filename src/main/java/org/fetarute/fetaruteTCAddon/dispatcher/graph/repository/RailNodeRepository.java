package org.fetarute.fetaruteTCAddon.dispatcher.graph.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** RailNode（节点牌子）仓库接口。 */
public interface RailNodeRepository {

  List<RailNodeRecord> listByWorld(UUID worldId);

  void upsert(RailNodeRecord node);

  /** 同一位置上的节点（正常至多一个）。 */
  List<RailNodeRecord> listByPosition(UUID worldId, int x, int y, int z);

  /**
   * 删除指定节点。
   *
   * @return 删掉的行数；0 表示库里本来就没有
   */
  int delete(UUID worldId, NodeId nodeId);

  void deleteByPosition(UUID worldId, int x, int y, int z);

  void replaceWorld(UUID worldId, Collection<RailNodeRecord> nodes);

  void deleteWorld(UUID worldId);
}
