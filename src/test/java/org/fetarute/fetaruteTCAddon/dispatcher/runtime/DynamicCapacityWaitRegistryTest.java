package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

/** 容量通知随运行窗口与列车 owner 生命周期清理的回归测试。 */
class DynamicCapacityWaitRegistryTest {

  private final DynamicCapacityWaitRegistry registry = new DynamicCapacityWaitRegistry();
  private final RouteId route = RouteId.of("op/mt/inbound");
  private final OccupancyResource platform = OccupancyResource.forNode(NodeId.of("OP:S:A:1"));
  private final OccupancyResource alternate = OccupancyResource.forNode(NodeId.of("OP:S:A:2"));

  @Test
  void targetSelectionRetiresOnlyTheMatchingCapacityWindow() {
    registry.register("MT-1", route, 12, 13, List.of(platform, alternate));
    registry.retainCurrentWindow("mt-1~a", route, 12);
    registry.targetResolved("MT-1", route, 12);
    assertEquals(List.of("MT-1"), registry.trainsWaitingFor(List.of(platform, alternate)));

    registry.targetResolved("MT-1", route, 13);

    assertTrue(registry.trainsWaitingFor(List.of(platform, alternate)).isEmpty());
  }

  @Test
  void advancingOrChangingRouteCannotKeepAnObsoleteWakeup() {
    registry.register("MT-1", route, 12, 13, List.of(platform));
    registry.retainCurrentWindow("MT-1", route, 13);
    assertTrue(registry.trainsWaitingFor(List.of(platform)).isEmpty());

    registry.register("MT-1", route, 12, 13, List.of(platform));
    registry.retainCurrentWindow("MT-1", RouteId.of("op/mt/outbound"), 12);
    assertTrue(registry.trainsWaitingFor(List.of(platform)).isEmpty());
  }

  @Test
  void ownerMigrationAndRemovalKeepOtherWaitersIntact() {
    registry.register("MT-1", route, 11, 13, List.of(platform, alternate));
    registry.register("MT-2", route, 12, 13, List.of(platform));
    registry.rename("MT-1", "MT-renamed");
    registry.register("MT-renamed", route, 11, 13, List.of(alternate));
    registry.retainCurrentWindow("MT-renamed", route, 11);

    assertEquals(List.of("MT-2"), registry.trainsWaitingFor(List.of(platform)));
    assertEquals(List.of("MT-renamed"), registry.trainsWaitingFor(List.of(alternate)));
    registry.remove("mt-renamed~a");
    assertTrue(registry.trainsWaitingFor(List.of(alternate)).isEmpty());
    assertEquals(List.of("MT-2"), registry.trainsWaitingFor(List.of(platform)));
  }
}
