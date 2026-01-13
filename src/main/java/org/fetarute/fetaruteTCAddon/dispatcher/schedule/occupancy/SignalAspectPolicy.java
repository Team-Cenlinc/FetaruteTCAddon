package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Duration;

/**
 * 将等待时间映射为信号许可。
 *
 * <p>默认策略以等待秒数分级，便于对接车载/信息屏显示。
 */
public interface SignalAspectPolicy {

  SignalAspect aspectForDelay(Duration delay);

  static SignalAspectPolicy defaultPolicy() {
    return delay -> {
      if (delay == null || delay.isNegative() || delay.isZero()) {
        return SignalAspect.PROCEED;
      }
      long seconds = delay.getSeconds();
      if (seconds <= 5L) {
        return SignalAspect.PROCEED_WITH_CAUTION;
      }
      if (seconds <= 30L) {
        return SignalAspect.CAUTION;
      }
      return SignalAspect.STOP;
    };
  }
}
