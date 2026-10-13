package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** 不可变图快照的站点/车库节点索引，避免每次动态选台都扫描全图。 */
public interface RailGraphPlatformSupport {
  /**
   * @param terminalKey 不含股道的站点身份（如 {@code op:s:name}），大小写不敏感
   * @return 本组四段节点的不可变快照；资格与数字排序由共享 DYNAMIC 匹配器负责
   */
  List<NodeId> platformNodes(String terminalKey);

  /** 快照创建时一次建站点身份索引；封锁覆盖或建图产生新快照时随图自然替换。 */
  static Map<String, List<NodeId>> indexPlatformNodes(Collection<NodeId> nodes) {
    Map<String, List<NodeId>> index = new HashMap<>();
    for (NodeId node : nodes) {
      String[] parts = node.value().split(":", -1);
      if (parts.length == 4 && (parts[1].equalsIgnoreCase("S") || parts[1].equalsIgnoreCase("D"))) {
        String key = (parts[0] + ":" + parts[1] + ":" + parts[2]).toLowerCase(Locale.ROOT);
        index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
      }
    }
    index.replaceAll((key, values) -> List.copyOf(values));
    return Map.copyOf(index);
  }
}
