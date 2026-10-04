package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.DispatchEffectClass;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Smart 恢复入口在非 ENFORCE 模式下不得改写账本；重点是自持尾部保护回收的**实测覆盖分支**。
 *
 * <p>{@code applySmartSelfOwnedStaleRetainRelease} 在 CONFLICT 候选为空时转去实测覆盖分支，而该分支释放的是 NODE/EDGE 上的
 * {@code PROTECTIVE_RETAIN}——同样是账本变更（{@code OCCUPANCY_MUTATION}），必须与 CONFLICT 分支过同一道模式闸。
 *
 * <p>用例必须成对看：
 *
 * <ul>
 *   <li>{@code ENFORCE} 下必须真的释放——否则"非 ENFORCE 下没释放"可能只是这条路径根本没走到；
 *   <li>{@code OBSERVE_ONLY / OFF} 下必须走到同一条路径、确有可释放的尾保，却一条 claim 都不动，并返回 {@code
 *       suppressed-by-mode}；
 *   <li>没有可释放的尾保时不得报告"被模式压下"——那会把"无事可做"说成"本会释放"。
 * </ul>
 *
 * <p>骨架只在 ENFORCE 下打开健康监控的自动修复，{@code DispatchObserverSideEffectTest} 因此从不在非 ENFORCE 下驱动任何恢复入口；
 * 这里另有一条用例把全部恢复入口逐 tick 扫一遍，补上这块空白。
 *
 * <p>所有用例都关掉骨架的恢复层（{@code smartRecoveryLayer(false)}），由用例逐 tick 直接调用恢复入口——恢复动作的发起方只有用例自己，
 * 才能把每一次调用前后的账本变化归到这一次调用上。
 */
class PhysicalEdgeRetainModeGateTest {

  /** 上限只是兜底：各模式下首个候选都远早于此出现，找到即停。 */
  private static final int MAX_TICKS = 600;

  /** 不以"找到候选"为终点的巡检跑多少 tick；足够覆盖开局与首轮互卡。 */
  private static final int SWEEP_TICKS = 120;

  /** 与生产恢复链的进度停滞判据同量级，只为让输入带上非零停滞时长。 */
  private static final Duration STUCK_DURATION = Duration.ofSeconds(30);

  private static final String PHYSICAL_DECISION = "SMART_RELEASE_PHYSICAL_EDGE_RETAIN";

  /** 健康监控恢复链会调用的全部入口，按链上顺序。 */
  private static final Map<String, RecoveryEntry> RECOVERY_ENTRIES = recoveryEntries();

  @Test
  void enforceReleasesDepartedPhysicalRetain() {
    DispatchScenarioHarness harness = contendedLoop(SmartDispatcherMode.ENFORCE);

    Attempt attempt = driveUntil(harness, PHYSICAL_DECISION, false);

    assertNotNull(attempt, () -> "整场没有一次实测覆盖释放——基线不成立\n" + harness.describeState());
    RuntimeDispatchService.SmartRecoveryActionResult result = attempt.result();
    assertTrue(result.applied(), () -> "ENFORCE 下实测覆盖分支没有落地：" + result);
    assertTrue(
        result.reason().startsWith("physical-edge-retain-released:"),
        () -> "释放结果缺少计数：" + result.reason());

    // 释放只能拿走本车在 NODE/EDGE 上的 PROTECTIVE_RETAIN，其余 claim 一条都不得变。
    List<String> removed = new ArrayList<>(attempt.before().claims());
    removed.removeAll(attempt.after().claims());
    List<String> added = new ArrayList<>(attempt.after().claims());
    added.removeAll(attempt.before().claims());
    assertFalse(removed.isEmpty(), "返回已释放，账本却没有任何 claim 消失");
    assertTrue(added.isEmpty(), () -> "释放过程中凭空多出 claim：" + added);
    String trainKey = TrainNameNormalizer.normalizeKey(attempt.train());
    assertTrue(
        removed.stream()
            .allMatch(
                line ->
                    line.startsWith(trainKey + "|PROTECTIVE_RETAIN|NODE|")
                        || line.startsWith(trainKey + "|PROTECTIVE_RETAIN|EDGE|")),
        () -> "释放波及了非本车或非尾保的 claim：" + removed);
    assertTrue(
        attempt.recentLog().stream()
            .anyMatch(
                line ->
                    line.contains("SMART_PHYSICAL_EDGE_RETAIN_RELEASED train=" + attempt.train())
                        && line.contains(" covered=")),
        "释放没有带实测覆盖证据");
  }

