package org.fetarute.fetaruteTCAddon.signaller;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 从大区剩下的部分切一片给后来上岗的人（中心工位的临时小区）。本类不依赖服务器对象。
 *
 * <ul>
 *   <li>目标：大区全部单元的工作量 ÷ 上岗后的总人数；
 *   <li>从剩余部分的每个端头单元（在剩余部分里至多一个相邻单元：线路的一端、支线末端、被别人管的那片隔出来的单独一个）出发，沿相邻关系逐个加入单元
 *       （同时有几个可加时取下标小的），工作量达到目标就停；每加一个都不能把剩下的切成更多段（剩下的本就被别人管的那片隔成几段时照样能切），会切断的单元跳过；
 *   <li>各端头一路加出来的每一片都比一比，取工作量最接近目标的；平局取单元少的，再平取端头下标小的；
 *   <li>剩下的至少留一个单元给大区那位；剩下的不足两个单元就切不出来；
 *   <li>没有端头（环线）时，从离大区那位的工位最远的单元起切。
 * </ul>
 */
final class ZoneSplitter {

  /** 工作量比较的容差。 */
  private static final double EPSILON = 1e-9;

  private ZoneSplitter() {}

  /**
   * 一个单元：切的最小单位（一个车站或车库，只隔道岔就相通的合成一个，管理员划好的小区整体一个）。
   *
   * @param id 单元标识（显示与调试用）
   * @param load 工作量
   * @param x 位置（环线时比远近用）
   * @param z 位置
   */
  record Unit(String id, double load, double x, double z) {
    Unit {
      Objects.requireNonNull(id, "id");
    }
  }

  /** 一片候选：哪几个单元、合计工作量、从哪个端头切起。 */
  private record Candidate(Set<Integer> units, double load, int start) {}

  /**
   * 切一片。
   *
   * @param units 大区的全部单元（含已分出去的）；下标即单元编号
   * @param links 相邻关系：一对单元编号（只隔区间）
   * @param remaining 大区剩下、还能切的单元编号
   * @param people 上岗后的总人数（含新来的人）
   * @param anchorX 大区那位的工位位置
   * @param anchorZ 大区那位的工位位置
   * @return 切给新来的人的单元编号；切不出来时为空
   */
  static Optional<Set<Integer>> split(
      List<Unit> units,
      List<int[]> links,
      Set<Integer> remaining,
      int people,
      double anchorX,
      double anchorZ) {
    if (remaining.size() < 2 || people < 2) {
      return Optional.empty();
    }
    double target = units.stream().mapToDouble(Unit::load).sum() / people;
    Map<Integer, Set<Integer>> adjacent = adjacency(links, remaining);
    List<Integer> starts =
        remaining.stream()
            .sorted()
            .filter(unit -> adjacent.getOrDefault(unit, Set.of()).size() <= 1)
            .toList();
    if (starts.isEmpty()) {
      starts = List.of(farthest(units, remaining, anchorX, anchorZ));
    }
    Candidate best = null;
    for (int start : starts) {
      Set<Integer> chosen = new LinkedHashSet<>();
      double load = 0.0;
      OptionalInt next =
          keepsPieces(adjacent, remaining, chosen, start)
              ? OptionalInt.of(start)
              : OptionalInt.empty();
      while (next.isPresent() && chosen.size() + 1 < remaining.size()) {
        chosen.add(next.getAsInt());
        load += units.get(next.getAsInt()).load();
        best = better(best, new Candidate(Set.copyOf(chosen), load, start), target);
        if (load + EPSILON >= target) {
          break;
        }
        next = nextUnit(adjacent, remaining, chosen);
      }
    }
    return best == null ? Optional.empty() : Optional.of(best.units());
  }

  /** 两片里取更接近目标的：工作量差，其次单元数，再其次端头编号。 */
  private static Candidate better(Candidate current, Candidate next, double target) {
    if (current == null) {
      return next;
    }
    double gapCurrent = Math.abs(current.load() - target);
    double gapNext = Math.abs(next.load() - target);
    if (gapNext < gapCurrent - EPSILON) {
      return next;
    }
    if (gapNext > gapCurrent + EPSILON) {
      return current;
    }
    if (next.units().size() != current.units().size()) {
      return next.units().size() < current.units().size() ? next : current;
    }
    return next.start() < current.start() ? next : current;
  }

  /** 已选的单元旁边、加上后剩下的不会多出一段的单元里，编号最小的那个；没有时为空。 */
  private static OptionalInt nextUnit(
      Map<Integer, Set<Integer>> adjacent, Set<Integer> remaining, Set<Integer> chosen) {
    Set<Integer> frontier = new java.util.TreeSet<>();
    for (int unit : chosen) {
      for (int neighbour : adjacent.getOrDefault(unit, Set.of())) {
        if (!chosen.contains(neighbour)) {
          frontier.add(neighbour);
        }
      }
    }
    for (int candidate : frontier) {
      if (keepsPieces(adjacent, remaining, chosen, candidate)) {
        return OptionalInt.of(candidate);
      }
    }
    return OptionalInt.empty();
  }

  /** 再切走 {@code candidate} 之后，剩下的单元不为空，段数也没变多。 */
  private static boolean keepsPieces(
      Map<Integer, Set<Integer>> adjacent,
      Set<Integer> remaining,
      Set<Integer> chosen,
      int candidate) {
    Set<Integer> rest = new HashSet<>(remaining);
    rest.removeAll(chosen);
    Set<Integer> after = new HashSet<>(rest);
    after.remove(candidate);
    return !after.isEmpty() && pieces(adjacent, after) <= pieces(adjacent, rest);
  }

  /** 这些单元按相邻关系分成几段。 */
  private static int pieces(Map<Integer, Set<Integer>> adjacent, Set<Integer> units) {
    Set<Integer> seen = new HashSet<>();
    int count = 0;
    for (int first : units) {
      if (!seen.add(first)) {
        continue;
      }
      count++;
      Deque<Integer> queue = new ArrayDeque<>();
      queue.add(first);
      while (!queue.isEmpty()) {
        int unit = queue.poll();
        for (int neighbour : adjacent.getOrDefault(unit, Set.of())) {
          if (units.contains(neighbour) && seen.add(neighbour)) {
            queue.add(neighbour);
          }
        }
      }
    }
    return count;
  }

  /** 剩余部分里的相邻关系。 */
  private static Map<Integer, Set<Integer>> adjacency(List<int[]> links, Set<Integer> remaining) {
    Map<Integer, Set<Integer>> out = new HashMap<>();
    for (int[] link : links) {
      if (link.length != 2 || link[0] == link[1]) {
        continue;
      }
      if (remaining.contains(link[0]) && remaining.contains(link[1])) {
        out.computeIfAbsent(link[0], ignored -> new HashSet<>()).add(link[1]);
        out.computeIfAbsent(link[1], ignored -> new HashSet<>()).add(link[0]);
      }
    }
    return out;
  }

  /** 剩余部分里离工位最远的单元；一样远取编号小的。 */
  private static int farthest(
      List<Unit> units, Set<Integer> remaining, double anchorX, double anchorZ) {
    int best = -1;
    double bestDistance = -1.0;
    for (int unit : remaining.stream().sorted().toList()) {
      double dx = units.get(unit).x() - anchorX;
      double dz = units.get(unit).z() - anchorZ;
      double distance = dx * dx + dz * dz;
      if (distance > bestDistance + EPSILON) {
        best = unit;
        bestDistance = distance;
      }
    }
    return best;
  }
}
