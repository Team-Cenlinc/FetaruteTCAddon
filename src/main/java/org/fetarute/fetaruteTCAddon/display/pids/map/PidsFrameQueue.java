package org.fetarute.fetaruteTCAddon.display.pids.map;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 一块屏的取帧：内容标识变了才取；取帧可以在后台完成（{@code PidsService#frameLater}），完成后由主线程在下一个 tick 取走。
 * 同一时间只取一帧：取帧期间内容又变了只记最新的一份，这一帧完成后再接着取，中间的不画。
 *
 * <p>只在主线程用。取帧的结果以 {@link CompletableFuture} 交回，完成可以发生在任何线程。
 */
public final class PidsFrameQueue {

  /**
   * 取好的一帧。
   *
   * @param key 内容标识
   * @param frame 调色板帧，长度为整块屏的像素数
   */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "帧是只读的共享缓冲（与 PidsFrameCache 里的同一份），只拷贝不改写；整帧几十万字节，交接时不复制")
  public record Ready(Object key, byte[] frame) {}

  /** 取帧期间又要显示的内容，连同取它的办法。 */
  private record Wanted(
      PidsContent content, Function<PidsContent, CompletableFuture<byte[]>> render) {}

  private final int length;
  private Object shownKey;
  private Object renderingKey;
  private CompletableFuture<byte[]> rendering;
  private Wanted wanted;

  /**
   * @param length 整块屏的像素数；长度不符的帧（渲染尺寸不对）不显示
   */
  public PidsFrameQueue(int length) {
    this.length = length;
  }

  /**
   * 要显示这份内容：与正显示或正在取的相同时什么都不做；正在取别的时记下，等那一帧完成再取。
   *
   * @param content 内容
   * @param render 取帧
   */
  public void offer(PidsContent content, Function<PidsContent, CompletableFuture<byte[]>> render) {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(render, "render");
    if (rendering != null) {
      wanted = content.key().equals(renderingKey) ? null : new Wanted(content, render);
      return;
    }
    wanted = null;
    if (!content.key().equals(shownKey)) {
      start(content, render);
    }
  }

  /** 取走已完成的一帧（没有时为空），并接着取期间记下的最新内容。取帧出错或尺寸不符时不显示，正显示的画面不变。 */
  public Optional<Ready> poll() {
    if (rendering == null || !rendering.isDone()) {
      return Optional.empty();
    }
    CompletableFuture<byte[]> done = rendering;
    Object key = renderingKey;
    rendering = null;
    renderingKey = null;
    Optional<Ready> ready = Optional.empty();
    if (!done.isCompletedExceptionally() && !done.isCancelled()) {
      byte[] frame = done.join();
      if (frame != null && frame.length == length) {
        shownKey = key;
        ready = Optional.of(new Ready(key, frame));
      }
    }
    Wanted next = wanted;
    wanted = null;
    if (next != null && !next.content().key().equals(shownKey)) {
      start(next.content(), next.render());
    }
    return ready;
  }

  /** 正显示的内容标识；还没显示过时为空。 */
  public Optional<Object> shownKey() {
    return Optional.ofNullable(shownKey);
  }

  /** 清空（屏幕重新挂上、服务换了）：正在取的帧不要了。 */
  public void reset() {
    shownKey = null;
    renderingKey = null;
    rendering = null;
    wanted = null;
  }

  private void start(PidsContent content, Function<PidsContent, CompletableFuture<byte[]>> render) {
    renderingKey = content.key();
    rendering = render.apply(content);
  }
}
