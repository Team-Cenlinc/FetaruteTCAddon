package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class SpeedLimitTrackerTest {

  private static final double MAX_BPS = 22.0;
  private static final double GUARDED_BPT = MAX_BPS / 20.0;

  private final List<Double> writes = new ArrayList<>();

  @Test
  void untouchedLimitKeepsMaxSpeedAndShowsNoLineLimit() {
    SpeedLimitTracker tracker = new SpeedLimitTracker(true);
    tracker.guard(GUARDED_BPT);

    double cap = tracker.onTick(GUARDED_BPT, MAX_BPS, writes::add);

    assertEquals(MAX_BPS, cap);
    assertTrue(writes.isEmpty());
    assertTrue(Double.isNaN(tracker.displayLimitBps(cap)));
    assertEquals(OptionalDouble.empty(), tracker.observedLimitBpt());
  }

  @Test
  void overrideRecordsASignLimitAndWritesTheGuardedValueBack() {
    SpeedLimitTracker tracker = new SpeedLimitTracker(true);
    tracker.guard(GUARDED_BPT);

    double cap = tracker.onTick(0.5, MAX_BPS, writes::add);

    assertEquals(MAX_BPS, cap, "允许超速时上限仍是最高速度");
    assertEquals(List.of(GUARDED_BPT), writes);
    assertEquals(10.0, tracker.displayLimitBps(cap), 1e-12, "0.5 格/tick 显示为 10 格/秒");
    assertEquals(OptionalDouble.of(0.5), tracker.observedLimitBpt());
  }

  @Test
  void overrideKeepsTheLatestLimitAcrossLaterTicks() {
    SpeedLimitTracker tracker = new SpeedLimitTracker(true);
    tracker.guard(GUARDED_BPT);
    tracker.onTick(0.5, MAX_BPS, writes::add);

    // 属性已写回接管时的值，下一 tick 不算新的改动。
    tracker.onTick(GUARDED_BPT, MAX_BPS, writes::add);
    tracker.onTick(0.8, MAX_BPS, writes::add);

    assertEquals(2, writes.size());
    assertEquals(OptionalDouble.of(0.8), tracker.observedLimitBpt());
  }

  @Test
  void withoutOverrideASignLimitCapsTheSpeedAndIsNotWrittenBack() {
    SpeedLimitTracker tracker = new SpeedLimitTracker(false);
    tracker.guard(GUARDED_BPT);

    double cap = tracker.onTick(0.5, MAX_BPS, writes::add);
    double next = tracker.onTick(0.5, MAX_BPS, writes::add);

    assertEquals(10.0, cap, 1e-12);
    assertEquals(10.0, next, 1e-12);
    assertTrue(writes.isEmpty());
    assertEquals(cap, tracker.displayLimitBps(cap));
    assertEquals(OptionalDouble.of(0.5), tracker.observedLimitBpt(), "结束时同样要保留线路限速");
  }

  @Test
  void beforeGuardingNothingIsTreatedAsAChange() {
    SpeedLimitTracker tracker = new SpeedLimitTracker(true);

    tracker.onTick(0.5, MAX_BPS, writes::add);

    assertTrue(writes.isEmpty());
    assertEquals(OptionalDouble.empty(), tracker.observedLimitBpt());
  }
}
