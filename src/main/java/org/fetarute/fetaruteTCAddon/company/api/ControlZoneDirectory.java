package org.fetarute.fetaruteTCAddon.company.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.ControlZone;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 控制区的内存目录：站场图屏每次检查都要查，不能每秒读库。
 *
 * <p>启动时与控制区、运营商、公司改库之后整体重读（{@link #reload}）；查询只读不可变快照，可在任意线程调用。
 *
 * <p>站台屏只按运营商代码认运营商：跨公司同代码时先到先得（公司、运营商按库里的先后），与车站目录同一规则，按代码只查得到这个运营商的控制区； 命令按运营商 ID 查，各公司的控制区都查得到。
 */
public final class ControlZoneDirectory {

  private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of());

  /**
   * 控制区连同成员。
   *
   * @param zone 控制区
   * @param operatorCode 所属运营商代码（大写）
   * @param members 成员，按排序
   */
  public record Zone(ControlZone zone, String operatorCode, List<ControlZoneMember> members) {
    public Zone {
      Objects.requireNonNull(zone, "zone");
      operatorCode = Objects.requireNonNull(operatorCode, "operatorCode").toUpperCase(Locale.ROOT);
      members = List.copyOf(members);
    }

    /** 控制区代码。 */
    public String code() {
      return zone.code();
    }
  }

  /**
   * @param byCode {@code 运营商代码:控制区代码}（大写）→ 控制区，只含按代码认得的那个运营商的
   * @param byOperator 运营商 ID → 控制区，按代码排序
   * @param operatorIds 运营商代码（大写）→ 按代码认得的运营商
   */
  private record Snapshot(
      Map<String, Zone> byCode, Map<UUID, List<Zone>> byOperator, Map<String, UUID> operatorIds) {}

  /** 从存储整体重读；读失败时保留旧快照并把异常抛给调用方。 */
  public void reload(StorageProvider provider) {
    Objects.requireNonNull(provider, "provider");
    Map<UUID, Operator> operators = new HashMap<>();
    Map<String, UUID> operatorIds = new HashMap<>();
    for (Company company : provider.companies().listAll()) {
      if (company == null) {
        continue;
      }
      for (Operator operator : provider.operators().listByCompany(company.id())) {
        if (operator != null) {
          operators.put(operator.id(), operator);
          operatorIds.putIfAbsent(upper(operator.code()), operator.id());
        }
      }
    }
    Map<UUID, List<ControlZoneMember>> membersByZone = new HashMap<>();
    for (ControlZoneMember member : provider.controlZones().listAllMembers()) {
      membersByZone.computeIfAbsent(member.zoneId(), ignored -> new ArrayList<>()).add(member);
    }
    Map<String, Zone> byCode = new HashMap<>();
    Map<UUID, List<Zone>> byOperator = new HashMap<>();
    for (ControlZone zone : provider.controlZones().listAll()) {
      Operator operator = operators.get(zone.operatorId());
      if (operator == null) {
        continue;
      }
      List<ControlZoneMember> members =
          new ArrayList<>(membersByZone.getOrDefault(zone.id(), List.of()));
      members.sort(Comparator.comparingInt(ControlZoneMember::sortOrder));
      Zone entry = new Zone(zone, operator.code(), members);
      byOperator.computeIfAbsent(operator.id(), ignored -> new ArrayList<>()).add(entry);
      if (operator.id().equals(operatorIds.get(upper(operator.code())))) {
        byCode.put(key(operator.code(), zone.code()), entry);
      }
    }
    Map<UUID, List<Zone>> sorted = new HashMap<>();
    byOperator.forEach(
        (id, list) ->
            sorted.put(
                id,
                list.stream()
                    .sorted(Comparator.comparing(Zone::code, String.CASE_INSENSITIVE_ORDER))
                    .toList()));
    snapshot = new Snapshot(Map.copyOf(byCode), Map.copyOf(sorted), Map.copyOf(operatorIds));
  }

  /**
   * 按运营商代码与控制区代码查找（都不分大小写；运营商代码按先到先得认）。
   *
   * @param operatorCode 运营商代码
   * @param zoneCode 控制区代码
   */
  public Optional<Zone> find(String operatorCode, String zoneCode) {
    if (operatorCode == null || zoneCode == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(snapshot.byCode().get(key(operatorCode, zoneCode)));
  }

  /** 按代码认得的运营商名下的控制区，按代码排序。 */
  public List<Zone> listByOperator(String operatorCode) {
    if (operatorCode == null) {
      return List.of();
    }
    Snapshot current = snapshot;
    UUID id = current.operatorIds().get(upper(operatorCode));
    return id == null ? List.of() : current.byOperator().getOrDefault(id, List.of());
  }

  /** 运营商名下的控制区，按代码排序。 */
  public List<Zone> listByOperator(UUID operatorId) {
    return operatorId == null
        ? List.of()
        : snapshot.byOperator().getOrDefault(operatorId, List.of());
  }

  private static String key(String operatorCode, String zoneCode) {
    return upper(operatorCode) + ":" + upper(zoneCode);
  }

  private static String upper(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
  }
}
