package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;

/**
 * 车次匹配：列车在门控上第一次问"我该几点开"时，把它绑到一趟表定车次上，并占住那趟车不让别的车再绑。 已经绑在交路上的车只跑本交路的车次；没绑交路的车按时间就近匹配。
 *
 * <p>从 {@link TimetableService} 抽出来的只有"绑定"这一件事：绑定表、trip 占用、起点等点判定、绑不上的留痕。 交路进度与交路归属在 {@link
 * DutyLedger}，快照与出票查询留在服务里。
 *
 * <p>所有 {@code TIMETABLE_ASSIGN*} / {@code TIMETABLE_RELEASE} 日志只从这里发出。
 */
final class TripMatcher {

  /** 匹配车次时允许回看/前看的服务日，用于覆盖跨零点的班次。 */
  private static final List<Integer> SERVICE_DATE_OFFSETS = List.of(-1, 0, 1);

  /** 绑定表上限：远超任何一条线的实际运营规模，命中说明清理没生效。 */
  static final int MAX_ASSIGNMENTS = 1024;

  /** 同一辆车同一停靠点同一原因的 MISS 日志节流：门控每秒问一次，不节流会刷屏。 */
  private static final Duration MISS_LOG_INTERVAL = Duration.ofSeconds(60);

  private final Consumer<String> debugLogger;
  private final ConcurrentMap<String, TimetableAssignment> assignments = new ConcurrentHashMap<>();
  private final ConcurrentMap<TripKey, String> claims = new ConcurrentHashMap<>();

  /** 绑不上车次的累计次数：跨线干扰 → 晚点 → 退回自由运行这条链，以前在数字上完全看不见。 */
  private final AtomicLong assignMisses = new AtomicLong();

  private final ConcurrentMap<String, Instant> missLoggedAt = new ConcurrentHashMap<>();

