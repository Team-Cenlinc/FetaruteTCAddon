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

  /** 这列车当前是否由驾驶员控制（执行层不得写限速、发车或清空动作队列）：人工驾驶，或 ATO 下扣着等驾驶员换端。 */
  boolean isDriverControlled(TrainProperties properties);

  /**
   * 这列车是否由驾驶员物理操纵（按车名）。
   *
   * <p>健康层据此不对它做重发车、改目的地等恢复动作；ATO 下由自动运行操纵，照常恢复（扣着等驾驶员换端时除外）。
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

  /** 车上的驾驶员是否亲手开关车门（人工驾驶）：停站中途接管时站台据此把开着的车门交给他；ATO 仍由站台开关门。 */
  default boolean driverOperatesDoors(TrainProperties properties) {
    return false;
  }

  /** 列车在站台停稳、开始一次停站（自动运行与驾驶员控车都通知）：上一站留下的发车确认状态到此作废。 */
  default void stationStopStarted(TrainProperties properties) {}

  /** 车上是否有车掌在岗：有就由车掌开关车门（自动运行与人工驾驶都一样），站台把停站交给他。 */
  default boolean guardOperatesDoors(TrainProperties properties) {
    return false;
  }

  /**
   * 车上有车掌时，站台在等发车那一步每秒问一次：车掌是否还扣着（还没给发车信号）。
   *
   * @param exitOpen 此刻出站门控是否放行；不放行时也要问，车掌据此暂停计时
   */
  default boolean holdForGuard(TrainProperties properties, boolean exitOpen) {
    return false;
  }

  /** 自动运行停站结束、出站许可就绪时，是否还要扣着等车上的驾驶员（ATO）确认发车。 */
  default boolean holdDeparture(TrainProperties properties) {
    return false;
  }

  /** 这列车上是否有驾驶员在岗（含 ATO）。死锁与清车遇到它时先请驾驶员交还，不销毁别的车。 */
  default boolean hasDriver(String trainName) {
    return isDriverControlledName(trainName);
  }

  /**
   * 驾驶员控制的列车这次发车前是否要按发车方向调头（终点站折返由驾驶员接班）。返回 true 后标记即清除。
   *
   * <p>自动运行的发车动作会自己调头；驾驶员控制时不下发发车动作，由执行层在放行的那一拍代为调头。
   */
  default boolean takeTurnback(TrainProperties properties) {
    return false;
  }

  /** 这列车是否正停着等驾驶员上车接班（始发站待命、车库出车）：健康层不当它停滞。 */
  default boolean awaitingDriver(String trainName) {
    return false;
  }

  /** 驾驶员控制的这列车停在终点站待命或结算后等开出下一趟（派车还没放行）。 */
  default boolean awaitingTurnback(String trainName) {
    return false;
  }

  /** 通过插件实例查找当前的控制权；插件未加载（如单元测试）时等同 {@link #NONE}。 */
  static ControlAuthority pluginLookup() {
    return PluginControlAuthority.INSTANCE;
  }
}
