package org.fetarute.fetaruteTCAddon.signaller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 从大区剩下的部分切一片给新来的人：从端头单元起逐个加单元，剩下的要仍连成一片，取工作量最接近目标的一片。 */
class ZoneSplitterTest {

  /** 一条直线上的单元，工作量依次给出，位置按 x 排开。 */
  private static List<ZoneSplitter.Unit> line(double... loads) {
    List<ZoneSplitter.Unit> units = new ArrayList<>();
    for (int i = 0; i < loads.length; i++) {
      units.add(new ZoneSplitter.Unit(String.valueOf((char) ('A' + i)), loads[i], i * 100, 0));
    }
    return units;
  }

  private static List<int[]> chain(int count) {
    List<int[]> links = new ArrayList<>();
    for (int i = 1; i < count; i++) {
      links.add(new int[] {i - 1, i});
    }
    return links;
  }

  /** 设计稿的例子：A–B–C–D–E 工作量 10、30、10、20、30。第二个人目标 50，两端都正好凑到 50，取单元少的 E、D。 */
  @Test
  void theSecondPersonGetsTheEndClosestToHalf() {
    List<ZoneSplitter.Unit> units = line(10, 30, 10, 20, 30);

    Optional<Set<Integer>> slice =
        ZoneSplitter.split(units, chain(5), Set.of(0, 1, 2, 3, 4), 2, 0, 0);

    assertEquals(Optional.of(Set.of(3, 4)), slice);
  }

  /** 第三个人目标 33：只能从剩下的 A–B–C 里切，两端都是 40，平局取靠前的端头，分 A、B，大区那位留下 C。 */
  @Test
  void theThirdPersonCutsFromWhatIsLeft() {
    List<ZoneSplitter.Unit> units = line(10, 30, 10, 20, 30);

    Optional<Set<Integer>> slice = ZoneSplitter.split(units, chain(5), Set.of(0, 1, 2), 3, 0, 0);

    assertEquals(Optional.of(Set.of(0, 1)), slice);
  }

  /** 大区中间有一片已有人管（C），剩下的本就分成两段：照样能切，只是不能再把哪一段切断。 */
  @Test
  void aRemainderAlreadyInTwoPiecesCanStillBeCut() {
    List<ZoneSplitter.Unit> units = line(10, 10, 10, 10, 10);

    Optional<Set<Integer>> slice = ZoneSplitter.split(units, chain(5), Set.of(0, 1, 3, 4), 3, 0, 0);

    assertEquals(Optional.of(Set.of(0, 1)), slice);
  }

  /** 剩下的不足两个单元切不出来；剩下的至少留一个单元给大区那位。 */
  @Test
  void aSliceAlwaysLeavesSomethingBehind() {
    List<ZoneSplitter.Unit> units = line(10, 90);

    assertEquals(Optional.empty(), ZoneSplitter.split(units, chain(2), Set.of(1), 2, 0, 0));
    assertEquals(
        Optional.of(Set.of(0)),
        ZoneSplitter.split(units, chain(2), Set.of(0, 1), 2, 0, 0),
        "两个单元只切一个；两端离目标一样近，平局取靠前的端头");
  }

  /** 支线：会把剩下的部分切断的单元跳过。A–B–C 主线、B–D 支线，目标 70：从哪个端头起，加上岔点 B 都会把剩下的切断，只能各切一个；A、C 最接近，平局取靠前的 A。 */
  @Test
  void aUnitThatWouldSplitTheRestIsSkipped() {
    List<ZoneSplitter.Unit> units =
        List.of(
            new ZoneSplitter.Unit("A", 40, 0, 0),
            new ZoneSplitter.Unit("B", 40, 100, 0),
            new ZoneSplitter.Unit("C", 40, 200, 0),
            new ZoneSplitter.Unit("D", 20, 100, 100));
    List<int[]> links = List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {1, 3});

    assertEquals(
        Optional.of(Set.of(0)), ZoneSplitter.split(units, links, Set.of(0, 1, 2, 3), 2, 0, 0));
  }

  /** 环线没有端头：从离大区那位的工位最远的单元起切。 */
  @Test
  void aRingStartsFarthestFromTheParentDesk() {
    List<ZoneSplitter.Unit> units =
        List.of(
            new ZoneSplitter.Unit("A", 25, 0, 0),
            new ZoneSplitter.Unit("B", 25, 100, 0),
            new ZoneSplitter.Unit("C", 25, 100, 100),
            new ZoneSplitter.Unit("D", 25, 0, 100));
    List<int[]> links =
        List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {2, 3}, new int[] {3, 0});

    Set<Integer> slice =
        ZoneSplitter.split(units, links, Set.of(0, 1, 2, 3), 2, 0, 0).orElseThrow();

    assertTrue(slice.contains(2), "工位在 A 旁边，离它最远的是 C");
    assertEquals(2, slice.size());
  }
}
