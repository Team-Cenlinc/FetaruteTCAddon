package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableDutyReplacementTest.overnight;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.CREATE_ROUTE;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.event;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.providerWith;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.timetable;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopObserver;
import org.junit.jupiter.api.Test;

/**
 * 随列车保存的交路记录：开服恢复、写回与兜底清理。
 *
 * <p>夹具交路 D001 在 3 月 2 日服务日 07:57 出库、08:17:20 回到车库；跨零点夹具 23:47 出库、次日 00:17:20 回到车库。
 */
class TimetableDutyRecoveryTest {

  private final AtomicReference<Instant> clock =
      new AtomicReference<>(Instant.parse("2026-03-02T08:00:00Z"));
  private final List<String> warnings = new ArrayList<>();

  /** 记录写入的交路记录存储；{@code failReads} 次读取先抛异常。 */
  private static final class RecordingStore implements TimetableDutyStore {
    final Map<String, String> tokens;
    final List<String> writes = new ArrayList<>();
    int failReads;

    RecordingStore(Map<String, String> tokens) {
      this.tokens = tokens;
    }

    @Override
    public Map<String, String> read() {
      if (failReads > 0) {
        failReads--;
        throw new IllegalStateException("store unavailable");
      }
      return tokens;
    }

    @Override
    public void write(String trainName, Optional<String> token) {
      writes.add(trainName + "=" + token.orElse("-"));
      token.ifPresentOrElse(value -> tokens.put(trainName, value), () -> tokens.remove(trainName));
    }
  }

  private TimetableService service(Timetable table, Duration maxDelay) {
    TimetableService service = new TimetableService(clock::get, message -> {});
    service.setWarningLogger(warnings::add);
    service.applySettings(
        new TimetableService.Settings(
            true,
            true,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            Duration.ZERO,
            maxDelay));
    service.reload(providerWith(table));
    return service;
  }

  private static String token(Timetable table, LocalDate serviceDay, int tripIndex) {
    VehicleDuty duty = table.duties().get(0);
    return new DutyRecoveryToken(
            new TimetableService.DutyKey(table.id(), duty.id(), serviceDay),
            table.updatedAt().toEpochMilli(),
            tripIndex + 1,
            Optional.of(duty.tripIds().get(tripIndex)))
        .encode();
  }

