package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 默认 Depot 仲裁器。
 *
 * <p>排序规则保持确定性：plannedDeparture 更早优先、priority 更高优先、再按 sequence 和 route code 稳定排序。同一个 depot key
 * 在一次仲裁中只允许一个票据进入执行路径；其余票据延迟重试但不增加 attempts。
 */
public final class DepotSpawnScheduler implements DepotDispatchCoordinator {

  private static final String BACKOFF_REASON = "depot-backoff";

  private final Duration backoff;
  private final Map<String, Instant> blockedUntilByDepot = new HashMap<>();
  private final Map<String, UUID> lastLineByDepot = new HashMap<>();

  public DepotSpawnScheduler(Duration backoff) {
    this.backoff =
        backoff == null || backoff.isZero() || backoff.isNegative()
            ? Duration.ofSeconds(1)
            : backoff;
  }

  @Override
  public DispatchBatch coordinate(List<SpawnTicket> tickets, Instant now) {
    if (tickets == null || tickets.isEmpty()) {
      return new DispatchBatch(List.of(), List.of());
    }
    Instant current = now == null ? Instant.now() : now;
    List<SpawnTicket> sorted =
        tickets.stream().filter(ticket -> ticket != null).sorted(ticketComparator()).toList();
    List<SpawnTicket> ready = new ArrayList<>();
    List<SpawnTicket> deferred = new ArrayList<>();
    Map<String, List<SpawnTicket>> byDepot = new java.util.LinkedHashMap<>();
    Set<String> deferredBlockedDepots = new HashSet<>();
    for (SpawnTicket ticket : sorted) {
      String depotKey = depotKey(ticket);
      if (!isDepotDispatchKey(depotKey)) {
        ready.add(ticket);
        continue;
      }
      Instant blockedUntil = blockedUntilByDepot.get(depotKey);
      if (blockedUntil != null && current.isBefore(blockedUntil)) {
        deferred.add(ticket.delayedUntil(blockedUntil, BACKOFF_REASON + ":" + depotKey));
        deferredBlockedDepots.add(depotKey);
        continue;
      }
      byDepot.computeIfAbsent(depotKey, ignored -> new ArrayList<>()).add(ticket);
    }
    for (Map.Entry<String, List<SpawnTicket>> entry : byDepot.entrySet()) {
      String depotKey = entry.getKey();
      if (deferredBlockedDepots.contains(depotKey)) {
        continue;
      }
      List<SpawnTicket> candidates = entry.getValue();
      if (candidates == null || candidates.isEmpty()) {
        continue;
      }
      SpawnTicket selected = selectTicketForDepot(depotKey, candidates, current);
      ready.add(selected);
      lastLineByDepot.put(depotKey, selected.service().lineId());
      for (SpawnTicket candidate : candidates) {
        if (candidate == null || Objects.equals(candidate.id(), selected.id())) {
          continue;
        }
        deferred.add(
            candidate.delayedUntil(current.plus(backoff), BACKOFF_REASON + ":" + depotKey));
      }
    }
    return new DispatchBatch(ready, deferred);
  }

  @Override
  public void recordOccupancyFailure(SpawnTicket ticket, Instant now) {
    if (ticket == null) {
      return;
    }
    String depotKey = depotKey(ticket);
    if (!isDepotDispatchKey(depotKey)) {
      return;
    }
    Instant current = now == null ? Instant.now() : now;
    blockedUntilByDepot.put(depotKey, current.plus(backoff));
  }

  @Override
  public Optional<Instant> backoffUntil(String depotNodeId, Instant now) {
    String depotKey = normalizeDepotKey(depotNodeId);
    if (!isDepotDispatchKey(depotKey)) {
      return Optional.empty();
    }
    Instant until = blockedUntilByDepot.get(depotKey);
    if (until == null) {
      return Optional.empty();
    }
    Instant current = now == null ? Instant.now() : now;
    if (!current.isBefore(until)) {
      blockedUntilByDepot.remove(depotKey, until);
      return Optional.empty();
    }
    return Optional.of(until);
  }

  private SpawnTicket selectTicketForDepot(
      String depotKey, List<SpawnTicket> candidates, Instant now) {
    if (candidates == null || candidates.isEmpty()) {
      throw new IllegalArgumentException("candidates 不能为空");
    }
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    List<SpawnTicket> sorted = candidates.stream().sorted(ticketComparator()).toList();
    UUID lastLine = lastLineByDepot.get(depotKey);
    if (lastLine == null) {
      return sorted.get(0);
    }
    Instant earliestDue = sorted.get(0).dueAt();
    Duration starvationWindow = backoff.multipliedBy(2L);
    for (SpawnTicket candidate : sorted) {
      if (candidate == null || candidate.service() == null) {
        continue;
      }
      if (lastLine.equals(candidate.service().lineId())) {
        continue;
      }
      if (candidate.dueAt().isAfter(earliestDue.plus(starvationWindow))) {
        continue;
      }
      return candidate;
    }
    return sorted.get(0);
  }

  private static Comparator<SpawnTicket> ticketComparator() {
    return Comparator.comparing(SpawnTicket::dueAt)
        .thenComparing(Comparator.comparingInt(SpawnTicket::priority).reversed())
        .thenComparingLong(SpawnTicket::sequenceNumber)
        .thenComparing(ticket -> ticket.service().operatorCode(), String.CASE_INSENSITIVE_ORDER)
        .thenComparing(ticket -> ticket.service().lineCode(), String.CASE_INSENSITIVE_ORDER)
        .thenComparing(ticket -> ticket.service().routeCode(), String.CASE_INSENSITIVE_ORDER)
        .thenComparing(ticket -> ticket.id().toString());
  }

  private static String depotKey(SpawnTicket ticket) {
    if (ticket == null || ticket.service() == null) {
      return "unknown";
    }
    String depot =
        ticket
            .selectedDepotNodeId()
            .filter(value -> !value.isBlank())
            .orElseGet(ticket.service()::depotNodeId);
    if (depot == null || depot.isBlank()) {
      return "unknown";
    }
    return normalizeDepotKey(depot);
  }

  private static String normalizeDepotKey(String depot) {
    if (depot == null || depot.isBlank()) {
      return "unknown";
    }
    return depot.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean isDepotDispatchKey(String key) {
    if (key == null || key.isBlank()) {
      return false;
    }
    String normalized = key.trim().toLowerCase(Locale.ROOT);
    if (normalized.startsWith("dynamic:")) {
      String rest = normalized.substring("dynamic:".length());
      String[] parts = rest.split(":", 4);
      return parts.length >= 2 && "d".equals(parts[1].trim());
    }
    String[] parts = normalized.split(":", 4);
    return parts.length >= 2 && "d".equals(parts[1].trim());
  }
}
