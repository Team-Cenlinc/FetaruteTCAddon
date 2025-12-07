package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Line;

/** 线路仓库接口。 */
public interface LineRepository {

  Optional<Line> findById(UUID id);

  Optional<Line> findByOperatorAndCode(UUID operatorId, String code);

  List<Line> listByOperator(UUID operatorId);

  Line save(Line line);

  void delete(UUID id);
}
