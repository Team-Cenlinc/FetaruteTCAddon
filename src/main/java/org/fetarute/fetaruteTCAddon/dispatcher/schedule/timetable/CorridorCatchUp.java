package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 共线追车：两条交路沿同一串互斥资源走一段（区间、道岔、具体站台股道逐个相同，中途没有待避）时，后车追上前车就只能跟着它一站一站地走。
 *
 * <p>冲突模型按资源逐个判先后，看不出"不能超车"：快车在慢车停站时从同一股道"穿过去"只记几处小冲突，之后两车次序就反了， 这些小冲突又都判成可吸收。这里按整段算：后车相对前车至少要晚发
 * {@code max(前车离开第 k 个资源 − 后车进入第 k 个资源)}，晚不到这么多的差额就是后车被拖住的秒数。
 *
 * <p>共线段从两条交路的第一个共用资源起对齐，所以同一站不同股道出发、在途中汇入都算；两条交路也可以分几段共线（中间有待避或分开再汇合），各段单独算。
 */
final class CorridorCatchUp {

  /** 至少要有这么多个互斥资源逐个相同才算一段共线：只共用一两条边的，追不上什么。 */
  static final int MIN_SHARED_RESOURCES = 4;

  private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles;
  private final TimetableConflictChecker.Footprints footprints;
  private final int separation;
  private final Map<UUID, List<Passage>> passages = new HashMap<>();
  private final Map<RoutePair, List<Run>> runs = new HashMap<>();

  /**
   * @param profiles 各交路的投影
   * @param index 图索引
   * @param separation 资源相邻占用的裕量（秒）：快出这么多才算"更快"
   */
  CorridorCatchUp(
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int separation) {
    this.profiles = profiles == null ? Map.of() : profiles;
    this.footprints = new TimetableConflictChecker.Footprints(index);
    this.separation = Math.max(0, separation);
  }

  /** 一个互斥资源上的一次占用（相对发车）。 */
  record Passage(String key, int entry, int exit, TimetableConflictChecker.Kind kind) {}

  /**
   * 一段共线：前车在前、后车在后时的最小发车间隔。
   *
   * @param behindFrom 共线段在后车资源序列里的起点下标：同一班后车被几条交路拖住时，起点相同的算同一段
   * @param aheadEntry 前车进入共线段的时刻（相对发车）
   * @param behindEntry 后车进入共线段的时刻（相对发车）
   * @param lead 后车至少晚发多少秒才追不上（不含裕量）
   * @param aheadSeconds 前车走完共线段的时长
   * @param behindSeconds 后车走完共线段的时长
   */
  record Run(
      int behindFrom,
      int aheadEntry,
      int behindEntry,
      int lead,
      int aheadSeconds,
      int behindSeconds) {}

  private record RoutePair(UUID ahead, UUID behind) {}

  /** {@code behind} 跟在 {@code ahead} 后面时的各段共线。 */
  List<Run> runs(UUID ahead, UUID behind) {
    return runs.computeIfAbsent(
        new RoutePair(ahead, behind), pair -> runsOf(passages(ahead), passages(behind)));
  }

  /** 在某段共线上比另一条交路明显更快（快出一个裕量以上）、因而可能被它拖住的交路。 */
  Set<UUID> fasterRoutes(Collection<UUID> routes) {
    Set<UUID> out = new HashSet<>();
    for (UUID ahead : routes) {
      for (UUID behind : routes) {
        if (!ahead.equals(behind) && runs(ahead, behind).stream().anyMatch(this::faster)) {
          out.add(behind);
        }
      }
    }
    return Set.copyOf(out);
  }

  /**
   * 一班快车在一段共线上被拖住。
   *
   * @param code 快车这一班的 code
   * @param routeId 快车交路
   * @param startSeconds 快车发车（与输入同一时间轴）
   * @param corridorEntry 快车进入这段共线的时刻（相对它自己的发车）
   * @param seconds 被拖住、超过一个裕量的秒数
   * @param blockingRouteId 拖住它的慢车交路（同一段被几班拖住时取最狠的那班）
   */
  record Caught(
      String code,
      UUID routeId,
      int startSeconds,
      int corridorEntry,
      int seconds,
      UUID blockingRouteId) {}

