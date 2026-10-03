package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;

/**
 * 停车位置标：在车站股道上找出各块标志，再按列车节数与行进方向选出车头该停的那一块。
 *
 * <p>标志只认与车站牌子在同一股道上的：从车站牌子所在的轨道往两边走，遇到道岔或别的节点牌子（下一个车站、区间点）就停。
 * 只认车站牌子前方（列车行进方向上）的标志：列车压到车站牌子才开始停站，已经越过的标志够不着。本类不依赖服务器对象，便于单测。
 */
public final class StopMarks {

  /** 沿股道往每一边最多找多远（格）。 */
  public static final int SEARCH_BLOCKS = 96;

  /** 标志在车站牌子后方不超过这么多（格）时仍算在前方（同一段轨道上的测量误差）。 */
  private static final double BEHIND_TOLERANCE_BLOCKS = 0.5;

  /**
   * 轨道上的一块停车位置标。
   *
   * @param rail 标志所在的轨道方块
   * @param point 轨道中心（车头应停的位置）
   * @param sign 适用的节数
   */
  public record Mark(RailBlockPos rail, Vector point, StopMarkSign sign) {
    public Mark {
      Objects.requireNonNull(rail, "rail");
      point = Objects.requireNonNull(point, "point").clone();
      Objects.requireNonNull(sign, "sign");
    }

    @Override
    public Vector point() {
      return point.clone();
    }
  }

  /**
   * 选中的停车位置标。
   *
   * @param mark 标志
   * @param aheadBlocks 标志在车站停车点前方多远（沿列车行进方向，格）
   */
  public record Selected(Mark mark, double aheadBlocks) {}

  private record Step(RailBlockPos pos, int blocks) {}

  private StopMarks() {}

  /**
   * 从车站牌子所在的轨道沿同一股道往两边收集停车位置标。
   *
   * @param start 车站牌子所在的轨道
   * @param maxBlocks 每一边最多走多远（格）
   * @param boundary 这段轨道上有别的节点牌子（走到这里就停）
   * @param marksAt 这段轨道上的停车位置标
   */
  public static List<Mark> scan(
      RailBlockAccess access,
      RailBlockPos start,
      int maxBlocks,
      Predicate<RailBlockPos> boundary,
      Function<RailBlockPos, List<Mark>> marksAt) {
    List<Mark> found = new ArrayList<>(marksAt.apply(start));
    Set<RailBlockPos> visited = new HashSet<>();
    visited.add(start);
    Deque<Step> frontier = new ArrayDeque<>();
    for (RailBlockPos next : access.neighbors(start)) {
      frontier.add(new Step(next, 1));
    }
    while (!frontier.isEmpty()) {
      Step step = frontier.poll();
      if (step.blocks() > maxBlocks || !visited.add(step.pos())) {
        continue;
      }
      Set<RailBlockPos> neighbors = access.neighbors(step.pos());
      if (RailBlockAccess.isJunction(access, step.pos(), neighbors) || boundary.test(step.pos())) {
        continue;
      }
      found.addAll(marksAt.apply(step.pos()));
      for (RailBlockPos next : neighbors) {
        if (!visited.contains(next)) {
          frontier.add(new Step(next, step.blocks() + 1));
        }
      }
    }
    return found;
  }

  /**
   * 按节数与行进方向选出车头该停的标志：节数要对得上、要在车站停车点前方；有几块都合适时取写得最专门的（覆盖节数最少），再取离车站停车点最近的。
   *
   * @param stationPoint 车站停车点（车站牌子的轨道中心）
   * @param travel 列车行进方向
   * @param carriages 列车节数（TrainCarts 车厢数）
   */
  public static Optional<Selected> select(
      List<Mark> marks, Vector stationPoint, Vector travel, int carriages) {
    Selected best = null;
    for (Mark mark : marks) {
      if (!mark.sign().matches(carriages)) {
        continue;
      }
      double ahead = StopAlignment.signedOffset(mark.point(), stationPoint, travel);
      if (Double.isNaN(ahead) || ahead < -BEHIND_TOLERANCE_BLOCKS) {
        continue;
      }
      if (best == null || better(mark, ahead, best)) {
        best = new Selected(mark, Math.max(0.0, ahead));
      }
    }
    return Optional.ofNullable(best);
  }

  private static boolean better(Mark mark, double ahead, Selected best) {
    int breadth = mark.sign().breadth();
    int bestBreadth = best.mark().sign().breadth();
    if (breadth != bestBreadth) {
      return breadth < bestBreadth;
    }
    return ahead < best.aheadBlocks();
  }
}
