package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DynamicTravelTimeModel} 单元测试。
 *
 * <p>验证动态旅行时间模型的核心计算逻辑：
 *
 * <ul>
 *   <li>边限速正确应用
 *   <li>fallback 速度在无限速时使用
 *   <li>加减速曲线计算（精确模式）
 *   <li>边间变速：相邻边限速不同时的减速/加速
 *   <li>初速参数：当前速度作为首边初速
 * </ul>
 */
class DynamicTravelTimeModelTest {

  private DynamicTravelTimeModel model;
  private static final double FALLBACK_SPEED = 6.0;

  @BeforeEach
  void setUp() {
    DynamicTravelTimeModel.TrainMotionParams params =
        new DynamicTravelTimeModel.TrainMotionParams(1.0, 1.2);
    model = new DynamicTravelTimeModel(params, FALLBACK_SPEED);
  }

  @Test
  void edgeTravelTime_withBaseSpeedLimit_usesLimit() {
    // 边长 120 blocks，限速 10 bps → 12 秒
    RailEdge edge = createEdge("A", "B", 120, 10.0);

    Optional<Duration> result = model.edgeTravelTime(null, edge, nodeId("A"), nodeId("B"));

    assertTrue(result.isPresent());
    assertEquals(Duration.ofSeconds(12), result.get());
  }

  @Test
  void edgeTravelTime_withoutSpeedLimit_usesFallback() {
    // 边长 60 blocks，无限速 → 使用 fallback 6 bps → 10 秒
    RailEdge edge = createEdge("A", "B", 60, 0.0);

    Optional<Duration> result = model.edgeTravelTime(null, edge, nodeId("A"), nodeId("B"));

    assertTrue(result.isPresent());
    assertEquals(Duration.ofSeconds(10), result.get());
  }

  @Test
  void edgeTravelTime_zeroLength_returnsEmpty() {
    RailEdge edge = createEdge("A", "B", 0, 6.0);

    Optional<Duration> result = model.edgeTravelTime(null, edge, nodeId("A"), nodeId("B"));

    assertTrue(result.isEmpty());
  }

  @Test
  void edgeTravelTime_negativeLength_returnsEmpty() {
    // 负数长度应返回 empty
    RailEdge edge = createEdge("A", "B", -10, 6.0);

    Optional<Duration> result = model.edgeTravelTime(null, edge, nodeId("A"), nodeId("B"));

    assertTrue(result.isEmpty());
  }

  @Test
  void computeTravelTimeWithSpeeds_cruiseOnly() {
    // 初速=末速=目标速度，纯匀速
    // 距离 100, 速度 10 → 10 秒
    double time = model.computeTravelTimeWithSpeeds(100, 10.0, 10.0, 10.0);

    assertEquals(10.0, time, 0.01);
  }

  @Test
  void computeTravelTimeWithSpeeds_accelerateOnly() {
    // 从 0 加速到 10 bps（accel = 1 bps²），然后匀速到终点
    // 加速阶段：t = (10-0)/1 = 10s, s = 0.5*1*10² = 50 blocks
    // 若总距离 150 blocks，匀速阶段：100 blocks / 10 bps = 10s
    // 总计约 20s（不考虑末速减速）
    double time = model.computeTravelTimeWithSpeeds(150, 0.0, 10.0, 10.0);

    // 加速 10s + 匀速 10s = 20s
    assertEquals(20.0, time, 0.5);
  }

  @Test
  void computeTravelTimeWithSpeeds_triangleProfile() {
    // 距离太短，无法完成完整加减速，使用三角形曲线
    // accel=1, decel=1.2, 从 0 到 0，距离 10 blocks
    // 应该是先加速后减速，峰值速度 < 目标速度
    double time = model.computeTravelTimeWithSpeeds(10, 0.0, 20.0, 0.0);

    // 三角形曲线时间应该合理（几秒内）
    assertTrue(time > 0 && time < 20, "三角形曲线时间应在合理范围内: " + time);
  }

