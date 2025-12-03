package org.fetarute.fetaruteTCAddon.company.repository;

import org.fetarute.fetaruteTCAddon.company.model.RouteStop;

import java.util.List;
import java.util.UUID;

/**
 * RouteStop 仓库接口。
 */
public interface RouteStopRepository {

    List<RouteStop> listByRoute(UUID routeId);

    RouteStop save(RouteStop stop);

    void delete(UUID routeId, int sequence);

    void deleteAll(UUID routeId);
}
