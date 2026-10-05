package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 按班次份额排车型：平滑、长期收敛、只按实际跑的车记账、方案变了从零记。 */
class ConsistSelectorTest {

  private static final List<ConsistSelector.Weighted> THREE_TO_ONE =
      List.of(new ConsistSelector.Weighted("a", 3), new ConsistSelector.Weighted("b", 1));

  /** 每班都按排在最前的车型跑。 */
  private static List<String> run(ConsistSelector selector, UUID route, int trips) {
    List<String> sequence = new ArrayList<>();
    for (int i = 0; i < trips; i++) {
      String next = selector.rank(route, "v1", THREE_TO_ONE).get(0);
      selector.record(route, "v1", next);
      sequence.add(next);
    }
    return sequence;
  }

  @Test
  void threeToOneGivesASmoothRepeatingSequence() {
    ConsistSelector selector = new ConsistSelector();
    UUID route = UUID.randomUUID();

    assertEquals(List.of("a", "a", "b", "a", "a", "a", "b", "a"), run(selector, route, 8));
    assertEquals(Map.of("a", 6L, "b", 2L), selector.counts(route));
  }

  @Test
  void substitutedTripsAreMadeUpByLaterTickets() {
    ConsistSelector selector = new ConsistSelector();
    UUID route = UUID.randomUUID();
    // 到站的车只有 a：两班本该轮到 b 的都由 a 顶上
    for (int i = 0; i < 4; i++) {
      selector.record(route, "v1", "a");
    }
    assertEquals(List.of("b", "a"), selector.rank(route, "v1", THREE_TO_ONE));
    assertEquals(List.of("b", "a", "b", "a"), run(selector, route, 4), "欠下的两班 b 在接下来几班里补上");
    assertEquals(Map.of("a", 6L, "b", 2L), selector.counts(route));
  }

  @Test
  void tiesFollowPlanOrderAndNewFingerprintStartsOver() {
    ConsistSelector selector = new ConsistSelector();
    UUID route = UUID.randomUUID();
    List<ConsistSelector.Weighted> even =
        List.of(new ConsistSelector.Weighted("x", 1), new ConsistSelector.Weighted("y", 1));
    assertEquals(List.of("x", "y"), selector.rank(route, "v1", even));
    selector.record(route, "v1", "x");
    assertEquals(List.of("y", "x"), selector.rank(route, "v1", even));

    assertEquals(List.of("x", "y"), selector.rank(route, "v2", even), "方案改了，记账从零开始");
    assertEquals(Map.of(), selector.counts(route));
  }

  @Test
  void routesKeepSeparateLedgers() {
    ConsistSelector selector = new ConsistSelector();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    run(selector, first, 3);
    assertEquals(List.of("a", "b"), selector.rank(second, "v1", THREE_TO_ONE));
  }
}