  TripMatcher(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /** 某辆车当前的绑定。 */
  Optional<TimetableAssignment> get(String key) {
    return key == null ? Optional.empty() : Optional.ofNullable(assignments.get(key));
  }

  /** 全部绑定。 */
  Collection<TimetableAssignment> assignments() {
    return List.copyOf(assignments.values());
  }

  int size() {
    return assignments.size();
  }

  boolean isEmpty() {
    return assignments.isEmpty();
  }

  long assignMisses() {
    return assignMisses.get();
  }

  /**
   * 这辆车是不是还在起点等它已经绑定的那趟车发车。
   *
   * <p>判据：绑定就是在起点建立的，且现在还没超过那趟车表定发车 + 容差。超过了就是下一圈回来了，该重新匹配。 门控每秒问一次，扣留期间反复解绑重绑只会刷日志、扫全表。
   */
  boolean stillWaitingAtOrigin(
      TimetableAssignment existing,
      StationStopEvent event,
      TimetableService.Settings current,
      TimetableService.Snapshot snapshot) {
    if (existing.assignedAtStopIndex() != 0) {
      return false;
    }
    Optional<Instant> scheduled =
        Optional.ofNullable(snapshot.byId().get(existing.timetableId()))
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
   * 为这辆车找一趟表定车次：同一 route、当前停靠点计划发车与"现在"最接近、尚未被别的车占用。
   *
   * <p>已经绑在交路上的车只在<b>本交路</b>里找，且不看容差：交路已经说明了它该跑哪一班，晚点就晚点跑。 按时间去抢别的交路的车次会连锁错班——被抢那一班的车只好再往后抢，
   * 每辆车都早到整整一班、在站台上等别人的时刻；交路归属与班次进度从此对不上，到终点连回库票都接不了 （2026-09-27 实服 PPK）。同一 route
   * 在一个交路里相隔整整一圈，就近不会选错。
   *
   * <p>没绑交路的车（重启后留在线上的车、自由运行的车）按时间就近匹配，限容差内，并跳过别的车已经绑定的交路： 那一班有它自己的车，孤儿车绑上去就是同一交路两辆车。
   *
   * <p>找不到就留痕并返回空——宁可少绑，不可错绑：错误的绑定会让车等一个不属于它的时刻，丢失绑定只会退回现状。
   *
   * <p>车手上已有绑定时（回到起点重新匹配），结果还是那一班就原样保留；换了班才解绑旧的，找不到也解绑旧的。
   *
   * @param boundDuty 这辆车已经绑定的交路
   * @param heldByOthers 某个交路是否已经归别的车
   */
  Optional<Match> match(
      String key,
      UUID routeId,
      List<Timetable> timetables,
      StationStopEvent event,
      TimetableService.Settings current,
      Optional<TimetableService.DutyKey> boundDuty,
      Predicate<TimetableService.DutyKey> heldByOthers) {
    if (assignments.size() >= MAX_ASSIGNMENTS) {
      debugLogger.accept(
          "TIMETABLE_ASSIGN_SKIP reason=assignment-limit train=" + event.trainName());
      return Optional.empty();
    }
    Instant now = event.at();
    long tolerance = current.assignTolerance().toSeconds();
    Candidate best = null;
    // 绑不上时要能说出"离得最近的那趟是谁、差多少"，否则日志上只剩 ASSIGN 变少，归因不了。
    String nearestCode = null;
    long nearestDeviation = Long.MAX_VALUE;
    int candidates = 0;
    int claimedByOthers = 0;
    for (Timetable timetable : timetables) {
      for (TimetableTrip trip : timetable.trips()) {
        if (!trip.routeId().equals(routeId)) {
          continue;
        }
        for (int offset : SERVICE_DATE_OFFSETS) {
          LocalDate date = LocalDate.ofInstant(now, timetable.zoneId()).plusDays(offset);
          Optional<TimetableService.DutyKey> tripDuty = dutyKeyOf(timetable, trip, date);
          if (boundDuty.isPresent() && !boundDuty.equals(tripDuty)) {
            continue;
          }
          Optional<Instant> scheduled = timetable.scheduledDeparture(trip, event.stopIndex(), date);
          if (scheduled.isEmpty()) {
            continue;
          }
          candidates++;
          long deviation = Duration.between(scheduled.get(), now).toSeconds();
          if (Math.abs(deviation) < Math.abs(nearestDeviation)) {
            nearestDeviation = deviation;
            nearestCode = trip.tripCode();
          }
          if (boundDuty.isEmpty() && Math.abs(deviation) > tolerance) {
            continue;
          }
          TripKey tripKey = new TripKey(timetable.id(), trip.id(), date);
          String holder = claims.get(tripKey);
          if ((holder != null && !holder.equals(key))
              || (boundDuty.isEmpty() && tripDuty.filter(heldByOthers).isPresent())) {
            claimedByOthers++;
            continue;
          }
          if (best == null || Math.abs(deviation) < Math.abs(best.deviationSeconds())) {
            best = new Candidate(timetable, trip, date, tripKey, tripDuty, deviation);
          }
        }
      }
    }
    TimetableAssignment previous = assignments.get(key);
    if (best == null) {
      if (previous != null) {
        release(key, "new-circuit");
      }
      String reason =
          candidates == 0
              ? boundDuty.isPresent() ? "duty-has-no-trip" : "no-trips"
              : claimedByOthers > 0 ? "all-claimed" : "out-of-tolerance";
      recordMiss(key, event, routeId, reason, nearestCode, nearestDeviation, tolerance, candidates);
      return Optional.empty();
    }
    if (previous != null
        && previous.timetableId().equals(best.timetable().id())
        && previous.tripId().equals(best.trip().id())
        && previous.serviceDate().equals(best.serviceDate())) {
      // 回到起点重新匹配，结果还是手上这一班（晚点了仍在起点等）：原样保留。门控每秒问一次，解绑重绑只会刷日志。
      return Optional.of(new Match(previous, best.timetable(), best.trip(), best.duty()));
    }
    String existingHolder = claims.putIfAbsent(best.tripKey(), key);
    if (existingHolder != null && !existingHolder.equals(key)) {
      return Optional.empty();
    }
    if (previous != null) {
      release(key, "new-circuit");
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
            + best.deviationSeconds()
            + " scope="
            + (boundDuty.isPresent() ? "duty" : "nearest"));
    return Optional.of(new Match(assignment, best.timetable(), best.trip(), best.duty()));
  }

  /** 某趟车在某个日历日发车时所属的交路身份；交路用服务日，跨零点的班次才能和前一晚出库的车对上。 */
  private static Optional<TimetableService.DutyKey> dutyKeyOf(
      Timetable timetable, TimetableTrip trip, LocalDate calendarDate) {
    return trip.dutyId()
        .map(
            dutyId ->
                new TimetableService.DutyKey(
                    timetable.id(), dutyId, timetable.serviceDayOf(trip, calendarDate)));
  }

  /** 记一次绑定失败：计数不节流，日志按 {@code train + stopIndex + reason} 每分钟一条。 */
  private void recordMiss(
      String key,
      StationStopEvent event,
      UUID routeId,
      String reason,
      String nearestCode,
      long nearestDeviation,
      long tolerance,
      int candidates) {
    assignMisses.incrementAndGet();
    String throttleKey = key + "#" + event.stopIndex() + "#" + reason;
    Instant last = missLoggedAt.get(throttleKey);
    if (last != null && Duration.between(last, event.at()).compareTo(MISS_LOG_INTERVAL) < 0) {
      return;
    }
    missLoggedAt.put(throttleKey, event.at());
    if (missLoggedAt.size() > MAX_ASSIGNMENTS) {
      missLoggedAt.clear();
    }
    debugLogger.accept(
        "TIMETABLE_ASSIGN_MISS train="
            + event.trainName()
            + " route="
            + routeId
            + " stopIndex="
            + event.stopIndex()
            + " nearest="
            + (nearestCode == null ? "-" : nearestCode + "@" + nearestDeviation + "s")
            + " tolerance="
            + tolerance
            + " candidates="
            + candidates
            + " reason="
            + reason);
  }

  /** 解绑并释放它占的那趟车。 */
  Optional<TimetableAssignment> release(String key, String reason) {
    TimetableAssignment removed = assignments.remove(key);
    if (removed == null) {
      return Optional.empty();
    }
    claims.remove(new TripKey(removed.timetableId(), removed.tripId(), removed.serviceDate()), key);
    debugLogger.accept(
        "TIMETABLE_RELEASE train="
            + removed.trainName()
            + " trip="
            + removed.tripCode()
            + " reason="
            + reason);
    return Optional.of(removed);
  }

  /** 某个日期的某趟车是否已有车绑着。 */
  boolean claimed(UUID timetableId, UUID tripId, LocalDate serviceDate) {
    return claims.containsKey(new TripKey(timetableId, tripId, serviceDate));
  }

  /** 只保留这些车的绑定。 */
  void retain(Set<String> keep) {
    for (String key : List.copyOf(assignments.keySet())) {
      if (!keep.contains(key)) {
        release(key, "train-gone");
      }
    }
  }

  /** 时刻表下架后，残留的绑定必须一起清掉，否则那趟车会永远"已被占用"。 */
  void dropOutside(Set<UUID> publishedTimetableIds, Consumer<String> released) {
    for (var entry : List.copyOf(assignments.entrySet())) {
      if (!publishedTimetableIds.contains(entry.getValue().timetableId())) {
        release(entry.getKey(), "timetable-unpublished");
        released.accept(entry.getKey());
      }
    }
  }

  /** 清空绑定与占用；返回清掉的绑定数。 */
  int clear() {
    int size = assignments.size();
    assignments.clear();
    claims.clear();
    return size;
  }

  /** 一次成功的匹配：绑定本身，它指向的表与车次，以及车次所属的交路（供交路账本推进进度与绑定归属）。 */
  record Match(
      TimetableAssignment assignment,
      Timetable timetable,
      TimetableTrip trip,
      Optional<TimetableService.DutyKey> duty) {}

  private record Candidate(
      Timetable timetable,
      TimetableTrip trip,
      LocalDate serviceDate,
      TripKey tripKey,
      Optional<TimetableService.DutyKey> duty,
      long deviationSeconds) {}

  private record TripKey(UUID timetableId, UUID tripId, LocalDate serviceDate) {}
}
