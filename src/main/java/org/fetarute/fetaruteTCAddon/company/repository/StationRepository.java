package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Station;

/** 车站仓库接口。 */
public interface StationRepository {

  Optional<Station> findById(UUID id);

  Optional<Station> findByOperatorAndCode(UUID operatorId, String code);

  List<Station> listByOperator(UUID operatorId);

  List<Station> listByLine(UUID lineId);

  /** 全部车站（内存索引一次性加载用）。 */
  List<Station> listAll();

  Station save(Station station);

  void delete(UUID id);
}
