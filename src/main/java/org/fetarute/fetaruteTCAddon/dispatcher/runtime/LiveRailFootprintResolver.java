package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 将列车各车厢的实时轨道方块映射为稀疏物理联锁区资源。
 *
 * <p>Node/Edge、switcher 与 single 资源由规范 Movement Plan 和停止态逻辑窗口负责；本解析器只补充图上不相邻却物理相交的 {@code
 * interlocking:*} 资源。普通轨道方块未命中任何 Zone 是完整 clear，而 catalog 或完整车体证据不可用时必须 fail-retain。
 */
public final class LiveRailFootprintResolver {

  private LiveRailFootprintResolver() {}

  /**
   * 解析当前现场足迹。
   *
   * @param graph 与现场世界对应的不可变图快照
   * @param liveCells 各有效车厢当前 rail block 坐标
   * @return 完整资源证据或包含未匹配坐标的失败结果
   */
  public static Resolution resolve(RailGraph graph, Collection<RailFootprintCell> liveCells) {
    if (graph == null || liveCells == null || liveCells.isEmpty()) {
      return Resolution.incomplete("live-footprint-missing");
    }
    Set<RailFootprintCell> expectedCells = new TreeSet<>();
    for (RailFootprintCell cell : liveCells) {
      if (cell == null) {
        return Resolution.incomplete("live-footprint-null-cell");
      }
      expectedCells.add(cell);
    }
    if (expectedCells.isEmpty()) {
      return Resolution.incomplete("live-footprint-empty");
    }

    if (!(graph instanceof RailGraphInterlockingSupport support)) {
      return Resolution.incomplete("interlocking-catalog-unavailable");
    }
    RailInterlockingState.LiveZoneObservation observation =
        support.interlockingState().observeLiveCells(expectedCells);
    if (!observation.complete()) {
      return Resolution.incomplete("interlocking-catalog-incomplete");
    }

    Set<OccupancyResource> resources = new LinkedHashSet<>();
    observation.occupiedZoneKeys().stream()
        .map(OccupancyResource::forConflict)
        .forEach(resources::add);
    return Resolution.complete(resources, observation.occupiedZoneKeys());
  }

  /**
   * 一次现场映射结果。
   *
   * @param complete catalog 与本轮完整车体证据是否可用于安全收缩物理 claim
   * @param resources 车体实际覆盖的稀疏联锁资源
   * @param occupiedZoneKeys 车体实际覆盖的 Zone key
   * @param reason 不完整原因；完整结果固定为 {@code complete}
   */
  public record Resolution(
      boolean complete,
      Set<OccupancyResource> resources,
      Set<String> occupiedZoneKeys,
      String reason) {

    public Resolution {
      Objects.requireNonNull(resources, "resources");
      Objects.requireNonNull(occupiedZoneKeys, "occupiedZoneKeys");
      resources = Set.copyOf(resources);
      occupiedZoneKeys = Set.copyOf(occupiedZoneKeys);
      reason = reason == null || reason.isBlank() ? "unknown" : reason.trim();
      if (!complete && (!resources.isEmpty() || !occupiedZoneKeys.isEmpty())) {
        throw new IllegalArgumentException("不完整现场观察不能携带可用于释放的联锁结论");
      }
    }

    private static Resolution complete(
        Set<OccupancyResource> resources, Set<String> occupiedZoneKeys) {
      return new Resolution(true, resources, occupiedZoneKeys, "complete");
    }

    private static Resolution incomplete(String reason) {
      return new Resolution(false, Set.of(), Set.of(), reason);
    }
  }
}
