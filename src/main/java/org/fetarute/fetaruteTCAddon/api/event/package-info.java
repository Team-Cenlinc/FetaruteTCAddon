/**
 * 公开 API 事件（1.4.0 新增）：列车到发、扣停、信号变化、离开管辖、健康告警、车次绑定。
 *
 * <p>全部是同步的 Bukkit 事件，在事实发生后的<b>下一个 tick</b> 由主线程统一发出，不在调度路径里直接调用外部代码——
 * 监听器抛异常或耗时都不会影响调度。事件只读、不可取消，监听器不能借此改变列车行为。
 *
 * <p>某类事件没有任何监听器时连事件对象都不会创建，不监听就没有开销。
 *
 * <pre>{@code
 * @EventHandler
 * public void onArrive(TrainArriveStationEvent event) {
 *   getLogger().info(event.getTrainName() + " 到达 " + event.getNodeId());
 * }
 * }</pre>
 *
 * <p>注意监听具体事件类；抽象基类 {@link org.fetarute.fetaruteTCAddon.api.event.TrainStationEvent} 不能直接监听。
 */
package org.fetarute.fetaruteTCAddon.api.event;
