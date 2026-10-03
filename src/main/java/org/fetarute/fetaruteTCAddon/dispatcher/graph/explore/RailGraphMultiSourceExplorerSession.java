package org.fetarute.fetaruteTCAddon.dispatcher.graph.explore;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.ExploredRailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.ExploredRailEdgeAccumulator;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 多源 Dijkstra 的增量执行器：一次探索遍历整张轨道网络，计算节点之间最短区间长度（按 stepCost 计）。
 *
 * <p>用法：
 *
 * <ul>
 *   <li>通过 anchorsByNode 传入各节点的轨道锚点（已确保是轨道方块）
 *   <li>反复调用 {@link #step(int)} 分段处理，直到 {@link #isDone()}
 *   <li>完成后用 {@link #exploredEdges()} 获取区间长度与物理足迹；旧调用方可继续读取 {@link #edgeLengths()}
 * </ul>
 */
public final class RailGraphMultiSourceExplorerSession {

  private static final Comparator<Entry> ENTRY_ORDER =
      Comparator.comparingDouble(Entry::distance)
          .thenComparing(entry -> entry.owner().value())
          .thenComparingInt(entry -> entry.pos().x())
          .thenComparingInt(entry -> entry.pos().y())
          .thenComparingInt(entry -> entry.pos().z());

  private final RailBlockAccess access;
  private final int maxDistanceBlocks;
  private final Consumer<RailBlockPos> onJunction;
  private final PriorityQueue<Entry> queue = new PriorityQueue<>(ENTRY_ORDER);
  private final Map<RailBlockPos, Visit> visits = new HashMap<>();
  private final Map<EdgeId, ExploredRailEdgeAccumulator> exploredEdges = new HashMap<>();

  /** 记录每个锚点位置所属的节点，用于判断波前是否到达了另一个节点的锚点。 */
  private final Map<RailBlockPos, NodeId> anchorOwners = new HashMap<>();

  private long processed;

  public RailGraphMultiSourceExplorerSession(
      Map<NodeId, Set<RailBlockPos>> anchorsByNode, RailBlockAccess access, int maxDistanceBlocks) {
    this(anchorsByNode, access, maxDistanceBlocks, null);
  }

  /**
   * @param onJunction 可选回调：用于标记“分叉/道岔候选位置”。
   *     <p>默认依赖 {@link RailBlockAccess#neighbors(RailBlockPos)} 的邻居数量；若访问器是 {@link
   *     TrainCartsRailBlockAccess}，则改用其 {@code junctionCount} 做更保守的判定，避免相邻但不连通的轨道误报。
   */
  public RailGraphMultiSourceExplorerSession(
      Map<NodeId, Set<RailBlockPos>> anchorsByNode,
      RailBlockAccess access,
      int maxDistanceBlocks,
      Consumer<RailBlockPos> onJunction) {
    Objects.requireNonNull(anchorsByNode, "anchorsByNode");
    this.access = Objects.requireNonNull(access, "access");
    if (maxDistanceBlocks <= 0) {
      throw new IllegalArgumentException("maxDistanceBlocks 必须为正数");
    }
    this.maxDistanceBlocks = maxDistanceBlocks;
    this.onJunction = onJunction;

    for (Map.Entry<NodeId, Set<RailBlockPos>> entry : anchorsByNode.entrySet()) {
      NodeId owner = entry.getKey();
      if (owner == null) {
        continue;
      }
      for (RailBlockPos anchor : entry.getValue()) {
        if (anchor == null) {
          continue;
        }
        if (!access.isRail(anchor)) {
          continue;
        }
        // 记录锚点归属，用于后续判断"波前是否到达了另一个节点的锚点"
        anchorOwners.putIfAbsent(anchor, owner);
        Visit existing = visits.get(anchor);
        if (existing == null || existing.distance > 0.0) {
          visits.put(anchor, new Visit(owner, 0.0, new PathTrace(anchor, null)));
          queue.add(new Entry(anchor, owner, 0.0));
        }
      }
    }
  }

  /**
   * @return 本轮 step 实际处理的队列元素数量。
   */
  public int step(int budget) {
    if (budget <= 0) {
      throw new IllegalArgumentException("budget 必须为正数");
    }
    int consumed = 0;
    while (consumed < budget && !queue.isEmpty()) {
      Entry entry = queue.poll();
      if (entry == null) {
        continue;
      }
      RailBlockPos current = entry.pos;
      Visit currentVisit = visits.get(current);
      if (currentVisit == null) {
        continue;
      }
      if (!currentVisit.owner.equals(entry.owner)
          || Math.abs(currentVisit.distance - entry.distance) > 1e-9) {
        continue;
      }
      processed++;
      consumed++;
      if (currentVisit.distance >= maxDistanceBlocks) {
        continue;
      }

      // 如果当前位置是另一个节点的锚点（不是自己的锚点），则停止扩展
      // 这确保波前不会"穿越"其他节点，从而只连接轨道上直接相邻的节点
      NodeId currentAnchorOwner = anchorOwners.get(current);
      if (currentAnchorOwner != null
          && !currentAnchorOwner.value().equals(currentVisit.owner.value())
          && currentVisit.distance > 0.0) {
        // 当前位置是另一个节点的锚点，且我们是从远处走过来的（distance > 0）
        // 说明我们刚好"到达"了另一个节点，应该停止继续扩展
        continue;
      }

      Set<RailBlockPos> neighbors = access.neighbors(current);
      if (onJunction != null && isJunction(access, current, neighbors)) {
        onJunction.accept(current);
      }

      for (RailBlockPos neighbor : neighbors) {
        if (!access.isRail(neighbor)) {
          continue;
        }
        double stepCost = access.stepCost(current, neighbor);
        if (!Double.isFinite(stepCost) || stepCost <= 0.0) {
          continue;
        }
        double nextDistance = currentVisit.distance + stepCost;
        if (!Double.isFinite(nextDistance)
            || nextDistance <= 0.0
            || nextDistance > maxDistanceBlocks) {
          continue;
        }

        // 检查邻居是否是另一个节点的锚点
        NodeId neighborAnchorOwner = anchorOwners.get(neighbor);
        boolean neighborIsOtherNodeAnchor =
            neighborAnchorOwner != null
                && !neighborAnchorOwner.value().equals(currentVisit.owner.value());

        if (neighborIsOtherNodeAnchor) {
          // 邻居是另一个节点的锚点：记录边，但不继续扩展
          if (!currentVisit.owner.value().equals(neighborAnchorOwner.value())) {
            int candidateInt = (int) Math.round(nextDistance);
            if (candidateInt > 0) {
              EdgeId edgeId = EdgeId.undirected(currentVisit.owner, neighborAnchorOwner);
              recordCandidate(
                  edgeId, candidateInt, new PathTrace(neighbor, currentVisit.pathTrace));
            }
          }
          // 不将邻居加入队列，因为它是另一个节点的锚点，该节点会自己从那里开始扩展
          continue;
        }

        Visit neighborVisit = visits.get(neighbor);
        if (neighborVisit == null || nextDistance + 1e-9 < neighborVisit.distance) {
          visits.put(
              neighbor,
              new Visit(
                  currentVisit.owner,
                  nextDistance,
                  new PathTrace(neighbor, currentVisit.pathTrace)));
          queue.add(new Entry(neighbor, currentVisit.owner, nextDistance));
          continue;
        }

        if (!neighborVisit.owner.equals(currentVisit.owner)) {
          // 两个不同源的波前在非锚点的轨道上相遇：说明它们之间没有其他节点
          // 注意：如果两个 owner 的 NodeId 值相同（同一节点的多个 anchor），会形成自环，需要跳过
          if (currentVisit.owner.value().equals(neighborVisit.owner.value())) {
            // 同一节点的不同 anchor 之间存在轨道路径，不应创建自环边
            continue;
          }
          double candidate = nextDistance + neighborVisit.distance;
          if (!Double.isFinite(candidate) || candidate <= 0.0) {
            continue;
          }
          int candidateInt = (int) Math.round(candidate);
          if (candidateInt <= 0) {
            continue;
          }
          EdgeId edgeId = EdgeId.undirected(currentVisit.owner, neighborVisit.owner);
          recordCandidate(
              edgeId,
              candidateInt,
              new PathTrace(neighbor, currentVisit.pathTrace),
              neighborVisit.pathTrace);
        }
      }
    }
    return consumed;
  }

  public boolean isDone() {
    return queue.isEmpty();
  }

  public long processedSteps() {
    return processed;
  }

  public int queueSize() {
    return queue.size();
  }

  public int visitedRailBlocks() {
    return visits.size();
  }

  public Map<EdgeId, Integer> edgeLengths() {
    if (!isDone()) {
      throw new IllegalStateException("探索尚未完成，无法读取 edgeLengths");
    }
    Map<EdgeId, Integer> lengths = new HashMap<>();
    exploredEdges().forEach((edgeId, edge) -> lengths.put(edgeId, edge.lengthBlocks()));
    return Map.copyOf(lengths);
  }

  /**
   * 返回当前已经发现的区间证据快照。
   *
   * <p>探索队列尚未耗尽时，返回的足迹会明确标记为不完整；调用方不得据此建立联锁索引。
   */
  public Map<EdgeId, ExploredRailEdge> exploredEdges() {
    Map<EdgeId, ExploredRailEdge> snapshot = new HashMap<>();
    boolean complete = isDone() && access.supportsExactBlockFootprint();
    exploredEdges.forEach(
        (edgeId, accumulator) -> snapshot.put(edgeId, accumulator.snapshot(complete)));
    return Map.copyOf(snapshot);
  }

  private void recordCandidate(EdgeId edgeId, int lengthBlocks, PathTrace... traces) {
    Set<RailFootprintCell> cells = new HashSet<>();
    for (PathTrace trace : traces) {
      PathTrace current = trace;
      while (current != null) {
        cells.add(new RailFootprintCell(current.pos.x(), current.pos.y(), current.pos.z()));
        current = current.previous;
      }
    }
    exploredEdges
        .computeIfAbsent(edgeId, ignored -> new ExploredRailEdgeAccumulator())
        .recordCandidate(lengthBlocks, cells);
  }

  private boolean isJunction(
      RailBlockAccess access, RailBlockPos current, Set<RailBlockPos> neighbors) {
    return RailBlockAccess.isJunction(access, current, neighbors);
  }

  private record Visit(NodeId owner, double distance, PathTrace pathTrace) {
    private Visit {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(pathTrace, "pathTrace");
      if (!Double.isFinite(distance) || distance < 0.0) {
        throw new IllegalArgumentException("distance 不能为负");
      }
    }
  }

  private record PathTrace(RailBlockPos pos, PathTrace previous) {
    private PathTrace {
      Objects.requireNonNull(pos, "pos");
    }
  }

  private record Entry(RailBlockPos pos, NodeId owner, double distance) {
    private Entry {
      Objects.requireNonNull(pos, "pos");
      Objects.requireNonNull(owner, "owner");
      if (!Double.isFinite(distance) || distance < 0.0) {
        throw new IllegalArgumentException("distance 不能为负");
      }
    }
  }
}
