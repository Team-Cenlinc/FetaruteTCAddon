package org.fetarute.fetaruteTCAddon.display.pids;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus.Condition;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus.Detail;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusSource;

/**
 * 线路运行状况：按线路汇总在途列车、晚点、近期取消、封锁区间与时刻表运营时段。
 *
 * <h2>数据来源</h2>
 *
 * <ul>
 *   <li>在途列车：{@link TrainApi#listAllActiveTrains(boolean)}（不算 ETA），按列车当前对乘客显示的线路归类（直通换线后算新线路），
 *       回库越过运营终点的不算；出库交路上的车另记一份（运营时段外只是在往首站开，不算在运营）
 *   <li>晚点：{@link TimetableApi#listAssignments} 的预计偏差（没有时取上一站偏差），只看在途列车；未按表运行时没有晚点
 *   <li>取消：{@link TimetableApi#cancellations} 里计划始发在近 {@link #CANCELLED_WINDOW} 内的车次，按交路本身所属线路归类；
 *       交路上后面几趟一起取消时，各趟到了计划时刻才算进去
 *   <li>封锁区间与线路检修：名称目录（{@link PidsDirectory#operatorLines}）
 *   <li>运营时段：按表运行启用时，各线路已发布时刻表的运营开始与结束
 * </ul>
 *
 * <h2>判定</h2>
 *
 * <p>按 {@link Condition} 的顺序取第一种成立的：线路检修为全线停运；交路经过被封锁的区间为部分停运； 最大晚点 {@value #SEVERE_DELAY_SECONDS}
 * 秒以上、或晚点 {@value #MINOR_DELAY_SECONDS} 秒以上的车不止一辆且超过在途列车的一半为严重延误（没绑车次的车算准点）； 最大晚点 {@value
 * #MINOR_DELAY_SECONDS} 秒以上为轻微延误；近一小时有取消为部分班次取消；有在运营的列车为运行正常；
 * 没有时，有时刻表且此刻不在任何一张的运营时段内为已结束运营，否则为暂无列车。
 *
 * <p>列车与时刻表每 {@link #REFRESH} 汇总一次（各屏共用，主线程调用）；取消同样缓存，车次取消或重新绑定时由 {@link
 * #invalidateCancellations()} 立即作废。汇总失败时沿用上一份并留下调试日志。
 */
public final class PidsLineStatusProvider implements PidsLineStatusSource {

  /** 列车、时刻表与取消多久汇总一次。 */
  static final Duration REFRESH = Duration.ofSeconds(60);

  /** “近期取消”看多久以内计划始发的车次。 */
  static final Duration CANCELLED_WINDOW = Duration.ofHours(1);

  /** 晚点达到这么多秒算延误。 */
  static final long MINOR_DELAY_SECONDS = 5 * 60;

  /** 晚点达到这么多秒算严重延误。 */
  static final long SEVERE_DELAY_SECONDS = 10 * 60;

  private static final int DAY = 24 * 60 * 60;

  private final TrainApi trains;
  private final TimetableApi timetables;
  private final PidsDirectory directory;
  private final Consumer<String> debugLogger;
  private volatile Facts facts = Facts.EMPTY;
  private volatile Cancelled cancelled = Cancelled.EMPTY;

