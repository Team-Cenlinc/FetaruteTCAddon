package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * Phase 1.8 死锁恢复层的队列 priority 意图判定回归。
 *
 * <p>恢复层唯一的执行手段就是给被选中的列车叠加一个有界的 queue priority 提升；提升不生效，整层就只是在空转。 实服 2026-09-13（42 分钟、44
 * 辆车）里它<b>一次都没生效过</b>：{@code SMART_UNLOCK_RESERVATION_CREATED} 78 次、 {@code
 * SMART_UNLOCK_RESERVATION_ROLLED_BACK} 78 次、{@code releasedReservationClaims} 恒为 0，而 {@code
 * SMART_UNLOCK_PRIORITY_INTENT_APPLIED} <b>为 0</b>。
 *
 * <p>成因是一次单位不匹配的比较：{@code applySmartUnlockPriorityIntent} 拿调用方传进来的<b>下一个路径点</b> （{@code
 * route.waypoints().get(currentIndex + 1)}，即下一个车站/通过点）去和预约里记的 {@code plannedDestinationNode}
 * （<b>授权窗口边界</b>，一个至多 8 条边以外的走行线图节点）比较。两者只在"下一个路径点恰好落在授权窗口末端"这个巧合下相等。
 *
 * <p>实服形状：MT-2F_Short 的路径点是 … → {@code SURC:S:PTK:1} → {@code SURC:S:RVS:1} → …， 而恢复计划的授权边界是 {@code
 * SURC:RVS:PTK:1:002}——PTK 与 RVS 之间的走行线节点，<b>根本不在路径点列表里</b>。 判定因此恒假，且回滚理由被写成 {@code
 * canonical-progress-window-moved}，而上一行的规范进度判定明明刚刚通过。
 *
 * <p>本用例固定的是判定本身：授权边界落在两个路径点之间是常态，不得据此撤销 priority 意图。
 */
class SmartUnlockPriorityIntentTest {

  private static final String ROUTE_ID = "SURC:MT:MT-2F_Short";
  private static final NodeId PTK = NodeId.of("SURC:S:PTK:1");
  private static final NodeId RVS = NodeId.of("SURC:S:RVS:1");
  private static final NodeId PPK = NodeId.of("SURC:PPK:RVS:1:001");

  /** PTK 与 RVS 之间的走行线节点；是授权边界，但不是路径点。 */
  private static final NodeId AUTHORITY_BOUNDARY = NodeId.of("SURC:RVS:PTK:1:002");

  /**
   * 授权边界落在两个路径点之间时，priority 意图仍须生效。
   *
   * <p>这是实服里 78 次预约全数作废、恢复层零执行的直接原因。
   */
  @Test
  void authorityBoundaryBetweenWaypointsStillGrantsPriorityIntent() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service = serviceWithProgressAtPtk(debugMessages);
    installReservation(service, AUTHORITY_BOUNDARY, PTK.value());

    DispatchPriorityResolution base = basePriority();
    // 调用方传的是下一个**路径点**（RVS 车站），而预约记的是**授权边界**（PTK–RVS 之间的走行线节点）。
    DispatchPriorityResolution selected =
        service.applySmartUnlockPriorityIntent("train-1", PTK, RVS, base);

