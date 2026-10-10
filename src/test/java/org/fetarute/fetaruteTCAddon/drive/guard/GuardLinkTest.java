package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.junit.jupiter.api.Test;

/** 车掌链路：每站结算一次，连续几站有超时就该结束值乘；站台只在停站进行中问发车。 */
class GuardLinkTest {

  private final long[] clock = {1000L};
  private final GuardLink link =
      new GuardLink(UUID.randomUUID(), "T", null, GuardConfig.defaults(), () -> clock[0]);

  static DriverStationStop stop() {
    return new DriverStationStop(
        NodeId.of("SURC:S:PPK:1"),
        "PPK",
        UUID.randomUUID(),
        new Vector(0, 64, 0),
        null,
        false,
        true);
  }

  /** 一站超时（开门超时）。 */
  private void timedOutStop() {
    link.beginStationStop(stop());
    GuardStopWork work = link.work().orElseThrow();
    for (long i = 0; i <= GuardConfig.defaults().openDoorsTicks(); i++) {
      work.tick(Phase.OPEN_DOORS);
    }
    assertTrue(work.timedOut());
  }

  @Test
  void threeTimedOutStopsInARowEndTheDuty() {
    timedOutStop();
    timedOutStop();
    timedOutStop();
    assertFalse(link.tooManyTimeouts(), "第三站还没结算");
    assertEquals(Optional.of(true), link.settle());
    assertTrue(link.tooManyTimeouts());
    assertEquals(3, link.timeoutStops());
    assertEquals(Optional.empty(), link.settle(), "同一站只结算一次");
  }

  @Test
  void aCleanStopResetsTheRun() {
    timedOutStop();
    timedOutStop();
    link.beginStationStop(stop());
    link.settle();
    assertEquals(0, link.consecutiveTimeoutStops());
    assertEquals(3, link.completedStops());
    assertEquals(2, link.timeoutStops());
  }

  @Test
  void theStationOnlyWaitsDuringAnActiveStop() {
    assertFalse(link.holdDeparture(true), "还没停站");
    DriverStationStop current = stop();
    link.beginStationStop(current);
    link.work().orElseThrow().tick(Phase.WAIT_DEPARTURE);
    assertTrue(link.holdDeparture(true), "等车掌的发车信号");
    current.end();
    assertFalse(link.holdDeparture(true), "停站结束不再扣");
  }
}
