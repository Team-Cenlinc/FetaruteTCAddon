package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 只做边探索的分段任务：节点与锚点已经备好（refresh 从库里读、extend 从新牌子出发），每 tick 在预算内推进 {@link
 * NodeToNodeEdgeExplorer}，走完后把探索器交给 {@code onFinish}。
 *
 * <p>与 {@link RailGraphBuildJob} 一样登记在世界的任务表里，可以 status/cancel，同一世界不会和别的构建并发。
 */
public final class RailGraphEdgeExploreJob implements RailGraphBuildTask {

  private final JavaPlugin plugin;
  private final NodeToNodeEdgeExplorer explorer;
  private final String phase;
  private final int nodesFound;
  private final int nodesWithAnchors;
  private final int nodesMissingAnchors;
  private final long tickBudgetNanos;
  private final Consumer<NodeToNodeEdgeExplorer> onFinish;
  private final Consumer<Throwable> onFailure;
  private final Instant startedAt = Instant.now();

  private BukkitTask task;
  private long processedSteps;

  /**
   * @param phase 状态里显示的阶段名（如 refresh、extend）
   * @param tickBudgetMs 每 tick 可消耗的时间预算（毫秒）
   */
  public RailGraphEdgeExploreJob(
      JavaPlugin plugin,
      NodeToNodeEdgeExplorer explorer,
      String phase,
      int nodesFound,
      int nodesWithAnchors,
      int nodesMissingAnchors,
      int tickBudgetMs,
      Consumer<NodeToNodeEdgeExplorer> onFinish,
      Consumer<Throwable> onFailure) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.explorer = Objects.requireNonNull(explorer, "explorer");
    this.phase = Objects.requireNonNull(phase, "phase");
    this.nodesFound = nodesFound;
    this.nodesWithAnchors = nodesWithAnchors;
    this.nodesMissingAnchors = nodesMissingAnchors;
    if (tickBudgetMs <= 0) {
      throw new IllegalArgumentException("tickBudgetMs 必须为正数");
    }
    this.tickBudgetNanos = tickBudgetMs * 1_000_000L;
    this.onFinish = Objects.requireNonNull(onFinish, "onFinish");
    this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
  }

  public synchronized void start() {
    if (task == null) {
      task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }
  }

  private void tick() {
    try {
      processedSteps += explorer.step(System.nanoTime() + tickBudgetNanos);
      if (!explorer.isDone()) {
        return;
      }
      cancel();
      onFinish.accept(explorer);
    } catch (Throwable ex) {
      cancel();
      onFailure.accept(ex);
    }
  }

  @Override
  public synchronized Optional<RailGraphBuildJob.RailGraphBuildStatus> getStatus() {
    return Optional.of(
        new RailGraphBuildJob.RailGraphBuildStatus(
            startedAt,
            phase,
            nodesFound,
            nodesWithAnchors,
            nodesMissingAnchors,
            0,
            0,
            0,
            explorer.pendingTaskCount(),
            processedSteps));
  }

  @Override
  public synchronized boolean cancel() {
    if (task == null) {
      return false;
    }
    task.cancel();
    task = null;
    return true;
  }
}
