package org.fetarute.fetaruteTCAddon.drive.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** 当班体力：生存、冒险模式喂饱、慢慢回血，其他模式与关闭时不动。 */
class CrewSustainTest {

  private static Player player(GameMode mode) {
    Player player = mock(Player.class);
    when(player.getGameMode()).thenReturn(mode);
    when(player.getHealth()).thenReturn(10.0);
    when(player.getFoodLevel()).thenReturn(3);
    when(player.getSaturation()).thenReturn(0.0f);
    return player;
  }

  @Test
  void aHungryPlayerIsFed() {
    Player player = player(GameMode.SURVIVAL);
    CrewSustain.feed(player);
    verify(player).setFoodLevel(20);
    verify(player).setSaturation(5.0f);
  }

  @Test
  void healthRecoversSlowlyAndNeverExceedsTheMaximum() {
    assertEquals(11.0, CrewSustain.healed(10.0, 20.0, 1.0));
    assertEquals(20.0, CrewSustain.healed(19.5, 20.0, 1.0));
  }

  @Test
  void onlySurvivalAndAdventureWhileEnabled() {
    CrewSustain on = new CrewSustain(true, 1.0);
    assertTrue(on.appliesTo(player(GameMode.SURVIVAL)));
    assertTrue(on.appliesTo(player(GameMode.ADVENTURE)));
    assertFalse(on.appliesTo(player(GameMode.CREATIVE)));
    assertFalse(on.appliesTo(player(GameMode.SPECTATOR)));
    assertFalse(new CrewSustain(false, 1.0).appliesTo(player(GameMode.SURVIVAL)));
  }
}
