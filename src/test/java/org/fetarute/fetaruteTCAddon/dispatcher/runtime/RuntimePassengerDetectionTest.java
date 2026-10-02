package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** 运行时真实玩家乘客识别测试。 */
class RuntimePassengerDetectionTest {

  @Test
  void unresolvedTrainPropertiesFailClosed() {
    assertTrue(RuntimeDispatchService.hasPlayerPassengers(null));
  }

  @Test
  void detectsPlayerNestedBelowSeatEntity() {
    Entity minecart = mock(Entity.class);
    Entity seat = mock(Entity.class);
    Player player = mock(Player.class);
    when(minecart.getPassengers()).thenReturn(List.of(seat));
    when(seat.getPassengers()).thenReturn(List.of(player));

    assertTrue(RuntimeDispatchService.hasPlayerPassengersInEntities(List.of(minecart)));
  }

  @Test
  void emptyValidTrainHasNoPlayerPassengers() {
    Entity minecart = mock(Entity.class);
    when(minecart.getPassengers()).thenReturn(List.of());

    assertFalse(RuntimeDispatchService.hasPlayerPassengersInEntities(List.of(minecart)));
  }

  @Test
  void unreadablePassengerTreeFailsClosed() {
    Entity minecart = mock(Entity.class);
    when(minecart.getPassengers()).thenThrow(new IllegalStateException("entity unavailable"));

    assertTrue(RuntimeDispatchService.hasPlayerPassengersInEntities(List.of(minecart)));
  }
}
