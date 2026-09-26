package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

/**
 * 从会让站 2 道出站的列车，身后保护必须留在 2 道。
 *
 * <h3>修复前的现场</h3>
 *
 * <p>车头驶过出站道岔 {@code SWITCHER:B1:E} 的那一 tick，运行时把当前路径点（2 道）改写成车头节点，身后保护只能按"上一站 → 车头"的
 * 最短路重建：两股道等长，平局落在 1 道。于是 2 道上的 NODE/EDGE 当场全部释放，1 道——列车从没走过的那条——被 PROTECTIVE_RETAIN 硬占，一直到车头抵达下一站。
 *
 * <p>普通轨道上车尾只靠身后保护（实测覆盖层只约束稀疏联锁 Zone 的释放），所以这不只是 1 道白白被占：实际股道上车尾之后的配置余量也丢了。
 */
class RearGuardActualTrackScenarioTest {

  private static final NodeId EXIT_SWITCH = NodeId.of("SWITCHER:B1:E");
  private static final NodeId NEXT_ENTRY_SWITCH = NodeId.of("SWITCHER:C1:W");
  private static final int MAX_TICKS = 600;

  @Test
  void rearGuardStaysOnTrackTwoAfterTheHeadPassesTheExitSwitch() {
    DispatchScenarioHarness.Topology topology = trackTwoOnlyAtB1();
    DispatchScenarioHarness harness =
        DispatchScenarioHarness.builder()
            .topology(topology)
            .train("m", "shared-route", topology.physicalPath(), topology.stations(), 0)
            .build();

    int ticksPastExit = 0;
    List<String> wrong = new ArrayList<>();
    for (int tick = 0; tick < MAX_TICKS; tick++) {
      harness.runTicks(1);
      NodeId head = harness.positionOf("m");
      if (!head.equals(EXIT_SWITCH) && !head.equals(NEXT_ENTRY_SWITCH)) {
        continue;
      }
      ticksPastExit++;
      Set<OccupancyResource> held =
          new java.util.LinkedHashSet<>(
              harness.sortedClaims().stream()
                  .filter(claim -> claim.trainName().equals("m"))
                  .map(claim -> claim.resource())
                  .toList());
      boolean onTrackTwo = held.contains(OccupancyResource.forNode(NodeId.of("OP:S:B1:2")));
      boolean onTrackOne = held.stream().anyMatch(r -> r.key().contains("OP:S:B1:1"));
      if (!onTrackTwo || onTrackOne) {
        wrong.add("tick " + tick + " head=" + head.value() + " held=" + held);
      }
    }

    // 反空绿：列车必须真的驶过了出站道岔，否则下面的断言什么也没检查。
    assertTrue(ticksPastExit > 0, () -> "列车一直没有驶过出站道岔:\n" + harness.describeState());
    assertTrue(
        wrong.isEmpty(), () -> "身后保护离开了实际股道（" + wrong.size() + " 个 tick），首个:\n  " + wrong.get(0));
  }

  /** 与 loopCorridor 相同，只把 B1 的 DYNAMIC 范围收成 2 道，单车也必然走 2 道。 */
  private static DispatchScenarioHarness.Topology trackTwoOnlyAtB1() {
    DispatchScenarioHarness.Topology base =
        DispatchScenarioHarness.loopCorridor(List.of("A1", "B1", "C1", "D1", "E1"));
    Map<NodeId, String> specs = new LinkedHashMap<>(base.dynamicSpecByStation());
    specs.put(NodeId.of("OP:S:B1:1"), "DYNAMIC:OP:S:B1:[2:2]");
    return new DispatchScenarioHarness.Topology(
        base.nodes(), base.edges(), base.physicalPath(), base.stations(), Map.copyOf(specs));
  }
}
