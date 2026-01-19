package org.fetarute.fetaruteTCAddon.company.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationLocation;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 站点主数据自愈：根据图构建/扫描到的节点牌子，自动创建/补全 Station 记录。
 *
 * <p>约定：
 *
 * <ul>
 *   <li>仅处理 {@link WaypointKind#STATION}（AutoStation/站点类节点）
 *   <li>仅补全缺失字段：不会覆盖已存在的 {@link Station#name()}（显示名）/secondaryName
 *   <li>多股道站点：选取 {@code trackNumber} 最小的节点作为默认 {@code graphNodeId}
 * </ul>
 */
public final class StationAutoSyncService {

  private final Consumer<String> debugLogger;

  public StationAutoSyncService(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  public StationAutoSyncResult syncFromRailNodes(
      StorageProvider provider, String worldName, List<RailNodeRecord> nodes) {
    Objects.requireNonNull(provider, "provider");
    if (nodes == null || nodes.isEmpty()) {
      return StationAutoSyncResult.empty();
    }
    Map<String, Operator> operators = buildUniqueOperatorCodeMap(provider);
    if (operators.isEmpty()) {
      return StationAutoSyncResult.empty();
    }

    Map<StationKey, Candidate> candidates = new HashMap<>();
    for (RailNodeRecord record : nodes) {
      if (record == null) {
        continue;
      }
      WaypointMetadata meta = record.waypointMetadata().orElse(null);
      if (meta == null || meta.kind() != WaypointKind.STATION) {
        continue;
      }
      String operatorCode = safeLower(meta.operator());
      String stationCode = meta.originStation();
      if (operatorCode.isBlank() || stationCode == null || stationCode.isBlank()) {
        continue;
      }
      Operator operator = operators.get(operatorCode);
      if (operator == null) {
        continue;
      }
      StationKey key = new StationKey(operator.id(), stationCode.trim());
      Candidate candidate =
          candidates.computeIfAbsent(key, k -> new Candidate(operator, stationCode.trim()));
      candidate.add(record, meta);
    }

    if (candidates.isEmpty()) {
      return StationAutoSyncResult.empty();
    }

    int created = 0;
    int updated = 0;
    Instant now = Instant.now();

    List<Candidate> sorted = new ArrayList<>(candidates.values());
    sorted.sort(Comparator.comparing(c -> c.operator.code().toLowerCase(Locale.ROOT)));
    for (Candidate candidate : sorted) {
      candidate.selectPreferred();
      Optional<Station> existingOpt =
          provider.stations().findByOperatorAndCode(candidate.operator.id(), candidate.stationCode);
      if (existingOpt.isEmpty()) {
        Station createdStation =
            new Station(
                UUID.randomUUID(),
                candidate.stationCode,
                candidate.operator.id(),
                Optional.empty(),
                candidate.stationCode,
                Optional.empty(),
                Optional.ofNullable(worldName).filter(s -> !s.isBlank()).map(String::trim),
                candidate.location(),
                candidate.graphNodeId(),
                Optional.empty(),
                Map.of(),
                now,
                now);
        provider.stations().save(createdStation);
        created++;
        continue;
      }
      Station existing = existingOpt.get();
      Optional<String> graphNodeId =
          existing.graphNodeId().isPresent() ? existing.graphNodeId() : candidate.graphNodeId();
      Optional<String> world =
          existing.world().isPresent()
              ? existing.world()
              : Optional.ofNullable(worldName).filter(s -> !s.isBlank()).map(String::trim);
      Optional<StationLocation> location =
          existing.location().isPresent() ? existing.location() : candidate.location();
      if (graphNodeId.equals(existing.graphNodeId())
          && world.equals(existing.world())
          && location.equals(existing.location())) {
        continue;
      }
      Station updatedStation =
          new Station(
              existing.id(),
              existing.code(),
              existing.operatorId(),
              existing.primaryLineId(),
              existing.name(),
              existing.secondaryName(),
              world,
              location,
              graphNodeId,
              existing.amenities(),
              existing.metadata(),
              existing.createdAt(),
              now);
      provider.stations().save(updatedStation);
      updated++;
    }

    debugLogger.accept(
        "站点自愈: created=" + created + " updated=" + updated + " candidates=" + candidates.size());
    return new StationAutoSyncResult(created, updated, candidates.size());
  }

  private Map<String, Operator> buildUniqueOperatorCodeMap(StorageProvider provider) {
    Map<String, Operator> unique = new HashMap<>();
    java.util.Set<String> ambiguous = new java.util.HashSet<>();
    for (Company company : provider.companies().listAll()) {
      if (company == null) {
        continue;
      }
      for (Operator operator : provider.operators().listByCompany(company.id())) {
        if (operator == null || operator.code() == null) {
          continue;
        }
        String key = safeLower(operator.code());
        if (key.isBlank()) {
          continue;
        }
        if (ambiguous.contains(key)) {
          continue;
        }
        Operator existing = unique.get(key);
        if (existing != null && !existing.id().equals(operator.id())) {
          unique.remove(key);
          ambiguous.add(key);
          continue;
        }
        unique.putIfAbsent(key, operator);
      }
    }
    if (!ambiguous.isEmpty()) {
      debugLogger.accept("站点自愈跳过重复运营商 code: " + String.join(", ", ambiguous));
    }
    return unique;
  }

  private static String safeLower(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  public record StationAutoSyncResult(int created, int updated, int candidates) {
    public static StationAutoSyncResult empty() {
      return new StationAutoSyncResult(0, 0, 0);
    }
  }

  private record StationKey(UUID operatorId, String stationCode) {}

  private static final class Candidate {
    private final Operator operator;
    private final String stationCode;
    private final List<Choice> choices = new ArrayList<>();
    private Optional<String> graphNodeId = Optional.empty();
    private Optional<StationLocation> location = Optional.empty();

    private Candidate(Operator operator, String stationCode) {
      this.operator = Objects.requireNonNull(operator, "operator");
      this.stationCode = Objects.requireNonNull(stationCode, "stationCode");
    }

    private void add(RailNodeRecord record, WaypointMetadata meta) {
      if (record == null || meta == null) {
        return;
      }
      choices.add(new Choice(record, meta.trackNumber()));
    }

    private void selectPreferred() {
      if (choices.isEmpty()) {
        return;
      }
      choices.sort(
          Comparator.comparingInt(Choice::trackNumber)
              .thenComparing(c -> c.record.nodeId().value(), String.CASE_INSENSITIVE_ORDER));
      RailNodeRecord record = choices.get(0).record;
      this.graphNodeId = Optional.of(record.nodeId().value());
      double x = record.x() + 0.5;
      double y = record.y() + 0.5;
      double z = record.z() + 0.5;
      this.location = Optional.of(new StationLocation(x, y, z, 0.0f, 0.0f));
    }

    private Optional<String> graphNodeId() {
      return graphNodeId;
    }

    private Optional<StationLocation> location() {
      return location;
    }
  }

  private record Choice(RailNodeRecord record, int trackNumber) {}
}
