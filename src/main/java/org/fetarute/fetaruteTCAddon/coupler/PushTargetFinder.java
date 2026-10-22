package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.MinecartMemberStore;
import com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;

/**
 * 推车前沿轨道找前方的车：从端车中心朝开着的那一端沿轨道走（弯道、坡道照走，道岔按此刻的开向），碰到的第一节别的列车的车厢就是对方。
 * 对方须是它那列车的端车；中间隔着别的车、轨道走到头或超出距离都算没找到。只走已加载的区块：走到没加载的区块就停（不让走行点去加载它）， 记为前方区块没加载。只在服务器主线程使用。
 */
final class PushTargetFinder {

  /** 沿轨道每步走多少格。 */
  private static final double STEP_BLOCKS = 0.25;

  /** 走到的点离某节车厢中心多近算碰到它（水平格数）。 */
  private static final double MATCH_HORIZONTAL_BLOCKS = 0.6;

  /** 走到的点与车厢中心的高差上限（格）。 */
  private static final double MATCH_VERTICAL_BLOCKS = 1.5;

  /** 对方车厢的半车长加车钩长按这么多格留余量（格）。 */
  private static final double OTHER_CAR_ALLOWANCE_BLOCKS = 12.0;

  private PushTargetFinder() {}

  /** 没找到的原因。 */
  enum Failure {
    /** 端车不在轨道上，或端车所在处的区块没加载。 */
    NO_TRACK,
    /** 距离内没有车，或轨道走到了头、道岔没开向对方。 */
    NOT_FOUND,
    /** 先碰到的是别的列车中间的车厢。 */
    MIDDLE_CAR,
    /** 沿轨道走到了没加载的区块，走到那里为止没碰到车（没有玩家在附近时，远处没有车的区块常常没加载）。 */
    UNLOADED_AHEAD
  }

  /** 区块是否加载按走行点周围这么多格查。 */
  private static final int LOADED_CHECK_BLOCKS = 2;

  /**
   * 找到的对方。
   *
   * @param member 对方端车
   * @param end 对方端车朝着我们的那一端
   * @param gap 两车之间的空隙（沿轨道，格；不小于 0）
   * @param pathCells 从本车端车中心沿轨道走到对方端车中心经过的轨道方块（推车会走过的地方）
   */
  record Target(
      MinecartMember<?> member,
      CouplerState.End end,
      double gap,
      Set<RailFootprintCell> pathCells) {

    Target {
      pathCells = Set.copyOf(pathCells);
    }
  }

  /** 附近别的列车的一节车厢与它的中心（找车前取一次）。 */
  private record Candidate(MinecartMember<?> member, Vector center) {}

  /** 找的结果：找到时 {@code target} 有值，否则 {@code failure} 有值。 */
  record Search(Optional<Target> target, Optional<Failure> failure) {

    static Search found(Target target) {
      return new Search(Optional.of(target), Optional.empty());
    }

    static Search failed(Failure failure) {
      return new Search(Optional.empty(), Optional.of(failure));
    }
  }

  /**
   * 从端车 {@code member} 的 {@code end} 一端往外找。
   *
   * @param maxGap 两车空隙最多多少格
   */
  static Search find(MinecartMember<?> member, CouplerState.End end, double maxGap) {
    MinecartGroup group = member == null ? null : member.getGroup();
    World world = group == null ? null : group.getWorld();
    Optional<Vector> center = CarEnds.center(member);
    Optional<Vector> forward = CarEnds.forward(member);
    if (world == null || center.isEmpty() || forward.isEmpty()) {
      return Search.failed(Failure.NO_TRACK);
    }
    double reach =
        CarEnds.halfLength(member)
            + CarEnds.couplerLength(member)
            + maxGap
            + OTHER_CAR_ALLOWANCE_BLOCKS;
    if (!WorldUtil.areBlocksLoaded(
        world,
        (int) Math.floor(center.get().getX()),
        (int) Math.floor(center.get().getZ()),
        LOADED_CHECK_BLOCKS)) {
      return Search.failed(Failure.NO_TRACK);
    }
    List<Candidate> candidates = nearbyCars(world, center.get(), reach + 2.0, group);
    if (candidates.isEmpty()) {
      return Search.failed(Failure.NOT_FOUND);
    }
    TrackWalkingPoint point;
    try {
      point =
          new TrackWalkingPoint(
              center.get().toLocation(world), CarEnds.outward(forward.get(), end));
      if (point.failReason != TrackWalkingPoint.FailReason.NONE) {
        return Search.failed(Failure.NO_TRACK);
      }
      point.skipFirst();
      if (!point.move(0.0)) {
        return Search.failed(Failure.NO_TRACK);
      }
    } catch (RuntimeException | LinkageError ex) {
      return Search.failed(Failure.NO_TRACK);
    }
    double walked = 0.0;
    Set<RailFootprintCell> cells = new TreeSet<>();
    try {
      addCell(cells, point);
      long checkedArea = Long.MIN_VALUE;
      while (walked < reach) {
        Vector at = point.state.positionLocation().toVector();
        long area = chunkArea(at);
        if (area != checkedArea) {
          if (!WorldUtil.areBlocksLoaded(
              world,
              (int) Math.floor(at.getX()),
              (int) Math.floor(at.getZ()),
              LOADED_CHECK_BLOCKS)) {
            return Search.failed(Failure.UNLOADED_AHEAD);
          }
          checkedArea = area;
        }
        boolean full = point.move(STEP_BLOCKS);
        walked += full ? STEP_BLOCKS : point.moved;
        addCell(cells, point);
        Vector position = point.state.positionLocation().toVector();
        Optional<Candidate> hit = firstHit(position, candidates);
        if (hit.isPresent()) {
          // 碰到时还差一小段才到对方车厢中心，补上这段才是沿轨道的距离。
          double remaining = horizontalDistance(position, hit.get().center());
          return judge(member, hit.get().member(), walked + remaining, maxGap, cells);
        }
        if (!full) {
          break;
        }
      }
    } catch (RuntimeException | LinkageError ex) {
      return Search.failed(Failure.NOT_FOUND);
    }
    return Search.failed(Failure.NOT_FOUND);
  }

