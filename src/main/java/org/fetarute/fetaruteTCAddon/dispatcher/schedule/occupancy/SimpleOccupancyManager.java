package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于内存 Map 的占用管理器，适合作为 MVP 版本。
 *
 * <p>占用释放由事件驱动触发：释放后即认为可重新判定，不依赖 releaseAt；headway 仅作为配置保留。
 */
public final class SimpleOccupancyManager implements OccupancyManager {

  private final HeadwayRule headwayRule;
  private final SignalAspectPolicy signalPolicy;
  private final Map<OccupancyResource, OccupancyClaim> claims = new LinkedHashMap<>();

  public SimpleOccupancyManager(HeadwayRule headwayRule, SignalAspectPolicy signalPolicy) {
    this.headwayRule = Objects.requireNonNull(headwayRule, "headwayRule");
    this.signalPolicy = signalPolicy != null ? signalPolicy : SignalAspectPolicy.defaultPolicy();
  }

  @Override
  public synchronized OccupancyDecision canEnter(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    Instant now = request.now();
    List<OccupancyClaim> blockers = new ArrayList<>();
    boolean allowed = true;
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      OccupancyClaim claim = claims.get(resource);
      if (claim == null) {
        continue;
      }
      if (claim.trainName().equalsIgnoreCase(request.trainName())) {
        continue;
      }
      blockers.add(claim);
      allowed = false;
    }
    SignalAspect signal = allowed ? signalPolicy.aspectForDelay(Duration.ZERO) : SignalAspect.STOP;
    return new OccupancyDecision(allowed, now, signal, List.copyOf(blockers));
  }

  @Override
  public synchronized OccupancyDecision acquire(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    OccupancyDecision decision = canEnter(request);
    if (!decision.allowed()) {
      return decision;
    }
    Instant now = request.now();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      Duration headway = headwayRule.headwayFor(request.routeId(), resource);
      OccupancyClaim existing = claims.get(resource);
      if (existing != null && existing.trainName().equalsIgnoreCase(request.trainName())) {
        Duration nextHeadway =
            existing.headway().compareTo(headway) >= 0 ? existing.headway() : headway;
        claims.put(
            resource,
            new OccupancyClaim(
                resource,
                existing.trainName(),
                request.routeId(),
                existing.acquiredAt(),
                nextHeadway));
        continue;
      }
      claims.put(
          resource,
          new OccupancyClaim(resource, request.trainName(), request.routeId(), now, headway));
    }
    return decision;
  }

  @Override
  public synchronized Optional<OccupancyClaim> getClaim(OccupancyResource resource) {
    if (resource == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(claims.get(resource));
  }

  @Override
  public synchronized List<OccupancyClaim> snapshotClaims() {
    return List.copyOf(claims.values());
  }

  @Override
  public synchronized int releaseByTrain(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return 0;
    }
    int removed = 0;
    Iterator<Map.Entry<OccupancyResource, OccupancyClaim>> iterator = claims.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<OccupancyResource, OccupancyClaim> entry = iterator.next();
      OccupancyClaim claim = entry.getValue();
      if (claim != null && claim.trainName().equalsIgnoreCase(trainName)) {
        iterator.remove();
        removed++;
      }
    }
    return removed;
  }

  @Override
  public synchronized boolean releaseResource(
      OccupancyResource resource, Optional<String> trainName) {
    if (resource == null) {
      return false;
    }
    OccupancyClaim claim = claims.get(resource);
    if (claim == null) {
      return false;
    }
    if (trainName != null && trainName.isPresent()) {
      String expected = trainName.get();
      if (!claim.trainName().equalsIgnoreCase(expected)) {
        return false;
      }
    }
    claims.remove(resource);
    return true;
  }

  // 事件反射式释放不需要 time-based 清理。
}
