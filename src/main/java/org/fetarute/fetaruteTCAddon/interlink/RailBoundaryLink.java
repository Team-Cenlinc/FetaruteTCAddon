package org.fetarute.fetaruteTCAddon.interlink;

import java.util.Objects;
import java.util.Optional;

/**
 * 路网的一个边界：列车从本服的 {@code localNodeId} 离开，出现在 {@code remoteNodeId}。对端在本服（同服跨世界的传送门）时 {@code
 * remoteServerId} 为空；在别的服务器时是那台服务器的 ID。路网只看这个接口，不关心门对面是本服还是外服。
 *
 * @param localNodeId 本服的边界节点
 * @param remoteServerId 对端服务器；本服时为空
 * @param remoteNodeId 对端的边界节点（只在对端服务器内唯一）
 */
public record RailBoundaryLink(
    String localNodeId, Optional<String> remoteServerId, String remoteNodeId) {

  public RailBoundaryLink {
    Objects.requireNonNull(localNodeId, "localNodeId");
    Objects.requireNonNull(remoteNodeId, "remoteNodeId");
    remoteServerId =
        remoteServerId == null ? Optional.empty() : remoteServerId.filter(id -> !id.isBlank());
  }

  /** 对端是否在别的服务器。 */
  public boolean remote() {
    return remoteServerId.isPresent();
  }
}
