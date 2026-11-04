package org.fetarute.fetaruteTCAddon.drive.crew;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 站长的一个班次：上岗到下岗之间的作业计数，下岗时交给结算（成绩、记录、奖励）。
 *
 * @param player 站长
 * @param playerName 站长名
 * @param operator 运营商代码
 * @param station 站码
 * @param start 上岗时刻
 * @param end 下岗时刻
 * @param counts 作业计数
 * @param endReason 下岗原因
 * @param examined 这一班是操作许可证的考试（写记录、不发奖励）
 * @param practiced 这一班是练习（不写记录、不发奖励）
 */
public record StationMasterShift(
    UUID player,
    String playerName,
    String operator,
    String station,
    Instant start,
    Instant end,
    Counts counts,
    EndReason endReason,
    boolean examined,
    boolean practiced) {

  public StationMasterShift {
    Objects.requireNonNull(player, "player");
    playerName = playerName == null ? "" : playerName;
    operator = operator == null ? "" : operator;
    station = station == null ? "" : station;
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
    counts = counts == null ? Counts.NONE : counts;
    Objects.requireNonNull(endReason, "endReason");
    examined = examined || practiced;
  }

  /**
   * 作业计数。
   *
   * @param signals 亲手发的发车信号
   * @param autoSignals 等发车信号超时、由系统代发
   * @param holds 扣车次数
   * @param holdsExpired 扣车到上限自动放行的次数
   * @param arrivalOk 接车监视合格
   * @param arrivalMissed 接车监视不合格（人在站台上、没看着车）
   * @param arrivalAbsent 接车不在岗（进站时一次也没站到那条股道的站台上）
   * @param departureOk 出站监视合格
   * @param departureMissed 出站监视不合格
   * @param emergencies 站台紧急停车次数（只记次数，不扣分）
   * @param clearedRiders 清客到时限送到站台的乘客数
   * @param simRight 模拟事件处置正确
   * @param simWrong 模拟事件处置错误
   * @param simMissed 模拟事件没按时处置
   */
  public record Counts(
      int signals,
      int autoSignals,
      int holds,
      int holdsExpired,
      int arrivalOk,
      int arrivalMissed,
      int arrivalAbsent,
      int departureOk,
      int departureMissed,
      int emergencies,
      int clearedRiders,
      int simRight,
      int simWrong,
      int simMissed) {

    public static final Counts NONE = new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public Counts {
      signals = Math.max(0, signals);
      autoSignals = Math.max(0, autoSignals);
      holds = Math.max(0, holds);
      holdsExpired = Math.max(0, holdsExpired);
      arrivalOk = Math.max(0, arrivalOk);
      arrivalMissed = Math.max(0, arrivalMissed);
      arrivalAbsent = Math.max(0, arrivalAbsent);
      departureOk = Math.max(0, departureOk);
      departureMissed = Math.max(0, departureMissed);
      emergencies = Math.max(0, emergencies);
      clearedRiders = Math.max(0, clearedRiders);
      simRight = Math.max(0, simRight);
      simWrong = Math.max(0, simWrong);
      simMissed = Math.max(0, simMissed);
    }
  }

  /** 经手的车：亲手发信号的与超时代发的。 */
  public int trainsHandled() {
    return counts.signals() + counts.autoSignals();
  }

  /** 值守时长；时钟倒退时为 0。 */
  public Duration duration() {
    return end.isBefore(start) ? Duration.ZERO : Duration.between(start, end);
  }

  /** 下岗原因。 */
  public enum EndReason {
    /** 自己下岗。 */
    OFF_DUTY,
    /** 离开车站过久。 */
    LEFT_STATION,
    /** 下线。 */
    QUIT,
    /** 死亡或改了游戏模式。 */
    INCAPACITATED,
    /** 插件关闭、重载或站长功能关闭。 */
    SHUTDOWN,
    /** 考试或练习判定完毕。 */
    EXAM
  }

  /** 下岗时的结算（成绩、记录、奖励）。只在主线程调用，不抛异常。 */
  @FunctionalInterface
  public interface Settlement {

    /** 不结算。 */
    Settlement NONE = shift -> {};

    void settle(StationMasterShift shift);
  }
}
