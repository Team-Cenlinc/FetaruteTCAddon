package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ForceBarTest {

  @Test
  void tractionFillsGreenCells() {
    assertEquals(new ForceBar.Bar(ForceBar.Kind.TRACTION, 10), ForceBar.of(1.0));
    assertEquals(new ForceBar.Bar(ForceBar.Kind.TRACTION, 3), ForceBar.of(0.33));
  }

  @Test
  void serviceBrakingFillsBrakeCells() {
    assertEquals(new ForceBar.Bar(ForceBar.Kind.BRAKE, 5), ForceBar.of(-0.5));
    assertEquals(new ForceBar.Bar(ForceBar.Kind.BRAKE, 10), ForceBar.of(-1.0));
  }

  @Test
  void emergencyBrakingFillsTheWholeBar() {
    assertEquals(new ForceBar.Bar(ForceBar.Kind.EMERGENCY, ForceBar.CELLS), ForceBar.of(-1.4));
  }

  @Test
  void coastingAndJunkShowAnEmptyBar() {
    assertEquals(new ForceBar.Bar(ForceBar.Kind.NONE, 0), ForceBar.of(0.0));
    assertEquals(new ForceBar.Bar(ForceBar.Kind.NONE, 0), ForceBar.of(Double.NaN));
  }
}
