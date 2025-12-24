package org.fetarute.fetaruteTCAddon.dispatcher.node;

/** Waypoint 元数据的细分类型，用于描述字符串编码所代表的含义。 */
public enum WaypointKind {
  /** 普通区间节点，形如 SURN:PTK:GPT:1:00。 */
  INTERVAL,
  /** 站咽喉节点，形如 SURN:S:PTK:1。 */
  STATION_THROAT,
  /** 车库内部或靠近车库的节点，形如 SURN:D:LVT:1。 */
  DEPOT,
  /** 区间内的道岔或分支点，可扩展自定义后缀。 */
  SWITCHER
}