  @ParameterizedTest
  @EnumSource(
      value = SmartDispatcherMode.class,
      names = {"OBSERVE_ONLY", "OFF"})
  void nonEnforceSuppressesPhysicalRetainReleaseWithoutTouchingTheLedger(SmartDispatcherMode mode) {
    DispatchScenarioHarness harness = contendedLoop(mode);

    Attempt attempt = driveUntil(harness, PHYSICAL_DECISION, true);

    // 判别性的前提：必须真的走到实测覆盖分支、且确有可释放的尾保（候选来自只读预判）。
    // 没有这一条，"一条都没释放"也可能只是因为根本没有东西可放。
    // 分支只看返回的决策名：观察行会被诊断预算按墙钟丢弃，不能拿来判别。
    assertNotNull(attempt, () -> "整场没有一次实测覆盖分支的候选——用例不判别任何东西\n" + harness.describeState());

    RuntimeDispatchService.SmartRecoveryActionResult result = attempt.result();
    assertTrue(result.candidate());
    assertFalse(result.applied(), () -> mode + " 下实测覆盖分支落地了：" + result);
    assertEquals("suppressed-by-mode", result.reason());
    assertEquals(DispatchEffectClass.OCCUPANCY_MUTATION, result.effectClass());
    assertEquals(attempt.before(), attempt.after(), mode + " 下账本被改写");
  }

  /**
   * 没有可释放的尾保时，实测覆盖分支必须交回 not-found，而不是报告"被模式压下"。
   *
   * <p>本车在 NODE/EDGE 上一条 {@code PROTECTIVE_RETAIN} 都没有时，预判必然为空——这是不依赖覆盖细节的独立判据。
   */
  @Test
  void observeOnlyWithNothingReleasableFallsThroughToNotFound() {
    DispatchScenarioHarness harness = contendedLoop(SmartDispatcherMode.OBSERVE_ONLY);
    RuntimeDispatchService service = harness.service();
    int checked = 0;

    for (int tick = 0; tick < SWEEP_TICKS; tick++) {
      harness.runTicks(1);
      for (String train : harness.trainNames()) {
        String trainKey = TrainNameNormalizer.normalizeKey(train);
        boolean holdsPhysicalRetain =
            ledger(harness).claims().stream()
                .anyMatch(
                    line ->
                        line.startsWith(trainKey + "|PROTECTIVE_RETAIN|NODE|")
                            || line.startsWith(trainKey + "|PROTECTIVE_RETAIN|EDGE|"));
        RuntimeDispatchService.SmartRecoveryActionResult result =
            service.applySmartSelfOwnedStaleRetainRelease(
                service.smartRecoveryInput(train, STUCK_DURATION));
        if (holdsPhysicalRetain) {
          continue;
        }
        checked++;
        assertNotEquals(
            PHYSICAL_DECISION,
            result.decision(),
            "tick=" + harness.tick() + " train=" + train + " 没有任何尾保，却报告了实测覆盖分支的候选：" + result);
      }
    }

    int checkedCalls = checked;
    assertTrue(checkedCalls > 0, () -> "整场没有一次本车无尾保的调用——用例不判别任何东西\n" + harness.describeState());
  }

  /** 非 ENFORCE 下，恢复链上的每个入口、每一次调用都不得改写账本（claim、队列、版本）。 */
  @ParameterizedTest
  @EnumSource(
      value = SmartDispatcherMode.class,
      names = {"OBSERVE_ONLY", "OFF"})
  void nonEnforceRecoveryEntriesNeverTouchTheLedger(SmartDispatcherMode mode) {
    DispatchScenarioHarness harness = contendedLoop(mode);
    RuntimeDispatchService service = harness.service();
    int candidates = 0;

    for (int tick = 0; tick < SWEEP_TICKS; tick++) {
      harness.runTicks(1);
      for (String train : harness.trainNames()) {
        for (Map.Entry<String, RecoveryEntry> entry : RECOVERY_ENTRIES.entrySet()) {
          Ledger before = ledger(harness);
          RuntimeDispatchService.SmartRecoveryActionResult result =
              entry.getValue().apply(service, service.smartRecoveryInput(train, STUCK_DURATION));
          Ledger after = ledger(harness);
          assertEquals(
              before,
              after,
              mode
                  + " tick="
                  + harness.tick()
                  + " train="
                  + train
                  + " 入口 "
                  + entry.getKey()
                  + " 改写了账本："
                  + result);
          if (result != null && result.candidate()) {
            candidates++;
          }
        }
      }
    }

    // 反空绿：至少要有入口真的拿到过候选，否则"账本没变"只是因为没人有机会动手。
    int candidateCalls = candidates;
    assertTrue(candidateCalls > 0, () -> mode + " 下整场没有任何入口产生候选\n" + harness.describeState());
  }

