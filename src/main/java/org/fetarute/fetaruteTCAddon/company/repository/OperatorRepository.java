package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Operator;

/** 运营商仓库接口。 */
public interface OperatorRepository {

  Optional<Operator> findById(UUID id);

  Optional<Operator> findByCompanyAndCode(UUID companyId, String code);

  List<Operator> listByCompany(UUID companyId);

  Operator save(Operator operator);

  void delete(UUID id);
}
