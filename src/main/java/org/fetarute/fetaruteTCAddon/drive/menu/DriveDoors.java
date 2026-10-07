package org.fetarute.fetaruteTCAddon.drive.menu;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DoorCars;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationDoorController;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationDoorController.ManualDoor;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationDoorController.ManualDoorSide;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;

/**
 * 一次驾驶会话里左右车门的开关。复用 AutoStation 的门动画与提示音。
 *
 * <p>左右按驾驶员面朝的方向算：把驾驶员的左（右）手边换算成世界方位，再按门附件的位置选 {@code doorL} 或 {@code doorR}（与 AutoStation
 * 同一套判定，不假定模型里哪个是左）；门附件判定不出时才按驾驶室在车头还是车尾端回退。对侧门开着时直接取另一组动画。会话结束时由调用方关门。
 *
 * <p>开着门折返换端后驾驶员面朝的方向反了过来，原来左手边的门到了右手边：由 {@link #followCab} 把左右记录对调。
 */
public final class DriveDoors {

  /** 一次开关的结果。 */
  public enum Result {
    OPENED,
    CLOSED,
    /** 这辆车没有对应的门动画。 */
    UNAVAILABLE
  }

  /** 量不出关门动画时长时，按这么久（tick）算车门还在关。 */
  static final long DEFAULT_CLOSE_TICKS = 60L;

  private MinecartGroup group;
  private String lastSummary = "";
  private ManualDoor left;
  private ManualDoor right;

  /** 最近一次开关门时驾驶员面朝的方向（世界坐标）；左右记录按它算。 */
  private Vector facing;

  /**
   * 切换一侧车门：开着就关，关着就开。
   *
   * @param physicalLeft 是否为驾驶员的左边；否则为右边
   * @param chime AutoStation 提示音配置，可为 {@code null}
   * @param cars 开门时只开这几节车厢（停站时停车位置标写了 {@code door:}）；关门总是关开门时那几节
   */
  public Result toggle(
      MinecartGroup current,
      DriveSession session,
      boolean physicalLeft,
      ConfigManager.AutoStationSettings chime,
      DoorCars cars) {
    if (group != current) {
      // 编组对象重建（如跨世界）后旧句柄指向已失效的编组，门动画状态无从还原，只能清掉记录。
      forget(session);
      group = current;
    }
    Vector nowFacing = cabFacing(current, session);
    if (nowFacing != null) {
      facing = nowFacing;
    }
    ManualDoor existing = physicalLeft ? left : right;
    if (existing != null && existing.isOpen()) {
      existing.close();
      lastSummary = existing.summary();
      session.setDoorOpen(physicalLeft, false);
      long closeTicks = existing.closeDurationTicks();
      // 关门动画放完前车门还没真正关上：站台等动画结束才给发车信号。
      session.markDoorsClosing(
          Bukkit.getCurrentTick() + (closeTicks > 0L ? closeTicks : DEFAULT_CLOSE_TICKS));
      return Result.CLOSED;
    }
    ManualDoor other = physicalLeft ? right : left;
    ManualDoorSide side;
    if (other != null && other.isOpen()) {
      // 对侧门已开：这一侧必须是另一组动画，否则两个按钮会操纵同一扇门而状态错乱。
      side = new ManualDoorSide(!other.modelLeft(), "opposite-of-open-door");
    } else {
      side =
          AutoStationDoorController.resolveManualDoorSide(
              current, nowFacing, physicalLeft, physicalLeft == session.cabAtHead(current.size()));
    }
    ManualDoor door = AutoStationDoorController.manualDoor(current, side, chime, cars);
    lastSummary = door.summary();
    if (!door.open()) {
      return Result.UNAVAILABLE;
    }
    if (physicalLeft) {
      left = door;
    } else {
      right = door;
    }
    session.setDoorOpen(physicalLeft, true);
    return Result.OPENED;
  }

  /**
   * 站台已打开的一侧车门交给驾驶员（停站中途接管）：记成开着，不再播开门动画，之后按这一侧关门。
   *
   * @param physicalLeft 是否为驾驶员的左边；否则为右边
   * @param chime AutoStation 提示音配置，可为 {@code null}
   * @param cars 站台开门的车厢
   */
  public void adoptOpen(
      MinecartGroup current,
      DriveSession session,
      boolean physicalLeft,
      ConfigManager.AutoStationSettings chime,
      DoorCars cars) {
    if (group != current) {
      forget(session);
      group = current;
    }
    Vector nowFacing = cabFacing(current, session);
    if (nowFacing != null) {
      facing = nowFacing;
    }
    ManualDoorSide side =
        AutoStationDoorController.resolveManualDoorSide(
            current, nowFacing, physicalLeft, physicalLeft == session.cabAtHead(current.size()));
    ManualDoor door = AutoStationDoorController.manualDoor(current, side, chime, cars);
    door.markOpenedByStation();
    lastSummary = door.summary();
    if (physicalLeft) {
      left = door;
    } else {
      right = door;
    }
    session.setDoorOpen(physicalLeft, true);
  }

