package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;

/**
 * 交路账本：每辆车跑到了交路的第几班、它属于哪个交路，以及由此推出的三道闸。
 *
 * <p>这是"每辆车最终都会回库"在运行期的执行点。语义刻意很窄：只回答"还能不能接 / 能不能被带走 / 能不能接这张票"， 不负责把车送回库——那仍由回库票与 {@code
 * ReclaimManager} 完成。
 *
 * <p>所有 {@code TIMETABLE_DUTY_*} / {@code TIMETABLE_RETURN_DENIED} / {@code
 * TIMETABLE_CANDIDATE_REJECT} 日志只从这里发出。
 *
 * <p><b>交路可以换车</b>，续班不限于由跑完上一班的那辆车来接：严重晚点、接不上下一班的车从交路上解下来（{@link #vacate}），
 * 交路登记为空缺，由出票侧派一辆替补车接它还赶得上的班次；被解下的车记为退役，不许再按时间绑回这个交路——否则同一交路会有两辆车。
 */
final class DutyLedger {

  private final Consumer<String> debugLogger;
  private final ConcurrentMap<String, TimetableService.DutyProgress> progress =
      new ConcurrentHashMap<>();

  /** 列车 → 它属于哪个交路（哪份表、哪个 duty、哪一天）。出库票实体化时或首次绑定车次时建立。 */
  private final ConcurrentMap<String, TimetableService.DutyKey> bindings =
      new ConcurrentHashMap<>();

  /** 空缺的交路：原来的车已经解下，还有班次等替补车来接。替补车绑上即移除。 */
  private final ConcurrentMap<TimetableService.DutyKey, Vacancy> vacancies =
      new ConcurrentHashMap<>();

  /** 列车 → 它被解下的那个交路：它不许再绑回去。列车绑上别的交路、或离开运行时管辖时清掉。 */
  private final ConcurrentMap<String, TimetableService.DutyKey> retired = new ConcurrentHashMap<>();

  /** 列车键 → 绑定时的列车名：按键扫出的日志要写回显示名，按车名 grep 日志才连得上。 */
  private final ConcurrentMap<String, String> displayNames = new ConcurrentHashMap<>();

  /**
   * 一个空缺的交路。
   *
   * @param key 交路身份
   * @param dutyCode 交路号，日志用
   * @param fromIndex 替补车从交路里的第几班（0 起）接起：原来的车跑到哪一班为止
   * @param vacatedAt 解下的时刻
   * @param reason 为什么解下
   * @param retiredTrain 被解下的车
   * @param replacementIndex 已经为哪一班派出了替补（出库票在路上）；没派为 −1
   */
  record Vacancy(
      TimetableService.DutyKey key,
      String dutyCode,
      int fromIndex,
      java.time.Instant vacatedAt,
      String reason,
      String retiredTrain,
      int replacementIndex) {

    Vacancy withReplacement(int index) {
      return new Vacancy(key, dutyCode, fromIndex, vacatedAt, reason, retiredTrain, index);
    }
  }

