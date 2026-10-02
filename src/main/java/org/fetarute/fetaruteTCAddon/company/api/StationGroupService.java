package org.fetarute.fetaruteTCAddon.company.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 车站组的增删改与权限判定。
 *
 * <p>权限规则：
 *
 * <ul>
 *   <li>组属于创建者所在的公司；建组、删组、改成员都要能管理该公司。
 *   <li>把<b>其他公司</b>的车站加入组：本次要求操作者同时能管理两家公司，否则拒绝（邀请/确认流程以后再做）。
 * </ul>
 *
 * <p>查找沿用命令解析约定（{@code company-management-schema.md}「Code 与命令解析」）：先按代码（可带 {@code 公司代码:}
 * 前缀消除歧义），未命中再按 UUID。 一次写多行的操作（写成员并更新组的修改时间）在同一个事务里完成。
 *
 * <p>权限用 {@code Predicate<UUID>}（公司 ID → 能否管理）传入，命令层接 {@code CompanyAccessChecker}，测试可直接构造。
 */
public final class StationGroupService {

  private final StorageProvider provider;

  public StationGroupService(StorageProvider provider) {
    this.provider = Objects.requireNonNull(provider, "provider");
  }

  // ─── 查找 ─────────────────────────────────────────────────────────────────

  /**
   * 按「组代码」「公司代码:组代码」或 UUID 查找车站组（代码不区分大小写，代码优先）。
   *
   * @param raw 用户输入
   * @return 唯一命中、未找到，或多家公司都有同代码的组（需带公司前缀）
   */
  public Lookup<StationGroup> findGroup(String raw) {
    if (raw == null || raw.isBlank()) {
      return Lookup.notFound();
    }
    String trimmed = raw.trim();
    Lookup<StationGroup> byCode = findGroupByCode(trimmed);
    if (!byCode.matches().isEmpty()) {
      return byCode;
    }
    return Lookup.of(
        parseUuid(trimmed).flatMap(provider.stationGroups()::findById).stream().toList());
  }

  private Lookup<StationGroup> findGroupByCode(String trimmed) {
    int colon = trimmed.indexOf(':');
    if (colon > 0 && colon < trimmed.length() - 1) {
      String companyCode = trimmed.substring(0, colon).trim();
      String groupCode = trimmed.substring(colon + 1).trim();
      Optional<Company> company = new CompanyQueryService(provider).findCompany(companyCode);
      if (company.isEmpty()) {
        return Lookup.notFound();
      }
      return Lookup.of(
          matchGroups(provider.stationGroups().listByCompany(company.get().id()), groupCode));
    }
    return Lookup.of(matchGroups(provider.stationGroups().listAll(), trimmed));
  }

  /**
   * 按「运营商代码」「公司代码:运营商代码」或 UUID 查找运营商（代码不区分大小写，代码优先）。
   *
   * <p>不带公司前缀时，优先 {@code preferredCompanyId} 名下的同代码运营商；否则在全部公司中找，多家公司都有时返回歧义。
   *
   * @param raw 用户输入
   * @param preferredCompanyId 优先公司（通常是车站组所属公司）
   */
  public Lookup<Operator> findOperator(String raw, Optional<UUID> preferredCompanyId) {
    if (raw == null || raw.isBlank()) {
      return Lookup.notFound();
    }
    String trimmed = raw.trim();
    Lookup<Operator> byCode = findOperatorByCode(trimmed, preferredCompanyId);
    if (!byCode.matches().isEmpty()) {
      return byCode;
    }
    return Lookup.of(parseUuid(trimmed).flatMap(provider.operators()::findById).stream().toList());
  }

  private Lookup<Operator> findOperatorByCode(String trimmed, Optional<UUID> preferredCompanyId) {
    int colon = trimmed.indexOf(':');
    if (colon > 0 && colon < trimmed.length() - 1) {
      String companyCode = trimmed.substring(0, colon).trim();
      String operatorCode = trimmed.substring(colon + 1).trim();
      Optional<Company> company = new CompanyQueryService(provider).findCompany(companyCode);
      if (company.isEmpty()) {
        return Lookup.notFound();
      }
      return Lookup.of(matchOperators(company.get().id(), operatorCode));
    }
    if (preferredCompanyId.isPresent()) {
      List<Operator> preferred = matchOperators(preferredCompanyId.get(), trimmed);
      if (!preferred.isEmpty()) {
        return Lookup.of(preferred);
      }
    }
    List<Operator> matches = new ArrayList<>();
    for (Company company : provider.companies().listAll()) {
      if (company != null) {
        matches.addAll(matchOperators(company.id(), trimmed));
      }
    }
    return Lookup.of(matches);
  }

  /** 车站所属公司（经由运营商）。 */
  public Optional<UUID> companyOfStation(Station station) {
    if (station == null) {
      return Optional.empty();
    }
    return provider.operators().findById(station.operatorId()).map(Operator::companyId);
  }

