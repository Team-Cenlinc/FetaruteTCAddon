package org.fetarute.fetaruteTCAddon.drive.crew;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 信号员一个班次的经过：从上岗到下岗，在一个控制区里经手的车与做过的事。成绩、奖励与记录都按班次结算，不按车次。本类不依赖服务器对象。
 *
 * @param player 信号员
 * @param playerName 信号员名字（上岗时）
 * @param operator 运营商代码
 * @param zone 控制区代码
 * @param start 上岗
 * @param end 下岗
 * @param trainsHandled 人工模式下经手的车数：拿着许可驶入控制区的车，含自动通过与超时代排的
 * @param routesSet 信号员亲手排列的进路数（含预排）
 * @param autoRouted 等许可超时、由系统代排的车数
 * @param autoPassed 走自动通过进路驶入的车数
 * @param preRouted 预排命中：车到入口时进路已排好、没有停车等许可
 * @param holds 扣车次数
 * @param callsAnswered 回复了的呼叫
 * @param callsMissed 超时没回复的呼叫
 * @param endReason 下岗原因
 */
public record SignallerShift(
    UUID player,
    String playerName,
    String operator,
    String zone,
    Instant start,
    Instant end,
    int trainsHandled,
    int routesSet,
    int autoRouted,
    int autoPassed,
    int preRouted,
    int holds,
    int callsAnswered,
    int callsMissed,
    EndReason endReason) {

  public SignallerShift {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
    Objects.requireNonNull(endReason, "endReason");
    playerName = playerName == null ? "" : playerName;
    operator = operator == null ? "" : operator;
    zone = zone == null ? "" : zone;
    trainsHandled = Math.max(0, trainsHandled);
    routesSet = Math.max(0, routesSet);
    autoRouted = Math.max(0, autoRouted);
    autoPassed = Math.max(0, autoPassed);
    preRouted = Math.max(0, preRouted);
    holds = Math.max(0, holds);
    callsAnswered = Math.max(0, callsAnswered);
    callsMissed = Math.max(0, callsMissed);
  }

  /** 班次时长；时钟回拨时为 0。 */
  public Duration duration() {
    Duration duration = Duration.between(start, end);
    return duration.isNegative() ? Duration.ZERO : duration;
  }

  /** 下岗原因。 */
  public enum EndReason {
    /** 自己下岗（命令或控制台）。 */
    OFF_DUTY,
    /** 离开控制台超过宽限期。 */
    LEFT_CONSOLE,
    /** 下线。 */
    QUIT,
    /** 死亡或换了游戏模式。 */
    INCAPACITATED,
    /** 管理员撤下，或控制区被删除、改名。 */
    REVOKED,
    /** 插件关闭或重载。 */
    SHUTDOWN
  }

  /** 班次结算：算成绩、发奖励、写记录。由驾驶模块实现，信号员模块下岗时调用一次。只在主线程调用，不得抛异常。 */
  @FunctionalInterface
  public interface Settlement {

    /** 不结算（驾驶模块未启用）。 */
    Settlement NONE = shift -> {};

    /**
     * 结算一个班次，并把成绩告诉在线的信号员。
     *
     * @param shift 刚结束的班次
     */
    void settle(SignallerShift shift);
  }
}
