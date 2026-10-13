package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher.DynamicSpec;
import org.junit.jupiter.api.Test;

/** DynamicStopMatcher 单元测试。 */
class DynamicStopMatcherTest {

  /** 离散允许集合必须保留空洞，不能用最小/最大股道替代集合成员判断。 */
  @Test
  void sparseSelectionOnlyMatchesDeclaredTracks() {
    DynamicSpec spec =
        DynamicStopMatcher.parseDynamicSpec("DYNAMIC:OP:S:DEST:[1,3,5]").orElseThrow();
    assertTrue(DynamicStopMatcher.matches("OP:S:DEST:1", spec));
    assertTrue(DynamicStopMatcher.matches("OP:S:DEST:3", spec));
    assertTrue(DynamicStopMatcher.matches("OP:S:DEST:5", spec));
    assertFalse(DynamicStopMatcher.matches("OP:S:DEST:2", spec));
    assertFalse(DynamicStopMatcher.matches("OP:S:DEST:4", spec));
  }

  /** “受限”只看允许集合是否小于该站实际股道数：覆盖全部现有股道的显式集合与不限股道同样不受限。 */
  @Test
  void restrictionComparesTheAllowedSetWithTheStationsActualTracks() {
    java.util.List<NodeId> station =
        java.util.List.of(
            NodeId.of("OP:S:DEST:1"),
            NodeId.of("OP:S:DEST:2"),
            NodeId.of("OP:S:OTHER:3"),
            NodeId.of("OP:S:DEST:1:001"));
    assertFalse(restricts("DYNAMIC:OP:S:DEST", station));
    assertFalse(restricts("DYNAMIC:OP:S:DEST:[1,2]", station));
    assertFalse(restricts("DYNAMIC:OP:S:DEST:[1:5]", station));
    assertTrue(restricts("DYNAMIC:OP:S:DEST:[1]", station));
    assertTrue(restricts("DYNAMIC:OP:S:DEST:[2,3]", station));
  }

  private static boolean restricts(String raw, java.util.List<NodeId> nodes) {
    return DynamicStopMatcher.restricts(
        DynamicStopMatcher.parseDynamicSpec(raw).orElseThrow(), nodes);
  }

  /** 股道按数字排序：10 道在 2 道之后；选台、出车与派票共用这一顺序。 */
  @Test
  void tracksAreOrderedNumerically() {
    java.util.List<NodeId> nodes =
        new java.util.ArrayList<>(
            java.util.List.of(
                NodeId.of("OP:D:DEP:10"), NodeId.of("OP:D:DEP:2"), NodeId.of("OP:D:DEP:1")));
    nodes.sort(DynamicStopMatcher.TRACK_ORDER);
    assertEquals(
        java.util.List.of(
            NodeId.of("OP:D:DEP:1"), NodeId.of("OP:D:DEP:2"), NodeId.of("OP:D:DEP:10")),
        nodes);
  }

  /** 固定声明节点与同站容错不能覆盖明确的 DYNAMIC 股道限制。 */
  @Test
  void dynamicRestrictionOverridesFixedWaypointTolerance() {
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.of("OP:S:DEST:1"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:OP:S:DEST:[1:1]"));
    assertFalse(DynamicStopMatcher.matchesStop(NodeId.of("OP:S:DEST:2"), stop));
  }

  /** 固定车库与辅助动态站台组合保留车库身份，但五段咽喉不是出库行为节点。 */
  @Test
  void fixedDepotWithAuxiliaryStationRejectsThroatArrival() {
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.of("OP:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET OP:D:DEPOT:1\nDYNAMIC:OP:S:DEST:[1,3]"));
    assertTrue(DynamicStopMatcher.matchesStop(NodeId.of("OP:D:DEPOT:1"), stop));
    assertFalse(DynamicStopMatcher.matchesStop(NodeId.of("OP:D:DEPOT:1:001"), stop));
  }

  @Test
  void parseDynamicSpec_stationWithRange() {
    Optional<DynamicSpec> result = DynamicStopMatcher.parseDynamicSpec("DYNAMIC:SURC:S:PPK:[1:3]");
    assertTrue(result.isPresent());
    DynamicSpec spec = result.get();
    assertEquals("SURC", spec.operatorCode());
    assertEquals("S", spec.nodeType());
    assertEquals("PPK", spec.nodeName());
    assertEquals(1, spec.tracks().firstDeclaredTrack().orElseThrow());
    assertEquals(3, spec.tracks().lastDeclaredTrack().orElseThrow());
    assertTrue(spec.isStation());
    assertFalse(spec.isDepot());
  }

