package org.fetarute.fetaruteTCAddon.company.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.company.model.ControlZone;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 控制区的增删改与权限判定。
 *
 * <ul>
 *   <li>控制区属于一个运营商，代码在运营商内唯一（不分大小写）、不能含冒号；改任何东西都要能管理运营商所属的公司。
 *   <li>成员只能是同一运营商的车站或车库：车站要在库里登记过，写回登记的站码；车库在库里没有实体，由调用方先在调度图里核对。
 * </ul>
 *
 * <p>权限用 {@code Predicate<UUID>}（公司 ID → 能否管理）传入，命令层接 {@code
 * CompanyAccessChecker}，测试可直接构造。改库之后由调用方刷新 {@link ControlZoneDirectory}。
 */
public final class ControlZoneService {

  private final StorageProvider provider;

  public ControlZoneService(StorageProvider provider) {
    this.provider = Objects.requireNonNull(provider, "provider");
  }

  /** 按代码查找运营商名下的控制区（不分大小写）。 */
  public Optional<ControlZone> find(Operator operator, String code) {
    Objects.requireNonNull(operator, "operator");
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    String wanted = code.trim();
    return provider.controlZones().listByOperator(operator.id()).stream()
        .filter(zone -> zone.code().equalsIgnoreCase(wanted))
        .findFirst();
  }

  /**
   * 建控制区。
   *
   * @param operator 所属运营商
   * @param code 代码
   * @param name 名称
   * @param secondaryName 英文名
   * @param canManage 公司 ID → 操作者能否管理
   */
  public Result create(
      Operator operator,
      String code,
      String name,
      Optional<String> secondaryName,
      Predicate<UUID> canManage) {
    Objects.requireNonNull(operator, "operator");
    if (!canManage.test(operator.companyId())) {
      return Result.of(Status.NO_PERMISSION);
    }
    String trimmedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    String trimmedName = name == null ? "" : name.trim();
    if (trimmedCode.isEmpty() || trimmedCode.contains(":") || trimmedName.isEmpty()) {
      return Result.of(Status.INVALID);
    }
    if (find(operator, trimmedCode).isPresent()) {
      return Result.of(Status.DUPLICATE);
    }
    Instant now = Instant.now();
    ControlZone zone =
        new ControlZone(
            UUID.randomUUID(),
            operator.id(),
            trimmedCode,
            trimmedName,
            normalize(secondaryName),
            Map.of(),
            now,
            now);
    provider.controlZones().save(zone);
    return Result.of(Status.CREATED, zone);
  }

  /**
   * 改名称与英文名。
   *
   * @param zone 控制区
   * @param name 新名称
   * @param secondaryName 新英文名；空表示去掉
   * @param canManage 公司 ID → 操作者能否管理
   */
  public Result rename(
      ControlZone zone, String name, Optional<String> secondaryName, Predicate<UUID> canManage) {
    Objects.requireNonNull(zone, "zone");
    if (!canManage(zone, canManage)) {
      return Result.of(Status.NO_PERMISSION);
    }
    String trimmedName = name == null ? "" : name.trim();
    if (trimmedName.isEmpty()) {
      return Result.of(Status.INVALID);
    }
    ControlZone renamed =
        new ControlZone(
            zone.id(),
            zone.operatorId(),
            zone.code(),
            trimmedName,
            normalize(secondaryName),
            zone.metadata(),
            zone.createdAt(),
            Instant.now());
    provider.controlZones().save(renamed);
    return Result.of(Status.UPDATED, renamed);
  }

