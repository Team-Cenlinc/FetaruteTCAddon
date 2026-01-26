package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import com.bergerkiller.bukkit.tc.controller.components.RailJunction;
import com.bergerkiller.bukkit.tc.controller.components.RailPath;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.rails.type.RailType;
import com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 使用 TrainCarts 的 {@link TrackWalkingPoint} 从节点出发探索到下一个节点的边。
 *
 * <p>优势：
 *
 * <ul>
 *   <li>不会遍历无节点的轨道（如废弃矿井）
 *   <li>遇到节点就停止，边长就是实际轨道距离
 *   <li>使用 TC 原生轨道行走逻辑，更准确
 * </ul>
 *
 * <p>流程：
 *
 * <ol>
 *   <li>从节点锚点获取 RailState
 *   <li>获取该位置的所有 RailJunction（道岔方向）
 *   <li>沿每个方向用 TrackWalkingPoint 走
 *   <li>每走一步检查是否到达另一个节点的锚点
 *   <li>到达则记录边，否则继续走直到超过 maxDistance
 * </ol>
 */
public final class NodeToNodeEdgeExplorer {

  /** 单条边最大探索距离（blocks） */
  private static final double DEFAULT_MAX_DISTANCE = 512.0;

  /** 每 tick 最大移动步数（大幅增加以提升速度） */
  private static final int DEFAULT_MAX_STEPS_PER_TICK = 2048;

  private static final BlockFace[] FALLBACK_NEIGHBOR_FACES = {
    BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
  };

  private final World world;
  private final Map<RailBlockPos, NodeId> anchorIndex;
  private final Set<NodeId> switcherNodeIds;
  private final double maxDistance;
  private final Consumer<String> debugLogger;

  private final Queue<EdgeExplorationTask> pendingTasks = new ArrayDeque<>();
  private final Map<EdgeId, Integer> discoveredEdges = new HashMap<>();

  /** 已探索过的节点对（无向），用于跳过重复探索 */
  private final Set<EdgeId> exploredPairs = new HashSet<>();

  private EdgeExplorationTask currentTask;
  private boolean done = false;

  /**
   * 创建边探索器。
   *
   * @param world 世界
   * @param anchorIndex 锚点位置 → 节点 ID 的映射（用于检测是否到达另一个节点）
   * @param debugLogger 调试日志输出
   */
  public NodeToNodeEdgeExplorer(
      World world, Map<RailBlockPos, NodeId> anchorIndex, Consumer<String> debugLogger) {
    this(world, anchorIndex, Set.of(), DEFAULT_MAX_DISTANCE, debugLogger);
  }

  public NodeToNodeEdgeExplorer(
      World world,
      Map<RailBlockPos, NodeId> anchorIndex,
      Set<NodeId> switcherNodeIds,
      Consumer<String> debugLogger) {
    this(world, anchorIndex, switcherNodeIds, DEFAULT_MAX_DISTANCE, debugLogger);
  }

  public NodeToNodeEdgeExplorer(
      World world,
      Map<RailBlockPos, NodeId> anchorIndex,
      double maxDistance,
      Consumer<String> debugLogger) {
    this(world, anchorIndex, Set.of(), maxDistance, debugLogger);
  }

