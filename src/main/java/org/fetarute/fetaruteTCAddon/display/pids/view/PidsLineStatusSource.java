package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.time.Instant;

/** 线路运行状况的来源：实现负责汇总与缓存，构建视图时每条线路调用一次。 */
@FunctionalInterface
public interface PidsLineStatusSource {

  /**
   * @param line 运营商的一条线路
   * @param now 当前时刻
   */
  PidsLineStatus statusOf(PidsDirectory.OperatorLine line, Instant now);
}
