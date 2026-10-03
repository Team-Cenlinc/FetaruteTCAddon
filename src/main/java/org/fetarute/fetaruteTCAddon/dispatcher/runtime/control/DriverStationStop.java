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
    ENDED
  }

  private final NodeId node;
  private final String stationName;
  private final UUID worldId;
  private final Vector stopPoint;
  private final BlockFace platformFace;
  private final boolean bothSides;
  private final boolean doorsRequired;

  private Phase phase = Phase.APPROACH;
  private double offsetBlocks = Double.NaN;
  private double stoppedOffsetBlocks = Double.NaN;
  private long dwellRemainingTicks;
  private boolean correctDoorsOpen;
  private boolean anyDoorOpen;
  private boolean wrongDoorOpened;
  private boolean doorsTakenOver;

  /**
   * @param node 车站节点
   * @param stationName 显示用的站名
   * @param worldId 停车点所在世界
   * @param stopPoint 停车点：列车中心应停在这里（与 TrainCarts 对位一致）
   * @param platformFace 站台在列车的哪个世界方位；两侧开门或不开门时为 {@code null}
   * @param bothSides 两侧开门
   * @param doorsRequired 本站是否开门
   */
  public DriverStationStop(
      NodeId node,
      String stationName,
      UUID worldId,
      Vector stopPoint,
      BlockFace platformFace,
      boolean bothSides,
      boolean doorsRequired) {
    this.node = Objects.requireNonNull(node, "node");
    this.stationName = stationName == null || stationName.isBlank() ? node.value() : stationName;
    this.worldId = Objects.requireNonNull(worldId, "worldId");
    this.stopPoint = Objects.requireNonNull(stopPoint, "stopPoint").clone();
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

  /** 列车中心相对停车点的偏移（格）：正数为越过，负数为未到；量不出时为 {@code NaN}。 */
  public double offsetBlocks() {
    return offsetBlocks;
  }

  public void updateOffset(double offset) {
    this.offsetBlocks = offset;
  }

  /** 停妥：记下停车时的偏移，进入开门（本站不开门时直接计停站时间）。 */
  public void markStopped() {
    stoppedOffsetBlocks = offsetBlocks;
    setPhase(doorsRequired ? Phase.OPEN_DOORS : Phase.DWELL);
  }

  /** 停妥时的偏移；还没停妥时为 {@code NaN}。 */
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
}
