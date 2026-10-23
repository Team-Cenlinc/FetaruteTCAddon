package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 合起驾驶员与车掌：有一方值乘即算有人，各方都结算过才拆，扣留取最晚的期限，每次调用再取各方此刻的实现。 */
class CrewCompositionTest {

  private static final Instant T0 = Instant.parse("2026-10-15T08:00:00Z");

  private static final class FakeCrew implements DecoupleCrew {
    boolean crewed;
    boolean ready = true;
    Optional<Instant> holding = Optional.empty();
    final List<String> calls = new ArrayList<>();

    @Override
    public boolean crewed(String trainName) {
      return crewed;
    }

    @Override
    public boolean readyToSplit(String trainName) {
      return ready;
    }

    @Override
    public void split(Split split) {
      calls.add("split " + split.keptName());
    }

    @Override
    public void notSplit(String trainName) {
      calls.add("not " + trainName);
    }

    @Override
    public Optional<Instant> holding(String trainName) {
      return holding;
    }
  }

  @Test
  void anyCrewMakesTheTrainCrewedAndAllMustBeReady() {
    FakeCrew driver = new FakeCrew();
    FakeCrew guard = new FakeCrew();
    DecoupleCrew both = DecoupleCrew.all(List.of(() -> driver, () -> guard));

    assertFalse(both.crewed("A"));
    guard.crewed = true;
    assertTrue(both.crewed("A"));

    guard.ready = false;
    assertFalse(both.readyToSplit("A"));
    guard.ready = true;
    assertTrue(both.readyToSplit("A"));
  }

  @Test
  void holdsUntilTheLatestDeadlineAndNotifiesEveryone() {
    FakeCrew driver = new FakeCrew();
    FakeCrew guard = new FakeCrew();
    DecoupleCrew both = DecoupleCrew.all(List.of(() -> driver, () -> guard));

    assertEquals(Optional.empty(), both.holding("A"));
    driver.holding = Optional.of(T0.plusSeconds(60));
    guard.holding = Optional.of(T0.plusSeconds(15));
    assertEquals(Optional.of(T0.plusSeconds(60)), both.holding("A"));

    both.split(new DecoupleCrew.Split("A", "B", "", ""));
    both.notSplit("A");
    assertEquals(List.of("split A", "not A"), driver.calls);
    assertEquals(List.of("split A", "not A"), guard.calls);
  }

  @Test
  void resolvesThePartsOnEveryCall() {
    AtomicReference<DecoupleCrew> current = new AtomicReference<>(DecoupleCrew.NONE);
    DecoupleCrew live = DecoupleCrew.all(List.of(current::get));
    assertFalse(live.crewed("A"));

    FakeCrew reloaded = new FakeCrew();
    reloaded.crewed = true;
    current.set(reloaded);
    assertTrue(live.crewed("A"), "驾驶模块重载后用新的实现");
  }

  @Test
  void noneIsReadyAndHoldsNothing() {
    assertFalse(DecoupleCrew.NONE.crewed("A"));
    assertTrue(DecoupleCrew.NONE.readyToSplit("A"));
    assertEquals(Optional.empty(), DecoupleCrew.NONE.holding("A"));
  }

  @Test
  void coupleCrewForwardsToEveryPartAndKnowsWhoMoves() {
    List<String> calls = new ArrayList<>();
    CoupleCrew part =
        new CoupleCrew() {
          @Override
          public void yieldBeforeApproach(Approach approach) {
            calls.add("yield " + approach.moverName());
          }

          @Override
          public void coupled(Approach approach) {
            calls.add("coupled " + approach.trunkName());
          }

          @Override
          public void notCoupled(Approach approach) {
            calls.add("not " + approach.feederName());
          }
        };
    CoupleCrew both = CoupleCrew.all(List.of(() -> part, () -> CoupleCrew.NONE, () -> part));
    CoupleCrew.Approach approach = new CoupleCrew.Approach("T", "F", "t");

    both.yieldBeforeApproach(approach);
    both.coupled(approach);
    both.notCoupled(approach);

    assertTrue(approach.trunkMoves());
    assertFalse(new CoupleCrew.Approach("T", "F", "F").trunkMoves());
    assertEquals(List.of("yield t", "yield t", "coupled T", "coupled T", "not F", "not F"), calls);
  }
}
