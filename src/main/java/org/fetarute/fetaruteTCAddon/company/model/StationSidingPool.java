package org.fetarute.fetaruteTCAddon.company.model;

import java.util.List;
import java.util.Objects;

/** 车站附属的存车线池配置。 */
public record StationSidingPool(
    String poolId,
    List<String> candidateNodes, // 可填 NodeId 或 DYNAMIC:<解析器>
    SelectionPolicy selectionPolicy,
    int capacity,
    FallbackPolicy fallbackPolicy) {

  public enum SelectionPolicy {
    FIRST_AVAILABLE,
    ROUND_ROBIN,
    RANDOM
  }

  public enum FallbackPolicy {
    STAY_AT_PLATFORM,
    RETURN_IMMEDIATELY
  }

  public StationSidingPool {
    Objects.requireNonNull(poolId, "poolId");
    candidateNodes = candidateNodes == null ? List.of() : List.copyOf(candidateNodes);
    selectionPolicy = selectionPolicy == null ? SelectionPolicy.FIRST_AVAILABLE : selectionPolicy;
    fallbackPolicy = fallbackPolicy == null ? FallbackPolicy.STAY_AT_PLATFORM : fallbackPolicy;
  }
}
