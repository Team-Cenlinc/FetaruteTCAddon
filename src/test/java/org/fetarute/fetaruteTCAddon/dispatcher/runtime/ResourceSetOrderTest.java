package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.junit.jupiter.api.Test;

/**
 * 会按序释放、并入请求的资源集合必须按 {@link OccupancyResource#STABLE_ORDER} 排列。
 *
 * <p>{@code OccupancyResource} 的 hash 含枚举（身份 hash），装进 {@code HashSet}/{@code Set.copyOf} 后顺序随进程变。
 * 这里输入刻意用 {@code HashSet}：若实现退回保留来源顺序或 {@code Set.copyOf}，断言几乎必然失败。
 */
class ResourceSetOrderTest {

  /** 期望顺序：先按 kind 名（CONFLICT &lt; EDGE &lt; NODE），同 kind 再按 key。 */
  private static final List<OccupancyResource> EXPECTED =
      List.of(
          OccupancyResource.forConflict("single:section:A"),
          OccupancyResource.forConflict("switcher:SWITCHER:B1:E"),
          new OccupancyResource(ResourceKind.EDGE, "OP:S:B1:1~SWITCHER:B1:E"),
          new OccupancyResource(ResourceKind.EDGE, "OP:S:B1:2~SWITCHER:B1:E"),
          OccupancyResource.forNode(NodeId.of("A")),
          OccupancyResource.forNode(NodeId.of("B")),
          OccupancyResource.forNode(NodeId.of("C")));

  @Test
  void turnbackReleaseIsSortedByStableResourceOrder() {
    TurnbackFootprintGuardRegistry.Release release =
        new TurnbackFootprintGuardRegistry.Release(new HashSet<>(EXPECTED));

    assertEquals(EXPECTED, List.copyOf(release.resources()));
  }

  @Test
  void liveFootprintResolutionIsSortedByStableResourceOrder() {
    Set<String> zones = new HashSet<>(List.of("zone:c", "zone:a", "zone:d", "zone:b"));

    LiveRailFootprintResolver.Resolution resolution =
        new LiveRailFootprintResolver.Resolution(true, new HashSet<>(EXPECTED), zones, "complete");

    assertEquals(EXPECTED, List.copyOf(resolution.resources()));
    assertEquals(
        List.of("zone:a", "zone:b", "zone:c", "zone:d"),
        List.copyOf(resolution.occupiedZoneKeys()));
  }

  /** zone key 刻意取 hash 分散的字符串：连续的 {@code zone:a..e} 在 {@code Set.copyOf} 下常常恰好顺序或逆序，测不出东西。 */
  @Test
  void liveZoneObservationIsSortedByZoneKey() {
    List<String> sorted =
        List.of(
            "zone:0f3a91",
            "zone:2b77c0",
            "zone:48d1e2",
            "zone:6c05aa",
            "zone:9e2f13",
            "zone:b41d7e",
            "zone:d9a0c4",
            "zone:f7e385");
    List<String> scrambled = new java.util.ArrayList<>(sorted);
    java.util.Collections.reverse(scrambled);
    java.util.Collections.swap(scrambled, 1, 5);

    RailInterlockingState.LiveZoneObservation observation =
        new RailInterlockingState.LiveZoneObservation(
            true, new java.util.LinkedHashSet<>(scrambled));

    assertEquals(sorted, List.copyOf(observation.occupiedZoneKeys()));
  }
}