  public NodeToNodeEdgeExplorer(
      World world,
      Map<RailBlockPos, NodeId> anchorIndex,
      Set<NodeId> switcherNodeIds,
      double maxDistance,
      Consumer<String> debugLogger) {
    this.world = Objects.requireNonNull(world, "world");
    this.anchorIndex = Objects.requireNonNull(anchorIndex, "anchorIndex");
    this.switcherNodeIds = switcherNodeIds == null ? Set.of() : Set.copyOf(switcherNodeIds);
    this.maxDistance = maxDistance > 0 ? maxDistance : DEFAULT_MAX_DISTANCE;
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  /**
   * 添加一个节点的所有锚点作为探索起点。
   *
   * @param nodeId 节点 ID
   * @param anchors 该节点的锚点集合
   */
  public void addNode(NodeId nodeId, Set<RailBlockPos> anchors) {
    if (nodeId == null || anchors == null || anchors.isEmpty()) {
      return;
    }
    for (RailBlockPos anchor : anchors) {
      if (anchor == null) {
        continue;
      }
      pendingTasks.add(new EdgeExplorationTask(nodeId, anchor));
    }
  }

  /**
   * 执行一个 tick 的探索。
   *
   * @param deadlineNanos tick 结束时间
   * @return 本 tick 处理的步数
   */
  public int step(long deadlineNanos) {
    return step(deadlineNanos, DEFAULT_MAX_STEPS_PER_TICK);
  }

  /**
   * 执行探索步骤（指定最大步数）。
   *
   * @param deadlineNanos tick 结束时间
   * @param maxSteps 最大步数（-1 表示无限制）
   * @return 本 tick 处理的步数
   */
  public int step(long deadlineNanos, int maxSteps) {
    if (done) {
      return 0;
    }

    int stepsThisTick = 0;

    while (System.nanoTime() < deadlineNanos && (maxSteps < 0 || stepsThisTick < maxSteps)) {
      // 如果没有当前任务，取下一个
      if (currentTask == null) {
        currentTask = pendingTasks.poll();
        if (currentTask == null) {
          done = true;
          return stepsThisTick;
        }
        initializeTask(currentTask);
      }

      // 执行当前任务的一步
      boolean taskDone = stepCurrentTask();
      stepsThisTick++;

      if (taskDone) {
        currentTask = null;
      }
    }

    return stepsThisTick;
  }

  private void initializeTask(EdgeExplorationTask task) {
    RailBlockPos anchor = task.startAnchor;
    Block railBlock = world.getBlockAt(anchor.x(), anchor.y(), anchor.z());

    // 检查是否是有效轨道
    RailPiece piece = RailPiece.create(railBlock);
    if (piece == null || piece.isNone()) {
      debugLogger.accept("无效轨道块: " + anchor + " for node " + task.startNodeId);
      return;
    }

    // 获取该轨道的所有可能方向（道岔）
    List<RailJunction> junctions = piece.getJunctions();
    if (junctions.isEmpty()) {
      // 没有道岔，尝试获取默认方向
      RailState state = RailState.getSpawnState(piece);
      if (state != null && state.railType() != RailType.NONE) {
        task.walkers.add(createWalker(state));
        // 反方向也探索
        RailState reversed = state.clone();
        reversed.position().invertMotion();
        task.walkers.add(createWalker(reversed));
      }
    } else {
      // 从每个道岔方向探索
      for (RailJunction junction : junctions) {
        RailPath.Position pos = junction.position().clone();
        RailState state = new RailState();
        state.setRailPiece(piece);
        // 设置位置 - position 可能是相对或绝对的
        if (pos.relative) {
          pos.makeAbsolute(railBlock);
        }
        RailPath.Position statePos = state.position();
        statePos.posX = pos.posX;
        statePos.posY = pos.posY;
        statePos.posZ = pos.posZ;
        statePos.motX = pos.motX;
        statePos.motY = pos.motY;
        statePos.motZ = pos.motZ;
        statePos.relative = false;
        state.initEnterDirection();
        task.walkers.add(createWalker(state));
      }
    }

    if (switcherNodeIds.contains(task.startNodeId)) {
      addSwitcherNeighborWalkers(task, railBlock);
    }

    debugLogger.accept(
        "初始化探索任务: node="
            + task.startNodeId
            + " anchor="
            + anchor
            + " directions="
            + task.walkers.size());
  }

  private void addSwitcherNeighborWalkers(EdgeExplorationTask task, Block railBlock) {
    if (task == null || railBlock == null) {
      return;
    }
    Set<RailBlockPos> visited = new HashSet<>();
    RailBlockPos center = new RailBlockPos(railBlock.getX(), railBlock.getY(), railBlock.getZ());
    for (BlockFace face : FALLBACK_NEIGHBOR_FACES) {
      if (face == null || face == BlockFace.SELF) {
        continue;
      }
      Block candidate = railBlock.getRelative(face);
      if (!world.isChunkLoaded(candidate.getX() >> 4, candidate.getZ() >> 4)) {
        continue;
      }
      RailPiece neighborPiece = RailPiece.create(candidate);
      if (neighborPiece == null || neighborPiece.isNone()) {
        continue;
      }
      Block neighborRailBlock = neighborPiece.block();
      if (neighborRailBlock == null) {
        continue;
      }
      RailBlockPos neighborPos =
          new RailBlockPos(
              neighborRailBlock.getX(), neighborRailBlock.getY(), neighborRailBlock.getZ());
      if (neighborPos.equals(center) || !visited.add(neighborPos)) {
        continue;
      }
      RailState state = RailState.getSpawnState(neighborPiece);
      if (state == null || state.railType() == RailType.NONE) {
        continue;
      }
      Vector away =
          new Vector(
              neighborRailBlock.getX() - railBlock.getX(),
              neighborRailBlock.getY() - railBlock.getY(),
              neighborRailBlock.getZ() - railBlock.getZ());
      if (away.lengthSquared() <= 0.0) {
        continue;
      }
      state.setMotionVector(away);
      state.initEnterDirection();
      task.walkers.add(createWalker(state));
      RailState reversed = state.cloneAndInvertMotion();
      task.walkers.add(createWalker(reversed));
    }
  }

  private WalkerState createWalker(RailState state) {
    TrackWalkingPoint walker = new TrackWalkingPoint(state);
    walker.setLoopFilter(true); // 防止环路
    return new WalkerState(walker);
  }

  private boolean stepCurrentTask() {
    if (currentTask == null || currentTask.walkers.isEmpty()) {
      return true;
    }

    // 处理每个方向的 walker
    List<WalkerState> remaining = new ArrayList<>();
    for (WalkerState ws : currentTask.walkers) {
      if (!stepWalker(ws, currentTask.startNodeId)) {
        // walker 还没完成
        remaining.add(ws);
      }
    }

    currentTask.walkers.clear();
    currentTask.walkers.addAll(remaining);

    return currentTask.walkers.isEmpty();
  }

  /**
   * 执行一个 walker 的一步。
   *
   * @return true 如果 walker 完成（到达节点或失败）
   */
  private boolean stepWalker(WalkerState ws, NodeId startNodeId) {
    TrackWalkingPoint walker = ws.walker;

    // 移动一个轨道块
    if (!walker.moveFull()) {
      // 无法继续移动（轨道结束、环路等）
      debugLogger.accept(
          "Walker 停止: node="
              + startNodeId
              + " reason="
              + walker.failReason
              + " distance="
              + walker.movedTotal);
      return true;
    }

    // 检查是否超过最大距离
    if (walker.movedTotal > maxDistance) {
      debugLogger.accept("Walker 超过最大距离: node=" + startNodeId + " distance=" + walker.movedTotal);
      return true;
    }

    // 检查当前位置是否是另一个节点的锚点
    Block currentBlock = walker.state.railBlock();
    if (currentBlock == null) {
      return true;
    }

    RailBlockPos currentPos =
        new RailBlockPos(currentBlock.getX(), currentBlock.getY(), currentBlock.getZ());
    NodeId targetNodeId = anchorIndex.get(currentPos);

    if (targetNodeId != null && !targetNodeId.equals(startNodeId)) {
      // 找到了另一个节点！记录边
      int distance = (int) Math.round(walker.movedTotal);
      EdgeId edgeId = EdgeId.undirected(startNodeId, targetNodeId);

      // 标记为已探索（从两个方向都算同一条边）
      exploredPairs.add(edgeId);

      // 检查是否已经有更短的边
      Integer existing = discoveredEdges.get(edgeId);
      if (existing == null || distance < existing) {
        discoveredEdges.put(edgeId, distance);
        debugLogger.accept(
            "发现边: " + startNodeId + " ↔ " + targetNodeId + " len=" + distance + " blocks");
      }

      return true;
    }

    // 还没到达节点，继续
    return false;
  }

  /** 检查从 fromNode 到 toNode 的边是否已经被探索过。 用于跳过反向探索（如果 A→B 已完成，则 B→A 可以跳过） */
  public boolean isEdgeExplored(NodeId fromNode, NodeId toNode) {
    return exploredPairs.contains(EdgeId.undirected(fromNode, toNode));
  }

  public boolean isDone() {
    return done && currentTask == null && pendingTasks.isEmpty();
  }

  public Map<EdgeId, Integer> getDiscoveredEdges() {
    return Map.copyOf(discoveredEdges);
  }

  public int pendingTaskCount() {
    return pendingTasks.size() + (currentTask != null ? 1 : 0);
  }

  public int discoveredEdgeCount() {
    return discoveredEdges.size();
  }

  private static class EdgeExplorationTask {
    final NodeId startNodeId;
    final RailBlockPos startAnchor;
    final List<WalkerState> walkers = new ArrayList<>();

    EdgeExplorationTask(NodeId startNodeId, RailBlockPos startAnchor) {
      this.startNodeId = startNodeId;
      this.startAnchor = startAnchor;
    }
  }

  private static class WalkerState {
    final TrackWalkingPoint walker;

    WalkerState(TrackWalkingPoint walker) {
      this.walker = walker;
    }
  }
}
