package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * 晚点追赶放宽的只是"写明的线路限速"；叫来的车按需降速压的是算完的边限速。
 *
 * <p>这张表钉住哪些限速可以被倍率放大、哪些不行：牌子写的边限速与永久覆盖是编表用的线路限速，可以放宽； 没写限速走默认速度的边没有证据扛得住更快，临时限速是运维硬约束，都不放宽。 小于 1
 * 的倍率不分来源，一律按比例压低。
 */
class RailGraphServiceLineSpeedFactorTest {

  private static final UUID WORLD = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
  private static final double DEFAULT_SPEED = 8.0;
  private static final double DELTA = 1.0e-9;

  private static final NodeId A = NodeId.of("A");
  private static final NodeId B = NodeId.of("B");

  private static RailEdge edge(double baseSpeedLimit) {
    return new RailEdge(EdgeId.undirected(A, B), A, B, 100, baseSpeedLimit, true, Optional.empty());
  }

  private static RailGraphService service() {
    return new RailGraphService(world -> null);
  }

  private static RailEdgeOverrideRecord override(
      OptionalDouble permanent, OptionalDouble temp, Optional<Instant> tempUntil) {
    return new RailEdgeOverrideRecord(
        WORLD, EdgeId.undirected(A, B), permanent, temp, tempUntil, false, Optional.empty(), NOW);
  }

  @Test
  void writtenBaseLimitIsScaled() {
    RailGraphService service = service();

    assertEquals(
        16.0 * 1.1,
        service.effectiveSpeedLimitBlocksPerSecond(WORLD, edge(16.0), NOW, DEFAULT_SPEED, 1.1),
        DELTA);
    assertEquals(
        16.0,
        service.effectiveSpeedLimitBlocksPerSecond(WORLD, edge(16.0), NOW, DEFAULT_SPEED),
        DELTA,
        "四参数版本等于倍率 1，旧调用方行为不变");
  }

  @Test
  void defaultSpeedIsNeverScaled() {
    assertEquals(
        DEFAULT_SPEED,
        service().effectiveSpeedLimitBlocksPerSecond(WORLD, edge(-1.0), NOW, DEFAULT_SPEED, 1.1),
        DELTA,
        "没写限速的边（常见于道岔边）没有证据扛得住更快");
  }

  @Test
  void permanentOverrideIsScaled() {
    RailGraphService service = service();
    service.putEdgeOverride(
        override(OptionalDouble.of(20.0), OptionalDouble.empty(), Optional.empty()));

    assertEquals(
        22.0,
        service.effectiveSpeedLimitBlocksPerSecond(WORLD, edge(-1.0), NOW, DEFAULT_SPEED, 1.1),
        DELTA);
  }

  @Test
  void activeTemporaryLimitStaysHard() {
    RailGraphService service = service();
    service.putEdgeOverride(
        override(
            OptionalDouble.of(20.0), OptionalDouble.of(12.0), Optional.of(NOW.plusSeconds(600))));

    assertEquals(
        12.0,
        service.effectiveSpeedLimitBlocksPerSecond(WORLD, edge(-1.0), NOW, DEFAULT_SPEED, 1.1),
        DELTA,
        "施工/限行的临时限速不放宽");
  }

  @Test
  void temporaryLimitAboveScaledLineSpeedDoesNotRaiseIt() {
    RailGraphService service = service();
    service.putEdgeOverride(
        override(
            OptionalDouble.of(20.0), OptionalDouble.of(25.0), Optional.of(NOW.plusSeconds(600))));

    assertEquals(
        22.0,
        service.effectiveSpeedLimitBlocksPerSecond(WORLD, edge(-1.0), NOW, DEFAULT_SPEED, 1.1),
        DELTA);
  }

  @Test
  void slowdownScalesWhateverTheLimitComesFrom() {
    RailGraphService service = service();

    assertEquals(
        11.2,
        service.effectiveSpeedLimitBlocksPerSecond(WORLD, edge(16.0), NOW, DEFAULT_SPEED, 0.7),
        DELTA,
        "写明的线路限速");
    assertEquals(
        DEFAULT_SPEED * 0.7,
        service.effectiveSpeedLimitBlocksPerSecond(WORLD, edge(-1.0), NOW, DEFAULT_SPEED, 0.7),
        DELTA,
        "默认速度也压");
    service.putEdgeOverride(
        override(
            OptionalDouble.of(20.0), OptionalDouble.of(12.0), Optional.of(NOW.plusSeconds(600))));
    assertEquals(
        12.0 * 0.7,
        service.effectiveSpeedLimitBlocksPerSecond(WORLD, edge(-1.0), NOW, DEFAULT_SPEED, 0.7),
        DELTA,
        "临时限速同样按比例压");
  }

  @Test
  void nonPositiveAndNonFiniteFactorsAreIgnored() {
    RailGraphService service = service();

    assertEquals(
        16.0,
        service.effectiveSpeedLimitBlocksPerSecond(WORLD, edge(16.0), NOW, DEFAULT_SPEED, 0.0),
        DELTA);
    assertEquals(
        16.0,
        service.effectiveSpeedLimitBlocksPerSecond(WORLD, edge(16.0), NOW, DEFAULT_SPEED, -0.5),
        DELTA);
    assertEquals(
        16.0,
        service.effectiveSpeedLimitBlocksPerSecond(
            WORLD, edge(16.0), NOW, DEFAULT_SPEED, Double.NaN),
        DELTA);
    assertEquals(
        16.0,
        service.effectiveSpeedLimitBlocksPerSecond(
            WORLD, edge(16.0), NOW, DEFAULT_SPEED, Double.POSITIVE_INFINITY),
        DELTA);
  }
}
