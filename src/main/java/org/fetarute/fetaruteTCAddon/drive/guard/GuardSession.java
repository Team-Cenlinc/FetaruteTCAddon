package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveSidebarRows;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarRewriter;
import org.fetarute.fetaruteTCAddon.drive.menu.DriveDoors;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeatKey;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;

/**
 * 一名车掌的值乘：在哪列车、预留的是哪个座位、开着哪侧的门、快捷栏此刻的样子。
 *
 * <p>座位按车厢实体与座位序号记（整列调头不影响）；车厢序号每 tick 按编组重算。只在服务器主线程读写，快捷栏改写规则与菜单槽位数会被网络线程读取。
 */
public final class GuardSession implements DriveDoors.Cab {

  /** 值乘结束的原因。 */
  public enum EndReason {
    /** 车掌自己结束。 */
    COMMAND,
    OFFLINE,
    DEATH,
    GAME_MODE,
    /** 列车不在了（回库销毁、被清掉、跨世界后找不到）。 */
    TRAIN_GONE,
    /** 连续几站有超时。 */
    TIMEOUTS,
    /** 列车开走时车掌不在车上（漏乘）。 */
    LEFT_BEHIND,
    /** 车掌功能被关掉或插件停用。 */
    DISABLED,
    /** 管理员撤下。 */
    ADMIN,
    /** 终点站换端时没能坐进车尾端驾驶室。 */
    CAB_CHANGE,
    /** 车掌考试没有通过（考试期间临时有车掌权限）。 */
    EXAM,
    /** 车掌任务值乘到交班站。 */
    HANDOVER
  }

  private final UUID playerId;
  private final String playerName;
  private final GuardLink link;
  private CabSeatKey seat;
  private final DriveDoors doors = new DriveDoors();
  private final BuzzerPress buzzer;
  private final long startedTick;

  private SeatBinding binding;
  private boolean leftDoorOpen;
  private boolean rightDoorOpen;
  private long doorsClosingUntilTick;
  private volatile HotbarRewriter<ItemStack> rewriter;
  private volatile int menuTopSize;
  private GuardHotbar.View view;
  private long endConfirmUntilTick = Long.MIN_VALUE;
  private DriverStationStop trackedStop;
  private DriverStationStop lastSettledStop;
  private DriverStationStop.Phase lastPhase;
  private long noticeUntilTick;
  private DriveSidebarRows.Row notice;
  private boolean sidebarDirty;
  private boolean signalAnnounced;
  private boolean forcedSignalHandled;
  private boolean sneakHeld;
  private com.bergerkiller.bukkit.tc.controller.MinecartGroup lastGroup;
  private boolean closingWatchActive;
  private DepartureWatch departureWatch;
  private final GuardCabChange cabChange = new GuardCabChange();
  private boolean moving;
  private CabSeats.Departure layoverDeparture;
  private org.fetarute.fetaruteTCAddon.drive.seat.CabSeatsMemo cabSeatsMemo;
  private GuardTrip trip = new GuardTrip(null, "", java.time.Instant.now());
  private WorkedStop pendingExamStop;
  private GuardDrill drill;
  private GuardTask task;

  /**
   * 考试中做完、还在采出站监视的一站：采完再交给考官。
   *
   * @param station 站名
   * @param work 这一站的作业
   */
  public record WorkedStop(String station, GuardStopWork work) {}

  private TaskKey trackedTrip;
  private org.bukkit.util.Vector lastHead;
  private java.util.UUID lastWorld;

  /**
   * 进行中的出站监视：起步时车头的位置，走过多远、到哪个 tick 为止，站台在哪个方向，结果记进哪一站。
   *
   * @param work 刚开出的那一站
   * @param origin 起步时车头的位置
   * @param blocks 走过这么远算车尾离开站台
   * @param untilTick 最长到这个 tick
   * @param platformSide 站台在哪个方向；两侧开门或判定不出时为 {@code null}
   */
  public record DepartureWatch(
      GuardStopWork work,
      org.bukkit.util.Vector origin,
      double blocks,
      long untilTick,
      org.bukkit.util.Vector platformSide) {}

