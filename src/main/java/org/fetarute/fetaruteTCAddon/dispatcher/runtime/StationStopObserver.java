package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

/**
 * 车站停靠事件的观察者。
 *
 * <p>观察者运行在调度主路径上，因此实现必须满足三条约束：不阻塞（禁止同步访问存储或网络）、不抛异常（调度层会吞掉异常但那是兜底不是契约）、 不修改列车状态。时刻表录制是第一个实现者。
 *
 * <p>之所以做成观察者而不是让调度层直接调用录制器，是为了让 {@code RuntimeDispatchService} 不依赖 timetable 包：
 * 录制能力可以整体缺席，调度层的行为不因此改变一行。
 */
public interface StationStopObserver {

  /** 列车在车站停稳并完成进度推进。 */
  void onStationArrival(StationStopEvent event);

  /** 列车获得发车许可并离开车站。 */
  void onStationDeparture(StationStopEvent event);

  /**
   * 列车离开运行时管辖（销毁、改派交路或异常清理）。
   *
   * @param trainName 规范列车名
   * @param reason 诊断用原因
   */
  default void onTrainReleased(String trainName, String reason) {}
}
