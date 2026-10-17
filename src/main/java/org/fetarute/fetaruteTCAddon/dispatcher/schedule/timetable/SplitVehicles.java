package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;

/**
 * 解编生出的子交路与解下的那列车的对应。
 *
 * <p>解下的车登记成本站待命车，子交路的首班票去接它：首班只接这辆车，这辆车也只接子交路的票（没绑交路的待命车本来能接任何交路的首班）。 车被派出、绑上子交路之后对应作废，由交路绑定接手。
 * 拆不开的子交路记为落空：首班不接任何车，票到期照常登记取消。只在内存里：重启后照常按没绑交路的待命车处理。
 */
final class SplitVehicles {

  /** 子交路 → 解下的车（车名键）。 */
  private final Map<TimetableService.DutyKey, String> byDuty = new ConcurrentHashMap<>();

  /** 解下的车（车名键）→ 子交路。 */
  private final Map<String, TimetableService.DutyKey> byVehicle = new ConcurrentHashMap<>();

  /** 拆不开的子交路。 */
  private final Set<TimetableService.DutyKey> failed = ConcurrentHashMap.newKeySet();

  private final Consumer<String> debugLogger;

  SplitVehicles(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /** 记下子交路由这辆车跑。 */
  void pin(TimetableService.DutyKey duty, String vehicleKey) {
    String previous = byDuty.put(duty, vehicleKey);
    if (previous != null && !previous.equals(vehicleKey)) {
      byVehicle.remove(previous, duty);
    }
    byVehicle.put(vehicleKey, duty);
    failed.remove(duty);
  }

  /** 记下子交路落空（主干拆不开）。 */
  void fail(TimetableService.DutyKey duty) {
    failed.add(duty);
  }

  /** 子交路的车已经解下、正等着接首班。 */
  boolean waiting(TimetableService.DutyKey duty) {
    return byDuty.containsKey(duty);
  }

  /** 子交路落空。 */
  boolean failed(TimetableService.DutyKey duty) {
    return failed.contains(duty);
  }

  /** 车没了或已经绑上交路：作废它的对应。 */
  void release(String vehicleKey) {
    TimetableService.DutyKey duty = byVehicle.remove(vehicleKey);
    if (duty != null) {
      byDuty.remove(duty, vehicleKey);
    }
  }

  /** 只保留这些时刻表的记录（重新发布、撤表之后）。 */
  void retainTimetables(Set<java.util.UUID> timetableIds) {
    byDuty.keySet().removeIf(key -> !timetableIds.contains(key.timetableId()));
    byVehicle.values().removeIf(key -> !timetableIds.contains(key.timetableId()));
    failed.removeIf(key -> !timetableIds.contains(key.timetableId()));
  }

  /**
   * 这辆待命车能不能接这张票：解下的车只接自己子交路的票；子交路的首班只接解下的那辆车，落空的不接任何车。
   *
   * @param splitStarted 票所属的交路由拆分开始
   * @param trunkComing 主干那辆车还没跑过拆分的那一班：车还没解下来，首班留给它
   */
  boolean accepts(
      TimetableService.TicketIntent intent,
      String vehicleKey,
      String trainName,
      boolean splitStarted,
      boolean trunkComing) {
    TimetableService.DutyKey own = byVehicle.get(vehicleKey);
    if (own != null && !own.equals(intent.key())) {
      reject(trainName, intent, "split-vehicle-reserved");
      return false;
    }
    boolean firstTrip = intent.kind() == RouteOperationType.OPERATION && intent.tripIndex() == 0;
    if (!splitStarted || !firstTrip) {
      return true;
    }
    if (failed.contains(intent.key())) {
      reject(trainName, intent, "split-failed");
      return false;
    }
    Optional<String> pinned = Optional.ofNullable(byDuty.get(intent.key()));
    if (pinned.isPresent() && !pinned.get().equals(vehicleKey)) {
      reject(trainName, intent, "split-duty-reserved");
      return false;
    }
    if (pinned.isEmpty() && trunkComing) {
      // 车还没解下来：首班不让站里别的待命车接走
      reject(trainName, intent, "split-awaiting-trunk");
      return false;
    }
    return true;
  }

  private void reject(String trainName, TimetableService.TicketIntent intent, String reason) {
    debugLogger.accept(
        "TIMETABLE_CANDIDATE_REJECT train="
            + trainName
            + " ticketDuty="
            + intent.key().describe()
            + " reason="
            + reason);
  }
}