  @Test
  void parseDynamicSpec_depotWithRange() {
    Optional<DynamicSpec> result = DynamicStopMatcher.parseDynamicSpec("DYNAMIC:SURC:D:OFL:[1:2]");
    assertTrue(result.isPresent());
    DynamicSpec spec = result.get();
    assertEquals("SURC", spec.operatorCode());
    assertEquals("D", spec.nodeType());
    assertEquals("OFL", spec.nodeName());
    assertEquals(1, spec.tracks().firstDeclaredTrack().orElseThrow());
    assertEquals(2, spec.tracks().lastDeclaredTrack().orElseThrow());
    assertFalse(spec.isStation());
    assertTrue(spec.isDepot());
  }

  /**
   * 未声明范围表示“该站现有的全部股道”。
   *
   * <p>旧行为是默认 1..10 的人为上限。它有两个问题：编号超过 10 的股道会被漏掉；更要命的是 {@code RuntimeDispatchService}
   * 内的同名解析对同一写法默认成 1..1，两套口径不一致， 导致被分配到 2 道的列车与唯一候选 1 道不相等、候选枚举静默走空，列车被永久挡在站外。 现在统一标记为
   * unbounded，由调用方按图上实际存在的股道枚举。
   */
  @Test
  void parseDynamicSpec_withoutRange_isUnbounded() {
    Optional<DynamicSpec> result = DynamicStopMatcher.parseDynamicSpec("DYNAMIC:SURC:S:PPK");
    assertTrue(result.isPresent());
    DynamicSpec spec = result.get();
    assertTrue(spec.unbounded());
    assertTrue(spec.tracks().firstDeclaredTrack().isEmpty());
  }

  @Test
  void parseDynamicSpec_withoutDynamicPrefix() {
    Optional<DynamicSpec> result = DynamicStopMatcher.parseDynamicSpec("SURC:S:PPK:[1:3]");
    assertTrue(result.isPresent());
    DynamicSpec spec = result.get();
    assertEquals("SURC", spec.operatorCode());
    assertEquals("PPK", spec.nodeName());
  }

  @Test
  void parseDynamicSpec_oldFormat_defaultsToStation() {
    Optional<DynamicSpec> result = DynamicStopMatcher.parseDynamicSpec("SURC:PPK:[1:3]");
    assertTrue(result.isPresent());
    DynamicSpec spec = result.get();
    assertEquals("SURC", spec.operatorCode());
    assertEquals("S", spec.nodeType());
    assertEquals("PPK", spec.nodeName());
  }

  @Test
  void matches_stationNodeIdMatchesDynamicSpec() {
    DynamicSpec spec = new DynamicSpec("SURC", "S", "PPK", 1, 3);
    assertTrue(DynamicStopMatcher.matches("SURC:S:PPK:1", spec));
    assertTrue(DynamicStopMatcher.matches("SURC:S:PPK:2", spec));
    assertTrue(DynamicStopMatcher.matches("SURC:S:PPK:3", spec));
    assertFalse(DynamicStopMatcher.matches("SURC:S:PPK:4", spec));
    assertFalse(DynamicStopMatcher.matches("SURC:S:PPK:0", spec));
  }

  @Test
  void matches_depotNodeIdMatchesDynamicSpec() {
    DynamicSpec spec = new DynamicSpec("SURC", "D", "OFL", 1, 2);
    assertTrue(DynamicStopMatcher.matches("SURC:D:OFL:1", spec));
    assertTrue(DynamicStopMatcher.matches("SURC:D:OFL:2", spec));
    assertFalse(DynamicStopMatcher.matches("SURC:D:OFL:3", spec));
    assertFalse(DynamicStopMatcher.matches("SURC:S:OFL:1", spec)); // wrong type
  }

  @Test
  void matches_caseInsensitive() {
    DynamicSpec spec = new DynamicSpec("SURC", "S", "PPK", 1, 3);
    assertTrue(DynamicStopMatcher.matches("surc:s:ppk:1", spec));
    assertTrue(DynamicStopMatcher.matches("Surc:S:Ppk:2", spec));
  }

  @Test
  void matches_differentOperator_fails() {
    DynamicSpec spec = new DynamicSpec("SURC", "S", "PPK", 1, 3);
    assertFalse(DynamicStopMatcher.matches("SURM:S:PPK:1", spec));
  }

  @Test
  void matches_differentNodeName_fails() {
    DynamicSpec spec = new DynamicSpec("SURC", "S", "PPK", 1, 3);
    assertFalse(DynamicStopMatcher.matches("SURC:S:JBS:1", spec));
  }

