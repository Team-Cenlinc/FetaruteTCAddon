package org.fetarute.fetaruteTCAddon.drive.seat;

import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.attachments.control.CartAttachmentSeat;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.MinecartMemberStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * 与 TrainCarts 座位系统的适配：识别玩家当前所坐的座位、按绑定找回座位并让玩家重新入座。
 *
 * <p>所有方法只能在服务器主线程调用。
 */
public final class SeatLocator {

  /** 同一世界里离座的玩家离列车多近（方块）仍会被送回座位。 */
  private static final double RESEAT_RANGE = 16.0;

  private static final double RESEAT_RANGE_SQUARED = RESEAT_RANGE * RESEAT_RANGE;

  private SeatLocator() {}

  /**
   * 玩家在 TrainCarts 座位里的眼睛位置（第一人称视角的位置；取不到时用座位位置）。
   *
   * <p>服务器上的乘客位置跟着矿车实体（车厢中心），座位附件可能装在车厢前部，这里给的是玩家实际看出去的位置。
   *
   * @return 玩家没有坐在 TrainCarts 座位里时为空
   */
  public static Optional<Vector> seatEyePosition(Player player) {
    Entity vehicle = player.getVehicle();
    MinecartMember<?> member = vehicle == null ? null : MinecartMemberStore.getFromEntity(vehicle);
    if (member == null) {
      return Optional.empty();
    }
    CartAttachmentSeat seat = member.getAttachments().findSeatOfExistingPassenger(player);
    if (seat == null) {
      return Optional.empty();
    }
    Location eye = seat.getFirstPersonEyeLocation();
    return Optional.of(eye != null ? eye.toVector() : seat.getPosition(player).toVector());
  }

  /**
   * 识别玩家当前所坐的 TrainCarts 座位。
   *
   * @return 座位绑定；玩家没有乘坐 TrainCarts 列车，或所坐车厢没有座位时为空
   */
  public static Optional<SeatBinding> locate(Player player) {
    Entity vehicle = player.getVehicle();
    if (vehicle == null) {
      return Optional.empty();
    }
    MinecartMember<?> member = MinecartMemberStore.getFromEntity(vehicle);
    if (member == null) {
      return Optional.empty();
    }
    MinecartGroup group = member.getGroup();
    if (group == null || group.getProperties() == null) {
      return Optional.empty();
    }
    CartAttachmentSeat seat = member.getAttachments().findSeatOfExistingPassenger(player);
    if (seat == null) {
      return Optional.empty();
    }
    int memberIndex = group.indexOf(member);
    int seatIndex = seatsOf(member).indexOf(seat);
    if (memberIndex < 0 || seatIndex < 0) {
      return Optional.empty();
    }
    String trainName = group.getProperties().getTrainName();
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new SeatBinding(trainName, memberIndex, seatIndex));
  }

  /** 按列车名找到当前的编组；跨世界传送后编组对象会重建，所以每次都按名字查。 */
  public static Optional<MinecartGroup> findGroup(String trainName) {
    for (MinecartGroup group : MinecartGroupStore.getGroups()) {
      if (group != null
          && group.isValid()
          && group.getProperties() != null
          && trainName.equals(group.getProperties().getTrainName())) {
        return Optional.of(group);
      }
    }
    return Optional.empty();
  }

  /**
   * 让玩家重新坐回绑定的座位（必要时由 TrainCarts 先把玩家传送到座位旁，含跨世界）。
   *
   * @return 是否已坐回；座位被其他实体占用、找不到座位或入座被拒绝时为 false
   */
  public static boolean reseat(Player player, MinecartGroup group, SeatBinding binding) {
    Optional<CartAttachmentSeat> seat = resolveSeat(group, binding);
    if (seat.isEmpty()) {
      return false;
    }
    return seat.get().enter(player);
  }

  /** 离座的玩家是否应当被送回座位：跨世界传送把玩家落在原世界，或玩家仍在车旁（被挤出座位）时送回； 玩家在同一世界里走远了（自己传送离开、被管理员带走）就不再强拉。 */
  public static boolean canReseat(Player player, MinecartGroup group) {
    MinecartMember<?> head = group.head();
    if (head == null) {
      return false;
    }
    org.bukkit.Location trainLocation = head.getEntity().getLocation();
    if (trainLocation.getWorld() == null || !trainLocation.getWorld().equals(player.getWorld())) {
      return true;
    }
    return trainLocation.distanceSquared(player.getLocation()) <= RESEAT_RANGE_SQUARED;
  }

  /** 绑定的座位存在、且空着或坐着的就是这名玩家。 */
  public static boolean seatAvailable(MinecartGroup group, SeatBinding binding, Player player) {
    return resolveSeat(group, binding)
        .map(seat -> seat.getEntity() == null || seat.getEntity() == player)
        .orElse(false);
  }

  /** 绑定的座位是否仍然存在。 */
  public static boolean seatExists(MinecartGroup group, SeatBinding binding) {
    return resolveSeat(group, binding).isPresent();
  }

  private static Optional<CartAttachmentSeat> resolveSeat(
      MinecartGroup group, SeatBinding binding) {
    if (group == null || !group.isValid() || binding.memberIndex() >= group.size()) {
      return Optional.empty();
    }
    MinecartMember<?> member = group.get(binding.memberIndex());
    if (member == null) {
      return Optional.empty();
    }
    List<CartAttachmentSeat> seats = seatsOf(member);
    if (binding.seatIndex() >= seats.size()) {
      return Optional.empty();
    }
    return Optional.of(seats.get(binding.seatIndex()));
  }

  /**
   * 读出列车上哪些座位被标记为驾驶座：座位附件的名字（TrainCarts 附件配置的 {@code names}，附件编辑器里可设）在名单里即是。
   *
   * @param cabNames 驾驶座名单（已转小写）；为空时不读标记，按车厢位置认定
   */
  public static CabSeats cabSeats(MinecartGroup group, Collection<String> cabNames) {
    int members = group == null ? 0 : group.size();
    if (members == 0 || cabNames == null || cabNames.isEmpty()) {
      return CabSeats.unmarked(members);
    }
    List<List<Integer>> marked = new ArrayList<>(members);
    for (MinecartMember<?> member : group) {
      List<Integer> seats = new ArrayList<>();
      if (member != null) {
        List<CartAttachmentSeat> all = seatsOf(member);
        for (int i = 0; i < all.size(); i++) {
          if (CabSeats.nameMatches(all.get(i).getNames(), cabNames)) {
            seats.add(i);
          }
        }
      }
      marked.add(seats);
    }
    return CabSeats.of(marked);
  }

  /** 一节车厢的全部座位，按模型里的出现顺序排列。 */
  private static List<CartAttachmentSeat> seatsOf(MinecartMember<?> member) {
    List<CartAttachmentSeat> seats = new ArrayList<>();
    for (Attachment attachment : member.getAttachments().getAllAttachments()) {
      if (attachment instanceof CartAttachmentSeat seat) {
        seats.add(seat);
      }
    }
    return seats;
  }
}
