package org.fetarute.fetaruteTCAddon.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.junit.jupiter.api.Test;

/** 叫车预先指定的站台、有没有待命车可接的判定。 */
class CallPinAndSourceTest {

  private static final UUID ROUTE = UUID.randomUUID();

  /** DYNAMIC 停靠、右键单站台的屏：指定那条股道；股道号写法统一（02 与 2 是同一条）。 */
  @Test
  void aSinglePlatformScreenPinsTheDynamicStop() {
    RouteStop dynamic = stop("DYNAMIC:SURC:S:PPK:[1:3]");

    assertEquals(
        Optional.of(new CallService.PlatformPin(ROUTE, 4, "SURC:S:PPK:2")),
        CallService.pinFor(Set.of("2"), Set.of("02"), ROUTE, 4, dynamic));
  }

  /** 统屏、命令（不限站台）、多站台的屏、固定站台的停靠、范围外的股道：都不指定，选台照常。 */
  @Test
  void otherCasesLeaveThePlatformToTheAllocator() {
    RouteStop dynamic = stop("DYNAMIC:SURC:S:PPK:[1:3]");

    assertTrue(CallService.pinFor(Set.of("1", "2", "3"), Set.of(), ROUTE, 4, dynamic).isEmpty());
    assertTrue(CallService.pinFor(Set.of("1", "2"), Set.of("1", "2"), ROUTE, 4, dynamic).isEmpty());
    assertTrue(CallService.pinFor(Set.of("2"), Set.of("2"), ROUTE, 4, stop(null)).isEmpty());
    assertTrue(CallService.pinFor(Set.of(), Set.of("5"), ROUTE, 4, dynamic).isEmpty());
  }

  /** 不限股道的 DYNAMIC 停靠：方向没有站台可筛，按屏幕的那一条指定。 */
  @Test
  void anUnboundedDynamicStopPinsTheScreenPlatform() {
    assertEquals(
        Optional.of(new CallService.PlatformPin(ROUTE, 1, "SURC:S:PPK:3")),
        CallService.pinFor(Set.of(), Set.of("3"), ROUTE, 1, stop("DYNAMIC:SURC:S:PPK")));
  }

  /** 指定站台随车写在标签里：读写往返，只认同一交路的同一个停靠。 */
  @Test
  void pinRoundTripsThroughItsTag() {
    CallService.PlatformPin pin = new CallService.PlatformPin(ROUTE, 4, "SURC:S:PPK:2");

    assertEquals(Optional.of(pin), CallService.PlatformPin.parse(pin.format()));
    assertEquals(Optional.of("SURC:S:PPK:2"), pin.at(ROUTE, 4));
    assertTrue(pin.at(ROUTE, 5).isEmpty());
    assertTrue(pin.at(UUID.randomUUID(), 4).isEmpty());
    assertTrue(CallService.PlatformPin.parse("not|a-pin").isEmpty());
    assertTrue(CallService.PlatformPin.parse("x|1|SURC:S:PPK:2").isEmpty());
  }

  /** 有交路在首站终到、并留在待命池（终点复用）时，首站才可能有待命车；终到即销毁的、由时刻表管辖的（留下的车还担着班）不算。 */
  @Test
  void standbyNeedsARouteThatEndsAndStaysAtTheStart() {
    RouteDefinitionCache.RouteEntry staysAtAaa =
        entry(RouteLifecycleMode.REUSE_AT_TERM, "SURC:S:NTA:1", "SURC:S:AAA:2");
    RouteDefinitionCache.RouteEntry destroyedAtBbb =
        entry(RouteLifecycleMode.DESTROY_AFTER_TERM, "SURC:S:NTA:1", "SURC:S:BBB:1");

    assertTrue(
        CallService.standbyPossible(List.of(staysAtAaa), "SURC:S:AAA:1", routeId -> false),
        "同站别的站台也算");
    assertFalse(
        CallService.standbyPossible(List.of(destroyedAtBbb), "SURC:S:BBB:1", routeId -> false));
    assertFalse(CallService.standbyPossible(List.of(staysAtAaa), "SURC:S:CCC:1", routeId -> false));
    assertFalse(
        CallService.standbyPossible(
            List.of(staysAtAaa), "SURC:S:AAA:1", routeId -> routeId.equals(staysAtAaa.routeId())),
        "时刻表交路留下的车叫车不接");
  }

  private static RouteStop stop(String notes) {
    return new RouteStop(
        ROUTE,
        4,
        Optional.empty(),
        Optional.of("SURC:S:PPK:1"),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.ofNullable(notes));
  }

  private static RouteDefinitionCache.RouteEntry entry(
      RouteLifecycleMode lifecycle, String... nodes) {
    RouteDefinition definition =
        new RouteDefinition(
            new RouteId("SURC:WS:" + UUID.randomUUID()),
            java.util.Arrays.stream(nodes).map(NodeId::of).toList(),
            Optional.empty(),
            lifecycle);
    return new RouteDefinitionCache.RouteEntry(
        UUID.randomUUID(), definition, mock(RouteDefinitionCache.RouteRecord.class), List.of());
  }
}
