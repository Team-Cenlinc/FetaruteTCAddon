package org.fetarute.fetaruteTCAddon.api.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 列车被扣停：信号、占用、授权、尾部保护等非例行停车；或停站/门控超出正常站内用时（停站计时结束后、按表早到则计划发车后超过 5 秒仍未发车）。 正常停站、按表等点、折返待命、终点作业不算。
 *
 * <p>同一次扣停只发一次；期间原因变化会再发一次（{@link #getReasonCode()} 不同、{@link #getSince()} 相同）。 扣停解除时发 {@link
 * TrainHoldReleasedEvent}。与 ETA 顺延使用同一个判定，每 tick 检测。
 */
public final class TrainHoldEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final String reasonCode;
  private final String detail;
  private final List<Blocker> blockers;
  private final Instant since;

  public TrainHoldEvent(
      String trainName, String reasonCode, String detail, List<Blocker> blockers, Instant since) {
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.reasonCode = reasonCode == null ? "UNKNOWN" : reasonCode;
    this.detail = detail == null ? "" : detail;
    this.blockers = blockers == null ? List.of() : List.copyOf(blockers);
    this.since = Objects.requireNonNull(since, "since");
  }

  /** 列车名。 */
  public String getTrainName() {
    return trainName;
  }

  /** 扣停原因代码（如 {@code HARD_BLOCKER_STOP}、{@code AUTHORIZATION_FAILURE}）。 */
  public String getReasonCode() {
    return reasonCode;
  }

  /** 诊断明细，不保证稳定格式。 */
  public String getDetail() {
    return detail;
  }

  /** 扣停时观察到的阻塞资源与持有者。 */
  public List<Blocker> getBlockers() {
    return blockers;
  }

  /** 本次扣停开始时刻（运行时停车状态换原因或阻挡者时保持不变）。 */
  public Instant getSince() {
    return since;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }

  /**
   * 阻塞者。
   *
   * @param resource 被占用的资源（如 {@code NODE:SURC:S:HHU:1}、{@code CONFLICT:single:...}）
   * @param owner 持有该资源的列车名
   * @param role 持有角色（如 {@code MOVEMENT_REQUIRED}、{@code PROTECTIVE_RETAIN}）
   */
  public record Blocker(String resource, String owner, String role) {}
}
