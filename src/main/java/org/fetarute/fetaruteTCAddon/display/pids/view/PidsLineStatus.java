package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条线路此刻的运行状况，供线路运行状况屏显示。
 *
 * <p>同时出现几种情况时只取最要紧的一种（{@link Condition} 的声明顺序），说明栏写这一种的细节。
 *
 * @param condition 状况
 * @param detail 说明栏的细节
 */
public record PidsLineStatus(Condition condition, Detail detail) {

  public PidsLineStatus {
    Objects.requireNonNull(condition, "condition");
    Objects.requireNonNull(detail, "detail");
  }

  /** 状况，越靠前越要紧。 */
  public enum Condition {
    /** 全线停运：线路主数据为检修。 */
    SUSPENDED,
    /** 部分停运：交路经过被封锁的区间。 */
    PART_SUSPENDED,
    /** 严重延误。 */
    SEVERE_DELAYS,
    /** 轻微延误。 */
    MINOR_DELAYS,
    /** 部分班次取消。 */
    CANCELLATIONS,
    /** 运行正常：有在途列车，没有上面任何一种情况。 */
    GOOD,
    /** 已结束运营：没有在途列车，且不在时刻表运营时段内。 */
    ENDED,
    /** 暂无列车。 */
    NO_TRAINS
  }

  /** 说明栏的细节。 */
  public sealed interface Detail {

    /** 不写说明（运行正常、暂无列车；全线停运的说明由状况本身决定）。 */
    record None() implements Detail {}

    /**
     * 晚点最多几分钟。
     *
     * @param minutes 在途列车里最大的晚点（分钟，向下取整）
     */
    record Late(long minutes) implements Detail {}

    /**
     * 近期取消了几班。
     *
     * @param trips 班次数
     */
    record Cancelled(int trips) implements Detail {}

    /**
     * 哪一段暂停运营。
     *
     * @param section 断开处前后的停车站
     */
    record Closed(PidsDirectory.Section section) implements Detail {}

    /**
     * 下一次运营从几点开始。
     *
     * @param at 时刻表运营开始时刻
     */
    record FirstTrain(Instant at) implements Detail {}
  }

  /** 没有说明的状况。 */
  public static PidsLineStatus of(Condition condition) {
    return new PidsLineStatus(condition, new Detail.None());
  }
}
