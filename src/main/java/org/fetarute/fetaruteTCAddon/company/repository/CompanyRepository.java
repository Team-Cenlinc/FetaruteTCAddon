package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Company;

/** 公司仓库接口，支持 UUID 与 code 查询。 */
public interface CompanyRepository {

  Optional<Company> findById(UUID id);

  Optional<Company> findByCode(String code);

  List<Company> listAll();

  List<Company> listByOwner(UUID ownerIdentityId);

  Company save(Company company);

  void delete(UUID id);
}
