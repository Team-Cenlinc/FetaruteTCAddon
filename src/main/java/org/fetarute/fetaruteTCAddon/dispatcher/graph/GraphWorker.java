package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 调度图后台计算用的单线程执行器：跨世界路网重建、交路路径预热等纯计算。
 *
 * <p>只有一个命名的守护线程，任务按提交顺序依次跑，不与游戏抢多个核；长任务应自己切成小段重新提交，让排在后面的任务插得进来。 任务只读不可变的图与快照，不碰 Bukkit
 * API；结果要回到主线程时由任务自己投递。关闭后再提交的任务被拒绝（{@link RejectedExecutionException}）， 已排队未跑的直接丢弃。
 */
public final class GraphWorker implements Executor, AutoCloseable {

  private final ExecutorService executor;
  private final String threadName;
  private final Consumer<String> errorLogger;

  private GraphWorker(ExecutorService executor, String threadName, Consumer<String> errorLogger) {
    this.executor = executor;
    this.threadName = threadName;
    this.errorLogger = errorLogger;
  }

  /**
   * 启动一个后台线程。
   *
   * @param threadName 线程名
   * @param errorLogger 任务抛出未捕获异常时的日志
   */
  public static GraphWorker start(String threadName, Consumer<String> errorLogger) {
    Objects.requireNonNull(threadName, "threadName");
    Consumer<String> logger = errorLogger != null ? errorLogger : message -> {};
    ClassLoader loader = GraphWorker.class.getClassLoader();
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            task -> {
              Thread thread = new Thread(task, threadName);
              thread.setDaemon(true);
              thread.setContextClassLoader(loader);
              thread.setUncaughtExceptionHandler(
                  (failed, error) -> logger.accept(threadName + " 线程异常退出: " + error));
              return thread;
            });
    return new GraphWorker(executor, threadName, logger);
  }

  /**
   * 提交任务。任务里的异常记日志后丢弃，不会让线程退出。
   *
   * @throws RejectedExecutionException 已关闭
   */
  @Override
  public void execute(Runnable task) {
    Objects.requireNonNull(task, "task");
    executor.execute(
        () -> {
          try {
            task.run();
          } catch (RuntimeException ex) {
            errorLogger.accept(threadName + " 任务异常: " + ex);
          }
        });
  }

  /** 是否已关闭。 */
  public boolean isShutdown() {
    return executor.isShutdown();
  }

  /** 关闭：不再接受任务，排队的丢弃，正在跑的收到中断（纯计算任务按自己的取消标记尽快结束）。 */
  @Override
  public void close() {
    executor.shutdownNow();
  }
}
