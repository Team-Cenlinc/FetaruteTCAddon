package org.fetarute.fetaruteTCAddon.drive.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Material;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.junit.jupiter.api.Test;

class HotbarItemsTest {

  @Test
  void defaultMaterialsArePlainDyesColouredByNotchKind() {
    assertEquals(Material.LIME_DYE, HotbarItems.materialOf(Notch.P1));
    assertEquals(Material.LIME_DYE, HotbarItems.materialOf(Notch.P3));
    assertEquals(Material.GRAY_DYE, HotbarItems.materialOf(Notch.N));
    assertEquals(Material.ORANGE_DYE, HotbarItems.materialOf(Notch.B1));
    assertEquals(Material.ORANGE_DYE, HotbarItems.materialOf(Notch.B4));
    assertEquals(Material.RED_DYE, HotbarItems.materialOf(Notch.EB));
  }
}
