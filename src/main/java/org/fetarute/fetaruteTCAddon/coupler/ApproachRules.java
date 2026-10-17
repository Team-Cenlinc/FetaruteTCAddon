package org.fetarute.fetaruteTCAddon.coupler;

import java.util.OptionalDouble;

/**
 * 联挂进站每一拍怎么走、什么时候停。纯计算。
 *
 * <p>后到的车从静止起以进站速度沿轨道开向对方，沿轨道看得见对方且空隙小于 {@link #FINAL_BLOCKS} 后降到对位速度，空隙不超过 {@link
 * #LINK_GAP_BLOCKS} 停车去挂。看不见对方时（道岔还没扳到）照常往前走，但走过的距离不超过上限；先看见的是别的列车、对方动了、推不动、超时都停车放弃。
 */
final class ApproachRules {

  /** 空隙小到这么多格就停车去挂。 */
  static final double LINK_GAP_BLOCKS = 0.25;

  /** 看得见对方且空隙小于这么多格时降到对位速度。 */
  static final double FINAL_BLOCKS = 6.0;

  /** 推了这么多 tick 还几乎没动就算推不动。 */
  static final int STALL_TICKS = 40;

  /** 推不动的判定：走不到这么多格。 */
  static final double STALL_BLOCKS = 0.05;

  /** 对方中心挪动超过这么多格就算对方动了。 */
  static final double STANDING_MOVED_BLOCKS = 0.5;

  private ApproachRules() {}

  /** 这一拍的判定。 */
  enum Step {
    CONTINUE,
    ARRIVED,
    /** 走过了上限仍没碰到。 */
    OVERRUN,
    /** 推不动或超时。 */
    STALLED,
    /** 先碰到的是别的列车（或别的列车中间的车厢）。 */
    OTHER_TRAIN,
    /** 对方动了。 */
    STANDING_MOVED
  }

  /**
   * 一拍看到的情况。
   *
   * @param gap 沿轨道到对方的空隙；看不见对方时为空
   * @param otherTrainAhead 先碰到的是别的列车
   * @param travelled 自起步走过的距离（格）
   * @param budget 最多走多少格
   * @param ticks 自起步过了几 tick
   * @param maxTicks 最多推多少 tick
   * @param standingMoved 对方中心挪动的距离（格）
   */
  record Observation(
      OptionalDouble gap,
      boolean otherTrainAhead,
      double travelled,
      double budget,
      int ticks,
      int maxTicks,
      double standingMoved) {}

  /** 判这一拍。 */
  static Step step(Observation seen) {
    if (seen.otherTrainAhead()) {
      return Step.OTHER_TRAIN;
    }
    if (seen.standingMoved() > STANDING_MOVED_BLOCKS) {
      return Step.STANDING_MOVED;
    }
    if (seen.gap().isPresent() && seen.gap().getAsDouble() <= LINK_GAP_BLOCKS) {
      return Step.ARRIVED;
    }
    if (seen.travelled() > seen.budget()) {
      return Step.OVERRUN;
    }
    if (seen.ticks() > seen.maxTicks()
        || (seen.ticks() > STALL_TICKS && seen.travelled() < STALL_BLOCKS)) {
      return Step.STALLED;
    }
    return Step.CONTINUE;
  }

  /**
   * 这一拍的速度（格/tick）。
   *
   * @param lastGap 最近一次看到的空隙；看不见对方时为空
   * @param approach 进站速度（格/tick）
   * @param creep 对位速度（格/tick）
   */
  static double speed(OptionalDouble lastGap, double approach, double creep) {
    if (lastGap.isPresent() && lastGap.getAsDouble() < FINAL_BLOCKS) {
      return Math.min(approach, creep);
    }
    return approach;
  }

  /** 进站最多推多少 tick：按进站速度走完上限、按对位速度走完最后一段，再加 10 秒余量。 */
  static int maxTicks(
      double budgetBlocks, double approachBlocksPerTick, double creepBlocksPerTick) {
    double approach = approachBlocksPerTick > 0.0 ? approachBlocksPerTick : 0.05;
    double creep = creepBlocksPerTick > 0.0 ? Math.min(creepBlocksPerTick, approach) : approach;
    return (int) Math.ceil(budgetBlocks / approach + FINAL_BLOCKS / creep) + 200;
  }
}