  /**
   * @param trains 列车接口
   * @param timetables 时刻表接口
   * @param directory 名称目录（交路所属线路与阶段）
   * @param debugLogger 调试日志
   */
  public PidsLineStatusProvider(
      TrainApi trains,
      TimetableApi timetables,
      PidsDirectory directory,
      Consumer<String> debugLogger) {
    this.trains = Objects.requireNonNull(trains, "trains");
    this.timetables = Objects.requireNonNull(timetables, "timetables");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  @Override
  public PidsLineStatus statusOf(PidsDirectory.OperatorLine line, Instant now) {
    Facts current = facts(now);
    String key = PidsDirectory.lineKey(line.operatorCode(), line.chip().code());
    return classify(
        line,
        new LineFacts(
            current.running().getOrDefault(key, 0),
            current.positioning().getOrDefault(key, 0),
            current.delays().getOrDefault(key, List.of()),
            cancelled(now).byLine().getOrDefault(key, 0),
            current.windows().getOrDefault(line.id(), List.of())),
        now);
  }

  /** 丢弃缓存的取消：车次取消或重新绑定时调用，下一次取状况就能看到。 */
  public void invalidateCancellations() {
    cancelled = Cancelled.EMPTY;
  }

  /**
   * 一条线路此刻的汇总。
   *
   * @param running 在途列车数（含出库交路上的车）
   * @param positioning 其中出库交路上的车
   * @param delays 在途且绑了车次的各列车晚点（秒，提前记 0）
   * @param cancelled 近期取消的班次数
   * @param windows 已发布时刻表的运营时段；未按表运行时为空
   */
  record LineFacts(
      int running, int positioning, List<Long> delays, int cancelled, List<ServiceWindow> windows) {

    LineFacts {
      delays = List.copyOf(delays);
      windows = List.copyOf(windows);
    }
  }

  /**
   * 判定一条线路的状况（见类注释）。
   *
   * @param line 线路
   * @param facts 这条线路的汇总
   * @param now 当前时刻
   */
  static PidsLineStatus classify(PidsDirectory.OperatorLine line, LineFacts facts, Instant now) {
    if (line.status() == LineApi.LineStatus.MAINTENANCE) {
      return PidsLineStatus.of(Condition.SUSPENDED);
    }
    if (line.suspended().isPresent()) {
      return new PidsLineStatus(
          Condition.PART_SUSPENDED, new Detail.Closed(line.suspended().get()));
    }
    List<Long> delays = facts.delays();
    long worst = delays.stream().mapToLong(Long::longValue).max().orElse(0);
    long late = delays.stream().filter(delay -> delay >= MINOR_DELAY_SECONDS).count();
    long trainCount = Math.max(facts.running(), delays.size());
    if (worst >= SEVERE_DELAY_SECONDS || (late >= 2 && late * 2 > trainCount)) {
      return new PidsLineStatus(Condition.SEVERE_DELAYS, new Detail.Late(worst / 60));
    }
    if (worst >= MINOR_DELAY_SECONDS) {
      return new PidsLineStatus(Condition.MINOR_DELAYS, new Detail.Late(worst / 60));
    }
    if (facts.cancelled() > 0) {
      return new PidsLineStatus(Condition.CANCELLATIONS, new Detail.Cancelled(facts.cancelled()));
    }
    List<ServiceWindow> windows = facts.windows();
    boolean closed =
        !windows.isEmpty() && windows.stream().noneMatch(window -> window.contains(now));
    int serving = closed ? facts.running() - facts.positioning() : facts.running();
    if (serving > 0) {
      return PidsLineStatus.of(Condition.GOOD);
    }
    if (closed) {
      Instant next =
          windows.stream()
              .map(window -> window.nextStart(now))
              .min(Comparator.naturalOrder())
              .orElseThrow();
      return new PidsLineStatus(Condition.ENDED, new Detail.FirstTrain(next));
    }
    return PidsLineStatus.of(Condition.NO_TRAINS);
  }

  private Facts facts(Instant now) {
    Facts current = facts;
    if (fresh(current.takenAt(), now)) {
      return current;
    }
    try {
      current = load(now);
    } catch (RuntimeException ex) {
      debugLogger.accept("PIDS_LINE_STATUS_FAILED error=" + ex);
      current = current.retakenAt(now);
    }
    facts = current;
    return current;
  }

  private Cancelled cancelled(Instant now) {
    Cancelled current = cancelled;
    if (fresh(current.takenAt(), now)) {
      return current;
    }
    try {
      Map<String, Integer> byLine = new HashMap<>();
      for (TimetableApi.CancelledTrip trip :
          timetables.cancellations(now.minus(CANCELLED_WINDOW), now)) {
        directory
            .lineOfRoute(trip.routeId())
            .ifPresent(
                ref ->
                    byLine.merge(
                        PidsDirectory.lineKey(ref.operatorCode(), ref.lineCode()),
                        1,
                        Integer::sum));
      }
      current = new Cancelled(now, byLine);
    } catch (RuntimeException ex) {
      debugLogger.accept("PIDS_LINE_STATUS_CANCELLATIONS_FAILED error=" + ex);
      current = new Cancelled(now, current.byLine());
    }
    cancelled = current;
    return current;
  }

  private static boolean fresh(Instant takenAt, Instant now) {
    return now.isBefore(takenAt.plus(REFRESH)) && !now.isBefore(takenAt);
  }

  private Facts load(Instant now) {
    Map<String, String> lineOfTrain = new HashMap<>();
    Map<String, Integer> running = new HashMap<>();
    Map<String, Integer> positioning = new HashMap<>();
    for (TrainApi.TrainSnapshot train : trains.listAllActiveTrains(false)) {
      if (train.outOfService()) {
        continue;
      }
      Optional<String> line = lineOf(train);
      if (line.isEmpty()) {
        continue;
      }
      lineOfTrain.put(train.trainName().toLowerCase(Locale.ROOT), line.get());
      running.merge(line.get(), 1, Integer::sum);
      if (directory.routeStage(train.routeId()).orElse(RouteApi.RouteStage.UNKNOWN)
          == RouteApi.RouteStage.CREATE) {
        positioning.merge(line.get(), 1, Integer::sum);
      }
    }
    Map<String, List<Long>> delays = new HashMap<>();
    for (TimetableApi.TrainAssignment assignment : timetables.listAssignments()) {
      String line = lineOfTrain.get(assignment.trainName().toLowerCase(Locale.ROOT));
      OptionalLong delay =
          assignment.projectedDelaySeconds().isPresent()
              ? assignment.projectedDelaySeconds()
              : assignment.currentDelaySeconds();
      if (line != null && delay.isPresent()) {
        delays
            .computeIfAbsent(line, ignored -> new ArrayList<>())
            .add(Math.max(0, delay.getAsLong()));
      }
    }
    Map<UUID, List<ServiceWindow>> windows = new HashMap<>();
    if (timetables.enabled()) {
      for (TimetableApi.TimetableInfo timetable : timetables.listPublished()) {
        windows
            .computeIfAbsent(timetable.lineId(), ignored -> new ArrayList<>())
            .add(ServiceWindow.of(timetable));
      }
    }
    return new Facts(now, running, positioning, delays, windows);
  }

  /** 列车当前对乘客显示的线路；没有线路标签时取交路代码的前两段。 */
  private static Optional<String> lineOf(TrainApi.TrainSnapshot train) {
    if (train.operatorCode().isPresent() && train.lineCode().isPresent()) {
      return Optional.of(PidsDirectory.lineKey(train.operatorCode().get(), train.lineCode().get()));
    }
    String[] parts = train.routeId() == null ? new String[0] : train.routeId().split(":");
    return parts.length >= 2
        ? Optional.of(PidsDirectory.lineKey(parts[0], parts[1]))
        : Optional.empty();
  }

  /**
   * 一张时刻表的运营时段。
   *
   * @param zone 时刻表时区
   * @param start 运营开始（当日秒数）
   * @param end 运营结束（当日秒数，可超过一天表示跨日）
   */
  record ServiceWindow(ZoneId zone, int start, int end) {

    ServiceWindow {
      Objects.requireNonNull(zone, "zone");
    }

    static ServiceWindow of(TimetableApi.TimetableInfo timetable) {
      ZoneId zone = ZoneId.systemDefault();
      if (timetable.zoneId() != null) {
        try {
          zone = ZoneId.of(timetable.zoneId());
        } catch (DateTimeException ex) {
          // 时区写错时按服务器时区，与时钟一致
        }
      }
      return new ServiceWindow(
          zone, timetable.serviceStartSecondOfDay(), timetable.serviceEndSecondOfDay());
    }

    /** 此刻在运营时段内；时段为空或写反时不据此判已结束，一律算在内。 */
    boolean contains(Instant now) {
      if (end <= start) {
        return true;
      }
      int second = now.atZone(zone).toLocalTime().toSecondOfDay();
      return (second >= start && second < end) || (end > DAY && second < end - DAY);
    }

    /** 下一次运营开始的时刻。 */
    Instant nextStart(Instant now) {
      LocalDate date = now.atZone(zone).toLocalDate();
      LocalTime time = LocalTime.ofSecondOfDay(Math.floorMod(start, DAY));
      Instant today = date.atTime(time).atZone(zone).toInstant();
      return today.isAfter(now) ? today : date.plusDays(1).atTime(time).atZone(zone).toInstant();
    }
  }

  /**
   * 一次列车与时刻表汇总。
   *
   * @param takenAt 汇总时刻
   * @param running 各线路在途列车数
   * @param positioning 各线路出库交路上的在途列车数
   * @param delays 各线路在途列车的晚点（秒）
   * @param windows 各线路（按 UUID）的运营时段
   */
  private record Facts(
      Instant takenAt,
      Map<String, Integer> running,
      Map<String, Integer> positioning,
      Map<String, List<Long>> delays,
      Map<UUID, List<ServiceWindow>> windows) {

    static final Facts EMPTY = new Facts(Instant.EPOCH, Map.of(), Map.of(), Map.of(), Map.of());

    Facts {
      running = Map.copyOf(running);
      positioning = Map.copyOf(positioning);
      delays = copyLists(delays);
      windows = copyLists(windows);
    }

    Facts retakenAt(Instant now) {
      return new Facts(now, running, positioning, delays, windows);
    }

    private static <K, V> Map<K, List<V>> copyLists(Map<K, List<V>> map) {
      Map<K, List<V>> out = new HashMap<>();
      map.forEach((key, values) -> out.put(key, List.copyOf(values)));
      return Map.copyOf(out);
    }
  }

  /**
   * 一次取消汇总。
   *
   * @param takenAt 汇总时刻
   * @param byLine 各线路近期取消的班次数
   */
  private record Cancelled(Instant takenAt, Map<String, Integer> byLine) {

    static final Cancelled EMPTY = new Cancelled(Instant.EPOCH, Map.of());

    Cancelled {
      byLine = Map.copyOf(byLine);
    }
  }
}