  /**
   * 快车被拖住的各段：每班、每段共线，被先进入这段的慢车拖住、超过一个裕量的部分，同一段取最狠的那班。
   *
   * <p>只看两车在这段上快慢有别（后车快出一个裕量以上）的；同时进入这段的按快车在后算。同一 code 的是同一辆车（班次与它紧接的回库走行），互相不算。
   * 前车只可能在后车之前一个全程时分以内发车，按发车排序后只看这个窗口，几千班的整张表也不用两两比较。
   */
  List<Caught> caught(List<TimetableConflictChecker.Movement> movements) {
    List<TimetableConflictChecker.Movement> sorted = new ArrayList<>(movements);
    sorted.sort(Comparator.comparingInt(TimetableConflictChecker.Movement::startSeconds));
    int window = 0;
    for (TimetableConflictChecker.Movement movement : sorted) {
      for (Passage passage : passages(movement.routeId())) {
        window = Math.max(window, passage.exit());
      }
    }
    List<Caught> out = new ArrayList<>();
    int from = 0;
    for (int b = 0; b < sorted.size(); b++) {
      TimetableConflictChecker.Movement behind = sorted.get(b);
      while (sorted.get(from).startSeconds() < behind.startSeconds() - window) {
        from++;
      }
      Map<Integer, Caught> worst = new TreeMap<>();
      for (int a = from;
          a < sorted.size() && sorted.get(a).startSeconds() <= behind.startSeconds() + window;
          a++) {
        TimetableConflictChecker.Movement ahead = sorted.get(a);
        if (ahead.code().equals(behind.code())) {
          continue;
        }
        for (Run run : runs(ahead.routeId(), behind.routeId())) {
          // 同一时刻进入算前车在前：让车修复会把其中一辆推到另一辆后面，谁先谁后说不准，按快车被拖住算。
          if (!faster(run)
              || ahead.startSeconds() + run.aheadEntry()
                  > behind.startSeconds() + run.behindEntry()) {
            continue;
          }
          int seconds = ahead.startSeconds() + run.lead() - behind.startSeconds();
          Caught known = worst.get(run.behindFrom());
          if (seconds > 0 && (known == null || seconds > known.seconds())) {
            worst.put(
                run.behindFrom(),
                new Caught(
                    behind.code(),
                    behind.routeId(),
                    behind.startSeconds(),
                    run.behindEntry(),
                    seconds,
                    ahead.routeId()));
          }
        }
      }
      out.addAll(worst.values());
    }
    return List.copyOf(out);
  }

  private boolean faster(Run run) {
    return run.behindSeconds() < run.aheadSeconds() - separation;
  }

  private List<Passage> passages(UUID routeId) {
    return passages.computeIfAbsent(
        routeId,
        id ->
            Optional.ofNullable(profiles.get(id))
                .map(profile -> passagesOf(footprints.of(id, profile)))
                .orElse(List.of()));
  }

  /** 一条交路沿途占用的互斥资源，按进入先后排列：区间、道岔、具体站台股道（站台组那一层有容量，不算）。 */
  static List<Passage> passagesOf(TimetableConflictChecker.Footprint footprint) {
    List<Passage> out = new ArrayList<>();
    for (TimetableConflictChecker.Slot slot : footprint.slots()) {
      boolean exclusive =
          switch (slot.kind()) {
            case TRACK, JUNCTION -> true;
            case PLATFORM -> slot.capacity() <= 1 && slot.key().startsWith("platform:");
            case SINGLE_LINE -> false;
          };
      if (exclusive) {
        out.add(new Passage(slot.key(), slot.from(), slot.to(), slot.kind()));
      }
    }
    // 停站的站台占用在足迹里排在最后；按进入时刻重排。同一时刻先节点（道岔、站台）后区间：节点就是前一条区间的终点。
    out.sort(
        Comparator.comparingInt(Passage::entry)
            .thenComparingInt(p -> p.kind() == TimetableConflictChecker.Kind.TRACK ? 1 : 0)
            .thenComparingInt(Passage::exit));
    return List.copyOf(out);
  }

  /**
   * 两串资源的各段共线：从前车的资源序列里依次找后车也经过（且在上一段之后）的资源，从那里起逐个相同的一串就是一段。
   *
   * <p>进入/离开时刻都相对各自发车，所以最小间隔 {@code max(前车离开 − 后车进入)} 不管共线段从哪里开始都成立。
   */
  static List<Run> runsOf(List<Passage> ahead, List<Passage> behind) {
    Map<String, Integer> behindAt = new HashMap<>();
    for (int j = behind.size() - 1; j >= 0; j--) {
      behindAt.put(behind.get(j).key(), j);
    }
    List<Run> out = new ArrayList<>();
    int i = 0;
    int floor = 0;
    while (i < ahead.size()) {
      Integer j = behindAt.get(ahead.get(i).key());
      if (j == null || j < floor) {
        i++;
        continue;
      }
      int n = 0;
      while (i + n < ahead.size()
          && j + n < behind.size()
          && ahead.get(i + n).key().equals(behind.get(j + n).key())) {
        n++;
      }
      if (n >= MIN_SHARED_RESOURCES) {
        int lead = Integer.MIN_VALUE;
        for (int k = 0; k < n; k++) {
          lead = Math.max(lead, ahead.get(i + k).exit() - behind.get(j + k).entry());
        }
        out.add(
            new Run(
                j,
                ahead.get(i).entry(),
                behind.get(j).entry(),
                lead,
                ahead.get(i + n - 1).exit() - ahead.get(i).entry(),
                behind.get(j + n - 1).exit() - behind.get(j).entry()));
      }
      i += Math.max(1, n);
      floor = j + Math.max(1, n);
    }
    return List.copyOf(out);
  }
}
