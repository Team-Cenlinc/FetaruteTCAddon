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
 * @param counts 这一班的各项计数
 * @param endReason 下岗原因
 * @param examined 这一班是信号员考试或练习：不发奖励
 * @param practiced 这一班是信号员练习：也不写记录
 * @param parentZone 在中心工位上值守时所属大区的代码（{@code zone} 为临时代码）；普通控制区为空串
 */
public record SignallerShift(
    UUID player,
    String playerName,
    String operator,
    String zone,
    Instant start,
    Instant end,
    Counts counts,
    EndReason endReason,
    boolean examined,
    boolean practiced,
    String parentZone) {

  /** 不是考试也不是练习的班次。 */
  public SignallerShift(
      UUID player,
      String playerName,
      String operator,
      String zone,
      Instant start,
      Instant end,
      Counts counts,
      EndReason endReason) {
    this(player, playerName, operator, zone, start, end, counts, endReason, false, false);
  }

  /** 不在中心工位上的班次。 */
  public SignallerShift(
      UUID player,
      String playerName,
      String operator,
      String zone,
      Instant start,
      Instant end,
      Counts counts,
      EndReason endReason,
      boolean examined,
      boolean practiced) {
    this(
        player, playerName, operator, zone, start, end, counts, endReason, examined, practiced, "");
  }

  public SignallerShift {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
    Objects.requireNonNull(endReason, "endReason");
    playerName = playerName == null ? "" : playerName;
    operator = operator == null ? "" : operator;
    zone = zone == null ? "" : zone;
    parentZone = parentZone == null ? "" : parentZone;
    counts = counts == null ? Counts.builder().build() : counts;
    examined = examined || practiced;
  }

  /**
   * 一班的各项计数（都不小于 0）。计数都是 int、又多，按名字设：用 {@link #builder()}。
   *
   * @param trainsHandled 人工模式下经手的车数：拿着许可驶入控制区的车，含自动通过与超时代排的
   * @param routesSet 信号员亲手排列的进路数（含预排）
   * @param autoRouted 等许可超时、由系统代排的车数
   * @param autoPassed 走自动通过进路驶入的车数
   * @param preRouted 预排命中：车到入口时进路已排好、没有停车等许可
   * @param holds 扣车次数
   * @param callsAnswered 回复了的呼叫
   * @param callsMissed 超时没回复的呼叫
   * @param callsWrong 回复与呼叫原因不符的（也计在回复了的里）
   * @param keptTime 亲手排进来的车里，出区时晚点没比进区时多出容差的车数（正点放过去）
   * @param lostTime 拿着许可驶入的车里，出区时晚点比进区时多出容差以上的车数（在区内丢了时间）
   * @param conflictsGood 交汇排序得当：从不同入口抢同一片道岔的两列车，两车在区内合计多出的晚点在容差以内
   * @param conflictsBad 交汇排序不当：两车合计多出的晚点超过上限
   * @param tripsCancelled 取消的车次（只记次数，不计分）
   * @param skipsOrdered 令越站的次数（只记次数，不计分）
   * @param simRight 值守中的模拟事件答对的次数
   * @param simWrong 模拟事件答错的次数
   * @param simMissed 模拟事件超时没答的次数
   */
  public record Counts(
      int trainsHandled,
      int routesSet,
      int autoRouted,
      int autoPassed,
      int preRouted,
      int holds,
      int callsAnswered,
      int callsMissed,
      int callsWrong,
      int keptTime,
      int lostTime,
      int conflictsGood,
      int conflictsBad,
      int tripsCancelled,
      int skipsOrdered,
      int simRight,
      int simWrong,
      int simMissed) {

    public Counts {
      trainsHandled = Math.max(0, trainsHandled);
      routesSet = Math.max(0, routesSet);
      autoRouted = Math.max(0, autoRouted);
      autoPassed = Math.max(0, autoPassed);
      preRouted = Math.max(0, preRouted);
      holds = Math.max(0, holds);
      callsAnswered = Math.max(0, callsAnswered);
      callsMissed = Math.max(0, callsMissed);
      callsWrong = Math.max(0, callsWrong);
      keptTime = Math.max(0, keptTime);
      lostTime = Math.max(0, lostTime);
      conflictsGood = Math.max(0, conflictsGood);
      conflictsBad = Math.max(0, conflictsBad);
      tripsCancelled = Math.max(0, tripsCancelled);
      skipsOrdered = Math.max(0, skipsOrdered);
      simRight = Math.max(0, simRight);
      simWrong = Math.max(0, simWrong);
      simMissed = Math.max(0, simMissed);
    }

    /** 按名字设各项，没设的为 0。 */
    public static Builder builder() {
      return new Builder();
    }
  }

  /** 按名字设计数。 */
  public static final class Builder {
    private int trainsHandled;
    private int routesSet;
    private int autoRouted;
    private int autoPassed;
    private int preRouted;
    private int holds;
    private int callsAnswered;
    private int callsMissed;
    private int callsWrong;
    private int keptTime;
    private int lostTime;
    private int conflictsGood;
    private int conflictsBad;
    private int tripsCancelled;
    private int skipsOrdered;
    private int simRight;
    private int simWrong;
    private int simMissed;

    private Builder() {}

    public Builder trainsHandled(int value) {
      trainsHandled = value;
      return this;
    }

    public Builder routesSet(int value) {
      routesSet = value;
      return this;
    }

    public Builder autoRouted(int value) {
      autoRouted = value;
      return this;
    }

    public Builder autoPassed(int value) {
      autoPassed = value;
      return this;
    }

    public Builder preRouted(int value) {
      preRouted = value;
      return this;
    }

    public Builder holds(int value) {
      holds = value;
      return this;
    }

    public Builder callsAnswered(int value) {
      callsAnswered = value;
      return this;
    }

    public Builder callsMissed(int value) {
      callsMissed = value;
      return this;
    }

    public Builder callsWrong(int value) {
      callsWrong = value;
      return this;
    }

    public Builder keptTime(int value) {
      keptTime = value;
      return this;
    }

    public Builder lostTime(int value) {
      lostTime = value;
      return this;
    }

    public Builder conflictsGood(int value) {
      conflictsGood = value;
      return this;
    }

    public Builder conflictsBad(int value) {
      conflictsBad = value;
      return this;
    }

    public Builder tripsCancelled(int value) {
      tripsCancelled = value;
      return this;
    }

    public Builder skipsOrdered(int value) {
      skipsOrdered = value;
      return this;
    }

    public Builder simRight(int value) {
      simRight = value;
      return this;
    }

    public Builder simWrong(int value) {
      simWrong = value;
      return this;
    }

    public Builder simMissed(int value) {
      simMissed = value;
      return this;
    }

    public Counts build() {
      return new Counts(
          trainsHandled,
          routesSet,
          autoRouted,
          autoPassed,
          preRouted,
          holds,
          callsAnswered,
          callsMissed,
          callsWrong,
          keptTime,
          lostTime,
          conflictsGood,
          conflictsBad,
          tripsCancelled,
          skipsOrdered,
          simRight,
          simWrong,
          simMissed);
    }
  }

  public int trainsHandled() {
    return counts.trainsHandled();
  }

  public int routesSet() {
    return counts.routesSet();
  }

  public int autoRouted() {
    return counts.autoRouted();
  }

  public int autoPassed() {
    return counts.autoPassed();
  }

  public int preRouted() {
    return counts.preRouted();
  }

  public int holds() {
    return counts.holds();
  }

  public int callsAnswered() {
    return counts.callsAnswered();
  }

  public int callsMissed() {
    return counts.callsMissed();
  }

  public int callsWrong() {
    return counts.callsWrong();
  }

  public int keptTime() {
    return counts.keptTime();
  }

  public int lostTime() {
    return counts.lostTime();
  }

  public int conflictsGood() {
    return counts.conflictsGood();
  }

  public int conflictsBad() {
    return counts.conflictsBad();
  }

  public int tripsCancelled() {
    return counts.tripsCancelled();
  }

  public int skipsOrdered() {
    return counts.skipsOrdered();
  }

  public int simRight() {
    return counts.simRight();
  }

  public int simWrong() {
    return counts.simWrong();
  }

  public int simMissed() {
    return counts.simMissed();
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
    SHUTDOWN,
    /** 信号员考试交卷或了结（这一班到此为止，要继续值守就重新上岗）。 */
    EXAM
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
