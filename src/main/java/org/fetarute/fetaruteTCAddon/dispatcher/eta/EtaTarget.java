package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * ETA 目标：描述“要估算到哪里”。
 *
 * <p>说明：HUD/内部占位符只需要一个强类型目标，不应在 UI 层拼接字符串。
 */
public sealed interface EtaTarget
    permits EtaTarget.NextStop, EtaTarget.Station, EtaTarget.PlatformNode {

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

  /** 估算到某个具体站台/节点。 */
  record PlatformNode(NodeId nodeId) implements EtaTarget {
    public PlatformNode {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  static EtaTarget nextStop() {
    return new NextStop();
  }
}
