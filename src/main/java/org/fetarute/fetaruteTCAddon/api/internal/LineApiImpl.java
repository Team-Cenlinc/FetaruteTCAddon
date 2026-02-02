package org.fetarute.fetaruteTCAddon.api.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;

/**
 * LineApi 内部实现：桥接到 LineRepository。
 *
 * <p>仅供内部使用，外部插件应通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 访问。
 */
public final class LineApiImpl implements LineApi {

  private final LineRepository lineRepository;
  private final CompanyRepository companyRepository;
  private final OperatorRepository operatorRepository;

  public LineApiImpl(
      LineRepository lineRepository,
      CompanyRepository companyRepository,
      OperatorRepository operatorRepository) {
    this.lineRepository = Objects.requireNonNull(lineRepository, "lineRepository");
    this.companyRepository = companyRepository;
    this.operatorRepository = operatorRepository;
  }

  @Override
  public Collection<LineInfo> listAllLines() {
    List<LineInfo> result = new ArrayList<>();
    if (companyRepository != null && operatorRepository != null) {
      for (Company company : companyRepository.listAll()) {
        for (Operator operator : operatorRepository.listByCompany(company.id())) {
          for (Line line : lineRepository.listByOperator(operator.id())) {
            result.add(convertLine(line));
          }
        }
      }
    }
    return List.copyOf(result);
  }

  @Override
  public Collection<LineInfo> listByOperator(UUID operatorId) {
    if (operatorId == null) {
      return List.of();
    }
    List<LineInfo> result = new ArrayList<>();
    for (Line line : lineRepository.listByOperator(operatorId)) {
      result.add(convertLine(line));
    }
    return List.copyOf(result);
  }

  @Override
  public Optional<LineInfo> getLine(UUID lineId) {
    if (lineId == null) {
      return Optional.empty();
    }
    return lineRepository.findById(lineId).map(this::convertLine);
  }

  @Override
  public Optional<LineInfo> findByCode(UUID operatorId, String lineCode) {
    if (operatorId == null || lineCode == null) {
      return Optional.empty();
    }
    return lineRepository.findByOperatorAndCode(operatorId, lineCode).map(this::convertLine);
  }

  @Override
  public int lineCount() {
    int count = 0;
    if (companyRepository != null && operatorRepository != null) {
      for (Company company : companyRepository.listAll()) {
        for (Operator operator : operatorRepository.listByCompany(company.id())) {
          count += lineRepository.listByOperator(operator.id()).size();
        }
      }
    }
    return count;
  }

  private LineInfo convertLine(Line line) {
    return new LineInfo(
        line.id(),
        line.code(),
        line.operatorId(),
        line.name(),
        line.secondaryName(),
        toServiceType(line.serviceType()),
        line.color(),
        toLineStatus(line.status()),
        line.spawnFreqBaselineSec());
  }

  private ServiceType toServiceType(LineServiceType type) {
    if (type == null) {
      return ServiceType.UNKNOWN;
    }
    return switch (type) {
      case METRO -> ServiceType.METRO;
      case REGIONAL -> ServiceType.REGIONAL;
      case COMMUTER -> ServiceType.COMMUTER;
      case LRT -> ServiceType.LRT;
      case EXPRESS -> ServiceType.EXPRESS;
    };
  }

  private LineStatus toLineStatus(org.fetarute.fetaruteTCAddon.company.model.LineStatus status) {
    if (status == null) {
      return LineStatus.UNKNOWN;
    }
    return switch (status) {
      case PLANNING -> LineStatus.PLANNING;
      case ACTIVE -> LineStatus.ACTIVE;
      case MAINTENANCE -> LineStatus.MAINTENANCE;
    };
  }
}
