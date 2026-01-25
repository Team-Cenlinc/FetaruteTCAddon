package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
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
