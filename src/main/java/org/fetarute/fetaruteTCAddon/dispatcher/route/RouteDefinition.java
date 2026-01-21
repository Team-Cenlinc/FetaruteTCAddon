package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** 描述某条线路所遵循的节点序列，列车仅需依次把 destination 设置为下一节点。 */
public record RouteDefinition(
    RouteId id,
    List<NodeId> waypoints,
    Optional<RouteMetadata> metadata,
    RouteLifecycleMode lifecycleMode) {

  private static final Pattern ACTION_PREFIX_PATTERN =
      Pattern.compile("^(CHANGE|DYNAMIC|ACTION|CRET|DSTY)\\b", Pattern.CASE_INSENSITIVE);

  public RouteDefinition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(waypoints, "waypoints");
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(lifecycleMode, "lifecycleMode");
    waypoints = List.copyOf(waypoints);
    if (waypoints.isEmpty()) {
      throw new IllegalArgumentException("线路至少要包含一个节点");
    }
  }

  public RouteDefinition(RouteId id, List<NodeId> waypoints, Optional<RouteMetadata> metadata) {
    this(id, waypoints, metadata, RouteLifecycleMode.DESTROY_AFTER_TERM);
  }

  public static RouteDefinition of(RouteId id, List<NodeId> waypoints, RouteMetadata metadata) {
    return new RouteDefinition(
        id, waypoints, Optional.ofNullable(metadata), RouteLifecycleMode.DESTROY_AFTER_TERM);
  }

  /**
   * 解析并推导线路的生命周期模式。
   *
   * <p>规则：若 TERM 后存在 DSTY 指令，则为 DESTROY_AFTER_TERM；否则为 REUSE_AT_TERM。
   */
  public static RouteLifecycleMode resolveMode(List<RouteStop> stops) {
    if (stops == null || stops.isEmpty()) {
      return RouteLifecycleMode.DESTROY_AFTER_TERM;
    }
    int termIndex = -1;
    int dstyIndex = -1;

    for (int i = 0; i < stops.size(); i++) {
      RouteStop stop = stops.get(i);
      if (stop.passType() == RouteStopPassType.TERMINATE) {
        termIndex = i;
      }
      if (hasDsty(stop)) {
        dstyIndex = i;
      }
    }

    // 若没有 TERM，或 TERM 后有 DSTY，则视为销毁模式。
    if (termIndex == -1 || (dstyIndex != -1 && dstyIndex > termIndex)) {
      return RouteLifecycleMode.DESTROY_AFTER_TERM;
    }
    return RouteLifecycleMode.REUSE_AT_TERM;
  }

  private static boolean hasDsty(RouteStop stop) {
    return stop.notes()
        .map(
            n -> {
              for (String line : n.split("\n")) {
                if (ACTION_PREFIX_PATTERN.matcher(line.trim()).find()
                    && line.trim().toUpperCase(java.util.Locale.ROOT).startsWith("DSTY")) {
                  return true;
                }
              }
              return false;
            })
        .orElse(false);
  }
}
