package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LayoverDispatchResultTest {

  @Test
  void missingCandidateUsesExplicitEmptyOwner() {
    LayoverDispatchResult result = LayoverDispatchResult.failed("missing-candidate-or-ticket");

    assertEquals(Optional.empty(), result.trainName());
  }

  @Test
  void successfulDispatchRequiresCommittedOwner() {
    assertThrows(IllegalArgumentException.class, () -> LayoverDispatchResult.success(" "));
  }
}
