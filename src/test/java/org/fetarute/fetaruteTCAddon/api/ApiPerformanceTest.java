package org.fetarute.fetaruteTCAddon.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.ApiEdge;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.ApiNode;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.GraphSnapshot;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.NodeType;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API 性能测试。
 *
 * <p>验证 API 数据模型的构建和访问性能，确保：
 *
 * <ul>
 *   <li>大规模数据集的快照构建在合理时间内完成
 *   <li>数据访问无额外开销
 *   <li>不可变转换（List.copyOf）的性能影响可接受
 * </ul>
 *
 * <p>注意：这些测试主要用于检测性能回归，具体阈值可根据实际硬件调整。
 */
class ApiPerformanceTest {

  /** 构建大规模图快照的性能测试。 */
  @Test
  @DisplayName("构建 1000 节点的图快照应在 50ms 内完成")
  void buildLargeGraphSnapshotShouldBeEfficient() {
    final int nodeCount = 1000;
    final int edgeCount = 2000;

    // 准备测试数据
    List<ApiNode> nodes = new ArrayList<>(nodeCount);
    for (int i = 0; i < nodeCount; i++) {
      nodes.add(
          new ApiNode(
              "node-" + i,
              NodeType.values()[i % NodeType.values().length],
              new Position(i * 10.0, 64.0, i * -10.0),
              i % 2 == 0 ? Optional.of("站点" + i) : Optional.empty()));
    }

    List<ApiEdge> edges = new ArrayList<>(edgeCount);
    for (int i = 0; i < edgeCount; i++) {
      edges.add(
          new ApiEdge(
              "edge-" + i,
              "node-" + (i % nodeCount),
              "node-" + ((i + 1) % nodeCount),
              100 + (i % 100),
              20.0 + (i % 10),
              true,
              false));
    }

    // 测量构建时间
    Instant start = Instant.now();
    GraphSnapshot snapshot =
        new GraphSnapshot(nodes, edges, Instant.now(), nodeCount, edgeCount, 1);
    Duration buildTime = Duration.between(start, Instant.now());

    // 验证快照正确性
    assertEquals(nodeCount, snapshot.nodes().size());
    assertEquals(edgeCount, snapshot.edges().size());

    // 验证性能
    assertTrue(
        buildTime.toMillis() < 50,
        "构建 " + nodeCount + " 节点快照耗时 " + buildTime.toMillis() + "ms，超过 50ms 阈值");

    System.out.println("构建 " + nodeCount + " 节点快照耗时: " + buildTime.toMillis() + "ms");
  }

  /** 验证数据访问无额外开销。 */
  @Test
  @DisplayName("访问快照数据应无额外计算开销")
  void accessSnapshotDataShouldBeZeroCost() {
    // 创建小规模快照
    List<ApiNode> nodes =
        List.of(
            new ApiNode("A", NodeType.STATION, new Position(0, 0, 0), Optional.of("站点A")),
            new ApiNode("B", NodeType.STATION, new Position(100, 0, 0), Optional.of("站点B")));
    List<ApiEdge> edges = List.of(new ApiEdge("A-B", "A", "B", 100, 20.0, true, false));

    GraphSnapshot snapshot = new GraphSnapshot(nodes, edges, Instant.now(), 2, 1, 1);

    // 重复访问应该是 O(1) 操作
    final int accessCount = 100000;
    Instant start = Instant.now();
    for (int i = 0; i < accessCount; i++) {
      // 访问节点列表（应该是常量时间）
      List<ApiNode> n = snapshot.nodes();
      // 访问边列表
      List<ApiEdge> e = snapshot.edges();
      // 访问元数据
      int nc = snapshot.nodeCount();
      int ec = snapshot.edgeCount();
    }
    Duration accessTime = Duration.between(start, Instant.now());

    // 10万次访问应在合理时间内完成
    assertTrue(
        accessTime.toMillis() < 100,
        accessCount + " 次数据访问耗时 " + accessTime.toMillis() + "ms，超过 100ms 阈值");

    System.out.println(accessCount + " 次快照数据访问耗时: " + accessTime.toMillis() + "ms");
  }