  DutyLedger(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  Optional<TimetableService.DutyProgress> progressOf(String key) {
    return key == null ? Optional.empty() : Optional.ofNullable(progress.get(key));
  }

  Optional<TimetableService.DutyKey> bindingOf(String key) {
    return key == null ? Optional.empty() : Optional.ofNullable(bindings.get(key));
  }

  boolean isEmpty() {
    return progress.isEmpty() && bindings.isEmpty() && vacancies.isEmpty() && retired.isEmpty();
  }

  /**
   * 建立或推进这辆车的交路进度。
   *
   * <p>记的是"跑到了交路里的第几班"（含正在跑的那一班），按这一班在交路里的位置算，而不是数自己被指派过几次：
   * 对"还能不能再接一班"这个问题来说，正在跑的那一班同样占用额度；而数次数离不开一个连续的计数者——
   * 折返复用常常给车改名（新名字从零数起），某一站没问到门控也会漏一次，两种情况都会让一辆跑完交路的车 以为自己还有班要跑，回库票就再也带不走它。位置只取决于这一班本身，不怕改名，也不怕漏。
   *
   * <p>带客回库班不在 {@link VehicleDuty#tripIds()} 里：跑到它说明交路已经跑完。换了 duty 就是换了一轮周转，旧进度作废； 同一交路里进度只进不退。
   */
  void startOrAdvance(String key, Timetable timetable, TimetableTrip trip) {
    UUID dutyId = trip.dutyId().orElse(null);
    if (dutyId == null) {
      progress.remove(key);
      return;
    }
    Optional<VehicleDuty> dutyOpt = timetable.duty(dutyId);
    if (dutyOpt.isEmpty()) {
      progress.remove(key);
      return;
    }
    VehicleDuty duty = dutyOpt.get();
    int position = duty.tripIds().indexOf(trip.id());
    int reached = position < 0 ? duty.tripCount() : position + 1;
    TimetableService.DutyProgress previous = progress.get(key);
    if (previous == null || !previous.dutyId().equals(dutyId)) {
      progress.put(
          key,
          new TimetableService.DutyProgress(
              dutyId, duty.dutyCode(), duty.tripCount(), reached, trip.id()));
      return;
    }
    if (!previous.lastTripId().equals(trip.id())) {
      progress.put(key, previous.reached(trip.id(), reached));
    }
  }

  /**
   * 把一辆车绑到某个交路上。已经绑在别的交路上时不覆盖——那是一辆被错派的车，覆盖只会把错误藏起来。
   *
   * <p>从这个交路上被解下的车不许绑回去；绑上的是替补车时，空缺就此填上。
   */
  void bind(String trainName, String key, TimetableService.DutyKey duty, String reason) {
    if (duty.equals(retired.get(key))) {
      debugLogger.accept(
          "TIMETABLE_DUTY_BIND_REFUSED train="
              + trainName
              + " duty="
              + duty.describe()
              + " reason=retired-from-duty requested="
              + reason);
      return;
    }
    TimetableService.DutyKey previous = bindings.putIfAbsent(key, duty);
    if (previous == null) {
      // 绑上别的交路就回到了正常运营：退役只拦它回原交路，不能让回收继续把它当成换下来的车。
      retired.remove(key);
      if (trainName != null && !trainName.isBlank()) {
        displayNames.put(key, trainName);
      }
      debugLogger.accept(
          "TIMETABLE_DUTY_BOUND train="
              + trainName
              + " duty="
              + duty.describe()
              + " reason="
              + reason);
      Vacancy filled = vacancies.remove(duty);
      if (filled != null) {
        debugLogger.accept(
            "TIMETABLE_DUTY_REPLACED train="
                + trainName
                + " duty="
                + filled.dutyCode()
                + " replaced="
                + filled.retiredTrain()
                + " fromTrip="
                + filled.fromIndex()
                + " vacatedAt="
                + filled.vacatedAt());
      }
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

  /**
   * 这个交路是否已经归别的车：没绑交路的车按时间匹配时，不能绑到一个已经有车在跑的交路上。
   *
   * <p>这辆车自己被解下的交路同样算"不归它"：空缺是留给替补车的，不能让被换下来的车按时间又接回去。
   */
  boolean heldByOther(TimetableService.DutyKey duty, String key) {
    if (duty.equals(retired.get(key))) {
      return true;
    }
    for (var entry : bindings.entrySet()) {
      if (entry.getValue().equals(duty) && !entry.getKey().equals(key)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 把一辆车从它的交路上解下来：交路还有班次没跑时登记为空缺，等替补车来接；这辆车记为退役，不许再绑回去。
   *
   * <p>解下之后这辆车没有交路：回库闸放行（没有进度可守），只能接别的交路的首班或被回收。它正在跑的这一趟不受影响——车次绑定在 {@link TripMatcher} 里，到站、结账照旧。
   *
   * @param key 规范列车键
   * @param trainName 列车名
   * @param reason 为什么解下
   * @param now 解下的时刻
   * @return 登记出的空缺；这辆车没绑交路、或交路已经跑完时为空
   */
  Optional<Vacancy> vacate(String key, String trainName, String reason, java.time.Instant now) {
    TimetableService.DutyKey duty = bindings.remove(key);
    TimetableService.DutyProgress current = progress.remove(key);
    if (duty == null) {
      return Optional.empty();
    }
    retired.put(key, duty);
    if (current == null || current.exhausted() || !current.dutyId().equals(duty.dutyId())) {
      debugLogger.accept(
          "TIMETABLE_DUTY_VACATED train="
              + trainName
              + " duty="
              + duty.describe()
              + " reason="
              + reason
              + " vacancy=none");
      return Optional.empty();
    }
    Vacancy vacancy =
        new Vacancy(duty, current.dutyCode(), current.assignedTrips(), now, reason, trainName, -1);
    vacancies.put(duty, vacancy);
    debugLogger.accept(
        "TIMETABLE_DUTY_VACATED train="
            + trainName
            + " duty="
            + current.dutyCode()
            + " trips="
            + current.assignedTrips()
            + "/"
            + current.plannedTrips()
            + " fromTrip="
            + current.assignedTrips()
            + " reason="
            + reason);
    return Optional.of(vacancy);
  }

  /** 这把键对应的列车名（最近一次绑定时记下）；没记过时就是键本身。 */
  String displayName(String key) {
    return key == null ? "" : displayNames.getOrDefault(key, key);
  }

  /** 这辆车是不是从某个交路上被换下来的（还没离开运行时管辖）。 */
  boolean isRetired(String key) {
    return key != null && retired.containsKey(key);
  }

  /** 当前全部绑定的快照：列车键 → 交路。 */
  java.util.Map<String, TimetableService.DutyKey> bindings() {
    return java.util.Map.copyOf(bindings);
  }

  /** 当前全部空缺的快照。 */
  java.util.List<Vacancy> vacancies() {
    return java.util.List.copyOf(vacancies.values());
  }

  /** 记下已为某一班派出替补（出库票在路上）；空缺已被填上或已撤销时不动。 */
  void markReplacement(TimetableService.DutyKey duty, int tripIndex) {
    vacancies.computeIfPresent(duty, (key, vacancy) -> vacancy.withReplacement(tripIndex));
  }

  /** 替补的出库票作废了：这一班不再有车在路上，空缺退回待派。 */
  void clearReplacement(TimetableService.DutyKey duty, int tripIndex) {
    vacancies.computeIfPresent(
        duty,
        (key, vacancy) ->
            vacancy.replacementIndex() == tripIndex ? vacancy.withReplacement(-1) : vacancy);
  }

  /** 撤销空缺：剩下的班次都已过了容差，再派车也没有班可跑。 */
  void dropVacancy(TimetableService.DutyKey duty, String reason) {
    Vacancy removed = vacancies.remove(duty);
    if (removed != null) {
      debugLogger.accept(
          "TIMETABLE_DUTY_VACANCY_CLOSED duty="
              + removed.dutyCode()
              + " fromTrip="
              + removed.fromIndex()
              + " reason="
              + reason);
    }
  }

  /** 绑在这个交路上的车（任取一辆；折返改名后旧名字要到下一次 retain 才清掉）。 */
  Optional<String> holderOf(TimetableService.DutyKey duty) {
    for (var entry : bindings.entrySet()) {
      if (entry.getValue().equals(duty)) {
        return Optional.of(entry.getKey());
      }
    }
    return Optional.empty();
  }

  /** duty 的班次余额用完了就不准再接运营班。 */
  boolean allowsLayoverReuse(String key, String trainName) {
    TimetableService.DutyProgress current = progress.get(key);
    if (current == null || !current.exhausted()) {
      return true;
    }
    debugLogger.accept(
        "TIMETABLE_DUTY_CLOSED train="
            + trainName
            + " duty="
            + current.dutyCode()
            + " trips="
            + current.assignedTrips()
            + "/"
            + current.plannedTrips()
            + " action=deny-reuse-return-to-storage");
    return false;
  }

  /** 交路还有余额的车不准被回库票带走。 */
  boolean allowsReturn(String key, String trainName) {
    TimetableService.DutyProgress current = progress.get(key);
    if (current == null || current.exhausted()) {
      return true;
    }
    debugLogger.accept(
        "TIMETABLE_RETURN_DENIED train="
            + trainName
            + " duty="
            + current.dutyCode()
            + " trips="
            + current.assignedTrips()
            + "/"
            + current.plannedTrips()
            + " action=keep-for-next-trip");
    return false;
  }

  /**
   * 这辆待命车能不能接这张票：绑在某交路上的车只接同一交路的票；没绑交路的车只能接首班。
   *
   * <p>续班要等的是本交路那辆车，晚点就晚点跑；回库票同样只带本交路的车，自由运行的车交给闲置回收。
   */
  boolean acceptsVehicle(TimetableService.TicketIntent intent, String key, String trainName) {
    TimetableService.DutyKey bound = bindings.get(key);
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

  /** 列车离开运行时管辖：释放进度与归属。 */
  void release(String key, String trainName, String reason) {
    retired.remove(key);
    displayNames.remove(key);
    bindings.remove(key);
    TimetableService.DutyProgress removed = progress.remove(key);
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

  void retain(Set<String> keep) {
    progress.keySet().retainAll(keep);
    bindings.keySet().retainAll(keep);
    retired.keySet().retainAll(keep);
    displayNames.keySet().retainAll(keep);
  }

  /** 时刻表下架：归属于它的绑定、空缺与被解绑车的进度一起清掉。 */
  void dropOutside(Set<UUID> publishedTimetableIds, Set<String> releasedKeys) {
    for (String key : releasedKeys) {
      progress.remove(key);
    }
    bindings
        .entrySet()
        .removeIf(entry -> !publishedTimetableIds.contains(entry.getValue().timetableId()));
    vacancies.keySet().removeIf(duty -> !publishedTimetableIds.contains(duty.timetableId()));
    retired
        .entrySet()
        .removeIf(entry -> !publishedTimetableIds.contains(entry.getValue().timetableId()));
  }

  void clear() {
    progress.clear();
    bindings.clear();
    vacancies.clear();
    retired.clear();
    displayNames.clear();
  }
}
