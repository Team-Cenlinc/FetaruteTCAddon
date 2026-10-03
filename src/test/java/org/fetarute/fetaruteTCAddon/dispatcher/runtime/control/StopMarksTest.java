package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("停车位置标：沿股道收集与按节数选取")
class StopMarksTest {

  /** 按给定连接组成的轨道；每一段长 {@code segment} 格（TCCoasters 的一段可能很长）。 */
  private static final class Track implements RailBlockAccess {
    private final Map<RailBlockPos, Set<RailBlockPos>> links = new HashMap<>();
    private double segment = 1.0;

    @Override
    public double stepCost(RailBlockPos from, RailBlockPos to) {
      return segment;
    }

    void connect(RailBlockPos a, RailBlockPos b) {
      links.computeIfAbsent(a, k -> new HashSet<>()).add(b);
      links.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }

    /** x 从 from 到 to 的一段直线轨道（z = 0）。 */
    void straight(int from, int to) {
      for (int x = from; x < to; x++) {
        connect(pos(x), pos(x + 1));
      }
    }

    @Override
    public boolean isRail(RailBlockPos pos) {
      return links.containsKey(pos);
    }

    @Override
    public Set<RailBlockPos> neighbors(RailBlockPos pos) {
      return links.getOrDefault(pos, Set.of());
    }
  }

  private static RailBlockPos pos(int x) {
    return new RailBlockPos(x, 64, 0);
  }

  private static StopMarks.Mark mark(int x, String carriages) {
    return new StopMarks.Mark(
        pos(x),
        new Vector(x + 0.5, 64.0, 0.5),
        StopMarkSign.parse("carriage:" + carriages, "").orElseThrow());
  }

  @Test
  @DisplayName("沿同一股道往两边找；遇到道岔或别的节点牌子就停")
  void scanStopsAtSwitchesAndOtherNodes() {
    Track track = new Track();
    track.straight(-40, 40);
    // x = 20 处分出一条岔线：道岔之后的标志不算。
    track.connect(pos(20), new RailBlockPos(21, 64, 1));
    Map<RailBlockPos, List<StopMarks.Mark>> marks =
        Map.of(
            pos(10), List.of(mark(10, "4")),
            pos(30), List.of(mark(30, "6")),
            pos(-10), List.of(mark(-10, "4")),
            pos(-30), List.of(mark(-30, "8")));
    Set<RailBlockPos> otherNodes = Set.of(pos(-20));

    StopMarks.Scan scan =
        StopMarks.scan(
            track,
            pos(0),
            StopMarks.SEARCH_BLOCKS,
            p -> new StopMarks.RailSigns(otherNodes.contains(p), marks.getOrDefault(p, List.of())),
            p -> true);

    assertEquals(
        Set.of(pos(10), pos(-10)),
        Set.copyOf(scan.marks().stream().map(StopMarks.Mark::rail).toList()),
        "x=30 在道岔外，x=-30 在别的节点外");
    assertTrue(scan.complete());
  }

  @Test
  @DisplayName("沿途有区块没加载时，结果标为不完整")
  void incompleteWhenChunksAreNotLoaded() {
    Track track = new Track();
    track.straight(-10, 40);
    StopMarks.Scan scan =
        StopMarks.scan(
            track, pos(0), StopMarks.SEARCH_BLOCKS, p -> StopMarks.RailSigns.NONE, p -> p.x() < 32);
    assertFalse(scan.complete());
  }

  @Test
  @DisplayName("按轨道实际长度限距：400 格长的站台两端都找得到；长段轨道（TCC）按长度而不是段数算")
  void distanceLimitFollowsTrackLength() {
    Track track = new Track();
    track.straight(-260, 260);
    Map<RailBlockPos, List<StopMarks.Mark>> marks =
        Map.of(pos(200), List.of(mark(200, "8")), pos(-200), List.of(mark(-200, "8")));
    StopMarks.Scan longStation =
        StopMarks.scan(
            track,
            pos(0),
            StopMarks.SEARCH_BLOCKS,
            p -> new StopMarks.RailSigns(false, marks.getOrDefault(p, List.of())),
            p -> true);
    assertEquals(2, longStation.marks().size());

    track.segment = 10.0;
    StopMarks.Scan coaster =
        StopMarks.scan(
            track,
            pos(0),
            StopMarks.SEARCH_BLOCKS,
            p -> new StopMarks.RailSigns(false, marks.getOrDefault(p, List.of())),
            p -> true);
    assertTrue(coaster.marks().isEmpty(), "200 段 × 10 格 = 2000 格，超出搜索距离");
  }

