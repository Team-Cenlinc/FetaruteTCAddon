package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * weight 是目标服务比例，不是抽签概率。
 *
 * <p>这一组用例分成两半，缺一不可：
 *
 * <ul>
 *   <li><b>长期比例</b>：足够多的班次之后，各线份额必须收敛到权重比。
 *   <li><b>短期公平</b>：任何一个短窗口内都不能出现某条线连开一串、别的线一趟没有。 只测第一项的话，一个每次独立抽签的实现也能通过——而那正是要排除的实现。
 * </ul>
 */
class WeightedTripAllocatorTest {

  /** 5:3:2 在 100 个时隙上必须精确收敛到 50/30/20。 */
  @Test
  void longRunSharesConvergeToWeightRatio() {
    List<WeightedTripAllocator.Candidate> candidates =
        List.of(
            new WeightedTripAllocator.Candidate("DS", 2),
            new WeightedTripAllocator.Candidate("MT", 3),
            new WeightedTripAllocator.Candidate("WS", 5));

    Map<String, Integer> counts = counts(candidates, 100);

    assertEquals(50, counts.get("WS"));
    assertEquals(30, counts.get("MT"));
    assertEquals(20, counts.get("DS"));
  }

  /** 1:1、1:3、1:1:1 都要精确。 */
  @Test
  void otherRatiosAreExact() {
    assertEquals(
        Map.of("A", 20, "B", 20),
        counts(
            List.of(
                new WeightedTripAllocator.Candidate("A", 1),
                new WeightedTripAllocator.Candidate("B", 1)),
            40));
    assertEquals(
        Map.of("A", 10, "B", 30),
        counts(
            List.of(
                new WeightedTripAllocator.Candidate("A", 1),
                new WeightedTripAllocator.Candidate("B", 3)),
            40));
    assertEquals(
        Map.of("A", 10, "B", 10, "C", 10),
        counts(
            List.of(
                new WeightedTripAllocator.Candidate("A", 1),
                new WeightedTripAllocator.Candidate("B", 1),
                new WeightedTripAllocator.Candidate("C", 1)),
            30));
  }

  /**
   * 短窗口内不许 starvation。
   *
   * <p>5:3:2 下，任意连续 10 个班次里三条线都必须至少各出现一次。每次独立抽签的实现有相当大的概率 连出 10 个 WS，因此这条断言恰好能把 lottery 实现挡在外面。
   */
  @Test
  void noStarvationInsideAnyShortWindow() {
    List<WeightedTripAllocator.Candidate> candidates =
        List.of(
            new WeightedTripAllocator.Candidate("DS", 2),
            new WeightedTripAllocator.Candidate("MT", 3),
            new WeightedTripAllocator.Candidate("WS", 5));
    List<String> order = order(candidates, 100);

    for (int start = 0; start + 10 <= order.size(); start++) {
      List<String> window = order.subList(start, start + 10);
      for (WeightedTripAllocator.Candidate candidate : candidates) {
        assertTrue(
            window.contains(candidate.key()), () -> "窗口 " + window + " 里缺少 " + candidate.key());
      }
    }
  }

  /** 同一条线不允许连开三班（5:3:2 下平滑加权轮询的最长连击是 2）。 */
  @Test
  void doesNotBurstTheSameRoute() {
    List<String> order =
        order(
            List.of(
                new WeightedTripAllocator.Candidate("DS", 2),
                new WeightedTripAllocator.Candidate("MT", 3),
                new WeightedTripAllocator.Candidate("WS", 5)),
            100);

    int run = 1;
    for (int i = 1; i < order.size(); i++) {
      run = order.get(i).equals(order.get(i - 1)) ? run + 1 : 1;
      assertTrue(run <= 2, () -> "出现了连续三班同线：" + order);
    }
  }

  /** 同样输入两次分配结果必须逐项一致。 */
  @Test
  void allocationIsDeterministic() {
    List<WeightedTripAllocator.Candidate> candidates =
        List.of(
            new WeightedTripAllocator.Candidate("A", 5),
            new WeightedTripAllocator.Candidate("B", 3),
            new WeightedTripAllocator.Candidate("C", 2));

    assertEquals(order(candidates, 50), order(candidates, 50));
  }

