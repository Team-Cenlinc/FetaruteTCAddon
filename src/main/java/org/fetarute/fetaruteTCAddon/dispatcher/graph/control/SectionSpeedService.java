package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 车站区间限速服务。
 *
 * <p>`section` 是运维视图：命令输入一段业务区间，服务把它解析为当前调度图最短路径上的多条 {@link RailEdge}，最终仍写入 {@code
 * rail_edge_overrides}。运行时、ETA 与占用层继续只读取 edge override，不需要新增速度来源。
 */
public final class SectionSpeedService {

  private final RailGraphPathFinder pathFinder;

  public SectionSpeedService() {
    this(new RailGraphPathFinder());
  }

  public SectionSpeedService(RailGraphPathFinder pathFinder) {
    this.pathFinder = Objects.requireNonNull(pathFinder, "pathFinder");
  }

  /**
   * 预览业务区间会展开到哪些图边。
   *
   * @return empty 表示起终点缺失或不可达
   */
  public Optional<SectionSpeedPlan> plan(RailGraph graph, NodeId from, NodeId to) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    return pathFinder
        .shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance())
        .map(SectionSpeedPlan::fromPath);
  }

  /**
   * 构造一组“设置限速”的覆盖变更。
   *
   * <p>{@code tempUntil} 有值时写入临时限速字段；为空时写入长期限速字段。已有封锁、另一类限速会被保留。
   */
  public SectionSpeedChange buildSetChange(
      UUID worldId,
      SectionSpeedPlan plan,
      RailSpeed speed,
      Optional<Instant> tempUntil,
      Map<EdgeId, RailEdgeOverrideRecord> existingByEdge,
      Instant now) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(speed, "speed");
    Objects.requireNonNull(tempUntil, "tempUntil");
    Objects.requireNonNull(existingByEdge, "existingByEdge");
    Objects.requireNonNull(now, "now");

    Map<EdgeId, RailEdgeOverrideRecord> existing = normalizeExisting(existingByEdge);
    List<RailEdgeOverrideRecord> upserts = new ArrayList<>();
    for (EdgeId edgeId : plan.edgeIds()) {
      RailEdgeOverrideRecord current = existing.get(edgeId);
      RailEdgeOverrideRecord next =
          new RailEdgeOverrideRecord(
              worldId,
              edgeId,
              tempUntil.isPresent()
                  ? currentSpeedLimit(current)
                  : OptionalDouble.of(speed.blocksPerSecond()),
              tempUntil.isPresent()
                  ? OptionalDouble.of(speed.blocksPerSecond())
                  : currentTempSpeedLimit(current),
              tempUntil.isPresent() ? tempUntil : currentTempSpeedUntil(current),
              current != null && current.blockedManual(),
              current != null ? current.blockedUntil() : Optional.empty(),
              now);
      upserts.add(next);
    }
    return new SectionSpeedChange(List.copyOf(upserts), List.of(), plan.edgeIds().size());
  }

  /**
   * 构造一组“清除区间限速”的覆盖变更。
   *
   * <p>该操作会同时清理长期限速与临时限速，但保留封锁字段；若记录清理后为空，则返回删除操作。
   */
  public SectionSpeedChange buildClearChange(
      UUID worldId,
      SectionSpeedPlan plan,
      Map<EdgeId, RailEdgeOverrideRecord> existingByEdge,
      Instant now) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(existingByEdge, "existingByEdge");
    Objects.requireNonNull(now, "now");

    Map<EdgeId, RailEdgeOverrideRecord> existing = normalizeExisting(existingByEdge);
    List<RailEdgeOverrideRecord> upserts = new ArrayList<>();
    List<EdgeId> deletes = new ArrayList<>();
    int touched = 0;
    for (EdgeId edgeId : plan.edgeIds()) {
      RailEdgeOverrideRecord current = existing.get(edgeId);
      if (current == null
          || (current.speedLimitBlocksPerSecond().isEmpty()
              && current.tempSpeedLimitBlocksPerSecond().isEmpty()
              && current.tempSpeedLimitUntil().isEmpty())) {
        continue;
      }
      touched++;
      RailEdgeOverrideRecord next =
          new RailEdgeOverrideRecord(
              worldId,
              edgeId,
              OptionalDouble.empty(),
              OptionalDouble.empty(),
              Optional.empty(),
              current.blockedManual(),
              current.blockedUntil(),
              now);
      if (next.isEmpty()) {
        deletes.add(edgeId);
      } else {
        upserts.add(next);
      }
    }
    return new SectionSpeedChange(List.copyOf(upserts), List.copyOf(deletes), touched);
  }

  private static Map<EdgeId, RailEdgeOverrideRecord> normalizeExisting(
      Map<EdgeId, RailEdgeOverrideRecord> existingByEdge) {
    Map<EdgeId, RailEdgeOverrideRecord> normalized = new HashMap<>();
    for (Map.Entry<EdgeId, RailEdgeOverrideRecord> entry : existingByEdge.entrySet()) {
      EdgeId edgeId = entry.getKey();
      RailEdgeOverrideRecord record = entry.getValue();
      if (edgeId == null || record == null) {
        continue;
      }
      normalized.put(EdgeId.undirected(edgeId.a(), edgeId.b()), record);
    }
    return normalized;
  }

  private static OptionalDouble currentSpeedLimit(RailEdgeOverrideRecord current) {
    return current != null ? current.speedLimitBlocksPerSecond() : OptionalDouble.empty();
  }

  private static OptionalDouble currentTempSpeedLimit(RailEdgeOverrideRecord current) {
    return current != null ? current.tempSpeedLimitBlocksPerSecond() : OptionalDouble.empty();
  }

  private static Optional<Instant> currentTempSpeedUntil(RailEdgeOverrideRecord current) {
    return current != null ? current.tempSpeedLimitUntil() : Optional.empty();
  }

  /** 区间限速预览结果，供命令输出与写入逻辑复用。 */
  public record SectionSpeedPlan(
      NodeId from,
      NodeId to,
      List<NodeId> nodes,
      List<RailEdge> edges,
      List<EdgeId> edgeIds,
      long totalLengthBlocks) {

    public SectionSpeedPlan {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
      edges = edges == null ? List.of() : List.copyOf(edges);
      edgeIds = edgeIds == null ? List.of() : List.copyOf(edgeIds);
      if (nodes.isEmpty()) {
        throw new IllegalArgumentException("nodes 不能为空");
      }
      if (nodes.size() != edges.size() + 1 || edgeIds.size() != edges.size()) {
        throw new IllegalArgumentException("路径节点、边与 edgeIds 数量不匹配");
      }
      if (totalLengthBlocks < 0) {
        throw new IllegalArgumentException("totalLengthBlocks 不能为负");
      }
    }

    static SectionSpeedPlan fromPath(RailGraphPath path) {
      List<EdgeId> ids = new ArrayList<>();
      for (RailEdge edge : path.edges()) {
        ids.add(EdgeId.undirected(edge.id().a(), edge.id().b()));
      }
      return new SectionSpeedPlan(
          path.from(), path.to(), path.nodes(), path.edges(), ids, path.totalLengthBlocks());
    }
  }

  /** 对 {@code rail_edge_overrides} 的批量变更。 */
  public record SectionSpeedChange(
      List<RailEdgeOverrideRecord> upserts, List<EdgeId> deletes, int touchedEdges) {

    public SectionSpeedChange {
      upserts = upserts == null ? List.of() : List.copyOf(upserts);
      deletes = deletes == null ? List.of() : List.copyOf(deletes);
      if (touchedEdges < 0) {
        throw new IllegalArgumentException("touchedEdges 不能为负");
      }
    }
  }
}
