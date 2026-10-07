package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 驾驶员控车时的一次停站：站台给出停车点与站台侧，驾驶员回报车门，双方按阶段推进。
 *
 * <p>站台（AutoStation）负责判断停妥、计停站时间、出站许可与记录发车；驾驶员负责停车、开关门与起步。只在服务器主线程使用。
 */
public final class DriverStationStop {

  /** 停站阶段。 */
  public enum Phase {
    /** 进站：等列车停在停车窗口内。 */
    APPROACH,
    /** 已停妥，等驾驶员开门。 */
    OPEN_DOORS,
    /** 停站计时中。 */
    DWELL,
    /** 停站时间到，等驾驶员关门。 */
    CLOSE_DOORS,
    /** 车门已关，等出站许可。 */
    WAIT_DEPARTURE,
    /** 已有出站许可，等列车起步。 */
    DEPART,
    /** 已发车、已交还自动运行或作废。 */
    ENDED;

    /** 已过开门这一步：车门已开（或本站不开门），正在停站、等关门、等发车或已放行。终点站据此结算、开始换端。 */
    public boolean doorsOpened() {
      return switch (this) {
        case DWELL, CLOSE_DOORS, WAIT_DEPARTURE, DEPART -> true;
        default -> false;
      };
    }

    /** 正要驾驶员操作车门或等停站计时：等开门、停站、等关门。这时停站提示优先于换端提示。 */
    public boolean needsDriver() {
      return switch (this) {
        case OPEN_DOORS, DWELL, CLOSE_DOORS -> true;
        default -> false;
      };
    }
  }

  private final NodeId node;
  private final String stationName;
  private final UUID worldId;
  private final Vector stopPoint;
  private final StopAlignment.Reference reference;
  private final BlockFace platformFace;
  private final boolean bothSides;
  private final boolean doorsRequired;

  private StopWindow window = StopWindow.DEFAULTS;
  private DoorCars doorCars = DoorCars.ALL;
  private Phase phase = Phase.APPROACH;
  private double offsetBlocks = Double.NaN;
  private double stoppedOffsetBlocks = Double.NaN;
  private boolean stopped;
  private boolean resultReported;
  private long dwellRemainingTicks;
  private boolean correctDoorsOpen;
  private boolean anyDoorOpen;
  private boolean wrongDoorOpened;
  private boolean doorsTakenOver;
  private boolean doorsHandedOver;
  private boolean skipped;

  /** 列车中心对准停车点（车站牌子的默认对位）。 */
  public DriverStationStop(
      NodeId node,
      String stationName,
      UUID worldId,
      Vector stopPoint,
      BlockFace platformFace,
      boolean bothSides,
      boolean doorsRequired) {
    this(
        node,
        stationName,
        worldId,
        stopPoint,
        StopAlignment.Reference.CENTER,
        platformFace,
        bothSides,
        doorsRequired);
  }

  /**
   * @param node 车站节点
   * @param stationName 显示用的站名
   * @param worldId 停车点所在世界
   * @param stopPoint 停车点（与 TrainCarts 对位一致）
   * @param reference 用列车的哪个部位对准停车点：车站牌子是列车中心，停车位置标是车头
   * @param platformFace 站台在列车的哪个世界方位；两侧开门或不开门时为 {@code null}
   * @param bothSides 两侧开门
   * @param doorsRequired 本站是否开门
   */
  public DriverStationStop(
      NodeId node,
      String stationName,
      UUID worldId,
      Vector stopPoint,
      StopAlignment.Reference reference,
      BlockFace platformFace,
      boolean bothSides,
      boolean doorsRequired) {
    this.node = Objects.requireNonNull(node, "node");
    this.stationName = stationName == null || stationName.isBlank() ? node.value() : stationName;
    this.worldId = Objects.requireNonNull(worldId, "worldId");
    this.stopPoint = Objects.requireNonNull(stopPoint, "stopPoint").clone();
    this.reference = Objects.requireNonNull(reference, "reference");
    this.platformFace = platformFace;
    this.bothSides = bothSides;
    this.doorsRequired = doorsRequired;
  }

  public NodeId node() {
    return node;
  }

  public String stationName() {
    return stationName;
  }

  public UUID worldId() {
    return worldId;
  }

  public Vector stopPoint() {
    return stopPoint.clone();
  }

  /** 用列车的哪个部位对准停车点。 */
  public StopAlignment.Reference reference() {
    return reference;
  }