  @Test
  @DisplayName("车站轨道走向按列车走向定正反；弯道上的列车也按站台轨道判前后")
  void orientAlongTheStationTrack() {
    Vector east = new Vector(1, 0, 0);
    assertEquals(east, StopMarks.orient(new Vector(-1, 0, 0), new Vector(0.6, 0, 0.8)));
    assertEquals(east, StopMarks.orient(east, new Vector(0.3, 0, -0.9)));
    assertEquals(new Vector(0, 0, 1), StopMarks.orient(null, new Vector(0, 0, 1)));

    // 列车此刻朝东北偏北走，西侧标志按列车走向投影会被当成前方；按站台轨道（东西向）判就不会。
    Vector station = new Vector(0.5, 64.0, 0.5);
    List<StopMarks.Mark> marks = List.of(mark(-12, "4"), mark(12, "4"));
    Vector travel = new Vector(-0.3, 0, 1.0);
    assertEquals(
        pos(-12),
        StopMarks.select(marks, station, travel, 4).orElseThrow().mark().rail(),
        "直接用列车走向会选错");
    assertEquals(
        pos(12),
        StopMarks.select(marks, station, StopMarks.orient(east, new Vector(0.2, 0, 1.0)), 4)
            .orElseThrow()
            .mark()
            .rail());
  }

  @Test
  @DisplayName("自动运行开往标志：保持进站速度，按常用制动距离开始制动；距离不够时直接制动")
  void approachPlan() {
    // 进站 0.5 格/tick（10 格/秒），常用制动 1 格/秒² = 0.0025 格/tick²：制动距离 50 格。
    StopMarks.Approach far = StopMarks.approach(80.0, 0.5, 0.5, 0.0025);
    assertEquals(30.0, far.holdBlocks(), 1.0e-9);
    assertEquals(0.5, far.holdBpt(), 1.0e-9);
    assertEquals(50.0, far.brakeBlocks(), 1.0e-9);
    assertEquals(60 + 200, far.ticks());

    StopMarks.Approach near = StopMarks.approach(20.0, 0.5, 0.5, 0.0025);
    assertEquals(0.0, near.holdBlocks(), 1.0e-9);
    assertEquals(20.0, near.brakeBlocks(), 1.0e-9);

    StopMarks.Approach slow = StopMarks.approach(20.0, 0.01, 0.5, 0.05);
    assertEquals(StopMarks.MIN_HOLD_BPT, slow.holdBpt(), 1.0e-9, "进站已经很慢时不至于一路蠕行");
    assertEquals(19.9, slow.holdBlocks(), 1.0e-9);
  }

  @Test
  @DisplayName("只认车站牌子前方、节数对得上的；写得越专门越优先，再取近的")
  void selectByCarriagesAndDirection() {
    Vector station = new Vector(0.5, 64.0, 0.5);
    List<StopMarks.Mark> marks =
        List.of(mark(12, "4"), mark(16, "*"), mark(-12, "4"), mark(20, "3-6"));

    StopMarks.Selected east =
        StopMarks.select(marks, station, new Vector(1, 0, 0), 4).orElseThrow();
    assertEquals(pos(12), east.mark().rail());
    assertEquals(12.0, east.aheadBlocks(), 1.0e-9);

    StopMarks.Selected west =
        StopMarks.select(marks, station, new Vector(-1, 0, 0), 4).orElseThrow();
    assertEquals(pos(-12), west.mark().rail(), "往西开的车只认西边的标志");

    assertEquals(
        pos(20),
        StopMarks.select(marks, station, new Vector(1, 0, 0), 5).orElseThrow().mark().rail(),
        "3-6 比 * 更专门");
    assertEquals(
        pos(16),
        StopMarks.select(marks, station, new Vector(1, 0, 0), 8).orElseThrow().mark().rail());
    assertTrue(StopMarks.select(marks, station, new Vector(-1, 0, 0), 8).isEmpty());
  }
}
