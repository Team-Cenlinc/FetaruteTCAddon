package org.fetarute.fetaruteTCAddon.drive.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReverserPositionTest {

  @Test
  void signsFollowTheDirection() {
    assertEquals(1, ReverserPosition.FORWARD.sign());
    assertEquals(0, ReverserPosition.NEUTRAL.sign());
    assertEquals(-1, ReverserPosition.REVERSE.sign());
  }

  @Test
  void parsesPositionNamesIgnoringCaseAndSpaces() {
    assertEquals(Optional.of(ReverserPosition.FORWARD), ReverserPosition.parse("forward"));
    assertEquals(Optional.of(ReverserPosition.NEUTRAL), ReverserPosition.parse(" Neutral "));
    assertEquals(Optional.of(ReverserPosition.REVERSE), ReverserPosition.parse("REVERSE"));
  }

  @Test
  void rejectsUnknownOrBlankNames() {
    assertTrue(ReverserPosition.parse(null).isEmpty());
    assertTrue(ReverserPosition.parse("").isEmpty());
    assertTrue(ReverserPosition.parse("back").isEmpty());
  }
}
