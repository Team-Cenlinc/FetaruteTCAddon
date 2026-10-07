package org.fetarute.fetaruteTCAddon.drive.seat;

import com.bergerkiller.bukkit.common.config.ConfigurationNode;
import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.attachments.config.AttachmentModel;
import com.bergerkiller.bukkit.tc.attachments.control.CartAttachmentSeat;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.MinecartMemberStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntPredicate;
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

  /**
   * 让玩家坐进这节车厢里离他最近的空座位。驾驶会话期间玩家的右键交互被拦下（保护背包），右键自己驾驶的列车时由这里代为入座。
   *
   * @return 是否已坐进去；这节车厢没有空座位或入座被拒绝时为 false
   */
  public static boolean enterNearestFreeSeat(Player player, MinecartMember<?> member) {
    return enterNearestFreeSeat(player, member, seatIndex -> true);
  }

  /**
   * 让玩家坐进这节车厢里离他最近、且符合条件的空座位。
   *
   * @param acceptSeat 按座位在这节车厢里的序号判断能不能坐（例如只坐驾驶座）
   * @return 是否已坐进去
   */
  public static boolean enterNearestFreeSeat(
      Player player, MinecartMember<?> member, IntPredicate acceptSeat) {
    return enterNearestFreeSeatIndex(player, member, acceptSeat).isPresent();
  }

  /**
   * 同 {@link #enterNearestFreeSeat(Player, MinecartMember, IntPredicate)}，返回坐进去的座位在这节车厢里的序号。
   *
   * @return 坐进去的座位序号；没坐进去时为空
   */
  public static OptionalInt enterNearestFreeSeatIndex(
      Player player, MinecartMember<?> member, IntPredicate acceptSeat) {
    if (member == null || member.isUnloaded()) {
      return OptionalInt.empty();
    }
    Vector eye = player.getEyeLocation().toVector();
    CartAttachmentSeat best = null;
    int bestIndex = -1;
    double bestDistance = Double.POSITIVE_INFINITY;
    List<CartAttachmentSeat> seats = seatsOf(member);
    for (int index = 0; index < seats.size(); index++) {
      CartAttachmentSeat seat = seats.get(index);
      if (seat.getEntity() != null || !acceptSeat.test(index)) {
        continue;
      }
      double distance = distanceSquared(seat, player, eye);
      if (best == null || distance < bestDistance) {
        best = seat;
        bestIndex = index;
        bestDistance = distance;
      }
    }
    return best != null && best.enter(player) ? OptionalInt.of(bestIndex) : OptionalInt.empty();
  }

  /** 座位到玩家眼睛的距离；取不到座位位置时排在最后。 */
  private static double distanceSquared(CartAttachmentSeat seat, Player player, Vector eye) {
    try {
      Location at = seat.getPosition(player);
      if (at == null || at.getWorld() == null || !at.getWorld().equals(player.getWorld())) {
        return Double.MAX_VALUE;
      }
      return at.toVector().distanceSquared(eye);
    } catch (RuntimeException ex) {
      return Double.MAX_VALUE;
    }
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
    for (int index = 0; index < members; index++) {
      MinecartMember<?> member = group.get(index);
      List<Integer> seats = new ArrayList<>();
      if (member != null) {
        List<CartAttachmentSeat> all = seatsOf(member);
        for (int i = 0; i < all.size(); i++) {
          if (CabSeats.nameMatches(all.get(i).getNames(), cabNames)) {
            seats.add(i);
          }
        }
        // 端车两头都标了驾驶座（单节车重连）：只认离相邻车厢最远的外侧那个。
        if (members >= 2 && (index == 0 || index == members - 1) && seats.size() >= 2) {
          MinecartMember<?> neighbour = group.get(index == 0 ? 1 : members - 2);
          seats =
              new ArrayList<>(
                  CabSeats.outerCabSeats(seats, seat -> distanceTo(all.get(seat), neighbour)));
        }
      }
      marked.add(seats);
    }
    return CabSeats.of(marked);
  }

  /** 座位到另一节车厢中心的距离（格）；车厢没加载、取不到位置时为 NaN。 */
  private static double distanceTo(CartAttachmentSeat seat, MinecartMember<?> member) {
    try {
      if (member == null || member.isUnloaded() || member.getEntity() == null) {
        return Double.NaN;
      }
      Vector at = seat.getTransform().toVector();
      return at.distance(member.getEntity().getLocation().toVector());
    } catch (RuntimeException ex) {
      return Double.NaN;
    }
  }

  /** 标记驾驶座的结果。 */
  public enum MarkOutcome {
    /** 玩家没有坐在 TrainCarts 座位里。 */
    NOT_SEATED,
    /** 座位在共用模型（model 附件引用的存档模型）里：改它会波及用这个模型的所有列车，不改。 */
    SHARED_MODEL,
    /** 已改名。 */
    CHANGED,
    /** 本来就是要的状态，没有改。 */
    UNCHANGED,
    /** 这节车厢已有两个驾驶座（两端各一个），没有标。 */
    CAR_FULL,
    /** 指定的座位序号在玩家所坐的这节车厢里不存在。 */
    NO_SUCH_SEAT
  }

  /**
   * 标记驾驶座的结果。
   *
   * @param outcome 结果
   * @param seat 改的座位（默认玩家所坐的座位）；没坐在座位里时为空
   * @param names 座位附件此刻的名字
   * @param end 改名后这个座位在列车的哪一端（{@link CabSeats#endOf}）
   * @param memberCount 编组节数
   * @param trainMarked 改名后列车上还有没有被标记的驾驶座
   * @param otherCabSeats 标记时同一节车厢里其余的驾驶座（座位序号）
   */
  public record MarkResult(
      MarkOutcome outcome,
      Optional<SeatBinding> seat,
      List<String> names,
      CabSeats.End end,
      int memberCount,
      boolean trainMarked,
      List<Integer> otherCabSeats) {

    public MarkResult {
      names = List.copyOf(names);
      otherCabSeats = List.copyOf(otherCabSeats);
    }

    static MarkResult of(MarkOutcome outcome, SeatBinding seat, List<String> names, CabSeats cabs) {
      return of(outcome, seat, names, cabs, List.of());
    }

    static MarkResult of(
        MarkOutcome outcome,
        SeatBinding seat,
        List<String> names,
        CabSeats cabs,
        List<Integer> otherCabSeats) {
      return new MarkResult(
          outcome,
          Optional.of(seat),
          names,
          cabs.endOf(seat),
          cabs.memberCount(),
          cabs.marked(),
          otherCabSeats);
    }
  }

  /**
   * 把玩家所坐的座位标为驾驶座，或取消标记：改座位附件的名字（TrainCarts 附件配置的 {@code names}），随即同步到这节车厢的模型。
   *
   * <p>只改这列车（车厢属性里的模型）；以后出库的车用的是存车，要另行保存。座位在共用模型里时不改。
   *
   * @param mark {@code true} 标记，{@code false} 取消
   * @param cabNames 驾驶座名单（已转小写）；标记时不能为空
   */
  public static MarkResult markCabSeat(Player player, boolean mark, List<String> cabNames) {
    return markCabSeat(player, mark, cabNames, OptionalInt.empty());
  }

  /**
   * 同 {@link #markCabSeat(Player, boolean, List)}，可改玩家所坐这节车厢里的另一个座位。
   *
   * @param seatIndex 要改的座位序号（0 起，见 {@link SeatBinding#seatIndex()}）；为空时改玩家所坐的座位
   */
  public static MarkResult markCabSeat(
      Player player, boolean mark, List<String> cabNames, OptionalInt seatIndex) {
    Optional<SeatBinding> located = locate(player);
    MinecartMember<?> member =
        located.isEmpty() ? null : MinecartMemberStore.getFromEntity(player.getVehicle());
    CartAttachmentSeat sitting =
        member == null ? null : member.getAttachments().findSeatOfExistingPassenger(player);
    if (located.isEmpty() || sitting == null) {
      return new MarkResult(
          MarkOutcome.NOT_SEATED,
          Optional.empty(),
          List.of(),
          CabSeats.End.NONE,
          0,
          false,
          List.of());
    }
    MinecartGroup group = member.getGroup();
    List<CartAttachmentSeat> seats = seatsOf(member);
    int index = seatIndex.orElse(located.get().seatIndex());
    SeatBinding binding =
        new SeatBinding(located.get().trainName(), located.get().memberIndex(), index);
    if (index < 0 || index >= seats.size()) {
      return MarkResult.of(MarkOutcome.NO_SUCH_SEAT, binding, List.of(), cabSeats(group, cabNames));
    }
    List<List<String>> names = new ArrayList<>(seats.size());
    for (CartAttachmentSeat each : seats) {
      names.add(namesOf(each.getConfig()));
    }
    ConfigurationNode config = seats.get(index).getConfig();
    List<String> current = names.get(index);
    AttachmentModel model = member.getProperties().getModel();
    if (!descendsFrom(config, model.getConfig())) {
      return MarkResult.of(MarkOutcome.SHARED_MODEL, binding, current, cabSeats(group, cabNames));
    }
    List<Integer> others = List.of();
    List<String> next;
    if (mark) {
      // 一节车厢最多两个驾驶座（见 CabSeats#markInCar），同一节里原有的驾驶座不动。
      CabSeats.CarMarking marking = CabSeats.markInCar(names, index, cabNames);
      others = marking.otherCabSeats();
      if (marking.full()) {
        return MarkResult.of(
            MarkOutcome.CAR_FULL, binding, current, cabSeats(group, cabNames), others);
      }
      next = marking.names();
    } else {
      next = CabSeats.withoutCabNames(current, cabNames);
    }
    if (next.equals(current)) {
      return MarkResult.of(
          MarkOutcome.UNCHANGED, binding, current, cabSeats(group, cabNames), others);
    }
    writeNames(config, next);
    // 改模型配置后立即同步：座位附件随之重新载入名字，下面的驾驶室认定读到的就是新名字。
    model.sync();
    return MarkResult.of(MarkOutcome.CHANGED, binding, next, cabSeats(group, cabNames), others);
  }

  /** 座位附件配置里的名字。 */
  private static List<String> namesOf(ConfigurationNode config) {
    return config.contains("names")
        ? List.copyOf(config.getList("names", String.class))
        : List.of();
  }

  /** 写回座位附件的名字；没有名字时去掉这一项。 */
  private static void writeNames(ConfigurationNode config, List<String> names) {
    if (names.isEmpty()) {
      config.remove("names");
    } else {
      config.set("names", names);
    }
  }

  /** 配置节点是不是挂在给定的根节点下面（车厢自己的模型，而不是 model 附件引用的共用模型）。 */
  static boolean descendsFrom(ConfigurationNode node, ConfigurationNode root) {
    for (ConfigurationNode current = node; current != null; current = current.getParent()) {
      if (current == root) {
        return true;
      }
    }
    return false;
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
