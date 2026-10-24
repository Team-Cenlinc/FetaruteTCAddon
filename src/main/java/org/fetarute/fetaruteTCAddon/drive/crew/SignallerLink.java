package org.fetarute.fetaruteTCAddon.drive.crew;

import java.util.UUID;

/**
 * 驾驶员与信号员之间的联系：驾驶员呼叫信号员（车上没有车掌时，两短铃就是呼叫信号员；也可用命令）。
 *
 * <p>本接口不依赖信号员包；信号员模块启用后由它实现。只在主线程调用。
 */
public interface SignallerLink {

  /** 没有信号员模块：呼叫无人接。 */
  SignallerLink NONE = (driver, trainName) -> false;

  /**
   * 驾驶员呼叫这列车所在（或将要进入）的控制区的信号员。
   *
   * @param driver 驾驶员
   * @param trainName 他驾驶的列车
   * @return 有信号员值守的控制区收到了呼叫时为 true；没人值守时为 false，调用方据此提示驾驶员
   */
  boolean call(UUID driver, String trainName);

  /**
   * 带原因的呼叫：信号员要按原因选对回复。没实现原因的一方当作一般联系。
   *
   * @param reason 原因
   */
  default boolean call(UUID driver, String trainName, CallReason reason) {
    return call(driver, trainName);
  }
}
