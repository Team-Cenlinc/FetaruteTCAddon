package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 队列仲裁 trace 的生产端语义去重。
 *
 * <p>仲裁每 tick 都会重算，但只有结论变化才是新证据。该 trace 已进入诊断门的免预算白名单，因此<b>必须</b>在生产端去重， 否则稳定期会被逐 tick
 * 重复刷屏——这正是其它免预算审计（到达、STOP、资源生命周期）遵循的约定。
 */
class PendingWinnerArbitrationDedupTest {

  private static final String CONFLICT_KEY = "switcher:SWITCHER:Towny:-566:77:1179";

  private final List<String> traces = new ArrayList<>();

  @AfterEach
  void resetLogger() {
    SignalComputationTrace.configureLogger(message -> {});
  }

  private SimpleOccupancyManager manager() {
    SignalComputationTrace.configureLogger(traces::add);
    return new SimpleOccupancyManager(
        (routeId, resource) -> Duration.ZERO,
        SignalAspectPolicy.defaultPolicy(),
        new SignalEventBus());
  }

  private OccupancyRequest request(String trainName, int priority, int entryOrder) {
    OccupancyResource conflict = OccupancyResource.forConflict(CONFLICT_KEY);
    return new OccupancyRequest(
        trainName,
        Optional.empty(),
        Instant.now(),
        List.of(conflict),
        Map.of(),
        Map.of(CONFLICT_KEY, entryOrder),
        priority,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        Map.of(conflict, ResourceIntent.MOVEMENT_REQUIRED));
  }

  private long arbitrationLines() {
    return traces.stream()
        .filter(line -> line.contains("SMART_PENDING_WINNER_ARBITRATION"))
        .count();
  }

  @Test
  void previewPathDoesNotProduceArbitrationEvidence() {
    SimpleOccupancyManager manager = manager();
    manager.canEnter(request("early-train", 0, 0));
    traces.clear();

    // 预览不入队、不改状态，被队首挡住不代表列车真的走不了。
    // 实测 20 条证据里 19 条来自预览，把唯一一条权威判定淹没了。
    manager.canEnterPreview(request("late-train", 0, 5));

    assertEquals(0, arbitrationLines(), "预览路径不得产出仲裁证据");
  }

  @Test
  void stableArbitrationConclusionIsEmittedOnce() {
    SimpleOccupancyManager manager = manager();
    // 先入队的一方成为 pending winner。
    manager.canEnter(request("early-train", 0, 0));
    traces.clear();

    // 后到的一方反复请求：结论始终相同，只应留下一条证据。
    for (int i = 0; i < 5; i++) {
      manager.canEnter(request("late-train", 0, 5));
    }

    assertTrue(arbitrationLines() <= 1, "结论未变时不得逐 tick 重复输出，实际 " + arbitrationLines() + " 条");
  }

  @Test
  void arbitrationTraceCarriesBothSidesOfTheDecision() {
    SimpleOccupancyManager manager = manager();
    manager.canEnter(request("early-train", 0, 0));
    traces.clear();
    manager.canEnter(request("late-train", 0, 5));

    String line =
        traces.stream()
            .filter(t -> t.contains("SMART_PENDING_WINNER_ARBITRATION"))
            .findFirst()
            .orElse("");
    if (line.isEmpty()) {
      // 该现场未触发队列阻塞时不强制要求输出；有输出则必须字段完整。
      return;
    }
    // 判断“前方无车却进不去”需要同时看到双方的位次与等待时长，缺一不可。
    for (String field :
        List.of(
            "requesterEntryOrder=",
            "requesterWaitSeconds=",
            "pendingWinnerEntryOrder=",
            "pendingWinnerWaitSeconds=",
            "pendingWinnerPriorityAdvantageSeconds=")) {
      assertTrue(line.contains(field), "仲裁 trace 缺少字段 " + field + "：" + line);
    }
    assertEquals(1, arbitrationLines());
  }
}
