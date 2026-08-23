package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeDispatchListenerTest {

  @Test
  void groupCreateWaitsUntilNextTickBeforeReadingInheritedTemplateTags() {
    Queue<Runnable> nextTickTasks = new ArrayDeque<>();
    List<Object> refreshed = new java.util.ArrayList<>();
    Object group = new Object();

    RuntimeDispatchListener.deferGroupCreateRefresh(nextTickTasks::add, group, refreshed::add);

    assertTrue(refreshed.isEmpty());
    assertEquals(1, nextTickTasks.size());
    nextTickTasks.remove().run();
    assertEquals(List.of(group), refreshed);
  }

  @Test
  void groupCreateSynchronouslyClosesAuthorizationForPersistentRollbackTombstone() {
    Queue<Runnable> nextTickTasks = new ArrayDeque<>();
    RuntimeDispatchService dispatchService = mock(RuntimeDispatchService.class);
    RuntimeDispatchListener listener =
        new RuntimeDispatchListener(dispatchService, nextTickTasks::add);
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(dispatchService.containMaterializedSpawnRollbackOnCreate(any())).thenReturn(true);

    listener.handleGroupCreate(train, () -> {});

    verify(dispatchService).containMaterializedSpawnRollbackOnCreate(any());
    assertEquals(1, nextTickTasks.size(), "完整身份读取仍应推迟到下一 tick");
  }

  @Test
  void groupCreateDefersSignalRefreshUntilDepotSpawnCompletesItsSameTickTransaction() {
    Queue<Runnable> nextTickTasks = new ArrayDeque<>();
    RuntimeDispatchService dispatchService = mock(RuntimeDispatchService.class);
    RuntimeDispatchListener listener =
        new RuntimeDispatchListener(dispatchService, nextTickTasks::add);
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);

    listener.handleGroupCreate(train, () -> dispatchService.handleSignalTick(train, false));

    verify(dispatchService, never()).handleSignalTick(train, false);
    assertEquals(1, nextTickTasks.size());

    nextTickTasks.remove().run();

    verify(dispatchService).handleSignalTick(train, false);
  }

  @Test
  void groupUnloadUsesUnloadBoundaryInsteadOfExactRemovalBoundary() {
    RuntimeDispatchService dispatchService = mock(RuntimeDispatchService.class);
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);

    RuntimeDispatchListener.notifyGroupUnloaded(dispatchService, train);

    verify(dispatchService).handleTrainUnloaded(any());
  }

  @Test
  void shouldTreatBulkMemberRemovalFollowedByGroupRemovalAsNormalCleanup() {
    Queue<Runnable> nextTickTasks = new ArrayDeque<>();
    AtomicInteger abnormalCleanupCalls = new AtomicInteger();
    AtomicInteger normalCleanupCalls = new AtomicInteger();
    RuntimeDispatchListener.DeferredIdentityBatch<Object, int[]> pendingSplits =
        pendingSplitBatch(nextTickTasks, abnormalCleanupCalls, new AtomicInteger());
    Object sourceGroup = new Object();

    addMemberRemoval(pendingSplits, sourceGroup);
    addMemberRemoval(pendingSplits, sourceGroup);
    addMemberRemoval(pendingSplits, sourceGroup);
    pendingSplits.cancel(sourceGroup);
    normalCleanupCalls.incrementAndGet();
    nextTickTasks.remove().run();

    assertEquals(0, abnormalCleanupCalls.get());
    assertEquals(1, normalCleanupCalls.get());
  }

  @Test
  void shouldTreatBulkMemberRemovalFollowedByGroupUnloadAsNormalCleanup() {
    Queue<Runnable> nextTickTasks = new ArrayDeque<>();
    AtomicInteger abnormalCleanupCalls = new AtomicInteger();
    AtomicInteger normalCleanupCalls = new AtomicInteger();
    RuntimeDispatchListener.DeferredIdentityBatch<Object, int[]> pendingSplits =
        pendingSplitBatch(nextTickTasks, abnormalCleanupCalls, new AtomicInteger());
    Object sourceGroup = new Object();

    addMemberRemoval(pendingSplits, sourceGroup);
    addMemberRemoval(pendingSplits, sourceGroup);
    pendingSplits.cancel(sourceGroup);
    normalCleanupCalls.incrementAndGet();
    nextTickTasks.remove().run();

    assertEquals(0, abnormalCleanupCalls.get());
    assertEquals(1, normalCleanupCalls.get());
  }

  @Test
  void shouldKeepStandaloneGroupUnloadOnNormalCleanupPath() {
    Queue<Runnable> nextTickTasks = new ArrayDeque<>();
    AtomicInteger abnormalCleanupCalls = new AtomicInteger();
    AtomicInteger normalCleanupCalls = new AtomicInteger();
    RuntimeDispatchListener.DeferredIdentityBatch<Object, int[]> pendingSplits =
        pendingSplitBatch(nextTickTasks, abnormalCleanupCalls, new AtomicInteger());

    pendingSplits.cancel(new Object());
    normalCleanupCalls.incrementAndGet();

    assertTrue(nextTickTasks.isEmpty());
    assertEquals(0, abnormalCleanupCalls.get());
    assertEquals(1, normalCleanupCalls.get());
  }

  @Test
  void shouldClassifySurvivingMemberRemovalOnceOnNextTick() {
    Queue<Runnable> nextTickTasks = new ArrayDeque<>();
    AtomicInteger abnormalCleanupCalls = new AtomicInteger();
    AtomicInteger aggregatedMemberCount = new AtomicInteger();
    RuntimeDispatchListener.DeferredIdentityBatch<Object, int[]> pendingSplits =
        pendingSplitBatch(nextTickTasks, abnormalCleanupCalls, aggregatedMemberCount);

    addMemberRemoval(pendingSplits, new Object());

    assertEquals(0, abnormalCleanupCalls.get());
    nextTickTasks.remove().run();
    assertEquals(1, abnormalCleanupCalls.get());
    assertEquals(1, aggregatedMemberCount.get());
  }

  @Test
  void shouldAggregateMultipleMemberRemovalsIntoOneUnexpectedSplit() {
    Queue<Runnable> nextTickTasks = new ArrayDeque<>();
    AtomicInteger abnormalCleanupCalls = new AtomicInteger();
    AtomicInteger aggregatedMemberCount = new AtomicInteger();
    RuntimeDispatchListener.DeferredIdentityBatch<Object, int[]> pendingSplits =
        pendingSplitBatch(nextTickTasks, abnormalCleanupCalls, aggregatedMemberCount);
    Object sourceGroup = new Object();

    addMemberRemoval(pendingSplits, sourceGroup);
    addMemberRemoval(pendingSplits, sourceGroup);
    addMemberRemoval(pendingSplits, sourceGroup);

    assertEquals(1, nextTickTasks.size());
    nextTickTasks.remove().run();
    assertEquals(1, abnormalCleanupCalls.get());
    assertEquals(3, aggregatedMemberCount.get());
  }

  @Test
  void shouldKeepSourceAndEveryIdentityDistinctDetachedSurvivor() {
    Object source = new Object();
    Object detached = new Object();

    List<Object> survivors =
        RuntimeDispatchListener.identityDistinctMatching(
            List.of(source, detached, detached), ignored -> true);

    assertEquals(List.of(source, detached), survivors);
  }

  @Test
  void shouldAdvancePassStationOnTrainGroupEnter() {
    assertTrue(
        RuntimeDispatchListener.shouldHandleStationPassProgress(SignActionType.GROUP_ENTER, false));
  }

  @Test
  void shouldAdvancePassStationOnCartHeadMemberEnter() {
    assertTrue(
        RuntimeDispatchListener.shouldHandleStationPassProgress(SignActionType.MEMBER_ENTER, true));
  }

  @Test
  void shouldNotAdvanceStationOnTrainMemberEnter() {
    assertFalse(
        RuntimeDispatchListener.shouldHandleStationPassProgress(
            SignActionType.MEMBER_ENTER, false));
  }

  private static RuntimeDispatchListener.DeferredIdentityBatch<Object, int[]> pendingSplitBatch(
      Queue<Runnable> nextTickTasks,
      AtomicInteger abnormalCleanupCalls,
      AtomicInteger aggregatedMemberCount) {
    return new RuntimeDispatchListener.DeferredIdentityBatch<>(
        nextTickTasks::add,
        batch -> {
          abnormalCleanupCalls.incrementAndGet();
          aggregatedMemberCount.set(batch[0]);
        });
  }

  private static void addMemberRemoval(
      RuntimeDispatchListener.DeferredIdentityBatch<Object, int[]> pendingSplits,
      Object sourceGroup) {
    pendingSplits.add(sourceGroup, () -> new int[] {1}, batch -> batch[0]++);
  }
}
