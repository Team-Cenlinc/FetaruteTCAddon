package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;

/**
 * 暴露图快照的世界级物理联锁状态。
 *
 * <p>调用方只消费不可变状态，不需要理解 TrainCarts 路径采样、足迹格式或 zone 构建算法。
 */
public interface RailGraphInterlockingSupport {

  /** 返回与当前图边 universe 同版本的联锁状态。 */
  RailInterlockingState interlockingState();

  /** 一条边占用的联锁区；复合路网按边所在的世界取。 */
  default java.util.Set<String> zoneKeysForEdge(EdgeId edgeId) {
    return interlockingState().zoneKeysForEdge(edgeId);
  }
}
