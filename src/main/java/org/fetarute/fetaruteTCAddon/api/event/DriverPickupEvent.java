package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;

/** 始发站或车库接车的进展（1.10.0）：列车停下等驾驶员、驾驶员上车、等到时限照常发车。 */
public final class DriverPickupEvent extends DriverEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final DriveApi.TaskView task;
  private final String trainName;
  private final Kind kind;
  private final Stage stage;
  private final String location;

  public DriverPickupEvent(
      UUID playerId,
      DriveApi.TaskView task,
      String trainName,
      Kind kind,
      Stage stage,
      String location) {
    super(playerId);
    this.task = Objects.requireNonNull(task, "task");
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.stage = Objects.requireNonNull(stage, "stage");
    this.location = location == null ? "" : location;
  }

  /** 任务。 */
  public DriveApi.TaskView getTask() {
    return task;
  }

  /** 留给驾驶员的列车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 从哪里接车。 */
  public Kind getKind() {
    return kind;
  }

  /** 进展。 */
  public Stage getStage() {
    return stage;
  }

  /** 列车停在哪里（站名或车库节点） */
  public String getLocation() {
    return location;
  }

  /** 从哪里接车。 */
  public enum Kind {
    /** 终点站待命车。 */
    TERMINAL,
    /** 车库出车。 */
    DEPOT
  }

  /** 接车进展。 */
  public enum Stage {
    /** 列车停着等驾驶员。 */
    WAITING,
    /** 驾驶员已上车接班。 */
    BOARDED,
    /** 等到时限，照常发车。 */
    EXPIRED
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
