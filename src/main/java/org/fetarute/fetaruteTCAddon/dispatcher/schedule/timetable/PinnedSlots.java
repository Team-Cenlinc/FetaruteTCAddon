package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.Pin;

/**
 * 把运行计划书的定点班次套到网格上（用户定：替换同方向最近的格位、硬钉住；写了加开的另加一班）。
 *
 * <p>替换时相邻的前后各一班向它平滑一半偏差：定点把一个格位挪开 Δ 秒，前后两个班距就一个变成 T+Δ、一个变成 T−Δ； 前后各挪 Δ/2 之后两侧班距变成 T+Δ/2 与
 * T−Δ/2，乘客看到的不匀减半。平滑只挪名义时刻，挪出来的冲突由预订表按只延后处理； 平滑会让相邻一班越过定点或越过它另一侧的那一班时不挪。定点班次本身一秒不动。
 *
 * <p>挪动顺着车辆接续传下去：被挪的每一班（定点与平滑的那两班），它的车到终点之后本来要接的那一班（在原网格上按先到先接、闲置不超过上限配出来），
 * 挪到这辆车新的就绪时刻之后、闲置上限之内刚好够得着为止——本来就够得着的不挪。否则车提前到了换不了车的折返站（没有出入库），
 * 本来要接的那班要等过闲置上限、晚到的又赶不上，派车就会一边退掉到站的班次、一边退掉没车发的班次。只挪够用的秒数，
 * 是因为跟着挪的班次离开了周期层排好的位置，挪得越多越容易撞上别的线路。传到终点能出入库（车可以回库、由库里另出一辆）为止， 至多 {@value #MAX_HOPS} 程。
 */
final class PinnedSlots {

  /** 接续最多传几程：两端都换不了车的线上偏差会一直绕回来，传下去就是整条线永久平移，到此为止交给派车。 */
  static final int MAX_HOPS = 4;

  private PinnedSlots() {}

  /**
   * 车辆在端点怎么接续，与派车同一套规则。
   *
   * @param origin route → 起点节点
   * @param terminal route → 终点节点
   * @param pool route → 车池（不同车池的车不互接）
   * @param turnaround route → 终点折返时间
   * @param swapsAtTerminal 终点能出入库换车的 route（以车库收尾、或终点有回库线路）
   * @param maxIdle 车在端点等下一班的上限
   */
  record Turns(
      Map<UUID, String> origin,
      Map<UUID, String> terminal,
      Map<UUID, String> pool,
      Map<UUID, Integer> turnaround,
      Set<UUID> swapsAtTerminal,
      int maxIdle) {

    /** 不传接续：每条 route 的终点都当作能换车。 */
    static final Turns NONE = new Turns(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), 0);

    Turns {
      origin = Map.copyOf(origin);
      terminal = Map.copyOf(terminal);
      pool = Map.copyOf(pool);
      turnaround = Map.copyOf(turnaround);
      swapsAtTerminal = Set.copyOf(swapsAtTerminal);
    }

