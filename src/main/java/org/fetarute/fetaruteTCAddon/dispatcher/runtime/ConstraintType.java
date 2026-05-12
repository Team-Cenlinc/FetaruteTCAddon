package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

/** 信号 envelope 支持的约束类型。 */
public enum ConstraintType {
  AUTHORITY_END,
  FOLLOWING_TRAIN,
  SINGLE_CONFLICT_ENTRY,
  HARD_BLOCKER,
  SWITCHER_THROAT,
  APPROACH_STATION,
  APPROACH_DEPOT,
  CAUTION_ZONE,
  EDGE_SPEED_LIMIT,
  DWELL_HOLD
}
