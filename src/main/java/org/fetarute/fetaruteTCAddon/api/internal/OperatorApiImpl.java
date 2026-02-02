package org.fetarute.fetaruteTCAddon.api.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;

/**
 * OperatorApi 内部实现：桥接到 OperatorRepository。
 *
 * <p>仅供内部使用，外部插件应通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 访问。
 */
public final class OperatorApiImpl implements OperatorApi {

  private final OperatorRepository operatorRepository;
  private final CompanyRepository companyRepository;

  public OperatorApiImpl(
      OperatorRepository operatorRepository, CompanyRepository companyRepository) {
    this.operatorRepository = Objects.requireNonNull(operatorRepository, "operatorRepository");
    this.companyRepository = companyRepository;
  }

  @Override
  public Collection<OperatorInfo> listAllOperators() {
    List<OperatorInfo> result = new ArrayList<>();
    if (companyRepository != null) {
      for (Company company : companyRepository.listAll()) {
        for (Operator operator : operatorRepository.listByCompany(company.id())) {
          result.add(convertOperator(operator));
        }
      }
    }
    return List.copyOf(result);
  }

  @Override
  public Collection<OperatorInfo> listByCompany(UUID companyId) {
    if (companyId == null) {
      return List.of();
    }
    List<OperatorInfo> result = new ArrayList<>();
    for (Operator operator : operatorRepository.listByCompany(companyId)) {
      result.add(convertOperator(operator));
    }
    return List.copyOf(result);
  }

  @Override
  public Optional<OperatorInfo> getOperator(UUID operatorId) {
    if (operatorId == null) {
      return Optional.empty();
    }
    return operatorRepository.findById(operatorId).map(this::convertOperator);
  }

  @Override
  public Optional<OperatorInfo> findByCode(UUID companyId, String operatorCode) {
    if (companyId == null || operatorCode == null) {
      return Optional.empty();
    }
    return operatorRepository
        .findByCompanyAndCode(companyId, operatorCode)
        .map(this::convertOperator);
  }

  @Override
  public int operatorCount() {
    int count = 0;
    if (companyRepository != null) {
      for (Company company : companyRepository.listAll()) {
        count += operatorRepository.listByCompany(company.id()).size();
      }
    }
    return count;
  }

  private OperatorInfo convertOperator(Operator operator) {
    return new OperatorInfo(
        operator.id(),
        operator.code(),
        operator.companyId(),
        operator.name(),
        operator.secondaryName(),
        operator.colorTheme(),
        operator.priority(),
        operator.description());
  }
}
