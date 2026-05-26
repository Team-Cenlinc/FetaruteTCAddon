package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/** Advisory lookahead 只读扫描识别到的前方风险来源。 */
public enum AdvisoryRiskSource {
  OCCUPIED_NODE,
  OCCUPIED_EDGE,
  ACTIVE_SINGLE_CONFLICT,
  ACTIVE_SWITCHER_CONFLICT,
  CONFLICT_QUEUE
}
