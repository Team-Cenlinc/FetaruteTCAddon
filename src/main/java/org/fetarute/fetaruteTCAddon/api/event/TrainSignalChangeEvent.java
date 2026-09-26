package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;

/** 列车信号显示变化（每 tick 对比一次采样快照，同一 tick 内的来回变化会被合并）。 */
public final class TrainSignalChangeEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final TrainApi.Signal previous;
  private final TrainApi.Signal current;

  public TrainSignalChangeEvent(
      String trainName, TrainApi.Signal previous, TrainApi.Signal current) {
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.previous = previous == null ? TrainApi.Signal.UNKNOWN : previous;
    this.current = current == null ? TrainApi.Signal.UNKNOWN : current;
  }

  /** 列车名。 */
  public String getTrainName() {
    return trainName;
  }

  /** 变化前的信号。 */
  public TrainApi.Signal getPrevious() {
    return previous;
  }

  /** 当前信号。 */
  public TrainApi.Signal getCurrent() {
    return current;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
