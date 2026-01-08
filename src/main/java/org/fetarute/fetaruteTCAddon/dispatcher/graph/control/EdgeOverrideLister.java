package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** Edge 运维覆盖（限速/临时限速/封锁）的筛选与管理工具。 */
public final class EdgeOverrideLister {

  private EdgeOverrideLister() {}

  public enum Kind {
    /** 长期限速（speed_limit_bps）。 */
    SPEED,
    /** 临时限速（temp_speed_limit_*）。 */
    RESTRICT,
    /** 封锁（blocked_*）。 */
    BLOCK,
    /** 任意（用于 overrides list）。 */
    ANY
  }

  public record Query(Kind kind, boolean includeInactiveTtl, Optional<NodeId> nodeFilter) {
    public Query {
      Objects.requireNonNull(kind, "kind");
      nodeFilter = nodeFilter != null ? nodeFilter : Optional.empty();
    }
  }

  /** 按查询条件筛选 overrides。 */
  public static List<RailEdgeOverrideRecord> filter(
      List<RailEdgeOverrideRecord> records, Instant now, Query query) {
    Objects.requireNonNull(records, "records");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(query, "query");

    List<RailEdgeOverrideRecord> results = new ArrayList<>();
    for (RailEdgeOverrideRecord record : records) {
      if (record == null || record.edgeId() == null) {
        continue;
      }
      EdgeId edgeId = EdgeId.undirected(record.edgeId().a(), record.edgeId().b());

      if (query.nodeFilter().isPresent()) {
        NodeId node = query.nodeFilter().get();
        if (node == null || (!node.equals(edgeId.a()) && !node.equals(edgeId.b()))) {
          continue;
        }
      }

      boolean keep =
          switch (query.kind()) {
            case SPEED -> record.speedLimitBlocksPerSecond().isPresent();
            case RESTRICT -> {
              if (record.tempSpeedLimitBlocksPerSecond().isEmpty()) {
                yield false;
              }
              yield query.includeInactiveTtl() || record.isTempSpeedActive(now);
            }
            case BLOCK -> {
              if (record.blockedManual()) {
                yield true;
              }
              if (record.blockedUntil().isEmpty()) {
                yield false;
              }
              yield query.includeInactiveTtl() || record.isBlockedEffective(now);
            }
            case ANY -> !record.isEmpty();
          };
      if (!keep) {
        continue;
      }

      results.add(record);
    }

    results.sort(
        Comparator.comparing(
                (RailEdgeOverrideRecord r) ->
                    EdgeId.undirected(r.edgeId().a(), r.edgeId().b()).a().value())
            .thenComparing(r -> EdgeId.undirected(r.edgeId().a(), r.edgeId().b()).b().value()));
    return results;
  }

  /**
   * 返回“孤儿 overrides”：库中存在，但当前图快照中不存在对应边。
   *
   * <p>用于线路改造/节点改名后清理历史覆盖。
   */
  public static List<RailEdgeOverrideRecord> orphanOverrides(
      List<RailEdgeOverrideRecord> records, RailGraph graph) {
    Objects.requireNonNull(records, "records");
    Objects.requireNonNull(graph, "graph");

    Set<EdgeId> edgeIds = new HashSet<>();
    for (RailEdge edge : graph.edges()) {
      if (edge == null) {
        continue;
      }
      EdgeId id = edge.id();
      if (id != null) {
        edgeIds.add(EdgeId.undirected(id.a(), id.b()));
      } else if (edge.from() != null && edge.to() != null) {
        edgeIds.add(EdgeId.undirected(edge.from(), edge.to()));
      }
    }

    List<RailEdgeOverrideRecord> results = new ArrayList<>();
    for (RailEdgeOverrideRecord record : records) {
      if (record == null || record.edgeId() == null) {
        continue;
      }
      EdgeId normalized = EdgeId.undirected(record.edgeId().a(), record.edgeId().b());
      if (!edgeIds.contains(normalized)) {
        results.add(record);
      }
    }
    results.sort(
        Comparator.comparing(
                (RailEdgeOverrideRecord r) ->
                    EdgeId.undirected(r.edgeId().a(), r.edgeId().b()).a().value())
            .thenComparing(r -> EdgeId.undirected(r.edgeId().a(), r.edgeId().b()).b().value()));
    return results;
  }
}
