package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;

/**
 * 调度图构建的“续跑”状态快照：用于在达到 chunk 限制或玩家掉线后继续沿轨道扩张。
 *
 * <p>该对象仅用于内存缓存：不保证跨服务器重启/插件重载可用。
 */
public record RailGraphBuildContinuation(
    Instant createdAt,
    ConnectedRailNodeDiscoverySession discoverySession,
    List<RailNodeRecord> nodes) {

  public RailGraphBuildContinuation {
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(discoverySession, "discoverySession");
    nodes = nodes != null ? List.copyOf(nodes) : List.of();
  }
}
