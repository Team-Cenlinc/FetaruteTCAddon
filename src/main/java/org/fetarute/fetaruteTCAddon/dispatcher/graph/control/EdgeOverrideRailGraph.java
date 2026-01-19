package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphCorridorInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphCorridorSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * RailGraph 覆盖视图：在“图快照（rail_edges）”之上叠加 edge overrides（封锁等），不修改原图实例。
 *
 * <p>当前仅覆盖 {@link #isBlocked(EdgeId)}，其余方法均委托给底层图。
 */
public final class EdgeOverrideRailGraph implements RailGraph, RailGraphCorridorSupport {

  private final RailGraph delegate;
  private final Map<EdgeId, RailEdgeOverrideRecord> overrides;
  private final Instant now;

  public EdgeOverrideRailGraph(
      RailGraph delegate, Map<EdgeId, RailEdgeOverrideRecord> overrides, Instant now) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.overrides = Objects.requireNonNull(overrides, "overrides");
    this.now = Objects.requireNonNull(now, "now");
  }

  @Override
  public Collection<RailNode> nodes() {
    return delegate.nodes();
  }

  @Override
  public Collection<RailEdge> edges() {
    return delegate.edges();
  }

  @Override
  public Optional<RailNode> findNode(NodeId id) {
    return delegate.findNode(id);
  }

  @Override
  public Set<RailEdge> edgesFrom(NodeId id) {
    return delegate.edgesFrom(id);
  }

  @Override
  public boolean isBlocked(EdgeId id) {
    if (delegate.isBlocked(id)) {
      return true;
    }
    if (id == null) {
      return false;
    }
    if (id.a() == null || id.b() == null) {
      return false;
    }
    EdgeId normalized = EdgeId.undirected(id.a(), id.b());
    RailEdgeOverrideRecord override = overrides.get(normalized);
    if (override == null) {
      return false;
    }
    return override.isBlockedEffective(now);
  }

  @Override
  public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
    if (delegate instanceof RailGraphCorridorSupport support) {
      return support.conflictKeyForEdge(edgeId);
    }
    return Optional.empty();
  }

  @Override
  public Optional<RailGraphCorridorInfo> corridorInfoForEdge(EdgeId edgeId) {
    if (delegate instanceof RailGraphCorridorSupport support) {
      return support.corridorInfoForEdge(edgeId);
    }
    return Optional.empty();
  }
}
