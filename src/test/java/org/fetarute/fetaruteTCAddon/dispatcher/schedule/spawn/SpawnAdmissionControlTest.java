package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 发车准入控制（全网在网列车上限）与拥挤评分标定的回归。
 *
 * <p>背景：实服十三轮里拥挤闸门一次都没触发过——14 辆车时最高分 0.464，阈值 0.72。原因有二： 评分只数 EDGE claim（实测 EDGE 5550 / NODE
 * 5471，漏掉约一半），且三个分量全是"本 route 自己" 的局部量，全网堵死时仍然可以很低。本测试钉住修复后的判别口径与那道硬上限。
 */
class SpawnAdmissionControlTest {

  // ---------- 评分公式 ----------

  @Test
  void capDisabledRestoresPreviousThreeComponentWeights() {
    // trainCap <= 0 时必须与引入准入控制之前逐位一致，否则"评分变了"与"上限生效了"无法分别证伪。
    double expected = 0.4D * 0.55D + 0.6D * 0.30D + 0.2D * 0.15D;
    assertEquals(
        expected,
        SimpleTicketAssigner.combineCongestionScore(0.4D, 0.6D, 0.2D, 1.0D, 0),
        1e-9,
        "准入控制关闭时应退回旧的三分量权重");
    assertEquals(
        expected,
        SimpleTicketAssigner.combineCongestionScore(0.4D, 0.6D, 0.2D, 0.0D, -5),
        1e-9,
        "负的 cap 同样视为关闭");
  }

  @Test
  void networkPressureEntersScoreOnlyWhenCapConfigured() {
    // 同样的局部量，仅因为全网压力不同，分数必须拉开——这正是旧评分做不到的那件事。
    double empty = SimpleTicketAssigner.combineCongestionScore(0.1D, 0.1D, 0.1D, 0.0D, 16);
    double full = SimpleTicketAssigner.combineCongestionScore(0.1D, 0.1D, 0.1D, 1.0D, 16);
    assertTrue(full > empty, "全网压力应当抬高分数");
    assertEquals(0.30D, full - empty, 1e-9, "network 分量权重应为 0.30");
  }

  @Test
  void scoreStaysWithinUnitRange() {
    assertEquals(1.0D, SimpleTicketAssigner.combineCongestionScore(1, 1, 1, 1, 16), 1e-9);
    assertEquals(0.0D, SimpleTicketAssigner.combineCongestionScore(0, 0, 0, 0, 16), 1e-9);
    assertEquals(
        1.0D,
        SimpleTicketAssigner.combineCongestionScore(2.0D, 2.0D, 2.0D, 2.0D, 16),
        1e-9,
        "越界输入应被 clamp 到 1");
  }

  @Test
  void fullNetworkAloneDoesNotReachDefaultHoldThreshold() {
    // 记录一个会被下一轮实测推翻的预期：仅靠 network 撑满（0.30）够不到 0.58，
    // 因此闸门仍需要局部量参与。若下一轮日志显示分布与此不符，应改的是阈值而不是本断言的存在。
    double onlyNetwork = SimpleTicketAssigner.combineCongestionScore(0.0D, 0.0D, 0.0D, 1.0D, 16);
    assertEquals(0.30D, onlyNetwork, 1e-9);
    assertTrue(onlyNetwork < 0.58D, "单靠全网压力不应独自触发默认阈值");
  }

  // ---------- 节点 key ----------

  @Test
  void routeNodeKeysCoverEveryWaypointCaseInsensitively() {
    RouteDefinition route = mock(RouteDefinition.class);
    when(route.waypoints())
        .thenReturn(
            List.of(NodeId.of("SURC:S:JBS:2"), NodeId.of("surc:s:jbs:2"), NodeId.of("A:B")));
    Set<String> keys = SimpleTicketAssigner.collectRouteNodeKeys(route);
    assertEquals(Set.of("surc:s:jbs:2", "a:b"), keys, "节点 key 应大小写归一并去重");
  }