  private static Search judge(
      MinecartMember<?> member,
      MinecartMember<?> hit,
      double walked,
      double maxGap,
      Set<RailFootprintCell> cells) {
    MinecartGroup other = hit.getGroup();
    if (other == null || (hit != other.head() && hit != other.tail())) {
      return Search.failed(Failure.MIDDLE_CAR);
    }
    Optional<CouplerState.End> facing = CarEnds.facingEnd(hit, member);
    if (facing.isEmpty()) {
      return Search.failed(Failure.NOT_FOUND);
    }
    double gap = walked - CarEnds.coupledDistance(member, hit);
    if (gap > maxGap) {
      return Search.failed(Failure.NOT_FOUND);
    }
    return Search.found(new Target(hit, facing.get(), Math.max(0.0, gap), cells));
  }

  /** 走行点周围 {@link #LOADED_CHECK_BLOCKS} 格涉及的区块范围（四个区块坐标拼成一个键）：变了才重查加载。 */
  private static long chunkArea(Vector at) {
    int x = (int) Math.floor(at.getX());
    int z = (int) Math.floor(at.getZ());
    long minX = (x - LOADED_CHECK_BLOCKS) >> 4;
    long maxX = (x + LOADED_CHECK_BLOCKS) >> 4;
    long minZ = (z - LOADED_CHECK_BLOCKS) >> 4;
    long maxZ = (z + LOADED_CHECK_BLOCKS) >> 4;
    return ((minX & 0xFFFFL) << 48)
        | ((maxX & 0xFFFFL) << 32)
        | ((minZ & 0xFFFFL) << 16)
        | (maxZ & 0xFFFFL);
  }

  /** 记下走到的这一步所在的轨道方块。 */
  private static void addCell(Set<RailFootprintCell> cells, TrackWalkingPoint point) {
    Block rail = point.state.railBlock();
    if (rail != null) {
      cells.add(new RailFootprintCell(rail.getX(), rail.getY(), rail.getZ()));
    }
  }

  /** 走到的点碰到的车厢（取最近的一节）。 */
  private static Optional<Candidate> firstHit(Vector position, List<Candidate> candidates) {
    Candidate best = null;
    double bestDistance = Double.MAX_VALUE;
    for (Candidate candidate : candidates) {
      double horizontal = horizontalDistance(position, candidate.center());
      if (touches(position, candidate.center()) && horizontal < bestDistance) {
        best = candidate;
        bestDistance = horizontal;
      }
    }
    return Optional.ofNullable(best);
  }

  /** 走到的点算不算碰到中心在 {@code center} 的车厢。纯计算。 */
  static boolean touches(Vector position, Vector center) {
    return horizontalDistance(position, center) <= MATCH_HORIZONTAL_BLOCKS
        && Math.abs(position.getY() - center.getY()) <= MATCH_VERTICAL_BLOCKS;
  }

  private static double horizontalDistance(Vector first, Vector second) {
    double dx = first.getX() - second.getX();
    double dz = first.getZ() - second.getZ();
    return Math.sqrt(dx * dx + dz * dz);
  }

  /** 附近别的列车的车厢与各自的中心。 */
  private static List<Candidate> nearbyCars(
      World world, Vector center, double radius, MinecartGroup self) {
    List<Candidate> out = new ArrayList<>();
    for (Entity entity :
        world.getNearbyEntities(center.toLocation(world), radius, radius, radius)) {
      MinecartMember<?> member = MinecartMemberStore.getFromEntity(entity);
      if (member != null
          && !member.isUnloaded()
          && member.getGroup() != null
          && member.getGroup() != self) {
        CarEnds.center(member).ifPresent(at -> out.add(new Candidate(member, at)));
      }
    }
    return out;
  }
}
