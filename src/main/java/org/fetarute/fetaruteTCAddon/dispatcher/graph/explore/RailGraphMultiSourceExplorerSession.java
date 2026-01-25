package org.fetarute.fetaruteTCAddon.dispatcher.graph.explore;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 多源 Dijkstra 的增量执行器：一次探索遍历整张轨道网络，计算节点之间最短区间长度（按 stepCost 计）。
 *
 * <p>用法：
 *
 * <ul>
 *   <li>通过 anchorsByNode 传入各节点的轨道锚点（已确保是轨道方块）
 *   <li>反复调用 {@link #step(int)} 分段处理，直到 {@link #isDone()}
 *   <li>完成后用 {@link #edgeLengths()} 获取区间长度结果
 * </ul>
 */
public final class RailGraphMultiSourceExplorerSession {

  private final RailBlockAccess access;
  private final int maxDistanceBlocks;
  private final Consumer<RailBlockPos> onJunction;
  private final PriorityQueue<Entry> queue =
      new PriorityQueue<>(java.util.Comparator.comparingDouble(Entry::distance));
  private final Map<RailBlockPos, Visit> visits = new HashMap<>();
  private final Map<EdgeId, Integer> bestLengths = new HashMap<>();

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
        Visit existing = visits.get(anchor);
        if (existing == null || existing.distance > 0.0) {
          visits.put(anchor, new Visit(owner, 0.0));
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

        Visit neighborVisit = visits.get(neighbor);
        if (neighborVisit == null || nextDistance + 1e-9 < neighborVisit.distance) {
          visits.put(neighbor, new Visit(currentVisit.owner, nextDistance));
          queue.add(new Entry(neighbor, currentVisit.owner, nextDistance));
          continue;
        }

        if (!neighborVisit.owner.equals(currentVisit.owner)) {
          // 两个不同源的波前在边 (current-neighbor) 上相遇：最短距离候选为 dist(a)+w+dist(b)
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
          Integer existingBest = bestLengths.get(edgeId);
          if (existingBest == null || candidateInt < existingBest) {
            bestLengths.put(edgeId, candidateInt);
          }
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
    bestLengths.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= 0);
    return Map.copyOf(bestLengths);
  }

  private boolean isJunction(
      RailBlockAccess access, RailBlockPos current, Set<RailBlockPos> neighbors) {
    if (access instanceof TrainCartsRailBlockAccess tcAccess) {
      int junctions = tcAccess.junctionCount(current);
      return junctions >= 3;
    }
    return neighbors.size() >= 3;
  }

  private record Visit(NodeId owner, double distance) {
    private Visit {
      Objects.requireNonNull(owner, "owner");
      if (!Double.isFinite(distance) || distance < 0.0) {
        throw new IllegalArgumentException("distance 不能为负");
      }
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
