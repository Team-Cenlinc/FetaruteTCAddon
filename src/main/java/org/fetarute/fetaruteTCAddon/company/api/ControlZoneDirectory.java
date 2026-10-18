package org.fetarute.fetaruteTCAddon.company.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.ControlZone;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 控制区的内存目录：站场图屏每次检查都要查，不能每秒读库。
 *
 * <p>启动时与控制区、运营商、公司改库之后整体重读（{@link #reload}）；查询只读不可变快照，可在任意线程调用。运营商代码跨公司重名时先到先得，与车站目录同一规则。
 */
public final class ControlZoneDirectory {

  private volatile Map<String, Zone> zones = Map.of();

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

  /** 从存储整体重读；读失败时保留旧快照并把异常抛给调用方。 */
  public void reload(StorageProvider provider) {
    Objects.requireNonNull(provider, "provider");
    Map<UUID, List<ControlZoneMember>> membersByZone = new HashMap<>();
    for (ControlZoneMember member : provider.controlZones().listAllMembers()) {
      membersByZone.computeIfAbsent(member.zoneId(), ignored -> new ArrayList<>()).add(member);
    }
    Map<UUID, Optional<String>> operatorCodes = new HashMap<>();
    Map<String, Zone> next = new LinkedHashMap<>();
    for (ControlZone zone : provider.controlZones().listAll()) {
      Optional<String> operator =
          operatorCodes.computeIfAbsent(
              zone.operatorId(), id -> provider.operators().findById(id).map(Operator::code));
      if (operator.isEmpty()) {
        continue;
      }
      List<ControlZoneMember> members =
          new ArrayList<>(membersByZone.getOrDefault(zone.id(), List.of()));
      members.sort(Comparator.comparingInt(ControlZoneMember::sortOrder));
      next.putIfAbsent(key(operator.get(), zone.code()), new Zone(zone, operator.get(), members));
    }
    zones = Map.copyOf(next);
  }

  /**
   * 按运营商代码与控制区代码查找（都不分大小写）。
   *
   * @param operatorCode 运营商代码
   * @param zoneCode 控制区代码
   */
  public Optional<Zone> find(String operatorCode, String zoneCode) {
    if (operatorCode == null || zoneCode == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(zones.get(key(operatorCode, zoneCode)));
  }

  /** 运营商名下的控制区，按代码排序。 */
  public List<Zone> listByOperator(String operatorCode) {
    if (operatorCode == null) {
      return List.of();
    }
    String wanted = operatorCode.trim().toUpperCase(Locale.ROOT);
    return zones.values().stream()
        .filter(zone -> zone.operatorCode().equals(wanted))
        .sorted(Comparator.comparing(Zone::code, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private static String key(String operatorCode, String zoneCode) {
    return operatorCode.trim().toUpperCase(Locale.ROOT)
        + ":"
        + zoneCode.trim().toUpperCase(Locale.ROOT);
  }
}
