package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingEdgeSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PendingRailGraphStoreTest {
  @TempDir Path directory;

  @Test
  void restartRestoresCandidateMetadataAndSparseInterlockingWithoutChangingItsBaseline()
      throws Exception {
    UUID world = UUID.randomUUID();
    NodeId a = NodeId.of("OP:S:A:1"), b = NodeId.of("OP:S:B:1");
    EdgeId edge = EdgeId.undirected(a, b);
    RailFootprintCell cell = new RailFootprintCell(2, 64, 3);
    List<RailNodeRecord> nodes =
        List.of(
            new RailNodeRecord(
                world,
                a,
                NodeType.STATION,
                0,
                64,
                0,
                Optional.of(a.value()),
                Optional.of(WaypointMetadata.station("OP", "A", 1))),
            new RailNodeRecord(
                world,
                b,
                NodeType.STATION,
                10,
                64,
                0,
                Optional.of(b.value()),
                Optional.of(WaypointMetadata.station("OP", "B", 1))));
    var graph =
        RailGraphService.buildGraphFromRecords(
            nodes, List.of(new RailEdgeRecord(world, edge, 10, 8.0, true, Set.of(cell))));
    Instant base = Instant.parse("2026-10-12T00:00:00.123456789Z");
    var result =
        new RailGraphBuildResult(
            graph,
            base.plusSeconds(60),
            RailGraphSignature.signatureForNodes(nodes),
            nodes,
            List.of(),
            List.of());
    new PendingRailGraphStore(directory)
        .save(world, new PendingRailGraphStore.Candidate(result, Optional.of(base), "old-nodes"));
    var restored = new PendingRailGraphStore(directory).load(world).orElseThrow();
    assertEquals(nodes, restored.result().nodes());
    assertEquals(result.builtAt(), restored.result().builtAt());
    assertTrue(restored.matchesBase(Optional.of(base), "old-nodes"));
    assertTrue(
        restored.matchesBase(Optional.of(Instant.ofEpochMilli(base.toEpochMilli())), "old-nodes"),
        "SQLite 重载后的毫秒精度仍是同一份基线");
    assertFalse(restored.matchesBase(Optional.of(base.plusSeconds(1)), "old-nodes"));
    assertFalse(restored.matchesBase(Optional.of(base), "edited-nodes"));
    // 激活时要原样写库：候选不带逐边足迹的话，写库会把库里的足迹清空，尾部保护从此保守到重新 build。
    var state = ((RailGraphInterlockingSupport) restored.result().graph()).interlockingState();
    assertTrue(state.cellCoverageAvailable(), "候选带回逐边足迹，cell 索引可用");
    assertEquals(Set.of(edge), state.edgesForCell(cell));
    assertEquals(Map.of(edge, Set.of(cell)), state.footprintCellsByEdge());
    assertTrue(new PendingRailGraphStore(directory).load(UUID.randomUUID()).isEmpty());
    new PendingRailGraphStore(directory).delete(world);
    assertTrue(new PendingRailGraphStore(directory).load(world).isEmpty());
  }

  /**
   * 新旧缓存都只恢复真实平交 Zone，普通轨道不建立全图反查索引。
   *
   * <p>固定世界标识包含普通轨道坐标的数字片段，确保缓存断言检查真实结构而不是随机元数据。
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void legacyCandidatesRestoreCrossingZonesWithoutOrdinaryRailCells(int version) throws Exception {
    UUID world = UUID.fromString("00000000-0000-4000-8000-000000000000");
    List<RailNodeRecord> nodes =
        java.util.stream.IntStream.range(0, 4)
            .mapToObj(
                i ->
                    new RailNodeRecord(
                        world,
                        NodeId.of("N" + i),
                        NodeType.WAYPOINT,
                        i * 10,
                        64,
                        0,
                        Optional.empty(),
                        Optional.empty()))
            .toList();
    EdgeId first = EdgeId.undirected(nodes.get(0).nodeId(), nodes.get(1).nodeId());
    EdgeId second = EdgeId.undirected(nodes.get(2).nodeId(), nodes.get(3).nodeId());
    RailFootprintCell crossing = new RailFootprintCell(5, 64, 0);
    RailFootprintCell ordinary = new RailFootprintCell(4000, 64, 4000);
    List<RailEdgeRecord> edges =
        List.of(
            new RailEdgeRecord(world, first, 10, 8.0, true, Set.of(crossing, ordinary)),
            new RailEdgeRecord(world, second, 10, 8.0, true, Set.of(crossing)));
    var sourceState =
        RailInterlockingState.from(
            world,
            Set.of(first, second),
            Map.of(
                first, new RailEdgeFootprint(1, true, Set.of(crossing, ordinary)),
                second, new RailEdgeFootprint(1, true, Set.of(crossing))));
    var snapshot =
        new RailInterlockingSnapshotRecord(
            world,
            1,
            RailInterlockingEdgeSignature.of(Set.of(first, second)),
            sourceState.coverage(),
            sourceState.exactZones());
    var result =
        new RailGraphBuildResult(
            RailGraphService.buildGraphFromRecords(nodes, edges, Optional.of(snapshot)),
            Instant.EPOCH,
            RailGraphSignature.signatureForNodes(nodes),
            nodes,
            List.of(),
            List.of());
    var store = new PendingRailGraphStore(directory);
    store.save(world, new PendingRailGraphStore.Candidate(result, Optional.empty(), ""));
    Path file = directory.resolve(world + ".json");
    var saved = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    var savedState =
        RailInterlockingSnapshotCodec.decode(world, saved.get("interlocking").getAsString())
            .orElseThrow();
    assertTrue(
        savedState.zones().values().stream()
            .noneMatch(zone -> zone.overlapCells().contains(ordinary)),
        "稀疏 Zone 不保存非交叉轨道采样");
    // 改写成旧格式：v2 没有足迹栏；v1 曾写过足迹栏但从不当作证据。两者读回都只有稀疏 Zone。
    var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    json.addProperty("version", version);
    for (var edge : json.getAsJsonArray("edges")) {
      edge.getAsJsonObject().remove("footprint");
    }
    if (version == 1) {
      json.getAsJsonArray("edges")
          .get(0)
          .getAsJsonObject()
          .addProperty("footprint", RailEdgeFootprintCodec.encode(Set.of(ordinary, crossing)));
    }
    Files.writeString(file, json.toString());
    var restored =
        ((RailGraphInterlockingSupport) store.load(world).orElseThrow().result().graph())
            .interlockingState();
    assertTrue(restored.coverage().complete());
    assertTrue(sourceState.sameResourceProjection(restored));
    assertFalse(restored.cellCoverageAvailable());
    assertTrue(restored.footprintCellsByEdge().isEmpty());
    assertTrue(restored.observeLiveCells(Set.of(crossing)).complete());
    assertEquals(
        sourceState.zoneKeysForEdge(first),
        restored.observeLiveCells(Set.of(crossing)).occupiedZoneKeys());
    assertTrue(restored.observeLiveCells(Set.of(ordinary)).complete());
    assertTrue(restored.observeLiveCells(Set.of(ordinary)).occupiedZoneKeys().isEmpty());
    var data = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    var interlocking =
        com.google.gson.JsonParser.parseString(data.get("interlocking").getAsString())
            .getAsJsonObject();
    interlocking.addProperty("edgeSignature", "changed-edges");
    data.addProperty("interlocking", interlocking.toString());
    Files.writeString(file, data.toString());
    assertThrows(java.io.IOException.class, () -> store.load(world), "联锁与拓扑不匹配时拒绝候选");
  }

  @Test
  void malformedCandidateCannotActivate() throws Exception {
    UUID world = UUID.randomUUID();
    Files.writeString(directory.resolve(world + ".json"), "{\"version\":999}");
    assertThrows(java.io.IOException.class, () -> new PendingRailGraphStore(directory).load(world));
  }
}
