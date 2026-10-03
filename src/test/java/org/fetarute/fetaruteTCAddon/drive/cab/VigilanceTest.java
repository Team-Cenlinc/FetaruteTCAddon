package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VigilanceTest {

  /** 100 tick 不操作就报警，报警 40 tick 后紧急制动。 */
  private static Vigilance vigilance() {
    return new Vigilance(100, 40, 0);
  }

  private static List<Vigilance.Event> run(Vigilance v, long from, long to, boolean moving) {
    List<Vigilance.Event> events = new ArrayList<>();
    for (long t = from; t <= to; t++) {
      Vigilance.Event event = v.tick(t, moving);
      if (event != Vigilance.Event.NONE) {
        events.add(event);
      }
    }
    return events;
  }

  @Test
  void warnsThenTripsWhenNobodyAcknowledges() {
    Vigilance v = vigilance();

    assertTrue(run(v, 1, 99, true).isEmpty());
    assertEquals(
        List.of(Vigilance.Event.WARNING_STARTED, Vigilance.Event.WARNING_SECOND),
        run(v, 100, 139, true));
    assertTrue(v.warning(139));
    assertEquals(1, v.warningRemainingSeconds(139));
    assertEquals(List.of(Vigilance.Event.TRIPPED), run(v, 140, 200, true));
    assertTrue(v.tripped());
    assertFalse(v.warning(200));
  }

  @Test
  void acknowledgingRestartsTheTimer() {
    Vigilance v = vigilance();
    run(v, 1, 110, true);
    assertTrue(v.warning(110));

    v.acknowledge(110);

    assertFalse(v.warning(111));
    assertTrue(run(v, 111, 209, true).isEmpty());
  }

  @Test
  void acknowledgingDoesNotLiftATripButStoppingDoes() {
    Vigilance v = vigilance();
    run(v, 1, 150, true);
    v.acknowledge(151);
    assertTrue(v.tripped());

    v.tick(152, false);

    assertFalse(v.tripped());
  }

  @Test
  void aStoppedTrainNeverWarns() {
    Vigilance v = vigilance();

    assertTrue(run(v, 1, 1000, false).isEmpty());
  }

  @Test
  void countsDownToTheWarning() {
    Vigilance v = vigilance();
    run(v, 1, 30, true);

    assertEquals(4, v.secondsUntilWarning(30), "100 tick 报警，还剩 70 tick");
    run(v, 31, 120, true);
    assertEquals(0, v.secondsUntilWarning(120));
  }
}
