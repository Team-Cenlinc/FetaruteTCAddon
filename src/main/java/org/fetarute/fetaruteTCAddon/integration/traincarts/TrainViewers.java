package org.fetarute.fetaruteTCAddon.integration.traincarts;

import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.MinecartMemberStore;
import com.bergerkiller.bukkit.tc.controller.components.AttachmentControllerMember;
import java.util.Collection;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 列车附件观看者的就近判定，供开关门提示音与 Sequencer 门控共用。
 *
 * <p>只在 TrainCarts 维护的观看者（正在跟踪这节车的玩家）里找，不扫全服玩家；坐标用 Paper 的 {@link Player#getX()} 等读取，不分配新对象。
 */
public final class TrainViewers {

  private TrainViewers() {}

  /** 观看者中是否有人与 (x, y, z) 同世界且直线距离不超过 {@code radius}。 */
  public static boolean anyWithin(
      Collection<Player> viewers, World world, double x, double y, double z, double radius) {
    double limit = radius * radius;
    for (Player viewer : viewers) {
      if (viewer == null || viewer.getWorld() != world) {
        continue;
      }
      double dx = viewer.getX() - x;
      double dy = viewer.getY() - y;
      double dz = viewer.getZ() - z;
      if (dx * dx + dy * dy + dz * dz <= limit) {
        return true;
      }
    }
    return false;
  }

  /** 玩家是否坐在 {@code attachment} 所属列车的任意一节车上。 */
  public static boolean ridesTrainOf(Player player, Attachment attachment) {
    Entity vehicle = player.getVehicle();
    if (vehicle == null
        || !(attachment.getManager() instanceof AttachmentControllerMember controller)) {
      return false;
    }
    MinecartMember<?> ridden = MinecartMemberStore.getFromEntity(vehicle);
    return ridden != null && ridden.getGroup() == controller.getMember().getGroup();
  }
}
