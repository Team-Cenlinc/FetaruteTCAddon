package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.seat.MemberBeforeSeatExitEvent;
import com.bergerkiller.bukkit.tc.properties.CartProperties;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("停站下车点按牌子写的方位落到站台一侧，只给开门车厢")
class AutoStationExitOffsetTest {

  private final StationExitPoints listener = new StationExitPoints();

  @AfterEach
  void tearDown() {
    StationExitPoints.clear();
  }

  /** 一次下车：座位在 (10, 64, 20)，TrainCarts 原本的落点在 (10, 65, 20) 朝 90°。 */
  private static MemberBeforeSeatExitEvent exit(UUID cart, boolean seatChange) {
    CartProperties properties = mock(CartProperties.class);
    when(properties.getUUID()).thenReturn(cart);
    MinecartMember<?> member = mock(MinecartMember.class);
    when(member.getProperties()).thenReturn(properties);
    MemberBeforeSeatExitEvent event = mock(MemberBeforeSeatExitEvent.class);
    doReturnMember(event, member);
    when(event.isSeatChange()).thenReturn(seatChange);
    when(event.getSeatPosition()).thenReturn(new Location(null, 10.0, 64.0, 20.0));
    when(event.getExitPosition()).thenReturn(new Location(null, 10.0, 65.0, 20.0, 90.0f, 5.0f));
    return event;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void doReturnMember(MemberBeforeSeatExitEvent event, MinecartMember<?> member) {
    when((MinecartMember) event.getMember()).thenReturn((MinecartMember) member);
  }

  private static Location landed(MemberBeforeSeatExitEvent event) {
    ArgumentCaptor<Location> point = ArgumentCaptor.forClass(Location.class);
    verify(event).setExitPosition(point.capture(), org.mockito.ArgumentMatchers.eq(true));
    return point.getValue();
  }

  @Test
  @DisplayName("落点是座位往牌子方位偏出的位置，与车厢怎么摆放无关；高度、朝向沿用原本的落点")
  void landsBesideTheSeatTowardsTheSignedSide() {
    UUID cart = UUID.randomUUID();
    Object stop = new Object();
    StationExitPoints.open(stop, cart, BlockFace.NORTH, 3.0);

    MemberBeforeSeatExitEvent event = exit(cart, false);
    listener.onSeatExit(event);

    Location point = landed(event);
    assertEquals(10.0, point.getX(), 1e-9);
    assertEquals(17.0, point.getZ(), 1e-9, "北是 -Z");
    assertEquals(65.0, point.getY(), 1e-9);
    assertEquals(90.0f, point.getYaw(), 1e-6f);
  }

  @Test
  @DisplayName("没登记的车厢、车上换座位：不改落点")
  void otherCarsAndSeatChangesKeepTheirOwnExit() {
    UUID cart = UUID.randomUUID();
    StationExitPoints.open(new Object(), cart, BlockFace.EAST, 3.0);

    MemberBeforeSeatExitEvent elsewhere = exit(UUID.randomUUID(), false);
    listener.onSeatExit(elsewhere);
    verify(elsewhere, never()).setExitPosition(any(), anyBoolean());

    MemberBeforeSeatExitEvent seatChange = exit(cart, true);
    listener.onSeatExit(seatChange);
    verify(seatChange, never()).setExitPosition(any(), anyBoolean());
  }

  @Test
  @DisplayName("停站只给开门的车厢登记，发车前撤销；只撤自己登记的")
  void stopRegistersDoorCarsAndRemovesOnlyItsOwn() {
    UUID front = UUID.randomUUID();
    UUID rear = UUID.randomUUID();
    AutoStationSignAction.StationExitState first =
        new AutoStationSignAction.StationExitState(List.of(front, rear));
    assertFalse(first.applied());

    first.apply(BlockFace.SOUTH, 3.0);
    assertTrue(first.applied());

    // 拆开以后解下的一段在本站另起一次停站，同一节车由它重新登记
    AutoStationSignAction.StationExitState detached =
        new AutoStationSignAction.StationExitState(List.of(front));
    detached.apply(BlockFace.SOUTH, 3.0);
    first.restore();
    assertFalse(first.applied());

    MemberBeforeSeatExitEvent stillOpen = exit(front, false);
    listener.onSeatExit(stillOpen);
    assertEquals(23.0, landed(stillOpen).getZ(), 1e-9, "解下那一段还在停站：不被前一次停站撤掉");

    MemberBeforeSeatExitEvent closed = exit(rear, false);
    listener.onSeatExit(closed);
    verify(closed, never()).setExitPosition(any(), anyBoolean());

    detached.restore();
    MemberBeforeSeatExitEvent departed = exit(front, false);
    listener.onSeatExit(departed);
    verify(departed, never()).setExitPosition(any(), anyBoolean());
  }

  @Test
  @DisplayName("方位不是水平方向、距离无效时不登记")
  void invalidSidesAreNotRegistered() {
    AutoStationSignAction.StationExitState state =
        new AutoStationSignAction.StationExitState(List.of(UUID.randomUUID()));
    state.apply(null, 3.0);
    assertFalse(state.applied());
    state.apply(BlockFace.UP, 3.0);
    assertFalse(state.applied());
    state.apply(BlockFace.WEST, 0.0);
    assertFalse(state.applied());

    assertNull(StationExitPoints.exitPoint(null, null, BlockFace.WEST, 3.0));
    Location diagonal =
        StationExitPoints.exitPoint(
            new Location(null, 0.0, 64.0, 0.0), null, BlockFace.NORTH_EAST, 3.0);
    assertEquals(3.0, Math.hypot(diagonal.getX(), diagonal.getZ()), 1e-9, "斜向同样偏出 3 格");
    assertEquals(64.0, diagonal.getY(), 1e-9, "没有原本落点时用座位高度");
  }
}
