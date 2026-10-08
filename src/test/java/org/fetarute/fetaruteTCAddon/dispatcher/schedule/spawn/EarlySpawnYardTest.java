package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EarlySpawnYardTest {

  private static final String T1 = "OP:D:DEP:1";
  private static final String T2 = "OP:D:DEP:2";
  private static final String T3 = "OP:D:DEP:3";
  private static final Instant AT = Instant.parse("2026-03-02T08:00:00Z");

  private static EarlySpawnYard.Use departure(String subject, String... tracks) {
    return new EarlySpawnYard.Use(
        EarlySpawnYard.UseKind.DEPARTURE, subject, Optional.of(AT), Set.of(tracks));
  }

  @Test
  void aFreeFixedTrackWithNoOtherUseIsTaken() {
    assertEquals(
        EarlySpawnYard.Decision.use(T1), EarlySpawnYard.choose(List.of(T1), Set.of(T1), List.of()));
  }

  @Test
  void anOccupiedFixedTrackIsRefused() {
    EarlySpawnYard.Decision decision = EarlySpawnYard.choose(List.of(T1), Set.of(T2), List.of());

    assertEquals(EarlySpawnYard.Reason.TRACK_OCCUPIED, decision.blocker().orElseThrow().reason());
    assertEquals(T1, decision.blocker().orElseThrow().track());
  }

  /** 两条线路共用一条出库股道：另一条线要先从这条股道出库，就不能提前占住它。 */
  @Test
  void aFixedTrackAnotherLineDepartsFromIsRefused() {
    EarlySpawnYard.Use other = departure("DS-1F_Full", T1);

    EarlySpawnYard.Decision decision =
        EarlySpawnYard.choose(List.of(T1), Set.of(T1, T2), List.of(other));

    EarlySpawnYard.Blocker blocker = decision.blocker().orElseThrow();
    assertEquals(EarlySpawnYard.Reason.NEEDED_BY_OTHERS, blocker.reason());
    assertEquals(T1, blocker.track());
    assertEquals(Optional.of(other), blocker.use());
  }

  /** 别的股道上的使用不相干。 */
  @Test
  void usesOnOtherTracksDoNotMatter() {
    assertEquals(
        EarlySpawnYard.Decision.use(T1),
        EarlySpawnYard.choose(List.of(T1), Set.of(T1), List.of(departure("X", T2))));
  }

  /** 车库池：别的车也用这个池时，占掉一条后还要剩一条空的。 */
  @Test
  void aPoolKeepsOneFreeTrackForOthers() {
    EarlySpawnYard.Use pool = departure("MT-1O_ShortD", T1, T2, T3);

    assertEquals(
        EarlySpawnYard.Decision.use(T1),
        EarlySpawnYard.choose(List.of(T1, T2, T3), Set.of(T1, T2), List.of(pool)));

    EarlySpawnYard.Decision lastFree =
        EarlySpawnYard.choose(List.of(T1, T2, T3), Set.of(T2), List.of(pool));
    assertEquals(EarlySpawnYard.Reason.NEEDED_BY_OTHERS, lastFree.blocker().orElseThrow().reason());
    assertEquals(T2, lastFree.blocker().orElseThrow().track());
  }

  /** 车库池里没有别的使用时，最后一条空股道也可以用。 */
  @Test
  void aPoolWithoutOtherUsesMayTakeItsLastFreeTrack() {
    assertEquals(
        EarlySpawnYard.Decision.use(T3),
        EarlySpawnYard.choose(List.of(T1, T2, T3), Set.of(T3), List.of()));
  }

  /** 车库池：避开别的车指定要用的股道，挑被最少次使用列为可用的那条。 */
  @Test
  void aPoolAvoidsTracksOthersNeed() {
    EarlySpawnYard.Use fixedOnFirst = departure("A", T1);
    EarlySpawnYard.Use pool = departure("B", T1, T2, T3);

    assertEquals(
        EarlySpawnYard.Decision.use(T3),
        EarlySpawnYard.choose(
            List.of(T1, T2, T3),
            Set.of(T1, T2, T3),
            List.of(fixedOnFirst, pool, departure("C", T2))));
  }

  @Test
  void aPoolWithNoFreeTrackIsRefused() {
    EarlySpawnYard.Decision decision = EarlySpawnYard.choose(List.of(T1, T2), Set.of(), List.of());

    assertEquals(EarlySpawnYard.Reason.NO_FREE_TRACK, decision.blocker().orElseThrow().reason());
  }

  @Test
  void anUnknownYardIsRefused() {
    assertEquals(
        EarlySpawnYard.Reason.YARD_UNKNOWN,
        EarlySpawnYard.choose(List.of(), Set.of(T1), List.of()).blocker().orElseThrow().reason());
  }

  /** 节点写法不分大小写。 */
  @Test
  void tracksCompareCaseInsensitively() {
    EarlySpawnYard.Decision decision =
        EarlySpawnYard.choose(
            List.of("op:d:dep:1"), Set.of(T1), List.of(departure("X", "Op:D:Dep:1")));

    assertEquals(EarlySpawnYard.Reason.NEEDED_BY_OTHERS, decision.blocker().orElseThrow().reason());
  }

  @Test
  void aDecisionHasExactlyOneOfTrackAndBlocker() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EarlySpawnYard.Decision(Optional.empty(), Optional.empty()));
  }
}
