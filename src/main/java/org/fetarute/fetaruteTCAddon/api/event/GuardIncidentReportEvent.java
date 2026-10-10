package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;

/** 车掌报告异常情况（1.14.0）：当前这一步的时限延长一次，每站最多两次，不扣分。 */
public final class GuardIncidentReportEvent extends GuardEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final String station;
  private final GuardApi.Incident incident;

  public GuardIncidentReportEvent(
      UUID playerId, String trainName, String station, GuardApi.Incident incident) {
    super(playerId);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.station = station == null ? "" : station;
    this.incident = Objects.requireNonNull(incident, "incident");
  }

  /** 列车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 车站名。 */
  public String getStation() {
    return station;
  }

  /** 原因。 */
  public GuardApi.Incident getIncident() {
    return incident;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
