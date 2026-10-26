package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 信号员的进路许可闸：列车要进入一片人工控制的控制区之前，问一次有没有信号员给的许可。
 *
 * <p>闸只能把内核允许的放行变成“暂不放行”，绝不反过来：调用方先照常算出内核的判定，只有内核本来会放行时才问闸；被拦的车按“等信号员”停在控制区外，
 * 不进控制区资源的排队。已经持有控制区资源的车（已在区内）一律放行，免得停在道岔中间堵死咽喉。
 *
 * <p>本接口不依赖信号员包。实现必须便宜、不抛异常，只在服务器主线程调用。
 */
public interface SignallerGate {

  /** 没有任何控制区由信号员控制：一律放行。 */
  SignallerGate NONE = (trainName, requested, now) -> Optional.empty();

  /**
   * 列车这次要申请的硬授权资源里若有人工控制区的资源、它又没有许可，就拦下。
   *
   * @param trainName 列车名
   * @param requested 这次要申请的硬授权资源（进路锁闭意图）
   * @param now 调度层时钟
   * @return 要拦时返回拦截明细；放行时为空
   */
  Optional<Hold> admit(String trainName, Collection<OccupancyResource> requested, Instant now);

  /** 此刻有没有任何控制区由信号员人工控制。没有时调用方可以连申请资源都不必整理、直接放行；{@link #admit} 的结论与此一致。 */
  default boolean active() {
    return true;
  }

  /**
   * 这列车此刻是否正停在控制区入口等信号员。健康层据此把它当作计划内的静止，不去重发车、不清车；等待有超时自动排列兜底，不会无限期。
   *
   * @param trainName 列车名
   */
  default boolean waiting(String trainName) {
    return false;
  }

  /**
   * 列车改了名（手动改名、折返改名）：许可与等待状态随车迁到新名下。
   *
   * @param from 旧名
   * @param to 新名
   * @return 确有许可、等待或扣车随车迁过去时为 true（调用方据此决定要不要唤醒新名）
   */
  default boolean renamed(String from, String to) {
    return false;
  }

  /**
   * 列车已销毁或已不归调度管：丢掉它的许可与等待状态。
   *
   * @param trainName 列车名
   */
  default void forget(String trainName) {}

  /**
   * 一次拦截。
   *
   * @param box 控制区（{@code 运营商:控制区代码}）
   * @param since 开始等的时刻
   */
  record Hold(String box, Instant since) {
    public Hold {
      Objects.requireNonNull(box, "box");
      Objects.requireNonNull(since, "since");
    }

    /**
     * 停车原因明细：{@code box=<控制区> since=<开始等的时刻>}。写开始时刻而不是已等秒数：明细不变，停车生命周期才认得出是同一段停车，不会每秒多记一条、重置开始时刻。
     *
     * @param now 不再参与，保留以兼容调用方
     */
    public String detail(Instant now) {
      return "box=" + box + " since=" + since.truncatedTo(ChronoUnit.SECONDS);
    }
  }
}