  @Test
  void trainMotionParams_defaults() {
    DynamicTravelTimeModel.TrainMotionParams defaults =
        DynamicTravelTimeModel.TrainMotionParams.defaults();

    assertEquals(1.0, defaults.accelBps2());
    assertEquals(1.2, defaults.decelBps2());
  }

  @Test
  void trainMotionParams_invalidAccel_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DynamicTravelTimeModel.TrainMotionParams(0.0, 1.0));

    assertThrows(
        IllegalArgumentException.class,
        () -> new DynamicTravelTimeModel.TrainMotionParams(-1.0, 1.0));
  }

  @Test
  void constructor_invalidFallbackSpeed_throws() {
    DynamicTravelTimeModel.TrainMotionParams params =
        DynamicTravelTimeModel.TrainMotionParams.defaults();

    assertThrows(IllegalArgumentException.class, () -> new DynamicTravelTimeModel(params, 0.0));

    assertThrows(IllegalArgumentException.class, () -> new DynamicTravelTimeModel(params, -1.0));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 边间变速测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pathTravelTime_sameSpeedEdges_noSpeedChange() {
    // 三条边，限速都是 10 bps，总距离 300 blocks
    // 不需要变速，纯匀速：300 / 10 = 30 秒
    List<NodeId> nodes = List.of(nodeId("A"), nodeId("B"), nodeId("C"), nodeId("D"));
    List<RailEdge> edges =
        List.of(
            createEdge("A", "B", 100, 10.0),
            createEdge("B", "C", 100, 10.0),
            createEdge("C", "D", 100, 10.0));

    Optional<Duration> result = model.pathTravelTime(null, nodes, edges);

    assertTrue(result.isPresent());
    assertEquals(30, result.get().getSeconds());
  }

  @Test
  void pathTravelTime_decelerateForSlowerEdge() {
    // A->B 限速 10 bps，B->C 限速 5 bps
    // 进入第二边前需要从 10 减速到 5
    // 比纯匀速计算要多花时间
    List<NodeId> nodes = List.of(nodeId("A"), nodeId("B"), nodeId("C"));
    List<RailEdge> edges = List.of(createEdge("A", "B", 100, 10.0), createEdge("B", "C", 100, 5.0));

    Optional<Duration> result = model.pathTravelTime(null, nodes, edges);

    assertTrue(result.isPresent());
    // 如果纯匀速：100/10 + 100/5 = 10 + 20 = 30s
    // 实际需要减速，会更久
    long seconds = result.get().getSeconds();
    assertTrue(seconds >= 30, "减速场景应该 >= 30s，实际: " + seconds);
  }

  @Test
  void pathTravelTime_accelerateForFasterEdge() {
    // A->B 限速 5 bps，B->C 限速 10 bps
    // 第一边末速取 min(5, 10) = 5，进入第二边后需要加速到 10
    List<NodeId> nodes = List.of(nodeId("A"), nodeId("B"), nodeId("C"));
    List<RailEdge> edges = List.of(createEdge("A", "B", 100, 5.0), createEdge("B", "C", 100, 10.0));

    Optional<Duration> result = model.pathTravelTime(null, nodes, edges);

    assertTrue(result.isPresent());
    // 如果纯匀速：100/5 + 100/10 = 20 + 10 = 30s
    // 第二边需要从 5 加速到 10，会更久
    long seconds = result.get().getSeconds();
    assertTrue(seconds >= 30, "加速场景应该 >= 30s，实际: " + seconds);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 初速参数测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pathTravelTimeWithInitialSpeed_fromZero_accelerates() {
    // 从静止开始，需要加速到边限速 10 bps
    List<NodeId> nodes = List.of(nodeId("A"), nodeId("B"));
    List<RailEdge> edges = List.of(createEdge("A", "B", 100, 10.0));

    // 不带初速
    Optional<Duration> withoutInitial =
        model.pathTravelTimeWithInitialSpeed(null, nodes, edges, OptionalDouble.empty());
    // 带初速 0
    Optional<Duration> withZeroInitial =
        model.pathTravelTimeWithInitialSpeed(null, nodes, edges, OptionalDouble.of(0.0));

    assertTrue(withoutInitial.isPresent());
    assertTrue(withZeroInitial.isPresent());

    // 从静止加速需要更多时间
    // 不带初速默认用边限速作为初速（匀速），带初速 0 需要加速
    assertTrue(withZeroInitial.get().compareTo(withoutInitial.get()) > 0, "从静止加速应该比匀速更久");
  }

  @Test
  void pathTravelTimeWithInitialSpeed_atTargetSpeed_noAcceleration() {
    // 当前速度等于边限速，不需要加速
    List<NodeId> nodes = List.of(nodeId("A"), nodeId("B"));
    List<RailEdge> edges = List.of(createEdge("A", "B", 100, 10.0));

    Optional<Duration> withInitial =
        model.pathTravelTimeWithInitialSpeed(null, nodes, edges, OptionalDouble.of(10.0));
    Optional<Duration> withoutInitial =
        model.pathTravelTimeWithInitialSpeed(null, nodes, edges, OptionalDouble.empty());

    assertTrue(withInitial.isPresent());
    assertTrue(withoutInitial.isPresent());

    // 两者应该相同（都是匀速 10 bps）
    assertEquals(withoutInitial.get().toMillis(), withInitial.get().toMillis(), 100);
  }

  @Test
  void pathTravelTimeWithInitialSpeed_belowTargetSpeed_accelerates() {
    // 当前速度 5 bps，边限速 10 bps，需要加速
    List<NodeId> nodes = List.of(nodeId("A"), nodeId("B"));
    List<RailEdge> edges = List.of(createEdge("A", "B", 100, 10.0));

    Optional<Duration> slow =
        model.pathTravelTimeWithInitialSpeed(null, nodes, edges, OptionalDouble.of(5.0));
    Optional<Duration> fast =
        model.pathTravelTimeWithInitialSpeed(null, nodes, edges, OptionalDouble.of(10.0));

    assertTrue(slow.isPresent());
    assertTrue(fast.isPresent());

    // 从 5 加速到 10 需要更多时间
    assertTrue(slow.get().compareTo(fast.get()) > 0, "低速起步应该比高速起步更久");
  }

  @Test
  void pathTravelTimeWithInitialSpeed_multiEdge_carriesSpeed() {
    // 三条边测试速度状态传递
    // A->B: 10 bps, B->C: 5 bps, C->D: 10 bps
    // 初速 0，需要加速 -> 减速 -> 加速
    List<NodeId> nodes = List.of(nodeId("A"), nodeId("B"), nodeId("C"), nodeId("D"));
    List<RailEdge> edges =
        List.of(
            createEdge("A", "B", 100, 10.0),
            createEdge("B", "C", 100, 5.0),
            createEdge("C", "D", 100, 10.0));

    Optional<Duration> fromZero =
        model.pathTravelTimeWithInitialSpeed(null, nodes, edges, OptionalDouble.of(0.0));
    Optional<Duration> fromTen =
        model.pathTravelTimeWithInitialSpeed(null, nodes, edges, OptionalDouble.of(10.0));

    assertTrue(fromZero.isPresent());
    assertTrue(fromTen.isPresent());

    // 从静止开始应该比从高速开始更久
    assertTrue(fromZero.get().compareTo(fromTen.get()) > 0, "从静止开始应该比从高速开始更久");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 辅助方法
  // ─────────────────────────────────────────────────────────────────────────────

  private static NodeId nodeId(String value) {
    return NodeId.of(value);
  }

  private static RailEdge createEdge(String from, String to, int lengthBlocks, double speedLimit) {
    NodeId fromNode = nodeId(from);
    NodeId toNode = nodeId(to);
    EdgeId edgeId = EdgeId.undirected(fromNode, toNode);
    return new RailEdge(edgeId, fromNode, toNode, lengthBlocks, speedLimit, true, Optional.empty());
  }
}