    /** 这条 route 的终点能不能换车；不知道终点的当作能。 */
    boolean swaps(UUID routeId) {
      return !terminal.containsKey(routeId) || swapsAtTerminal.contains(routeId);
    }
  }

  /**
   * 套用定点班次。
   *
   * @param placed 网格上的班次
   * @param options 编表参数（定点班次、计划窗口）
   * @param gridGroups 上网格的交路组
   * @param operationPlans 运营 route
   * @param candidateIndexByRoute route → 在 {@code operationPlans} 里的下标
   * @param runByRoute route → 全程时分
   * @param turns 车辆在端点怎么接续：挪动顺着它传到对面
   * @param notes 套用说明与忽略原因，进报告
   * @return 套用后的班次（顺序不保证，调用方会再排）
   */
  static List<TimetableBuilder.Placed> apply(
      List<TimetableBuilder.Placed> placed,
      TimetableBuildOptions options,
      List<ServiceGroupClassifier.Group> gridGroups,
      List<TimetableRoutePlan> operationPlans,
      Map<UUID, Integer> candidateIndexByRoute,
      Map<UUID, Integer> runByRoute,
      Turns turns,
      List<String> notes) {
    List<Pin> pins = options.schedule().pins();
    if (pins.isEmpty()) {
      return placed;
    }
    List<TimetableBuilder.Placed> out = new ArrayList<>(placed);
    Map<Integer, Integer> shifted = new HashMap<>();
    int start = options.serviceStartSecondOfDay();
    int horizon = options.horizonSeconds();
    List<Pin> ordered = new ArrayList<>(pins);
    ordered.sort(Comparator.comparingInt(Pin::secondOfDay).thenComparing(Pin::routeCode));
    for (Pin pin : ordered) {
      String label = clock(pin.secondOfDay()) + " " + pin.routeCode();
      Optional<TimetableRoutePlan> plan =
          operationPlans.stream()
              .filter(candidate -> candidate.routeCode().equals(pin.routeCode()))
              .filter(candidate -> candidateIndexByRoute.containsKey(candidate.routeId()))
              .findFirst();
      if (plan.isEmpty()) {
        notes.add("定点 " + label + "：不是本次编表上网格的运营 route，忽略");
        continue;
      }
      UUID routeId = plan.get().routeId();
      Optional<ServiceGroupClassifier.Direction> direction = directionOf(gridGroups, routeId);
      if (direction.isEmpty()) {
        notes.add("定点 " + label + "：找不到它所在的方向，忽略");
        continue;
      }
      int at = pin.secondOfDay() - start;
      int run = runByRoute.getOrDefault(routeId, 0);
      if (at < 0 || at + run > horizon) {
        notes.add("定点 " + label + "：在计划窗口之外或跑不完，忽略");
        continue;
      }
      int candidate = direction.get().routeIds().indexOf(routeId);
      String key = direction.get().key();
      List<Integer> sameDirection = new ArrayList<>();
      for (int i = 0; i < out.size(); i++) {
        if (out.get(i).direction().key().equals(key)) {
          sameDirection.add(i);
        }
      }
      sameDirection.sort(Comparator.comparingInt(i -> out.get(i).slot().departureSeconds()));
      int nearest = -1;
      for (int position = 0; position < sameDirection.size(); position++) {
        TimetableBuilder.Placed item = out.get(sameDirection.get(position));
        if (item.pinned()) {
          continue;
        }
        if (nearest < 0
            || Math.abs(item.slot().departureSeconds() - at)
                < Math.abs(out.get(sameDirection.get(nearest)).slot().departureSeconds() - at)) {
          nearest = position;
        }
      }
      int slotIndex = nearest < 0 ? -1 : out.get(sameDirection.get(nearest)).slot().slot();
      TimetableBuilder.Placed pinned =
          new TimetableBuilder.Placed(
              new GroupGrid.Slot(at, routeId, candidate, slotIndex), direction.get(), true);
      if (pin.extra() || nearest < 0) {
        out.add(pinned);
        notes.add("定点 " + label + "：加开一班");
        continue;
      }
      int replaced = out.get(sameDirection.get(nearest)).slot().departureSeconds();
      out.set(sameDirection.get(nearest), pinned);
      if (at != replaced) {
        shifted.put(sameDirection.get(nearest), at - replaced);
      }
      int shift = (at - replaced) / 2;
      if (shift != 0) {
        smooth(out, sameDirection, nearest - 1, shift, at, true, shifted);
        smooth(out, sameDirection, nearest + 1, shift, at, false, shifted);
      }
      notes.add(
          String.format(
              Locale.ROOT,
              "定点 %s：替换 %s 的格位（偏 %+d s），前后各一班平滑 %+d s",
              label,
              clock(replaced + start),
              at - replaced,
              shift));
    }
    int carried = carry(out, placed, shifted, runByRoute, turns);
    if (carried > 0) {
      notes.add("定点挪动顺着车辆接续传到 " + carried + " 班（车在换不了车的折返站接这几班）");
    }
    return out;
  }

  /**
   * 把挪动顺着车辆接续传下去（见类说明）。接续那一班是定点、已经挪过、本来就够得着、或挪了会越过同方向相邻一班时不挪。
   *
   * @param out 套用中的班次，下标与 {@code original} 对齐（加开的在末尾）
   * @param original 套用前的网格
   * @param shifted 已经挪过的班次下标 → 挪了多少秒；跟着挪的也记进去
   * @return 跟着挪的班次数
   */
  private static int carry(
      List<TimetableBuilder.Placed> out,
      List<TimetableBuilder.Placed> original,
      Map<Integer, Integer> shifted,
      Map<UUID, Integer> runByRoute,
      Turns turns) {
    if (shifted.isEmpty()) {
      return 0;
    }
    // 接续按"套用后的 route、套用前的时刻"算：定点替换了同方向另一条 route 的格位时，那辆车按定点的 route 到它的终点，
    // 不按被替换的那条。
    List<TimetableBuilder.Placed> basis = new ArrayList<>(original.size());
    for (int i = 0; i < original.size(); i++) {
      GroupGrid.Slot before = original.get(i).slot();
      GroupGrid.Slot after = out.get(i).slot();
      basis.add(
          after.routeId().equals(before.routeId())
              ? original.get(i)
              : new TimetableBuilder.Placed(
                  new GroupGrid.Slot(
                      before.departureSeconds(),
                      after.routeId(),
                      after.candidateIndex(),
                      before.slot()),
                  out.get(i).direction(),
                  out.get(i).pinned()));
    }
    Map<Integer, Integer> next = continuations(basis, runByRoute, turns);
    Deque<int[]> pending = new ArrayDeque<>();
    shifted.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> pending.add(new int[] {entry.getKey(), entry.getValue(), 0}));
    int carried = 0;
    while (!pending.isEmpty()) {
      int[] item = pending.poll();
      int index = item[0];
      UUID routeId = out.get(index).slot().routeId();
      if (item[2] >= MAX_HOPS || turns.swaps(routeId)) {
        continue;
      }
      Integer follow = next.get(index);
      if (follow == null || shifted.containsKey(follow) || out.get(follow).pinned()) {
        continue;
      }
      int ready =
          out.get(index).slot().departureSeconds()
              + runByRoute.getOrDefault(routeId, 0)
              + turns.turnaround().getOrDefault(routeId, 0);
      int departure = out.get(follow).slot().departureSeconds();
      int shift =
          departure < ready ? ready - departure : Math.min(0, ready + turns.maxIdle() - departure);
      if (shift == 0 || !shiftKeepingOrder(out, follow, shift)) {
        continue;
      }
      shifted.put(follow, shift);
      carried++;
      pending.add(new int[] {follow, shift, item[2] + 1});
    }
    return carried;
  }

  /** 原网格上每一班的车到终点后接哪一班（下标）：同一终点、同一车池，按就绪（到站加折返）先后接最早能接的那一班，等过闲置上限的不接。 */
  private static Map<Integer, Integer> continuations(
      List<TimetableBuilder.Placed> grid, Map<UUID, Integer> runByRoute, Turns turns) {
    Map<String, List<Integer>> arrivals = new HashMap<>();
    Map<String, List<Integer>> departures = new HashMap<>();
    int[] ready = new int[grid.size()];
    for (int i = 0; i < grid.size(); i++) {
      UUID routeId = grid.get(i).slot().routeId();
      String pool = turns.pool().getOrDefault(routeId, "");
      String origin = turns.origin().get(routeId);
      if (origin != null) {
        departures.computeIfAbsent(origin + "|" + pool, key -> new ArrayList<>()).add(i);
      }
      String terminal = turns.terminal().get(routeId);
      if (terminal != null) {
        ready[i] =
            grid.get(i).slot().departureSeconds()
                + runByRoute.getOrDefault(routeId, 0)
                + turns.turnaround().getOrDefault(routeId, 0);
        arrivals.computeIfAbsent(terminal + "|" + pool, key -> new ArrayList<>()).add(i);
      }
    }
    Map<Integer, Integer> out = new HashMap<>();
    arrivals.forEach(
        (key, arriving) -> {
          arriving.sort(Comparator.comparingInt((Integer i) -> ready[i]).thenComparing(i -> i));
          List<Integer> leaving = new ArrayList<>(departures.getOrDefault(key, List.of()));
          leaving.sort(
              Comparator.comparingInt((Integer i) -> grid.get(i).slot().departureSeconds())
                  .thenComparing(i -> i));
          Deque<Integer> waiting = new ArrayDeque<>();
          int a = 0;
          for (int departure : leaving) {
            int at = grid.get(departure).slot().departureSeconds();
            while (a < arriving.size() && ready[arriving.get(a)] <= at) {
              waiting.add(arriving.get(a++));
            }
            while (!waiting.isEmpty() && at - ready[waiting.peek()] > turns.maxIdle()) {
              waiting.poll();
            }
            if (!waiting.isEmpty()) {
              out.put(waiting.poll(), departure);
            }
          }
        });
    return out;
  }

  /** 把第 {@code index} 班挪 {@code shift} 秒；会越过同方向相邻一班或挪到窗口之前时不挪。 */
  private static boolean shiftKeepingOrder(
      List<TimetableBuilder.Placed> out, int index, int shift) {
    TimetableBuilder.Placed item = out.get(index);
    int from = item.slot().departureSeconds();
    int to = from + shift;
    if (to < 0) {
      return false;
    }
    String key = item.direction().key();
    for (int i = 0; i < out.size(); i++) {
      TimetableBuilder.Placed other = out.get(i);
      if (i == index || !other.direction().key().equals(key)) {
        continue;
      }
      int at = other.slot().departureSeconds();
      if (shift > 0 ? at > from && at <= to : at < from && at >= to) {
        return false;
      }
    }
    GroupGrid.Slot slot = item.slot();
    out.set(
        index,
        new TimetableBuilder.Placed(
            new GroupGrid.Slot(to, slot.routeId(), slot.candidateIndex(), slot.slot()),
            item.direction(),
            false));
    return true;
  }

  /** 把相邻一班挪 {@code shift} 秒；会越过定点或越过它另一侧的那一班时不挪，定点班次不挪。挪了就记进 {@code shifted}。 */
  private static void smooth(
      List<TimetableBuilder.Placed> out,
      List<Integer> sameDirection,
      int position,
      int shift,
      int pinnedAt,
      boolean before,
      Map<Integer, Integer> shifted) {
    if (position < 0 || position >= sameDirection.size()) {
      return;
    }
    TimetableBuilder.Placed item = out.get(sameDirection.get(position));
    if (item.pinned()) {
      return;
    }
    int moved = item.slot().departureSeconds() + shift;
    int outer = before ? position - 1 : position + 1;
    boolean crossesPin = before ? moved >= pinnedAt : moved <= pinnedAt;
    boolean crossesOuter =
        outer >= 0
            && outer < sameDirection.size()
            && (before
                ? moved <= out.get(sameDirection.get(outer)).slot().departureSeconds()
                : moved >= out.get(sameDirection.get(outer)).slot().departureSeconds());
    if (moved < 0 || crossesPin || crossesOuter) {
      return;
    }
    GroupGrid.Slot slot = item.slot();
    out.set(
        sameDirection.get(position),
        new TimetableBuilder.Placed(
            new GroupGrid.Slot(moved, slot.routeId(), slot.candidateIndex(), slot.slot()),
            item.direction(),
            false));
    shifted.put(sameDirection.get(position), shift);
  }

  private static Optional<ServiceGroupClassifier.Direction> directionOf(
      List<ServiceGroupClassifier.Group> groups, UUID routeId) {
    for (ServiceGroupClassifier.Group group : groups) {
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        if (direction.routeIds().contains(routeId)) {
          return Optional.of(direction);
        }
      }
    }
    return Optional.empty();
  }

  private static String clock(int secondOfDay) {
    int s = Math.floorMod(secondOfDay, TimetableTrip.SECONDS_PER_DAY);
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
  }
}
