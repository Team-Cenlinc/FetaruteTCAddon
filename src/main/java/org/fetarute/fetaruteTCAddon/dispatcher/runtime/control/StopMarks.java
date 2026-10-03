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
 * 停车位置标：在车站股道上找出各块标志，再按列车节数与行进方向选出车头该停的那一块，以及自动运行怎么开过去。
 *
 * <p>标志只认与车站牌子在同一股道上的：从车站牌子所在的轨道往两边走，遇到道岔或别的节点牌子（下一个车站、区间点）就停。
 * 只认车站牌子前方（列车行进方向上）的标志：列车压到车站牌子才开始停站，已经越过的标志够不着。本类不依赖服务器对象，便于单测。
 */
public final class StopMarks {

  /** 沿股道往每一边最多找多远（格），按轨道实际长度量（TCCoasters 的一段轨道可能很长）。遇到道岔或别的节点牌子就会先停，这里只防没有边界的长线一直找下去。 */
  public static final double SEARCH_BLOCKS = 512.0;

  /** 标志在车站牌子（或车头）后方不超过这么多（格）时仍算在前方（同一段轨道上的测量误差）。 */
  public static final double BEHIND_TOLERANCE_BLOCKS = 0.5;

  /** 开往标志时至少保持的速度（格/tick）：列车进站时已经很慢也不至于一路蠕行。 */
  static final double MIN_HOLD_BPT = 0.1;

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

  /**
   * 一次收集的结果。
   *
   * @param marks 找到的标志
   * @param complete 沿途轨道都已加载（没加载完时可能漏掉标志，不宜久存）
   */
  public record Scan(List<Mark> marks, boolean complete) {
    public Scan {
      marks = List.copyOf(marks);
    }
  }

  /**
   * 一段轨道上的牌子。
   *
   * @param boundary 有别的节点牌子（车站、区间点、车库、道岔等），走到这里就停
   * @param marks 停车位置标
   */
  public record RailSigns(boolean boundary, List<Mark> marks) {
    /** 没有牌子。 */
    public static final RailSigns NONE = new RailSigns(false, List.of());

    public RailSigns {
      marks = List.copyOf(marks);
    }
  }

  /**
   * 自动运行开往标志的两段：先以 {@code holdBpt} 走 {@code holdBlocks}，再在 {@code brakeBlocks} 内制动停下。
   *
   * @param holdBlocks 保持速度的一段（格）；为 0 时直接制动
   * @param holdBpt 保持的速度（格/tick）
   * @param brakeBlocks 制动的一段（格）
   * @param ticks 预计用时（tick）
   */
  public record Approach(double holdBlocks, double holdBpt, double brakeBlocks, int ticks) {}

  private record Step(RailBlockPos pos, double blocks) {}

  private StopMarks() {}

  /**
   * 从车站牌子所在的轨道沿同一股道往两边收集停车位置标。每段轨道只查一次牌子。
   *
   * @param start 车站牌子所在的轨道（它自己的节点牌子不算边界）
   * @param maxBlocks 每一边最多走多远（格，按 {@link RailBlockAccess#stepCost} 累计）
   * @param inspect 这段轨道上的牌子
   * @param loaded 这个位置所在的区块已加载；沿途有没加载的邻接轨道时结果记为不完整
   */
  public static Scan scan(
      RailBlockAccess access,
      RailBlockPos start,
      double maxBlocks,
      Function<RailBlockPos, RailSigns> inspect,
      Predicate<RailBlockPos> loaded) {
    List<Mark> found = new ArrayList<>(inspect.apply(start).marks());
    boolean complete = allLoaded(access, start, loaded);
    Set<RailBlockPos> visited = new HashSet<>();
    visited.add(start);
    Deque<Step> frontier = new ArrayDeque<>();
    for (RailBlockPos next : access.neighbors(start)) {
      frontier.add(new Step(next, access.stepCost(start, next)));
    }
    while (!frontier.isEmpty()) {
      Step step = frontier.poll();
      if (step.blocks() > maxBlocks || !visited.add(step.pos())) {
        continue;
      }
      Set<RailBlockPos> neighbors = access.neighbors(step.pos());
      if (RailBlockAccess.isJunction(access, step.pos(), neighbors)) {
        continue;
      }
      RailSigns signs = inspect.apply(step.pos());
      if (signs.boundary()) {
        continue;
      }
      found.addAll(signs.marks());
      complete &= allLoaded(access, step.pos(), loaded);
      for (RailBlockPos next : neighbors) {
        if (!visited.contains(next)) {
          frontier.add(new Step(next, step.blocks() + access.stepCost(step.pos(), next)));
        }
      }
    }
    return new Scan(found, complete);
  }

