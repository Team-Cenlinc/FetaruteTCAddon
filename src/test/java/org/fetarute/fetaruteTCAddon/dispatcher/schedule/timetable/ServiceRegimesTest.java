package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.Band;
import org.junit.jupiter.api.Test;

/** 分时段切制式与分段铺格。 */
class ServiceRegimesTest {

  private static final int START = 5 * 3600;

  @Test
  void noBandsMeansOneRegime() {
    List<ServiceRegimes.Regime> regimes =
        ServiceRegimes.of(Map.of("A", 300, "B", 600), Map.of(), START, 18 * 3600);
    assertEquals(1, regimes.size());
    assertEquals(Map.of("A", 300, "B", 600), regimes.get(0).intervals());
  }

  @Test
  void bandsCutTheWindowAndEqualNeighboursMerge() {
    // A 早高峰 07:00–09:00 每 180 秒，其余 300；B 全天 600。08:00 切一刀但两侧相同，合并。
    List<ServiceRegimes.Regime> regimes =
        ServiceRegimes.of(
            Map.of("A", 300, "B", 600),
            Map.of(
                "A",
                List.of(band("A", 7 * 3600, 8 * 3600, 180), band("A", 8 * 3600, 9 * 3600, 180))),
            START,
            18 * 3600);
    assertEquals(3, regimes.size());
    assertEquals(2 * 3600, regimes.get(1).from());
    assertEquals(4 * 3600, regimes.get(1).to());
    assertEquals(180, regimes.get(1).intervals().get("A"));
    assertEquals(600, regimes.get(1).intervals().get("B"));
    assertEquals(300, regimes.get(2).intervals().get("A"));
  }

  @Test
  void firstBandWinsWhenListedFirst() {
    // 限定线路的时段排在前面：重叠部分用它，其余用不限线路的。
    List<ServiceRegimes.Regime> regimes =
        ServiceRegimes.of(
            Map.of("L/A", 300),
            Map.of(
                "L/A",
                List.of(
                    new Band(Optional.of("L"), "A", 7 * 3600, 8 * 3600, 150),
                    band("A", 6 * 3600, 9 * 3600, 200))),
            START,
            18 * 3600);
    assertEquals(
        List.of(300, 200, 150, 200, 300),
        regimes.stream().map(regime -> regime.intervals().get("L/A")).toList());
  }

  @Test
  void segmentsDoNotBunchAtTheBoundary() {
    // 前段 300 秒相位 0，后段 180 秒相位 170：交界处第一班离上一段末班至少 180 秒。
    UUID route = UUID.nameUUIDFromBytes(new byte[] {1});
    ServiceGroupClassifier.Direction direction =
        new ServiceGroupClassifier.Direction(
            "X", "Y", List.of(new WeightedTripAllocator.Candidate("r", 1)), List.of(route));
    GroupGrid.DirectionGrid grid =
        GroupGrid.ofSegments(
            direction,
            List.of(
                new GroupGrid.Segment(0, 1000, 300, 0),
                new GroupGrid.Segment(1000, 2000, 180, 170)),
            WeightedTripAllocator.unconstrained());
    List<Integer> times = grid.slots().stream().map(GroupGrid.Slot::departureSeconds).toList();
    assertEquals(List.of(0, 300, 600, 900, 1250, 1430, 1610, 1790, 1970), times);
    for (int i = 1; i < times.size(); i++) {
      assertTrue(times.get(i) - times.get(i - 1) >= 180);
    }
  }

  private static Band band(String group, int from, int to, int interval) {
    return new Band(Optional.empty(), group, from, to, interval);
  }
}
