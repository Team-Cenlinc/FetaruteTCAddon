package org.fetarute.fetaruteTCAddon.display.pids.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsFacing;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.junit.jupiter.api.Test;

class PidsAudienceTest {

  private static final UUID WORLD = UUID.randomUUID();

  private static PidsScreen screen(UUID world, int x, Optional<PidsStationKey> station) {
    return new PidsScreen(
        UUID.randomUUID(),
        world,
        new PidsScreen.Position(x, 64, 0),
        PidsFacing.SOUTH,
        1,
        1,
        "platform-1x3",
        station,
        Set.of(),
        Set.of(),
        PidsScreen.Appearance.AUTO,
        PidsScreen.Mode.LIVE,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  @Test
  void nearestLoadedBoundScreenInTheSameWorldIsTheSource() {
    PidsScreen tpc = screen(WORLD, 10, Optional.of(new PidsStationKey("SURC", "TPC")));
    PidsScreen unloaded = screen(WORLD, 5, Optional.of(new PidsStationKey("SURC", "HHU")));
    PidsScreen unbound = screen(WORLD, 2, Optional.empty());
    PidsScreen elsewhere =
        screen(UUID.randomUUID(), 1, Optional.of(new PidsStationKey("SURC", "SPB")));
    List<PidsScreen> screens = List.of(tpc, unloaded, unbound, elsewhere);

    assertEquals(
        Optional.of(tpc),
        PidsAudience.source(screens, WORLD, 0.5, 64.5, 0.5, 32, screen -> screen != unloaded));
  }

  @Test
  void screensOutOfRangeDoNotCount() {
    PidsScreen far = screen(WORLD, 40, Optional.of(new PidsStationKey("SURC", "TPC")));

    assertEquals(
        Optional.empty(),
        PidsAudience.source(List.of(far), WORLD, 0.5, 64.5, 0.5, 32, screen -> true));
  }
}
