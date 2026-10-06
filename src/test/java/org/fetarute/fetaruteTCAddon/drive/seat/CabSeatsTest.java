package org.fetarute.fetaruteTCAddon.drive.seat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶室座位认定")
class CabSeatsTest {

  @Test
  @DisplayName("没有标记：前半列车算车头端，最后一节算车尾端，中间偏后的车厢不算驾驶室")
  void unmarkedUsesCarPosition() {
    CabSeats seats = CabSeats.unmarked(6);

    assertFalse(seats.marked());
    assertEquals(CabSeats.End.HEAD, seats.endOf(0, 0));
    assertEquals(CabSeats.End.HEAD, seats.endOf(2, 3));
    assertEquals(CabSeats.End.NONE, seats.endOf(3, 0));
    assertEquals(CabSeats.End.NONE, seats.endOf(4, 0));
    assertEquals(CabSeats.End.TAIL, seats.endOf(5, 1));
  }

  @Test
  @DisplayName("没有标记的两节编组：一节一端")
  void unmarkedTwoCars() {
    CabSeats seats = CabSeats.unmarked(2);

    assertEquals(CabSeats.End.HEAD, seats.endOf(0, 0));
    assertEquals(CabSeats.End.TAIL, seats.endOf(1, 0));
  }

  @Test
  @DisplayName("有标记：只有端车上被标记的座位算驾驶室")
  void markedSeatsOnlyOnEndCars() {
    CabSeats seats =
        CabSeats.of(List.of(Set.of(0), Set.of(), Set.of(2), Set.of(), Set.of(), Set.of(1)));

    assertTrue(seats.marked());
    assertEquals(CabSeats.End.HEAD, seats.endOf(0, 0));
    assertEquals(CabSeats.End.NONE, seats.endOf(0, 1), "端车上没标记的座位是客室");
    assertEquals(CabSeats.End.NONE, seats.endOf(2, 2), "中间车上的标记不算驾驶室");
    assertEquals(CabSeats.End.NONE, seats.endOf(1, 0), "有标记时不再按前半列车认定");
    assertEquals(CabSeats.End.TAIL, seats.endOf(5, 1));
    assertEquals(CabSeats.End.NONE, seats.endOf(5, 0));
  }

  @Test
  @DisplayName("单节车：驾驶室总算车头端")
  void singleCarIsAlwaysHead() {
    assertEquals(CabSeats.End.HEAD, CabSeats.unmarked(1).endOf(0, 0));
    CabSeats marked = CabSeats.of(List.of(Set.of(0, 3)));
    assertEquals(CabSeats.End.HEAD, marked.endOf(0, 3));
    assertEquals(CabSeats.End.NONE, marked.endOf(0, 1));
  }

  @Test
  @DisplayName("越界与空座位按非驾驶室处理")
  void outOfRange() {
    CabSeats seats = CabSeats.unmarked(4);

    assertEquals(CabSeats.End.NONE, seats.endOf(4, 0));
    assertEquals(CabSeats.End.NONE, seats.endOf(-1, 0));
    assertEquals(CabSeats.End.NONE, seats.endOf((SeatBinding) null));
    assertEquals(CabSeats.End.TAIL, seats.endOf(new SeatBinding("T", 3, 0)));
  }

  @Test
  @DisplayName("座位所在端能否担当下一趟：车头、车尾按预计发车端，方向未定时两端都行")
  void accepts() {
    assertTrue(CabSeats.accepts(CabSeats.End.HEAD, CabSeats.Departure.HEAD));
    assertFalse(CabSeats.accepts(CabSeats.End.TAIL, CabSeats.Departure.HEAD));
    assertTrue(CabSeats.accepts(CabSeats.End.TAIL, CabSeats.Departure.TAIL));
    assertFalse(CabSeats.accepts(CabSeats.End.HEAD, CabSeats.Departure.TAIL));
    assertTrue(CabSeats.accepts(CabSeats.End.HEAD, CabSeats.Departure.EITHER));
    assertTrue(CabSeats.accepts(CabSeats.End.TAIL, CabSeats.Departure.EITHER));
    assertFalse(CabSeats.accepts(CabSeats.End.NONE, CabSeats.Departure.EITHER));
  }

