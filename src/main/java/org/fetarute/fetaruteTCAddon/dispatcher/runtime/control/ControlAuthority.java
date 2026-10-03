package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;

/**
 * 列车的物理控制权：自动运行，还是由玩家驾驶。
 *
 * <p>调度层照常计算信号、授权、占用和交路进度；驾驶员控制的列车，执行层（{@code TrainLaunchManager}、{@code
 * RuntimeTrainController}、{@code TrainCartsRuntimeHandle}）不再写限速、不再发车，而是把这一刻的决定作为 {@link
 * DriverDirective 驾驶指令} 交出去，由驾驶会话执行保护包络；停车与销毁类的物理动作改为 {@link DriverInterrupt 中断}。
 *
 * <p>本接口不依赖驾驶包。实现必须便宜（每次控车都会调用）且不抛异常；所有方法只在服务器主线程调用。
 */
public interface ControlAuthority {

  /** 全部列车都由自动运行控制。 */
  ControlAuthority NONE = properties -> false;

  /** 这列车当前是否由驾驶员物理控制（执行层不得写限速、发车或清空动作队列）。 */
  boolean isDriverControlled(TrainProperties properties);

  /**
   * 这列车上是否有驾驶员在岗（含 ATO 下由自动运行代为操纵的情况）。
   *
   * <p>健康层据此不对有人驾驶的车做重发车、销毁等恢复动作。
   */
  default boolean isDriverControlledName(String trainName) {
    return false;
  }

  /** 把这一刻的控车决定交给驾驶员。 */
  default void publish(TrainProperties properties, DriverDirective directive) {}

  /** 调度层要求的停车或销毁类动作。 */
  default void interrupt(TrainProperties properties, DriverInterrupt interrupt) {}

  /** 调度层请求把这列车交还自动运行（例如它卡在死锁环里）。 */
  default void requestHandback(String trainName, String reason) {}

  /** 驾驶员控制的列车进站：站台交出停车点与站台侧，此后双方经这个对象推进停站。 */
  default void beginStationStop(TrainProperties properties, DriverStationStop stop) {}

  /** 通过插件实例查找当前的控制权；插件未加载（如单元测试）时等同 {@link #NONE}。 */
  static ControlAuthority pluginLookup() {
    return PluginControlAuthority.INSTANCE;
  }
}
