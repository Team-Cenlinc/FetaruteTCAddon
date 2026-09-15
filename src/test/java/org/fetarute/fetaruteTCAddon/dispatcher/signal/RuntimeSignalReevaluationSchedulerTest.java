package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

/** 下一 tick 信号完整重评估调度器测试。 */
class RuntimeSignalReevaluationSchedulerTest {

  /**
   * 冻结时钟：让"一次 drain 能排空整批"成为**行为断言**，而不是速度断言。
   *
   * <p>默认构造器用真实 {@code System.nanoTime} 和 5 毫秒预算。冷 JVM 里（比如 forkEvery 之后 本类恰好是某个 fork
   * 的第一个）类加载加上构造一个 {@code LinkageError} 就能吃掉这 5 毫秒， 于是第二辆车被推到下一 tick，而用例只 {@code runNextTick()}
   * 一次——断言随机变红。 这个顺序依赖在 {@code forkEvery} 引入之前一直被"总有别的类先把 JVM 跑热"掩盖着， 在慢机器或 CI 上迟早会自己冒出来。
   *
   * <p>预算耗尽时的分批行为由 {@code drainYieldsRemainingBatchToFollowingTickWhenWorkBudgetExpires}
   * 正面覆盖，因此这里冻结时钟不会丢掉任何判别力。
   */
  private static RuntimeSignalReevaluationScheduler withFrozenClock(
      ManualNextTickScheduler nextTick,
      java.util.function.Consumer<String> attempt,
      java.util.function.BiConsumer<String, Throwable> failureHandler,
      java.util.function.Consumer<String> debugLogger) {
    return new RuntimeSignalReevaluationScheduler(
        nextTick, attempt, failureHandler, debugLogger, Duration.ofMillis(5), () -> 0L);
  }

  @Test
  void coalescesLogicalAliasesUntilNextTick() {
    ManualNextTickScheduler nextTick = new ManualNextTickScheduler();
    List<String> reevaluated = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        new RuntimeSignalReevaluationScheduler(nextTick, reevaluated::add);

    scheduler.request("Train-A");
    scheduler.request("train-a");
    scheduler.request("TRAIN-A~a");

    assertTrue(reevaluated.isEmpty());
    assertEquals(1, nextTick.pendingTasks());

    nextTick.runNextTick();

    assertEquals(List.of("Train-A"), reevaluated);
  }

  @Test
  void requestDuringDrainRunsInFollowingTickWithoutReentrancy() {
    ManualNextTickScheduler nextTick = new ManualNextTickScheduler();
    List<String> reevaluated = new ArrayList<>();
    int[] callDepth = {0};
    int[] maximumDepth = {0};
    RuntimeSignalReevaluationScheduler[] holder = new RuntimeSignalReevaluationScheduler[1];
    holder[0] =
        new RuntimeSignalReevaluationScheduler(
            nextTick,
            trainName -> {
              callDepth[0]++;
              maximumDepth[0] = Math.max(maximumDepth[0], callDepth[0]);
              reevaluated.add(trainName);
              if (reevaluated.size() == 1) {
                holder[0].request(trainName);
                holder[0].request("train-B");
              }
              callDepth[0]--;
            });

    holder[0].request("train-A");
    nextTick.runNextTick();

    assertEquals(List.of("train-A"), reevaluated);
    assertEquals(1, maximumDepth[0]);
    assertEquals(1, nextTick.pendingTasks());

    nextTick.runNextTick();

    assertEquals(List.of("train-A", "train-A", "train-B"), reevaluated);
    assertEquals(1, maximumDepth[0]);
  }

