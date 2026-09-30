package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;

/**
 * READY 之后单车水合问题不得把全网拖进"重建—READY—再重建"的死循环。
 *
 * <p>2026-09-30 两次实服事故的回归：08:37 全网循环 15 秒直到人工重载，09:15 主线程被同一条链占死 65 秒、看门狗强杀。 本类另开而不放进
 * RuntimeDispatchServiceTest，是因为后者的方法数已贴着 SpotBugs 的 1000 上限。
 */
class RuntimeDispatchServiceStartupEscalationTest {

  private final UUID worldId = UUID.randomUUID();
  private final RouteDefinition route =
      new RouteDefinition(
          RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
  private final List<String> debugMessages = new ArrayList<>();

  private RuntimeDispatchService newService(DwellRegistry dwellRegistry) {
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    return RuntimeDispatchServiceTest.startupReconstructionService(
        worldId,
        route,
        occupancyManager,
        debugMessages::add,
        RuntimeDispatchServiceTest.startupPhysicalGraph(worldId),
        dwellRegistry);
  }

  private FakeTrain train(String tcName, String taggedOwner) {
    List<String> tags =
        new ArrayList<>(
            List.of(
                "FTA_OPERATOR_CODE=op",
                "FTA_LINE_CODE=l1",
                "FTA_ROUTE_CODE=r1",
                "FTA_ROUTE_INDEX=0"));
    if (taggedOwner != null) {
      tags.add(RouteProgressRegistry.TAG_TRAIN_NAME + "=" + taggedOwner);
    }
    return new FakeTrain(
        worldId, new TagStore(tcName, tags.toArray(String[]::new)).properties(), false);
  }

  /** 两个活编组带同一逻辑身份：多出来的那个被隔离并销毁，重建才能在没有孪生的现场上收敛。 */
  @Test
  void duplicateTwinIsIsolatedAndRecoveryConverges() {
    RuntimeDispatchService service = newService(new DwellRegistry());
    FakeTrain existing = train("shared-owner", null);
    FakeTrain twin = train("shared-owner", null);
    AtomicInteger recoveryRequests = new AtomicInteger();
    service.setStartupRecoveryRequestedListener(recoveryRequests::incrementAndGet);
    assertTrue(service.rebuildOccupancySnapshot(List.of(existing)));

    service.handleSignalTick(twin, false);
    assertEquals(1, recoveryRequests.get());
    assertEquals(1, twin.destroyCalls);
    assertEquals(0, existing.destroyCalls);

    // 孪生已被隔离：它再次触发信号检查只硬停，不会再请求全局恢复。
    service.handleSignalTick(twin, false);
    service.handleSignalTick(twin, false);
    assertEquals(1, recoveryRequests.get());

    // 孪生还活着时重建拒绝提交；实体被移除后同一现场一次收敛。
    assertFalse(service.rebuildOccupancySnapshot(List.of(existing, twin)));
    service.handleTrainRemoved(twin);
    int requestsAfterRemoval = recoveryRequests.get();
    assertEquals(2, requestsAfterRemoval, "移除同名编组本身按既有约定请求一次全局重建");
    assertTrue(service.rebuildOccupancySnapshot(List.of(existing)));
    service.handleSignalTick(existing, false);
    assertEquals(requestsAfterRemoval, recoveryRequests.get(), "收敛之后不再有新的恢复请求");
    assertEquals(0, existing.destroyCalls);
  }

  /**
   * 名字对不上时无法断定谁是孪生，不能销毁；这辆车反复触发全局重建时，限流之后只隔离本车、不再拖全网。
   *
   * <p>重建治不好这辆车（下面每轮都把现场重建回只含 existing 的 READY），它就会在下一次信号检查里再触发一遍。
   */
  @Test
  void repeatedEscalationFromOneAmbiguousTrainIsContainedAfterLimit() {
    RuntimeDispatchService service = newService(new DwellRegistry());
    FakeTrain existing = train("shared-owner", null);
    FakeTrain ambiguous = train("shared-owner", "some-other-owner");
    AtomicInteger recoveryRequests = new AtomicInteger();
    service.setStartupRecoveryRequestedListener(recoveryRequests::incrementAndGet);

    for (int i = 0; i < 10; i++) {
      assertTrue(service.rebuildOccupancySnapshot(List.of(existing)));
      service.handleSignalTick(ambiguous, false);
    }

    assertEquals(3, recoveryRequests.get(), debugMessages::toString);
    assertEquals(0, ambiguous.destroyCalls, "名字对不上的编组不能被当成孪生销毁");
    assertTrue(
        debugMessages.stream().anyMatch(m -> m.contains("SMART_STARTUP_ESCALATION_SUPPRESSED")),
        debugMessages::toString);
    // 限流后全局门保持 READY，其余列车不再被牵连。
    assertTrue(service.captureReadyStartupRecoveryEpoch().isPresent());
    assertTrue(ambiguous.hardStopCalls > 0, "被限流的车本身仍必须保持硬停");
  }

  /**
   * TrainCarts 名与 tag 里的 owner 名不一致（改名迁移被拒）时，水合记录按 owner 名存放；READY 后的校验必须认得。
   *
   * <p>修复前校验只按 TrainCarts 当前名查，永远查不到，每个 tick 都当成迟加载列车重新提交物理占用。
   */
  @Test
  void hydrationRecordedUnderHeldOwnerNameIsRecognisedAfterReady() {
    DwellRegistry dwellRegistry = new DwellRegistry();
    RuntimeDispatchService service = newService(dwellRegistry);
    // owner 名仍持有停站状态时改名迁移被延后，运行时继续用旧 owner 名。
    dwellRegistry.start("old-owner", 60);
    FakeTrain renamed = train("new-name", "old-owner");
    AtomicInteger recoveryRequests = new AtomicInteger();
    service.setStartupRecoveryRequestedListener(recoveryRequests::incrementAndGet);
    assertTrue(service.rebuildOccupancySnapshot(List.of(renamed)));
    debugMessages.clear();

    for (int i = 0; i < 5; i++) {
      service.handleSignalTick(renamed, false);
    }

    assertEquals(0, recoveryRequests.get(), debugMessages::toString);
    // 迟加载路径每次都会重新准备现场证据并把本车打回 startup-occupancy-hydrating 硬停；READY 之后不应再出现。
    assertFalse(
        debugMessages.stream().anyMatch(m -> m.contains("startup-occupancy-hydrating")),
        debugMessages::toString);
  }
}