  @Test
  void matches_nodeId_wrapper() {
    DynamicSpec spec = new DynamicSpec("SURC", "D", "OFL", 1, 2);
    assertTrue(DynamicStopMatcher.matches(NodeId.of("SURC:D:OFL:1"), spec));
    assertFalse(DynamicStopMatcher.matches(NodeId.of("SURC:D:OFL:3"), spec));
  }

  @Test
  void isDynamicStop_withDynamicNotes() {
    RouteStop stop = createStop("DYNAMIC:SURC:S:PPK:[1:3]");
    assertTrue(DynamicStopMatcher.isDynamicStop(stop));
  }

  @Test
  void isDynamicStop_withCretDynamic() {
    RouteStop stop = createStop("CRET DYNAMIC:SURC:D:OFL:[1:2]");
    assertTrue(DynamicStopMatcher.isDynamicStop(stop));
  }

  @Test
  void isDynamicStop_withBareInvalidDirective() {
    RouteStop stop = createStop("DYNAMIC:");
    assertTrue(DynamicStopMatcher.isDynamicStop(stop));
    assertTrue(DynamicStopMatcher.parseDynamicSpec(stop).isEmpty());
  }

  @Test
  void isDynamicStop_withoutDynamic() {
    RouteStop stop = createStop("STOP dwell=30");
    assertFalse(DynamicStopMatcher.isDynamicStop(stop));
  }

  @Test
  void isDynamicStop_doesNotTreatNodeSegmentAsDirective() {
    RouteStop stop = createStop("CHANGE:OP:DYNAMIC");
    assertFalse(DynamicStopMatcher.isDynamicStop(stop));
    assertTrue(DynamicStopMatcher.parseDynamicSpec(stop).isEmpty());
  }

  @Test
  void parseDynamicSpec_fromStop() {
    RouteStop stop = createStop("DYNAMIC:SURC:S:PPK:[1:3]");
    Optional<DynamicSpec> result = DynamicStopMatcher.parseDynamicSpec(stop);
    assertTrue(result.isPresent());
    assertEquals("PPK", result.get().nodeName());
  }

  @Test
  void matchesStop_dynamicStop() {
    RouteStop stop = createStop("DYNAMIC:SURC:D:OFL:[1:2]");
    assertTrue(DynamicStopMatcher.matchesStop(NodeId.of("SURC:D:OFL:1"), stop));
    assertTrue(DynamicStopMatcher.matchesStop(NodeId.of("SURC:D:OFL:2"), stop));
    assertFalse(DynamicStopMatcher.matchesStop(NodeId.of("SURC:D:OFL:3"), stop));
  }

  @Test
  void matchesStop_normalStop() {
    RouteStop stop = createStopWithNodeId("SURC:S:PPK:1");
    assertTrue(DynamicStopMatcher.matchesStop(NodeId.of("SURC:S:PPK:1"), stop));
    // 同站不同轨道也应该匹配（容错）
    assertTrue(DynamicStopMatcher.matchesStop(NodeId.of("SURC:S:PPK:2"), stop));
    assertFalse(DynamicStopMatcher.matchesStop(NodeId.of("SURC:S:JBS:1"), stop));
  }

  @Test
  void extractStationKey_validFormat() {
    Optional<String> key = DynamicStopMatcher.extractStationKey("SURC:S:PPK:1");
    assertTrue(key.isPresent());
    assertEquals("surc:s:ppk", key.get());
  }

  @Test
  void extractStationKey_depotFormat() {
    Optional<String> key = DynamicStopMatcher.extractStationKey("SURC:D:OFL:1");
    assertTrue(key.isPresent());
    assertEquals("surc:d:ofl", key.get());
  }

  @Test
  void extractStationKey_invalidFormat() {
    assertTrue(DynamicStopMatcher.extractStationKey("invalid").isEmpty());
    assertTrue(DynamicStopMatcher.extractStationKey("A:B:C").isEmpty());
    assertTrue(DynamicStopMatcher.extractStationKey("A:X:C:1").isEmpty()); // X is not S/D
  }

  @Test
  void specToStationKey() {
    DynamicSpec spec = new DynamicSpec("SURC", "S", "PPK", 1, 3);
    assertEquals("surc:s:ppk", DynamicStopMatcher.specToStationKey(spec));
  }

  private RouteStop createStop(String notes) {
    return new RouteStop(
        UUID.randomUUID(),
        1,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.of(notes));
  }

  private RouteStop createStopWithNodeId(String nodeId) {
    return new RouteStop(
        UUID.randomUUID(),
        1,
        Optional.empty(),
        Optional.of(nodeId),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.empty());
  }
}
