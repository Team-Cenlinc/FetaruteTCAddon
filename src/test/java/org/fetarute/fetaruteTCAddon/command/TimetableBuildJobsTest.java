package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TimetableBuildJobsTest {

  private static final UUID WS = UUID.randomUUID();
  private static final UUID MT = UUID.randomUUID();

  @Test
  void sameLineAndCodeCannotBuildTwice() {
    TimetableBuildJobs jobs = new TimetableBuildJobs();
    TimetableBuildJobs.Job first = jobs.start("WS,MT/BASE", "alice", Set.of(WS, MT), "BASE").get();

    assertTrue(jobs.start("WS/base", "bob", Set.of(WS), "base").isEmpty(), "code 大小写不敏感");
    assertEquals(
        Optional.of(first.id()),
        jobs.conflicting(Set.of(MT), "BASE").map(TimetableBuildJobs.Job::id));
    assertTrue(jobs.start("WS/ALT", "bob", Set.of(WS), "ALT").isPresent(), "别的 code 不冲突");
    assertEquals(2, jobs.list().size());

    jobs.finish(first.id());
    assertTrue(jobs.start("WS/BASE", "bob", Set.of(WS), "BASE").isPresent(), "编完就能再编");
  }

  @Test
  void cancelUnlocksImmediatelyAndMarksProgress() {
    TimetableBuildJobs jobs = new TimetableBuildJobs();
    TimetableBuildJobs.Job job = jobs.start("WS/BASE", "alice", Set.of(WS), "BASE").get();

    assertTrue(jobs.cancel("nope").isEmpty());
    TimetableBuildJobs.Cancellation cancelled = jobs.cancel(job.shortId()).orElseThrow();
    assertEquals(job.id(), cancelled.job().id());
    assertTrue(cancelled.stopped());
    assertTrue(job.progress().cancelled(), "编表线程靠它在下一个检查点停下");
    assertFalse(job.progress().beginSaving(), "编完回主线程时发现已取消，不落库");
    assertTrue(jobs.list().isEmpty());
    assertTrue(jobs.start("WS/BASE", "bob", Set.of(WS), "BASE").isPresent(), "取消即解锁");
    assertTrue(jobs.cancel("ws/base").orElseThrow().stopped(), "也可以按范围取消");
  }

  /** 已开始保存的取消不了，锁也不放：落库期间再 build 会读不到正在写的草稿。 */
  @Test
  void savingCannotBeCancelledAndHoldsTheLockUntilFinished() {
    TimetableBuildJobs jobs = new TimetableBuildJobs();
    TimetableBuildJobs.Job job = jobs.start("WS/BASE", "alice", Set.of(WS), "BASE").get();
    assertTrue(job.progress().beginSaving());

    TimetableBuildJobs.Cancellation late = jobs.cancel(job.shortId()).orElseThrow();
    assertFalse(late.stopped());
    assertFalse(job.progress().cancelled());
    assertTrue(job.progress().saving());
    assertTrue(jobs.start("WS/BASE", "bob", Set.of(WS), "BASE").isEmpty(), "保存期间仍然锁着");

    jobs.finish(job.id());
    assertTrue(jobs.start("WS/BASE", "bob", Set.of(WS), "BASE").isPresent(), "事务结束才解锁");
  }
}
