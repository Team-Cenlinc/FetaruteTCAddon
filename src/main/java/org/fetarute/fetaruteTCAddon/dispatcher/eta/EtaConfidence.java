package org.fetarute.fetaruteTCAddon.dispatcher.eta;

/** ETA 可信度：用于区分“实时运行中”与“缺少关键数据时的保守估算”。 */
public enum EtaConfidence {
  HIGH,
  MED,
  LOW
}
