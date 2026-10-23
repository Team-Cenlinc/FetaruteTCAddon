package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.coupler.DecoupleCrew;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.junit.jupiter.api.Test;

/** 分行后选继续哪一段：结算、限时选择、改驾三步的状态，以及期间两段车扣到几点。 */
class SplitChoicesTest {

  private static final UUID PLAYER = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
  private static final TaskKey TRIP =
      new TaskKey(UUID.randomUUID(), "1201", LocalDate.of(2026, 10, 15));
  private static final DecoupleCrew.Split SPLIT =
      new DecoupleCrew.Split("WS-1", "WS-1~ab12", "前 4 节，开往南渡", "后 4 节，开往大港城");

  @Test
  void readyIsPerTrainAndCarriesTheSettledTripIntoTheChoice() {
    SplitChoices choices = new SplitChoices();
    choices.markReady(PLAYER, new SplitChoices.Ready("WS-1", Optional.of(TRIP)));

    assertTrue(choices.readyFor(PLAYER, "ws-1"));
    assertFalse(choices.readyFor(PLAYER, "WS-2"));

    SplitChoices.Choice choice = choices.open(PLAYER, SPLIT, NOW);
    assertEquals(Optional.of(TRIP), choice.resume());
    assertEquals(NOW.plus(SplitChoices.WINDOW), choice.deadline());
    assertFalse(choices.readyFor(PLAYER, "WS-1"), "拆开后结算记录已用掉");
    assertTrue(choices.choosing(PLAYER));
  }

  @Test
  void bothPartsAreHeldUntilTheDeadlineWhileChoosing() {
    SplitChoices choices = new SplitChoices();
    choices.open(PLAYER, SPLIT, NOW);

    assertEquals(Optional.of(NOW.plus(SplitChoices.WINDOW)), choices.holding("WS-1"));
    assertEquals(Optional.of(NOW.plus(SplitChoices.WINDOW)), choices.holding("ws-1~AB12"));
    assertEquals(Optional.empty(), choices.holding("WS-2"));

    choices.closeChoice(PLAYER);
    assertEquals(Optional.empty(), choices.holding("WS-1"));
    assertEquals(List.of(), choices.choosers());
  }

  @Test
  void switchHoldsOnlyTheTargetAndExpires() {
    SplitChoices choices = new SplitChoices();
    Instant deadline = NOW.plus(SplitChoices.SWITCH_WINDOW);
    choices.beginSwitch(
        PLAYER,
        new SplitChoices.Switch(
            "WS-1~ab12", "后 4 节", Optional.of(TRIP), false, deadline, false, false));

    assertTrue(choices.switchTo(PLAYER, "WS-1~AB12").isPresent());
    assertTrue(choices.switchTo(PLAYER, "WS-1").isEmpty());
    assertEquals(Optional.of(deadline), choices.holding("WS-1~ab12"));
    assertEquals(Optional.empty(), choices.holding("WS-1"));

    choices.pruneSwitches(deadline.minusSeconds(1));
    assertTrue(choices.switchTo(PLAYER, "WS-1~ab12").isPresent());
    choices.pruneSwitches(deadline);
    assertTrue(choices.switchTo(PLAYER, "WS-1~ab12").isEmpty());
  }

  @Test
  void endingTheSessionDropsReadyAndChoiceButKeepsASwitchInProgress() {
    SplitChoices choices = new SplitChoices();
    choices.markReady(PLAYER, new SplitChoices.Ready("WS-1", Optional.empty()));
    choices.open(PLAYER, SPLIT, NOW);
    choices.closeChoice(PLAYER);
    choices.beginSwitch(
        PLAYER,
        new SplitChoices.Switch(
            "WS-1~ab12",
            "",
            Optional.empty(),
            true,
            NOW.plus(SplitChoices.SWITCH_WINDOW),
            false,
            true));
    choices.markReady(PLAYER, new SplitChoices.Ready("WS-1", Optional.empty()));

    choices.forget(PLAYER);

    assertFalse(choices.readyFor(PLAYER, "WS-1"));
    assertFalse(choices.choosing(PLAYER));
    assertTrue(choices.switchTo(PLAYER, "WS-1~ab12").isPresent(), "改驾本身要先结束原来那一段的驾驶");
  }

  @Test
  void parsesOptionsCaseInsensitively() {
    assertEquals(Optional.of(SplitChoices.Option.KEEP), SplitChoices.Option.parse(" Keep "));
    assertEquals(Optional.of(SplitChoices.Option.SWITCH), SplitChoices.Option.parse("SWITCH"));
    assertEquals(Optional.of(SplitChoices.Option.END), SplitChoices.Option.parse("end"));
    assertEquals(Optional.empty(), SplitChoices.Option.parse("stay"));
    assertEquals(Optional.empty(), SplitChoices.Option.parse(null));
  }
}