    assertTrue(
        selected.priority() > base.priority(),
        "授权边界落在两个路径点之间是常态，不得因此撤销 priority 意图；debug=" + debugMessages);
    assertTrue(
        debugMessages.stream().anyMatch(m -> m.contains("SMART_UNLOCK_PRIORITY_INTENT_APPLIED")),
        "生效时必须留下 SMART_UNLOCK_PRIORITY_INTENT_APPLIED；debug=" + debugMessages);
  }

  /** 列车真的走了（路径 index 前进）时仍须回滚——收紧不得被这次放宽抵消。 */
  @Test
  void priorityIntentStillRollsBackWhenTrainActuallyAdvanced() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    RuntimeDispatchService service = serviceWith(registry, debugMessages);
    // 预约记的是 index 0，进度表却已经推进到 index 1。
    initProgress(registry, 1);
    installReservation(service, AUTHORITY_BOUNDARY, PTK.value());

    DispatchPriorityResolution base = basePriority();
    DispatchPriorityResolution selected =
        service.applySmartUnlockPriorityIntent("train-1", PTK, RVS, base);

    assertEquals(base, selected, "列车已推进，priority 意图必须撤销");
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                m ->
                    m.contains("SMART_UNLOCK_ROLLBACK_STARTED") && m.contains("route-index-moved")),
        "回滚理由必须点名真正变化的量；debug=" + debugMessages);
  }

  /** 列车已经离开计划采样的 current node 时仍须回滚，且理由不得继续冒充"规范进度窗口移动"。 */
  @Test
  void currentNodeMovedRollsBackWithItsOwnReason() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service = serviceWithProgressAtPtk(debugMessages);
    // 预约在 PPK 采样，而信号层解析出的 current node 是 PTK。
    installReservation(service, AUTHORITY_BOUNDARY, PPK.value());

    DispatchPriorityResolution base = basePriority();
    DispatchPriorityResolution selected =
        service.applySmartUnlockPriorityIntent("train-1", PTK, RVS, base);

    assertEquals(base, selected, "current node 与计划采样点不一致时必须撤销");
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                m ->
                    m.contains("SMART_UNLOCK_ROLLBACK_STARTED")
                        && m.contains("current-node-moved")),
        "理由必须是 current-node-moved，不得写成 canonical-progress-window-moved；debug=" + debugMessages);
  }

  private DispatchPriorityResolution basePriority() {
    return new DispatchPriorityResolution(
        0,
        DispatchPrioritySource.ROUTE_CODE_TAGS,
        Optional.empty(),
        Optional.of("op:l1:r1"),
        Optional.empty(),
        "operation_type_missing");
  }

  private RuntimeDispatchService serviceWithProgressAtPtk(List<String> debugMessages) {
    RouteProgressRegistry registry = new RouteProgressRegistry();
    initProgress(registry, 0);
    return serviceWith(registry, debugMessages);
  }

  private void initProgress(RouteProgressRegistry registry, int index) {
    RouteDefinition route =
        new RouteDefinition(RouteId.of(ROUTE_ID), List.of(PTK, RVS, PPK), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=" + index);
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.STOP, Instant.now());
  }

  private RuntimeDispatchService serviceWith(
      RouteProgressRegistry progressRegistry, List<String> debugMessages) {
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(20, 20.0);
    when(configManager.current())
        .thenReturn(
            new ConfigManager.ConfigView(
                base.configVersion(),
                base.debugEnabled(),
                base.locale(),
                base.storageSettings(),
                base.graphSettings(),
                base.autoStationSettings(),
                base.runtimeSettings(),
                base.spawnSettings(),
                base.trainConfigSettings(),
                base.reclaimSettings(),
                new ConfigManager.SmartDispatcherSettings(SmartDispatcherMode.ENFORCE),
                base.healthSettings()));
    return new RuntimeDispatchService(
        new SimpleOccupancyManager(
            (routeId, resource) -> java.time.Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
        mock(RailGraphService.class),
        mock(RouteDefinitionCache.class),
        progressRegistry,
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        new TrainConfigResolver(),
        debugMessages::add);
  }

  /** 按实服形状装一个预约：授权边界是走行线节点，规范身份取自 MT-2F_Short 的 index 0。 */
  private void installReservation(
      RuntimeDispatchService service, NodeId plannedDestination, String initialCurrentNode)
      throws Exception {
    Class<?> type =
        Class.forName(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService$SmartUnlockReservation");
    var constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object reservation =
        constructor.newInstance(
            "unlock-test",
            "train-1",
            "cascade:train-1",
            "FORWARD_TO_RELEASE_BLOCKER",
            List.of(OccupancyResource.forNode(AUTHORITY_BOUNDARY)),
            plannedDestination,
            "planhash",
            ROUTE_ID,
            0,
            System.currentTimeMillis() / 50L,
            100000,
            List.of("NODE:" + AUTHORITY_BOUNDARY.value()),
            List.of("follower"),
            initialCurrentNode,
            "-",
            -1L,
            true);
    var byCycleField =
        RuntimeDispatchService.class.getDeclaredField("smartUnlockReservationsByCycle");
    var byTrainField =
        RuntimeDispatchService.class.getDeclaredField("smartUnlockReservationsByTrain");
    byCycleField.setAccessible(true);
    byTrainField.setAccessible(true);
    @SuppressWarnings("unchecked")
    ConcurrentMap<String, Object> byCycle =
        (ConcurrentMap<String, Object>) byCycleField.get(service);
    @SuppressWarnings("unchecked")
    ConcurrentMap<String, Object> byTrain =
        (ConcurrentMap<String, Object>) byTrainField.get(service);
    byCycle.put("cascade:train-1", reservation);
    byTrain.put("train-1", reservation);
  }
}
