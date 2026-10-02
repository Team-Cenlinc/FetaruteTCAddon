package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 出库点附近的离线（已卸载）编组探测的纯判定部分。
 *
 * <p>TrainCarts 的 {@code SpawnLocationList#isOccupied} 只看已加载的车。被卸载的车躺在离线存储里，位置只到区块粒度；
 * 生成点附近若还有离线编组，新车就可能与它叠放，苏醒时同坐标复原并被联挂。TrainCarts 对象的适配留在生成器里，本类只处理区块几何，便于单测。
 */
final class OfflineSpawnFootprint {

  private OfflineSpawnFootprint() {}

  /** 离线编组的区块视图：名字与其成员所在的区块。 */
  record OfflineGroupView(String name, Set<Long> chunkKeys) {}

  /** 区块坐标打包成单个 long；高低 32 位分别放 x、z，负数不冲突。 */
  static long chunkKey(int chunkX, int chunkZ) {
    return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
  }

  /**
   * 生成点所在区块及其外扩 {@code ring} 圈的区块集合。
   *
   * @param blockXZ 各生成点的方块坐标 {@code [x, z]}
   */
  static Set<Long> chunksAround(List<int[]> blockXZ, int ring) {
    Set<Long> chunks = new HashSet<>();
    for (int[] xz : blockXZ) {
      int chunkX = xz[0] >> 4;
      int chunkZ = xz[1] >> 4;
      for (int dx = -ring; dx <= ring; dx++) {
        for (int dz = -ring; dz <= ring; dz++) {
          chunks.add(chunkKey(chunkX + dx, chunkZ + dz));
        }
      }
    }
    return chunks;
  }

  /** 在生成点区块集合内有成员的离线编组名（按输入顺序，去重）。 */
  static List<String> groupsIn(Collection<OfflineGroupView> groups, Set<Long> footprintChunks) {
    Set<String> names = new LinkedHashSet<>();
    for (OfflineGroupView group : groups) {
      for (Long chunk : group.chunkKeys()) {
        if (footprintChunks.contains(chunk)) {
          names.add(group.name());
          break;
        }
      }
    }
    return new ArrayList<>(names);
  }
}
