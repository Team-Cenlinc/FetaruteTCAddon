package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 站长的站台作业：站台（AutoStation）登记停站、问发车信号，出站门控问站长扣车。
 *
 * <p>与信号员的闸一样只能把放行变成“暂不放行”：站台只在车门已关、出站门控已放行之后才问发车信号，站长迟迟不发时由站长一侧到时限代发。没有站长值守的股道一律放行。
 *
 * <p>本接口不依赖站长包。实现必须便宜、不抛异常，只在服务器主线程调用。
 */
public interface StationMasterGate {

  /** 没有站长值守：一律放行。 */
  StationMasterGate NONE = new StationMasterGate() {};

  /**
   * 车门已开（本站不开门时为停站开始）：登记这一站，站长据此知道哪条股道上停着哪列车。
   *
   * @param trainName 列车名
   * @param node 停靠的站台股道
   * @param closeOpensIn 还要多久车上的乘务员才能关门（提前关门窗口的起点，已到为零）；车门不归乘务员时为空
   * @param crewDoors 车门归车上的驾驶员或车掌（站长只能催关）
   */
  default void stopOpened(
      String trainName, NodeId node, Optional<Duration> closeOpensIn, boolean crewDoors) {}

  /**
   * 站台报车门此刻是否全关，只用于给站长显示。
   *
   * @param trainName 列车名
   * @param node 停靠的站台股道
   * @param closed 车门全关着
   */
  default void doorsClosed(String trainName, NodeId node, boolean closed) {}

  /**
   * 这一站结束（已发车、停站会话被接管或作废）。同一列车已在别的股道登记了新的一站时不动那一站。
   *
   * @param trainName 列车名
   * @param node 结束的这一站的股道
   */
  default void stopEnded(String trainName, NodeId node) {}

  /**
   * 车门已关、出站门控已放行：问这列车是否还在等站长的发车信号。站长迟迟不发时由实现到时限代发，不会无限期扣着。
   *
   * @param trainName 列车名
   * @param node 停靠的站台股道
   * @return 还要等发车信号时为 true
   */
  default boolean holdsDeparture(String trainName, NodeId node) {
    return false;
  }

  /**
   * 出站门控此刻没放行（闭塞、按表扣车、站长扣车等）：发车信号的等待到此作废，放行后重新等，关着的时间不算站长超时。
   *
   * @param trainName 列车名
   * @param node 停靠的站台股道
   */
  default void departureBlocked(String trainName, NodeId node) {}

  /**
   * 站长扣车扣到几点；不扣时为空。由出站门控与按表扣车、联挂等候、信号员扣车合并取较晚者。
   *
   * @param trainName 列车名
   */
  default Optional<Instant> holdTarget(String trainName) {
    return Optional.empty();
  }
}
