package org.fetarute.fetaruteTCAddon.company.repository;

import org.fetarute.fetaruteTCAddon.company.model.Route;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Route 仓库接口。
 */
public interface RouteRepository {

    Optional<Route> findById(UUID id);

    Optional<Route> findByLineAndCode(UUID lineId, String code);

    List<Route> listByLine(UUID lineId);

    Route save(Route route);

    void delete(UUID id);
}
