package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * refresh/extend 的前置阶段：每 tick 在预算内推进 {@link SwitcherNodeAudit}，核对完把任务本身与结果交给 {@code onFinish}（主线程），
 * 以便接手的任务在世界任务表里原位换下它。
 *
 * <p>与其它构建任务一样登记在世界的任务表里，可以 status/cancel；取消时撤掉核对挂的区块票。
 */
public final class SwitcherAuditJob implements RailGraphBuildTask {

  private final JavaPlugin plugin;
  private final SwitcherNodeAudit audit;
  private final String phase;
  private final int nodesFound;
  private final long tickBudgetNanos;
  private final BiConsumer<SwitcherAuditJob, SwitcherNodeAudit.Result> onFinish;
  private final Consumer<Throwable> onFailure;
  private final Instant startedAt = Instant.now();

  private BukkitTask task;

  /**
   * @param phase 状态里显示的阶段名
   * @param nodesFound 库里读到的节点数
   * @param tickBudgetMs 每 tick 可消耗的时间预算（毫秒）
   */
  public SwitcherAuditJob(
      JavaPlugin plugin,
      SwitcherNodeAudit audit,
      String phase,
      int nodesFound,
      int tickBudgetMs,
      BiConsumer<SwitcherAuditJob, SwitcherNodeAudit.Result> onFinish,
      Consumer<Throwable> onFailure) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.phase = Objects.requireNonNull(phase, "phase");
    this.nodesFound = nodesFound;
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
      if (!audit.step(System.nanoTime() + tickBudgetNanos)) {
        return;
      }
      stop();
      onFinish.accept(this, audit.result());
    } catch (Throwable ex) {
      cancel();
      onFailure.accept(ex);
    }
  }

  private synchronized void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
  }

  @Override
  public synchronized Optional<RailGraphBuildJob.RailGraphBuildStatus> getStatus() {
    SwitcherNodeAudit.Result result = audit.result();
    return Optional.of(
        new RailGraphBuildJob.RailGraphBuildStatus(
            startedAt, phase, nodesFound, 0, 0, result.chunksLoaded(), result.audited(), 0, 0, 0));
  }

  @Override
  public synchronized boolean cancel() {
    audit.close();
    if (task == null) {
      return false;
    }
    task.cancel();
    task = null;
    return true;
  }
}
