package org.fetarute.fetaruteTCAddon.dispatcher.graph.portal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** 内存里的传送门连接，按入口节点索引。可在任意线程读。 */
public final class PortalLinkRegistry {

  private final Map<NodeId, PortalLink> byFrom = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong revision =
      new java.util.concurrent.atomic.AtomicLong();

  /** 整份替换（启动时从存储读入）。 */
  public void replaceAll(Collection<PortalLink> links) {
    byFrom.clear();
    for (PortalLink link : links) {
      byFrom.put(link.fromNode(), link);
    }
    revision.incrementAndGet();
  }

  public void put(PortalLink link) {
    byFrom.put(link.fromNode(), link);
    revision.incrementAndGet();
  }

  public void remove(NodeId fromNode) {
    if (byFrom.remove(fromNode) != null) {
      revision.incrementAndGet();
    }
  }

  /** 去掉全部自动连接。 */
  public void removeAuto() {
    byFrom.values().removeIf(link -> link.source() == PortalLink.Source.AUTO);
    revision.incrementAndGet();
  }

  public Optional<PortalLink> from(NodeId node) {
    return Optional.ofNullable(byFrom.get(node));
  }

  public List<PortalLink> links() {
    return new ArrayList<>(byFrom.values());
  }

  /** 每次变化递增，复合路网据此判断要不要重建。 */
  public long revision() {
    return revision.get();
  }
}
