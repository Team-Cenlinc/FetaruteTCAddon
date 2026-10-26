package org.fetarute.fetaruteTCAddon.display.pids.map;

import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.GraphWorker;

/**
 * 站台屏的调色板帧：渲染整帧、换算调色板。能在各屏间复用的帧进共享缓存（{@link PidsFrameCache}）；缓存里没有的可以交给渲染线程画（{@link
 * #frameLater}），不占主线程。
 *
 * <p>渲染线程第一次要用时才起，{@link #stop} 后不再起；之后要的帧在调用线程上当场画。渲染只读内容里的视图快照，不碰 Bukkit。
 */
public final class PidsFrameSource {

  private final PidsFrameCache cache;
  private final Predicate<Object> shared;
  private final Supplier<GraphWorker> workers;
  private final Consumer<String> warn;
  private final Object lock = new Object();
  private GraphWorker worker;
  private boolean stopped;

  /** 上一次记下的渲染错误，同一种错误不重复记。 */
  private volatile String lastError;

  /**
   * @param cache 共享帧缓存
   * @param shared 这种内容标识的帧进不进共享缓存
   * @param workers 起渲染线程
   * @param warn 渲染出错时的日志
   */
  public PidsFrameSource(
      PidsFrameCache cache,
      Predicate<Object> shared,
      Supplier<GraphWorker> workers,
      Consumer<String> warn) {
    this.cache = Objects.requireNonNull(cache, "cache");
    this.shared = Objects.requireNonNull(shared, "shared");
    this.workers = Objects.requireNonNull(workers, "workers");
    this.warn = warn != null ? warn : message -> {};
  }

  /**
   * 当场取帧：共享的内容在缓存里没有才渲染、换算。
   *
   * @return 只读的帧，长度为 {@code width × height}；渲染尺寸不符时为空数组
   */
  public byte[] frame(PidsContent content, int width, int height) {
    if (!shared.test(content.key())) {
      return convert(content, width, height);
    }
    return cache.frame(content.key(), width, height, () -> convert(content, width, height));
  }

  /**
   * 同 {@link #frame}，但缓存里没有的帧交给渲染线程画。缓存里有的当场给出（返回已完成的结果）；渲染线程已停时当场在调用线程上画。
   * 渲染出错时结果以异常结束，同一种错误只记一次日志。
   */
  public CompletableFuture<byte[]> frameLater(PidsContent content, int width, int height) {
    if (shared.test(content.key())) {
      byte[] cached = cache.peek(content.key(), width, height);
      if (cached != null) {
        return CompletableFuture.completedFuture(cached);
      }
    }
    Executor executor = executor();
    CompletableFuture<byte[]> rendered;
    try {
      rendered =
          executor == null
              ? inline(content, width, height)
              : CompletableFuture.supplyAsync(() -> frame(content, width, height), executor);
    } catch (RejectedExecutionException closed) {
      rendered = inline(content, width, height);
    }
    return rendered.whenComplete(
        (frame, error) -> {
          if (error == null) {
            return;
          }
          String message = String.valueOf(error.getCause() != null ? error.getCause() : error);
          if (!message.equals(lastError)) {
            lastError = message;
            warn.accept("站台屏渲染失败: " + message);
          }
        });
  }

  /** 停掉渲染线程：排队没画的丢弃，之后要的帧在调用线程上当场画。 */
  public void stop() {
    synchronized (lock) {
      stopped = true;
      if (worker != null) {
        worker.close();
        worker = null;
      }
    }
  }

  /** 共享缓存里的帧数。 */
  public int cached() {
    return cache.size();
  }

  /** 在调用线程上当场画；出错时也只以异常结束，不往外抛（调用方在主线程上接着处理别的屏）。 */
  private CompletableFuture<byte[]> inline(PidsContent content, int width, int height) {
    try {
      return CompletableFuture.completedFuture(frame(content, width, height));
    } catch (RuntimeException ex) {
      return CompletableFuture.failedFuture(ex);
    }
  }

  private Executor executor() {
    synchronized (lock) {
      if (worker == null && !stopped) {
        worker = workers.get();
      }
      return worker;
    }
  }

  private static byte[] convert(PidsContent content, int width, int height) {
    BufferedImage image = content.image().get();
    if (image.getWidth() != width || image.getHeight() != height) {
      return new byte[0];
    }
    byte[] out = new byte[width * height];
    PidsMapPalette.minecraft().convert(image, out);
    return out;
  }
}
