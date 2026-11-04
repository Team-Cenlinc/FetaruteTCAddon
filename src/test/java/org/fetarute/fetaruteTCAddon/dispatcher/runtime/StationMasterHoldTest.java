package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StationMasterGate;
import org.junit.jupiter.api.Test;

/** 站长扣车：出站门控的第四个扣车来源，与信号员扣车各扣各放、取较晚者，同样对健康检查豁免。 */
class StationMasterHoldTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:00Z");
  private static final NodeId NODE = NodeId.of("OP:S:AAA:1");

  private static RouteDefinition route() {
    return new RouteDefinition(
        RouteId.of("OP:L1:R1"), List.of(NODE, NodeId.of("OP:S:BBB:1")), Optional.empty());
  }

  private static boolean holds(StationStopCoordinator stops, Instant now) {
    return stops.holdsDeparture("train-A", route(), Optional.of(UUID.randomUUID()), 0, NODE, now);
  }

  private static StationMasterGate holding(AtomicReference<Optional<Instant>> until) {
    return new StationMasterGate() {
      @Override
      public Optional<Instant> holdTarget(String trainName) {
        return trainName.equals("train-A") ? until.get() : Optional.empty();
      }
    };
  }

  @Test
  void theStationMasterHoldKeepsTheTrainUntilReleased() {
    StationStopCoordinator stops = TestServices.minimal(new ArrayList<>(), () -> T0).stationStops();
    AtomicReference<Optional<Instant>> until =
        new AtomicReference<>(Optional.of(T0.plusSeconds(60)));
    stops.setStationMaster(holding(until));

    assertTrue(holds(stops, T0));
    assertTrue(stops.holdingForSchedule("train-A"), "站长扣车同样对健康检查豁免");

    until.set(Optional.empty());
    assertFalse(holds(stops, T0.plusSeconds(1)), "放行后下一次门控检查立刻放行");
    assertFalse(stops.holdingForSchedule("train-A"));
  }

  @Test
  void theLaterOfSignallerAndStationMasterWins() {
    StationStopCoordinator stops = TestServices.minimal(new ArrayList<>(), () -> T0).stationStops();
    stops.setSignallerHold(train -> Optional.of(T0.plusSeconds(30)));
    stops.setStationMaster(holding(new AtomicReference<>(Optional.of(T0.plusSeconds(90)))));

    assertTrue(holds(stops, T0.plusSeconds(45)), "信号员已放、站长还扣着");
    assertFalse(holds(stops, T0.plusSeconds(90)), "到点放行");
  }

  @Test
  void aFailingStationMasterNeverHoldsAndNullRestoresNone() {
    StationStopCoordinator stops = TestServices.minimal(new ArrayList<>(), () -> T0).stationStops();
    stops.setStationMaster(
        new StationMasterGate() {
          @Override
          public Optional<Instant> holdTarget(String trainName) {
            throw new IllegalStateException("boom");
          }
        });
    assertFalse(holds(stops, T0), "判定出错时不扣");

    stops.setStationMaster(null);
    assertSame(StationMasterGate.NONE, stops.stationMaster());
  }
}