  @Test
  void drainYieldsRemainingBatchToFollowingTickWhenWorkBudgetExpires() {
    ManualNextTickScheduler nextTick = new ManualNextTickScheduler();
    List<String> reevaluated = new ArrayList<>();
    long[] nanoTime = {0L};
    RuntimeSignalReevaluationScheduler scheduler =
        new RuntimeSignalReevaluationScheduler(
            nextTick,
            trainName -> {
              reevaluated.add(trainName);
              nanoTime[0] += 10L;
            },
            (trainName, failure) -> {},
            message -> {},
            Duration.ofNanos(10),
            () -> nanoTime[0]);

    scheduler.request("train-A");
    scheduler.request("train-B");
    scheduler.request("train-C");

    nextTick.runNextTick();

    assertEquals(List.of("train-A"), reevaluated);
    assertEquals(1, nextTick.pendingTasks());

    nextTick.runNextTick();
    assertEquals(List.of("train-A", "train-B"), reevaluated);
    assertEquals(1, nextTick.pendingTasks());

    nextTick.runNextTick();
    assertEquals(List.of("train-A", "train-B", "train-C"), reevaluated);
    assertEquals(0, nextTick.pendingTasks());
  }

  @Test
  void failureOfOneTrainDoesNotBlockRemainingBatch() {
    ManualNextTickScheduler nextTick = new ManualNextTickScheduler();
    List<String> reevaluated = new ArrayList<>();
    List<String> diagnostics = new ArrayList<>();
    List<String> failedTrains = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        withFrozenClock(
            nextTick,
            trainName -> {
              if ("train-fail".equals(trainName)) {
                throw new LinkageError("test-abi");
              }
              reevaluated.add(trainName);
            },
            (trainName, error) ->
                failedTrains.add(trainName + ":" + error.getClass().getSimpleName()),
            diagnostics::add);

    scheduler.request("train-fail");
    scheduler.request("train-ok");
    nextTick.runNextTick();

    assertEquals(List.of("train-ok"), reevaluated);
    assertEquals(List.of("train-fail:LinkageError"), failedTrains);
    assertTrue(diagnostics.stream().anyMatch(line -> line.contains("LinkageError")));
  }

  @Test
  void scheduleSubmissionFailureTriggersFailClosedHandler() {
    List<String> failedTrains = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        new RuntimeSignalReevaluationScheduler(
            task -> {
              throw new LinkageError("scheduler unavailable");
            },
            trainName -> {},
            (trainName, error) -> failedTrains.add(trainName),
            message -> {});

    scheduler.request("train-A");

    assertEquals(List.of("train-A"), failedTrains);
    assertEquals(1, scheduler.pendingCount());
  }

  @Test
  void failingDebugLoggerCannotDropRemainingBatch() {
    ManualNextTickScheduler nextTick = new ManualNextTickScheduler();
    List<String> reevaluated = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        withFrozenClock(
            nextTick,
            trainName -> {
              if ("train-fail".equals(trainName)) {
                throw new LinkageError("test-abi");
              }
              reevaluated.add(trainName);
            },
            (trainName, error) -> {},
            message -> {
              throw new LinkageError("logger unavailable");
            });

    scheduler.request("train-fail");
    scheduler.request("train-ok");
    nextTick.runNextTick();

    assertEquals(List.of("train-ok"), reevaluated);
  }

  @Test
  void closeDiscardsPendingWorkAndRejectsNewRequests() {
    ManualNextTickScheduler nextTick = new ManualNextTickScheduler();
    List<String> reevaluated = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        new RuntimeSignalReevaluationScheduler(nextTick, reevaluated::add);

    scheduler.request("train-A");
    scheduler.close();
    scheduler.request("train-B");
    nextTick.runNextTick();

    assertTrue(reevaluated.isEmpty());
    assertEquals(0, scheduler.pendingCount());
  }

  @Test
  void requestAfterCloseDoesNotAttemptAnotherNextTickSubmission() {
    ManualNextTickScheduler nextTick = new ManualNextTickScheduler();
    List<String> reevaluated = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        new RuntimeSignalReevaluationScheduler(nextTick, reevaluated::add);

    scheduler.close();
    scheduler.request("train-A");

    assertEquals(0, nextTick.pendingTasks());
    assertEquals(0, scheduler.pendingCount());
    assertTrue(reevaluated.isEmpty());
  }

  private static final class ManualNextTickScheduler
      implements RuntimeSignalReevaluationScheduler.NextTickScheduler {

    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void schedule(Runnable task) {
      tasks.add(task);
    }

    int pendingTasks() {
      return tasks.size();
    }

    void runNextTick() {
      int count = tasks.size();
      for (int i = 0; i < count; i++) {
        tasks.remove().run();
      }
    }
  }
}
