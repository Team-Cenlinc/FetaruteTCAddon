/**
 * 调度图（RailGraph）查询与代价模型。
 *
 * <p>本包提供一组“只读”的查询工具，用于命令侧诊断与后续调度算法扩展：
 *
 * <ul>
 *   <li>{@link
 *       org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder}：最短路（Dijkstra）
 *   <li>{@link
 *       org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailEdgeCostModel}：区间代价模型（可扩展为按时间/按距离/按权重）
 *   <li>{@link org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel}：行程时间模型（用于
 *       ETA 估算）
 * </ul>
 *
 * <p>设计原则：命令层优先输出“稳定可用的诊断信息”，因此默认采用距离最短 + 常量速度估算；后续引入限速/单向/封锁策略时， 只需替换模型实现即可在不改命令输出结构的情况下升级算法。
 */
package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;
