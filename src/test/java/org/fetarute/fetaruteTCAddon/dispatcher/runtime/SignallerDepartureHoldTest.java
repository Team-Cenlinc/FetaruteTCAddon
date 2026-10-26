package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/** 信号员扣车：出站门控的第三个扣车来源，与按表扣车同样对健康检查豁免；放行在下一次门控检查即生效，上限由信号员模块自己管。 */
class SignallerDepartureHoldTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:00Z");
  private static final NodeId NODE = NodeId.of("OP:S:AAA:1");
  private static final UUID ROUTE = UUID.randomUUID();

  private static RouteDefinition route() {
    return new RouteDefinition(
        RouteId.of("OP:L1:R1"),
        List.of(NODE, NodeId.of("OP:S:BBB:1"), NodeId.of("OP:S:CCC:1")),
        Optional.empty());
  }

  private static boolean holds(StationStopCoordinator coordinator, Instant now) {
    return coordinator.holdsDeparture("train-A", route(), Optional.of(ROUTE), 0, NODE, now);
  }

  @Test
  void signallerHoldKeepsTheTrainAndExemptsItFromHealthChecksUntilReleased() {
    AtomicReference<Instant> clock = new AtomicReference<>(T0);
    StationStopCoordinator stops =
        TestServices.minimal(new ArrayList<>(), clock::get).stationStops();
    AtomicReference<Optional<Instant>> hold =
        new AtomicReference<>(Optional.of(T0.plusSeconds(90)));
    stops.setSignallerHold(train -> train.equals("train-A") ? hold.get() : Optional.empty());

    assertTrue(holds(stops, T0));
    assertTrue(stops.holdingForSchedule("train-A"), "信号员扣车与按表扣车一样对健康检查豁免");
    assertTrue(stops.holdingForSchedule("TRAIN-A"));

    hold.set(Optional.empty());
    assertFalse(holds(stops, T0.plusSeconds(1)), "放行后下一次门控检查立刻放行");
    assertFalse(stops.holdingForSchedule("train-A"), "放行即撤销健康豁免");
  }

  @Test
  void signallerHoldIsNotTruncatedToTheScheduledHoldCeiling() {
    AtomicReference<Instant> clock = new AtomicReference<>(T0);
    StationStopCoordinator stops =
        TestServices.minimal(new ArrayList<>(), clock::get).stationStops();
    Instant until = T0.plus(Duration.ofSeconds(180));
    stops.setSignallerHold(train -> Optional.of(until));

    clock.set(T0.plusSeconds(170));
    assertTrue(holds(stops, clock.get()), "上限由信号员模块管：超过按表扣车的 150 秒仍扣着");
    assertTrue(stops.holdingForSchedule("train-A"));

    clock.set(until);
    assertFalse(holds(stops, until), "到点放行");
  }

  @Test
  void theLatestOfScheduleCouplingAndSignallerWins() {
    StationStopCoordinator stops = TestServices.minimal(new ArrayList<>(), () -> T0).stationStops();
    stops.setPlan(event -> Optional.of(T0.plusSeconds(30)));
    stops.setMaxHold(Duration.ofSeconds(120));
    stops.setSignallerHold(train -> Optional.of(T0.plusSeconds(60)));

    assertTrue(holds(stops, T0.plusSeconds(45)), "表定时刻已过、信号员还扣着");
    assertFalse(holds(stops, T0.plusSeconds(60)));
  }

  @Test
  void aFailingOrExpiredSignallerHoldDoesNotHold() {
    StationStopCoordinator stops = TestServices.minimal(new ArrayList<>(), () -> T0).stationStops();
    stops.setSignallerHold(
        train -> {
          throw new IllegalStateException("boom");
        });
    assertFalse(holds(stops, T0), "判定出错时不扣");

    stops.setSignallerHold(train -> Optional.of(T0.minusSeconds(1)));
    assertFalse(holds(stops, T0), "已过的时刻不扣");

    stops.setSignallerHold(null);
    assertFalse(holds(stops, T0));
  }
}
