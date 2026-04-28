package org.fetarute.fetaruteTCAddon.dispatcher.schedule.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ScheduleWindow;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ScheduledStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ServiceTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDepot;

/**
 * 计划窗口 JSON 导出器。
 *
 * <p>该 JSON 是第一阶段的 machine-readable snapshot。所有 {@link Instant} 均以 epoch millis 输出，并在字段名中带 {@code
 * _epoch_millis} 后缀。Optional 为空时直接省略字段，不输出 {@code null}，避免下游无法区分“未知”和“显式空值”。
 */
public final class ScheduleJsonExporter {

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final Gson PRETTY_GSON =
      new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

  private ScheduleJsonExporter() {}

  /** 返回可被 Gson 继续处理的稳定 JSON tree。 */
  public static JsonObject toJsonTree(ScheduleWindow window) {
    JsonObject root = new JsonObject();
    if (window == null) {
      root.addProperty("generated_at_epoch_millis", Instant.EPOCH.toEpochMilli());
      root.add("trips", new JsonArray());
      return root;
    }
    root.addProperty("generated_at_epoch_millis", window.generatedAt().toEpochMilli());
    root.addProperty("window_start_epoch_millis", window.windowStart().toEpochMilli());
    root.addProperty("window_end_epoch_millis", window.windowEnd().toEpochMilli());
    JsonArray trips = new JsonArray();
    for (ServiceTrip trip : window.trips()) {
      trips.add(tripToJson(trip));
    }
    root.add("trips", trips);
    return root;
  }

  /** 紧凑 JSON。 */
  public static String export(ScheduleWindow window) {
    return GSON.toJson(toJsonTree(window));
  }

  /** 适合人工查看的缩进 JSON。 */
  public static String exportPretty(ScheduleWindow window) {
    return PRETTY_GSON.toJson(toJsonTree(window));
  }

  private static JsonObject tripToJson(ServiceTrip trip) {
    JsonObject json = new JsonObject();
    json.addProperty("trip_id", trip.tripId());
    json.addProperty("source", trip.source().name());
    json.addProperty("company_id", trip.companyId().toString());
    json.addProperty("company", trip.companyCode());
    json.addProperty("operator_id", trip.operatorId().toString());
    json.addProperty("operator", trip.operatorCode());
    json.addProperty("line_id", trip.lineId().toString());
    json.addProperty("line", trip.lineCode());
    json.addProperty("route_id", trip.routeId().toString());
    json.addProperty("route", trip.routeCode());
    trip.direction().ifPresent(value -> json.addProperty("direction", value));
    json.addProperty("planned_departure_epoch_millis", trip.plannedDeparture().toEpochMilli());
    json.addProperty("priority", trip.priority());
    trip.maxOperationTrips().ifPresent(value -> json.addProperty("max_operation_trips", value));
    trip.notes().ifPresent(value -> json.addProperty("notes", value));
    JsonArray depotCandidates = new JsonArray();
    for (SpawnDepot depot : trip.depotCandidates()) {
      JsonObject depotJson = new JsonObject();
      depotJson.addProperty("node_id", depot.nodeId());
      depotJson.addProperty("weight", depot.weight());
      depotCandidates.add(depotJson);
    }
    json.add("depot_candidates", depotCandidates);
    JsonArray stops = new JsonArray();
    for (ScheduledStop stop : trip.plannedStops()) {
      stops.add(stopToJson(stop));
    }
    json.add("planned_stops", stops);
    return json;
  }

  private static JsonObject stopToJson(ScheduledStop stop) {
    JsonObject json = new JsonObject();
    json.addProperty("stop_sequence", stop.stopSequence());
    stop.stationCode().ifPresent(value -> json.addProperty("station_code", value));
    stop.nodeId().ifPresent(value -> json.addProperty("node_id", value));
    stop.plannedArrival()
        .ifPresent(value -> json.addProperty("planned_arrival_epoch_millis", value.toEpochMilli()));
    stop.plannedDeparture()
        .ifPresent(
            value -> json.addProperty("planned_departure_epoch_millis", value.toEpochMilli()));
    stop.dwell().ifPresent(value -> json.addProperty("dwell_seconds", value.toSeconds()));
    stop.notes().ifPresent(value -> json.addProperty("notes", value));
    return json;
  }
}