  // ─── 修改 ─────────────────────────────────────────────────────────────────

  /**
   * 建组。
   *
   * @param company 所属公司
   * @param code 组代码（公司内唯一，不区分大小写）
   * @param name 组名
   * @param secondaryName 第二语言名称
   * @param canManage 公司 ID → 操作者能否管理
   */
  public Result create(
      Company company,
      String code,
      String name,
      Optional<String> secondaryName,
      Predicate<UUID> canManage) {
    Objects.requireNonNull(company, "company");
    if (!canManage.test(company.id())) {
      return Result.of(Status.NO_PERMISSION);
    }
    String trimmedCode = code == null ? "" : code.trim();
    String trimmedName = name == null ? "" : name.trim();
    if (trimmedCode.isEmpty() || trimmedCode.contains(":") || trimmedName.isEmpty()) {
      return Result.of(Status.INVALID);
    }
    if (!matchGroups(provider.stationGroups().listByCompany(company.id()), trimmedCode).isEmpty()) {
      return Result.of(Status.DUPLICATE);
    }
    Instant now = Instant.now();
    StationGroup group =
        new StationGroup(
            UUID.randomUUID(),
            company.id(),
            trimmedCode,
            trimmedName,
            secondaryName.map(String::trim).filter(value -> !value.isEmpty()),
            Map.of(),
            now,
            now);
    provider.stationGroups().save(group);
    return new Result(
        Status.CREATED,
        Optional.of(group),
        Optional.empty(),
        Optional.empty(),
        Optional.of(StationGroupChange.of(group, StationGroupChange.Kind.CREATED, null)));
  }

  /**
   * 把车站加入组；车站已在本组时更新换乘方式与步行秒数（保留原排序）。
   *
   * @param group 车站组
   * @param station 车站
   * @param transferType 换乘方式
   * @param walkSeconds 步行秒数
   * @param canManage 公司 ID → 操作者能否管理
   */
  public Result addMember(
      StationGroup group,
      Station station,
      StationTransferType transferType,
      Optional<Integer> walkSeconds,
      Predicate<UUID> canManage) {
    Objects.requireNonNull(group, "group");
    Objects.requireNonNull(station, "station");
    if (!canManage.test(group.companyId())) {
      return Result.of(Status.NO_PERMISSION);
    }
    Optional<UUID> stationCompany = companyOfStation(station);
    if (stationCompany.isEmpty()) {
      return Result.of(Status.INVALID);
    }
    if (!stationCompany.get().equals(group.companyId()) && !canManage.test(stationCompany.get())) {
      return new Result(
          Status.CROSS_COMPANY_DENIED,
          Optional.of(group),
          Optional.empty(),
          stationCompany,
          Optional.empty());
    }
    Optional<StationGroupMember> existing =
        provider.stationGroups().findMemberByStation(station.id());
    if (existing.isPresent() && !existing.get().groupId().equals(group.id())) {
      Optional<StationGroup> other = provider.stationGroups().findById(existing.get().groupId());
      return new Result(Status.IN_OTHER_GROUP, other, existing, stationCompany, Optional.empty());
    }
    StationGroupMember member =
        provider
            .transactionManager()
            .execute(
                () -> {
                  int sortOrder =
                      existing
                          .map(StationGroupMember::sortOrder)
                          .orElseGet(() -> nextSortOrder(group));
                  StationGroupMember saved =
                      new StationGroupMember(
                          group.id(),
                          station.id(),
                          transferType == null ? StationTransferType.IN_STATION : transferType,
                          walkSeconds,
                          sortOrder);
                  provider.stationGroups().saveMember(saved);
                  touch(group);
                  return saved;
                });
    StationGroupChange.Kind kind =
        existing.isPresent()
            ? StationGroupChange.Kind.MEMBER_UPDATED
            : StationGroupChange.Kind.MEMBER_ADDED;
    return new Result(
        existing.isPresent() ? Status.UPDATED : Status.ADDED,
        Optional.of(group),
        Optional.of(member),
        stationCompany,
        Optional.of(StationGroupChange.of(group, kind, station.id())));
  }

  /**
   * 把车站移出组（需要能管理组所在公司）。
   *
   * @param group 车站组
   * @param station 车站
   * @param canManage 公司 ID → 操作者能否管理
   */
  public Result removeMember(StationGroup group, Station station, Predicate<UUID> canManage) {
    Objects.requireNonNull(group, "group");
    Objects.requireNonNull(station, "station");
    if (!canManage.test(group.companyId())) {
      return Result.of(Status.NO_PERMISSION);
    }
    Optional<UUID> stationCompany = companyOfStation(station);
    Optional<StationGroupMember> existing =
        provider.stationGroups().findMemberByStation(station.id());
    if (existing.isEmpty() || !existing.get().groupId().equals(group.id())) {
      return Result.of(Status.NOT_MEMBER);
    }
    provider
        .transactionManager()
        .execute(
            () -> {
              provider.stationGroups().removeMember(group.id(), station.id());
              touch(group);
              return null;
            });
    return new Result(
        Status.REMOVED,
        Optional.of(group),
        existing,
        stationCompany,
        Optional.of(
            StationGroupChange.of(group, StationGroupChange.Kind.MEMBER_REMOVED, station.id())));
  }

