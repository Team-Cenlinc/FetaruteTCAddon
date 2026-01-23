package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BossBarProgressTrackerTest {

  @Test
  void progressClampsAndFreezesWhenStopped() {
    BossBarProgressTracker tracker = new BossBarProgressTracker();

    long t0 = 1_000_000L;
    long eta = t0 + 10_000L;

    assertEquals(0.0f, tracker.progress("T1", 0, eta, t0, false));

    assertEquals(0.0f, tracker.progress("T1", 0, eta, t0, true));
    float mid = tracker.progress("T1", 0, eta, t0 + 5_000L, true);
    assertEquals(0.5f, mid, 0.05f);
    assertEquals(mid, tracker.progress("T1", 0, eta, t0 + 5_000L, false), 0.05f);
    assertEquals(mid, tracker.progress("T1", 0, eta, t0 + 7_000L, true), 0.05f);
    assertEquals(1.0f, tracker.progress("T1", 0, eta, t0 + 20_000L, true));

    assertEquals(1.0f, tracker.progress("T1", 0, eta, t0 + 20_000L, false));
  }

  @Test
  void routeIndexChangeResetsSegment() {
    BossBarProgressTracker tracker = new BossBarProgressTracker();

    long t0 = 1_000_000L;
    long eta = t0 + 10_000L;

    tracker.progress("T1", 0, eta, t0, true);
    assertEquals(0.5f, tracker.progress("T1", 0, eta, t0 + 5_000L, true), 0.05f);

    long t1 = t0 + 6_000L;
    long eta2 = t1 + 10_000L;
    assertEquals(0.0f, tracker.progress("T1", 1, eta2, t1, true));
  }
}
