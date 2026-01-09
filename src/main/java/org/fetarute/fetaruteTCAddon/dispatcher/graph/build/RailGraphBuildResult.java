package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;

/**
 * 一次图构建的结果，用于回调通知与持久化。
 *
 * <p>{@code missingSwitcherJunctions} 用于构建完成后的运维提示（道岔附近缺少 switcher 牌子），不参与持久化，也不影响图的可用性。
 */
public record RailGraphBuildResult(
    RailGraph graph,
    Instant builtAt,
    String nodeSignature,
    List<RailNodeRecord> nodes,
    List<RailBlockPos> missingSwitcherJunctions,
    List<DuplicateNodeId> duplicateNodeIds) {

  public RailGraphBuildResult {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(builtAt, "builtAt");
    nodeSignature = nodeSignature == null ? "" : nodeSignature;
    nodes = nodes != null ? List.copyOf(nodes) : List.of();
    missingSwitcherJunctions =
        missingSwitcherJunctions != null ? List.copyOf(missingSwitcherJunctions) : List.of();
    duplicateNodeIds = duplicateNodeIds != null ? List.copyOf(duplicateNodeIds) : List.of();
  }
}
