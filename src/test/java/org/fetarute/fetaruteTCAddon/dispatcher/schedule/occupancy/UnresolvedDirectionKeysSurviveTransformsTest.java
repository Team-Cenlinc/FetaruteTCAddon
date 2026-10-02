package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/**
 * {@code unresolvedDirectionKeys} 必须活过请求变换链。
 *
 * <p>方向 fail-closed 标记是在 builder 里写入的，但运行时授权请求在下发前一定会再经过若干 {@code with*} 变换（打来源标签、写占用/进度版本、 改
 * intent、附冲突清空证据）。这些变换历史上全部退回到不带该集合的兼容构造器，于是标记在真正参与判定之前就被清空——门在单元测试里成立， 在实服运行路径上等于不存在。
 *
 * <p>本用例逐个钉住每种变换，新增变换时必须同步登记。
 */
class UnresolvedDirectionKeysSurviveTransformsTest {

  private static final String CONFLICT_KEY = "single:section:bridge:OP:S:ALFA:1~OP:S:BRAVO:1";

  private static OccupancyRequest marked() {
    OccupancyResource conflict = OccupancyResource.forConflict(CONFLICT_KEY);
    return new OccupancyRequest(
        "T-1",
        Optional.of(RouteId.of("R-1")),
        Instant.EPOCH,
        List.of(conflict),
        Map.of(),
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        Map.of(conflict, ResourceIntent.MOVEMENT_REQUIRED),
        Optional.of(directedContext()),
        Set.of(CONFLICT_KEY));
  }

  private static DirectedTraversalContext directedContext() {
    return new DirectedTraversalContext(
        "T-1",
        Optional.of(RouteId.of("R-1")),
        0,
        Optional.of(NodeId.of("OP:S:ALFA:1")),
        Optional.empty(),
        Optional.of(NodeId.of("OP:S:ALFA:1")),
        Optional.of(NodeId.of("OP:S:BRAVO:1")),
        List.of(NodeId.of("OP:S:ALFA:1"), NodeId.of("OP:S:BRAVO:1")),
        List.of(),
        Map.of(),
        Map.of(),
        "RUNTIME_MOVE",
        -1L,
        -1L,
        "req-1",
        Optional.empty());
  }

  private static void assertStillMarked(String transform, OccupancyRequest request) {
    assertTrue(
        request.directionExplicitlyUnresolved(CONFLICT_KEY), transform + " 丢掉了方向 fail-closed 标记");
    assertEquals(Set.of(CONFLICT_KEY), request.unresolvedDirectionKeys(), transform);
  }

  @Test
  void identityTransformsKeepTheMarks() {
    OccupancyRequest base = marked();

    assertStillMarked("withTrainName", base.withTrainName("T-2"));
    assertStillMarked("withPurpose", base.withPurpose(AuthorizationPurpose.DEPOT_SPAWN));
    assertStillMarked(
        "withConflictReleaseHints",
        base.withConflictReleaseHints(AuthorizationPurpose.CONFLICT_CLEARING, Map.of()));
    assertStillMarked("withConflictClearingEvidence", base.withConflictClearingEvidence(Map.of()));
    assertStillMarked(
        "withResourceIntents",
        base.withResourceIntents(
            Map.of(OccupancyResource.forConflict(CONFLICT_KEY), ResourceIntent.MOVEMENT_REQUIRED)));
    assertStillMarked(
        "withSchedulingMetadata", base.withSchedulingMetadata(Instant.EPOCH.plusSeconds(1), 3));
    assertStillMarked("withDirectedContext", base.withDirectedContext(Optional.empty()));
    assertStillMarked("withDirectedSource", base.withDirectedSource("PERIODIC_TICK"));
    assertStillMarked("withDirectedOccupancyVersion", base.withDirectedOccupancyVersion(7L));
    assertStillMarked("withDirectedProgressVersion", base.withDirectedProgressVersion(11L));
  }

  /** 这是实服里真正跑的那条链：打来源标签 -> 写两个版本号。 */
  @Test
  void theRuntimeAuthorizationChainKeepsTheMarks() {
    OccupancyRequest directed =
        marked()
            .withDirectedSource("PERIODIC_TICK")
            .withDirectedOccupancyVersion(42L)
            .withDirectedProgressVersion(43L);

    assertStillMarked("runtime authorization chain", directed);
  }

  /** advisory preview 会换掉全部 intent；标记同样不得因此蒸发。 */
  @Test
  void lookaheadPreviewKeepsTheMarks() {
    assertStillMarked("asLookaheadPreview", marked().asLookaheadPreview());
  }

  /** 资源被移除时按资源键索引的元数据同步收窄，这是唯一允许丢弃标记的场合。 */
  @Test
  void removingPreviewResourcesAlsoNarrowsTheMarks() {
    OccupancyRequest preview = marked().asLookaheadPreview();
    OccupancyRequest writable = preview.withoutLookaheadPreviewResources();

    assertTrue(writable.resourceList().isEmpty(), "preview 资源应被全部移除");
    assertFalse(writable.directionExplicitlyUnresolved(CONFLICT_KEY), "资源已不在请求内时，对应标记必须一并收窄");
  }
}
