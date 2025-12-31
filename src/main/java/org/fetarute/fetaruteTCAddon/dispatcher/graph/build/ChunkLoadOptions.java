package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

/**
 * 沿轨道探索时用于异步加载区块的配置。
 *
 * <p>注意：该配置仅描述“本次 build 允许加载多少区块”。实际是否需要加载、以及加载是否成功取决于世界当前状态。
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
