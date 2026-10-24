package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 信号员对一列车的处置：令越过下一个停车站、取消车次。
 *
 * <p>本接口由调度侧实现，信号员模块调用；只在主线程调用，实现不得抛异常。
 */
public interface SignallerTrainDesk {

  /** 没有接上调度：车不在，什么也不做。 */
  SignallerTrainDesk NONE =
      new SignallerTrainDesk() {
        @Override
        public Optional<Skip> skip(String trainName) {
          return Optional.empty();
        }

        @Override
        public Optional<SkipRefusal> orderSkip(String trainName) {
          return Optional.empty();
        }

        @Override
        public Optional<SkipRefusal> revokeSkip(String trainName) {
          return Optional.empty();
        }

        @Override
        public Scene scene(String trainName) {
          return new Scene(false, false, false);
        }

        @Override
        public void announceCancel(String trainName) {}

        @Override
        public void cancel(String trainName) {}
      };

  /**
   * 这列车的下一个停车站，以及能不能越。
   *
   * @param trainName 列车名
   * @return 车不在、没有交路进度或往后已无停车站时为空
   */
  Optional<Skip> skip(String trainName);

  /**
   * 这列车已令越过的那一站；只读，不做越站判断，控制台列车表每行都问。
   *
   * @param trainName 列车名
   * @return 没有越站令时为空
   */
  default Optional<NodeId> skipping(String trainName) {
    return skip(trainName).filter(Skip::ordered).map(Skip::station);
  }

  /**
   * 令这列车越过下一个停车站。
   *
   * @param trainName 列车名
   * @return 不能越的原因；下了令时为空
   */
  Optional<SkipRefusal> orderSkip(String trainName);

  /**
   * 撤销这列车的越站令。
   *
   * @param trainName 列车名
   * @return 不能撤的原因（已开始为那一站刹车）；撤了时为空
   */
  Optional<SkipRefusal> revokeSkip(String trainName);

  /**
   * 这列车此刻的现场。
   *
   * @param trainName 列车名
   * @return 车不在时三项都为 false
   */
  Scene scene(String trainName);

  /**
   * 向车上所有玩家插播停运通知（车随后被取消）。
   *
   * @param trainName 列车名
   */
  void announceCancel(String trainName);

  /**
   * 取消这列车的车次：车上还有玩家时送到本站站台，再删车；交路转空缺并派替补，剩余停站登记取消。
   *
   * @param trainName 列车名
   */
  void cancel(String trainName);

  /**
   * 列车的现场。
   *
   * @param playersAboard 车上有玩家（乘客、驾驶员或车掌）
   * @param atPlatform 停稳在站台上
   * @param coupled 是联挂中的一体车（带着两条交路）
   */
  record Scene(boolean playersAboard, boolean atPlatform, boolean coupled) {}

  /**
   * 下一个停车站。
   *
   * @param station 这一站的节点
   * @param ordered 已令越过这一站
   * @param refusal 不能越的原因
   */
  record Skip(NodeId station, boolean ordered, Optional<SkipRefusal> refusal) {
    public Skip {
      Objects.requireNonNull(station, "station");
      refusal = refusal == null ? Optional.empty() : refusal;
    }
  }

  /** 不能越（或不能撤）的原因。 */
  enum SkipRefusal {
    /** 往后没有停车站，或车没接上调度（没有交路进度）。 */
    NO_STOP,
    /** 终点。 */
    TERMINAL,
    /** 这一站有动作指令（换线、加挂、拆开、换模、折返、销毁等）。 */
    DIRECTIVE,
    /** 车已开始为这一站刹车。 */
    BRAKING,
    /** 有人叫了这列车，目的站就是这一站。 */
    CALL_DESTINATION,
    /** 有乘务员在这一站等着上车接班。 */
    CREW_BOARDING
  }
}
