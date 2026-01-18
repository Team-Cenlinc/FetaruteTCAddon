package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** RouteStop 解析辅助：将 stop 解析为图节点或站点信息。 */
public final class RouteStopResolver {

  private RouteStopResolver() {}

  /** 解析 stop 的图节点 ID（优先 waypointNodeId，其次 station.graphNodeId）。 */
  public static Optional<NodeId> resolveNodeId(StorageProvider provider, RouteStop stop) {
    if (provider == null || stop == null) {
      return Optional.empty();
    }
    if (stop.waypointNodeId().isPresent()) {
      return stop.waypointNodeId().map(NodeId::of);
    }
    if (stop.stationId().isEmpty()) {
      return Optional.empty();
    }
    return provider
        .stations()
        .findById(stop.stationId().get())
        .flatMap(station -> station.graphNodeId().map(NodeId::of));
  }

  /** 解析 stop 对应的站点记录（仅当 stop 绑定 stationId）。 */
  public static Optional<Station> resolveStation(StorageProvider provider, RouteStop stop) {
    if (provider == null || stop == null || stop.stationId().isEmpty()) {
      return Optional.empty();
    }
    return provider.stations().findById(stop.stationId().get());
  }
}
