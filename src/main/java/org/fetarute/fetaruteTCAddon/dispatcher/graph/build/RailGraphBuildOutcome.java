package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.util.Objects;
import java.util.Optional;

/** 一次调度图 build 的最终输出（可能带续跑状态）。 */
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
