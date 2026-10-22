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

  @Test
  @DisplayName("只写节数没写 door: 的标志：照样全车开门，但停站中挂上来的车厢按这块标志判，节数对不上整列时不开")
  void markWithoutDoorSpecStillJudgesJoinedCars() {
    UUID trunk = UUID.randomUUID();
    UUID joined = UUID.randomUUID();
    List<UUID> merged = List.of(trunk, joined);

    DoorCars ownLength =
        AutoStationDoorController.doorCars(null, StopMarkSign.parse("car:1", "").orElseThrow());
    assertTrue(ownLength.all(), "停在一节车的标志：主干全车开门");
    assertFalse(
        ownLength.joined(merged, java.util.Set.of(joined)).includes(joined),
        "挂上后两节，标志是一节车的：挂上来的那节多半在站台外");

    DoorCars wholeTrain =
        AutoStationDoorController.doorCars(null, StopMarkSign.parse("car:2", "").orElseThrow());
    assertTrue(
        wholeTrain.joined(merged, java.util.Set.of(joined)).includes(joined),
        "按整列节数的标志停好：挂上来的那节开门");

    assertTrue(
        AutoStationDoorController.doorCars(null, null)
            .joined(merged, java.util.Set.of(joined))
            .includes(joined),
        "没有标志（按车站牌子居中）：都开");
  }
}
