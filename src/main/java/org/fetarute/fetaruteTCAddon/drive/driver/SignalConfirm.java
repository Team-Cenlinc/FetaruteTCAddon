package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 信号确认：行车中信号变严、稳定一小段后要驾驶员右键确认；迟迟不确认先常用制动，再不确认紧急制动。
 *
 * <p>standard 级只对“减速”和“停车”要求确认，simulation 级每次变严都要。信号放宽时不用确认，正在等的确认随之作废。本类不依赖服务器对象。
 */
public final class SignalConfirm {

  /** 信号变严后稳定这么久才要求确认（滤掉闪动）。 */
  static final long DEBOUNCE_TICKS = 10L;

  /** 这么久不确认就常用制动。 */
  static final long SERVICE_AFTER_TICKS = 80L;

  /** 这么久不确认就紧急制动。 */
  static final long EMERGENCY_AFTER_TICKS = 160L;

  /** 介入方式。 */
  public enum Intervention {
    NONE,
    SERVICE,
    EMERGENCY
  }

  private SignalAspect accepted = SignalAspect.PROCEED;
  private SignalAspect candidate;
  private long candidateSince;
  private SignalAspect pendingAspect;
  private long pendingSince = -1L;
  private boolean missCounted;

  private int confirmations;
  private int misses;
  private long totalReactionTicks;

  /**
   * 观察这一 tick 的信号。
   *
   * @param moving 列车是否在动（停着时信号变严不要求确认）
   * @param everyRestriction 每次变严都要确认（simulation 级）
   */
  public void observe(SignalAspect aspect, long nowTick, boolean moving, boolean everyRestriction) {
    if (aspect == null) {
      return;
    }
    if (rank(aspect) <= rank(accepted)) {
      // 放宽或不变：直接接受；正在等的确认作废。
      accepted = aspect;
      candidate = null;
      if (pendingSince >= 0L && rank(aspect) < rank(pendingAspect)) {
        pendingSince = -1L;
        pendingAspect = null;
      }
      return;
    }
    if (pendingSince >= 0L) {
      if (rank(aspect) > rank(pendingAspect)) {
        pendingAspect = aspect;
      }
      return;
    }
    if (candidate != aspect) {
      candidate = aspect;
      candidateSince = nowTick;
      return;
    }
    if (nowTick - candidateSince < DEBOUNCE_TICKS) {
      return;
    }
    boolean required =
        everyRestriction || aspect == SignalAspect.CAUTION || aspect == SignalAspect.STOP;
    candidate = null;
    if (!required || !moving) {
      accepted = aspect;
      return;
    }
    pendingAspect = aspect;
    pendingSince = nowTick;
    missCounted = false;
  }

  /** 转换驾驶方式时清掉等着的确认（ATO 下不要求确认，转回人工后从当时的信号重新开始）。 */
  public void reset() {
    accepted = SignalAspect.PROCEED;
    candidate = null;
    pendingAspect = null;
    pendingSince = -1L;
  }

  /** 是否在等驾驶员确认。 */
  public boolean pending() {
    return pendingSince >= 0L;
  }

  /** 等确认的信号；没有时为 {@code null}。 */
  public SignalAspect pendingAspect() {
    return pendingAspect;
  }

  /**
   * 驾驶员右键确认。
   *
   * @return 确认的反应时间（tick）；没有在等确认时为空
   */
  public OptionalLong acknowledge(long nowTick) {
    if (pendingSince < 0L) {
      return OptionalLong.empty();
    }
    long reaction = Math.max(0L, nowTick - pendingSince);
    confirmations++;
    totalReactionTicks += reaction;
    accepted = pendingAspect;
    pendingSince = -1L;
    pendingAspect = null;
    return OptionalLong.of(reaction);
  }

  /** 迟迟不确认时的介入；行车中才升级。 */
  public Intervention intervention(long nowTick, boolean moving) {
    if (pendingSince < 0L || !moving) {
      return Intervention.NONE;
    }
    long age = nowTick - pendingSince;
    if (age >= SERVICE_AFTER_TICKS && !missCounted) {
      missCounted = true;
      misses++;
    }
    if (age >= EMERGENCY_AFTER_TICKS) {
      return Intervention.EMERGENCY;
    }
    return age >= SERVICE_AFTER_TICKS ? Intervention.SERVICE : Intervention.NONE;
  }

  public int confirmations() {
    return confirmations;
  }

  public int misses() {
    return misses;
  }

  /** 平均反应时间（秒）；没有确认过时为 0。 */
  public double averageReactionSeconds() {
    return confirmations == 0 ? 0.0 : totalReactionTicks / 20.0 / confirmations;
  }

  private static int rank(SignalAspect aspect) {
    return switch (aspect) {
      case PROCEED -> 0;
      case PROCEED_WITH_CAUTION -> 1;
      case CAUTION -> 2;
      case STOP -> 3;
    };
  }
}
