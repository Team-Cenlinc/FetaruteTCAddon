package org.fetarute.fetaruteTCAddon.dispatcher.node;

/** 描述节点的功能类型，调度逻辑会根据类型加载不同的行为。 */
public enum NodeType {
  DEPOT,
  STATION,
  WAYPOINT,
  DESTINATION,
  SWITCHER
}