  @Test
  @DisplayName("端车的车厢号：车头第 1 节，车尾为节数")
  void carNumber() {
    CabSeats seats = CabSeats.unmarked(6);

    assertEquals(1, seats.carNumber(CabSeats.End.HEAD));
    assertEquals(6, seats.carNumber(CabSeats.End.TAIL));
  }

  @Test
  @DisplayName("尽头式待命站：离出口近的一端是下一趟车头；两头都通或量不出时要到派车才知道")
  void terminalDeparture() {
    assertEquals(CabSeats.Departure.TAIL, CabSeats.terminalDeparture(1, 120.0, 40.0, 6));
    assertEquals(
        CabSeats.Departure.HEAD,
        CabSeats.terminalDeparture(1, 40.0, 120.0, 6),
        "居中时往回挪过、已被调头的车：车头就朝着出口");
    assertEquals(CabSeats.Departure.EITHER, CabSeats.terminalDeparture(1, 50.0, 50.5, 6));
    assertEquals(CabSeats.Departure.EITHER, CabSeats.terminalDeparture(2, 120.0, 40.0, 6));
    assertEquals(CabSeats.Departure.EITHER, CabSeats.terminalDeparture(-1, 120.0, 40.0, 6));
    assertEquals(CabSeats.Departure.EITHER, CabSeats.terminalDeparture(1, Double.NaN, 40.0, 6));
    assertEquals(CabSeats.Departure.HEAD, CabSeats.terminalDeparture(1, 120.0, 40.0, 1));
  }

  @Test
  @DisplayName("座位名字与驾驶座名单比较时不区分大小写、不计两端空白")
  void nameMatches() {
    List<String> names = List.of("driver", "驾驶");

    assertTrue(CabSeats.nameMatches(Set.of(" Driver "), names));
    assertTrue(CabSeats.nameMatches(Set.of("door_left", "驾驶"), names));
    assertFalse(CabSeats.nameMatches(Set.of("driver_seat"), names), "按整名比较");
    assertFalse(CabSeats.nameMatches(Set.of(), names));
    assertFalse(CabSeats.nameMatches(Set.of("driver"), List.of()));
    assertEquals("cab", CabSeats.normalize("  CAB "));
  }

  @Test
  @DisplayName("AB 两组重联：中间两节的驾驶座不算，只认两头")
  void coupledMarriedPairsOnlyUseTheOuterCabs() {
    CabSeats seats = CabSeats.of(List.of(Set.of(0), Set.of(0), Set.of(0), Set.of(0)));

    assertEquals(CabSeats.End.HEAD, seats.endOf(0, 0));
    assertEquals(CabSeats.End.NONE, seats.endOf(1, 0));
    assertEquals(CabSeats.End.NONE, seats.endOf(2, 0));
    assertEquals(CabSeats.End.TAIL, seats.endOf(3, 0));
  }

  @Test
  @DisplayName("标记驾驶座只加名单第一个名字，已有名单上的名字不重复加；取消时只去掉名单上的名字")
  void markingEditsOnlyCabNames() {
    List<String> cabNames = List.of("driver", "驾驶座");

    assertEquals(List.of("door", "driver"), CabSeats.withCabName(List.of("door"), cabNames));
    assertEquals(List.of("Driver "), CabSeats.withCabName(List.of("Driver "), cabNames));
    assertEquals(List.of("driver"), CabSeats.withCabName(List.of(), cabNames));
    assertEquals(
        List.of("door"), CabSeats.withoutCabNames(List.of("door", "驾驶座", "DRIVER"), cabNames));
    assertEquals(List.of(), CabSeats.withoutCabNames(List.of("driver"), cabNames));
  }
}
