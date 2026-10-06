package org.fetarute.fetaruteTCAddon.display.pids.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen.Position;
import org.junit.jupiter.api.Test;

/** 屏幕几何：站在屏幕前看，列向右排开、行向下排开。 */
class PidsScreenTest {

  private static PidsScreen screen(PidsFacing facing, int rows, int cols) {
    Instant now = Instant.EPOCH;
    return new PidsScreen(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new Position(10, 64, 20),
        facing,
        rows,
        cols,
        "platform-1x3",
        Optional.empty(),
        Set.of(),
        Set.of(),
        PidsScreen.Appearance.AUTO,
        PidsScreen.Mode.TEST_CARD,
        now,
        now);
  }

  @Test
  void layoutsKeepTheirOrderWithoutBlanksOrRepeats() {
    PidsScreen screen =
        screen(PidsFacing.SOUTH, 3, 5)
            .withLayouts(
                List.of(" station-3x5", "status-3x5", "", "station-3x5", "custom"), Instant.EPOCH);

    assertEquals(List.of("station-3x5", "status-3x5", "custom"), screen.layoutIds());
    assertEquals("station-3x5", screen.layoutId());
    assertEquals(List.of("status-3x5", "custom"), screen.pageLayoutIds());
    assertEquals(List.of("status-3x5"), screen.withLayout("status-3x5", Instant.EPOCH).layoutIds());
    assertTrue(screen(PidsFacing.SOUTH, 1, 3).pageLayoutIds().isEmpty());
    assertEquals(
        List.of("", "status-3x5"),
        screen.withLayouts(List.of(" ", "status-3x5"), Instant.EPOCH).layoutIds(),
        "主布局空白也保留（按不存在处理、退回内置布局），不让一行旧数据读不进来");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PidsScreen(
                screen.id(),
                screen.worldId(),
                screen.anchor(),
                screen.facing(),
                3,
                5,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Set.of(),
                PidsScreen.Appearance.AUTO,
                PidsScreen.Mode.TEST_CARD,
                Instant.EPOCH,
                Instant.EPOCH),
        "至少一个布局");
  }

  @Test
  void columnsRunToTheViewersRight() {
    // 面朝南的屏幕，观众面朝北站着，右手是东（+x）
    assertEquals(new Position(12, 64, 20), screen(PidsFacing.SOUTH, 1, 3).frameAt(0, 2));
    assertEquals(new Position(8, 64, 20), screen(PidsFacing.NORTH, 1, 3).frameAt(0, 2));
    assertEquals(new Position(10, 64, 18), screen(PidsFacing.EAST, 1, 3).frameAt(0, 2));
    assertEquals(new Position(10, 64, 22), screen(PidsFacing.WEST, 1, 3).frameAt(0, 2));
  }

  @Test
  void framesAreRowMajorFromTheTopLeft() {
    List<Position> frames = screen(PidsFacing.SOUTH, 2, 2).frames();

    assertEquals(
        List.of(
            new Position(10, 64, 20),
            new Position(11, 64, 20),
            new Position(10, 63, 20),
            new Position(11, 63, 20)),
        frames);
  }

  @Test
  void onlyWallFacingsAreSupported() {
    assertEquals(Optional.of(PidsFacing.EAST), PidsFacing.of(BlockFace.EAST));
    assertTrue(PidsFacing.of(BlockFace.UP).isEmpty());
    assertTrue(PidsFacing.of(BlockFace.NORTH_EAST).isEmpty());
    assertEquals(BlockFace.WEST, PidsFacing.WEST.blockFace());
  }

  @Test
  void toggleAddsRemovesAndClearsWithAll() {
    assertEquals(Set.of("1", "4"), PidsScreen.toggle(Set.of("1"), "4"));
    assertEquals(Set.of("1"), PidsScreen.toggle(Set.of("1", "4"), "4"));
    assertEquals(Set.of(), PidsScreen.toggle(Set.of("1", "4"), "ALL"));
  }

  @Test
  void centerIsTheMiddleTile() {
    assertEquals(new Position(11, 64, 20), screen(PidsFacing.SOUTH, 1, 3).center());
    assertEquals(new Position(12, 63, 20), screen(PidsFacing.SOUTH, 3, 5).center());
  }

  @Test
  void rejectsEmptySizes() {
    assertThrows(IllegalArgumentException.class, () -> screen(PidsFacing.SOUTH, 0, 3));
  }

  @Test
  void operatorOnlyBindingIsNormalisedAndYieldsToAStation() {
    PidsScreen bare = screen(PidsFacing.SOUTH, 3, 5);
    assertEquals(Optional.empty(), bare.operatorCode());

    PidsScreen operatorOnly = bare.withOperator(" surc ", Set.of("MT"), Instant.EPOCH);
    assertEquals(Optional.empty(), operatorOnly.station());
    assertEquals(Optional.of("SURC"), operatorOnly.operator());
    assertEquals(Optional.of("SURC"), operatorOnly.operatorCode());
    assertTrue(operatorOnly.platforms().isEmpty());

    PidsScreen stationBound =
        operatorOnly.withBinding(
            Optional.of(new PidsStationKey("OFL", "HHU")), Set.of("1"), Set.of(), Instant.EPOCH);
    assertEquals(Optional.empty(), stationBound.operator(), "绑了车站就不再单独记运营商");
    assertEquals(Optional.of("OFL"), stationBound.operatorCode());
  }
}
