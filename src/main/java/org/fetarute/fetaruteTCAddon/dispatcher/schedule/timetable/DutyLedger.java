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
 */
final class DutyLedger {

  private final Consumer<String> debugLogger;
  private final ConcurrentMap<String, TimetableService.DutyProgress> progress =
      new ConcurrentHashMap<>();

  /** 列车 → 它属于哪个交路（哪份表、哪个 duty、哪一天）。出库票实体化时或首次绑定车次时建立。 */
  private final ConcurrentMap<String, TimetableService.DutyKey> bindings =
      new ConcurrentHashMap<>();

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
    return progress.isEmpty() && bindings.isEmpty();
  }

  /**
   * 建立或推进这辆车的交路进度。
   *
   * <p>计的是"已经被指派了几班"，而不是"已经跑完几班"：对"还能不能再接一班"这个问题来说， 正在跑的那一班同样占用额度。用"跑完"计数则需要一个可靠的完成事件，而同一条 route
   * 连续接班时 并不会产生解绑，那个事件根本不存在——那正是上一版会漏计的地方。
   *
   * <p>换了 duty 就是换了一轮周转：旧进度作废，新 duty 从第一班重新计。
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
    TimetableService.DutyProgress previous = progress.get(key);
    if (previous == null || !previous.dutyId().equals(dutyId)) {
      progress.put(
          key,
          new TimetableService.DutyProgress(
              dutyId, duty.dutyCode(), duty.tripCount(), 1, trip.id()));
      return;
    }
    if (!previous.lastTripId().equals(trip.id())) {
      progress.put(key, previous.withTrip(trip.id()));
    }
  }

  /** 把一辆车绑到某个交路上。已经绑在别的交路上时不覆盖——那是一辆被错派的车，覆盖只会把错误藏起来。 */
  void bind(String trainName, String key, TimetableService.DutyKey duty, String reason) {
    TimetableService.DutyKey previous = bindings.putIfAbsent(key, duty);
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
  }

  /** 时刻表下架：归属于它的绑定与被解绑车的进度一起清掉。 */
  void dropOutside(Set<UUID> publishedTimetableIds, Set<String> releasedKeys) {
    for (String key : releasedKeys) {
      progress.remove(key);
    }
    bindings
        .entrySet()
        .removeIf(entry -> !publishedTimetableIds.contains(entry.getValue().timetableId()));
  }

  void clear() {
    progress.clear();
    bindings.clear();
  }
}
