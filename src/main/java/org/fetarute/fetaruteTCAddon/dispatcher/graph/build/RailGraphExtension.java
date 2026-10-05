package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.ExploredRailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphComponentIndex;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResourceResolver;

/**
 * 增量增补调度图：只从图里还没有的节点牌子出发，往从未探索过的轨道方向补节点与区间，不改动任何已有区间。
 *
 * <p>判断"探索过没有"靠旧图的逐边足迹（cell 索引）：新牌子的锚点落在旧区间足迹上，就是落在已有区间中间，需要拆分区间，只能整图 build。
 * 因此旧图必须带完整足迹。各步都是纯计算，世界访问（找锚点、查道岔）由调用方以函数传入。
 */
public final class RailGraphExtension {

  /** 走进旧区间中间（而不是旧节点锚点）时探索器看到的哨兵节点前缀。 */
  static final String MID_EDGE_PREFIX = "EXPLORED-EDGE:";

  private RailGraphExtension() {}

  /**
   * 比对库里的节点与内存图，找出要增补的新节点。
   *
   * @param served 内存里正在用的图
   * @param stored 库里该世界的全部节点（节点牌子增删已同步）
   * @return 新节点，或拒绝原因：有节点被删、换了位置，或没有新节点
   */
  public static Planned plan(RailGraph served, List<RailNodeRecord> stored) {
    Objects.requireNonNull(served, "served");
    Objects.requireNonNull(stored, "stored");
    Map<NodeId, RailNodeRecord> storedById = new HashMap<>();
    for (RailNodeRecord record : stored) {
      storedById.put(record.nodeId(), record);
    }
    for (RailNode node : served.nodes()) {
      RailNodeRecord record = storedById.get(node.id());
      if (record == null) {
        return Planned.refused("节点 " + node.id().value() + " 已经拆除；增补只能加节点，请用 build 重建");
      }
      Vector pos = node.worldPosition();
      if (pos.getBlockX() != record.x()
          || pos.getBlockY() != record.y()
          || pos.getBlockZ() != record.z()) {
        return Planned.refused("节点 " + node.id().value() + " 换了位置；增补不改已有节点，请用 build 重建");
      }
    }
    List<RailNodeRecord> newNodes = new ArrayList<>();
    for (RailNodeRecord record : stored) {
      if (served.findNode(record.nodeId()).isEmpty()) {
        newNodes.add(record);
      }
    }
    if (newNodes.isEmpty()) {
      return Planned.refused("没有需要增补的节点牌子：图里已包含库里的全部节点");
    }
    return new Planned(List.copyOf(newNodes), Optional.empty());
  }

