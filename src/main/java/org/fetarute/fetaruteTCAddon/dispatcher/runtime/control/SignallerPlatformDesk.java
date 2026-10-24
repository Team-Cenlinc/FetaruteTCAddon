package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 信号员改停股道：只对 {@code DYNAMIC:} 停站。信号员的选择优先于时刻表排定的计划站台，仅次于联挂搭档所在的股道。
 *
 * <p>本接口由调度侧实现，信号员模块调用；只在主线程调用，实现不得抛异常。改选后由实现方撤掉已缓存的选台与节点替换，并唤醒这列车重新评估。
 */
public interface SignallerPlatformDesk {

  /** 没有接上调度：没有可改的停站。 */
  SignallerPlatformDesk NONE =
      new SignallerPlatformDesk() {
        @Override
        public Optional<Options> options(String trainName) {
          return Optional.empty();
        }

        @Override
        public Choice choose(String trainName, NodeId platform) {
          return Choice.UNAVAILABLE;
        }

        @Override
        public void forget(String trainName) {}
      };

  /**
   * 这列车下一个还没开始停的 {@code DYNAMIC:} 停站可以改停哪些股道。正在这一站停着的不算（停下后不能再改）。
   *
   * @param trainName 列车名
   * @return 没有这样的停站时为空
   */
  Optional<Options> options(String trainName);

  /**
   * 让这列车在 {@link #options} 给出的那一站改停 {@code platform}。
   *
   * @param trainName 列车名
   * @param platform 候选股道之一
   * @return 改成了没有、没改成的原因
   */
  Choice choose(String trainName, NodeId platform);

  /** 改停的结果。 */
  enum Choice {
    /** 改成了。 */
    CHOSEN,
    /** 不是候选、停站已开始或车不在了。 */
    UNAVAILABLE,
    /** 已锁闭通往原来那条股道的进路（过了分岔点），不能再改。 */
    ROUTE_LOCKED
  }

  /** 撤销这列车的全部改停（下岗、控制区回到自动或车被删除时）；之后照时刻表与选台器原样选。 */
  void forget(String trainName);

  /**
   * 一个动态停站的可选股道。
   *
   * @param stopIndex 停站在交路里的下标
   * @param candidates 候选股道（站台节点），按股道号排序（与选台器一致）
   * @param current 车现在要去的那条（计划、信号员选的或选台器暂定的）；还没定时为空
   * @param chosen 信号员选过的那条
   */
  record Options(
      int stopIndex, List<NodeId> candidates, Optional<NodeId> current, Optional<NodeId> chosen) {
    public Options {
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
      current = current == null ? Optional.empty() : current;
      chosen = chosen == null ? Optional.empty() : chosen;
    }

    /** 候选里有没有这条。 */
    public boolean offers(NodeId platform) {
      return platform != null && candidates.contains(Objects.requireNonNull(platform));
    }
  }
}
