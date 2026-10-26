package org.fetarute.fetaruteTCAddon.api.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 信号员下岗（1.15.0）：这一班已结算（成绩、奖励与记录），控制区回到自动。带这一班的计数。 */
public final class SignallerDutyEndedEvent extends SignallerEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final Instant start;
  private final Instant end;
  private final String reason;
  private final int trainsHandled;
  private final int routesSet;
  private final int autoRouted;
  private final int autoPassed;
  private final int holds;
  private final int callsAnswered;
  private final int callsMissed;
  private final String parentZoneCode;

  /**
   * @param start 上岗时刻
   * @param end 下岗时刻
   * @param reason 下岗原因
   * @param trainsHandled 拿着许可驶入控制区的车数（含自动通过与代排）
   * @param routesSet 信号员亲手排列的进路数
   * @param autoRouted 等许可超时、由系统代排的车数
   * @param autoPassed 走自动通过进路驶入的车数
   * @param holds 扣车次数
   * @param callsAnswered 回复了的驾驶员呼叫
   * @param callsMissed 超时没回复的呼叫
   */
  public SignallerDutyEndedEvent(
      UUID playerId,
      String operatorCode,
      String zoneCode,
      String zoneName,
      Instant start,
      Instant end,
      String reason,
      int trainsHandled,
      int routesSet,
      int autoRouted,
      int autoPassed,
      int holds,
      int callsAnswered,
      int callsMissed) {
    this(
        playerId,
        operatorCode,
        zoneCode,
        zoneName,
        start,
        end,
        reason,
        trainsHandled,
        routesSet,
        autoRouted,
        autoPassed,
        holds,
        callsAnswered,
        callsMissed,
        null);
  }

  /**
   * @param parentZoneCode 在中心工位上值守时所属大区的代码；普通控制区为 {@code null} 或空串
   */
  public SignallerDutyEndedEvent(
      UUID playerId,
      String operatorCode,
      String zoneCode,
      String zoneName,
      Instant start,
      Instant end,
      String reason,
      int trainsHandled,
      int routesSet,
      int autoRouted,
      int autoPassed,
      int holds,
      int callsAnswered,
      int callsMissed,
      String parentZoneCode) {
    super(playerId, operatorCode, zoneCode, zoneName);
    this.start = Objects.requireNonNull(start, "start");
    this.end = Objects.requireNonNull(end, "end");
    this.reason = reason == null ? "" : reason;
    this.trainsHandled = trainsHandled;
    this.routesSet = routesSet;
    this.autoRouted = autoRouted;
    this.autoPassed = autoPassed;
    this.holds = holds;
    this.callsAnswered = callsAnswered;
    this.callsMissed = callsMissed;
    this.parentZoneCode = parentZoneCode == null ? "" : parentZoneCode;
  }

  /** 上岗时刻。 */
  public Instant getStart() {
    return start;
  }

  /** 下岗时刻。 */
  public Instant getEnd() {
    return end;
  }

  /** 值守时长。 */
  public Duration getDuration() {
    Duration duration = Duration.between(start, end);
    return duration.isNegative() ? Duration.ZERO : duration;
  }

  /**
   * 下岗原因：OFF_DUTY（自己下岗）、LEFT_CONSOLE（离开控制台超过宽限期）、QUIT（下线）、INCAPACITATED（死亡或换游戏模式）、
   * REVOKED（控制区被删除或改动）、SHUTDOWN（插件关闭或重载、功能关闭）、EXAM（信号员考试交卷或了结，这一班随之结束）。
   */
  public String getReason() {
    return reason;
  }

  /** 拿着许可驶入控制区的车数（含自动通过与代排）。 */
  public int getTrainsHandled() {
    return trainsHandled;
  }

  /** 信号员亲手排列的进路数（含预排）。 */
  public int getRoutesSet() {
    return routesSet;
  }

  /** 等许可超时、由系统代排的车数。 */
  public int getAutoRouted() {
    return autoRouted;
  }

  /** 走自动通过进路驶入的车数。 */
  public int getAutoPassed() {
    return autoPassed;
  }

  /** 扣车次数。 */
  public int getHolds() {
    return holds;
  }

  /** 回复了的驾驶员呼叫。 */
  public int getCallsAnswered() {
    return callsAnswered;
  }

  /** 超时没回复的呼叫。 */
  public int getCallsMissed() {
    return callsMissed;
  }

  /**
   * 在中心工位上值守时所属大区的代码（{@link #getZoneCode()} 为临时代码，见 {@link
   * SignallerDutyStartEvent#getParentZoneCode()}）；普通控制区为空。
   */
  public Optional<String> getParentZoneCode() {
    return parentZoneCode.isEmpty() ? Optional.empty() : Optional.of(parentZoneCode);
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