  /**
   * 新节点的锚点不能落在已探索的区间上。
   *
   * @return 拒绝原因；都在未探索轨道上时为空
   */
  public static Optional<String> checkUnexplored(
      RailInterlockingState state, Map<NodeId, Set<RailBlockPos>> newAnchors) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(newAnchors, "newAnchors");
    if (!state.cellCoverageAvailable() || !state.coverage().complete()) {
      return Optional.of("当前调度图没有完整的区间足迹，无法判断哪里探索过；请先用 build 完整重建一次");
    }
    for (Map.Entry<NodeId, Set<RailBlockPos>> entry : newAnchors.entrySet()) {
      for (RailBlockPos anchor : entry.getValue()) {
        Set<EdgeId> covering = state.edgesForCell(cell(anchor));
        if (!covering.isEmpty()) {
          EdgeId edge = covering.iterator().next();
          return Optional.of(
              "节点 "
                  + entry.getKey().value()
                  + " 落在已有区间 "
                  + edge.a().value()
                  + " ↔ "
                  + edge.b().value()
                  + " 上，需要拆分区间；请用 build 重建");
        }
      }
    }
    return Optional.empty();
  }

  /**
   * 核验探索结果，转成要追加的区间。
   *
   * @param explored 从新节点出发探索到的区间
   * @param evidenceComplete 探索器是否保有完整足迹证据
   * @param unmarkedJunction 某段轨道是否是没挂节点牌子的道岔
   * @param nodesById 新节点与旧图节点（用于取端点与过滤跨股道直连）
   * @return 追加用的区间与足迹，或拒绝原因
   */
  public static Verified verify(
      Map<EdgeId, ExploredRailEdge> explored,
      boolean evidenceComplete,
      Predicate<RailBlockPos> unmarkedJunction,
      Map<NodeId, RailNode> nodesById) {
    Objects.requireNonNull(explored, "explored");
    Objects.requireNonNull(unmarkedJunction, "unmarkedJunction");
    Objects.requireNonNull(nodesById, "nodesById");
    for (EdgeId edge : explored.keySet()) {
      Optional<NodeId> midEdge = midEdgeEndpoint(edge);
      if (midEdge.isPresent()) {
        String other = (edge.a().equals(midEdge.get()) ? edge.b() : edge.a()).value();
        return Verified.refused(
            "从 "
                + other
                + " 出发的新轨道接进了已有区间中间（"
                + midEdge.get().value().substring(MID_EDGE_PREFIX.length())
                + "），那里需要道岔节点；请补设节点牌子或用 build 重建");
      }
    }
    if (!evidenceComplete) {
      return Verified.refused("新区间的足迹不完整（可能有区块未加载）；请靠近新轨道重试，或用 build 重建");
    }
    Map<EdgeId, ExploredRailEdge> kept =
        org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdgeValidator
            .filterCrossTrackExploredEdges(explored, nodesById);
    List<RailEdge> edges = new ArrayList<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new HashMap<>();
    for (Map.Entry<EdgeId, ExploredRailEdge> entry : kept.entrySet()) {
      EdgeId edgeId = entry.getKey();
      RailEdgeFootprint footprint = entry.getValue().footprint();
      if (!footprint.participatesInInterlocking()) {
        return Verified.refused(
            "新区间 " + edgeId.a().value() + " ↔ " + edgeId.b().value() + " 的足迹不完整；请用 build 重建");
      }
      for (RailFootprintCell cell : footprint.cells()) {
        RailBlockPos pos = new RailBlockPos(cell.x(), cell.y(), cell.z());
        if (unmarkedJunction.test(pos)) {
          return Verified.refused(
              "新轨道 ("
                  + pos.x()
                  + ", "
                  + pos.y()
                  + ", "
                  + pos.z()
                  + ") 处有道岔但没有节点牌子；请补设 switcher 牌子或用 build 重建");
        }
      }
      RailNode a = nodesById.get(edgeId.a());
      RailNode b = nodesById.get(edgeId.b());
      if (a == null || b == null) {
        continue;
      }
      edges.add(
          new RailEdge(
              edgeId,
              edgeId.a(),
              edgeId.b(),
              entry.getValue().lengthBlocks(),
              0.0,
              true,
              Optional.of(
                  new org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdgeMetadata(
                      a.waypointMetadata(), b.waypointMetadata()))));
      footprints.put(edgeId, footprint);
    }
    return new Verified(List.copyOf(edges), Map.copyOf(footprints), Optional.empty());
  }

  /**
   * 增补后资源键会变的旧区间所对应的旧资源。
   *
   * <p>要看的旧区间有两类：增补连上的连通分量里的（区段键、分量键可能变），以及和新区间足迹重叠、多出联锁区的（平交可以跨分量，
   * 不能只看连通性）。联锁整体完整度变了时所有区间的联锁键都变，全部要看。旧区间的资源集合有任何变化，就把它的全部旧资源算进来——
   * 正持有这些资源的列车与之后按新键申请的列车会互相看不见。别处的区间不必逐条比对。
   *
   * @param touched 新节点（增补连上的分量从它们出发找）
   */
  public static Set<OccupancyResource> changedResources(
      RailGraph before, RailGraph after, Collection<NodeId> touched) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(after, "after");
    Set<NodeId> component = componentOf(after, touched);
    RailInterlockingState beforeState = interlockingState(before);
    RailInterlockingState afterState = interlockingState(after);
    boolean everyEdge =
        beforeState.coverage().complete() != afterState.coverage().complete()
            || beforeState.available() != afterState.available();
    Set<EdgeId> zonePartners = new HashSet<>();
    for (RailEdge edge : after.edges()) {
      if (before.findNode(edge.from()).isPresent() && before.findNode(edge.to()).isPresent()) {
        continue;
      }
      for (String key : afterState.zoneKeysForEdge(edge.id())) {
        afterState
            .zoneInfo(key)
            .ifPresent(
                zone -> {
                  zonePartners.add(zone.firstEdge());
                  zonePartners.add(zone.secondEdge());
                });
      }
    }
    Set<OccupancyResource> changed = new LinkedHashSet<>();
    for (RailEdge edge : before.edges()) {
      if (!everyEdge
          && !component.contains(edge.from())
          && !component.contains(edge.to())
          && !zonePartners.contains(edge.id())) {
        continue;
      }
      Set<OccupancyResource> old =
          new HashSet<>(OccupancyResourceResolver.resourcesForEdge(before, edge));
      Set<OccupancyResource> now =
          new HashSet<>(OccupancyResourceResolver.resourcesForEdge(after, edge));
      if (!old.equals(now)) {
        changed.addAll(old);
      }
    }
    return changed;
  }

  private static RailInterlockingState interlockingState(RailGraph graph) {
    return graph
            instanceof
            org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport
            support
        ? support.interlockingState()
        : RailInterlockingState.unavailable();
  }

  /**
   * 增补后连通分量键变了、且旧键上挂着运维限速的分量。
   *
   * <p>分量键取分量内最小的节点 ID；新节点 ID 更小、或把两个分量连成一个时键会变，按旧键存的分量限速会悄悄失效。
   */
  public static Set<String> componentKeysLosingCautions(
      RailGraph before, RailGraph after, Collection<NodeId> touched, Set<String> cautionedKeys) {
    if (cautionedKeys.isEmpty()) {
      return Set.of();
    }
    RailGraphComponentIndex beforeIndex = RailGraphComponentIndex.fromGraph(before);
    RailGraphComponentIndex afterIndex = RailGraphComponentIndex.fromGraph(after);
    Set<String> lost = new LinkedHashSet<>();
    for (NodeId node : componentOf(after, touched)) {
      if (before.findNode(node).isEmpty()) {
        continue;
      }
      String oldKey = beforeIndex.componentKey(node);
      if (oldKey != null
          && cautionedKeys.contains(oldKey)
          && !oldKey.equals(afterIndex.componentKey(node))) {
        lost.add(oldKey);
      }
    }
    return lost;
  }

  private static Set<NodeId> componentOf(RailGraph graph, Collection<NodeId> seeds) {
    Set<NodeId> visited = new HashSet<>();
    ArrayDeque<NodeId> queue = new ArrayDeque<>();
    for (NodeId seed : seeds) {
      if (seed != null && graph.findNode(seed).isPresent() && visited.add(seed)) {
        queue.add(seed);
      }
    }
    while (!queue.isEmpty()) {
      NodeId current = queue.poll();
      for (RailEdge edge : graph.edgesFrom(current)) {
        NodeId next = edge.from().equals(current) ? edge.to() : edge.from();
        if (visited.add(next)) {
          queue.add(next);
        }
      }
    }
    return visited;
  }

  private static Optional<NodeId> midEdgeEndpoint(EdgeId edge) {
    if (edge.a().value().startsWith(MID_EDGE_PREFIX)) {
      return Optional.of(edge.a());
    }
    if (edge.b().value().startsWith(MID_EDGE_PREFIX)) {
      return Optional.of(edge.b());
    }
    return Optional.empty();
  }

  private static RailFootprintCell cell(RailBlockPos pos) {
    return new RailFootprintCell(pos.x(), pos.y(), pos.z());
  }

  /**
   * @param newNodes 要增补的新节点
   * @param refusal 不能增补时的原因
   */
  public record Planned(List<RailNodeRecord> newNodes, Optional<String> refusal) {
    public Planned {
      newNodes = List.copyOf(newNodes);
    }

    static Planned refused(String reason) {
      return new Planned(List.of(), Optional.of(reason));
    }
  }

  /**
   * @param edges 要追加的区间
   * @param footprints 新区间的完整足迹
   * @param refusal 不能增补时的原因
   */
  public record Verified(
      List<RailEdge> edges, Map<EdgeId, RailEdgeFootprint> footprints, Optional<String> refusal) {
    public Verified {
      edges = List.copyOf(edges);
      footprints = Map.copyOf(footprints);
    }

    static Verified refused(String reason) {
      return new Verified(List.of(), Map.of(), Optional.of(reason));
    }
  }

  /**
   * 探索器用的锚点查找：只回答 {@code get}。
   *
   * <p>新节点的锚点预先算好；旧节点按区块分桶的空间索引就近找，锚点用到时才算并缓存——不必为整张图预先找锚点。走到旧区间足迹上、却不是旧节点锚点的格子时返回哨兵，
   * 让探索器在那里停下并记一条区间，事后据此拒绝。
   */
  public static final class AnchorLookup extends AbstractMap<RailBlockPos, NodeId> {

    private final Map<RailBlockPos, NodeId> newAnchors;
    private final Map<Long, List<RailNode>> existingByChunk = new HashMap<>();
    private final Map<NodeId, Set<RailBlockPos>> resolvedAnchors = new HashMap<>();
    private final Function<RailNode, Set<RailBlockPos>> anchorResolver;
    private final RailInterlockingState state;
    private final int searchRadius;

    /**
     * @param existing 旧图节点
     * @param anchorResolver 旧节点的轨道锚点（按建图同样的半径规则）
     * @param searchRadius 节点牌子离它锚点的最远距离
     */
    public AnchorLookup(
        Map<RailBlockPos, NodeId> newAnchors,
        Collection<RailNode> existing,
        Function<RailNode, Set<RailBlockPos>> anchorResolver,
        RailInterlockingState state,
        int searchRadius) {
      this.newAnchors = Map.copyOf(newAnchors);
      this.anchorResolver = Objects.requireNonNull(anchorResolver, "anchorResolver");
      this.state = Objects.requireNonNull(state, "state");
      this.searchRadius = Math.max(0, searchRadius);
      for (RailNode node : existing) {
        Vector pos = node.worldPosition();
        existingByChunk
            .computeIfAbsent(
                chunkKey(pos.getBlockX(), pos.getBlockZ()), ignored -> new ArrayList<>())
            .add(node);
      }
    }

    @Override
    public NodeId get(Object key) {
      if (!(key instanceof RailBlockPos pos)) {
        return null;
      }
      NodeId fresh = newAnchors.get(pos);
      if (fresh != null) {
        return fresh;
      }
      for (int cx = (pos.x() - searchRadius) >> 4; cx <= (pos.x() + searchRadius) >> 4; cx++) {
        for (int cz = (pos.z() - searchRadius) >> 4; cz <= (pos.z() + searchRadius) >> 4; cz++) {
          for (RailNode node :
              existingByChunk.getOrDefault(chunkKey(cx << 4, cz << 4), List.of())) {
            if (isNear(node.worldPosition(), pos)
                && resolvedAnchors
                    .computeIfAbsent(node.id(), id -> anchorResolver.apply(node))
                    .contains(pos)) {
              return node.id();
            }
          }
        }
      }
      if (!state.edgesForCell(cell(pos)).isEmpty()) {
        return NodeId.of(MID_EDGE_PREFIX + pos.x() + ", " + pos.y() + ", " + pos.z());
      }
      return null;
    }

    @Override
    public boolean containsKey(Object key) {
      return get(key) != null;
    }

    /** 只列出新节点的锚点；旧节点按需解析，不在这里枚举。 */
    @Override
    public Set<Entry<RailBlockPos, NodeId>> entrySet() {
      return newAnchors.entrySet();
    }

    private boolean isNear(Vector nodePos, RailBlockPos pos) {
      return Math.abs(nodePos.getBlockX() - pos.x()) <= searchRadius
          && Math.abs(nodePos.getBlockY() - pos.y()) <= searchRadius
          && Math.abs(nodePos.getBlockZ() - pos.z()) <= searchRadius;
    }

    private static long chunkKey(int blockX, int blockZ) {
      return ((long) (blockX >> 4) << 32) ^ ((blockZ >> 4) & 0xffffffffL);
    }
  }
}