  /**
   * 加入成员；已在区内时不改动。
   *
   * @param zone 控制区
   * @param kind 车站或车库
   * @param code 站码或车库代码；车站按库里登记的站码写回
   * @param canManage 公司 ID → 操作者能否管理
   */
  public Result addMember(
      ControlZone zone, ControlZoneMember.Kind kind, String code, Predicate<UUID> canManage) {
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(kind, "kind");
    if (!canManage(zone, canManage)) {
      return Result.of(Status.NO_PERMISSION);
    }
    String trimmed = code == null ? "" : code.trim();
    if (trimmed.isEmpty() || trimmed.contains(":")) {
      return Result.of(Status.INVALID, zone);
    }
    if (kind == ControlZoneMember.Kind.STATION) {
      Optional<Station> station =
          new CompanyQueryService(provider).findStationIgnoreCase(zone.operatorId(), trimmed);
      if (station.isEmpty()) {
        return Result.of(Status.NOT_FOUND, zone);
      }
      trimmed = station.get().code();
    }
    List<ControlZoneMember> members = provider.controlZones().listMembers(zone.id());
    String wanted = trimmed.toUpperCase(Locale.ROOT);
    if (members.stream().anyMatch(m -> m.kind() == kind && m.code().equals(wanted))) {
      return Result.of(Status.ALREADY_MEMBER, zone);
    }
    int sortOrder = members.stream().mapToInt(ControlZoneMember::sortOrder).max().orElse(-1) + 1;
    provider.controlZones().saveMember(new ControlZoneMember(zone.id(), kind, wanted, sortOrder));
    return Result.of(Status.ADDED, zone);
  }

  /**
   * 移出成员。
   *
   * @param zone 控制区
   * @param kind 车站或车库
   * @param code 站码或车库代码（不分大小写）
   * @param canManage 公司 ID → 操作者能否管理
   */
  public Result removeMember(
      ControlZone zone, ControlZoneMember.Kind kind, String code, Predicate<UUID> canManage) {
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(kind, "kind");
    if (!canManage(zone, canManage)) {
      return Result.of(Status.NO_PERMISSION);
    }
    String wanted = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    boolean member =
        provider.controlZones().listMembers(zone.id()).stream()
            .anyMatch(m -> m.kind() == kind && m.code().equals(wanted));
    if (!member) {
      return Result.of(Status.NOT_MEMBER, zone);
    }
    provider.controlZones().removeMember(zone.id(), kind, wanted);
    return Result.of(Status.REMOVED, zone);
  }

  /** 删除控制区（成员一并删除）。 */
  public Result delete(ControlZone zone, Predicate<UUID> canManage) {
    Objects.requireNonNull(zone, "zone");
    if (!canManage(zone, canManage)) {
      return Result.of(Status.NO_PERMISSION);
    }
    provider.controlZones().delete(zone.id());
    return Result.of(Status.DELETED, zone);
  }

  private boolean canManage(ControlZone zone, Predicate<UUID> canManage) {
    return provider
        .operators()
        .findById(zone.operatorId())
        .map(Operator::companyId)
        .filter(canManage)
        .isPresent();
  }

  private static Optional<String> normalize(Optional<String> value) {
    return value == null
        ? Optional.empty()
        : value.map(String::trim).filter(trimmed -> !trimmed.isEmpty());
  }

  /** 操作结果状态。 */
  public enum Status {
    CREATED,
    UPDATED,
    ADDED,
    REMOVED,
    DELETED,
    /** 不能管理运营商所属的公司。 */
    NO_PERMISSION,
    /** 运营商下已有同代码的控制区。 */
    DUPLICATE,
    /** 参数不合法（空代码、代码含冒号、空名称）。 */
    INVALID,
    /** 车站不存在。 */
    NOT_FOUND,
    /** 已在区内。 */
    ALREADY_MEMBER,
    /** 不在区内。 */
    NOT_MEMBER;

    /** 是否真的改了数据。 */
    public boolean changed() {
      return this == CREATED
          || this == UPDATED
          || this == ADDED
          || this == REMOVED
          || this == DELETED;
    }
  }

  /**
   * 操作结果。
   *
   * @param status 状态
   * @param zone 相关控制区（改名后为新记录）
   */
  public record Result(Status status, Optional<ControlZone> zone) {

    static Result of(Status status) {
      return new Result(status, Optional.empty());
    }

    static Result of(Status status, ControlZone zone) {
      return new Result(status, Optional.of(zone));
    }
  }
}
