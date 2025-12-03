package org.fetarute.fetaruteTCAddon.company.repository;

import org.fetarute.fetaruteTCAddon.company.model.Station;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 车站仓库接口。
 */
public interface StationRepository {

    Optional<Station> findById(UUID id);

    Optional<Station> findByOperatorAndCode(UUID operatorId, String code);

    List<Station> listByOperator(UUID operatorId);

    List<Station> listByLine(UUID lineId);

    Station save(Station station);

    void delete(UUID id);
}
