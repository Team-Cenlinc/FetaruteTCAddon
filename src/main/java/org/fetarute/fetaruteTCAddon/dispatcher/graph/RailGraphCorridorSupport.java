package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Optional;

/** 提供走廊信息的调度图扩展接口。 */
public interface RailGraphCorridorSupport extends RailGraphConflictSupport {

  /**
   * 根据边查询所属走廊信息。
   *
   * <p>若该边不属于任何走廊，返回 empty。
   */
  Optional<RailGraphCorridorInfo> corridorInfoForEdge(EdgeId edgeId);
}
