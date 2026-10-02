package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

/**
 * 停因的「入场理由」与「当前成因」必须是两个量。
 *
 * <p>{@code SMART_BLOCKING_SNAPSHOT} 的 {@code detail} 取自停因建立时存下的字符串。实服 2026-09-17 一轮：102 段长时间保持里
 * 100 段（98%）的 detail 全程不变，中位持续 153 秒。于是日志只回答了 「它最初为什么停」。这个类钉住 {@code liveCause} 回答另一个问题：「此刻记下的那些
 * blocker 还成立吗」。
 *
 * <p>最要紧的是 {@link #recordedBlockersAllGoneIsSaidOutLoud()}：阻塞者已经全部消失、车却还停着， 必须**明说**。实服里这个形状被
 * detail 的陈旧文本盖住了整整 183 秒——那条 EDGE 的资源生命周期 （必留项）显示它全程无人持有。
 */
class LiveStopCauseTest {

  private static final OccupancyResource PHYSICAL_EDGE =
      OccupancyResource.forEdge(
          EdgeId.undirected(NodeId.of("OP:S:ALFA:1"), NodeId.of("OP:S:BRAVO:1")));
  private static final OccupancyResource ABSTRACT_SINGLE =
      OccupancyResource.forConflict("single:section:bridge:A~B");

  /** 记下的阻塞者仍被别人以硬角色持有：如实报出形状与命中比例。 */
  @Test
  void stillHeldReportsShapeAndRatio() {
    String cause =
        OccupancyClaimEvidence.describeLiveStopCause(
            "VICTIM",
            List.of(blockerOf(PHYSICAL_EDGE, "HOLDER", ClaimRole.PROTECTIVE_RETAIN)),
            List.of(claimOf(PHYSICAL_EDGE, "HOLDER", ClaimRole.PROTECTIVE_RETAIN)));
    assertEquals("still-held:EDGE/PROTECTIVE_RETAIN:1/1", cause);
  }

  /** 阻塞者全部释放、车却还停着——这是最要紧的一类，必须与「仍被挡着」截然不同。 */
  @Test
  void recordedBlockersAllGoneIsSaidOutLoud() {
    String cause =
        OccupancyClaimEvidence.describeLiveStopCause(
            "VICTIM",
            List.of(blockerOf(PHYSICAL_EDGE, "HOLDER", ClaimRole.PROTECTIVE_RETAIN)),
            List.of());
    assertEquals("recorded-blockers-all-cleared:0/1", cause);
    assertTrue(cause.startsWith("recorded-blockers-all-cleared"), cause);
  }

  /** 抽象 single 上别人的 PROTECTIVE_RETAIN 不算阻塞——liveCause 必须走同一张真值表，不另写一套。 */
  @Test
  void abstractSingleRetainIsNotAnObstruction() {
    assertEquals(
        "recorded-blockers-all-cleared:0/1",
        OccupancyClaimEvidence.describeLiveStopCause(
            "VICTIM",
            List.of(blockerOf(ABSTRACT_SINGLE, "HOLDER", ClaimRole.PROTECTIVE_RETAIN)),
            List.of(claimOf(ABSTRACT_SINGLE, "HOLDER", ClaimRole.PROTECTIVE_RETAIN))));
  }

  /** 本车自己持有的 claim 永远不是自己的阻塞者。 */
  @Test
  void selfHeldClaimIsNeverOwnBlocker() {
    assertEquals(
        "recorded-blockers-all-cleared:0/1",
        OccupancyClaimEvidence.describeLiveStopCause(
            "VICTIM",
            List.of(blockerOf(PHYSICAL_EDGE, "VICTIM", ClaimRole.MOVEMENT_REQUIRED)),
            List.of(claimOf(PHYSICAL_EDGE, "VICTIM", ClaimRole.MOVEMENT_REQUIRED))));
  }

  /** 部分命中要能看出比例，否则「还剩几个」无从判断疏通有没有在推进。 */
  @Test
  void partialHitKeepsRatio() {
    OccupancyResource otherEdge =
        OccupancyResource.forEdge(
            EdgeId.undirected(NodeId.of("OP:S:BRAVO:1"), NodeId.of("OP:S:CHARLIE:1")));
    String cause =
        OccupancyClaimEvidence.describeLiveStopCause(
            "VICTIM",
            List.of(
                blockerOf(PHYSICAL_EDGE, "HOLDER", ClaimRole.PROTECTIVE_RETAIN),
                blockerOf(otherEdge, "HOLDER", ClaimRole.MOVEMENT_REQUIRED)),
            List.of(claimOf(otherEdge, "HOLDER", ClaimRole.MOVEMENT_REQUIRED)));
    assertEquals("still-held:EDGE/MOVEMENT_REQUIRED:1/2", cause);
  }

  /** 停因本来就没记下 blocker（发车门控等）：要说「没记」，不能说「已清空」——两者下一步不同。 */
  @Test
  void emptyRecordIsDistinctFromCleared() {
    assertEquals(
        "no-recorded-blockers",
        OccupancyClaimEvidence.describeLiveStopCause("VICTIM", List.of(), List.of()));
  }

  private static RuntimeStopState.Blocker blockerOf(
      OccupancyResource resource, String owner, ClaimRole role) {
    return new RuntimeStopState.Blocker(resource.toString(), owner, role.name());
  }

  private static OccupancyClaim claimOf(OccupancyResource resource, String train, ClaimRole role) {
    return new OccupancyClaim(
        resource,
        train,
        java.util.Optional.empty(),
        java.time.Instant.parse("2026-01-01T00:00:00Z"),
        java.time.Duration.ZERO,
        java.util.Optional.empty(),
        role);
  }
}
