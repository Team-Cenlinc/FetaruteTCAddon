package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Optional;

/** 提供单线 section 信息的调度图扩展接口。 */
public interface RailGraphSectionSupport extends RailGraphCorridorSupport {

  /**
   * 根据边查询所属的单线 section。
   *
   * <p>section 是比 corridor 更粗的方向性预留粒度，用于准入层阻止对向列车同时进入同一物理单线段。
   */
  Optional<SingleLineSectionInfo> sectionInfoForEdge(EdgeId edgeId);
}