  /** 站台所在的世界方位；两侧开门或不开门时为空。 */
  public Optional<BlockFace> platformFace() {
    return Optional.ofNullable(platformFace);
  }

  public boolean bothSides() {
    return bothSides;
  }

  public boolean doorsRequired() {
    return doorsRequired;
  }

  /** 本次停站的停车窗口（驾驶员接下停站时按驾驶配置设置）。 */
  public StopWindow window() {
    return window;
  }

  public void setWindow(StopWindow window) {
    this.window = window == null ? StopWindow.DEFAULTS : window;
  }

  /** 本站开关门的车厢（停车位置标写了 {@code door:} 时只是其中几节）。 */
  public DoorCars doorCars() {
    return doorCars;
  }

  public void setDoorCars(DoorCars doorCars) {
    this.doorCars = doorCars == null ? DoorCars.ALL : doorCars;
  }

  public Phase phase() {
    return phase;
  }

  public boolean active() {
    return phase != Phase.ENDED;
  }

  /** 推进到下一阶段；已结束的停站不再变化。 */
  public void setPhase(Phase next) {
    if (phase != Phase.ENDED && next != null) {
      phase = next;
    }
  }

  /** 结束这次停站。 */
  public void end() {
    phase = Phase.ENDED;
  }

  /** 越站：越过停车点太多，本站不停、不开门，停站就此结束。 */
  public void markSkipped() {
    if (phase != Phase.ENDED) {
      skipped = true;
      phase = Phase.ENDED;
    }
  }

  /** 是否越站。 */
  public boolean skipped() {
    return skipped;
  }

  /** 列车（按 {@link #reference()} 取中心或车头）相对停车点的偏移（格）：正数为越过，负数为未到；量不出时为 {@code NaN}。 */
  public double offsetBlocks() {
    return offsetBlocks;
  }

  public void updateOffset(double offset) {
    this.offsetBlocks = offset;
  }

  /** 停妥：记下停车时的偏移，进入开门（本站不开门时直接计停站时间）。 */
  public void markStopped() {
    stopped = true;
    stoppedOffsetBlocks = offsetBlocks;
    setPhase(doorsRequired ? Phase.OPEN_DOORS : Phase.DWELL);
  }

  /** 是否停妥过（开过门窗口）。 */
  public boolean stopped() {
    return stopped;
  }

  /**
   * 记下驾驶侧已经报过这一站的对标结果。
   *
   * @return 第一次调用时为 {@code true}
   */
  public boolean markResultReported() {
    if (resultReported) {
      return false;
    }
    resultReported = true;
    return true;
  }

  /** 停妥时的偏移；还没停妥或量不出时为 {@code NaN}。 */
  public double stoppedOffsetBlocks() {
    return stoppedOffsetBlocks;
  }

  public long dwellRemainingTicks() {
    return dwellRemainingTicks;
  }

  public void setDwellRemainingTicks(long ticks) {
    this.dwellRemainingTicks = Math.max(0L, ticks);
  }

  /**
   * 驾驶员回报车门状态。
   *
   * @param correct 站台侧的门已开
   * @param any 有门开着
   * @param wrong 有非站台侧的门开着
   */
  public void reportDoors(boolean correct, boolean any, boolean wrong) {
    this.correctDoorsOpen = correct;
    this.anyDoorOpen = any;
    if (wrong) {
      wrongDoorOpened = true;
    }
  }

  public boolean correctDoorsOpen() {
    return correctDoorsOpen;
  }

  public boolean anyDoorOpen() {
    return anyDoorOpen;
  }

  /** 本站是否开过非站台侧的门。 */
  public boolean wrongDoorOpened() {
    return wrongDoorOpened;
  }

  /** 驾驶员迟迟不开门，由站台代为开关门。 */
  public void markDoorsTakenOver() {
    doorsTakenOver = true;
  }

  public boolean doorsTakenOver() {
    return doorsTakenOver;
  }

  /** 停站中途由驾驶员接管：站台开着的站台侧车门交给驾驶员，之后由驾驶员关门。 */
  public void handOverOpenDoors() {
    doorsHandedOver = true;
  }

  /**
   * 取走“站台开着的车门已交给驾驶员”：驾驶侧据此把站台侧车门记成开着。
   *
   * @return 第一次取走且确有交接时为 {@code true}
   */
  public boolean takeHandedOverDoors() {
    boolean handed = doorsHandedOver;
    doorsHandedOver = false;
    return handed;
  }
}