  /**
   * 逐 tick 对每列车调用一次自持尾保回收入口，返回首个决策名为 {@code decision} 的调用。
   *
   * @param decision 要找的决策名，用来锁定实测覆盖分支（CONFLICT 分支的候选不算）
   * @param requireUnchanged 为真时，**每一次**调用前后的账本（claim、队列、版本）都必须一致，不只是候选那一次
   */
  private static Attempt driveUntil(
      DispatchScenarioHarness harness, String decision, boolean requireUnchanged) {
    RuntimeDispatchService service = harness.service();
    for (int tick = 0; tick < MAX_TICKS; tick++) {
      harness.runTicks(1);
      for (String train : harness.trainNames()) {
        Ledger before = ledger(harness);
        RuntimeDispatchService.SmartRecoveryActionResult result =
            service.applySmartSelfOwnedStaleRetainRelease(
                service.smartRecoveryInput(train, STUCK_DURATION));
        Ledger after = ledger(harness);
        if (requireUnchanged) {
          assertEquals(
              before,
              after,
              "tick=" + harness.tick() + " train=" + train + " 的恢复调用改写了账本：" + result);
        }
        if (result != null && decision.equals(result.decision())) {
          return new Attempt(train, result, before, after, harness.debugLog());
        }
      }
    }
    return null;
  }

  /** 账本的规范摘要：版本、claim（列车 key | 角色 | 资源类型 | 资源）与各资源的排队。 */
  private static Ledger ledger(DispatchScenarioHarness harness) {
    List<String> claims = new ArrayList<>();
    for (OccupancyClaim claim : harness.sortedClaims()) {
      claims.add(
          TrainNameNormalizer.normalizeKey(claim.trainName())
              + "|"
              + claim.role()
              + "|"
              + claim.resource().kind()
              + "|"
              + claim.resource());
    }
    List<String> queues = new ArrayList<>();
    for (OccupancyQueueSnapshot queue : harness.occupancy().snapshotQueues()) {
      queues.add(queue.resource() + " -> " + queue.entries());
    }
    queues.sort(null);
    return new Ledger(harness.occupancy().version(), claims, queues);
  }

  private static Map<String, RecoveryEntry> recoveryEntries() {
    Map<String, RecoveryEntry> entries = new LinkedHashMap<>();
    entries.put(
        "applySmartSelfOwnedStaleRetainRelease",
        RuntimeDispatchService::applySmartSelfOwnedStaleRetainRelease);
    entries.put("applySmartDrainUnlock", RuntimeDispatchService::applySmartDrainUnlock);
    entries.put("applySmartForwardUnlock", RuntimeDispatchService::applySmartForwardUnlock);
    entries.put(
        "applySmartQueuePositionYield", RuntimeDispatchService::applySmartQueuePositionYield);
    return entries;
  }

  private static DispatchScenarioHarness contendedLoop(SmartDispatcherMode mode) {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("ALFA", "BRAVO", "CHARLIE", "DELTA", "ECHO"));
    List<NodeId> path = topology.physicalPath();
    return DispatchScenarioHarness.builder()
        .topology(topology)
        .smartRecoveryLayer(false)
        .smartDispatcherMode(mode)
        .train("gate-lead", "shared-route", path, topology.stations(), 5)
        .train("gate-mid", "shared-route", path, topology.stations(), 2)
        .train("gate-tail", "shared-route", path, topology.stations(), 0)
        .build();
  }

  /** 恢复链上的一个入口。 */
  private interface RecoveryEntry
      extends BiFunction<
          RuntimeDispatchService,
          RuntimeDispatchService.SmartRecoveryInput,
          RuntimeDispatchService.SmartRecoveryActionResult> {}

  private record Ledger(long version, List<String> claims, List<String> queues) {}

  /** 一次恢复调用及其前后账本；{@code recentLog} 是调用刚结束时骨架保留的最近若干行日志。 */
  private record Attempt(
      String train,
      RuntimeDispatchService.SmartRecoveryActionResult result,
      Ledger before,
      Ledger after,
      List<String> recentLog) {}
}
