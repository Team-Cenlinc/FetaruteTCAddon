package org.fetarute.fetaruteTCAddon.coupler;

/**
 * 车钩扳手与拆挂命令能不能动这列受管列车：只动不在运营中的车——终点待命车（没有派车在进行）、已过最后载客站的回库车——且列车静止、没有驾驶员与车掌、
 * 不在停站计时、没挂发车门控，也没有叫车、提前出车扣车与时刻表下一班。非受管列车不受这些限制。纯判定。
 */
public final class CouplerEligibility {

  private CouplerEligibility() {}

  /** 判定结果；除 {@link #ALLOWED} 外都是拒绝的原因（语言键 {@code coupler.deny.<key>}）。 */
  public enum Verdict {
    ALLOWED("allowed"),
    MOVING("moving"),
    DRIVER("driver"),
    GUARD("guard"),
    DWELLING("dwelling"),
    DEPARTURE_GATE("departure-gate"),
    DISPATCHING("dispatching"),
    CALLED("called"),
    EARLY_HOLD("early-hold"),
    TIMETABLE_DUTY("timetable-duty"),
    IN_SERVICE("in-service");

    private final String key;

    Verdict(String key) {
      this.key = key;
    }

    /** 语言键的末段。 */
    public String key() {
      return key;
    }
  }

  /**
   * 判定要用到的事实。
   *
   * @param moving 列车在动
   * @param driverAboard 有驾驶员会话（人工驾驶或 ATO 值乘）
   * @param guardAboard 有车掌值乘
   * @param dwelling 在停站计时
   * @param departureGateHeld 挂着发车门控（停站放行、提前出车或等驾驶员接车）
   * @param layover 是终点待命车
   * @param dispatchPending 待命车正被一张票接走
   * @param called 带叫车标签
   * @param earlyHold 在车库等候提前出车
   * @param timetableDuty 绑着时刻表的一班
   * @param returnOutOfService 回库车且已过最后一个载客站
   */
  public record Facts(
      boolean moving,
      boolean driverAboard,
      boolean guardAboard,
      boolean dwelling,
      boolean departureGateHeld,
      boolean layover,
      boolean dispatchPending,
      boolean called,
      boolean earlyHold,
      boolean timetableDuty,
      boolean returnOutOfService) {}

  /** 判定一列受管列车。 */
  public static Verdict judge(Facts facts) {
    if (facts.moving()) {
      return Verdict.MOVING;
    }
    if (facts.driverAboard()) {
      return Verdict.DRIVER;
    }
    if (facts.guardAboard()) {
      return Verdict.GUARD;
    }
    if (facts.dwelling()) {
      return Verdict.DWELLING;
    }
    if (facts.earlyHold()) {
      return Verdict.EARLY_HOLD;
    }
    if (facts.departureGateHeld()) {
      return Verdict.DEPARTURE_GATE;
    }
    if (facts.called()) {
      return Verdict.CALLED;
    }
    if (facts.timetableDuty()) {
      return Verdict.TIMETABLE_DUTY;
    }
    if (facts.layover()) {
      return facts.dispatchPending() ? Verdict.DISPATCHING : Verdict.ALLOWED;
    }
    return facts.returnOutOfService() ? Verdict.ALLOWED : Verdict.IN_SERVICE;
  }
}
