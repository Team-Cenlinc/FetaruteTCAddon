package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * 方块 → 图区间的反向索引：Phase 4 的前置条件。
 *
 * <p>要把自持尾部保护的回收从 CONFLICT 扩到 NODE/EDGE，必须能证明"车体此刻**不**压在该资源上"。 CONFLICT 是抽象互斥键，放了不会撞车；NODE/EDGE
 * 对应物理空间，车体还压着时释放就是 co-occupancy。
 *
 * <p>此前唯一的现场索引是 {@code zoneKeysForCell}，而 Zone 只存在于两条**互不相邻**区间的足迹重叠处 （{@link InterlockingZoneInfo}
 * 是 pair-zone），普通区间方块根本不在里面——所以那条路走不通。
 *
 * <p>本索引由逐边足迹 {@link RailEdgeFootprint} 直接反转而来，是全量的。
 */
class RailFootprintReverseIndexTest {

  private static final UUID WORLD =
      UUID.nameUUIDFromBytes("w".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  private static EdgeId edge(String from, String to) {
    return EdgeId.undirected(NodeId.of(from), NodeId.of(to));
  }

  private static RailEdgeFootprint footprint(RailFootprintCell... cells) {
    return new RailEdgeFootprint(RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, Set.of(cells));
  }

  @Test
  void cellsResolveBackToTheEdgesThatCoverThem() {
    RailFootprintCell shared = new RailFootprintCell(10, 64, 0);
    RailFootprintCell onlyA = new RailFootprintCell(11, 64, 0);
    RailFootprintCell onlyB = new RailFootprintCell(12, 64, 0);
    EdgeId a = edge("OP:S:ALFA:1", "OP:S:BRAVO:1");
    EdgeId b = edge("OP:S:CHARLIE:1", "OP:S:DELTA:1");

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            WORLD, Map.of(a, footprint(shared, onlyA), b, footprint(shared, onlyB)));

    assertTrue(index.cellCoverageAvailable());
    assertEquals(Set.of(a, b), index.edgesForCell(shared), "重叠方块必须同时解析出两条边");
    assertEquals(Set.of(a), index.edgesForCell(onlyA));
    assertEquals(Set.of(b), index.edgesForCell(onlyB));
    assertTrue(index.edgesForCell(new RailFootprintCell(99, 64, 0)).isEmpty(), "不在任何区间上的方块解析为空");
  }

  /**
   * 普通区间方块进不了 Zone 索引——这正是不能复用 zoneKeysForCell 的原因。
   *
   * <p>Zone 只在**互不相邻**区间的重叠处产生，所以单边独占的方块在 Zone 索引里查不到， 在反向索引里却必须查得到。
   */
  @Test
  void zoneIndexAloneCannotAnswerCoverage() {
    RailFootprintCell onlyA = new RailFootprintCell(11, 64, 0);
    EdgeId a = edge("OP:S:ALFA:1", "OP:S:BRAVO:1");

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(WORLD, Map.of(a, footprint(onlyA)));

    assertTrue(index.zoneKeysForCell(onlyA).isEmpty(), "单边独占方块不构成 Zone");
    assertEquals(Set.of(a), index.edgesForCell(onlyA), "但反向索引必须答得出来");
  }

  /**
   * 持久化快照重建时索引不可用——空集合这时表示"无从判断"，不是"确实没覆盖"。
   *
   * <p>两者必须能区分，否则就是"缺证据被当成证据"，而那正是本项目反复栽跟头的那一类缺陷。
   */
  @Test
  void snapshotRebuildMarksCoverageUnavailableInsteadOfPretendingEmpty() {
    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.fromZones(
            Set.of(), new RailInterlockingCoverage(0, 0, true), Map.of());

    assertFalse(index.cellCoverageAvailable(), "快照路径没有逐边足迹，必须显式报告索引不可用，让调用方 fail-closed");
    assertTrue(index.edgesForCell(new RailFootprintCell(1, 1, 1)).isEmpty());
  }
}
