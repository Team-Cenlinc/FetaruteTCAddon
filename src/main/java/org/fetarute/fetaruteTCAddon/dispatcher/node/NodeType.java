package org.fetarute.fetaruteTCAddon.dispatcher.node;

/** 描述节点的功能类型，调度逻辑会根据类型加载不同的行为。 */
public enum NodeType {
  DEPOT,
  STATION,
  WAYPOINT,
  DESTINATION,
  SWITCHER,
  /** TrainCarts 的 {@code [portal]} 牌子（MyWorlds 传送门）所在的轨道：图在这里断开，经传送门连接接到另一个世界。 */
  PORTAL
}
