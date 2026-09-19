package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ScheduledDeparturePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 按表运行的运行时门面：把已发布的时刻表翻译成"这辆车此刻该几点开"，以及"这辆车还能不能再接一班"。
 *
 * <p>它同时是 {@link ScheduledDeparturePlan} 的唯一实现、发车侧取"到点该发哪趟车"的来源，
 * 以及车辆交路边界的执行者，三者共用同一份缓存与同一套时刻换算，避免出现"发车按 A 算、扣车按 B 算"这种最难查的不一致。
 *
 * <p>三条安全约束贯穿全类：
 *
 * <ul>
 *   <li><b>不确定就返回空</b>。没绑定、没发布、缓存为空、时刻算不出来——一律回到自由运行， 而不是猜一个时间。空的代价是"这趟车没按表跑"，猜错的代价是"这趟车被扣在站里"。
 *   <li><b>不落库</b>。查询路径只读内存快照；快照由 {@link #reload(StorageProvider)} 在独立 tick 上刷新。
 *   <li><b>不写状态到调度层</b>。绑定只活在本类的 map 里，调度层拿到的永远只是一个时间或一个布尔。
 * </ul>
 *
 * <h2>车辆交路边界如何被执行</h2>
 *
 * <p>{@link #allowsLayoverReuse} 是"每辆车最终都会回库"在运行期的执行点。它的语义刻意很窄： 只回答"这辆车的 duty
 * 还有没有余额"，不负责把车送回库。完整链路是
 *
 * <pre>
 *   duty 跑完最后一班 → 复用被否决 → 列车留在 layover 闲置
 *                    → ReclaimManager 在 maxIdleSeconds 内派出 RETURN 票 → 回库
 * </pre>
 *
 * 回收动作仍然由既有的 {@code ReclaimManager} / {@code StorageSpawnManager} 完成——本类不复制一套车辆所有权，
 * 只是把"不准再接班"这个事实告诉它们。因此终止性有两道保险：计划本身的 duty 是有限的， 运行期的否决又保证了有限性不会被"恰好还有下一班"绕过。
 *
 * <p>按表发车时，回库不再只靠闲置回收：每个 duty 在 {@link VehicleDuty#returnSecondOfDay()} 发出一张 RETURN 票 （{@link
 * #dueLegs}），而 {@link #allowsReturn} 保证这张票只带走交路已经跑完的车，不会把正等着跑下一班的车抓回车库。
 */
public final class TimetableService implements ScheduledDeparturePlan {

  /** 匹配车次时允许回看/前看的服务日，用于覆盖跨零点的班次。 */
  private static final List<Integer> SERVICE_DATE_OFFSETS = List.of(-1, 0, 1);

  /** 绑定表上限：远超任何一条线的实际运营规模，命中说明清理没生效。 */
  private static final int MAX_ASSIGNMENTS = 1024;

  private final Supplier<Instant> clock;
  private final Consumer<String> debugLogger;

  private volatile Settings settings = Settings.disabled();
  private volatile Snapshot snapshot = Snapshot.empty();

  private final ConcurrentMap<String, TimetableAssignment> assignments = new ConcurrentHashMap<>();
  private final ConcurrentMap<TripKey, String> claims = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, DutyProgress> dutyProgress = new ConcurrentHashMap<>();

  /** 列车 → 它属于哪个交路（哪份表、哪个 duty、哪一天）。出库票实体化时或首次绑定车次时建立。 */
  private final ConcurrentMap<String, DutyKey> dutyBindings = new ConcurrentHashMap<>();

  public TimetableService(Supplier<Instant> clock, Consumer<String> debugLogger) {
    this.clock = clock == null ? Instant::now : clock;
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /** 更新配置；关闭时会立刻清空绑定，避免留下"已经不生效但还显示着"的状态。 */
  public void applySettings(Settings next) {
    Settings resolved = next == null ? Settings.disabled() : next;
    this.settings = resolved;
    if (!resolved.enabled()) {
      clearAssignments("settings-disabled");
    }
  }

  /** 当前配置。 */
  public Settings settings() {
    return settings;
  }

  /**
   * 从存储重新加载已发布的时刻表。
   *
   * <p>加载失败时保留旧快照：一次数据库抖动不应该让全网列车同时脱表。
   *
   * @param provider 存储提供者
   * @return 加载到的时刻表数量；失败时返回 -1
   */
  public int reload(StorageProvider provider) {
    if (provider == null) {
      return -1;
    }
    try {
      List<Timetable> published = provider.timetables().listPublished();
      Snapshot next = Snapshot.of(published);
      this.snapshot = next;
      dropClaimsOutsideSnapshot(next);
      debugLogger.accept(
          "TIMETABLE_RELOAD timetables="
              + next.timetables().size()
              + " routes="
              + next.byRoute().size());
      return next.timetables().size();
    } catch (StorageException ex) {
      debugLogger.accept("TIMETABLE_RELOAD_FAILED error=" + ex.getMessage());
      return -1;
    }
  }

  /** 当前缓存里的已发布时刻表。 */
  public List<Timetable> publishedTimetables() {
    return snapshot.timetables();
  }

  /** 哪些 route 正处于按表运行。 */
  public Set<UUID> managedRoutes() {
    return snapshot.byRoute().keySet();
  }

  /** 该 route 是否按表运行。 */
  public boolean managed(UUID routeId) {
    return settings.enabled() && routeId != null && snapshot.byRoute().containsKey(routeId);
  }

  @Override
  public Optional<Instant> scheduledDepartureAt(StationStopEvent event) {
    Settings current = settings;
    if (!current.enabled() || event == null) {
      return Optional.empty();
    }
    String key = keyOf(event.trainName());
    if (key == null) {
      return Optional.empty();
    }
    UUID routeId = event.routeUuid().orElse(null);
    TimetableAssignment existing = assignments.get(key);
    // 解绑判定必须先于"本 route 有没有表"的判定：改派到一条没有时刻表的交路同样是换了任务，
    // 此时若把旧绑定留着，等这辆车绕回原交路时会拿上一圈的车次继续算时刻，而它已经是下一圈了。
    if (existing != null && !existing.routeId().equals(routeId)) {
      releaseAssignment(key, "route-changed");
      existing = null;
    } else if (existing != null
        && event.stopIndex() == 0
        && !stillWaitingAtOrigin(existing, event, current)) {
      // 回到起点就是新的一趟车。不在这里重新匹配的话，同一条 route 上连续接班的列车会一直用第一趟的时刻，
      // 交路进度也永远停在第一班——"每辆车最终都会回库"就失去了推进它的事件。
      // 但"还在起点等点"不算回到起点：门控每秒问一次，扣留期间反复解绑重绑只会刷日志、扫全表。
      releaseAssignment(key, "new-circuit");
      existing = null;
    }
    if (routeId == null) {
      return Optional.empty();
    }
    List<Timetable> timetables = snapshot.byRoute().get(routeId);
    if (timetables == null || timetables.isEmpty()) {
      return Optional.empty();
    }
    TimetableAssignment assignment =
        existing != null ? existing : assign(key, routeId, timetables, event, current).orElse(null);
    if (assignment == null) {
      return Optional.empty();
    }
    return resolveTimetable(assignment)
        .flatMap(
            timetable ->
                timetable
                    .tripByCode(assignment.tripCode())
                    .flatMap(
                        trip ->
                            timetable.scheduledDeparture(
                                trip, event.stopIndex(), assignment.serviceDate())));
  }

  /**
   * 这辆车是不是还在起点等它已经绑定的那趟车发车。
   *
   * <p>判据：绑定就是在起点建立的，且现在还没超过那趟车表定发车 + 容差。超过了就是下一圈回来了，该重新匹配。
   */
  private boolean stillWaitingAtOrigin(
      TimetableAssignment existing, StationStopEvent event, Settings current) {
    if (existing.assignedAtStopIndex() != 0) {
      return false;
    }
    Optional<Instant> scheduled =
        resolveTimetable(existing)
            .flatMap(
                timetable ->
                    timetable
                        .tripByCode(existing.tripCode())
                        .flatMap(
                            trip -> timetable.scheduledDeparture(trip, 0, existing.serviceDate())));
    return scheduled
        .map(at -> !event.at().isAfter(at.plus(current.assignTolerance())))
        .orElse(false);
  }

  /**
   * 这辆车还能不能被复用去跑下一班运营车次。
   *
   * <p>这是车辆交路边界在运行期的唯一执行点，也是"每辆车最终都会回库"这条不变量不被 "恰好还有下一班"绕过的保证。语义刻意只有一条：<b>duty 的班次余额用完了就不准再接</b>。
   *
   * <p>返回 {@code false} 不会把车送回库——那仍然由 {@code ReclaimManager} 完成。本方法只是让它 停止接班，从而落进 reclaim 的闲置回收窗口。
   *
   * @param trainName 列车名
   * @return 允许复用返回 true；未启用按表运行、或该车不受时刻表管辖时同样返回 true（完全透明）
   */
  public boolean allowsLayoverReuse(String trainName) {
    Settings current = settings;
    if (!current.enabled()) {
      return true;
    }
    String key = keyOf(trainName);
    if (key == null) {
      return true;
    }
    DutyProgress progress = dutyProgress.get(key);
    if (progress == null) {
      return true;
    }
    if (!progress.exhausted()) {
      return true;
    }
    debugLogger.accept(
        "TIMETABLE_DUTY_CLOSED train="
            + trainName
            + " duty="
            + progress.dutyCode()
            + " trips="
            + progress.assignedTrips()
            + "/"
            + progress.plannedTrips()
            + " action=deny-reuse-return-to-storage");
    return false;
  }

  /**
   * 这辆车现在能不能被回库票带走。
   *
   * <p>这是 {@link #allowsLayoverReuse} 的镜像：交路还有余额的车不准被 RETURN 票抓走，否则一张按表发出的回库票会把
   * 正等着跑下一班的车送回车库，后面的班次就开了天窗。没有交路进度的车（自由运行、或本来就不受时刻表管辖）照常可回。
   *
   * @param trainName 列车名
   * @return 允许回库返回 true；未启用按表运行、或该车不受时刻表管辖时同样返回 true
   */
  public boolean allowsReturn(String trainName) {
    Settings current = settings;
    if (!current.enabled()) {
      return true;
    }
    String key = keyOf(trainName);
    if (key == null) {
      return true;
    }
    DutyProgress progress = dutyProgress.get(key);
    if (progress == null || progress.exhausted()) {
      return true;
    }
    debugLogger.accept(
        "TIMETABLE_RETURN_DENIED train="
            + trainName
            + " duty="
            + progress.dutyCode()
            + " trips="
            + progress.assignedTrips()
            + "/"
            + progress.plannedTrips()
            + " action=keep-for-next-trip");
    return false;
  }

  /**
   * 这辆待命车能不能接这张票。
   *
   * <p>票据认识 duty 之后，"接班"才有了硬定义：一个 duty 的第 N+1 班只能由跑完第 N 班的那辆车来接。 否则一张票会抓走终点上任何一辆顺手的车，
   * 把别的交路的车拐跑，那条交路后面的班次就开了天窗——而时刻表侧看不出原因。
   *
   * <ul>
   *   <li>车已经绑在某个交路上：只接同一交路的票。
   *   <li>车没绑交路（自由运行）：只能接交路的首班——出库票实体化的车在派发回调里就已绑定，所以首班通常接的也是本交路的车；
   *       续班要等的是本交路那辆车，晚点就晚点跑；回库票同样只带本交路的车，自由运行的车交给 ReclaimManager 的闲置回收。
   * </ul>
   *
   * @param intent 票据的交路意图
   * @param trainName 候选列车
   * @return 允许返回 true；未启用按表运行时恒为 true
   */
  public boolean acceptsVehicle(TicketIntent intent, String trainName) {
    Settings current = settings;
    if (!current.enabled() || intent == null) {
      return true;
    }
    String key = keyOf(trainName);
    if (key == null) {
      return true;
    }
    DutyKey bound = dutyBindings.get(key);
    if (bound != null) {
      if (bound.equals(intent.key())) {
        return true;
      }
      debugLogger.accept(
          "TIMETABLE_CANDIDATE_REJECT train="
              + trainName
              + " boundDuty="
              + bound.describe()
              + " ticketDuty="
              + intent.key().describe()
              + " reason=other-duty");
      return false;
    }
    boolean firstTrip = intent.kind() == RouteOperationType.OPERATION && intent.tripIndex() == 0;
    if (firstTrip) {
      return true;
    }
    debugLogger.accept(
        "TIMETABLE_CANDIDATE_REJECT train="
            + trainName
            + " ticketDuty="
            + intent.key().describe()
            + " kind="
            + intent.kind().name()
            + " tripIndex="
            + intent.tripIndex()
            + " reason=unbound-only-first-trip");
    return false;
  }

  /**
   * 把一辆车绑到某个交路上。
   *
   * <p>两个入口：出库票实体化了一辆车（发车侧回调）；列车在门控上首次绑定到带 duty 的车次。 已经绑在别的交路上时不覆盖——那是一辆被错派的车，覆盖只会把错误藏起来。
   */
  public void bindDuty(String trainName, DutyKey duty, String reason) {
    if (!settings.enabled() || duty == null) {
      return;
    }
    String key = keyOf(trainName);
    if (key == null) {
      return;
    }
    DutyKey previous = dutyBindings.putIfAbsent(key, duty);
    if (previous == null) {
      debugLogger.accept(
          "TIMETABLE_DUTY_BOUND train="
              + trainName
              + " duty="
              + duty.describe()
              + " reason="
              + reason);
    } else if (!previous.equals(duty)) {
      debugLogger.accept(
          "TIMETABLE_DUTY_BIND_CONFLICT train="
              + trainName
              + " bound="
              + previous.describe()
              + " requested="
              + duty.describe()
              + " reason="
              + reason);
    }
  }

  /** 查询某辆车绑在哪个交路上。 */
  public Optional<DutyKey> dutyBindingOf(String trainName) {
    String key = keyOf(trainName);
    return key == null ? Optional.empty() : Optional.ofNullable(dutyBindings.get(key));
  }

  /** 查询某辆车当前的车次绑定。 */
  public Optional<TimetableAssignment> assignmentOf(String trainName) {
    String key = keyOf(trainName);
    return key == null ? Optional.empty() : Optional.ofNullable(assignments.get(key));
  }

  /** 查询某辆车的交路进度。 */
  public Optional<DutyProgress> dutyProgressOf(String trainName) {
    String key = keyOf(trainName);
    return key == null ? Optional.empty() : Optional.ofNullable(dutyProgress.get(key));
  }

  /** 全部绑定快照。 */
  public List<TimetableAssignment> assignments() {
    return List.copyOf(assignments.values());
  }

  /** 保留仍在网的列车绑定，其余释放。由运行时清理 tick 调用。 */
  public void retain(Collection<String> activeTrainNames) {
    if (activeTrainNames == null) {
      clearAssignments("retain-empty");
      return;
    }
    Set<String> keep = new HashSet<>();
    for (String name : activeTrainNames) {
      String key = keyOf(name);
      if (key != null) {
        keep.add(key);
      }
    }
    for (String key : List.copyOf(assignments.keySet())) {
      if (!keep.contains(key)) {
        releaseAssignment(key, "train-gone");
      }
    }
    dutyProgress.keySet().retainAll(keep);
    dutyBindings.keySet().retainAll(keep);
  }

  /** 列车离开运行时管辖时释放绑定与交路进度。 */
  public void release(String trainName, String reason) {
    String key = keyOf(trainName);
    if (key == null) {
      return;
    }
    releaseAssignment(key, reason);
    dutyBindings.remove(key);
    DutyProgress removed = dutyProgress.remove(key);
    if (removed != null) {
      debugLogger.accept(
          "TIMETABLE_DUTY_RELEASED train="
              + trainName
              + " duty="
              + removed.dutyCode()
              + " trips="
              + removed.assignedTrips()
              + "/"
              + removed.plannedTrips()
              + " reason="
              + reason);
    }
  }

  /**
   * 取出计划发车时间落在 {@code (from, to]} 内的车次，供发车侧出票。
   *
   * <p>窗口是半开的，且由调用方保存上次的 {@code from}，因此同一趟车不会被出两次票——这正是"按表运行"与 headway 出票最大的区别：headway
   * 是"每隔多久来一趟"，时刻表是"这一趟只发一次"。
   *
   * @param from 上次轮询时间（不含）
   * @param to 本次轮询时间（含）
   * @return 到点车次，按计划发车时间升序
   */
  public List<DueTrip> dueTrips(Instant from, Instant to) {
    Settings current = settings;
    if (!current.enabled() || !current.spawnEnabled() || from == null || to == null) {
      return List.of();
    }
    // 限制单次窗口长度：服务器停了一整天再启动，不应该把这一天的车全部补发出来。
    Instant windowStart =
        to.minus(current.maxCatchUp()).isAfter(from) ? to.minus(current.maxCatchUp()) : from;
    return tripsBetween(windowStart, to);
  }

  /**
   * 取出计划发车时间落在 {@code (from, to]} 内的车次，不做任何开关与窗口裁剪。
   *
   * <p>供 ETA 预测这类"只是想知道接下来会发什么车"的只读消费者使用；出票请走 {@link #dueTrips(Instant, Instant)}。
   *
   * @param from 窗口起点（不含）
   * @param to 窗口终点（含）
   * @return 车次，按计划发车时间升序
   */
  public List<DueTrip> tripsBetween(Instant from, Instant to) {
    if (from == null || to == null || !to.isAfter(from)) {
      return List.of();
    }
    List<DueTrip> out = new ArrayList<>();
    for (Timetable timetable : snapshot.timetables()) {
      for (TimetableTrip trip : timetable.trips()) {
        for (int offset : SERVICE_DATE_OFFSETS) {
          LocalDate date = LocalDate.ofInstant(to, timetable.zoneId()).plusDays(offset);
          Instant departure = trip.departureAt(date, timetable.zoneId());
          if (departure.isAfter(from) && !departure.isAfter(to)) {
            out.add(new DueTrip(timetable, trip, date, departure));
          }
        }
      }
    }
    out.sort(Comparator.comparing(DueTrip::departure).thenComparing(due -> due.trip().tripCode()));
    return List.copyOf(out);
  }

  /**
   * 取出计划发出时间落在 {@code (from, to]} 内的出库/回库走行票，供发车侧出票。
   *
   * <p>出库票在 duty 的 {@link VehicleDuty#plannedStartSecondOfDay()} 发出：它比首班发车早"车库到首站的走行 + 就绪时间"，
   * 否则首班必然晚点。回库票在 {@link VehicleDuty#returnSecondOfDay()} 发出。 两端 route 自带 CRET/DSTY 的 duty
   * 没有对应的走行票。
   *
   * @param from 上次轮询时间（不含）
   * @param to 本次轮询时间（含）
   * @return 到点走行票，按发出时间升序
   */
  public List<DueLeg> dueLegs(Instant from, Instant to) {
    Settings current = settings;
    if (!current.enabled() || !current.spawnEnabled() || from == null || to == null) {
      return List.of();
    }
    Instant windowStart =
        to.minus(current.maxCatchUp()).isAfter(from) ? to.minus(current.maxCatchUp()) : from;
    return legsBetween(windowStart, to);
  }

  /**
   * 取出计划发出时间落在 {@code (from, to]} 内的出库/回库走行票，不做任何开关与窗口裁剪。
   *
   * @param from 窗口起点（不含）
   * @param to 窗口终点（含）
   * @return 走行票，按发出时间升序
   */
  public List<DueLeg> legsBetween(Instant from, Instant to) {
    if (from == null || to == null || !to.isAfter(from)) {
      return List.of();
    }
    List<DueLeg> out = new ArrayList<>();
    for (Timetable timetable : snapshot.timetables()) {
      for (VehicleDuty duty : timetable.duties()) {
        for (int offset : SERVICE_DATE_OFFSETS) {
          LocalDate date = LocalDate.ofInstant(to, timetable.zoneId()).plusDays(offset);
          Instant dayStart = date.atStartOfDay(timetable.zoneId()).toInstant();
          duty.createRouteId()
              .ifPresent(
                  routeId -> {
                    Instant at = dayStart.plusSeconds(duty.plannedStartSecondOfDay());
                    if (at.isAfter(from) && !at.isAfter(to)) {
                      out.add(
                          new DueLeg(
                              timetable, duty, RouteOperationType.CREATE, routeId, date, at));
                    }
                  });
          duty.returnRouteId()
              .ifPresent(
                  routeId -> {
                    Instant at = dayStart.plusSeconds(duty.returnSecondOfDay());
                    if (at.isAfter(from) && !at.isAfter(to)) {
                      out.add(
                          new DueLeg(
                              timetable, duty, RouteOperationType.RETURN, routeId, date, at));
                    }
                  });
        }
      }
    }
    out.sort(
        Comparator.comparing(DueLeg::departure)
            .thenComparing(due -> due.duty().dutyCode())
            .thenComparing(due -> due.kind().name()));
    return List.copyOf(out);
  }

  /** 运行态汇总，供 {@code /fta timetable status} 使用。 */
  public StatusSnapshot status() {
    Instant now = clock.get();
    List<DeviationEntry> deviations = new ArrayList<>();
    for (TimetableAssignment assignment : assignments.values()) {
      Optional<Timetable> timetableOpt = resolveTimetable(assignment);
      Optional<TimetableTrip> tripOpt =
          timetableOpt.flatMap(timetable -> timetable.tripByCode(assignment.tripCode()));
      if (tripOpt.isEmpty()) {
        continue;
      }
      DutyProgress progress = dutyProgress.get(keyOf(assignment.trainName()));
      deviations.add(
          new DeviationEntry(
              assignment.trainName(),
              assignment.tripCode(),
              tripOpt.get().departureText(),
              assignment.initialDeviationSeconds(),
              Duration.between(assignment.assignedAt(), now).toSeconds(),
              progress == null ? "-" : progress.describe()));
    }
    deviations.sort(Comparator.comparing(DeviationEntry::trainName));
    return new StatusSnapshot(
        settings.enabled(),
        settings.spawnEnabled(),
        snapshot.timetables().size(),
        snapshot.byRoute().size(),
        List.copyOf(deviations));
  }

  private Optional<TimetableAssignment> assign(
      String key,
      UUID routeId,
      List<Timetable> timetables,
      StationStopEvent event,
      Settings current) {
    if (assignments.size() >= MAX_ASSIGNMENTS) {
      debugLogger.accept(
          "TIMETABLE_ASSIGN_SKIP reason=assignment-limit train=" + event.trainName());
      return Optional.empty();
    }
    Instant now = event.at();
    long tolerance = current.assignTolerance().toSeconds();
    Candidate best = null;
    for (Timetable timetable : timetables) {
      for (TimetableTrip trip : timetable.trips()) {
        if (!trip.routeId().equals(routeId)) {
          continue;
        }
        for (int offset : SERVICE_DATE_OFFSETS) {
          LocalDate date = LocalDate.ofInstant(now, timetable.zoneId()).plusDays(offset);
          Optional<Instant> scheduled = timetable.scheduledDeparture(trip, event.stopIndex(), date);
          if (scheduled.isEmpty()) {
            continue;
          }
          long deviation = Duration.between(scheduled.get(), now).toSeconds();
          if (Math.abs(deviation) > tolerance) {
            continue;
          }
          TripKey tripKey = new TripKey(timetable.id(), trip.id(), date);
          String holder = claims.get(tripKey);
          if (holder != null && !holder.equals(key)) {
            continue;
          }
          if (best == null || Math.abs(deviation) < Math.abs(best.deviationSeconds())) {
            best = new Candidate(timetable, trip, date, tripKey, deviation);
          }
        }
      }
    }
    if (best == null) {
      return Optional.empty();
    }
    String existingHolder = claims.putIfAbsent(best.tripKey(), key);
    if (existingHolder != null && !existingHolder.equals(key)) {
      return Optional.empty();
    }
    TimetableAssignment assignment =
        new TimetableAssignment(
            event.trainName(),
            best.timetable().id(),
            best.trip().id(),
            best.trip().tripCode(),
            routeId,
            best.trip().dutyId(),
            best.serviceDate(),
            now,
            event.stopIndex(),
            best.deviationSeconds());
    assignments.put(key, assignment);
    startOrAdvanceDuty(key, best.timetable(), best.trip());
    Candidate chosen = best;
    chosen
        .trip()
        .dutyId()
        .ifPresent(
            dutyId ->
                bindDuty(
                    event.trainName(),
                    new DutyKey(
                        chosen.timetable().id(),
                        dutyId,
                        chosen.timetable().serviceDayOf(chosen.trip(), chosen.serviceDate())),
                    "trip-assigned"));
    debugLogger.accept(
        "TIMETABLE_ASSIGN train="
            + event.trainName()
            + " trip="
            + best.trip().tripCode()
            + " duty="
            + best.trip().dutyId().map(UUID::toString).orElse("-")
            + " plannedDeparture="
            + best.trip().departureText()
            + " stopIndex="
            + event.stopIndex()
            + " deviationSeconds="
            + best.deviationSeconds());
    return Optional.of(assignment);
  }

  /**
   * 建立或推进这辆车的交路进度。
   *
   * <p>计的是"已经被指派了几班"，而不是"已经跑完几班"：对"还能不能再接一班"这个问题来说， 正在跑的那一班同样占用额度。用"跑完"计数则需要一个可靠的完成事件，而同一条 route
   * 连续接班时 并不会产生解绑，那个事件根本不存在——那正是上一版会漏计的地方。
   *
   * <p>换了 duty 就是换了一轮周转：旧进度作废，新 duty 从第一班重新计。
   */
  private void startOrAdvanceDuty(String key, Timetable timetable, TimetableTrip trip) {
    UUID dutyId = trip.dutyId().orElse(null);
    if (dutyId == null) {
      dutyProgress.remove(key);
      return;
    }
    Optional<VehicleDuty> dutyOpt = timetable.duty(dutyId);
    if (dutyOpt.isEmpty()) {
      dutyProgress.remove(key);
      return;
    }
    VehicleDuty duty = dutyOpt.get();
    DutyProgress previous = dutyProgress.get(key);
    if (previous == null || !previous.dutyId().equals(dutyId)) {
      dutyProgress.put(
          key, new DutyProgress(dutyId, duty.dutyCode(), duty.tripCount(), 1, trip.id()));
      return;
    }
    if (!previous.lastTripId().equals(trip.id())) {
      dutyProgress.put(key, previous.withTrip(trip.id()));
    }
  }

  private Optional<Timetable> resolveTimetable(TimetableAssignment assignment) {
    if (assignment == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(snapshot.byId().get(assignment.timetableId()));
  }

  private void releaseAssignment(String key, String reason) {
    TimetableAssignment removed = assignments.remove(key);
    if (removed == null) {
      return;
    }
    claims.remove(new TripKey(removed.timetableId(), removed.tripId(), removed.serviceDate()), key);
    debugLogger.accept(
        "TIMETABLE_RELEASE train="
            + removed.trainName()
            + " trip="
            + removed.tripCode()
            + " reason="
            + reason);
  }

  private void clearAssignments(String reason) {
    if (assignments.isEmpty() && dutyProgress.isEmpty() && dutyBindings.isEmpty()) {
      claims.clear();
      return;
    }
    int size = assignments.size();
    assignments.clear();
    claims.clear();
    dutyProgress.clear();
    dutyBindings.clear();
    debugLogger.accept("TIMETABLE_CLEAR assignments=" + size + " reason=" + reason);
  }

  /** 时刻表被删除或下架后，残留的占用必须一起清掉，否则那趟车会永远"已被占用"。 */
  private void dropClaimsOutsideSnapshot(Snapshot next) {
    for (Map.Entry<String, TimetableAssignment> entry : List.copyOf(assignments.entrySet())) {
      if (!next.byId().containsKey(entry.getValue().timetableId())) {
        releaseAssignment(entry.getKey(), "timetable-unpublished");
        dutyProgress.remove(entry.getKey());
      }
    }
    dutyBindings
        .entrySet()
        .removeIf(entry -> !next.byId().containsKey(entry.getValue().timetableId()));
  }

  private static String keyOf(String trainName) {
    if (trainName == null) {
      return null;
    }
    String trimmed = trainName.trim();
    return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
  }

  private record Candidate(
      Timetable timetable,
      TimetableTrip trip,
      LocalDate serviceDate,
      TripKey tripKey,
      long deviationSeconds) {}

  private record TripKey(UUID timetableId, UUID tripId, LocalDate serviceDate) {}

  /** 已发布时刻表的只读索引。 */
  private record Snapshot(
      List<Timetable> timetables, Map<UUID, Timetable> byId, Map<UUID, List<Timetable>> byRoute) {

    private static Snapshot empty() {
      return new Snapshot(List.of(), Map.of(), Map.of());
    }

    private static Snapshot of(List<Timetable> published) {
      List<Timetable> kept =
          published == null
              ? List.of()
              : published.stream().filter(Objects::nonNull).filter(Timetable::published).toList();
      Map<UUID, Timetable> byId = new HashMap<>();
      Map<UUID, List<Timetable>> byRoute = new LinkedHashMap<>();
      for (Timetable timetable : kept) {
        byId.put(timetable.id(), timetable);
        for (UUID routeId : timetable.routeIds()) {
          byRoute.computeIfAbsent(routeId, key -> new ArrayList<>()).add(timetable);
        }
      }
      Map<UUID, List<Timetable>> frozen = new LinkedHashMap<>();
      byRoute.forEach((route, list) -> frozen.put(route, List.copyOf(list)));
      return new Snapshot(List.copyOf(kept), Map.copyOf(byId), Map.copyOf(frozen));
    }
  }

  /**
   * 一辆车在当前交路上的进度。
   *
   * @param dutyId 交路 UUID
   * @param dutyCode 交路编号
   * @param plannedTrips 该交路计划承担的班次数（有限，构建时就夹住了）
   * @param assignedTrips 已经被指派的班次数，含正在跑的那一班
   * @param lastTripId 最近一次指派的班次，用于识别"换了一班"而不是同一班的重复查询
   */
  public record DutyProgress(
      UUID dutyId, String dutyCode, int plannedTrips, int assignedTrips, UUID lastTripId) {

    public DutyProgress {
      Objects.requireNonNull(dutyId, "dutyId");
      Objects.requireNonNull(lastTripId, "lastTripId");
      dutyCode = dutyCode == null ? "" : dutyCode;
      plannedTrips = Math.max(0, plannedTrips);
      assignedTrips = Math.max(0, assignedTrips);
    }

    DutyProgress withTrip(UUID tripId) {
      return new DutyProgress(dutyId, dutyCode, plannedTrips, assignedTrips + 1, tripId);
    }

    /** 交路是否已经用完额度。 */
    public boolean exhausted() {
      return assignedTrips >= plannedTrips;
    }

    /** 供诊断输出的简述。 */
    public String describe() {
      return dutyCode + " " + assignedTrips + "/" + plannedTrips;
    }
  }

  /**
   * 到点待发的车次。
   *
   * @param timetable 所属时刻表
   * @param trip 车次
   * @param serviceDate 服务日期
   * @param departure 绝对计划发车时间
   */
  public record DueTrip(
      Timetable timetable, TimetableTrip trip, LocalDate serviceDate, Instant departure) {}

  /**
   * 到点待发的出库/回库走行票。
   *
   * @param timetable 所属时刻表
   * @param duty 所属车辆交路
   * @param kind {@code CREATE} 或 {@code RETURN}
   * @param routeId 走行线路
   * @param serviceDate 服务日期
   * @param departure 绝对发出时间
   */
  public record DueLeg(
      Timetable timetable,
      VehicleDuty duty,
      RouteOperationType kind,
      UUID routeId,
      LocalDate serviceDate,
      Instant departure) {

    /** 供票据 serviceTripId 与日志使用的稳定标识。 */
    public String code() {
      return duty.dutyCode() + "-" + kind.name();
    }
  }

  /**
   * 一个交路在某一天的身份。
   *
   * @param timetableId 所属时刻表
   * @param dutyId 交路
   * @param serviceDate 服务日期
   */
  public record DutyKey(UUID timetableId, UUID dutyId, LocalDate serviceDate) {
    public DutyKey {
      Objects.requireNonNull(timetableId, "timetableId");
      Objects.requireNonNull(dutyId, "dutyId");
      Objects.requireNonNull(serviceDate, "serviceDate");
    }

    /** 日志用简述。 */
    public String describe() {
      return dutyId + "@" + serviceDate;
    }
  }

  /**
   * 一张表定票据的交路意图：它属于哪个交路、是哪一种票、是第几班。
   *
   * @param timetableId 所属时刻表
   * @param dutyId 交路
   * @param serviceDate 服务日期
   * @param kind CREATE / OPERATION / RETURN
   * @param tripIndex 运营票在交路里的序号（0 为首班）；走行票为 0
   */
  public record TicketIntent(
      UUID timetableId,
      UUID dutyId,
      LocalDate serviceDate,
      RouteOperationType kind,
      int tripIndex) {
    public TicketIntent {
      Objects.requireNonNull(timetableId, "timetableId");
      Objects.requireNonNull(dutyId, "dutyId");
      Objects.requireNonNull(serviceDate, "serviceDate");
      kind = kind == null ? RouteOperationType.OPERATION : kind;
      tripIndex = Math.max(0, tripIndex);
    }

    public DutyKey key() {
      return new DutyKey(timetableId, dutyId, serviceDate);
    }
  }

  /**
   * 单辆车的表定偏差。
   *
   * @param trainName 列车名
   * @param tripCode 车次号
   * @param plannedDeparture 起点计划发车时刻
   * @param initialDeviationSeconds 绑定时偏差（正数为晚点）
   * @param assignedAgoSeconds 绑定至今的秒数
   * @param duty 交路进度简述
   */
  public record DeviationEntry(
      String trainName,
      String tripCode,
      String plannedDeparture,
      long initialDeviationSeconds,
      long assignedAgoSeconds,
      String duty) {}

  /**
   * 运行态汇总。
   *
   * @param enabled 按表运行总开关
   * @param spawnEnabled 是否由时刻表驱动发车
   * @param publishedTimetables 已发布时刻表数
   * @param managedRoutes 受管辖的 route 数
   * @param deviations 当前绑定的列车偏差
   */
  public record StatusSnapshot(
      boolean enabled,
      boolean spawnEnabled,
      int publishedTimetables,
      int managedRoutes,
      List<DeviationEntry> deviations) {}

  /**
   * 按表运行的运行时配置。
   *
   * @param enabled 总开关；关闭时本类对调度层完全透明
   * @param spawnEnabled 是否由时刻表驱动发车出票
   * @param maxHold 早到列车最多被扣留多久
   * @param assignTolerance 匹配车次时允许的最大偏差
   * @param maxCatchUp 发车侧单次轮询最多回补多长时间窗口
   * @param zoneId 命令未指定时构建时刻表使用的默认时区
   */
  public record Settings(
      boolean enabled,
      boolean spawnEnabled,
      Duration maxHold,
      Duration assignTolerance,
      Duration maxCatchUp,
      ZoneId zoneId) {

    public Settings {
      maxHold = maxHold == null || maxHold.isNegative() ? Duration.ZERO : maxHold;
      assignTolerance =
          assignTolerance == null || assignTolerance.isNegative() ? Duration.ZERO : assignTolerance;
      maxCatchUp = maxCatchUp == null || maxCatchUp.isNegative() ? Duration.ZERO : maxCatchUp;
      zoneId = zoneId == null ? ZoneId.systemDefault() : zoneId;
    }

    /** 关闭状态：按表运行完全不参与。 */
    public static Settings disabled() {
      return new Settings(
          false, false, Duration.ZERO, Duration.ZERO, Duration.ZERO, ZoneId.systemDefault());
    }
  }
}
