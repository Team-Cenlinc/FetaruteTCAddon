package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrainOverlapDetector 车身重叠")
class TrainOverlapDetectorTest {

  private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-000000000002");

  /** 车厢长 10 格、车厢间距 0.5 格（与实服 MT 编组同一量级）。 */
  private static final double CART_LENGTH = 10.0;

  private static final double CART_PITCH = 10.5;

  /** 沿 x 轴一段直线轨道上 [fromX, toX] 的方块。 */
  private static Set<RailFootprintCell> straight(int fromX, int toX, int z) {
    Set<RailFootprintCell> cells = new TreeSet<>();
    for (int x = fromX; x <= toX; x++) {
      cells.add(new RailFootprintCell(x, 64, z));
    }
    return cells;
  }

  /** 一列三节车：车尾那节中心在 {@code tailCenterX}，车头朝 +x。 */
  private static List<TrainOverlapDetector.Cart> carts(double tailCenterX, int z) {
    List<TrainOverlapDetector.Cart> carts = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      carts.add(
          TrainOverlapDetector.Cart.at(
              tailCenterX + i * CART_PITCH, 64.5, z + 0.5, CART_LENGTH / 2.0));
    }
    return carts;
  }

  /** 车身两端各带 1 格余量的足迹，与车厢摆放对应。 */
  private static TrainOverlapDetector.Sample train(String name, double tailCenterX) {
    return train(name, WORLD, tailCenterX, 0);
  }

  private static TrainOverlapDetector.Sample train(
      String name, UUID world, double tailCenterX, int z) {
    int from = (int) Math.floor(tailCenterX - CART_LENGTH / 2.0) - 1;
    int to = (int) Math.ceil(tailCenterX + 2 * CART_PITCH + CART_LENGTH / 2.0) + 1;
    return new TrainOverlapDetector.Sample(
        name, world, straight(from, to, z), carts(tailCenterX, z));
  }

  @Test
  @DisplayName("两车居中停在同一站台：叠成一团")
  void stackedAtTheSameStopMark() {
    List<TrainOverlapDetector.Pair> pairs =
        TrainOverlapDetector.overlappingPairs(List.of(train("B-2", 0.0), train("A-1", 0.0)));

    assertEquals(List.of(new TrainOverlapDetector.Pair("A-1", "B-2", 10)), pairs);
  }

  @Test
  @DisplayName("后车冲进前车车尾 4 格：算重叠")
  void partialOverrunCounts() {
    double leaderTail = 100.0;
    double followerTail = leaderTail - 2 * CART_PITCH - CART_LENGTH + 4.0;

    List<TrainOverlapDetector.Pair> pairs =
        TrainOverlapDetector.overlappingPairs(
            List.of(train("A-1", leaderTail), train("B-2", followerTail)));

    assertEquals(1, pairs.size());
    assertEquals(4, pairs.get(0).depthBlocks());
  }

  @Test
  @DisplayName("首尾贴住、足迹余量互相压着：不算重叠")
  void tailToHeadTouchingIsNotOverlap() {
    double leaderTail = 100.0;
    double followerTail = leaderTail - 2 * CART_PITCH - CART_LENGTH;

    assertTrue(
        TrainOverlapDetector.overlappingPairs(
                List.of(train("A-1", leaderTail), train("B-2", followerTail)))
            .isEmpty());
  }

  @Test
  @DisplayName("跨长路径的足迹把后车也盖住了，但车厢没嵌进去：不算重叠")
  void longPathFootprintAloneIsNotOverlap() {
    double leaderTail = 100.0;
    double followerTail = leaderTail - 2 * CART_PITCH - CART_LENGTH - 3.0;
    TrainOverlapDetector.Sample follower = train("B-2", followerTail);
    // 前车跨在两段长 TCCoasters 路径上：足迹保留了整段路径，往后一直盖到后车车身。
    TrainOverlapDetector.Sample leader =
        new TrainOverlapDetector.Sample("A-1", WORLD, straight(20, 140, 0), carts(leaderTail, 0));

    assertTrue(TrainOverlapDetector.overlappingPairs(List.of(leader, follower)).isEmpty());
  }

  @Test
  @DisplayName("并列股道并排停：不共用轨道方块，不算重叠")
  void parallelTracksDoNotOverlap() {
    assertTrue(
        TrainOverlapDetector.overlappingPairs(
                List.of(train("A-1", WORLD, 0.0, 0), train("B-2", WORLD, 0.0, 4)))
            .isEmpty());
  }

  @Test
  @DisplayName("不同世界的同一坐标不是重叠")
  void differentWorldsDoNotOverlap() {
    assertTrue(
        TrainOverlapDetector.overlappingPairs(
                List.of(train("A-1", WORLD, 0.0, 0), train("B-2", OTHER_WORLD, 0.0, 0)))
            .isEmpty());
  }

  @Test
  @DisplayName("两组同名的车叠在一起：照样报出来，标为同名")
  void sameNameGroupsAreReported() {
    List<TrainOverlapDetector.Pair> pairs =
        TrainOverlapDetector.overlappingPairs(List.of(train("A-1", 0.0), train("a-1", 0.0)));

    assertEquals(1, pairs.size());
    assertTrue(pairs.get(0).sameName());
  }

  @Test
  @DisplayName("并列股道并排停、足迹顺着渡线漏到邻线一格：嵌进去的车厢不在对方轨道上，不算重叠")
  void footprintSpillThroughCrossoverIsNotOverlap() {
    TrainOverlapDetector.Sample onTrack0 = train("A-1", WORLD, 0.0, 0);
    Set<RailFootprintCell> spilled = new TreeSet<>(straight(-6, 27, 4));
    // B 的余量顺着渡线道岔走到了 A 那条股道上的一格。
    spilled.add(new RailFootprintCell(-6, 64, 0));
    TrainOverlapDetector.Sample onTrack4 =
        new TrainOverlapDetector.Sample("B-2", WORLD, spilled, carts(0.0, 4));

    assertTrue(TrainOverlapDetector.mayOverlap(onTrack0.carts(), onTrack4.carts()), "粗筛放过并排停的车");
    assertTrue(TrainOverlapDetector.overlappingPairs(List.of(onTrack0, onTrack4)).isEmpty());
  }

  @Test
  @DisplayName("粗筛：叠上的放过，首尾相隔的拦下")
  void prefilterKeepsStackedAndDropsSpacedTrains() {
    assertTrue(TrainOverlapDetector.mayOverlap(carts(0.0, 0), carts(0.0, 0)));
    assertTrue(
        TrainOverlapDetector.mayOverlap(
            carts(100.0, 0), carts(100.0 - 2 * CART_PITCH - CART_LENGTH + 4.0, 0)));
    assertFalse(
        TrainOverlapDetector.mayOverlap(
            carts(100.0, 0), carts(100.0 - 2 * CART_PITCH - CART_LENGTH, 0)));
    assertFalse(TrainOverlapDetector.mayOverlap(carts(0.0, 0), carts(200.0, 0)));
  }

  @Test
  @DisplayName("三车叠在一处：三对都报，按配对键排序")
  void threeStackedTrainsYieldEveryPair() {
    List<TrainOverlapDetector.Pair> pairs =
        TrainOverlapDetector.overlappingPairs(
            List.of(train("C-3", 0.0), train("A-1", 0.0), train("B-2", 0.0)));

    assertEquals(
        List.of(
            new TrainOverlapDetector.Pair("A-1", "B-2", 10),
            new TrainOverlapDetector.Pair("A-1", "C-3", 10),
            new TrainOverlapDetector.Pair("B-2", "C-3", 10)),
        pairs);
  }

  @Test
  @DisplayName("嵌入深度刚好到门槛就算")
  void depthThresholdIsInclusive() {
    TrainOverlapDetector.Cart a = TrainOverlapDetector.Cart.at(0.0, 0.0, 0.0, 5.0);
    TrainOverlapDetector.Cart b =
        TrainOverlapDetector.Cart.at(10.0 - TrainOverlapDetector.MIN_DEPTH_BLOCKS, 0.0, 0.0, 5.0);

    assertEquals(
        TrainOverlapDetector.MIN_DEPTH_BLOCKS,
        TrainOverlapDetector.deepestInterpenetration(List.of(a), List.of(b)),
        1e-9);
  }
}
