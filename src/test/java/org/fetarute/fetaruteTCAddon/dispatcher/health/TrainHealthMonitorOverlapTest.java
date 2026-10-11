package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.RecordingControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 车身叠在一起的两列车：停站中也照样处理，销毁开关、乘客、驾驶员、冷却、调度模式各守各的线。 */
@DisplayName("TrainHealthMonitor 车身重叠处理")
class TrainHealthMonitorOverlapTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final TrainOverlapDetector.Pair PAIR =
      new TrainOverlapDetector.Pair("A-1", "B-2", 10);

  private RuntimeDispatchService dispatchService;
  private RecordingControlAuthority authority;
  private TrainHealthMonitor monitor;
  private final List<HealthAlert> alerts = new ArrayList<>();

  @BeforeEach
  void setUp() {
    dispatchService = mock(RuntimeDispatchService.class);
    HealthAlertBus bus = new HealthAlertBus();
    bus.subscribe(alerts::add);
    monitor = new TrainHealthMonitor(dispatchService, mock(DwellRegistry.class), bus, s -> {});
    authority = new RecordingControlAuthority();
    monitor.setControlAuthority(authority);
    monitor.setTrainCleanupEnabled(true);
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(60));
    monitor.setDeadlockDestroyCooldown(Duration.ofSeconds(120));
    when(dispatchService.smartDispatcherMode()).thenReturn(SmartDispatcherMode.ENFORCE);
    when(dispatchService.destroyTrainByName(anyString(), anyString())).thenReturn(true);
    // 两车都停在站里：停站计时、发车门控都还在——正是现有互卡销毁一律不收的状态。
    context("A-1", RouteOperationType.OPERATION, false, 0);
    context("B-2", RouteOperationType.OPERATION, false, 0);
  }

  private void context(String train, RouteOperationType type, boolean passengers, int priority) {
    when(dispatchService.deadlockTrainContext(train))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.DeadlockTrainContext(
                    train,
                    5,
                    12,
                    SignalAspect.STOP,
                    0.0,
                    type,
                    priority,
                    true,
                    true,
                    false,
                    false,
                    false,
                    passengers,
                    false)));
  }

  private int observe(long seconds, TrainOverlapDetector.Pair... pairs) {
    return monitor.resolveTrainOverlaps(List.of(pairs), T0.plusSeconds(seconds));
  }

  private boolean alertedWith(String text) {
    return alerts.stream().anyMatch(alert -> alert.message().contains(text));
  }

  @Test
  @DisplayName("停站中叠着满阈值：销毁其中一列，另一列留下")
  void stackedAtStationIsResolvedDespiteDwelling() {
    assertEquals(0, observe(0, PAIR));
    assertEquals(0, observe(55, PAIR));
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());

    assertEquals(1, observe(60, PAIR));

    verify(dispatchService).destroyTrainByName("A-1", "health-overlap-destroy");
    verify(dispatchService).scheduleSurvivorRefreshAfterTrainRemoved("A-1", "B-2");
    assertTrue(alerts.stream().anyMatch(HealthAlert::autoFixed), alerts::toString);
  }

  @Test
  @DisplayName("中途有一轮没叠着：从头计时")
  void gapInObservationRestartsTheClock() {
    observe(0, PAIR);
    observe(40);
    observe(45, PAIR);
    assertEquals(0, observe(100, PAIR));
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());

    assertEquals(1, observe(105, PAIR));
  }

  @Test
  @DisplayName("销毁开关关着：只告警，不删车")
  void destroyDisabledOnlyAlerts() {
    monitor.setTrainCleanupEnabled(false);

    observe(0, PAIR);
    observe(60, PAIR);
    observe(65, PAIR);

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(alertedWith("销毁未开启"), alerts::toString);
    assertEquals(1, alerts.size(), "同一结论只告警一次");
  }

  @Test
  @DisplayName("有玩家乘坐的那列不删，删另一列")
  void passengerTrainIsKept() {
    context("A-1", RouteOperationType.OPERATION, true, 0);

    observe(0, PAIR);
    observe(60, PAIR);

    verify(dispatchService).destroyTrainByName("B-2", "health-overlap-destroy");
    verify(dispatchService, never()).destroyTrainByName("A-1", "health-overlap-destroy");
  }

  @Test
  @DisplayName("两列都有玩家乘坐：不删")
  void bothCarryingPassengersIsLeftAlone() {
    context("A-1", RouteOperationType.OPERATION, true, 0);
    context("B-2", RouteOperationType.OPERATION, true, 0);

    observe(0, PAIR);
    observe(60, PAIR);

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(alertedWith("玩家乘坐"), alerts::toString);
  }

  @Test
  @DisplayName("回库车先于运营车")
  void returnTrainIsPreferred() {
    context("B-2", RouteOperationType.RETURN, false, 0);

    observe(0, PAIR);
    observe(60, PAIR);

    verify(dispatchService).destroyTrainByName("B-2", "health-overlap-destroy");
  }

  @Test
  @DisplayName("要删的那列有驾驶员在岗：先请交还，不删")
  void driverOfTheVictimIsAskedToHandBack() {
    authority.controlName("A-1");

    observe(0, PAIR);
    observe(60, PAIR);

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(authority.handbacks().contains("A-1:deadlock"), authority.handbacks()::toString);
  }

  @Test
  @DisplayName("驾驶员坐在留下的那列上：不打断他，删另一列")
  void seatedDriverOfTheSurvivorIsLeftAlone() {
    authority.controlName("A-1");
    context("A-1", RouteOperationType.OPERATION, true, 0);

    observe(0, PAIR);
    observe(60, PAIR);

    verify(dispatchService).destroyTrainByName("B-2", "health-overlap-destroy");
    assertTrue(authority.handbacks().isEmpty(), authority.handbacks()::toString);
  }

  @Test
  @DisplayName("一个冷却窗口只删一列")
  void globalCooldownLimitsToOneDestroy() {
    TrainOverlapDetector.Pair other = new TrainOverlapDetector.Pair("C-3", "D-4", 10);
    context("C-3", RouteOperationType.OPERATION, false, 0);
    context("D-4", RouteOperationType.OPERATION, false, 0);

    observe(0, PAIR, other);
    assertEquals(1, observe(60, PAIR, other));
    assertEquals(0, observe(65, other));
    verify(dispatchService, times(1)).destroyTrainByName(anyString(), anyString());
    assertTrue(alertedWith("销毁冷却中"), alerts::toString);

    assertEquals(1, observe(180, other));
    verify(dispatchService).destroyTrainByName("C-3", "health-overlap-destroy");
  }

  @Test
  @DisplayName("观察模式：不删")
  void observeOnlyModeDoesNotDestroy() {
    when(dispatchService.smartDispatcherMode()).thenReturn(SmartDispatcherMode.OBSERVE_ONLY);

    observe(0, PAIR);
    observe(60, PAIR);

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(alertedWith("调度模式不允许销毁"), alerts::toString);
  }

  @Test
  @DisplayName("读不到运行状态：不删")
  void missingRuntimeStateIsFailClosed() {
    when(dispatchService.deadlockTrainContext("B-2")).thenReturn(Optional.empty());

    observe(0, PAIR);
    observe(60, PAIR);

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("自动修复关着：不删、不请驾驶员交还，只告警")
  void autoFixDisabledOnlyAlerts() {
    monitor.setAutoFixEnabled(false);
    authority.controlName("B-2");

    observe(0, PAIR);
    observe(60, PAIR);

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(authority.handbacks().isEmpty(), authority.handbacks()::toString);
    assertTrue(alertedWith("自动修复未开启"), alerts::toString);
  }

  @Test
  @DisplayName("销毁开关关着：不请驾驶员交还，也不去读运行状态")
  void destroyDisabledLeavesDriverAlone() {
    monitor.setTrainCleanupEnabled(false);
    authority.controlName("B-2");

    observe(0, PAIR);
    observe(60, PAIR);
    observe(65, PAIR);

    assertTrue(authority.handbacks().isEmpty(), authority.handbacks()::toString);
    verify(dispatchService, never()).deadlockTrainContext(anyString());
  }

  @Test
  @DisplayName("宽限内漏采一两轮：不从头计时")
  void briefMissWithinGraceKeepsTheClock() {
    observe(0, PAIR);
    observe(5);
    observe(10);
    observe(15, PAIR);

    assertEquals(1, observe(60, PAIR));
  }

  @Test
  @DisplayName("两组同名的车叠着：分不出删哪组，只告警")
  void sameNameGroupsAreAlertOnly() {
    TrainOverlapDetector.Pair twins = new TrainOverlapDetector.Pair("A-1", "a-1", 10);

    observe(0, twins);
    observe(60, twins);

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(alertedWith("同名"), alerts::toString);
  }

  @Test
  @DisplayName("销毁失败：这一对进冷却，下一轮不再重试")
  void failedDestroyBacksOff() {
    when(dispatchService.destroyTrainByName(anyString(), anyString())).thenReturn(false);

    observe(0, PAIR);
    assertEquals(0, observe(60, PAIR));
    assertEquals(0, observe(65, PAIR));
    assertEquals(0, observe(70, PAIR));

    verify(dispatchService, times(1)).destroyTrainByName(anyString(), anyString());
    assertTrue(alertedWith("失败"), alerts::toString);
  }

  @Test
  @DisplayName("三车叠在一处：删掉一列后，同一轮里涉及它的对不再报读不到状态")
  void threeStackDoesNotReportTheJustDestroyedTrain() {
    TrainOverlapDetector.Pair ac = new TrainOverlapDetector.Pair("A-1", "C-3", 10);
    TrainOverlapDetector.Pair bc = new TrainOverlapDetector.Pair("B-2", "C-3", 10);
    context("C-3", RouteOperationType.OPERATION, false, 0);
    when(dispatchService.destroyTrainByName("A-1", "health-overlap-destroy"))
        .thenAnswer(
            invocation -> {
              when(dispatchService.deadlockTrainContext("A-1")).thenReturn(Optional.empty());
              return true;
            });

    observe(0, PAIR, ac, bc);
    assertEquals(1, observe(60, PAIR, ac, bc));

    assertTrue(!alertedWith("读不到"), alerts::toString);
  }

  @Test
  @DisplayName("运行状态里的车名与采样车名不同：幸存车按采样车名推出")
  void survivorIsDerivedFromPairNames() {
    when(dispatchService.deadlockTrainContext("A-1"))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.DeadlockTrainContext(
                    "A-OLD",
                    5,
                    12,
                    SignalAspect.STOP,
                    0.0,
                    RouteOperationType.OPERATION,
                    0,
                    true,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false)));

    observe(0, PAIR);
    observe(60, PAIR);

    verify(dispatchService).destroyTrainByName("A-1", "health-overlap-destroy");
    verify(dispatchService).scheduleSurvivorRefreshAfterTrainRemoved("A-1", "B-2");
  }

  @Test
  @DisplayName("服务器冻结后计时平移：不因墙钟跳变立刻删车")
  void freezeGapIsNotCountedAsOverlap() {
    observe(0, PAIR);
    monitor.rebaseAfterFreeze(Duration.ofSeconds(300));

    assertEquals(0, observe(305, PAIR));
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertEquals(1, observe(360, PAIR));
  }
}
