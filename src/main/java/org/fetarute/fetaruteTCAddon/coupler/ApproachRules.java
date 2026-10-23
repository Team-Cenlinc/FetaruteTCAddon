package org.fetarute.fetaruteTCAddon.coupler;

import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;

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

  /**
   * 推车往前找车时先碰到的别的列车是不是不在计划进路上（找车顺着道岔此刻的开向走岔了）。纯计算。
   *
   * @param routeEdges 计划进路经过的区间；为空（算不出）时一律当挡路
   * @param headEdges 推着的车车头压着的区间；为空表示车头不在任何区间上（例如车库股道尽头）
   * @param hitEdges 碰到的那节车厢压着的区间；为空（查不到）时当挡路
   * @return 车头还在计划进路上、而那节车厢压着的区间都不在计划进路上时为 true
   */
  static boolean obstacleOffRoute(
      java.util.Set<EdgeId> routeEdges,
      java.util.Set<EdgeId> headEdges,
      java.util.Set<EdgeId> hitEdges) {
    if (routeEdges == null || routeEdges.isEmpty() || hitEdges == null || hitEdges.isEmpty()) {
      return false;
    }
    if (headEdges != null
        && !headEdges.isEmpty()
        && java.util.Collections.disjoint(headEdges, routeEdges)) {
      // 车头已经压在进路以外：真开进了那一支，那辆车就在前面
      return false;
    }
    return java.util.Collections.disjoint(hitEdges, routeEdges);
  }

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

  /**
   * 对方最近一节车厢离车头（直线）不到这么多格时，前方区块没加载也不再往前推：按 TrainCarts 保持区块半径 1（每节车厢周围保留 3×3 区块），两节车厢这么近时沿途区块本该已加载，
   * 看不见就是不对劲。
   */
  static final double UNLOADED_NEAR_BLOCKS = 32.0;

  /**
   * 找车没找到时算不算挡路：车头不在轨道上（或车头所在处区块没加载）、先碰到别的列车中间的车厢时不再往前推；距离内没有车只是还没看见对方，
   * 照常往前推（不超过推车距离与超时的上限）；前方区块没加载时对方还远就照常推，已经很近（{@link #UNLOADED_NEAR_BLOCKS} 以内）就不推。
   *
   * @param standingDistance 对方最近一节车厢离车头的直线距离（格；与 {@link #UNLOADED_NEAR_BLOCKS} 同按车厢周围保留的区块推出）；量不出时为
   *     {@code NaN}（按很近处理）
   */
  static boolean finderBlocks(PushTargetFinder.Failure failure, double standingDistance) {
    if (failure == PushTargetFinder.Failure.NO_TRACK
        || failure == PushTargetFinder.Failure.MIDDLE_CAR) {
      return true;
    }
    return failure == PushTargetFinder.Failure.UNLOADED_AHEAD
        && !(standingDistance >= UNLOADED_NEAR_BLOCKS);
  }
}
