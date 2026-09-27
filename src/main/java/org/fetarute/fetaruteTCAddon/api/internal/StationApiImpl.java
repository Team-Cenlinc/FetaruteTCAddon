package org.fetarute.fetaruteTCAddon.api.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;

/**
 * StationApi 内部实现：站点查询桥接到 StationRepository；车站组与停靠线路读 {@link StationDirectory} 的内存快照。
 *
 * <p>车站组与停靠线路的公开记录按快照版本整体转换一次后缓存，之后每次查询只是一次版本比较加查表，不访问存储。
 *
 * <p>仅供内部使用，外部插件应通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 访问。
 */
public final class StationApiImpl implements StationApi {

  private final StationRepository stationRepository;
  private final CompanyRepository companyRepository;
  private final OperatorRepository operatorRepository;
  private final StationDirectory directory;
  private volatile GroupView groupView;

  /**
   * 创建 StationApi 实现（不含车站组与停靠线路，这两类查询返回空）。
   *
   * @param stationRepository 站点仓库
   * @param companyRepository 公司仓库（用于遍历所有运营商）
   * @param operatorRepository 运营商仓库
   */
  public StationApiImpl(
      StationRepository stationRepository,
      CompanyRepository companyRepository,
      OperatorRepository operatorRepository) {
    this(stationRepository, companyRepository, operatorRepository, null);
  }

  /**
   * 创建 StationApi 实现。
   *
   * @param stationRepository 站点仓库
   * @param companyRepository 公司仓库（用于遍历所有运营商）
   * @param operatorRepository 运营商仓库
   * @param directory 车站目录（车站组与停靠线路）；为 null 时这两类查询返回空
   */
  public StationApiImpl(
      StationRepository stationRepository,
      CompanyRepository companyRepository,
      OperatorRepository operatorRepository,
      StationDirectory directory) {
    this.stationRepository = Objects.requireNonNull(stationRepository, "stationRepository");
    this.companyRepository = companyRepository;
    this.operatorRepository = operatorRepository;
    this.directory = directory;
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

  @Override
  public Collection<StationGroupInfo> listStationGroups() {
    return view().groups();
  }

  @Override
  public Optional<StationGroupInfo> findGroupOfStation(UUID stationId) {
    if (stationId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(view().groupByStation().get(stationId));
  }

  @Override
  public Optional<StationGroupInfo> findGroupOfNode(String nodeId) {
    GroupView view = view();
    return view.snapshot()
        .stationIdOfNode(nodeId)
        .map(stationId -> view.groupByStation().get(stationId));
  }

  @Override
  public List<ServingLine> linesServing(UUID stationId) {
    if (stationId == null) {
      return List.of();
    }
    return view().servingByStation().getOrDefault(stationId, List.of());
  }

  @Override
  public List<ServingLine> linesServingNode(String nodeId) {
    GroupView view = view();
    return view.snapshot()
        .stationIdOfNode(nodeId)
        .map(stationId -> view.servingByStation().getOrDefault(stationId, List.of()))
        .orElse(List.of());
  }

  /** 当前快照对应的公开记录；快照版本变了才整体重转一次。 */
  private GroupView view() {
    StationDirectory.Snapshot snapshot =
        directory == null ? StationDirectory.detachedSnapshot() : directory.snapshot();
    GroupView current = groupView;
    if (current != null && current.snapshot() == snapshot) {
      return current;
    }
    GroupView built = GroupView.of(snapshot);
    if (directory != null) {
      groupView = built;
    }
    return built;
  }

  /**
   * 一个快照版本的公开记录。
   *
   * @param snapshot 来源快照
   * @param groups 全部车站组
   * @param groupByStation 车站 → 所属车站组
   * @param servingByStation 车站 → 停靠线路
   */
  private record GroupView(
      StationDirectory.Snapshot snapshot,
      List<StationGroupInfo> groups,
      Map<UUID, StationGroupInfo> groupByStation,
      Map<UUID, List<ServingLine>> servingByStation) {

    static GroupView of(StationDirectory.Snapshot snapshot) {
      List<StationGroupInfo> groups = new ArrayList<>();
      Map<UUID, StationGroupInfo> byStation = new HashMap<>();
      for (StationDirectory.GroupEntry entry : snapshot.groups()) {
        List<StationGroupMember> members = new ArrayList<>();
        for (StationDirectory.MemberEntry member : entry.members()) {
          members.add(
              new StationGroupMember(
                  member.station().id(),
                  member.station().operator().id(),
                  member.station().operator().code(),
                  member.station().code(),
                  member.station().name(),
                  toApi(member.member().transferType()),
                  toOptionalInt(member.member().walkSeconds()),
                  member.member().sortOrder()));
        }
        StationGroupInfo info =
            new StationGroupInfo(
                entry.group().id(),
                entry.group().companyId(),
                entry.group().code(),
                entry.group().name(),
                entry.group().secondaryName(),
                List.copyOf(members));
        groups.add(info);
        for (StationGroupMember member : info.members()) {
          byStation.put(member.stationId(), info);
        }
      }
      Map<UUID, List<ServingLine>> serving = new HashMap<>();
      for (Map.Entry<UUID, List<StationDirectory.ServingLineEntry>> entry :
          snapshot.servingByStation().entrySet()) {
        List<ServingLine> lines = new ArrayList<>(entry.getValue().size());
        for (StationDirectory.ServingLineEntry line : entry.getValue()) {
          lines.add(
              new ServingLine(
                  line.line().line().id(),
                  line.line().operator().code(),
                  line.line().line().code(),
                  line.line().line().name(),
                  line.line().color(),
                  line.station().id(),
                  line.station().code(),
                  line.transferType().map(GroupView::toApi),
                  toOptionalInt(line.walkSeconds())));
        }
        serving.put(entry.getKey(), List.copyOf(lines));
      }
      return new GroupView(
          snapshot, List.copyOf(groups), Map.copyOf(byStation), Map.copyOf(serving));
    }

    private static TransferType toApi(StationTransferType type) {
      return switch (type) {
        case SAME_PLATFORM -> TransferType.SAME_PLATFORM;
        case IN_STATION -> TransferType.IN_STATION;
        case OUT_OF_STATION -> TransferType.OUT_OF_STATION;
      };
    }

    private static OptionalInt toOptionalInt(Optional<Integer> value) {
      return value.isPresent() ? OptionalInt.of(value.get()) : OptionalInt.empty();
    }
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