  /** tie-break 只看候选顺序，不看内存布局：相同权重时按调用方给的稳定顺序。 */
  @Test
  void tieBreakFollowsCandidateOrder() {
    List<String> order =
        order(
            List.of(
                new WeightedTripAllocator.Candidate("A", 1),
                new WeightedTripAllocator.Candidate("B", 1)),
            4);

    assertEquals(List.of("A", "B", "A", "B"), order);
  }

  /**
   * 暂时不可安排的线路不丢份额，约束解除后能追回。
   *
   * <p>前 20 个时隙 B 不可用：这段时间全部给 A，但 B 的 deficit 一直在涨；第 20 个时隙之后 B 应当连续被选中直到追平，而不是"错过的就算了"。
   */
  @Test
  void temporarilyInfeasibleRouteCatchesUpAfterwards() {
    List<WeightedTripAllocator.Candidate> candidates =
        List.of(
            new WeightedTripAllocator.Candidate("A", 1),
            new WeightedTripAllocator.Candidate("B", 1));

    List<WeightedTripAllocator.Allocation> allocations =
        WeightedTripAllocator.allocate(
            candidates, 60, (slot, index, assigned) -> index != 1 || slot >= 20);

    List<String> order = new ArrayList<>();
    for (WeightedTripAllocator.Allocation allocation : allocations) {
      order.add(candidates.get(allocation.candidateIndex()).key());
    }
    assertFalse(order.subList(0, 20).contains("B"), "前 20 个时隙 B 不可安排");

    // 约束解除后的第一批必须连续给 B，把欠的份额追回来。
    List<String> justAfter = order.subList(20, 30);
    long bCount = justAfter.stream().filter("B"::equals).count();
    assertTrue(bCount >= 9, () -> "约束解除后应当连续补 B，实际：" + justAfter);

    // 到窗口末尾，两边份额应当已经接近持平。
    long totalA = order.stream().filter("A"::equals).count();
    long totalB = order.stream().filter("B"::equals).count();
    assertTrue(Math.abs(totalA - totalB) <= 2, () -> "最终份额应接近持平，实际 A=" + totalA + " B=" + totalB);
  }

  /** 全部不可安排时不硬塞班次，返回空。 */
  @Test
  void producesNothingWhenNothingIsFeasible() {
    List<WeightedTripAllocator.Candidate> candidates =
        List.of(new WeightedTripAllocator.Candidate("A", 1));

    assertTrue(
        WeightedTripAllocator.allocate(candidates, 10, (slot, index, assigned) -> false).isEmpty());
  }

  /** 比例报告同时给出目标与实际。 */
  @Test
  void reportsTargetAndAchievedShare() {
    List<WeightedTripAllocator.Candidate> candidates =
        List.of(
            new WeightedTripAllocator.Candidate("A", 3),
            new WeightedTripAllocator.Candidate("B", 1));
    List<WeightedTripAllocator.Allocation> allocations =
        WeightedTripAllocator.allocate(candidates, 40, WeightedTripAllocator.unconstrained());

    List<WeightedTripAllocator.ShareReport> reports =
        WeightedTripAllocator.report(candidates, allocations);

    WeightedTripAllocator.ShareReport a =
        reports.stream().filter(r -> r.key().equals("A")).findFirst().orElseThrow();
    assertEquals(0.75D, a.targetShare(), 1e-9);
    assertEquals(0.75D, a.achievedShare(), 1e-9);
    assertEquals(30, a.assignedTrips());
  }

  private static List<String> order(List<WeightedTripAllocator.Candidate> candidates, int slots) {
    List<WeightedTripAllocator.Allocation> allocations =
        WeightedTripAllocator.allocate(candidates, slots, WeightedTripAllocator.unconstrained());
    List<String> out = new ArrayList<>(allocations.size());
    for (WeightedTripAllocator.Allocation allocation : allocations) {
      out.add(candidates.get(allocation.candidateIndex()).key());
    }
    return out;
  }

  private static Map<String, Integer> counts(
      List<WeightedTripAllocator.Candidate> candidates, int slots) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String key : order(candidates, slots)) {
      counts.merge(key, 1, Integer::sum);
    }
    return counts;
  }
}
