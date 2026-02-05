package org.fetarute.fetaruteTCAddon.dispatcher.signal.event;

import java.time.Instant;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 信号变化事件。
 *
 * <p>当列车信号状态发生变化时发布，通知控车模块立即下发新的速度/控制指令。
 */
public record SignalChangedEvent(
    Instant timestamp, String trainName, SignalAspect previousSignal, SignalAspect newSignal)
    implements SignalEvent {

  public SignalChangedEvent {
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(newSignal, "newSignal");
  }

  @Override
  public String eventType() {
    return "SIGNAL_CHANGED";
  }

  /** 信号是否从阻塞变为放行。 */
  public boolean isUnblocked() {
    return (previousSignal == SignalAspect.STOP || previousSignal == null)
        && newSignal != SignalAspect.STOP;
  }

  /** 信号是否从放行变为阻塞。 */
  public boolean isBlocked() {
    return previousSignal != SignalAspect.STOP && newSignal == SignalAspect.STOP;
  }
}
