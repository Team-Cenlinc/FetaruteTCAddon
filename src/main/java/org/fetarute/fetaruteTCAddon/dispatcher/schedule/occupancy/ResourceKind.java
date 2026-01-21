package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 资源类型：用于表示不同粒度的闭塞/互斥对象。
 *
 * <p>资源粒度越细，吞吐越高但冲突判定复杂；最小可用版本以 EDGE + SWITCHER CONFLICT 为主。
 */
public enum ResourceKind {
  EDGE,
  NODE,
  CONFLICT
}
