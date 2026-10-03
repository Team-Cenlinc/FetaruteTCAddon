package org.fetarute.fetaruteTCAddon.dispatcher.graph.portal;

import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** 传送门连接仓库。 */
public interface PortalLinkRepository {

  List<PortalLink> listAll();

  /** 保存或替换以 {@code link.fromNode} 为入口的连接。 */
  void upsert(PortalLink link);

  void delete(UUID fromWorld, NodeId fromNode);

  /** 删掉全部自动解析的连接（重新扫描前）。 */
  void deleteAuto();
}
