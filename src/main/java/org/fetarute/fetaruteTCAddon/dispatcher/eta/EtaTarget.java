package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * ETA 目标：描述“要估算到哪里”。
 *
 * <p>说明：HUD/内部占位符只需要一个强类型目标，不应在 UI 层拼接字符串。
 */
public sealed interface EtaTarget
    permits EtaTarget.NextStop, EtaTarget.Station, EtaTarget.PlatformNode, EtaTarget.StopIndex {

  /**
   * 估算“下一站/下一停靠点”。
   *
   * <p>通常对应线路定义中的下一站台节点；若线路已结束则返回空结果。
   */
  record NextStop() implements EtaTarget {}

  /** 估算到某个站点（可能有多站台/多咽喉），由 ETA 模块自行选择适配的目标节点。 */
  record Station(String stationId) implements EtaTarget {
    public Station {
      Objects.requireNonNull(stationId, "stationId");
      if (stationId.isBlank()) {
        throw new IllegalArgumentException("stationId 不能为空");
      }
    }
  }

  /**
   * 估算到某个具体站台/节点。
   *
   * <p>DYNAMIC 停靠传占位股道也能定位：选台后按实际股道估算。
   */
  record PlatformNode(NodeId nodeId) implements EtaTarget {
    public PlatformNode {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  /**
   * 估算到交路上第 {@code stopIndex} 个节点（{@code waypoints()} 的 0 起下标，与公开 API 的停靠序号同一口径）。
   *
   * <p>已经知道下标时用它，不必再按节点反查：DYNAMIC 已选台按实际股道估算，尚未选台按车站级估算（到该站任一候选股道）。
   */
  record StopIndex(int stopIndex) implements EtaTarget {
    public StopIndex {
      if (stopIndex < 0) {
        throw new IllegalArgumentException("stopIndex 不能为负");
      }
    }
  }

  static EtaTarget nextStop() {
    return new NextStop();
  }
}
