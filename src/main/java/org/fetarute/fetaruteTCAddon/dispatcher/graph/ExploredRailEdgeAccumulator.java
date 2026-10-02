package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;

/**
 * 汇总同一 {@link EdgeId} 的多条成功探索候选。
 *
 * <p>长度始终取最短值，但物理足迹保留每条成功候选经过的全部方块。这样道岔存在多个可达进路时，不会因为较短候选先到达就漏掉另一条进路上的真实平交冲突。
 *
 * <p>该累加器由主线程探索任务独占，不提供并发写入保证。
 */
public final class ExploredRailEdgeAccumulator {

  private int shortestLengthBlocks = Integer.MAX_VALUE;
  private final Set<RailFootprintCell> cells = new TreeSet<>();
  private boolean hasCandidate;
  private boolean everyCandidateCaptured = true;

  /**
   * 记录一条已到达目标节点的候选进路。
   *
   * @param lengthBlocks 候选长度
   * @param candidateCells 该候选的实际三维轨迹方块；空集合表示本次足迹取证失败
   */
  public void recordCandidate(int lengthBlocks, Collection<RailFootprintCell> candidateCells) {
    Objects.requireNonNull(candidateCells, "candidateCells");
    recordCandidate(lengthBlocks, candidateCells, !candidateCells.isEmpty());
  }

  /**
   * 记录一条候选进路，并显式声明全过程是否均取得可解释的轨迹。
   *
   * @param lengthBlocks 候选长度
   * @param candidateCells 已成功取得的三维轨迹方块
   * @param footprintCaptured 是否完整取得该候选经过的每段轨迹
   */
  public void recordCandidate(
      int lengthBlocks, Collection<RailFootprintCell> candidateCells, boolean footprintCaptured) {
    if (lengthBlocks <= 0) {
      throw new IllegalArgumentException("lengthBlocks 必须为正数");
    }
    Objects.requireNonNull(candidateCells, "candidateCells");
    hasCandidate = true;
    shortestLengthBlocks = Math.min(shortestLengthBlocks, lengthBlocks);
    if (!footprintCaptured || candidateCells.isEmpty()) {
      everyCandidateCaptured = false;
    }
    cells.addAll(candidateCells);
  }

  /**
   * 生成当前不可变快照。
   *
   * @param explorationComplete 所属探索任务是否已经自然完成
   * @return 长度与足迹快照
   * @throws IllegalStateException 尚未记录任何成功候选时抛出
   */
  public ExploredRailEdge snapshot(boolean explorationComplete) {
    if (!hasCandidate) {
      throw new IllegalStateException("尚未记录成功候选");
    }
    boolean footprintComplete = explorationComplete && everyCandidateCaptured && !cells.isEmpty();
    return new ExploredRailEdge(
        shortestLengthBlocks,
        new RailEdgeFootprint(RailEdgeFootprint.CURRENT_FORMAT_VERSION, footprintComplete, cells));
  }
}