  public GuardSession(
      UUID playerId,
      String playerName,
      GuardLink link,
      CabSeatKey seat,
      SeatBinding binding,
      BuzzerPress buzzer,
      long startedTick) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.playerName = playerName == null ? playerId.toString() : playerName;
    this.link = Objects.requireNonNull(link, "link");
    this.seat = Objects.requireNonNull(seat, "seat");
    this.binding = Objects.requireNonNull(binding, "binding");
    this.buzzer = Objects.requireNonNull(buzzer, "buzzer");
    this.startedTick = startedTick;
  }

  public UUID playerId() {
    return playerId;
  }

  public String playerName() {
    return playerName;
  }

  public GuardLink link() {
    return link;
  }

  /** 预留的车掌座位（车厢实体 + 座位序号）。 */
  public CabSeatKey seat() {
    return seat;
  }

  /** 换端后预留改到新坐的座位。 */
  public void moveSeat(CabSeatKey seat, SeatBinding binding) {
    this.seat = Objects.requireNonNull(seat, "seat");
    this.binding = Objects.requireNonNull(binding, "binding");
  }

  /** 终点站换端。 */
  public GuardCabChange cabChange() {
    return cabChange;
  }

  /** 预留的座位此刻让给驾驶员（只让给驾驶员，乘客照样坐不进）：在换端，或正被直接送进另一端。 */
  public boolean seatReleased() {
    return moving || cabChange.changing();
  }

  /** 正在把车掌直接送进另一端（同一拍里先请下、再分别入座）。 */
  public void setMoving(boolean moving) {
    this.moving = moving;
  }

  /** 只有车掌的列车这次待命预计的发车端（要查线路图，待命期间只查一次）；没在待命时为 {@code null}。 */
  public CabSeats.Departure layoverDeparture() {
    return layoverDeparture;
  }

  public void setLayoverDeparture(CabSeats.Departure departure) {
    this.layoverDeparture = departure;
  }

  /** 正在做的这一趟（车次换了或值乘结束时结算）。 */
  public GuardTrip trip() {
    return trip;
  }

  public void setTrip(GuardTrip trip) {
    this.trip = Objects.requireNonNull(trip, "trip");
  }

  /** 取走等出站监视采完再交给考官的那一站；没有时为 {@code null}。 */
  public WorkedStop takePendingExamStop() {
    WorkedStop pending = pendingExamStop;
    pendingExamStop = null;
    return pending;
  }

  public void setPendingExamStop(WorkedStop stop) {
    this.pendingExamStop = stop;
  }

  /** 这次值乘接的车掌任务；不是领任务上岗、或任务已结算时为 {@code null}。 */
  public GuardTask task() {
    return task;
  }

  void setTask(GuardTask task) {
    this.task = task;
  }

  /** 正在进行的夹人夹物演练；没有时为 {@code null}。 */
  GuardDrill drill() {
    return drill;
  }

  void setDrill(GuardDrill drill) {
    this.drill = drill;
  }

  /** 正在跟着的那一站开始时列车跑的车次；不按时刻表运行时为 {@code null}。 */
  public TaskKey trackedTrip() {
    return trackedTrip;
  }

  public void setTrackedTrip(TaskKey key) {
    this.trackedTrip = key;
  }

  /**
   * 列车车头又挪到了这里：返回比上一拍走了多远（格）。换了世界、第一次量时为 0。
   *
   * @param maxStep 一拍走的超过这么远不算（调头时车头换到另一端、传送）
   */
  public double moveHead(java.util.UUID world, org.bukkit.util.Vector head, double maxStep) {
    double moved = 0.0;
    if (lastHead != null && world != null && world.equals(lastWorld)) {
      double distance = lastHead.distance(head);
      if (distance <= maxStep) {
        moved = distance;
      }
    }
    lastHead = head.clone();
    lastWorld = world;
    return moved;
  }

  public org.fetarute.fetaruteTCAddon.drive.seat.CabSeatsMemo cabSeatsMemo() {
    return cabSeatsMemo;
  }

  public void setCabSeatsMemo(org.fetarute.fetaruteTCAddon.drive.seat.CabSeatsMemo memo) {
    this.cabSeatsMemo = memo;
  }

  public DriveDoors doors() {
    return doors;
  }

  public BuzzerPress buzzer() {
    return buzzer;
  }

  public long startedTick() {
    return startedTick;
  }

  @Override
  public SeatBinding binding() {
    return binding;
  }

  /** 编组次序变了（调头、改名）后更新座位所在的车厢序号与车名。 */
  public void setBinding(SeatBinding binding) {
    this.binding = Objects.requireNonNull(binding, "binding");
  }

  /**
   * 车掌所在的驾驶室是不是车头端：座位在编组前半算车头端。单节车按车尾端算（车掌背向前进方向）。
   *
   * @param memberCount 编组节数
   */
  @Override
  public boolean cabAtHead(int memberCount) {
    return memberCount > 1 && binding.cabSign(memberCount) > 0;
  }

  /** 车掌的车门左右按列车行进方向（车头端）算，与报站、站台屏、驾驶员一致，不按车掌坐在车尾面朝后方的方向。 */
  @Override
  public boolean doorsFromHead(int memberCount) {
    return true;
  }

  @Override
  public boolean isLeftDoorOpen() {
    return leftDoorOpen;
  }

  @Override
  public boolean isRightDoorOpen() {
    return rightDoorOpen;
  }

  public boolean anyDoorOpen() {
    return leftDoorOpen || rightDoorOpen;
  }

  @Override
  public void setDoorOpen(boolean left, boolean open) {
    if (left) {
      leftDoorOpen = open;
    } else {
      rightDoorOpen = open;
    }
  }

  @Override
  public void markDoorsClosing(long untilTick) {
    doorsClosingUntilTick = Math.max(doorsClosingUntilTick, untilTick);
  }

  /** 关门动画是否还在放（车门还没真正关上）。 */
  public boolean doorsClosing(long nowTick) {
    return !anyDoorOpen() && nowTick < doorsClosingUntilTick;
  }

  /** 快捷栏改写规则；网络线程会读取。 */
  public HotbarRewriter<ItemStack> rewriter() {
    return rewriter;
  }

  /** 快捷栏此刻的样子。 */
  public GuardHotbar.View view() {
    return view;
  }

  /** 换上新的快捷栏样子。 */
  public void setHotbar(GuardHotbar.View view, HotbarRewriter<ItemStack> rewriter) {
    this.view = view;
    this.rewriter = rewriter;
  }

  public int menuTopSize() {
    return menuTopSize;
  }

  public void setMenuTopSize(int size) {
    this.menuTopSize = Math.max(0, size);
  }

  /** 结束值乘的第一次点击到这个 tick 之前，再点一次才结束。 */
  public long endConfirmUntilTick() {
    return endConfirmUntilTick;
  }

  public void setEndConfirmUntilTick(long tick) {
    this.endConfirmUntilTick = tick;
  }

  /** 正在跟着的那一站（换了就是新的一站）。 */
  public DriverStationStop trackedStop() {
    return trackedStop;
  }

  public void setTrackedStop(DriverStationStop stop) {
    this.trackedStop = stop;
  }

  /** 已结算过的那一站（同一站不重复结算）。 */
  public DriverStationStop lastSettledStop() {
    return lastSettledStop;
  }

  public void setLastSettledStop(DriverStationStop stop) {
    this.lastSettledStop = stop;
  }

  /** 上一拍看到的停站阶段（提示音与提示换阶段时才响）。 */
  public DriverStationStop.Phase lastPhase() {
    return lastPhase;
  }

  public void setLastPhase(DriverStationStop.Phase phase) {
    this.lastPhase = phase;
  }

  /**
   * 侧边栏最上面的“提示”一行：按钮的反馈、超时与演练结果，显示到 {@code untilTick}，下一拍就刷新侧边栏。
   *
   * @param row 提示行
   */
  void showNotice(DriveSidebarRows.Row row, long untilTick) {
    this.notice = row;
    this.noticeUntilTick = untilTick;
    this.sidebarDirty = true;
  }

  /** 还没过时的提示行；没有时为空。 */
  Optional<DriveSidebarRows.Row> notice(long nowTick) {
    return nowTick < noticeUntilTick ? Optional.ofNullable(notice) : Optional.empty();
  }

  /** 下一拍就刷新侧边栏（不等固定的刷新间隔）。 */
  void markSidebarDirty() {
    this.sidebarDirty = true;
  }

  /** 取走“要立即刷新侧边栏”的标记。 */
  boolean takeSidebarDirty() {
    boolean dirty = sidebarDirty;
    sidebarDirty = false;
    return dirty;
  }

  /** 这一站的发车信号已经告诉过驾驶员。 */
  public boolean signalAnnounced() {
    return signalAnnounced;
  }

  public void setSignalAnnounced(boolean announced) {
    this.signalAnnounced = announced;
  }

  /** 关门动画正在放（关门监视在采样）。 */
  public boolean closingWatchActive() {
    return closingWatchActive;
  }

  public void setClosingWatchActive(boolean active) {
    this.closingWatchActive = active;
  }

  /** 进行中的出站监视；没有时为 {@code null}。 */
  public DepartureWatch departureWatch() {
    return departureWatch;
  }

  public void setDepartureWatch(DepartureWatch watch) {
    this.departureWatch = watch;
  }

  /** 上一拍找到的编组（还有效时直接用）。 */
  public com.bergerkiller.bukkit.tc.controller.MinecartGroup lastGroup() {
    return lastGroup;
  }

  public void setLastGroup(com.bergerkiller.bukkit.tc.controller.MinecartGroup group) {
    this.lastGroup = group;
  }

  /** 潜行键此刻按着（车掌自己按 Shift 下车才拦，插件挪座位不拦）。 */
  public boolean sneakHeld() {
    return sneakHeld;
  }

  public void setSneakHeld(boolean held) {
    this.sneakHeld = held;
  }

  /** 这一站发车铃超时代发后，已把车掌传送回座位。 */
  public boolean forcedSignalHandled() {
    return forcedSignalHandled;
  }

  public void setForcedSignalHandled(boolean handled) {
    this.forcedSignalHandled = handled;
  }
}
