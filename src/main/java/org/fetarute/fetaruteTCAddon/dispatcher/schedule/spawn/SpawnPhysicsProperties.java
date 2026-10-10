package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.standard.type.ChunkLoadOptions;
import com.bergerkiller.bukkit.tc.properties.standard.type.CollisionOptions;
import com.bergerkiller.bukkit.tc.properties.standard.type.SlowdownMode;

/**
 * 调度出的车（车库出车与区间生成）一律写上的物理属性：关摩擦、关重力、关碰撞，区块常驻加载用最小范围。
 *
 * <ul>
 *   <li>摩擦与重力：车速全由调度控车给，坡道上不会自己溜车，静止时也不会被慢慢减速。
 *   <li>碰撞：与玩家、生物、其他列车、方块都不碰——两车接近只靠闭塞隔开，不会因为一碰就联挂成一列。
 *   <li>区块常驻加载用 {@link ChunkLoadOptions.Mode#MINIMAL}：车不会被 TrainCarts
 *       卸载冻结（占用模型假设受管列车一直在模拟），又只加载车身所在的区块。
 * </ul>
 *
 * <p>物理编组此刻已经存在：按出车事务约定，这里的任何失败都不得冒泡，只返回是否全部写上。
 */
public final class SpawnPhysicsProperties {

  private SpawnPhysicsProperties() {}

  /**
   * 写上出车物理属性。
   *
   * @param properties 新生成列车的属性
   * @return 全部写上时为 true
   */
  public static boolean apply(TrainProperties properties) {
    if (properties == null) {
      return false;
    }
    boolean ok = true;
    try {
      properties.setSlowingDown(SlowdownMode.FRICTION, false);
      properties.setSlowingDown(SlowdownMode.GRAVITY, false);
    } catch (RuntimeException | LinkageError ex) {
      ok = false;
    }
    try {
      properties.setCollision(CollisionOptions.CANCEL);
    } catch (RuntimeException | LinkageError ex) {
      ok = false;
    }
    try {
      ChunkLoadOptions current = properties.getChunkLoadOptions();
      ChunkLoadOptions minimal =
          current == null
              ? ChunkLoadOptions.of(ChunkLoadOptions.Mode.MINIMAL, 2)
              : current.withMode(ChunkLoadOptions.Mode.MINIMAL);
      properties.setChunkLoadOptions(minimal);
    } catch (RuntimeException | LinkageError ex) {
      ok = false;
    }
    return ok;
  }
}
