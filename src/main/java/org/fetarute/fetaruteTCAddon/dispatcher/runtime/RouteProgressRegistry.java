package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteProgress;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 运行时 RouteProgress 缓存：按列车名追踪 routeId 与当前索引。
 *
 * <p>RouteProgress 常驻内存以获得更高更新频率；同时把 index 写回 TrainProperties tag，便于重启恢复。
 */
public final class RouteProgressRegistry {

  public static final String TAG_ROUTE_ID = "FTA_ROUTE_ID";
  public static final String TAG_OPERATOR_CODE = "FTA_OPERATOR_CODE";
  public static final String TAG_LINE_CODE = "FTA_LINE_CODE";
  public static final String TAG_ROUTE_CODE = "FTA_ROUTE_CODE";
  public static final String TAG_ROUTE_INDEX = "FTA_ROUTE_INDEX";
  public static final String TAG_ROUTE_UPDATED_AT = "FTA_ROUTE_UPDATED_AT";

  private final ConcurrentMap<String, RouteProgressEntry> entries = new ConcurrentHashMap<>();

  public Optional<RouteProgressEntry> get(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(entries.get(trainName));
  }

  /**
   * 根据 tags 初始化列车进度。
   *
   * <p>当 tag 缺失时回退为 0。
   */
  public RouteProgressEntry initFromTags(
      String trainName, TrainProperties properties, RouteDefinition route) {
    Objects.requireNonNull(route, "route");
    int index = TrainTagHelper.readIntTag(properties, TAG_ROUTE_INDEX).orElse(0);
    return upsert(trainName, parseRouteId(properties).orElse(null), route, index, Instant.now());
  }

  /**
   * 推进进度并写回 tag。
   *
   * <p>currentIndex 为“已抵达节点索引”。
   */
  public RouteProgressEntry advance(
      String trainName,
      UUID routeUuid,
      RouteDefinition route,
      int currentIndex,
      TrainProperties properties,
      Instant now) {
    RouteProgressEntry entry = upsert(trainName, routeUuid, route, currentIndex, now);
    TrainTagHelper.writeTag(properties, TAG_ROUTE_INDEX, String.valueOf(currentIndex));
    TrainTagHelper.writeTag(properties, TAG_ROUTE_UPDATED_AT, String.valueOf(now.toEpochMilli()));
    return entry;
  }

  /** 更新当前信号许可状态，用于运行时控车与 UI 反馈。 */
  public void updateSignal(String trainName, SignalAspect aspect, Instant now) {
    if (trainName == null || trainName.isBlank() || aspect == null) {
      return;
    }
    entries.computeIfPresent(
        trainName,
        (key, entry) ->
            new RouteProgressEntry(
                entry.trainName(),
                entry.routeUuid(),
                entry.routeId(),
                entry.currentIndex(),
                entry.nextTarget(),
                aspect,
                now));
  }

  public void remove(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    entries.remove(trainName);
  }

  public Map<String, RouteProgressEntry> snapshot() {
    return Map.copyOf(entries);
  }

  private RouteProgressEntry upsert(
      String trainName, UUID routeUuid, RouteDefinition route, int currentIndex, Instant now) {
    if (trainName == null || trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
    int boundedIndex = Math.max(0, Math.min(currentIndex, route.waypoints().size() - 1));
    Optional<NodeId> next =
        boundedIndex + 1 < route.waypoints().size()
            ? Optional.of(route.waypoints().get(boundedIndex + 1))
            : Optional.empty();
    RouteId routeId = route.id();
    RouteProgressEntry entry =
        new RouteProgressEntry(
            trainName, routeUuid, routeId, boundedIndex, next, SignalAspect.PROCEED, now);
    entries.put(trainName, entry);
    return entry;
  }

  private Optional<UUID> parseRouteId(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, TAG_ROUTE_ID)
        .flatMap(RouteProgressRegistry::parseUuid);
  }

  private static Optional<UUID> parseUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  /**
   * 运行时快照，供调度/控车读取。
   *
   * <p>实现 {@link TrainRuntimeState} 以兼容占用请求构建。
   */
  public record RouteProgressEntry(
      String trainName,
      UUID routeUuid,
      RouteId routeId,
      int currentIndex,
      Optional<NodeId> nextTarget,
      SignalAspect lastSignal,
      Instant lastUpdatedAt)
      implements TrainRuntimeState {

    public RouteProgressEntry {
      Objects.requireNonNull(trainName, "trainName");
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(nextTarget, "nextTarget");
      Objects.requireNonNull(lastSignal, "lastSignal");
      Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt");
    }

    @Override
    public RouteProgress routeProgress() {
      return new RouteProgressSnapshot(routeId, currentIndex, nextTarget);
    }

    @Override
    public Optional<NodeId> occupiedNode() {
      return Optional.empty();
    }

    @Override
    public Optional<java.time.Instant> estimatedArrivalTime() {
      return Optional.empty();
    }
  }

  private record RouteProgressSnapshot(
      RouteId routeId, int currentIndex, Optional<NodeId> nextTarget) implements RouteProgress {}
}
