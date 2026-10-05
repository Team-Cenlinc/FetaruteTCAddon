package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BrakeBlendTest {

  private static final BlendedBrakeConfig BLEND = BlendedBrakeConfig.defaults();
  private static final double KMH = 1.0 / 3.6;

  @Test
  void theElectricBrakeTakesWhatItCanAndTheAirBrakeTheRest() {
    BrakeBlend.Split full = BrakeBlend.split(1.0, 0.7);
    assertEquals(0.7, full.electric(), 1e-12);
    assertEquals(0.3, full.air(), 1e-12);
    assertEquals(1.0, full.total(), 1e-12);

    BrakeBlend.Split light = BrakeBlend.split(0.25, 0.7);
    assertEquals(0.25, light.electric(), 1e-12, "轻制动全由电制动承担");
    assertEquals(0.0, light.air(), 1e-12);

    BrakeBlend.Split noElectric = BrakeBlend.split(0.75, 0.0);
    assertEquals(0.0, noElectric.electric());
    assertEquals(0.75, noElectric.air(), 1e-12);
  }

  @Test
  void theElectricBrakeFadesOutBelowTheExitSpeed() {
    assertEquals(0.0, BLEND.electricCapacity(10 * KMH), "退出速度以下没有电制动");
    assertEquals(0.0, BLEND.electricCapacity(15 * KMH));
    assertEquals(0.35, BLEND.electricCapacity(17.5 * KMH), 1e-9, "过渡带中间是一半");
    assertEquals(0.7, BLEND.electricCapacity(20 * KMH), 1e-9);
    assertEquals(0.7, BLEND.electricCapacity(80 * KMH), 1e-9);
  }

  @Test
  void theCylinderPressureFollowsTheAirShareSoTheAirAloneReachesItsFractionAtFullPressure() {
    assertEquals(1.0, BrakeBlend.cylinderRatio(1.0, 0.85, 1.0), 1e-12, "单靠空气的 B4 制动缸全压");
    assertEquals(0.3 / 0.85, BrakeBlend.cylinderRatio(0.3, 0.85, 1.0), 1e-12);
    assertEquals(1.4, BrakeBlend.cylinderRatio(1.4, 0.85, 1.4), 1e-12, "紧急制动可超过常用全压");
    assertEquals(0.0, BrakeBlend.cylinderRatio(0.0, 0.85, 1.0));
  }

  @Test
  void theDeliveredScaleIsTheTotalTheBrakesCanGiveOverTheDemand() {
    BrakeBlend.Split blended = BrakeBlend.split(1.0, 0.7);
    assertEquals(1.0, BrakeBlend.deliveredScale(1.0, blended, 0.85), 1e-12, "有电制动时满足全制动");

    BrakeBlend.Split airOnly = BrakeBlend.split(1.0, 0.0);
    assertEquals(0.85, BrakeBlend.deliveredScale(1.0, airOnly, 0.85), 1e-12, "只靠空气时略低");
    assertEquals(1.0, BrakeBlend.deliveredScale(0.5, BrakeBlend.split(0.5, 0.0), 0.85), 1e-12);
    assertEquals(1.0, BrakeBlend.deliveredScale(0.0, BrakeBlend.split(0.0, 0.7), 0.0));
  }

  @Test
  void invalidFractionsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new BlendedBrakeConfig(0.0, 4.0, 0.85));
    assertThrows(IllegalArgumentException.class, () -> new BlendedBrakeConfig(0.7, 4.0, 1.2));
    assertThrows(IllegalArgumentException.class, () -> new BlendedBrakeConfig(0.7, -1.0, 0.85));
  }
}
