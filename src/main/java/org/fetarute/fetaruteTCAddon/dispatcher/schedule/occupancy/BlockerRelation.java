package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 当前请求列车与 blocker claim 之间的关系分类。
 *
 * <p>该分类服务于信号授权、互卡修复与诊断输出。尤其是同向跟驰时，后车的保护 claim 不能被误判成前车的硬占用。
 */
public enum BlockerRelation {
  SELF,
  SAME_DIRECTION_FRONT,
  SAME_DIRECTION_REAR,
  OPPOSITE_SINGLE_CONFLICT,
  UNKNOWN_SINGLE_CONFLICT,
  STALE_PROTECTIVE_CLAIM,
  HARD_OCCUPANCY,
  SWITCHER_CONFLICT,
  DEPARTURE_GATE,
  DWELL_OR_STATION_HOLD
}