  /** 停服过夜，次日 09:00 开服：昨天的交路早已结束，不能绑回去（否则随即被判严重晚点、退役并取消昨天的班次），记录一并清掉。 */
  @Test
  void aTokenOfADutyThatEndedYesterdayIsClearedInsteadOfRestored() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 2);
    RecordingStore store =
        new RecordingStore(
            new HashMap<>(Map.of("train-A", token(table, LocalDate.of(2026, 3, 2), 0))));
    TimetableService service = service(table, Duration.ofSeconds(900));
    service.setDutyStore(store);

    service.restoreDutyBindings(Instant.parse("2026-03-03T09:00:00Z"));

    assertTrue(service.dutyBindingOf("train-A").isEmpty());
    assertTrue(service.assignmentOf("train-A").isEmpty());
    assertEquals(List.of("train-A=-"), store.writes, "过期记录清掉，下次开服不再读到");
  }

  /** 跨零点：属于 3 月 2 日服务日的交路在 3 月 3 日 00:05 仍在跑，按昨天的服务日恢复。 */
  @Test
  void anOvernightDutyIsRestoredAfterMidnightWhileStillRunning() {
    Timetable table = overnight(timetable(TimetableStatus.PUBLISHED, 3));
    RecordingStore store =
        new RecordingStore(
            new HashMap<>(Map.of("train-A", token(table, LocalDate.of(2026, 3, 2), 1))));
    TimetableService service = service(table, Duration.ZERO);
    service.setDutyStore(store);

    service.restoreDutyBindings(Instant.parse("2026-03-03T00:05:00Z"));

    assertEquals(
        Optional.of(LocalDate.of(2026, 3, 2)),
        service.dutyBindingOf("train-A").map(TimetableService.DutyKey::serviceDate));
    assertEquals(
        Optional.of(LocalDate.of(2026, 3, 3)),
        service.assignmentOf("train-A").map(TimetableAssignment::serviceDate),
        "00:00 那班按发车的日历日绑定");
    assertTrue(store.writes.isEmpty());
  }

  /** 时间窗是"回到车库的计划时刻 + 晚点上限"：跨零点交路 00:17:20 回库、上限 900 秒，00:32:20 之前恢复，之后清掉。 */
  @Test
  void theRecoveryWindowEndsAtThePlannedDepotArrivalPlusTheDelayCap() {
    Timetable table = overnight(timetable(TimetableStatus.PUBLISHED, 3));
    String saved = token(table, LocalDate.of(2026, 3, 2), 2);

    RecordingStore inside = new RecordingStore(new HashMap<>(Map.of("train-A", saved)));
    TimetableService before = service(table, Duration.ofSeconds(900));
    before.setDutyStore(inside);
    before.restoreDutyBindings(Instant.parse("2026-03-03T00:32:19Z"));
    assertTrue(before.dutyBindingOf("train-A").isPresent());
    assertTrue(inside.writes.isEmpty());

    RecordingStore outside = new RecordingStore(new HashMap<>(Map.of("train-A", saved)));
    TimetableService after = service(table, Duration.ofSeconds(900));
    after.setDutyStore(outside);
    after.restoreDutyBindings(Instant.parse("2026-03-03T00:32:20Z"));
    assertTrue(after.dutyBindingOf("train-A").isEmpty());
    assertEquals(List.of("train-A=-"), outside.writes);
  }

  /** 读记录失败不能抛到启动门里：保持待恢复，期间不补缺车交路（未知的车不能当成不存在），下一轮读成功再恢复、再补。 */
  @Test
  void aFailedReadKeepsRecoveryPendingAndHoldsBackUnboundRecovery() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 2);
    RecordingStore store = new RecordingStore(new HashMap<>());
    store.failReads = 2;
    TimetableService service = service(table, Duration.ZERO);
    service.setDutyStore(store);
    Instant now = Instant.parse("2026-03-02T08:05:00Z");

    assertDoesNotThrow(() -> service.restoreDutyBindings(now));
    service.recoverUnboundDuties(Set.of(CREATE_ROUTE), now);
    assertEquals(0, service.vacantDutyCount(), "恢复未完成时不补车");
    assertDoesNotThrow(() -> service.restoreDutyBindings(now));
    assertEquals(1, warnings.size(), () -> "连续失败只告警一次：" + warnings);

    service.restoreDutyBindings(now);
    service.recoverUnboundDuties(Set.of(CREATE_ROUTE), now);
    assertEquals(1, service.vacantDutyCount(), "恢复完成后照常补缺车交路");
  }

  /** 判断"叫来的车"的回调出错时不能把整个恢复打断：按不是叫来的车处理（与其余调用同一口径）。 */
  @Test
  void aThrowingUnscheduledProbeDoesNotAbortRecovery() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 2);
    RecordingStore store =
        new RecordingStore(
            new HashMap<>(Map.of("train-A", token(table, LocalDate.of(2026, 3, 2), 0))));
    TimetableService service = service(table, Duration.ZERO);
    service.setDutyStore(store);
    service.setUnscheduledTrain(
        name -> {
          throw new IllegalStateException("probe failed");
        });

    assertDoesNotThrow(() -> service.restoreDutyBindings(Instant.parse("2026-03-02T08:00:45Z")));
    assertTrue(service.dutyBindingOf("train-A").isPresent());
  }

  /** 两辆车带着同一个交路的记录：按车名定胜者，与读出来的顺序无关。 */
  @Test
  void duplicateOwnersResolveByTrainNameRegardlessOfReadOrder() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 2);
    String saved = token(table, LocalDate.of(2026, 3, 2), 0);
    for (List<String> order :
        List.of(List.of("b-train", "a-train"), List.of("a-train", "b-train"))) {
      Map<String, String> tokens = new LinkedHashMap<>();
      order.forEach(name -> tokens.put(name, saved));
      TimetableService service = service(table, Duration.ZERO);
      service.setDutyStore(new RecordingStore(tokens));

      service.restoreDutyBindings(Instant.parse("2026-03-02T08:00:45Z"));

      assertTrue(service.dutyBindingOf("a-train").isPresent(), order::toString);
      assertTrue(service.dutyBindingOf("b-train").isEmpty(), order::toString);
    }
  }

  /** 首次绑上交路只写一次记录（不先删后写），之后同一交路、同一班的重复确认不再写。 */
  @Test
  void bindingWritesTheTokenOnceAndRepeatedConfirmationsWriteNothing() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 2);
    RecordingStore store = new RecordingStore(new HashMap<>());
    TimetableService service = service(table, Duration.ZERO);
    service.setDutyStore(store);
    service.restoreDutyBindings(clock.get());

    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    assertEquals(1, store.writes.size(), store.writes::toString);
    assertFalse(store.writes.get(0).endsWith("=-"), store.writes::toString);
    assertEquals(
        Optional.of(1),
        DutyRecoveryToken.parse(store.tokens.get("train-A")).map(DutyRecoveryToken::assignedTrips),
        "记录里的进度就是第一班");

    service.scheduledDepartureAt(event("train-A", 1, Instant.parse("2026-03-02T08:01:50Z")));
    service.bindDuty("train-A", service.dutyBindingOf("train-A").orElseThrow(), "repeat");
    assertEquals(1, store.writes.size(), store.writes::toString);
  }

  /** 兜底清理用服务上注入的同一份记录：带着交路记录的离线车保留归属。 */
  @Test
  void retainKeepsOfflineOwnersFromTheInjectedStore() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 2);
    RecordingStore store = new RecordingStore(new HashMap<>());
    TimetableService service = service(table, Duration.ZERO);
    service.setDutyStore(store);
    service.restoreDutyBindings(clock.get());
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));

    service.retain(List.of());

    assertTrue(service.dutyBindingOf("train-A").isPresent(), "离线车仍持有交路");
    assertTrue(service.assignmentOf("train-A").isPresent());
  }

  /** 读记录失败时兜底清理什么都不清：不知道哪些离线车还在，清掉归属会让交路被别的车接走。 */
  @Test
  void retainKeepsEverythingWhenTheStoreCannotBeRead() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 2);
    RecordingStore store = new RecordingStore(new HashMap<>());
    TimetableService service = service(table, Duration.ZERO);
    service.setDutyStore(store);
    service.restoreDutyBindings(clock.get());
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    store.failReads = 1;

    assertDoesNotThrow(() -> service.retain(List.of()));

    assertTrue(service.dutyBindingOf("train-A").isPresent());
    assertTrue(service.assignmentOf("train-A").isPresent());
  }

  /** 卸载时提前返回，车次绑定与晚点账留着等车醒来；它们不会无限期留着：车被删掉后 TrainCarts 不再有它的属性，交路记录随之消失， 下一次兜底清理就把它清掉。 */
  @Test
  void stateKeptForAnUnloadedVehicleIsDroppedOnceItsSavedDutyIsGone() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 2);
    RecordingStore store = new RecordingStore(new HashMap<>());
    TimetableService service = service(table, Duration.ZERO);
    service.setDutyStore(store);
    service.restoreDutyBindings(clock.get());
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));

    service.release("train-A", StationStopObserver.RELEASE_UNLOADED);
    service.retain(List.of());
    assertTrue(service.assignmentOf("train-A").isPresent(), "离线期间保留");
    assertTrue(service.dutyBindingOf("train-A").isPresent());

    store.tokens.clear();
    service.retain(List.of());
    assertTrue(service.assignmentOf("train-A").isEmpty());
    assertTrue(service.dutyBindingOf("train-A").isEmpty());
    assertTrue(service.dutyProgressOf("train-A").isEmpty());
  }
}
