package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** ControlDiagnostics 与 ControlDiagnosticsCache 单元测试。 */
public final class ControlDiagnosticsTest {

  @Test
  void buildDiagnosticsWithAllFields() {
    Instant now = Instant.now();
    ControlDiagnostics diag =
        new ControlDiagnostics.Builder()
            .trainName("train1")
            .routeId(RouteId.of("SURN/LT/EXP01"))
            .currentNode(NodeId.of("SURN:S:StationA:1"))
            .nextNode(NodeId.of("SURN:S:StationB:1"))
            .currentSpeedBps(5.0)
            .targetSpeedBps(8.0)
            .edgeLimitBps(16.0)
            .recommendedSpeedBps(6.5)
            .distanceToBlocker(OptionalLong.of(100))
            .distanceToCaution(OptionalLong.of(200))
            .distanceToApproach(OptionalLong.of(50))
            .currentSignal(SignalAspect.PROCEED)
            .effectiveSignal(SignalAspect.PROCEED_WITH_CAUTION)
            .allowLaunch(true)
            .sampledAt(now)
            .build();

    assertEquals("train1", diag.trainName());
    assertEquals("SURN/LT/EXP01", diag.routeId().value());
    assertEquals("SURN:S:StationA:1", diag.currentNode().value());
    assertEquals("SURN:S:StationB:1", diag.nextNode().value());
    assertEquals(5.0, diag.currentSpeedBps());
    assertEquals(8.0, diag.targetSpeedBps());
    assertEquals(16.0, diag.edgeLimitBps());
    assertEquals(6.5, diag.recommendedSpeedBps().orElse(-1));
    assertEquals(100L, diag.distanceToBlocker().orElse(-1));
    assertEquals(200L, diag.distanceToCaution().orElse(-1));
    assertEquals(50L, diag.distanceToApproach().orElse(-1));
    assertEquals(SignalAspect.PROCEED, diag.currentSignal());
    assertEquals(SignalAspect.PROCEED_WITH_CAUTION, diag.effectiveSignal());
    assertTrue(diag.allowLaunch());
    assertEquals(now, diag.sampledAt());
  }

  @Test
  void minConstraintDistanceReturnsSmallestStopConstraint() {
    // minConstraintDistance 现在只考虑 blocker/caution，不含 approach
    ControlDiagnostics diag =
        new ControlDiagnostics.Builder()
            .trainName("train1")
            .distanceToBlocker(OptionalLong.of(100))
            .distanceToCaution(OptionalLong.of(200))
            .distanceToApproach(OptionalLong.of(50)) // 不参与 min 计算
            .currentSignal(SignalAspect.PROCEED)
            .effectiveSignal(SignalAspect.PROCEED)
            .sampledAt(Instant.now())
            .build();

    OptionalLong min = diag.minConstraintDistance();
    assertTrue(min.isPresent());
    assertEquals(100L, min.getAsLong()); // blocker 最近，不是 approach
  }

  @Test
  void minConstraintDistanceEmptyWhenNoConstraints() {
    ControlDiagnostics diag =
        new ControlDiagnostics.Builder()
            .trainName("train1")
            .currentSignal(SignalAspect.PROCEED)
            .effectiveSignal(SignalAspect.PROCEED)
            .sampledAt(Instant.now())
            .build();

    OptionalLong min = diag.minConstraintDistance();
    assertFalse(min.isPresent());
  }

  @Test
  void cacheGetReturnsEmptyForUnknownTrain() {
    ControlDiagnosticsCache cache = new ControlDiagnosticsCache();
    assertTrue(cache.get("unknown", Instant.now()).isEmpty());
  }

  @Test
  void cachePutAndGet() {
    ControlDiagnosticsCache cache = new ControlDiagnosticsCache();
    Instant now = Instant.now();
    ControlDiagnostics diag =
        new ControlDiagnostics.Builder()
            .trainName("train1")
            .currentSignal(SignalAspect.PROCEED)
            .effectiveSignal(SignalAspect.PROCEED)
            .sampledAt(now)
            .build();

    cache.put(diag, now);
    var result = cache.get("train1", now);
    assertTrue(result.isPresent());
    assertEquals("train1", result.get().trainName());
  }

  @Test
  void cacheExpiresAfterTtl() {
    // 使用短 TTL 测试过期
    ControlDiagnosticsCache cache = new ControlDiagnosticsCache(Duration.ofMillis(10));
    Instant now = Instant.now();
    ControlDiagnostics diag =
        new ControlDiagnostics.Builder()
            .trainName("train1")
            .currentSignal(SignalAspect.PROCEED)
            .effectiveSignal(SignalAspect.PROCEED)
            .sampledAt(now)
            .build();

    cache.put(diag, now);

    // 立即获取应该命中
    assertTrue(cache.get("train1", now).isPresent());

    // 过期后应该返回空
    Instant expired = now.plusMillis(20);
    assertTrue(cache.get("train1", expired).isEmpty());
  }

  @Test
  void cacheRemove() {
    ControlDiagnosticsCache cache = new ControlDiagnosticsCache();
    Instant now = Instant.now();
    ControlDiagnostics diag =
        new ControlDiagnostics.Builder()
            .trainName("train1")
            .currentSignal(SignalAspect.PROCEED)
            .effectiveSignal(SignalAspect.PROCEED)
            .sampledAt(now)
            .build();

    cache.put(diag, now);
    assertTrue(cache.get("train1", now).isPresent());

    cache.remove("train1");
    assertTrue(cache.get("train1", now).isEmpty());
  }

  @Test
  void cacheSnapshot() {
    ControlDiagnosticsCache cache = new ControlDiagnosticsCache();
    Instant now = Instant.now();

    for (int i = 1; i <= 3; i++) {
      ControlDiagnostics diag =
          new ControlDiagnostics.Builder()
              .trainName("train" + i)
              .currentSignal(SignalAspect.PROCEED)
              .effectiveSignal(SignalAspect.PROCEED)
              .sampledAt(now)
              .build();
      cache.put(diag, now);
    }

    var snapshot = cache.snapshot(now);
    assertEquals(3, snapshot.size());
    assertTrue(snapshot.containsKey("train1"));
    assertTrue(snapshot.containsKey("train2"));
    assertTrue(snapshot.containsKey("train3"));
  }

  @Test
  void builderWithLookahead() {
    SignalLookahead.LookaheadResult lookahead =
        new SignalLookahead.LookaheadResult(
            OptionalLong.of(100),
            OptionalLong.of(200),
            OptionalLong.of(50),
            SignalAspect.PROCEED_WITH_CAUTION);

    ControlDiagnostics diag =
        new ControlDiagnostics.Builder()
            .trainName("train1")
            .currentSignal(SignalAspect.PROCEED)
            .lookahead(lookahead)
            .sampledAt(Instant.now())
            .build();

    assertEquals(100L, diag.distanceToBlocker().orElse(-1));
    assertEquals(200L, diag.distanceToCaution().orElse(-1));
    assertEquals(50L, diag.distanceToApproach().orElse(-1));
    assertEquals(SignalAspect.PROCEED_WITH_CAUTION, diag.effectiveSignal());
  }
}
