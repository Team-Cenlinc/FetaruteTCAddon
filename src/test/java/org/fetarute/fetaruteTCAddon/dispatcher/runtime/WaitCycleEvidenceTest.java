package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** 等待环复审证据：挡路的车必须全在环里，等待才算由环解释。 */
class WaitCycleEvidenceTest {

  @Test
  void blockersInsideTheCycleExplainTheWait() {
    assertTrue(WaitCycleEvidence.explainsWait(Set.of("B", " c "), Set.of("a", "b", "c")));
  }

  @Test
  void aBlockerOutsideTheCycleKeepsTheTrainWaiting() {
    assertFalse(WaitCycleEvidence.explainsWait(Set.of("b", "parked"), Set.of("a", "b", "c")));
  }

  /** 复审口径：等待由环解释时不算在等，即使仍在排队；否则有 blocker 或仍在排队就算。 */
  @Test
  void waitingOnLiveBlockerFollowsTheCycleExceptionThenBlockersThenQueue() {
    assertFalse(WaitCycleEvidence.waitingOnLiveBlocker(Set.of("b"), true, Set.of("a", "b")));
    assertTrue(WaitCycleEvidence.waitingOnLiveBlocker(Set.of("b"), false, Set.of()));
    assertTrue(WaitCycleEvidence.waitingOnLiveBlocker(Set.of(), true, Set.of("a", "b")));
    assertFalse(WaitCycleEvidence.waitingOnLiveBlocker(Set.of(), false, Set.of()));
  }

  @Test
  void noKnownBlockerOrNoCycleNeverExplainsTheWait() {
    assertFalse(WaitCycleEvidence.explainsWait(Set.of(), Set.of("a", "b")));
    assertFalse(WaitCycleEvidence.explainsWait(Set.of("b"), Set.of()));
    assertFalse(WaitCycleEvidence.explainsWait(null, Set.of("a", "b")));
  }
}
