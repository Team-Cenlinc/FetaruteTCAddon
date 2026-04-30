package org.fetarute.fetaruteTCAddon.command;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ServiceTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.LineSpawnMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDepot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 发车计划诊断工具。
 *
 * <p>命令层只负责渲染；这里统一处理 depot 候选、DYNAMIC depot、已选择 depot、未发车原因等判断，避免 `/fta spawn` 与 `/fta depot
 * schedule check` 出现两套口径。
 */
final class SpawnScheduleDiagnostics {

  private SpawnScheduleDiagnostics() {}

  /** 判断服务是否可能从指定 depot 发车。 */
  static boolean serviceMatchesDepot(
      StorageProvider provider, SpawnService service, String depotNodeId) {
    if (service == null || depotNodeId == null || depotNodeId.isBlank()) {
      return false;
    }
    return depotCandidates(provider, service).stream()
        .anyMatch(candidate -> depotCandidateMatches(candidate, depotNodeId));
  }

  /** 判断票据是否归属于指定 depot。 */
  static boolean ticketMatchesDepot(
      StorageProvider provider, SpawnTicket ticket, String depotNodeId) {
    if (ticket == null
        || ticket.service() == null
        || depotNodeId == null
        || depotNodeId.isBlank()) {
      return false;
    }
    if (ticket.selectedDepotNodeId().isPresent()) {
      return depotCandidateMatches(ticket.selectedDepotNodeId().get(), depotNodeId);
    }
    return serviceMatchesDepot(provider, ticket.service(), depotNodeId);
  }

  /** 判断静态计划车次是否归属于指定 depot。 */
  static boolean tripMatchesDepot(ServiceTrip trip, String depotNodeId) {
    if (trip == null || depotNodeId == null || depotNodeId.isBlank()) {
      return false;
    }
    return trip.depotCandidates().stream()
        .map(SpawnDepot::nodeId)
        .anyMatch(candidate -> depotCandidateMatches(candidate, depotNodeId));
  }

  /** 生成服务的 depot 候选文本。 */
  static String depotCandidatesText(StorageProvider provider, SpawnService service) {
    List<String> candidates = depotCandidates(provider, service);
    return candidates.isEmpty() ? "-" : String.join(",", candidates);
  }

  /** 解释票据当前没有发出的主要原因。 */
  static String ticketReason(SpawnTicket ticket, Instant now) {
    if (ticket == null) {
      return "unknown";
    }
    if (ticket.lastError().isPresent() && !ticket.lastError().get().isBlank()) {
      return ticket.lastError().get();
    }
    Instant effectiveNow = now == null ? Instant.now() : now;
    if (ticket.notBefore() != null && effectiveNow.isBefore(ticket.notBefore())) {
      return "not-before:" + Duration.between(effectiveNow, ticket.notBefore()).toSeconds() + "s";
    }
    if (ticket.dueAt() != null && effectiveNow.isBefore(ticket.dueAt())) {
      return "due-in:" + Duration.between(effectiveNow, ticket.dueAt()).toSeconds() + "s";
    }
    return ticket.attempts() > 0 ? "retry-ready" : "ready";
  }

  /** 汇总未发车原因。 */
  static Map<String, Long> reasonSummary(List<SpawnTicket> tickets, Instant now) {
    if (tickets == null || tickets.isEmpty()) {
      return Map.of();
    }
    return tickets.stream()
        .filter(ticket -> ticket != null)
        .collect(
            Collectors.groupingBy(
                ticket -> ticketReason(ticket, now),
                java.util.LinkedHashMap::new,
                Collectors.counting()));
  }

  private static List<String> depotCandidates(StorageProvider provider, SpawnService service) {
    if (service == null) {
      return List.of();
    }
    List<String> candidates = new ArrayList<>();
    if (provider != null && service.lineId() != null) {
      Optional<Line> lineOpt = provider.lines().findById(service.lineId());
      if (lineOpt.isPresent()) {
        for (SpawnDepot depot : LineSpawnMetadata.parseDepots(lineOpt.get().metadata())) {
          if (depot != null && depot.nodeId() != null && !depot.nodeId().isBlank()) {
            candidates.add(formatDepotCandidate(depot));
          }
        }
      }
    }
    if (candidates.isEmpty() && service.depotNodeId() != null && !service.depotNodeId().isBlank()) {
      candidates.add(service.depotNodeId());
    }
    return List.copyOf(candidates);
  }

  private static String formatDepotCandidate(SpawnDepot depot) {
    return depot.weight() == 1 ? depot.nodeId() : depot.nodeId() + "*" + depot.weight();
  }

  private static boolean depotCandidateMatches(String candidateRaw, String depotNodeId) {
    if (candidateRaw == null || depotNodeId == null) {
      return false;
    }
    String candidate = stripWeight(candidateRaw);
    String depot = depotNodeId.trim();
    if (candidate.equalsIgnoreCase(depot)) {
      return true;
    }
    return DynamicStopMatcher.parseDynamicSpec(candidate)
        .filter(spec -> "D".equalsIgnoreCase(spec.nodeType()))
        .map(spec -> DynamicStopMatcher.matches(NodeId.of(depot), spec))
        .orElse(false);
  }

  private static String stripWeight(String raw) {
    String value = raw == null ? "" : raw.trim();
    int marker = value.lastIndexOf('*');
    if (marker <= 0 || marker == value.length() - 1) {
      return value;
    }
    String weight = value.substring(marker + 1).trim();
    if (weight.chars().allMatch(Character::isDigit)) {
      return value.substring(0, marker).trim();
    }
    return value;
  }
}
