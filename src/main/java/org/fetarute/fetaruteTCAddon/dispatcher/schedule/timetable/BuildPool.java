package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.Function;

/**
 * 编表候选的并行池：一次 build 一个，快车错峰的候选与相位第三层的完整候选共用。
 *
 * <p>它只做一件事：一批互不依赖的候选同时编，按原来的顺序交回。取舍与早停仍由调用方按原顺序逐个做，所以挑出来的与串行逐字相同——
 * 并行只是提前编了几个调用方未必用得上的候选，早停之后的直接丢掉。每个候选抛的异常留在它自己的位置上，调用方消费到它才抛： 串行时轮不到的候选出的错不会冒出来。{@link Error}
 * 例外，整批编完立即抛。
 *
 * <p>池是工作窃取的：错峰候选里又有相位候选，池内线程等子任务时会去帮着编，嵌套不会把线程占死。工作线程只做纯计算，不碰 Bukkit API。
 *
 * <p>只有一个线程时不建池，候选在调用方线程上依次编，与从前的串行完全一样。
 */
public final class BuildPool implements AutoCloseable {

  /** 不配置时最多用几个线程：服务器上还要跑游戏，不默认吃满。 */
  public static final int DEFAULT_MAX_THREADS = 4;

  private static final BuildPool SERIAL = new BuildPool(null, 1);

  /** 为空时串行。 */
  private final ForkJoinPool pool;

  private final int threads;

  private BuildPool(ForkJoinPool pool, int threads) {
    this.pool = pool;
    this.threads = threads;
  }

  /** 串行：不建线程，候选在调用方线程上依次编。 */
  public static BuildPool serial() {
    return SERIAL;
  }

  /**
   * 开一个池；用完要 {@link #close}。
   *
   * @param threads 线程数；不超过 1 时串行
   */
  public static BuildPool open(int threads) {
    if (threads <= 1) {
      return SERIAL;
    }
    ForkJoinPool.ForkJoinWorkerThreadFactory factory =
        owner -> {
          ForkJoinWorkerThread thread =
              ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(owner);
          thread.setName("FTA-timetable-build-" + thread.getPoolIndex());
          thread.setDaemon(true);
          thread.setContextClassLoader(BuildPool.class.getClassLoader());
          return thread;
        };
    return new BuildPool(new ForkJoinPool(threads, factory, null, false), threads);
  }

  /**
   * 配置值换成线程数：负数（没配置）取 {@link #defaultThreads}，0 取全部核，其余照用。
   *
   * @param configured {@code timetable.build-threads}
   */
  public static int resolveThreads(int configured) {
    int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
    if (configured < 0) {
      return defaultThreads(cores);
    }
    return configured == 0 ? cores : configured;
  }

  /** 缺省线程数：核数的一半，至少 1、至多 {@value #DEFAULT_MAX_THREADS}。 */
  static int defaultThreads(int cores) {
    return Math.max(1, Math.min(DEFAULT_MAX_THREADS, cores / 2));
  }

  /** 线程数；也是错峰搜索一批编几个候选。 */
  public int threads() {
    return threads;
  }

  /**
   * 各候选并行编，按 {@code items} 的顺序交回。
   *
   * <p>串行时依次编，某一个抛了异常就不再编后面的（交回的列表到它为止）：调用方消费到它就抛，与从前一样不会去编下一个。
   *
   * @param items 互不依赖的候选
   * @param task 编一个候选；必须线程安全
   * @return 与 {@code items} 一一对应的结果
   */
  <T, R> List<Evaluated<R>> evaluate(List<T> items, Function<? super T, ? extends R> task) {
    Objects.requireNonNull(task, "task");
    List<Evaluated<R>> out = new ArrayList<>(items.size());
    if (pool == null || items.size() < 2) {
      for (T item : items) {
        Evaluated<R> one = Evaluated.of(task, item);
        out.add(one);
        if (one.failure != null) {
          break;
        }
      }
      return out;
    }
    List<ForkJoinTask<Evaluated<R>>> tasks = new ArrayList<>(items.size());
    for (T item : items) {
      tasks.add(ForkJoinTask.adapt(() -> Evaluated.of(task, item)));
    }
    if (Thread.currentThread() instanceof ForkJoinWorkerThread worker && worker.getPool() == pool) {
      // 嵌套（错峰候选里的相位候选）：在池内分叉，等的时候帮着编。
      ForkJoinTask.invokeAll(tasks);
    } else {
      pool.invoke(ForkJoinTask.adapt(() -> ForkJoinTask.invokeAll(tasks)));
    }
    for (ForkJoinTask<Evaluated<R>> one : tasks) {
      out.add(one.join());
    }
    for (Evaluated<R> one : out) {
      if (one.failure instanceof Error error) {
        throw error;
      }
    }
    return out;
  }

  @Override
  public void close() {
    if (pool != null) {
      pool.shutdown();
    }
  }

  /** 一个候选的结果：值，或它抛的异常（{@link #get} 时才抛）。 */
  static final class Evaluated<R> {
    private final R value;
    private final Throwable failure;

    private Evaluated(R value, Throwable failure) {
      this.value = value;
      this.failure = failure;
    }

    private static <T, R> Evaluated<R> of(Function<? super T, ? extends R> task, T item) {
      try {
        return new Evaluated<>(task.apply(item), null);
      } catch (RuntimeException | Error failure) {
        return new Evaluated<>(null, failure);
      }
    }

    /** 候选的结果；它编的时候抛了异常就原样抛出。 */
    R get() {
      if (failure instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (failure instanceof Error error) {
        throw error;
      }
      return value;
    }
  }
}
