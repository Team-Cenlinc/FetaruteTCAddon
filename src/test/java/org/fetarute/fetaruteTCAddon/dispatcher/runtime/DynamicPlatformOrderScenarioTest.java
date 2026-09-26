package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * 会让站选台按物理先后：前车在咽喉等台时，身后的车不能订走唯一的空台。
 *
 * <h3>修复前的现场</h3>
 *
 * <p>五站会让走廊、四车挤在 A1–B1 之间（与 {@code DispatchObserverSideEffectTest} 同一现场）。tick 0 时 q2 占着 B1:1， q1 停在
 * B1 西咽喉，q0 还在 A1 站台。骨架按 q0→q3 的顺序评估，q0 先把唯一的空台 B1:2 订走；q1 从此"候选站台均被占用" （{@code
 * AUTHORIZATION_FAILURE}，blockers 为空），q0 又被 q1 挡在身后——300 tick 都解不开。预订不是 claim，前车停因里没有 blocker，打开
 * Smart 恢复层与健康监控也看不见这个环。
 *
 * <p>修复前约 40% 的 JVM 恰好走另一条路：q3 的身后保护保留被错放在它没走过的 B1:2 上，q0 在 tick 0 也订不到台。图遍历确定化之后每个 JVM
 * 都走进了互卡那一条，所以这个用例现在能稳定复现缺陷。
 */
class DynamicPlatformOrderScenarioTest {

  private static final int TICKS = 200;

  @Test
  void trainAtTheThroatGetsTheLastFreePlatformInsteadOfTheTrainBehindIt() {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("A1", "B1", "C1", "D1", "E1"));
    List<NodeId> path = topology.physicalPath();
    DispatchScenarioHarness harness =
        DispatchScenarioHarness.builder()
            .topology(topology)
            .train("q0", "shared-route", path, topology.stations(), 0)
            .train("q1", "shared-route", path, topology.stations(), 1)
            .train("q2", "shared-route", path, topology.stations(), 2)
            .train("q3", "shared-route", path, topology.stations(), 3)
            .build();

    harness.runTicks(TICKS);

    // 反空绿：规则必须真的出过手；否则 q1 能动可能只是别的原因把 B1:2 腾给了它。
    assertTrue(
        harness.diagnosticCount("DYNAMIC_PLATFORM_ORDER_WITHHELD") > 0,
        () -> "物理先后规则一次都没触发:\n" + harness.describeState());
    assertEquals(
        NodeId.of("OP:S:B1:2"),
        harness.positionOf("q1"),
        () -> "咽喉上的前车没有拿到唯一的空台:\n" + harness.describeState());
    assertEquals(
        NodeId.of("OP:S:A1:1"),
        harness.positionOf("q0"),
        () -> "身后的车应当留在原地正当等台:\n" + harness.describeState());
    harness.assertNoViolationsOf("I1", "I2", "I3");
  }
}