  /**
   * 驾驶员换到另一端驾驶室后跟着对调左右：面朝方向与上次开关门时相反（夹角超过 90°）时，原来的左门记为右门、右门记为左门。
   * 列车停着时面朝方向只会因换端而反过来（整列调头只翻转车厢序号，不改驾驶员实际朝向）。
   */
  public void followCab(MinecartGroup current, DriveSession session) {
    if (current != group || facing == null) {
      return;
    }
    Vector nowFacing = cabFacing(current, session);
    if (!reversed(facing, nowFacing)) {
      return;
    }
    facing = nowFacing;
    ManualDoor formerLeft = left;
    left = right;
    right = formerLeft;
    boolean leftOpen = session.isLeftDoorOpen();
    session.setDoorOpen(true, session.isRightDoorOpen());
    session.setDoorOpen(false, leftOpen);
  }

  /** 两个水平朝向是否相反（夹角超过 90°）；任一取不到时不算。 */
  static boolean reversed(Vector before, Vector after) {
    if (before == null || after == null) {
      return false;
    }
    return before.getX() * after.getX() + before.getZ() * after.getZ() < 0.0;
  }

  /** 关门动画还排在门附件的队里没轮到（前面有牌子排的动画在播）：车门其实还开着，把“车门关闭中”往后推，牵引继续封锁。每 tick 调用。 */
  public void holdClosingWhilePending(DriveSession session, long nowTick) {
    for (ManualDoor door : new ManualDoor[] {left, right}) {
      if (door != null && door.closePending()) {
        long closeTicks = door.closeDurationTicks();
        session.markDoorsClosing(nowTick + (closeTicks > 0L ? closeTicks : DEFAULT_CLOSE_TICKS));
      }
    }
  }

  /** 最近一次开关的那扇门的左右侧判定过程，仅用于诊断输出。 */
  public String lastSummary() {
    return lastSummary;
  }

  /**
   * 驾驶员面朝的水平方向（世界坐标）。
   *
   * <p>不用车厢模型的朝向：整列调头只翻转车厢序号，不转车厢模型，混用两者会让左右在每次调头后对调。这里先用驾驶室所在车厢前后两节的位置
   * 求出“指向车头”的方向（单节车取它的行进方向，调头时随之翻转），驾驶室在车头端就面朝车头，否则背向车头。
   *
   * @return 取不到方向时为 {@code null}
   */
  public static Vector cabFacing(MinecartGroup group, DriveSession session) {
    int size = group.size();
    int index = session.binding().memberIndex();
    if (index < 0 || index >= size) {
      return null;
    }
    Vector headward;
    if (size >= 2) {
      Vector towardHeadEnd = position(group.get(Math.max(0, index - 1)));
      Vector towardTailEnd = position(group.get(Math.min(size - 1, index + 1)));
      headward = towardHeadEnd.subtract(towardTailEnd);
    } else {
      BlockFace direction = group.get(index).getDirection();
      headward = direction == null ? null : direction.getDirection();
    }
    return facingFrom(headward, session.cabAtHead(size));
  }

  /**
   * 由“指向车头”的方向得到驾驶员面朝的方向。
   *
   * @param headward 指向车头的方向，可为 {@code null}
   * @param cabAtHead 驾驶室是否在车头端
   */
  static Vector facingFrom(Vector headward, boolean cabAtHead) {
    if (headward == null) {
      return null;
    }
    return cabAtHead ? headward.clone() : headward.clone().multiply(-1.0);
  }

  private static Vector position(MinecartMember<?> member) {
    return member.getEntity().getLocation().toVector();
  }

  /** 关上所有还开着的车门。会话结束时调用。 */
  public void closeAll(DriveSession session) {
    if (left != null && left.isOpen()) {
      left.close();
    }
    if (right != null && right.isOpen()) {
      right.close();
    }
    forget(session);
  }

  private void forget(DriveSession session) {
    left = null;
    right = null;
    facing = null;
    session.setDoorOpen(true, false);
    session.setDoorOpen(false, false);
  }
}
