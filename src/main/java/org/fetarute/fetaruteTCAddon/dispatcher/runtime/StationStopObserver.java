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

  /**
   * {@link #onTrainReleased} 的原因：TrainCarts 把列车卸载进了离线存储。
   *
   * <p>卸载与移除走同一条运行时清理路径（占用、进度照样释放），但车还在，区块再加载时它会原样醒来。车次层要分清两者： 卸载的车不能把交路交给替补，否则它醒来时同一交路上就有两辆车。
   */
  String RELEASE_UNLOADED = "train-unloaded";

  /** 列车在车站停稳并完成进度推进。 */
  void onStationArrival(StationStopEvent event);

  /** 列车获得发车许可并离开车站。 */
  void onStationDeparture(StationStopEvent event);

  /**
   * 列车离开运行时管辖（销毁、卸载、改派交路或异常清理）。
   *
   * @param trainName 规范列车名
   * @param reason 诊断用原因；卸载时为 {@link #RELEASE_UNLOADED}
   */
  default void onTrainReleased(String trainName, String reason) {}
}