  /** 验证 List.copyOf 的防御性复制性能。 */
  @Test
  @DisplayName("List.copyOf 防御性复制应在 10ms 内完成 1000 元素")
  void listCopyOfShouldBeEfficient() {
    final int elementCount = 1000;

    // 准备可变列表
    List<ApiNode> mutableList = new ArrayList<>(elementCount);
    for (int i = 0; i < elementCount; i++) {
      mutableList.add(
          new ApiNode("node-" + i, NodeType.STATION, new Position(i, i, i), Optional.empty()));
    }

    // 测量复制时间
    Instant start = Instant.now();
    List<ApiNode> immutableList = List.copyOf(mutableList);
    Duration copyTime = Duration.between(start, Instant.now());

    assertEquals(elementCount, immutableList.size());
    assertTrue(
        copyTime.toMillis() < 10,
        "复制 " + elementCount + " 元素耗时 " + copyTime.toMillis() + "ms，超过 10ms 阈值");

    System.out.println("复制 " + elementCount + " 元素耗时: " + copyTime.toMillis() + "ms");
  }

  /** 验证记录类型的创建性能。 */
  @Test
  @DisplayName("批量创建记录类型应高效")
  void recordCreationShouldBeEfficient() {
    final int createCount = 10000;

    Instant start = Instant.now();
    List<ApiNode> nodes = new ArrayList<>(createCount);
    for (int i = 0; i < createCount; i++) {
      nodes.add(
          new ApiNode(
              "node-" + i,
              NodeType.WAYPOINT,
              new Position(i * 0.1, 64.0, i * -0.1),
              Optional.of("节点" + i)));
    }
    Duration createTime = Duration.between(start, Instant.now());

    assertEquals(createCount, nodes.size());
    assertTrue(
        createTime.toMillis() < 100,
        "创建 " + createCount + " 个记录耗时 " + createTime.toMillis() + "ms，超过 100ms 阈值");

    System.out.println("创建 " + createCount + " 个 ApiNode 记录耗时: " + createTime.toMillis() + "ms");
  }

  /** 验证 Optional 的开销（信息性测试，不强制断言）。 */
  @Test
  @DisplayName("Optional 包装应无显著开销")
  void optionalShouldHaveMinimalOverhead() {
    final int iterations = 100000;

    // 预热 JVM
    for (int i = 0; i < 10000; i++) {
      Optional<String> opt = Optional.of("warmup-" + i);
      String value = opt.get();
    }

    // 测量 Optional.of 开销
    Instant start = Instant.now();
    for (int i = 0; i < iterations; i++) {
      Optional<String> opt = Optional.of("value-" + i);
      String value = opt.get();
    }
    Duration withOptional = Duration.between(start, Instant.now());

    // 测量直接访问开销
    start = Instant.now();
    for (int i = 0; i < iterations; i++) {
      String value = "value-" + i;
    }
    Duration withoutOptional = Duration.between(start, Instant.now());

    // 仅记录结果，不强制断言（JIT 优化可能导致结果不稳定）
    double ratio = (double) withOptional.toNanos() / Math.max(1, withoutOptional.toNanos());
    System.out.println(
        "Optional 开销: "
            + withOptional.toMillis()
            + "ms vs 直接访问: "
            + withoutOptional.toMillis()
            + "ms (比例: "
            + String.format("%.2f", ratio)
            + "x)");

    // 仅确保两者都能在合理时间内完成
    assertTrue(withOptional.toMillis() < 500, "Optional 操作耗时过长");
  }

  /** 验证 UUID 操作性能（API 中大量使用）。 */
  @Test
  @DisplayName("UUID 操作应高效")
  void uuidOperationsShouldBeEfficient() {
    final int iterations = 10000;

    // 测量 UUID 生成
    Instant start = Instant.now();
    List<UUID> uuids = new ArrayList<>(iterations);
    for (int i = 0; i < iterations; i++) {
      uuids.add(UUID.randomUUID());
    }
    Duration generateTime = Duration.between(start, Instant.now());

    // 测量 UUID 字符串转换
    start = Instant.now();
    for (UUID uuid : uuids) {
      String str = uuid.toString();
    }
    Duration toStringTime = Duration.between(start, Instant.now());

    // 测量 UUID 解析
    List<String> uuidStrings = uuids.stream().map(UUID::toString).toList();
    start = Instant.now();
    for (String str : uuidStrings) {
      UUID uuid = UUID.fromString(str);
    }
    Duration parseTime = Duration.between(start, Instant.now());

    System.out.println("UUID 生成 " + iterations + " 次: " + generateTime.toMillis() + "ms");
    System.out.println("UUID toString " + iterations + " 次: " + toStringTime.toMillis() + "ms");
    System.out.println("UUID 解析 " + iterations + " 次: " + parseTime.toMillis() + "ms");

    // 合理的性能预期
    assertTrue(generateTime.toMillis() < 200, "UUID 生成耗时过长");
    assertTrue(toStringTime.toMillis() < 50, "UUID toString 耗时过长");
    assertTrue(parseTime.toMillis() < 100, "UUID 解析耗时过长");
  }
}
