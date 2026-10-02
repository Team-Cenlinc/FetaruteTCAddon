package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/** 编表只认永久限速覆盖：临时限速与封锁不进表，边没限速时用默认速度。 */
class TimetableEdgeSpeedsTest {

  private static final NodeId A = NodeId.of("OP:S:A:1");
  private static final NodeId B = NodeId.of("OP:S:B:1");

  @Test
  void permanentOverrideWinsOverBaseAndFallback() {
    EdgeId id = EdgeId.undirected(A, B);
    RailEdge edge = new RailEdge(id, A, B, 100, 0.0, true, Optional.empty());
    RailEdgeOverrideRecord permanent =
        new RailEdgeOverrideRecord(
            UUID.randomUUID(),
            id,
            OptionalDouble.of(16.5),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            Instant.EPOCH);
    RunCurveModel.EdgeSpeedResolver resolver = TimetableEdgeSpeeds.resolver(Map.of(id, permanent));

    assertEquals(16.5, resolver.resolve(null, edge, 8.0), 1e-9);
  }

  @Test
  void temporaryLimitIsIgnoredAndBaseOrFallbackApplies() {
    EdgeId id = EdgeId.undirected(A, B);
    RailEdge noBase = new RailEdge(id, A, B, 100, 0.0, true, Optional.empty());
    RailEdge withBase = new RailEdge(id, A, B, 100, 12.0, true, Optional.empty());
    RailEdgeOverrideRecord temporary =
        new RailEdgeOverrideRecord(
            UUID.randomUUID(),
            id,
            OptionalDouble.empty(),
            OptionalDouble.of(3.0),
            Optional.of(Instant.MAX),
            false,
            Optional.empty(),
            Instant.EPOCH);
    RunCurveModel.EdgeSpeedResolver resolver = TimetableEdgeSpeeds.resolver(Map.of(id, temporary));

    assertEquals(8.0, resolver.resolve(null, noBase, 8.0), 1e-9, "临时限速不进表，没基础限速用默认");
    assertEquals(12.0, resolver.resolve(null, withBase, 8.0), 1e-9, "有基础限速用基础限速");
    assertEquals(8.0, TimetableEdgeSpeeds.resolver(Map.of()).resolve(null, noBase, 8.0), 1e-9);
  }
}
