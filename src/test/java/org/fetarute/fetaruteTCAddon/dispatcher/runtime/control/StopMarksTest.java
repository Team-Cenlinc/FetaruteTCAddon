package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  /** 按给定连接组成的轨道。 */
  private static final class Track implements RailBlockAccess {
    private final Map<RailBlockPos, Set<RailBlockPos>> links = new HashMap<>();

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

    List<StopMarks.Mark> found =
        StopMarks.scan(
            track,
            pos(0),
            StopMarks.SEARCH_BLOCKS,
            otherNodes::contains,
            p -> marks.getOrDefault(p, List.of()));

    assertEquals(
        Set.of(pos(10), pos(-10)),
        Set.copyOf(found.stream().map(StopMarks.Mark::rail).toList()),
        "x=30 在道岔外，x=-30 在别的节点外");
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
