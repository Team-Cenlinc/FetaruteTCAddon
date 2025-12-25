package org.fetarute.fetaruteTCAddon.dispatcher.node;

/**
 * Waypoint 元数据的细分类型，用于描述“字符串编码”所代表的含义。
 *
 * <p>注意：此枚举描述的是 {@code NodeId} 编码语义（由 {@code SignTextParser} 解析得到），并不等价于牌子类型。
 * 例如站咽喉/车库咽喉属于“图节点（Waypoint）”职责，但它们的 kind 并非 {@link #INTERVAL}。
 */
public enum WaypointKind {
  /** 普通区间节点，形如 SURN:PTK:GPT:1:00。 */
  INTERVAL,
  /** 站点节点，形如 SURN:S:PTK:1。 */
  STATION,
  /** 站咽喉节点，形如 SURN:S:PTK:1:00。 */
  STATION_THROAT,
  /** 车库节点，形如 SURN:D:LVT:1。 */
  DEPOT,
  /** 车库咽喉节点，形如 SURN:D:LVT:1:00。 */
  DEPOT_THROAT,
  /** 区间内的道岔或分支点，可扩展自定义后缀。 */
  SWITCHER
}