  /** 删除车站组（成员一并删除）。 */
  public Result delete(StationGroup group, Predicate<UUID> canManage) {
    Objects.requireNonNull(group, "group");
    if (!canManage.test(group.companyId())) {
      return Result.of(Status.NO_PERMISSION);
    }
    provider.stationGroups().delete(group.id());
    return new Result(
        Status.DELETED,
        Optional.of(group),
        Optional.empty(),
        Optional.empty(),
        Optional.of(StationGroupChange.of(group, StationGroupChange.Kind.DELETED, null)));
  }

  private int nextSortOrder(StationGroup group) {
    int max = -1;
    for (StationGroupMember member : provider.stationGroups().listMembers(group.id())) {
      max = Math.max(max, member.sortOrder());
    }
    return max + 1;
  }

  private void touch(StationGroup group) {
    provider
        .stationGroups()
        .save(
            new StationGroup(
                group.id(),
                group.companyId(),
                group.code(),
                group.name(),
                group.secondaryName(),
                group.metadata(),
                group.createdAt(),
                Instant.now()));
  }

  private static Optional<UUID> parseUuid(String raw) {
    try {
      return Optional.of(UUID.fromString(raw));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  private static List<StationGroup> matchGroups(List<StationGroup> groups, String code) {
    List<StationGroup> matches = new ArrayList<>();
    if (groups == null) {
      return matches;
    }
    String wanted = code.trim().toLowerCase(Locale.ROOT);
    for (StationGroup group : groups) {
      if (group != null && group.code().trim().toLowerCase(Locale.ROOT).equals(wanted)) {
        matches.add(group);
      }
    }
    return matches;
  }

  private List<Operator> matchOperators(UUID companyId, String code) {
    List<Operator> matches = new ArrayList<>();
    String wanted = code.trim().toLowerCase(Locale.ROOT);
    for (Operator operator : provider.operators().listByCompany(companyId)) {
      if (operator != null && operator.code().trim().toLowerCase(Locale.ROOT).equals(wanted)) {
        matches.add(operator);
      }
    }
    return matches;
  }

  // ─── 结果 ─────────────────────────────────────────────────────────────────

  /** 操作结果状态。 */
  public enum Status {
    CREATED,
    ADDED,
    UPDATED,
    REMOVED,
    DELETED,
    /** 不能管理组所在公司。 */
    NO_PERMISSION,
    /** 车站属于其他公司，而操作者不能管理那家公司。 */
    CROSS_COMPANY_DENIED,
    /** 车站已在另一个组。 */
    IN_OTHER_GROUP,
    /** 车站不在本组。 */
    NOT_MEMBER,
    /** 同公司下已有同代码的组。 */
    DUPLICATE,
    /** 参数不合法（空代码、代码含冒号、车站没有运营商等）。 */
    INVALID;

    /** 是否真的改了数据。 */
    public boolean changed() {
      return this == CREATED
          || this == ADDED
          || this == UPDATED
          || this == REMOVED
          || this == DELETED;
    }
  }

  /**
   * 操作结果。
   *
   * @param status 状态
   * @param group 相关车站组（{@code IN_OTHER_GROUP} 时是车站已在的那个组）
   * @param member 相关成员
   * @param stationCompanyId 车站所属公司
   * @param change 数据变化（只有改了数据时才有），用于刷新索引与发事件
   */
  public record Result(
      Status status,
      Optional<StationGroup> group,
      Optional<StationGroupMember> member,
      Optional<UUID> stationCompanyId,
      Optional<StationGroupChange> change) {

    static Result of(Status status) {
      return new Result(
          status, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
  }

  /**
   * 查找结果：唯一命中、未找到或歧义。
   *
   * @param matches 全部命中
   */
  public record Lookup<T>(List<T> matches) {
    public Lookup {
      matches = List.copyOf(matches);
    }

    static <T> Lookup<T> notFound() {
      return new Lookup<>(List.of());
    }

    static <T> Lookup<T> of(List<T> matches) {
      return new Lookup<>(matches == null ? List.of() : matches);
    }

    /** 唯一命中。 */
    public Optional<T> unique() {
      return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** 多个命中。 */
    public boolean ambiguous() {
      return matches.size() > 1;
    }
  }
}
