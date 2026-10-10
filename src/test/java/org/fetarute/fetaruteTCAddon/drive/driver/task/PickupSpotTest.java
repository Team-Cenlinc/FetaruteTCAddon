package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("前往接车的落脚处")
class PickupSpotTest {

  @Test
  @DisplayName("优先车头侧面两格，不落在轨道方向上")
  void prefersTheSideOfTheHeadCar() {
    Optional<Vector> spot =
        PickupSpot.find(
            new Vector(10.5, 64.0, 20.5), new Vector(1.0, 0.0, 0.0), (x, y, z) -> y == 64);

    assertTrue(spot.isPresent());
    assertEquals(10.5, spot.get().getX(), 1.0e-9);
    assertEquals(64.0, spot.get().getY(), 1.0e-9);
    assertEquals(2.0, Math.abs(spot.get().getZ() - 20.5), 1.0e-9);
  }

  @Test
  @DisplayName("站台在哪一侧已知时只在那一侧找：另一侧（轨道边）站得住也不去")
  void onlyThePlatformSide() {
    // 两侧都站得住；站台在 +z 一侧。
    Optional<Vector> spot =
        PickupSpot.findOnSide(
            new Vector(10.5, 64.0, 20.5), new Vector(0.0, 0.0, 1.0), (x, y, z) -> y == 64);

    assertEquals(new Vector(10.5, 64.0, 22.5), spot.orElseThrow());
    assertTrue(
        PickupSpot.findOnSide(
                new Vector(10.5, 64.0, 20.5), new Vector(0.0, 0.0, 1.0), (x, y, z) -> z < 20)
            .isEmpty(),
        "这一侧站不住时为空，由调用方退回两侧都试");
    assertTrue(
        PickupSpot.findOnSide(new Vector(0, 64, 0), null, (x, y, z) -> true).isEmpty(),
        "不知道站台在哪一侧时为空");
  }

  @Test
  @DisplayName("同一高度站不住时上下各试一格")
  void triesOneBlockUpAndDown() {
    Optional<Vector> spot =
        PickupSpot.find(
            new Vector(0.5, 64.0, 0.5), new Vector(0.0, 0.0, 1.0), (x, y, z) -> y == 65 && x < 0);

    assertEquals(new Vector(-1.5, 65.0, 0.5), spot.orElseThrow());
  }

  @Test
  @DisplayName("走向不明时四个方向都试；都站不住时为空")
  void unknownTravelTriesAllFourDirectionsAndMayFail() {
    assertTrue(
        PickupSpot.find(new Vector(0.5, 64.0, 0.5), null, (x, y, z) -> z < -1 && y == 64)
            .isPresent());
    assertTrue(
        PickupSpot.find(new Vector(0.5, 64.0, 0.5), new Vector(), (x, y, z) -> false).isEmpty());
  }
}
