package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

/**
 * 一次 build 的“完整性”标记：用于区分是否可以安全执行“替换连通分量”（删除旧节点/边）。
 *
 * <p>注意：该标记只描述“本次 build 覆盖范围”的可信程度，不代表图一定完美无缺（例如轨道实现异常、世界数据损坏等）。
 */
public enum RailGraphBuildCompletion {
  /** 已尽可能沿轨道扩张并完成扫描，可认为覆盖了该连通分量。 */
  COMPLETE,
  /** 未启用区块加载，未加载区块视为不可达（可能缺失）。 */
  PARTIAL_UNLOADED_CHUNKS,
  /** 触发 maxChunks 限制导致暂停，可通过 continue 续跑。 */
  PARTIAL_MAX_CHUNKS,
  /** 区块加载发生失败（例如 gen=false 且 chunk 不存在），本次结果可能缺失。 */
  PARTIAL_FAILED_CHUNK_LOADS;

  public boolean canReplaceComponents() {
    return this == COMPLETE;
  }
}
