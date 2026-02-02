package org.fetarute.fetaruteTCAddon.api.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;

/**
 * StationApi 内部实现：桥接到 StationRepository。
 *
 * <p>仅供内部使用，外部插件应通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 访问。
 */
public final class StationApiImpl implements StationApi {

  private final StationRepository stationRepository;
  private final CompanyRepository companyRepository;
  private final OperatorRepository operatorRepository;

  /**
   * 创建 StationApi 实现。
   *
   * @param stationRepository 站点仓库
   * @param companyRepository 公司仓库（用于遍历所有运营商）
   * @param operatorRepository 运营商仓库
   */
  public StationApiImpl(
      StationRepository stationRepository,
      CompanyRepository companyRepository,
      OperatorRepository operatorRepository) {
    this.stationRepository = Objects.requireNonNull(stationRepository, "stationRepository");
    this.companyRepository = companyRepository;
    this.operatorRepository = operatorRepository;
  }

  @Override
  public Collection<StationInfo> listAllStations() {
    // StationRepository 没有 listAll()，需要遍历公司 -> 运营商 -> 站点
    List<StationInfo> result = new ArrayList<>();
    if (companyRepository != null && operatorRepository != null) {
      for (Company company : companyRepository.listAll()) {
        for (Operator operator : operatorRepository.listByCompany(company.id())) {
          for (Station station : stationRepository.listByOperator(operator.id())) {
            result.add(convertStation(station));
          }
        }
      }
    }
    return List.copyOf(result);
  }

  @Override
  public Collection<StationInfo> listByOperator(UUID operatorId) {
    if (operatorId == null) {
      return List.of();
    }
    List<StationInfo> result = new ArrayList<>();
    for (Station station : stationRepository.listByOperator(operatorId)) {
      result.add(convertStation(station));
    }
    return List.copyOf(result);
  }

  @Override
  public Collection<StationInfo> listByLine(UUID lineId) {
    if (lineId == null) {
      return List.of();
    }
    List<StationInfo> result = new ArrayList<>();
    for (Station station : stationRepository.listByLine(lineId)) {
      result.add(convertStation(station));
    }
    return List.copyOf(result);
  }

  @Override
  public Optional<StationInfo> getStation(UUID stationId) {
    if (stationId == null) {
      return Optional.empty();
    }
    return stationRepository.findById(stationId).map(this::convertStation);
  }

  @Override
  public Optional<StationInfo> findByCode(UUID operatorId, String stationCode) {
    if (operatorId == null || stationCode == null) {
      return Optional.empty();
    }
    return stationRepository
        .findByOperatorAndCode(operatorId, stationCode)
        .map(this::convertStation);
  }

  @Override
  public int stationCount() {
    // 遍历公司 -> 运营商统计数量
    int count = 0;
    if (companyRepository != null && operatorRepository != null) {
      for (Company company : companyRepository.listAll()) {
        for (Operator operator : operatorRepository.listByCompany(company.id())) {
          count += stationRepository.listByOperator(operator.id()).size();
        }
      }
    }
    return count;
  }

  private StationInfo convertStation(Station station) {
    Optional<Position> position =
        station.location().map(loc -> new Position(loc.x(), loc.y(), loc.z()));

    return new StationInfo(
        station.id(),
        station.code(),
        station.operatorId(),
        station.primaryLineId(),
        station.name(),
        station.secondaryName(),
        station.world(),
        position,
        station.graphNodeId());
  }
}
