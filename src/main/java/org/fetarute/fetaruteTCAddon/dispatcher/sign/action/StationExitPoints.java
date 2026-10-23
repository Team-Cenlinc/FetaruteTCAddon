package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.seat.MemberBeforeSeatExitEvent;
import com.bergerkiller.bukkit.tc.properties.CartProperties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

/**
 * 停站开门期间的下车点：乘客从开门的车厢下车时，落在座位往牌子写的下车方位（世界方位）偏出若干格的地方。
 *
 * <p>直接改 TrainCarts 下车事件里的落点，不改车厢的下车偏移属性：那个偏移是车厢自身坐标系下的值，TrainCarts 对翻转摆放的车厢还要再转 180°，
 * 按行车方向判左右换算过去，翻转过的车厢左右正好反；属性还随车厢存盘，停站中途停服会留在车上。落点只按世界方位算，与车厢怎么摆放无关。
 */
public final class StationExitPoints implements Listener {

  /**
   * 一节车此刻的下车方位。
   *
   * @param owner 登记它的停站（只有同一次停站能撤销，拆挂后两段各自停站时互不撤掉对方的）
   * @param face 下车一侧（世界方位）
   * @param distance 从座位偏出的格数
   */
  private record Exit(Object owner, BlockFace face, double distance) {}

  /** 车厢 UUID → 下车方位。停站的主线程写，下车事件在主线程读。 */
  private static final Map<UUID, Exit> EXITS = new ConcurrentHashMap<>();

  /**
   * 开门时登记：这节车的乘客下车时落到座位的 {@code face} 一侧 {@code distance} 格外。
   *
   * @return 登记了（方位不是水平方向、距离无效时不登记）
   */
  static boolean open(Object owner, UUID cart, BlockFace face, double distance) {
    if (owner == null || cart == null || horizontal(face) == null) {
      return false;
    }
    if (!Double.isFinite(distance) || distance <= 0.0) {
      return false;
    }
    EXITS.put(cart, new Exit(owner, face, distance));
    return true;
  }

  /** 发车或停站中止时撤销；只撤销 {@code owner} 自己登记的。 */
  static void close(Object owner, UUID cart) {
    if (owner == null || cart == null) {
      return;
    }
    EXITS.computeIfPresent(cart, (id, exit) -> exit.owner() == owner ? null : exit);
  }

  /** 全部撤销（插件停用时）。 */
  public static void clear() {
    EXITS.clear();
  }

  /**
   * 落点：座位的水平位置往 {@code face} 偏出 {@code distance} 格；高度与朝向沿用 TrainCarts 原本算出的落点（没有时用座位的）。纯计算。
   *
   * @param seat 座位位置
   * @param fallback TrainCarts 原本算出的落点；可为 null
   * @return 落点；座位位置或方位无效时为 null
   */
  static Location exitPoint(Location seat, Location fallback, BlockFace face, double distance) {
    Vector direction = horizontal(face);
    if (seat == null || direction == null) {
      return null;
    }
    Location base = fallback != null ? fallback : seat;
    Vector offset = direction.multiply(distance);
    return new Location(
        seat.getWorld(),
        seat.getX() + offset.getX(),
        base.getY(),
        seat.getZ() + offset.getZ(),
        base.getYaw(),
        base.getPitch());
  }

  /** 水平方位的单位向量；不是水平方向时为 null。 */
  private static Vector horizontal(BlockFace face) {
    if (face == null || face.getModY() != 0) {
      return null;
    }
    Vector vector = new Vector(face.getModX(), 0, face.getModZ());
    return vector.lengthSquared() == 0.0 ? null : vector.normalize();
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSeatExit(MemberBeforeSeatExitEvent event) {
    if (event.isSeatChange()) {
      return;
    }
    MinecartMember<?> member = event.getMember();
    CartProperties properties = member == null ? null : member.getProperties();
    Exit exit = properties == null ? null : EXITS.get(properties.getUUID());
    if (exit == null) {
      return;
    }
    Location point =
        exitPoint(event.getSeatPosition(), event.getExitPosition(), exit.face(), exit.distance());
    if (point != null) {
      // 乘客保持自己的视角
      event.setExitPosition(point, true);
    }
  }
}