  private static boolean allLoaded(
      RailBlockAccess access, RailBlockPos pos, Predicate<RailBlockPos> loaded) {
    for (RailBlockPos candidate : access.neighborCandidates(pos)) {
      if (!loaded.test(candidate)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 按节数与行进方向选出车头该停的标志：节数要对得上、要在车站停车点前方；有几块都合适时取写得最专门的（覆盖节数最少），再取离车站停车点最近的。
   *
   * @param stationPoint 车站停车点（车站牌子的轨道中心）
   * @param direction 列车在车站股道上的行进方向（见 {@link #orient}）
   * @param carriages 列车节数（TrainCarts 车厢数）
   */
  public static Optional<Selected> select(
      List<Mark> marks, Vector stationPoint, Vector direction, int carriages) {
    Selected best = null;
    for (Mark mark : marks) {
      if (!mark.sign().matches(carriages)) {
        continue;
      }
      double ahead = StopAlignment.signedOffset(mark.point(), stationPoint, direction);
      if (Double.isNaN(ahead) || ahead < -BEHIND_TOLERANCE_BLOCKS) {
        continue;
      }
      double clamped = Math.max(0.0, ahead);
      if (best == null || better(mark, clamped, best)) {
        best = new Selected(mark, clamped);
      }
    }
    return Optional.ofNullable(best);
  }

  /**
   * 列车在车站股道上的行进方向：车站轨道的走向按列车走向定正反。列车还在弯道上时，它此刻的走向与站台轨道差得很远，直接拿来判前后会判错。
   *
   * @param stationAxis 车站轨道的走向（不分正反）；为空或水平分量为零时直接用列车走向
   * @param travel 列车此刻的走向
   */
  public static Vector orient(Vector stationAxis, Vector travel) {
    if (travel == null) {
      return null;
    }
    if (stationAxis == null
        || !(Math.abs(stationAxis.getX()) + Math.abs(stationAxis.getZ()) > 1.0e-6)) {
      return travel.clone();
    }
    double dot = stationAxis.getX() * travel.getX() + stationAxis.getZ() * travel.getZ();
    return dot < 0.0 ? stationAxis.clone().multiply(-1.0) : stationAxis.clone();
  }

  /**
   * 自动运行怎么开到标志：保持进站速度（列车本来就更慢时保持现速，但不低于 {@link #MIN_HOLD_BPT}），在常用制动距离处开始制动，正好停在标志上。 距离不够制动时直接制动。
   *
   * @param distanceBlocks 车头沿轨道到标志的距离（格）
   * @param currentBpt 此刻速度（格/tick）
   * @param approachBpt 进站限速（格/tick）
   * @param decelBpt2 常用制动减速度（格/tick²）
   */
  public static Approach approach(
      double distanceBlocks, double currentBpt, double approachBpt, double decelBpt2) {
    double ceiling = Math.max(MIN_HOLD_BPT, approachBpt);
    double hold = Math.min(Math.max(MIN_HOLD_BPT, currentBpt), ceiling);
    double brake = hold * hold / (2.0 * Math.max(1.0e-4, decelBpt2));
    if (brake + BEHIND_TOLERANCE_BLOCKS >= distanceBlocks) {
      return new Approach(0.0, hold, distanceBlocks, (int) Math.ceil(2.0 * distanceBlocks / hold));
    }
    double holdBlocks = distanceBlocks - brake;
    return new Approach(
        holdBlocks, hold, brake, (int) Math.ceil(holdBlocks / hold + 2.0 * brake / hold));
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
