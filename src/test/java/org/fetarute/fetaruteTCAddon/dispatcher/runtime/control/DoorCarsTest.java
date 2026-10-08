package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("停站开门的车厢")
class DoorCarsTest {

  private final UUID first = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();
  private final UUID third = UUID.randomUUID();
  private final UUID fourth = UUID.randomUUID();
  private final List<UUID> train = List.of(first, second, third, fourth);

  private static StopMarkSign sign(String doors) {
    return StopMarkSign.parse("car:*", doors).orElseThrow();
  }

  @Test
  @DisplayName("没有停车位置标或没写 door: 时全车开门")
  void allWithoutDoorSpec() {
    assertSame(DoorCars.ALL, DoorCars.select(null, train));
    assertSame(DoorCars.ALL, DoorCars.select(sign(""), train));
    assertSame(DoorCars.ALL, DoorCars.select(sign("door:*"), train));
    assertTrue(DoorCars.ALL.all());
    assertTrue(DoorCars.ALL.includes(UUID.randomUUID()));
  }

  @Test
  @DisplayName("door:1 只开行进方向上的第一节")
  void firstCarOnly() {
    DoorCars cars = DoorCars.select(sign("door:1"), train);
    assertFalse(cars.all());
    assertTrue(cars.includes(first));
    assertFalse(cars.includes(second));
    assertFalse(cars.includes(fourth));
    assertFalse(cars.includes(null));
  }

  @Test
  @DisplayName("记下的是车厢本身：整列调头后还是原来那几节")
  void keepsCartsAcrossReversal() {
    DoorCars cars = DoorCars.select(sign("door:1-2"), train);
    List<UUID> reversed = List.of(fourth, third, second, first);
    for (UUID cart : reversed) {
      assertEquals(cart.equals(first) || cart.equals(second), cars.includes(cart));
    }
  }

  @Test
  @DisplayName("取不到实体的车厢不开门，其余照选")
  void skipsCartsWithoutEntity() {
    List<UUID> partial = Arrays.asList(first, null, third, fourth);
    DoorCars cars = DoorCars.select(sign("door:1-3"), partial);
    assertTrue(cars.includes(first));
    assertTrue(cars.includes(third));
    assertFalse(cars.includes(null));
    assertFalse(cars.includes(fourth));
  }

  @Test
  @DisplayName("只含一节车厢：逐节开门时每节车单独播动画")
  void onlyOneCar() {
    DoorCars only = DoorCars.only(second);

    assertFalse(only.all());
    assertTrue(only.includes(second));
    assertFalse(only.includes(first));
    assertFalse(DoorCars.only(null).includes(first));
  }
}
