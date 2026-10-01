package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ScheduledDeparturePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopObserver;
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
 * <p>按表发车时，回库不只靠闲置回收：每个 duty 在 {@link VehicleDuty#returnSecondOfDay()} 发出一张 RETURN 票 （{@link
 * #dueLegs}），而 {@link #allowsReturn} 保证这张票只带走交路已经跑完的车，不会把正等着跑下一班的车抓回车库。
 */
public final class TimetableService implements ScheduledDeparturePlan {

  /** 匹配车次时允许回看/前看的服务日，用于覆盖跨零点的班次。 */
  private static final List<Integer> SERVICE_DATE_OFFSETS = List.of(-1, 0, 1);

  private final Supplier<Instant> clock;
  private final Consumer<String> debugLogger;

  private volatile Settings settings = Settings.disabled();
  private volatile Snapshot snapshot = Snapshot.empty();

  /** 车次绑定与 trip 占用。 */
  private final TripMatcher matcher;

  /** 交路进度、交路归属与三道闸。 */
  private final DutyLedger ledger;

  /** 车次取消登记：站牌与公开事件读这里。 */
  private final TripCancellations cancellations = new TripCancellations();

  /** 各车在当前车次上已停完的最后一站；车半途离开时据此判断哪些站不再停。 */
  private final ConcurrentMap<String, ServedStops> servedStops = new ConcurrentHashMap<>();

  /** 替补车的就绪时间：同一份表里一条出库线路只算一次（要扫全部交路）。表数 × 出库线路数，量很小。 */
  private final ConcurrentMap<ReadyKey, Long> readyByCreateRoute = new ConcurrentHashMap<>();

  /** 各车在当前车次上逐站的到发偏差：晚点追赶读最近一次，跑完一趟结账成一行日志。 */
  private final TripDelayLedger delays = new TripDelayLedger();

  private volatile Consumer<TripCancellations.Cancellation> cancellationListener =
      cancellation -> {};

  /** 出票侧还有没有某个交路意图的票在等车；由时刻表出票时由出票层装上。默认恒否。 */
  private volatile Predicate<TicketIntent> pendingTicket = intent -> false;

  public TimetableService(Supplier<Instant> clock, Consumer<String> debugLogger) {
    this.clock = clock == null ? Instant::now : clock;
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
    this.matcher = new TripMatcher(this.debugLogger);
    this.ledger = new DutyLedger(this.debugLogger);
  }

  /** 更新配置；关闭时会立刻清空绑定，避免留下"已经不生效但还显示着"的状态。 */
  public void applySettings(Settings next) {
    Settings resolved = next == null ? Settings.disabled() : next;
    this.settings = resolved;
    if (!resolved.enabled()) {
      clearAssignments("settings-disabled");
      cancellations.clear();
    }
  }

  /**
   * 设置车次取消的监听者（公开事件桥）；传 {@code null} 取消监听。
   *
   * <p>回调发生在调度路径里（销毁列车、票据作废、出票轮询），监听者只能入队，不能做耗时操作。
   */
  public void setCancellationListener(Consumer<TripCancellations.Cancellation> listener) {
    this.cancellationListener = listener == null ? cancellation -> {} : listener;
  }

  /**
   * 装上"这张票还在等车"的探针。
   *
   * <p>续班票等着本交路的车时不按时刻作废（{@link #awaitsOwnVehicle}），所以"过了容差"不再等于"接不上"：回库闸要问出票侧这张票还在不在。
   *
   * @param probe 给交路意图，返回出票侧是否还有这张票在等车；{@code null} 恢复为恒否
   */
  public void setPendingTicketProbe(Predicate<TicketIntent> probe) {
    this.pendingTicket = probe == null ? intent -> false : probe;
  }

  /**
   * 查询某趟车的取消。
   *
   * @param timetableId 时刻表
   * @param tripId 车次
   * @param serviceDate 起点发车所在日期
   * @return 没有取消时为空
   */
  public Optional<TripCancellations.Cancellation> cancellationOf(
      UUID timetableId, UUID tripId, LocalDate serviceDate) {
    return cancellations.find(timetableId, tripId, serviceDate);
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
    TimetableAssignment existing = matcher.get(key).orElse(null);
    // 解绑判定必须先于"本 route 有没有表"的判定：改派到一条没有时刻表的交路同样是换了任务，
    // 此时若把旧绑定留着，等这辆车绕回原交路时会拿上一圈的车次继续算时刻，而它已经是下一圈了。
    if (existing != null && !existing.routeId().equals(routeId)) {
      matcher.release(key, "route-changed");
      closeDelays(key, event.trainName(), "route-changed");
      existing = null;
    } else if (existing != null
        && event.stopIndex() == 0
        && !matcher.stillWaitingAtOrigin(existing, event, current, snapshot)) {
      // 回到起点就是新的一趟车。不在这里重新匹配的话，同一条 route 上连续接班的列车会一直用第一趟的时刻，
      // 交路进度也永远停在第一班——"每辆车最终都会回库"就失去了推进它的事件。
      // 但"还在起点等点"不算回到起点：门控每秒问一次，扣留期间反复解绑重绑只会刷日志、扫全表。
      // 旧绑定由重新匹配换掉：匹配到的还是同一班（晚点超过容差、仍在起点）就原样保留。
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
    return key == null || ledger.allowsLayoverReuse(key, trainName);
  }

  /**
   * 这辆车现在能不能被带回车库——表定回库票与 {@code ReclaimManager} 的回收共用这一个判据。
   *
   * <p>这是 {@link #allowsLayoverReuse} 的镜像：交路还有余额的车不准被带走，否则会把正等着跑下一班的车送回车库，后面的班次就开了天窗。
   * 没有交路进度的车（自由运行、或本来就不受时刻表管辖）照常可回。
   *
   * <p>例外是<b>交路已经断了</b>：剩下的班次全都过了发车容差，也没有一张票还在等这辆车，再也不会有人派它。不放行的话它会被自己交路的回库票
   * 永远拒绝、又被回收绕开，只能在终点等兜底销毁。
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
    Optional<DutyProgress> progress = ledger.progressOf(key).filter(p -> !p.exhausted());
    if (progress.isPresent() && continuationLost(key, progress.get(), current)) {
      debugLogger.accept(
          "TIMETABLE_DUTY_CONTINUATION_LOST train="
              + trainName
              + " duty="
              + progress.get().describe()
              + " action=allow-return");
      return true;
    }
    return ledger.allowsReturn(key, trainName);
  }

  /**
   * 停在正线折返点（区间路径点，不是车站也不是车库）的车能不能立即回收。
   *
   * <p>车停在正线上会挡同一股道的后车，不能像在站台上那样等闲置上限或交路末班过期。只管由时刻表出票的交路（{@code routeId}
   * 是它刚跑完的那一趟）：自由运行的线路、只扣车不出票的模式下，接哪一班由间隔出票决定，没有交路上的对应关系可判，照旧交给闲置回收。 由时刻表出票时以下情形都没有对应关系，放行：
   *
   * <ul>
   *   <li>本交路的<b>下一班</b>已过了发车容差、它的票也不在了：再下一次回到这个折返点发车要等一整个往返，停在正线上等那么久会一直挡着后车；
   *   <li>{@link #allowsReturn} 放行的情形：没绑交路（例如重启后账本丢了）、交路已跑完、剩下的班次全都作废。
   * </ul>
   *
   * <p>先判下一班：下一班还接得上时才落到 {@link #allowsReturn}，它记的 {@code TIMETABLE_RETURN_DENIED} 才名副其实。
   *
   * @param trainName 列车名
   * @param routeId 列车刚跑完的交路；缺失视为不由时刻表出票
   * @return 允许立即回收返回 true
   */
  public boolean allowsReturnFromMainlineTurnback(String trainName, Optional<UUID> routeId) {
    Settings current = settings;
    if (!current.spawnEnabled() || routeId.filter(this::managed).isEmpty()) {
      return false;
    }
    String key = keyOf(trainName);
    Optional<DutyProgress> progress = ledger.progressOf(key).filter(p -> !p.exhausted());
    if (progress.isPresent() && nextTripMissed(key, progress.get(), current)) {
      debugLogger.accept(
          "TIMETABLE_DUTY_NEXT_TRIP_MISSED train="
              + trainName
              + " duty="
              + progress.get().describe()
              + " action=allow-mainline-return");
      return true;
    }
    return allowsReturn(trainName);
  }

  /**
   * 严重晚点提前换车的余量：当前晚点超过发车容差再加这么多，才判定追赶也赶不上下一班。
   *
   * <p>停站压缩与放宽线路限速每趟大约能追回一到两分钟；超出这段的晚点到终点时下一班的票已经作废，早一点换车，替补车就早一点出库。
   */
  static final Duration VACATE_MARGIN = Duration.ofSeconds(120);

  /**
   * 替补车计划：空缺交路的第 {@code tripIndex} 班由一辆新车接，它走出库线路 {@code createRouteId} 从车库开到那一班的起点。
   *
   * @param timetable 所属时刻表
   * @param duty 空缺的交路
   * @param key 交路身份
   * @param tripIndex 替补车接起的那一班（交路内 0 起）
   * @param trip 那一班
   * @param createRouteId 替补车的出库线路
   * @param issueAt 按计划该出库的时刻（计划发车 − 走行 − 就绪），可能已经过去
   * @param latestIssue 最晚还来得及的出库时刻（计划发车 + 容差 − 走行 − 就绪）：出库票的到期时刻
   * @param departure 那一班的计划发车
   */
  public record Replacement(
      Timetable timetable,
      VehicleDuty duty,
      DutyKey key,
      int tripIndex,
      TimetableTrip trip,
      UUID createRouteId,
      Instant issueAt,
      Instant latestIssue,
      Instant departure) {

    /**
     * 替补车的出库走行，与交路自己的出库票同一形状；票面时刻取计划出库与现在的较晚者——替补常常是晚了才派的， 按过去的时刻出票，它的到期也会按过去算。
     *
     * @param now 出票时刻
     * @return 出库走行
     */
    public DueLeg leg(Instant now) {
      Instant at = now != null && now.isAfter(issueAt) ? now : issueAt;
      return new DueLeg(
          timetable, duty, RouteOperationType.CREATE, createRouteId, key.serviceDate(), at);
    }
  }

  /**
   * 出票侧每轮问一次：该派哪些替补车。
   *
   * <p>顺带做两件事：接不上下一班（过了容差、票也不在了）、而替补车赶得上它后面某一班的车，从交路上解下来；剩下的班次全都过了容差的空缺撤销。
   * 每个空缺同时只派一辆（出库票在路上时不再派），替补车绑上交路即填上。
   *
   * @param now 调度层当前时间
   * @return 这一轮要发出的替补出库票
   */
  public List<Replacement> replacementsDue(Instant now) {
    Settings current = settings;
    if (!current.enabled() || !current.spawnEnabled() || now == null) {
      return List.of();
    }
    for (Map.Entry<String, DutyKey> bound : ledger.bindings().entrySet()) {
      ledger
          .progressOf(bound.getKey())
          .filter(progress -> !progress.exhausted())
          .filter(progress -> nextTripMissed(bound.getKey(), progress, current))
          .filter(
              progress ->
                  replacementFor(bound.getValue(), progress.assignedTrips(), now, current)
                      .isPresent())
          .ifPresent(
              progress ->
                  ledger.vacate(
                      bound.getKey(), ledger.displayName(bound.getKey()), "next-trip-missed", now));
    }
    List<Replacement> out = new ArrayList<>();
    for (DutyLedger.Vacancy vacancy : ledger.vacancies()) {
      if (vacancy.replacementIndex() >= 0) {
        // 在途的替补：它要接的那一班过了容差还没绑上，出库票多半已经丢了（票据追踪重置、发车复位），退回待派。
        if (tripDeadline(vacancy.key(), vacancy.replacementIndex(), current)
            .filter(deadline -> deadline.isAfter(now))
            .isPresent()) {
          continue;
        }
        ledger.clearReplacement(vacancy.key(), vacancy.replacementIndex());
      }
      if (!anyTripLeft(vacancy, now, current)) {
        ledger.dropVacancy(vacancy.key(), "remaining-trips-expired");
        continue;
      }
      Optional<Replacement> planned =
          replacementFor(vacancy.key(), vacancy.fromIndex(), now, current);
      if (planned.isEmpty() || planned.get().issueAt().isAfter(now)) {
        continue;
      }
      Replacement replacement = planned.get();
      ledger.markReplacement(vacancy.key(), replacement.tripIndex());
      debugLogger.accept(
          "TIMETABLE_DUTY_REPLACEMENT duty="
              + vacancy.dutyCode()
              + " trip="
              + replacement.trip().tripCode()
              + " tripIndex="
              + replacement.tripIndex()
              + " createRoute="
              + replacement.createRouteId()
              + " plannedDeparture="
              + replacement.departure()
              + " replaced="
              + vacancy.retiredTrain());
      out.add(replacement);
    }
    return List.copyOf(out);
  }

  /**
   * 替补车的出库票作废了（没派出车就过了容差）：那一班不再有车在路上，空缺退回待派，下一轮再为后面的班次试。
   *
   * @param intent 出库票的交路意图
   */
  public void replacementAbandoned(TicketIntent intent) {
    if (intent != null && intent.kind() == RouteOperationType.CREATE) {
      ledger.clearReplacement(intent.key(), intent.tripIndex());
    }
  }

  /**
   * 这辆车是不是被换下来的：交路已交给替补车，它再也没有班可跑，回收应当尽快把它带走，不占着站台等闲置上限。
   *
   * @param trainName 列车名
   * @return 被换下来的车返回 true
   */
  public boolean retiredFromDuty(String trainName) {
    return settings.enabled() && ledger.isRetired(keyOf(trainName));
  }

  /** 当前空缺的交路数（诊断用）。 */
  public int vacantDutyCount() {
    return ledger.vacancies().size();
  }

  /** 交路里第 {@code index} 班的发车容差截止时刻；查不到时为空。 */
  private Optional<Instant> tripDeadline(DutyKey key, int index, Settings current) {
    Timetable timetable = snapshot.byId().get(key.timetableId());
    if (timetable == null) {
      return Optional.empty();
    }
    return timetable
        .duty(key.dutyId())
        .map(VehicleDuty::tripIds)
        .filter(ids -> index >= 0 && index < ids.size())
        .flatMap(ids -> timetable.trip(ids.get(index)))
        .map(
            trip ->
                timetable
                    .departureOnServiceDay(trip, key.serviceDate())
                    .plus(current.assignTolerance()));
  }

  /** 空缺交路还有没有没过容差的班次。 */
  private boolean anyTripLeft(DutyLedger.Vacancy vacancy, Instant now, Settings current) {
    Timetable timetable = snapshot.byId().get(vacancy.key().timetableId());
    if (timetable == null) {
      return false;
    }
    List<UUID> ids =
        timetable.duty(vacancy.key().dutyId()).map(VehicleDuty::tripIds).orElse(List.of());
    for (int j = Math.max(0, vacancy.fromIndex()); j < ids.size(); j++) {
      Optional<TimetableTrip> trip = timetable.trip(ids.get(j));
      if (trip.isPresent()
          && timetable
              .departureOnServiceDay(trip.get(), vacancy.key().serviceDate())
              .plus(current.assignTolerance())
              .isAfter(now)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 替补车最早能接交路里的哪一班：从 {@code fromIndex} 起第一班还没过容差、有出库线路能把车及时送到它起点的。
   *
   * <p>起点本身就是车库（首站 CRET）的班次不在这里派：那一班自己的票就会从车库出车，交路空着时出车即绑上。 出库线路取表里终点在那一班起点站台组的 CREATE
   * 线路（走行最短的那条），出库时刻 = 计划发车 − 走行 − 就绪；已经过了这个时刻， 只要现在出库还能在容差内到达也行。没有出库线路或来不及时试下一班。
   *
   * @return 替补计划；一班都接不上时为空
   */
  private Optional<Replacement> replacementFor(
      DutyKey key, int fromIndex, Instant now, Settings current) {
    Timetable timetable = snapshot.byId().get(key.timetableId());
    if (timetable == null) {
      return Optional.empty();
    }
    Optional<VehicleDuty> dutyOpt = timetable.duty(key.dutyId());
    if (dutyOpt.isEmpty()) {
      return Optional.empty();
    }
    VehicleDuty duty = dutyOpt.get();
    List<UUID> ids = duty.tripIds();
    for (int j = Math.max(0, fromIndex); j < ids.size(); j++) {
      Optional<TimetableTrip> tripOpt = timetable.trip(ids.get(j));
      Optional<TimetableRoutePlan> planOpt =
          tripOpt.flatMap(trip -> timetable.routePlan(trip.routeId()));
      if (tripOpt.isEmpty() || planOpt.isEmpty()) {
        continue;
      }
      TimetableTrip trip = tripOpt.get();
      Instant departure = timetable.departureOnServiceDay(trip, key.serviceDate());
      Instant deadline = departure.plus(current.assignTolerance());
      if (!deadline.isAfter(now) || startsAtDepot(planOpt.get())) {
        continue;
      }
      Optional<TimetableRoutePlan> create = positioningRoute(timetable, planOpt.get());
      if (create.isEmpty()) {
        continue;
      }
      long lead = create.get().totalRunSeconds() + readySeconds(timetable, create.get());
      Instant issueAt = departure.minusSeconds(lead);
      Instant arrival = (issueAt.isAfter(now) ? issueAt : now).plusSeconds(lead);
      if (arrival.isAfter(deadline)) {
        continue;
      }
      return Optional.of(
          new Replacement(
              timetable,
              duty,
              key,
              j,
              trip,
              create.get().routeId(),
              issueAt,
              deadline.minusSeconds(lead),
              departure));
    }
    return Optional.empty();
  }

  /** 这条运营线路是不是从车库出发（首站 CRET）：它的票自己会出车。 */
  private static boolean startsAtDepot(TimetableRoutePlan plan) {
    return plan.depotNodeId().isPresent()
        || TimetableConflictChecker.groupOf(plan.originNodeId()).contains(":D:");
  }

  /** 表里终点就在这条线路起点站台组的本线 CREATE 线路（外线走行没有本线的出库服务），走行最短的那条。 */
  private static Optional<TimetableRoutePlan> positioningRoute(
      Timetable timetable, TimetableRoutePlan target) {
    String origin = TimetableConflictChecker.groupOf(target.originNodeId());
    if (origin.isBlank()) {
      return Optional.empty();
    }
    TimetableRoutePlan best = null;
    for (TimetableRoutePlan plan : timetable.routePlans()) {
      if (plan.kind() == RouteOperationType.CREATE
          && !plan.external()
          && origin.equals(TimetableConflictChecker.groupOf(plan.terminalNodeId()))
          && (best == null || plan.totalRunSeconds() < best.totalRunSeconds())) {
        best = plan;
      }
    }
    return Optional.ofNullable(best);
  }

  /**
   * 出库之后到站还要多久才能走：取表里用这条出库线路的交路里最长的那段"首班发车 − 出库时刻 − 走行"。
   *
   * <p>编表时它来自出库线路终到站的 dwell；表里没存这个数，从已经排好的交路反推，与原计划同一口径。没有交路用它时按 0 计——判定本身还有发车容差兜底。
   */
  private long readySeconds(Timetable timetable, TimetableRoutePlan create) {
    return readyByCreateRoute.computeIfAbsent(
        new ReadyKey(timetable.id(), timetable.updatedAt(), create.routeId()),
        ignored -> computeReadySeconds(timetable, create));
  }

  /** 就绪时间缓存的键：同一份表（按更新时刻区分重新 build）里的一条出库线路。 */
  private record ReadyKey(UUID timetableId, Instant updatedAt, UUID createRouteId) {}

  private static long computeReadySeconds(Timetable timetable, TimetableRoutePlan create) {
    long ready = 0L;
    for (VehicleDuty duty : timetable.duties()) {
      if (duty.createRouteId().filter(create.routeId()::equals).isEmpty()
          || duty.tripIds().isEmpty()) {
        continue;
      }
      Optional<TimetableTrip> first = timetable.trip(duty.tripIds().get(0));
      if (first.isPresent()) {
        long gap =
            Math.floorMod(
                    first.get().departureSecondOfDay() - duty.plannedStartSecondOfDay(),
                    TimetableTrip.SECONDS_PER_DAY)
                - create.totalRunSeconds();
        ready = Math.max(ready, gap);
      }
    }
    return ready;
  }

  /**
   * 这张续班票（或回库票）是不是在等它自己交路的车：车还绑在交路上，也还没跑过这一班。
   *
   * <p>等的期间票不按"计划时刻 + 容差"作废，晚点就晚发。作废的话车到终点就没有班可接；终点没有回库线路时（原地折返的车站），
   * 它只能占着站台，后车全都进不来。等待仍有上界：车被换下（{@link DutyLedger#vacate}）或离开运行时管辖，交路就不再归它，票照常到期；
   * 出票侧另有折返票的最长等待（{@code spawn.pending-layover-max-age-seconds}）。
   *
   * @param intent 票据的交路意图
   * @return 本交路的车还在路上时为 true；未由时刻表出票、出库类票、交路没有车时为 false
   */
  public boolean awaitsOwnVehicle(TicketIntent intent) {
    Settings current = settings;
    if (!current.enabled() || !current.spawnEnabled() || intent == null) {
      return false;
    }
    boolean returnLeg = intent.kind() == RouteOperationType.RETURN;
    boolean continuation =
        returnLeg || (intent.kind() == RouteOperationType.OPERATION && intent.tripIndex() > 0);
    if (!continuation) {
      return false;
    }
    Optional<String> holder = ledger.holderOf(intent.key());
    if (holder.isEmpty()) {
      return false;
    }
    Optional<DutyProgress> progress = ledger.progressOf(holder.get());
    if (progress.isEmpty()) {
      // 出库票派出后、首班绑定前还没有进度：车在出库走行上，同样是在路上。
      return true;
    }
    return progress.get().dutyId().equals(intent.dutyId())
        && (returnLeg || progress.get().assignedTrips() <= intent.tripIndex());
  }

  /**
   * 交路的下一班（进度之后的第一班）是否已经接不上：过了发车容差，它的票也不在了；查不到归属、表、交路或下一班时返回 false。
   *
   * <p>票还在等这辆车时不算接不上：晚点就晚发（{@link #awaitsOwnVehicle}）。换车（{@link #replacementsDue}）同样以此为准—— 只看时刻的话，
   * 换下原车会让这一班的票立即作废，而原车本来会晚点把它跑掉。
   */
  private boolean nextTripMissed(String key, DutyProgress progress, Settings current) {
    int next = progress.assignedTrips();
    return boundDuty(key, progress)
        .filter(
            duty ->
                duty.trip(next).filter(trip -> overdue(duty, trip, current)).isPresent()
                    && !ticketWaiting(duty, next))
        .isPresent();
  }

  /**
   * 交路剩下的班次是否都已接不上：末班过了发车容差，剩下的班次也没有一张票还在等这辆车。
   *
   * <p>末班过了容差，前面的只会更早；但续班票等本交路的车时不会到期（{@link #awaitsOwnVehicle}），所以还要看票。 查不到归属、表、交路或末班时返回
   * false，保持"交路没跑完不准回库"的原判定。
   */
  private boolean continuationLost(String key, DutyProgress progress, Settings current) {
    Optional<BoundDuty> bound = boundDuty(key, progress);
    if (bound.isEmpty()) {
      return false;
    }
    BoundDuty duty = bound.get();
    int last = duty.tripIds().size() - 1;
    if (duty.trip(last).filter(trip -> overdue(duty, trip, current)).isEmpty()) {
      return false;
    }
    for (int index = progress.assignedTrips(); index <= last; index++) {
      if (ticketWaiting(duty, index)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 车所绑的交路。
   *
   * @param key 交路身份
   * @param timetable 所属时刻表
   * @param tripIds 交路里的班次，按执行顺序
   */
  private record BoundDuty(DutyKey key, Timetable timetable, List<UUID> tripIds) {

    /** 交路里第 {@code index} 班（0 起）；越界时为空。 */
    Optional<TimetableTrip> trip(int index) {
      return index >= 0 && index < tripIds.size()
          ? timetable.trip(tripIds.get(index))
          : Optional.empty();
    }
  }

  /** 这辆车所绑、与进度同一个的交路；查不到归属、表或交路时为空。 */
  private Optional<BoundDuty> boundDuty(String key, DutyProgress progress) {
    return ledger
        .bindingOf(key)
        .filter(bound -> bound.dutyId().equals(progress.dutyId()))
        .flatMap(
            bound ->
                Optional.ofNullable(snapshot.byId().get(bound.timetableId()))
                    .flatMap(
                        timetable ->
                            timetable
                                .duty(progress.dutyId())
                                .map(duty -> new BoundDuty(bound, timetable, duty.tripIds()))));
  }

  /** 这一班的计划发车加上容差已经过去（表定票按这个时刻到期）。 */
  private boolean overdue(BoundDuty duty, TimetableTrip trip, Settings current) {
    return duty.timetable()
        .departureOnServiceDay(trip, duty.key().serviceDate())
        .plus(current.assignTolerance())
        .isBefore(clock.get());
  }

  /** 出票侧还有交路第 {@code index} 班的票在等车。 */
  private boolean ticketWaiting(BoundDuty duty, int index) {
    DutyKey key = duty.key();
    return pendingTicket.test(
        new TicketIntent(
            key.timetableId(),
            key.dutyId(),
            key.serviceDate(),
            RouteOperationType.OPERATION,
            index));
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
    return key == null || ledger.acceptsVehicle(intent, key, trainName);
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
    if (key != null) {
      ledger.bind(trainName, key, duty, reason);
    }
  }

  /**
   * 这张票要从车库新出一辆车、而它的交路已经有车在跑时，返回那辆车。
   *
   * <p>同一交路只能有一辆车。重启后留在线上的车没有交路归属，它在门控上按时间绑到当前那一班，也就接下了那一班的交路；
   * 这时那一班的出库票再出库，就是同一交路两辆车——后出的那辆只能去抢下一班，从此每辆车错一班。
   *
   * <p>只管"会新出一辆车"的票：出库走行票，以及交路没有出库走行、首班本身从车库始发时的首班票。 续班与回库票只接本交路的车，本来就不会多出车。
   *
   * @param intent 票据的交路意图
   * @return 已经在跑这个交路的车（规范化后的键）；未启用按表运行、不是出库类票、或交路还没有车时为空
   */
  public Optional<String> runningVehicleFor(TicketIntent intent) {
    if (!settings.enabled() || intent == null || !materializesVehicle(intent)) {
      return Optional.empty();
    }
    return ledger.holderOf(intent.key());
  }

  private boolean materializesVehicle(TicketIntent intent) {
    if (intent.kind() == RouteOperationType.CREATE) {
      return true;
    }
    if (intent.kind() != RouteOperationType.OPERATION || intent.tripIndex() != 0) {
      return false;
    }
    return Optional.ofNullable(snapshot.byId().get(intent.timetableId()))
        .flatMap(timetable -> timetable.duty(intent.dutyId()))
        .map(duty -> duty.createRouteId().isEmpty())
        .orElse(false);
  }

  /** 查询某辆车绑在哪个交路上。 */
  public Optional<DutyKey> dutyBindingOf(String trainName) {
    return ledger.bindingOf(keyOf(trainName));
  }

  /** 查询某辆车当前的车次绑定。 */
  public Optional<TimetableAssignment> assignmentOf(String trainName) {
    return matcher.get(keyOf(trainName));
  }

  /**
   * 只读查询：已绑定车次的列车在某个停靠点的计划发车时刻。
   *
   * <p>与 {@link #scheduledDepartureAt} 不同：不建立、不解除绑定，也不写日志——ETA 与公开 API 可以放心轮询。
   * 未启用按表运行、或列车尚未绑定车次时为空。
   *
   * @param trainName 列车名（大小写不敏感）
   * @param stopIndex 停靠序号（与运行时进度索引同义）
   * @return 计划发车时刻
   */
  public Optional<Instant> plannedDepartureOf(String trainName, int stopIndex) {
    if (!settings.enabled()) {
      return Optional.empty();
    }
    return assignmentOf(trainName).flatMap(assignment -> plannedDeparture(assignment, stopIndex));
  }

  private Optional<Instant> plannedDeparture(TimetableAssignment assignment, int stopIndex) {
    return resolveTimetable(assignment)
        .flatMap(
            timetable ->
                timetable
                    .tripByCode(assignment.tripCode())
                    .flatMap(
                        trip ->
                            timetable.scheduledDeparture(
                                trip, stopIndex, assignment.serviceDate())));
  }

  /**
   * 只读查询：已绑定车次的列车在某个停靠点的计划到达时刻。语义同 {@link #plannedDepartureOf}。
   *
   * @param trainName 列车名（大小写不敏感）
   * @param stopIndex 停靠序号
   * @return 计划到达时刻
   */
  public Optional<Instant> plannedArrivalOf(String trainName, int stopIndex) {
    if (!settings.enabled()) {
      return Optional.empty();
    }
    return assignmentOf(trainName)
        .flatMap(
            assignment ->
                resolveTimetable(assignment)
                    .flatMap(
                        timetable ->
                            timetable
                                .tripByCode(assignment.tripCode())
                                .flatMap(
                                    trip ->
                                        timetable.scheduledArrival(
                                            trip, stopIndex, assignment.serviceDate()))));
  }

  /**
   * {@inheritDoc}
   *
   * <p>只认当前绑定、且车次就在事件所在交路上的：改派之后、重新绑定之前的停靠拿不到时刻，本站按计划停站。
   */
  @Override
  public Optional<Instant> boundDepartureAt(StationStopEvent event) {
    if (!settings.enabled() || event == null) {
      return Optional.empty();
    }
    return assignmentOf(event.trainName())
        .filter(assignment -> event.routeUuid().equals(Optional.of(assignment.routeId())))
        .flatMap(assignment -> plannedDeparture(assignment, event.stopIndex()));
  }

  /**
   * {@inheritDoc}
   *
   * <p>只认列车<b>此刻仍绑定</b>的那一趟：改派、下架、回起点重新匹配之后，旧车次的偏差不再算数——否则一辆已经不受时刻表约束的车会一直带着放宽的限速跑。
   */
  @Override
  public OptionalLong currentDelaySeconds(String trainName) {
    if (!settings.enabled()) {
      return OptionalLong.empty();
    }
    String key = keyOf(trainName);
    return matcher
        .get(key)
        .map(assignment -> delays.current(key, assignment.tripId()))
        .orElse(OptionalLong.empty());
  }

  /** 查询某辆车的交路进度。 */
  public Optional<DutyProgress> dutyProgressOf(String trainName) {
    return ledger.progressOf(keyOf(trainName));
  }

  /** 全部绑定快照。 */
  public List<TimetableAssignment> assignments() {
    return List.copyOf(matcher.assignments());
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
    matcher.retain(keep);
    ledger.retain(keep);
    servedStops.keySet().retainAll(keep);
    delays.retain(keep);
  }

  /**
   * 列车离开运行时管辖时释放绑定与交路进度。
   *
   * <p>车次还没跑完就离开的（销毁、异常清理），剩下的停车站登记为取消：已经发车的站不算，到了终点站就是跑完了。
   *
   * <p>交路后面还有班次的，交路登记为空缺交给替补（{@link #handOverDuty}）：替补赶得上的班次照常跑，赶不上的立即登记取消。
   */
  public void release(String trainName, String reason) {
    String key = keyOf(trainName);
    if (key == null) {
      return;
    }
    Optional<TimetableAssignment> released = matcher.release(key, reason);
    ServedStops served = servedStops.remove(key);
    released.ifPresent(assignment -> cancelRemainingStops(assignment, served, reason));
    handOverDuty(key, trainName, reason);
    ledger.release(key, trainName, reason);
    closeDelays(key, trainName, "released:" + reason);
  }

  /**
   * 车没了、交路还有班：交路转成空缺，替补赶不上的班次立即登记取消。
   *
   * <p>清车或手动删车之后，交路不能就这么空着等后面每张票各自过容差再逐张作废（那要拖到末班，站牌与 API 一直报着不会来的车）。 空缺交给 {@link #replacementsDue}
   * 按交路换车的同一套口径派替补；从空缺起到替补能接的第一班之前的班次，替补无论如何赶不上， 此刻就发 {@link
   * TripCancellations.Reason#VEHICLE_REMOVED}（后面的票到期时同一班不会再记一次）。一辆替补都派不出（没有能送到起点的出库线路）时，
   * 剩下的班次全部取消；起点本身是车库的班次不取消——它自己的票会出车，出车即绑上交路。
   *
   * <p>只在按表出票时做：只扣车不出票（{@code spawn-enabled: false}）时没有替补可派，后面的班次仍可能被别的车按时间接上。 卸载（{@link
   * StationStopObserver#RELEASE_UNLOADED}）不算车没了：它在离线存储里，醒来还是它，交了替补同一交路就有两辆车。
   */
  private void handOverDuty(String key, String trainName, String reason) {
    Settings current = settings;
    if (!current.enabled()
        || !current.spawnEnabled()
        || StationStopObserver.RELEASE_UNLOADED.equals(reason)
        || ledger.progressOf(key).filter(progress -> !progress.exhausted()).isEmpty()) {
      // 跑完交路回库、从没跑过一班（实体化回滚）的车没有班次可交，不记空缺，免得每次正常回库都印一行"换下"。
      return;
    }
    Instant now = clock.get();
    ledger
        .vacate(key, trainName, "removed:" + reason, now)
        .ifPresent(vacancy -> cancelUncoverable(vacancy, trainName, reason, now, current));
  }

  private void cancelUncoverable(
      DutyLedger.Vacancy vacancy, String trainName, String reason, Instant now, Settings current) {
    Timetable timetable = snapshot.byId().get(vacancy.key().timetableId());
    VehicleDuty duty =
        timetable == null ? null : timetable.duty(vacancy.key().dutyId()).orElse(null);
    if (duty == null) {
      return;
    }
    List<UUID> ids = duty.tripIds();
    Optional<Replacement> replacement =
        replacementFor(vacancy.key(), vacancy.fromIndex(), now, current);
    int coveredFrom = replacement.map(Replacement::tripIndex).orElse(ids.size());
    String detail =
        "duty-vacated:"
            + reason
            + replacement
                .map(planned -> " replacement-from=" + planned.trip().tripCode())
                .orElse(" no-replacement");
    for (int j = Math.max(0, vacancy.fromIndex()); j < coveredFrom; j++) {
      TimetableTrip trip = timetable.trip(ids.get(j)).orElse(null);
      TimetableRoutePlan plan =
          trip == null ? null : timetable.routePlan(trip.routeId()).orElse(null);
      if (plan == null || startsAtDepot(plan)) {
        continue;
      }
      Instant departure = timetable.departureOnServiceDay(trip, vacancy.key().serviceDate());
      // 取消登记、站牌与出票都按起点发车所在的日历日；交路身份用的是服务日，跨零点的班次两者差一天。
      LocalDate departureDate = LocalDate.ofInstant(departure, timetable.zoneId());
      OptionalInt origin = plan.firstStopAfter(-1);
      if (origin.isEmpty() || matcher.claimed(timetable.id(), trip.id(), departureDate)) {
        continue;
      }
      recordCancellation(
          new TripCancellations.Cancellation(
              timetable.id(),
              trip.id(),
              trip.tripCode(),
              trip.routeId(),
              departureDate,
              departure,
              TripCancellations.Scope.FULL,
              origin.getAsInt(),
              TripCancellations.Reason.VEHICLE_REMOVED,
              Optional.of(trainName),
              detail));
    }
  }

  /**
   * 记下列车在当前车次上停完了哪一站：发车才算这一站停完，到达车次终点站算整趟跑完。
   *
   * <p>由车站停靠观察者调用。只认当前绑定车次所在交路的事件：改派到别的交路之后、重新绑定之前的停靠不算旧车次的。
   *
   * <p>同时记晚点账：每次到发相对计划的偏差。到达终点站即结账。
   *
   * @param event 停靠事件
   * @param departure true 为发车，false 为到达
   */
  public void observeStop(StationStopEvent event, boolean departure) {
    String key = event == null ? null : keyOf(event.trainName());
    TimetableAssignment assignment = key == null ? null : matcher.get(key).orElse(null);
    if (assignment == null || !event.routeUuid().equals(Optional.of(assignment.routeId()))) {
      return;
    }
    Optional<Timetable> timetable = resolveTimetable(assignment);
    recordDelay(key, event, departure, assignment, timetable);
    int through = event.stopIndex();
    if (!departure) {
      OptionalInt terminating =
          timetable
              .flatMap(table -> table.routePlan(assignment.routeId()))
              .map(TimetableRoutePlan::terminatingSequence)
              .orElse(OptionalInt.empty());
      if (terminating.isEmpty() || through < terminating.getAsInt()) {
        return;
      }
      closeDelays(key, event.trainName(), "terminated");
    }
    servedStops.merge(key, ServedStops.of(assignment, through), ServedStops::advance);
  }

  /**
   * 记一次到发偏差；列车换了车次时，上一趟就此结账。
   *
   * <p>到站事件发生在列车停稳之后，而表定到达是压牌时刻（停稳与开门算在车站停车开销里）。两者差一个 {@link
   * Settings#arrivalSettle()}，不补上的话每一站到达都会凭空晚几秒。
   */
  private void recordDelay(
      String key,
      StationStopEvent event,
      boolean departure,
      TimetableAssignment assignment,
      Optional<Timetable> timetable) {
    Optional<Instant> planned =
        timetable.flatMap(
            table ->
                table
                    .tripByCode(assignment.tripCode())
                    .flatMap(
                        trip ->
                            departure
                                ? table.scheduledDeparture(
                                    trip, event.stopIndex(), assignment.serviceDate())
                                : table
                                    .scheduledArrival(
                                        trip, event.stopIndex(), assignment.serviceDate())
                                    .map(arrival -> arrival.plus(settings.arrivalSettle()))));
    if (planned.isEmpty()) {
      return;
    }
    long delay = Duration.between(planned.get(), event.at()).getSeconds();
    delays
        .record(key, assignment, new TripDelayLedger.Mark(event.stopIndex(), departure, delay))
        .ifPresent(summary -> debugLogger.accept(summary.logLine(event.trainName(), "rebound")));
    vacateIfHopeless(key, event.trainName(), delay, event.at());
  }

  /**
   * 严重晚点提前换车：晚点超过容差 + {@link #VACATE_MARGIN}，追赶也赶不上下一班；替补车赶得上下一班时，现在就把车从交路上解下来，
   * 让替补车早一点出库。这一趟照常跑完，车到终点后没有交路，由回收带走。替补车赶不上下一班时不解——解了反而让原车连后面还接得上的班次也跑不了。
   */
  private void vacateIfHopeless(String key, String trainName, long delaySeconds, Instant now) {
    Settings current = settings;
    if (!current.spawnEnabled()
        || delaySeconds <= current.assignTolerance().plus(VACATE_MARGIN).toSeconds()) {
      return;
    }
    Optional<DutyKey> bound = ledger.bindingOf(key);
    Optional<DutyProgress> progress = ledger.progressOf(key).filter(p -> !p.exhausted());
    // 只在替补车赶得上<b>下一班</b>时提前换：下一班本身替补也赶不上，原车晚点跑说不定还在容差内（终点有折返余量时），换了反而丢班。
    if (bound.isPresent()
        && progress.isPresent()
        && replacementFor(bound.get(), progress.get().assignedTrips(), now, current)
            .filter(plan -> plan.tripIndex() == progress.get().assignedTrips())
            .isPresent()) {
      ledger.vacate(key, trainName, "late-" + delaySeconds + "s", now);
    }
  }

  private void closeDelays(String key, String trainName, String reason) {
    delays.close(key).ifPresent(summary -> debugLogger.accept(summary.logLine(trainName, reason)));
  }

  /**
   * 一趟到点没有派出车的车次登记为整趟取消：票过了容差作废，或服务器卡顿超过追补上限被跳过。
   *
   * <p>这趟车已经有车绑着（重启后留在线上的车在门控上接了它）时不算取消。
   *
   * @param due 车次
   * @param detail 诊断明细
   */
  public void cancelUndispatched(DueTrip due, String detail) {
    if (due == null
        || !settings.enabled()
        || !snapshot.byId().containsKey(due.timetable().id())
        || matcher.claimed(due.timetable().id(), due.trip().id(), due.serviceDate())) {
      return;
    }
    OptionalInt origin =
        due.timetable()
            .routePlan(due.trip().routeId())
            .map(plan -> plan.firstStopAfter(-1))
            .orElse(OptionalInt.empty());
    if (origin.isEmpty()) {
      return;
    }
    recordCancellation(
        new TripCancellations.Cancellation(
            due.timetable().id(),
            due.trip().id(),
            due.trip().tripCode(),
            due.trip().routeId(),
            due.serviceDate(),
            due.departure(),
            TripCancellations.Scope.FULL,
            origin.getAsInt(),
            TripCancellations.Reason.NOT_DISPATCHED,
            Optional.empty(),
            detail));
  }

  /** 车次没跑完车就离开了：从第一个还没发车的停车站起登记取消；一站都没开出就是整趟取消。 */
  private void cancelRemainingStops(
      TimetableAssignment assignment, ServedStops served, String reason) {
    Timetable timetable = resolveTimetable(assignment).orElse(null);
    TimetableTrip trip =
        timetable == null ? null : timetable.trip(assignment.tripId()).orElse(null);
    TimetableRoutePlan plan =
        trip == null ? null : timetable.routePlan(trip.routeId()).orElse(null);
    if (plan == null) {
      return;
    }
    // 没有停靠记录时从绑定的那一站算起：绑定发生在该站的门控上，那时还没发车；更早的站不是这辆车跑的，不算。
    int through =
        served != null && served.sameTrip(assignment)
            ? served.through()
            : assignment.assignedAtStopIndex() - 1;
    OptionalInt first = plan.firstStopAfter(through);
    if (first.isEmpty()) {
      return;
    }
    boolean untouched = plan.firstStopAfter(-1).equals(first);
    recordCancellation(
        new TripCancellations.Cancellation(
            timetable.id(),
            trip.id(),
            trip.tripCode(),
            trip.routeId(),
            assignment.serviceDate(),
            trip.departureAt(assignment.serviceDate(), timetable.zoneId()),
            untouched ? TripCancellations.Scope.FULL : TripCancellations.Scope.PARTIAL,
            first.getAsInt(),
            TripCancellations.Reason.VEHICLE_REMOVED,
            Optional.of(assignment.trainName()),
            reason));
  }

  private void recordCancellation(TripCancellations.Cancellation cancellation) {
    Optional<TripCancellations.Cancellation> recorded = cancellations.record(cancellation);
    if (recorded.isEmpty()) {
      return;
    }
    debugLogger.accept(
        "TIMETABLE_TRIP_CANCELLED trip="
            + cancellation.tripCode()
            + " date="
            + cancellation.serviceDate()
            + " scope="
            + cancellation.scope()
            + " fromStop="
            + cancellation.firstCancelledStopSequence()
            + " reason="
            + cancellation.reason()
            + " train="
            + cancellation.trainName().orElse("-")
            + " detail="
            + cancellation.detail());
    try {
      cancellationListener.accept(cancellation);
    } catch (RuntimeException ex) {
      debugLogger.accept("TIMETABLE_TRIP_CANCELLED_LISTENER_FAILED error=" + ex);
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
    Instant catchUpFrom = to.minus(current.maxCatchUp());
    if (!catchUpFrom.isAfter(from)) {
      return tripsBetween(from, to);
    }
    // 追补上限之外的车次不会再出票，就此取消。
    for (DueTrip skipped : tripsBetween(from, catchUpFrom)) {
      cancelUndispatched(skipped, "catch-up-limit");
    }
    return tripsBetween(catchUpFrom, to);
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
        // 带客的回库班有 trip 行，但它由 duty 的回库走行票出车，这里不再出运营票。
        boolean returnTrip =
            timetable
                .routePlan(trip.routeId())
                .map(plan -> plan.kind() == RouteOperationType.RETURN)
                .orElse(false);
        if (returnTrip) {
          continue;
        }
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
    for (TimetableAssignment assignment : matcher.assignments()) {
      Optional<Timetable> timetableOpt = resolveTimetable(assignment);
      Optional<TimetableTrip> tripOpt =
          timetableOpt.flatMap(timetable -> timetable.tripByCode(assignment.tripCode()));
      if (tripOpt.isEmpty()) {
        continue;
      }
      DutyProgress progress = ledger.progressOf(keyOf(assignment.trainName())).orElse(null);
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
        matcher.assignMisses(),
        List.copyOf(deviations));
  }

  private Optional<TimetableAssignment> assign(
      String key,
      UUID routeId,
      List<Timetable> timetables,
      StationStopEvent event,
      Settings current) {
    Optional<TripMatcher.Match> matched =
        matcher.match(
            key,
            routeId,
            timetables,
            event,
            current,
            ledger.bindingOf(key),
            duty -> ledger.heldByOther(duty, key));
    if (matched.isEmpty()) {
      return Optional.empty();
    }
    TripMatcher.Match match = matched.get();
    // 取消之后又有车接上了这趟车（重启后留在线上的车、晚到的车）：站牌恢复正常。
    cancellations.revoke(
        match.timetable().id(), match.trip().id(), match.assignment().serviceDate());
    ledger.startOrAdvance(key, match.timetable(), match.trip());
    match.duty().ifPresent(duty -> ledger.bind(event.trainName(), key, duty, "trip-assigned"));
    return Optional.of(match.assignment());
  }

  /** 自启动以来绑定失败的累计次数。 */
  public long assignMisses() {
    return matcher.assignMisses();
  }

  private Optional<Timetable> resolveTimetable(TimetableAssignment assignment) {
    if (assignment == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(snapshot.byId().get(assignment.timetableId()));
  }

  private void clearAssignments(String reason) {
    servedStops.clear();
    delays.clear();
    if (matcher.isEmpty() && ledger.isEmpty()) {
      matcher.clear();
      return;
    }
    int size = matcher.clear();
    ledger.clear();
    debugLogger.accept("TIMETABLE_CLEAR assignments=" + size + " reason=" + reason);
  }

  /** 时刻表被删除或下架后，残留的占用必须一起清掉，否则那趟车会永远"已被占用"。 */
  private void dropClaimsOutsideSnapshot(Snapshot next) {
    Set<String> released = new HashSet<>();
    matcher.dropOutside(next.byId().keySet(), released::add);
    ledger.dropOutside(next.byId().keySet(), released);
    released.forEach(key -> closeDelays(key, key, "timetable-unpublished"));
    cancellations.retainTimetables(next.byId().keySet());
  }

  private static String keyOf(String trainName) {
    if (trainName == null) {
      return null;
    }
    String trimmed = trainName.trim();
    return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
  }

  /**
   * 某辆车在某趟车上已停完的最后一站。
   *
   * @param timetableId 时刻表
   * @param tripId 车次
   * @param serviceDate 起点发车所在日期
   * @param through 已停完的最后一个停靠序号（到达终点站时即终点序号）
   */
  private record ServedStops(UUID timetableId, UUID tripId, LocalDate serviceDate, int through) {

    static ServedStops of(TimetableAssignment assignment, int through) {
      return new ServedStops(
          assignment.timetableId(), assignment.tripId(), assignment.serviceDate(), through);
    }

    boolean sameTrip(TimetableAssignment assignment) {
      return sameTrip(assignment.timetableId(), assignment.tripId(), assignment.serviceDate());
    }

    /** 同一趟车只往前推；换了车次就从新记录重来。 */
    ServedStops advance(ServedStops next) {
      return sameTrip(next.timetableId, next.tripId, next.serviceDate) && through >= next.through
          ? this
          : next;
    }

    private boolean sameTrip(UUID otherTimetable, UUID otherTrip, LocalDate otherDate) {
      return timetableId.equals(otherTimetable)
          && tripId.equals(otherTrip)
          && serviceDate.equals(otherDate);
    }
  }

  /** 已发布时刻表的只读索引。 */
  record Snapshot(
      List<Timetable> timetables, Map<UUID, Timetable> byId, Map<UUID, List<Timetable>> byRoute) {

    static Snapshot empty() {
      return new Snapshot(List.of(), Map.of(), Map.of());
    }

    static Snapshot of(List<Timetable> published) {
      List<Timetable> kept =
          published == null
              ? List.of()
              : published.stream().filter(Objects::nonNull).filter(Timetable::published).toList();
      Map<UUID, Timetable> byId = new HashMap<>();
      Map<UUID, List<Timetable>> byRoute = new LinkedHashMap<>();
      for (Timetable timetable : kept) {
        byId.put(timetable.id(), timetable);
        for (UUID routeId : timetable.managedRouteIds()) {
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
   * @param assignedTrips 跑到了交路里的第几班，含正在跑的那一班；按班次在交路里的位置算，不按指派次数数
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

    /** 跑到了交路里的第 {@code position} 班：进度只进不退，同一班的重复查询不会把它拉回去。 */
    DutyProgress reached(UUID tripId, int position) {
      return new DutyProgress(
          dutyId, dutyCode, plannedTrips, Math.max(assignedTrips, position), tripId);
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
   * @param assignMisses 自启动以来绑不上车次的累计次数（详情看 {@code TIMETABLE_ASSIGN_MISS} 日志）
   * @param deviations 当前绑定的列车偏差
   */
  public record StatusSnapshot(
      boolean enabled,
      boolean spawnEnabled,
      int publishedTimetables,
      int managedRoutes,
      long assignMisses,
      List<DeviationEntry> deviations) {}

  /**
   * 按表运行的运行时配置。
   *
   * @param enabled 总开关；关闭时本类对调度层完全透明
   * @param spawnEnabled 是否由时刻表驱动发车出票
   * @param maxHold 早到列车最多被扣留多久
   * @param assignTolerance 匹配车次时允许的最大偏差
   * @param maxCatchUp 发车侧单次轮询最多回补多长时间窗口
   * @param arrivalSettle 压牌到停稳的时长：到站事件在停稳之后才发生，晚点账据此把表定到达换算到同一时刻
   */
  public record Settings(
      boolean enabled,
      boolean spawnEnabled,
      Duration maxHold,
      Duration assignTolerance,
      Duration maxCatchUp,
      Duration arrivalSettle) {

    public Settings {
      maxHold = maxHold == null || maxHold.isNegative() ? Duration.ZERO : maxHold;
      assignTolerance =
          assignTolerance == null || assignTolerance.isNegative() ? Duration.ZERO : assignTolerance;
      maxCatchUp = maxCatchUp == null || maxCatchUp.isNegative() ? Duration.ZERO : maxCatchUp;
      arrivalSettle =
          arrivalSettle == null || arrivalSettle.isNegative() ? Duration.ZERO : arrivalSettle;
    }

    /** 不区分压牌与停稳（{@code arrivalSettle} 为 0）；测试与未接配置的调用方用。 */
    public Settings(
        boolean enabled,
        boolean spawnEnabled,
        Duration maxHold,
        Duration assignTolerance,
        Duration maxCatchUp) {
      this(enabled, spawnEnabled, maxHold, assignTolerance, maxCatchUp, Duration.ZERO);
    }

    /** 关闭状态：按表运行完全不参与。 */
    public static Settings disabled() {
      return new Settings(false, false, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }
  }
}
