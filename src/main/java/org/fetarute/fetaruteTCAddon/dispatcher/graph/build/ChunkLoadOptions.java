package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

/**
 * 沿轨道探索时用于异步加载区块的配置。
 *
 * <p>注意：该配置仅描述“本次 build 允许加载多少区块”。实际是否需要加载、以及加载是否成功取决于世界当前状态。
 *
 * <p>字段语义：
 *
 * <ul>
 *   <li>{@code maxChunks}：本次 build/continue 允许触发加载的区块总数上限；达到上限后 discovery 会进入“暂停”
 *   <li>{@code maxConcurrentLoads}：并发加载上限（用于保护主线程与 IO），超过会排队等待
 * </ul>
 */
public record ChunkLoadOptions(boolean enabled, int maxChunks, int maxConcurrentLoads) {

  public static ChunkLoadOptions disabled() {
    return new ChunkLoadOptions(false, 0, 0);
  }

  public ChunkLoadOptions {
    if (!enabled) {
      maxChunks = 0;
      maxConcurrentLoads = 0;
    } else {
      if (maxChunks <= 0) {
        throw new IllegalArgumentException("maxChunks 必须为正数");
      }
      if (maxConcurrentLoads <= 0) {
        throw new IllegalArgumentException("maxConcurrentLoads 必须为正数");
      }
    }
  }
}
