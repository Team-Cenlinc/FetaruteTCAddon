package org.fetarute.fetaruteTCAddon.dispatcher.signal.event;

import java.time.Instant;

/**
 * 信号系统事件基类。
 *
 * <p>所有信号相关事件（占用变化、信号变化、死锁检测等）都继承此接口，通过 {@link SignalEventBus} 发布和订阅。
 */
public interface SignalEvent {

  /** 事件发生时间。 */
  Instant timestamp();

  /** 事件类型标识（用于日志和调试）。 */
  String eventType();
}
