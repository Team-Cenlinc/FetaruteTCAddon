package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

final class RailEdgeOverrideRecordTest {

  @Test
  void rejectsNonPositiveSpeedLimits() {
    UUID worldId = UUID.randomUUID();
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RailEdgeOverrideRecord(
                worldId,
                edgeId,
                OptionalDouble.of(0.0),
                OptionalDouble.empty(),
                Optional.empty(),
                false,
                Optional.empty(),
                now));
  }

  @Test
  void rejectsTempSpeedWithoutUntil() {
    UUID worldId = UUID.randomUUID();
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RailEdgeOverrideRecord(
                worldId,
                edgeId,
                OptionalDouble.empty(),
                OptionalDouble.of(4.0),
                Optional.empty(),
                false,
                Optional.empty(),
                now));
  }

  @Test
  void isEmptyReturnsTrueWhenNoFieldsSet() {
    UUID worldId = UUID.randomUUID();
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    RailEdgeOverrideRecord record =
        new RailEdgeOverrideRecord(
            worldId,
            edgeId,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            now);
    assertTrue(record.isEmpty());
  }

  @Test
  void tempSpeedActiveDependsOnUntil() {
    UUID worldId = UUID.randomUUID();
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RailEdgeOverrideRecord record =
        new RailEdgeOverrideRecord(
            worldId,
            edgeId,
            OptionalDouble.empty(),
            OptionalDouble.of(4.0),
            Optional.of(now.plusSeconds(60)),
            false,
            Optional.empty(),
            now);

    assertTrue(record.isTempSpeedActive(now));
    assertFalse(record.isTempSpeedActive(now.plusSeconds(120)));
  }

  @Test
  void blockedEffectiveCombinesManualAndTtl() {
    UUID worldId = UUID.randomUUID();
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    RailEdgeOverrideRecord manual =
        new RailEdgeOverrideRecord(
            worldId,
            edgeId,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            true,
            Optional.empty(),
            now);
    assertTrue(manual.isBlockedEffective(now.plusSeconds(999)));

    RailEdgeOverrideRecord ttl =
        new RailEdgeOverrideRecord(
            worldId,
            edgeId,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.of(now.plusSeconds(60)),
            now);
    assertTrue(ttl.isBlockedEffective(now));
    assertFalse(ttl.isBlockedEffective(now.plusSeconds(120)));
  }
}
