package org.fetarute.fetaruteTCAddon.display.pids.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.junit.jupiter.api.Test;

/** 站台变更按“车 + 车站”记：折返站上站牌列的是这辆车接着开的下一趟（另一条交路），进站前的变更对那一趟同样成立。 */
class PidsPlatformChangesTest {

  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
  private static final PidsStationKey PPK = new PidsStationKey("SURN", "PPK");

  @Test
  void aChangeIsFoundByTrainAndStationWhicheverRouteTheRowBelongsTo() {
    PidsPlatformChanges changes = new PidsPlatformChanges();

    changes.record("Train-1", "SURN:S:PPK:1", "2", "1", NOW);

    assertEquals(Optional.of("2"), changes.previousOf("train-1", PPK, "1"));
    assertTrue(changes.previousOf("train-1", PPK, "2").isEmpty(), "站牌上已不是改到的站台：不标");
    assertTrue(
        changes.previousOf("train-1", new PidsStationKey("SURN", "AAA"), "1").isEmpty(), "别的车站不标");
  }

  @Test
  void depotTracksAndExpiredChangesAreNotKept() {
    PidsPlatformChanges changes = new PidsPlatformChanges();

    changes.record("train-1", "SURN:D:PPK:3", "1", "3", NOW);
    assertTrue(changes.find("train-1", PPK).isEmpty(), "车库股道不是站台");

    changes.record("train-1", "SURN:S:PPK:1", "2", "1", NOW);
    changes.record(
        "train-2",
        "SURN:S:PPK:2",
        "1",
        "2",
        NOW.plus(PidsPlatformChanges.RETENTION).plusSeconds(1));
    assertTrue(changes.find("train-1", PPK).isEmpty(), "过期即删");
  }
}
