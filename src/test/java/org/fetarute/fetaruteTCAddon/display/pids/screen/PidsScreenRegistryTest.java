package org.fetarute.fetaruteTCAddon.display.pids.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen.Position;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreenRegistry.FrameKey;
import org.junit.jupiter.api.Test;

/** 屏幕表：每个展示框位置都能查回屏幕，更新与移除同步维护位置索引。 */
class PidsScreenRegistryTest {

  private static final UUID WORLD = UUID.randomUUID();

  private static PidsScreen screen(int x, int createdSecond) {
    Instant at = Instant.ofEpochSecond(createdSecond);
    return new PidsScreen(
        UUID.randomUUID(),
        WORLD,
        new Position(x, 64, 0),
        PidsFacing.SOUTH,
        1,
        3,
        "platform-1x3",
        Optional.empty(),
        Set.of(),
        Set.of(),
        PidsScreen.Appearance.AUTO,
        PidsScreen.Mode.TEST_CARD,
        at,
        at);
  }

  private static FrameKey frame(int x) {
    return new FrameKey(WORLD, new Position(x, 64, 0), PidsFacing.SOUTH);
  }

  @Test
  void everyFrameMapsBackToItsScreen() {
    PidsScreenRegistry registry = new PidsScreenRegistry();
    PidsScreen screen = screen(0, 1);

    registry.put(screen);

    for (int x = 0; x < 3; x++) {
      assertEquals(Optional.of(screen), registry.findByFrame(frame(x)));
    }
    assertTrue(registry.findByFrame(frame(3)).isEmpty());
    assertTrue(
        registry
            .findByFrame(new FrameKey(WORLD, new Position(0, 64, 0), PidsFacing.NORTH))
            .isEmpty(),
        "同一方块的另一面不是这块屏幕");
  }

  @Test
  void removalClearsTheFrameIndex() {
    PidsScreenRegistry registry = new PidsScreenRegistry();
    PidsScreen screen = screen(0, 1);
    registry.put(screen);

    assertEquals(Optional.of(screen), registry.remove(screen.id()));

    assertTrue(registry.findByFrame(frame(1)).isEmpty());
    assertEquals(0, registry.size());
  }

  @Test
  void replaceAllKeepsCreationOrder() {
    PidsScreenRegistry registry = new PidsScreenRegistry();
    PidsScreen later = screen(10, 5);
    PidsScreen earlier = screen(0, 1);

    registry.replaceAll(List.of(later, earlier));

    assertEquals(List.of(earlier, later), registry.all());
  }
}
