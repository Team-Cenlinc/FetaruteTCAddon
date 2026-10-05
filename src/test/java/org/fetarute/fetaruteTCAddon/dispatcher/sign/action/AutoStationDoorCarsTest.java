package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.bergerkiller.bukkit.common.entity.type.CommonMinecart;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DoorCars;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("开关门只认选中的车厢")
class AutoStationDoorCarsTest {

  private static MinecartMember<?> member(UUID id) {
    MinecartMember<?> member = mock(MinecartMember.class);
    if (id != null) {
      CommonMinecart<?> entity = mock(CommonMinecart.class);
      doReturn(id).when(entity).getUniqueId();
      doReturn(entity).when(member).getEntity();
    }
    return member;
  }

  @Test
  @DisplayName("door:1 只有第一节开门；全车开门或没有范围时每节都开")
  void filtersMembersByCart() {
    UUID head = UUID.randomUUID();
    UUID tail = UUID.randomUUID();
    MinecartMember<?> first = member(head);
    MinecartMember<?> second = member(tail);
    DoorCars cars =
        DoorCars.select(StopMarkSign.parse("car:2", "door:1").orElseThrow(), List.of(head, tail));

    assertTrue(AutoStationDoorController.opensDoors(cars, first));
    assertFalse(AutoStationDoorController.opensDoors(cars, second));
    assertFalse(AutoStationDoorController.opensDoors(cars, member(null)), "取不到实体的车厢不开");
    assertTrue(AutoStationDoorController.opensDoors(DoorCars.ALL, second));
    assertTrue(AutoStationDoorController.opensDoors(null, second));
  }
}
