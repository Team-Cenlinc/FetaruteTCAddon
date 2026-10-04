package org.fetarute.fetaruteTCAddon.drive.sound;

import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection;

/**
 * 按驾驶状态的变化决定这一帧该响哪些提示音：大多数只在状态出现的那一刻响一次，信号待确认与超速红区持续期间按固定间隔重复。
 *
 * <p>每个驾驶会话一个实例。本类不依赖服务器对象。
 */
public final class DriveCueTracker {

  /** 信号待确认时的重复间隔（tick）。 */
  static final long PENDING_REPEAT_TICKS = 20L;

  /** 超速红区时的重复间隔（tick）。 */
  static final long OVERSPEED_REPEAT_TICKS = 10L;

  /** 两次“信号转宽”至少间隔（tick），滤掉调度重算造成的来回跳动。 */
  static final long CLEAR_MIN_GAP_TICKS = 40L;

  /**
   * 这一帧的驾驶状态。
   *
   * @param signalPending 信号变严，正等驾驶员确认
   * @param aspectRank 行车许可的严格程度：0 允许、1 注意、2 减速、3 停车；没有许可或 ATO 时为 -1
   * @param intervention 防护此刻的介入方式
   * @param overspeedRed 超速进入红区
   * @param brakeAdvice 引导提示开始制动
   * @param stationPhase 停站阶段；不在停站时为 {@code null}
   * @param departurePending ATO 等驾驶员确认发车
   */
  public record Snapshot(
      boolean signalPending,
      int aspectRank,
      DriverProtection.Intervention intervention,
      boolean overspeedRed,
      boolean brakeAdvice,
      DriverStationStop.Phase stationPhase,
      boolean departurePending) {

    public Snapshot {
      intervention = intervention == null ? DriverProtection.Intervention.NONE : intervention;
    }

    /** 什么都没有的状态。 */
    public static Snapshot idle() {
      return new Snapshot(false, -1, DriverProtection.Intervention.NONE, false, false, null, false);
    }
  }

  private Snapshot last = Snapshot.idle();
  private long lastPendingTick = Long.MIN_VALUE / 2;
  private long lastOverspeedTick = Long.MIN_VALUE / 2;
  private long lastClearTick = Long.MIN_VALUE / 2;

  /** 观察这一帧，返回该响的提示音（按先后）。 */
  public List<DriveCue> observe(Snapshot now, long tick) {
    List<DriveCue> cues = new ArrayList<>(2);
    Snapshot previous = last;
    last = now;

    if (now.signalPending()
        && (!previous.signalPending() || tick - lastPendingTick >= PENDING_REPEAT_TICKS)) {
      cues.add(DriveCue.SIGNAL_RESTRICTIVE);
      lastPendingTick = tick;
    }
    if (previous.aspectRank() >= 0
        && now.aspectRank() >= 0
        && now.aspectRank() < previous.aspectRank()
        && tick - lastClearTick >= CLEAR_MIN_GAP_TICKS) {
      cues.add(DriveCue.SIGNAL_CLEAR);
      lastClearTick = tick;
    }

    DriverProtection.Intervention before = previous.intervention();
    DriverProtection.Intervention current = now.intervention();
    if (current.ordinal() > before.ordinal()) {
      if (current == DriverProtection.Intervention.SERVICE) {
        cues.add(DriveCue.ATP_BRAKE);
      } else if (current != DriverProtection.Intervention.NONE
          && before.ordinal() < DriverProtection.Intervention.EMERGENCY.ordinal()) {
        cues.add(DriveCue.EMERGENCY);
      }
    }

    if (now.overspeedRed()
        && (!previous.overspeedRed() || tick - lastOverspeedTick >= OVERSPEED_REPEAT_TICKS)) {
      cues.add(DriveCue.OVERSPEED);
      lastOverspeedTick = tick;
    }
    if (now.brakeAdvice() && !previous.brakeAdvice()) {
      cues.add(DriveCue.BRAKE_ADVICE);
    }
    if (now.stationPhase() != previous.stationPhase()) {
      if (now.stationPhase() == DriverStationStop.Phase.OPEN_DOORS) {
        cues.add(DriveCue.DOORS_RELEASED);
      } else if (now.stationPhase() == DriverStationStop.Phase.DEPART) {
        cues.add(DriveCue.DEPART);
      }
    }
    if (now.departurePending() && !previous.departurePending()) {
      cues.add(DriveCue.ATO_CONFIRM);
    }
    return cues;
  }
}
