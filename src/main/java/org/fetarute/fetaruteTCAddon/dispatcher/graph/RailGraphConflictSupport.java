package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Optional;

/** 调度图冲突组支持：为区间提供冲突资源 key。 */
public interface RailGraphConflictSupport {

  /** 返回指定区间的冲突组 key（不存在则 empty）。 */
  Optional<String> conflictKeyForEdge(EdgeId edgeId);
}
