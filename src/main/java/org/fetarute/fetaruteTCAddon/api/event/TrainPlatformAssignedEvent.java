package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 列车在某一站的站台定下来或变了（1.9.0）。
 *
 * <p>动态站台（DYNAMIC）在进站前选台、被挡时改选；固定站台的车停到了别的股道也算。第一次定下、或与上一次不同才发，同值不发。 下一 tick
 * 在主线程发出。显示方据此刷新站台号，变了的时候提示乘客。
 */
public final class TrainPlatformAssignedEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final String routeId;
  private final int stopSequence;
  private final String nodeId;
  private final String platform;
  private final Optional<String> previousPlatform;
  private final Optional<String> plannedPlatform;
  private final Reason reason;

  /**
   * @param trainName 列车名
   * @param routeId 交路 ID（{@code 运营商:线路:交路}）
   * @param stopSequence 停靠序号（交路节点的 0 起下标）
   * @param nodeId 现在的站台节点
   * @param platform 现在的站台号
   * @param previousPlatform 上一次定下的站台号；第一次定下时为空
   * @param plannedPlatform 计划站台号：动态站台为编表排定或暂定的站台，没有时为空；固定站台为交路声明的站台
   * @param reason 原因
   */
  public TrainPlatformAssignedEvent(
      String trainName,
      String routeId,
      int stopSequence,
      String nodeId,
      String platform,
      Optional<String> previousPlatform,
      Optional<String> plannedPlatform,
      Reason reason) {
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.routeId = Objects.requireNonNull(routeId, "routeId");
    this.stopSequence = stopSequence;
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.platform = Objects.requireNonNull(platform, "platform");
    this.previousPlatform = previousPlatform == null ? Optional.empty() : previousPlatform;
    this.plannedPlatform = plannedPlatform == null ? Optional.empty() : plannedPlatform;
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /** 列车名。 */
  public String getTrainName() {
    return trainName;
  }

  /** 交路 ID。 */
  public String getRouteId() {
    return routeId;
  }

  /** 停靠序号（交路节点的 0 起下标，与 RouteApi、TimetableApi 同一口径）。 */
  public int getStopSequence() {
    return stopSequence;
  }

  /** 现在的站台节点。 */
  public String getNodeId() {
    return nodeId;
  }

  /** 现在的站台号。 */
  public String getPlatform() {
    return platform;
  }

  /** 上一次定下的站台号；第一次定下时为空。 */
  public Optional<String> getPreviousPlatform() {
    return previousPlatform;
  }

  /** 计划站台号：动态站台为编表排定或暂定的站台，没有时为空；固定站台为交路声明的站台。 */
  public Optional<String> getPlannedPlatform() {
    return plannedPlatform;
  }

  /** 原因。 */
  public Reason getReason() {
    return reason;
  }

  /** 站台落定的原因。 */
  public enum Reason {
    /** 第一次定下，与计划一致或没有计划。 */
    ASSIGNED,
    /** 第一次定下，但不是计划站台（计划站台不可用而改选；固定站台的车停到了别的股道）。 */
    CHANGED_FROM_PLAN,
    /** 已定下的站台改了。 */
    CHANGED
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