  @Test
  void routeNodeKeysEmptyWhenNoWaypoints() {
    assertTrue(SimpleTicketAssigner.collectRouteNodeKeys(null).isEmpty());
    RouteDefinition route = mock(RouteDefinition.class);
    when(route.waypoints()).thenReturn(List.of());
    assertTrue(SimpleTicketAssigner.collectRouteNodeKeys(route).isEmpty());
  }

  @Test
  void normalizeNodeKeyTrimsAndLowercases() {
    assertEquals("surc:s:jbs:2", SimpleTicketAssigner.normalizeNodeKey("  SURC:S:JBS:2 "));
    assertEquals("", SimpleTicketAssigner.normalizeNodeKey(null));
    assertEquals("", SimpleTicketAssigner.normalizeNodeKey("   "));
  }

  // ---------- 硬上限 ----------

  @Test
  void fleetCapHoldsOnlyWhenActiveReachesCap() {
    assertFalse(assignerWith(16, 15).shouldHoldByFleetCap(line(), route()), "低于上限应放行");
    assertTrue(assignerWith(16, 16).shouldHoldByFleetCap(line(), route()), "达到上限应拦下");
    assertTrue(assignerWith(16, 40).shouldHoldByFleetCap(line(), route()), "超过上限应拦下");
  }

  @Test
  void fleetCapDisabledByZeroNeverHolds() {
    assertFalse(assignerWith(0, 999).shouldHoldByFleetCap(line(), route()), "cap=0 表示禁用准入控制");
  }

  @Test
  void fleetCapTraceIsEmittedWhetherOrNotItHolds() {
    // 只在触发时才打印的闸门等于没有闸门——本会话已经在拥挤闸门上验证过这一点。
    List<String> logs = new ArrayList<>();
    assignerWith(16, 3, logs::add).shouldHoldByFleetCap(line(), route());
    assertEquals(1, logs.size(), "未触发时同样要报告");
    assertTrue(logs.get(0).startsWith("SMART_SPAWN_FLEET_CAP "), logs.get(0));
    assertTrue(logs.get(0).contains("active=3"), logs.get(0));
    assertTrue(logs.get(0).contains("cap=16"), logs.get(0));
    assertTrue(logs.get(0).contains("holding=false"), logs.get(0));
  }

  @Test
  void fleetCapTraceDedupesUntilStateChanges() {
    List<String> logs = new ArrayList<>();
    SimpleTicketAssigner assigner = assignerWith(16, 3, logs::add);
    assigner.shouldHoldByFleetCap(line(), route());
    assigner.shouldHoldByFleetCap(line(), route());
    assigner.shouldHoldByFleetCap(line(), route());
    assertEquals(1, logs.size(), "同一 (route, active, holding) 不应随调用次数放大");
  }

  // ---------- 脚手架 ----------

  private static Line line() {
    Line line = mock(Line.class);
    when(line.code()).thenReturn("WS");
    return line;
  }

  private static Route route() {
    Route route = mock(Route.class);
    when(route.code()).thenReturn("WS-2N_FullR");
    return route;
  }

  private static SimpleTicketAssigner assignerWith(int cap, int activeTrains) {
    return assignerWith(cap, activeTrains, message -> {});
  }

  private static SimpleTicketAssigner assignerWith(
      int cap, int activeTrains, Consumer<String> debugLogger) {
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    ConfigManager.SpawnSettings spawnSettings = mock(ConfigManager.SpawnSettings.class);
    when(configManager.current()).thenReturn(view);
    when(view.spawnSettings()).thenReturn(spawnSettings);
    when(spawnSettings.maxActiveTrains()).thenReturn(cap);

    Map<String, RouteProgressRegistry.RouteProgressEntry> progress = new HashMap<>();
    for (int i = 0; i < activeTrains; i++) {
      progress.put("train-" + i, mock(RouteProgressRegistry.RouteProgressEntry.class));
    }
    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.snapshotProgressEntries()).thenReturn(progress);

    return new SimpleTicketAssigner(
        mock(SpawnManager.class),
        mock(DepotSpawner.class),
        mock(OccupancyManager.class),
        mock(RailGraphService.class),
        mock(RouteDefinitionCache.class),
        runtimeDispatchService,
        configManager,
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        debugLogger,
        Duration.ofSeconds(2),
        1,
        10);
  }
}
