package org.fetarute.fetaruteTCAddon.drive.hud;

import com.bergerkiller.bukkit.common.math.Quaternion;
import com.bergerkiller.bukkit.common.utils.EntityUtil;
import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.bukkit.common.wrappers.BlockData;
import com.bergerkiller.bukkit.common.wrappers.Brightness;
import com.bergerkiller.bukkit.common.wrappers.DataWatcher;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayOutEntityDestroyHandle;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayOutEntityMetadataHandle;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayOutEntityTeleportHandle;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayOutSpawnEntityHandle;
import com.bergerkiller.generated.net.minecraft.world.entity.DisplayHandle;
import com.bergerkiller.generated.net.minecraft.world.entity.EntityHandle;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * 只存在于一名玩家客户端的方块展示实体：生成、移动、改外观、移除都只发数据包给这名玩家，服务器上没有这个实体（不存档、不参与实体运算、其他玩家收不到）。
 *
 * <p>实体编号取服务器的实体计数，不会与真实实体冲突。数据包经 BKCommonLib 发送（TrainCarts 的虚拟展示实体也是这样做的）。只在服务器主线程使用。
 */
final class ClientBlockDisplay {

  /** 位置变化小于这个距离（格）不发移动包。 */
  private static final double MOVE_EPSILON_SQUARED = 0.05 * 0.05;

  private final int entityId = EntityUtil.getUniqueEntityId();
  private final UUID entityUuid = UUID.randomUUID();
  private final DataWatcher metadata = new DataWatcher();
  private final Vector scale;

  /** 已在哪个世界发给客户端；没有发过或已移除时为 {@code null}。 */
  private UUID spawnedWorld;

  private Vector sentPosition;

  /**
   * @param scale 方块的缩放（格）：宽、高、深
   * @param viewRange 渲染距离倍率（1 为 64 格）
   * @param moveTicks 移动时客户端插值的 tick 数
   */
  ClientBlockDisplay(Vector scale, float viewRange, int moveTicks) {
    this.scale = scale.clone();
    metadata.set(DisplayHandle.DATA_SCALE, this.scale.clone());
    metadata.set(DisplayHandle.DATA_TRANSLATION, new Vector());
    metadata.set(DisplayHandle.DATA_LEFT_ROTATION, new Quaternion());
    metadata.set(DisplayHandle.DATA_VIEW_RANGE, viewRange);
    metadata.set(DisplayHandle.DATA_BRIGHTNESS_OVERRIDE, Brightness.blockAndSkyLight(15, 15));
    metadata.set(DisplayHandle.DATA_POS_ROT_INTERPOLATION_DURATION, moveTicks);
    metadata.setFlag(EntityHandle.DATA_FLAGS, EntityHandle.DATA_FLAG_GLOWING, true);
  }

  /** 设置外观：方块与发光轮廓颜色（RGB）。下次 {@link #sync} 时发出。 */
  void setAppearance(BlockData block, int glowRgb) {
    metadata.set(DisplayHandle.BlockDisplayHandle.DATA_BLOCK_STATE, block);
    metadata.set(DisplayHandle.DATA_GLOW_COLOR_OVERRIDE, glowRgb);
  }

  /** 设置朝向：方块的 +Z 轴指向 {@code forward}（水平），并让方块以实体位置为底面中心。下次 {@link #sync} 时发出。 */
  void setForward(Vector forward) {
    Quaternion rotation = Quaternion.fromLookDirection(forward, new Vector(0.0, 1.0, 0.0));
    Vector translation = new Vector(-scale.getX() / 2.0, 0.0, -scale.getZ() / 2.0);
    rotation.transformPoint(translation);
    metadata.set(DisplayHandle.DATA_LEFT_ROTATION, rotation);
    metadata.set(DisplayHandle.DATA_TRANSLATION, translation);
  }

  /** 把当前位置与外观同步给玩家：还没发过或换了世界时整个生成，否则只发移动与变化的外观。 */
  void sync(Player player, World world, Vector position) {
    if (spawnedWorld != null && !spawnedWorld.equals(world.getUID())) {
      destroy(player);
    }
    if (spawnedWorld == null) {
      PacketPlayOutSpawnEntityHandle spawn = PacketPlayOutSpawnEntityHandle.createNew();
      spawn.setEntityId(entityId);
      spawn.setEntityUUID(entityUuid);
      spawn.setEntityType(EntityType.BLOCK_DISPLAY);
      spawn.setPosX(position.getX());
      spawn.setPosY(position.getY());
      spawn.setPosZ(position.getZ());
      spawn.setMotX(0.0);
      spawn.setMotY(0.0);
      spawn.setMotZ(0.0);
      spawn.setYaw(0.0f);
      spawn.setPitch(0.0f);
      PacketUtil.sendPacket(player, spawn);
      PacketUtil.sendPacket(
          player, PacketPlayOutEntityMetadataHandle.createNew(entityId, metadata, true));
      // 生成包已带上全部外观：清掉变更标记，免得下一次同步又重发一遍。
      metadata.packChanges();
      spawnedWorld = world.getUID();
      sentPosition = position.clone();
      return;
    }
    if (sentPosition.distanceSquared(position) > MOVE_EPSILON_SQUARED) {
      PacketUtil.sendPacket(
          player,
          PacketPlayOutEntityTeleportHandle.createNew(
              entityId, position.getX(), position.getY(), position.getZ(), 0.0f, 0.0f, false));
      sentPosition = position.clone();
    }
    if (metadata.isChanged()) {
      PacketUtil.sendPacket(
          player, PacketPlayOutEntityMetadataHandle.createNew(entityId, metadata, false));
    }
  }

  /** 让玩家的客户端移除它；没有发过时什么也不做。玩家已离线时传 {@code null}，只清记录。 */
  void destroy(Player player) {
    if (spawnedWorld != null && player != null && player.isOnline()) {
      PacketUtil.sendPacket(player, PacketPlayOutEntityDestroyHandle.createNewSingle(entityId));
    }
    spawnedWorld = null;
    sentPosition = null;
  }
}
