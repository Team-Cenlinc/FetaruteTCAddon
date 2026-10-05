package org.fetarute.fetaruteTCAddon.drive.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Intervention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("提示音时机")
class DriveCueTrackerTest {

  private final DriveCueTracker tracker = new DriveCueTracker();

  private static DriveCueTracker.Snapshot snapshot(
      boolean pending,
      int aspect,
      Intervention intervention,
      boolean overspeed,
      boolean brake,
      DriverStationStop.Phase phase,
      boolean departure) {
    return new DriveCueTracker.Snapshot(
        pending, aspect, intervention, overspeed, brake, phase, departure);
  }

  private static DriveCueTracker.Snapshot aspect(int rank) {
    return snapshot(false, rank, Intervention.NONE, false, false, null, false);
  }

  @Test
  @DisplayName("信号待确认：出现时响，之后每秒重复")
  void pendingSignalRepeats() {
    DriveCueTracker.Snapshot pending =
        snapshot(true, 2, Intervention.NONE, false, false, null, false);
    assertEquals(List.of(DriveCue.SIGNAL_RESTRICTIVE), tracker.observe(pending, 0L));
    assertEquals(List.of(), tracker.observe(pending, 10L));
    assertEquals(List.of(DriveCue.SIGNAL_RESTRICTIVE), tracker.observe(pending, 20L));
  }

  @Test
  @DisplayName("信号转宽响一次，短时间内来回跳动不重复")
  void clearSignalIsDebounced() {
    tracker.observe(aspect(2), 0L);
    assertEquals(List.of(DriveCue.SIGNAL_CLEAR), tracker.observe(aspect(0), 5L));
    tracker.observe(aspect(2), 10L);
    assertEquals(List.of(), tracker.observe(aspect(0), 15L));
    tracker.observe(aspect(2), 60L);
    assertEquals(List.of(DriveCue.SIGNAL_CLEAR), tracker.observe(aspect(0), 70L));
  }

  @Test
  @DisplayName("防护介入加重时响：常用制动一声，紧急制动或强制停车一声，强制停车接在紧急制动后不再响")
  void interventionEscalation() {
    assertEquals(
        List.of(DriveCue.ATP_BRAKE),
        tracker.observe(snapshot(false, 0, Intervention.SERVICE, false, false, null, false), 0L));
    assertEquals(
        List.of(DriveCue.EMERGENCY),
        tracker.observe(snapshot(false, 0, Intervention.EMERGENCY, false, false, null, false), 5L));
    assertEquals(
        List.of(),
        tracker.observe(snapshot(false, 0, Intervention.CLAMP, false, false, null, false), 10L));
  }

  @Test
  @DisplayName("超速红区每半秒重复；开始制动只在出现时响")
  void overspeedRepeatsAndBrakeAdviceIsEdgeTriggered() {
    DriveCueTracker.Snapshot both = snapshot(false, 0, Intervention.NONE, true, true, null, false);
    assertEquals(List.of(DriveCue.OVERSPEED, DriveCue.BRAKE_ADVICE), tracker.observe(both, 0L));
    assertEquals(List.of(), tracker.observe(both, 5L));
    assertEquals(List.of(DriveCue.OVERSPEED), tracker.observe(both, 10L));
  }

  @Test
  @DisplayName("停站：停妥可开门、发车信号、ATO 等确认各响一次")
  void stationPhases() {
    assertEquals(
        List.of(DriveCue.DOORS_RELEASED),
        tracker.observe(
            snapshot(
                false,
                0,
                Intervention.NONE,
                false,
                false,
                DriverStationStop.Phase.OPEN_DOORS,
                false),
            0L));
    assertTrue(
        tracker
            .observe(
                snapshot(
                    false,
                    0,
                    Intervention.NONE,
                    false,
                    false,
                    DriverStationStop.Phase.DWELL,
                    false),
                5L)
            .isEmpty());
    assertEquals(
        List.of(DriveCue.DEPART),
        tracker.observe(
            snapshot(
                false, 0, Intervention.NONE, false, false, DriverStationStop.Phase.DEPART, false),
            10L));
    assertEquals(
        List.of(DriveCue.ATO_CONFIRM),
        tracker.observe(snapshot(false, -1, Intervention.NONE, false, false, null, true), 15L));
  }
}
