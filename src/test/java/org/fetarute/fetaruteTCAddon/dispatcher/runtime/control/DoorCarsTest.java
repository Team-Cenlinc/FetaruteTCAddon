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
    assertTrue(DoorCars.select(sign(""), train).all());
    assertTrue(DoorCars.select(sign("door:*"), train).all());
    assertTrue(DoorCars.select(sign(""), train).includes(UUID.randomUUID()));
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
  @DisplayName("停站中挂上来的车厢：没有标志时都开；标志对得上整列节数时照它选；只对得上先到那列的节数时不开")
  void joinedCarsFollowTheSameMark() {
    List<UUID> trunk = List.of(first, second);
    java.util.Set<UUID> joined = java.util.Set.of(third, fourth);

    DoorCars all = DoorCars.ALL.joined(train, joined);
    assertFalse(all.all(), "只含挂上来的那几节");
    assertTrue(all.includes(third));
    assertTrue(all.includes(fourth));
    assertFalse(all.includes(first), "原来那几节的门已经开着");

    DoorCars marked = DoorCars.select(sign("door:1-3"), trunk).joined(train, joined);
    assertTrue(marked.includes(third), "第三节在 door:1-3 里");
    assertFalse(marked.includes(fourth), "第四节在站台外");
    assertFalse(marked.includes(first));

    StopMarkSign forTwo = StopMarkSign.parse("car:2", "").orElseThrow();
    DoorCars ownLength = DoorCars.select(forTwo, trunk).joined(train, joined);
    assertFalse(ownLength.includes(third), "标志是两节车的：挂上后四节，后两节多半在站台外");
    StopMarkSign forFour = StopMarkSign.parse("car:4", "").orElseThrow();
    assertTrue(DoorCars.select(forFour, trunk).joined(train, joined).includes(fourth));

    assertFalse(DoorCars.only(first).joined(train, joined).includes(third), "只有一节的不扩");
  }

  @Test
  @DisplayName("并上挂上来的车厢：按标志选的取并集，全车开门的还是全车")
  void plusJoinedCars() {
    DoorCars front = DoorCars.select(sign("door:1"), train);
    DoorCars more =
        DoorCars.select(sign("door:1-3"), List.of(first, second))
            .joined(train, java.util.Set.of(third, fourth));
    DoorCars union = front.plus(more);
    assertTrue(union.includes(first));
    assertTrue(union.includes(third));
    assertFalse(union.includes(second));
    assertFalse(union.includes(fourth));
    assertSame(DoorCars.ALL, DoorCars.ALL.plus(more));

    DoorCars trunkOnly = DoorCars.ALL.restrictTo(java.util.Set.of(first, second));
    assertFalse(trunkOnly.all());
    assertTrue(trunkOnly.includes(second));
    assertFalse(trunkOnly.includes(third), "全车开门改成明确的几节：没开门的挂上来的车厢不在内");
    assertTrue(trunkOnly.plus(more).includes(third));
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
