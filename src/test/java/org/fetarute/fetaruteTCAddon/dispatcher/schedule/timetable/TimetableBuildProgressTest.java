package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TimetableBuildProgressTest {

  /** 手动拨的钟。 */
  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-10-02T00:00:00Z");

    void advance(long seconds) {
      now = now.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void stageTimerResetsOnlyOnStageChange() {
    ManualClock clock = new ManualClock();
    TimetableBuildProgress progress = new TimetableBuildProgress(clock);
    clock.advance(5);
    progress.stage(TimetableBuildProgress.Stage.RELAX);
    progress.detail("试 150s");
    progress.fullBuild();
    clock.advance(10);
    // 同一阶段再进一次（如另一个间隔又进了搜索）：阶段计时不归零，说明清空。
    progress.stage(TimetableBuildProgress.Stage.RELAX);
    progress.fullBuild();
    clock.advance(3);

    TimetableBuildProgress.Snapshot snapshot = progress.snapshot();
    assertEquals(TimetableBuildProgress.Stage.RELAX, snapshot.stage());
    assertEquals("", snapshot.detail());
    assertEquals(2, snapshot.fullBuilds());
    assertEquals(Duration.ofSeconds(18), snapshot.elapsed());
    assertEquals(Duration.ofSeconds(13), snapshot.stageElapsed());

    progress.stage(TimetableBuildProgress.Stage.TIGHTEN);
    clock.advance(2);
    assertEquals(Duration.ofSeconds(2), progress.snapshot().stageElapsed());
  }

  /** 完整构建按用途分开数：放宽确认、严格取消重排各几次，报告里一行写清。 */
  @Test
  void trialsAreCountedByKind() {
    TimetableBuildProgress progress = new TimetableBuildProgress();
    progress.trial(TimetableBuildProgress.Trial.RELAX_CONFIRM);
    progress.trial(TimetableBuildProgress.Trial.RELAX_CONFIRM);
    progress.trial(TimetableBuildProgress.Trial.CANCEL_RETRY);

    TimetableBuildProgress.Snapshot snapshot = progress.snapshot();
    assertEquals(2, snapshot.trials(TimetableBuildProgress.Trial.RELAX_CONFIRM));
    assertEquals(1, snapshot.trials(TimetableBuildProgress.Trial.CANCEL_RETRY));
    assertEquals("放宽确认 2、严格取消重排 1", snapshot.describeTrials());
  }

  @Test
  void cancelStopsAtNextFullBuild() {
    TimetableBuildProgress progress = new TimetableBuildProgress();
    progress.fullBuild();
    progress.cancel();

    assertThrows(TimetableBuildProgress.Cancelled.class, progress::fullBuild);
    assertEquals(1, progress.snapshot().fullBuilds(), "被拦下的那次不计数");
  }

  /** 取消与开始保存只有一个能成：先保存的取消不了，先取消的保存不了。 */
  @Test
  void cancelAndSavingExcludeEachOther() {
    TimetableBuildProgress saving = new TimetableBuildProgress();
    assertTrue(saving.beginSaving());
    assertFalse(saving.cancel());
    assertTrue(saving.saving());
    assertFalse(saving.cancelled());

    TimetableBuildProgress cancelled = new TimetableBuildProgress();
    assertTrue(cancelled.cancel());
    assertTrue(cancelled.cancel(), "重复取消仍算取消");
    assertFalse(cancelled.beginSaving());
    assertFalse(cancelled.saving());
  }
}
