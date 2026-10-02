package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.OfflineSpawnFootprint.OfflineGroupView;
import org.junit.jupiter.api.Test;

/**
 * TrainCarts 的 {@code SpawnLocationList#isOccupied} 只看已加载的车。被卸载的车在离线存储里，位置只到区块粒度；
 * 生成点附近若还有离线编组，新生成的车就可能与它叠放。
 */
class OfflineSpawnFootprintTest {

  @Test
  void footprintCoversTheChunkOfEachSpawnBlockPlusTheConfiguredRing() {
    // 出库口 x=518..548, z=996：区块 x=32..34, z=62
    Set<Long> footprint =
        OfflineSpawnFootprint.chunksAround(List.of(new int[] {518, 996}, new int[] {548, 996}), 0);

    assertEquals(
        Set.of(OfflineSpawnFootprint.chunkKey(32, 62), OfflineSpawnFootprint.chunkKey(34, 62)),
        footprint);
    assertEquals(9, OfflineSpawnFootprint.chunksAround(List.of(new int[] {518, 996}), 1).size());
  }

  @Test
  void offlineGroupSharingAChunkWithTheFootprintIsReported() {
    Set<Long> footprint = OfflineSpawnFootprint.chunksAround(List.of(new int[] {528, 996}), 0);
    OfflineGroupView ghost =
        new OfflineGroupView("SURC-DS-LW-8125", Set.of(OfflineSpawnFootprint.chunkKey(33, 62)));
    OfflineGroupView far =
        new OfflineGroupView("SURC-MT-LP-1", Set.of(OfflineSpawnFootprint.chunkKey(-40, 10)));

    assertEquals(
        List.of("SURC-DS-LW-8125"), OfflineSpawnFootprint.groupsIn(List.of(ghost, far), footprint));
  }

  @Test
  void nothingIsReportedWhenNoOfflineGroupIsNearby() {
    Set<Long> footprint = OfflineSpawnFootprint.chunksAround(List.of(new int[] {528, 996}), 1);
    OfflineGroupView far =
        new OfflineGroupView("SURC-MT-LP-1", Set.of(OfflineSpawnFootprint.chunkKey(-40, 10)));

    assertTrue(OfflineSpawnFootprint.groupsIn(List.of(far), footprint).isEmpty());
    assertTrue(OfflineSpawnFootprint.groupsIn(List.of(), footprint).isEmpty());
  }

  @Test
  void negativeChunkCoordinatesDoNotCollide() {
    assertTrue(OfflineSpawnFootprint.chunkKey(-1, 0) != OfflineSpawnFootprint.chunkKey(0, -1));
    assertTrue(OfflineSpawnFootprint.chunkKey(-1, -1) != OfflineSpawnFootprint.chunkKey(1, 1));
  }
}
