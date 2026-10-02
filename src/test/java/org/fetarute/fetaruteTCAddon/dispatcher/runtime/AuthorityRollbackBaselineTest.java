package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

/** 回滚快照：acquire 之前本车持有的资源（任意角色）不放，其余照旧可放；保下资源的诊断行按车去重。 */
class AuthorityRollbackBaselineTest {

  private static final String TRAIN = "SURC-MT-LP-3504";
  private static final OccupancyResource MERGE =
      OccupancyResource.forNode(NodeId.of("SWITCHER:Towny:-566:77:1179"));
  private static final OccupancyResource BODY =
      OccupancyResource.forNode(NodeId.of("SURC:SPB:WSD:2:001"));
  private static final OccupancyResource AHEAD =
      OccupancyResource.forNode(NodeId.of("SURC:SPB:JBS:1:002"));

  @Test
  void resourcesHeldBeforeInAnyRoleAreNotReleasable() {
    AuthorityRollbackBaseline baseline =
        AuthorityRollbackBaseline.capture(
            List.of(
                claim(MERGE, ClaimRole.MOVEMENT_REQUIRED),
                claim(BODY, ClaimRole.PROTECTIVE_RETAIN)));

    assertFalse(baseline.releasable(MERGE));
    assertFalse(baseline.releasable(BODY));
    assertTrue(baseline.releasable(AHEAD), "本拍新拿到的照旧可放");
    assertTrue(AuthorityRollbackBaseline.capture(null).releasable(MERGE));
  }

  @Test
  void traceLineListsTheKeptResourcesInAStableOrder() {
    assertTrue(
        AuthorityRollbackBaseline.traceLine(TRAIN, "AUTHORIZATION_FAILURE", List.of()).isEmpty());
    assertEquals(
        Optional.of(
            "SMART_AUTHORITY_ROLLBACK_KEPT_HELD train="
                + TRAIN
                + " reason=AUTHORIZATION_FAILURE kept=2 resources=["
                + BODY
                + ", "
                + MERGE
                + "]"),
        AuthorityRollbackBaseline.traceLine(TRAIN, "AUTHORIZATION_FAILURE", List.of(MERGE, BODY)));
  }

  /** 同一行只输出一次；中间有一次没保下任何资源（记录被清掉）之后，同样的内容会再输出。 */
  @Test
  void dedupEmitsOnlyWhenTheLineChanges() {
    HeldForwardAuthority.TraceDedup dedup = new HeldForwardAuthority.TraceDedup();
    List<String> out = new ArrayList<>();
    Optional<String> line =
        AuthorityRollbackBaseline.traceLine(TRAIN, "RECOVERABLE_HOLD", List.of(MERGE));

    dedup.emit(TRAIN, line, out::add);
    dedup.emit(TRAIN, line, out::add);
    dedup.emit(TRAIN, Optional.empty(), out::add);
    dedup.emit(TRAIN, line, out::add);

    assertEquals(List.of(line.orElseThrow(), line.orElseThrow()), out);
  }

  private static OccupancyClaim claim(OccupancyResource resource, ClaimRole role) {
    return new OccupancyClaim(
        resource, TRAIN, Optional.empty(), Instant.now(), Duration.ZERO, Optional.empty(), role);
  }
}
