package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次调度图 build 的最终输出（可能带续跑状态）。
 *
 * <p>当 {@link #completion()} 不是 {@link RailGraphBuildCompletion#COMPLETE} 时，结果可能缺失（例如未加载区块、达到配额上限
 * 或区块加载失败）；命令层应避免直接“替换连通分量”，以免误删旧图数据。
 *
 * <p>当 {@link #continuation()} 存在时，表示 discovery 阶段因 {@code maxChunks} 限制暂停，可用 {@code /fta graph
 * continue} 续跑（内存缓存，不保证跨重启）。
 */
public record RailGraphBuildOutcome(
    RailGraphBuildResult result,
    RailGraphBuildCompletion completion,
    Optional<RailGraphBuildContinuation> continuation) {

  public RailGraphBuildOutcome {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(completion, "completion");
    continuation = continuation != null ? continuation : Optional.empty();
  }
}
