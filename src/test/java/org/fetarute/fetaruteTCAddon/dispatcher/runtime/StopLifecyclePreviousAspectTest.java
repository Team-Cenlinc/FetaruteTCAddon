package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 停因留痕必须带上「变红之前是什么灯」——这是「绿灯突然变红」唯一还站得住的测量点。
 *
 * <p>{@code 98cd49f} 把这个探针装在 {@code stageSignalAspectForAuthorityAndAdvisory} 的 hardBlocked
 * 分支上。实服第二十八轮 {@code SIGNAL_ASPECT_STAGING} 共 513 行， 而该分支 <b>0
 * 行</b>——硬停根本不走那条路，探针装在了到不了的分支上（本仓反复出现的 「守卫条件与真实永不相交」，这一次是我自己犯的）。{@code SMART_STOP_LIFECYCLE}
 * 在必留名单上、同轮 4543 行带 STOP，是唯一稳定覆盖硬停的落点。
 *
 * <p>两格都要钉死，因为它们对「红灯是不是突然的」给出相反答案：已发布 PROCEED ⇒ 确实是绿转红；从未发布（{@code -}）⇒ 无从判断，<b>不能</b>当成「本来就是红的」。
 *
 * <p>单独成类而不是并进 {@code RuntimeDispatchServiceTest}：后者已经两万行， 多一条用例就会触发 SpotBugs 的 {@code
 * SKIPPED_CLASS_TOO_BIG}，整类不再被静态分析 ——和生产侧 997/1000 是同一个毛病。
 */
class StopLifecyclePreviousAspectTest {

  @Test
  @DisplayName("停因留痕记下变红前的灯位，且「从未发布」与「本来就红」可区分")
  void stopLifecycleRecordsPreviousAspect() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            mock(OccupancyManager.class),
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            mock(ConfigManager.class),
            null,
            new TrainConfigResolver(),
            debugMessages::add);

    // 从未发布过物理灯位的车：必须是 `-`，不得回退成 STOP。
    service.recordStopState(
        RuntimeStopState.plannedStop(
            "never-published",
            "LAYOVER_HOLD",
            "layover-ready-check-pending",
            RuntimeStopState.ReleaseCondition.LAYOVER_READY_AND_AUTHORITY_REISSUED,
            RuntimeStopState.RetryTrigger.LAYOVER_RECHECK,
            Instant.now()));

    // 正在显示绿灯的车被停下：必须留下 PROCEED，这才叫「绿转红」。
    service.publishedPhysicalSignals.put("green-train", SignalAspect.PROCEED);
    service.recordStopState(
        RuntimeStopState.plannedStop(
            "green-train",
            "PROTECTIVE_RETAIN_HOLD",
            "protective-retain:blocked-by:NODE/PROTECTIVE_RETAIN",
            RuntimeStopState.ReleaseCondition.BLOCKING_RESOURCES_RELEASED_OR_TRANSFERRED,
            RuntimeStopState.RetryTrigger.OCCUPANCY_CHANGE_OR_PERIODIC_RECHECK,
            Instant.now()));

    assertTrue(
        line(debugMessages, "never-published").contains("previousAspect=-"),
        debugMessages::toString);
    assertTrue(
        line(debugMessages, "green-train").contains("previousAspect=PROCEED"),
        debugMessages::toString);
  }

  private static String line(List<String> messages, String trainName) {
    return messages.stream()
        .filter(message -> message.contains("train=" + trainName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("缺少留痕 train=" + trainName + "：" + messages));
  }
}
