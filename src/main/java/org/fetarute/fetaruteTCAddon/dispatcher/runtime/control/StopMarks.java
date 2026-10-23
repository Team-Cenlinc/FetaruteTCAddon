package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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

  private record Step(RailBlockPos pos, double blocks) {}

  private StopMarks() {}

  /**
   * 从车站牌子所在的轨道沿同一股道往两边收集停车位置标，一次走完（见 {@link ScanJob}）。
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
    ScanJob job = new ScanJob(access, start, maxBlocks, inspect, loaded);
    job.step(Integer.MAX_VALUE);
    return job.result();
  }

  /**
   * 可以分段推进的收集：每次只走若干段轨道，长站台可以分到几个 tick 里走完，不卡主线程。
   *
   * <p>从车站牌子所在的轨道往两边走，遇到道岔或别的节点牌子就停；每段轨道只查一次牌子。只在服务器主线程使用。
   */
  public static final class ScanJob {
    private final RailBlockAccess access;
    private final RailBlockPos start;
    private final double maxBlocks;
    private final Function<RailBlockPos, RailSigns> inspect;
    private final Predicate<RailBlockPos> loaded;
    private final List<Mark> found = new ArrayList<>();
    private final Set<RailBlockPos> visited = new HashSet<>();
    private final Deque<Step> frontier = new ArrayDeque<>();
    private boolean started;
    private boolean complete = true;

    /** 参数见 {@link StopMarks#scan}。 */
    public ScanJob(
        RailBlockAccess access,
        RailBlockPos start,
        double maxBlocks,
        Function<RailBlockPos, RailSigns> inspect,
        Predicate<RailBlockPos> loaded) {
      this.access = Objects.requireNonNull(access, "access");
      this.start = Objects.requireNonNull(start, "start");
      this.maxBlocks = maxBlocks;
      this.inspect = Objects.requireNonNull(inspect, "inspect");
      this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    /**
     * 往前走至多 {@code rails} 段轨道。
     *
     * @return 是否已经走完
     */
    public boolean step(int rails) {
      int budget = rails;
      if (!started) {
        started = true;
        found.addAll(inspect.apply(start).marks());
        complete = allLoaded(access, start, loaded);
        visited.add(start);
        for (RailBlockPos next : access.neighbors(start)) {
          frontier.add(new Step(next, access.stepCost(start, next)));
        }
        budget--;
      }
      while (budget > 0 && !frontier.isEmpty()) {
        Step step = frontier.poll();
        if (step.blocks() > maxBlocks || !visited.add(step.pos())) {
          continue;
        }
        budget--;
        Set<RailBlockPos> neighbors = access.neighbors(step.pos());
        if (RailBlockAccess.isJunction(access, step.pos(), neighbors)) {
          continue;
        }
        RailSigns signs = inspect.apply(step.pos());
        if (signs.boundary()) {
          continue;
        }
        found.addAll(signs.marks());
        if (complete && !allLoaded(access, step.pos(), loaded)) {
          complete = false;
        }
        for (RailBlockPos next : neighbors) {
          if (!visited.contains(next)) {
            frontier.add(new Step(next, step.blocks() + access.stepCost(step.pos(), next)));
          }
        }
      }
      return done();
    }

    /** 是否已经走完。 */
    public boolean done() {
      return started && frontier.isEmpty();
    }

    /** 收集结果；还没走完时是到目前为止的结果，记为不完整。 */
    public Scan result() {
      return new Scan(found, complete && done());
    }
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
   * 同 {@link #select}，但先按 {@code preferredCarriages} 选：先到联挂站、要停着等对方挂上来的车按挂上以后整列的节数停，在身后给后到的车留出位置；
   * 没有对得上这个节数的标志时照列车节数选。
   *
   * @param preferredCarriages 挂上以后整列几节；为空或不比 {@code carriages} 多时同 {@link #select}
   */
  public static Optional<Selected> select(
      List<Mark> marks,
      Vector stationPoint,
      Vector direction,
      int carriages,
      OptionalInt preferredCarriages) {
    if (preferredCarriages.isPresent() && preferredCarriages.getAsInt() > carriages) {
      Optional<Selected> preferred =
          select(marks, stationPoint, direction, preferredCarriages.getAsInt());
      if (preferred.isPresent()) {
        return preferred;
      }
    }
    return select(marks, stationPoint, direction, carriages);
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

  private static boolean better(Mark mark, double ahead, Selected best) {
    int breadth = mark.sign().breadth();
    int bestBreadth = best.mark().sign().breadth();
    if (breadth != bestBreadth) {
      return breadth < bestBreadth;
    }
    return ahead < best.aheadBlocks();
  }
}
