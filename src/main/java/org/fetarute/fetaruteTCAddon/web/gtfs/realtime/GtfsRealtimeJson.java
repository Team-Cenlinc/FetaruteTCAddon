package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Alert;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.CarriageDetails;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Entity;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.EntitySelector;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.FeedMessage;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Header;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Position;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.StopTimeEvent;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.StopTimeUpdate;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TimeRange;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TranslatedString;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Translation;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TripDescriptor;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TripUpdate;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.VehicleDescriptor;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.VehiclePosition;

/**
 * GTFS-Realtime 的 JSON 调试输出：字段名用 proto 原名，枚举写名字。与官方 JSON 映射不逐字节一致，也不承诺稳定——它只给排查用。
 *
 * <p>编码的正文与 protobuf 是同一份 {@link FeedMessage}，不存在两套生成逻辑。
 */
public final class GtfsRealtimeJson {

  private static final Gson GSON =
      new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

  private GtfsRealtimeJson() {}

  /**
   * 写成 JSON。
   *
   * @param message 报文
   */
  public static String write(FeedMessage message) {
    return GSON.toJson(view(message));
  }

  private static Map<String, Object> view(FeedMessage message) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("header", header(message.header()));
    List<Map<String, Object>> entities = new ArrayList<>(message.entities().size());
    for (Entity entity : message.entities()) {
      entities.add(entity(entity));
    }
    out.put("entity", entities);
    return out;
  }

  private static Map<String, Object> header(Header header) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("gtfs_realtime_version", header.gtfsRealtimeVersion());
    out.put("timestamp", header.timestamp());
    header.feedVersion().ifPresent(value -> out.put("feed_version", value));
    return out;
  }

  private static Map<String, Object> entity(Entity entity) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", entity.id());
    entity.tripUpdate().ifPresent(value -> out.put("trip_update", tripUpdate(value)));
    entity.vehiclePosition().ifPresent(value -> out.put("vehicle", vehiclePosition(value)));
    entity.alert().ifPresent(value -> out.put("alert", alert(value)));
    return out;
  }

  private static Map<String, Object> tripUpdate(TripUpdate update) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("trip", tripDescriptor(update.trip()));
    List<Map<String, Object>> stopTimes = new ArrayList<>(update.stopTimeUpdates().size());
    for (StopTimeUpdate stopTime : update.stopTimeUpdates()) {
      stopTimes.add(stopTimeUpdate(stopTime));
    }
    out.put("stop_time_update", stopTimes);
    update.vehicle().ifPresent(value -> out.put("vehicle", vehicleDescriptor(value)));
    out.put("timestamp", update.timestamp());
    update.delay().ifPresent(value -> out.put("delay", value));
    return out;
  }

  private static Map<String, Object> tripDescriptor(TripDescriptor descriptor) {
    Map<String, Object> out = new LinkedHashMap<>();
    descriptor.tripId().ifPresent(value -> out.put("trip_id", value));
    descriptor.routeId().ifPresent(value -> out.put("route_id", value));
    descriptor.startTime().ifPresent(value -> out.put("start_time", value));
    descriptor.startDate().ifPresent(value -> out.put("start_date", value));
    out.put("schedule_relationship", descriptor.scheduleRelationship().name());
    return out;
  }

  private static Map<String, Object> stopTimeUpdate(StopTimeUpdate update) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("stop_sequence", update.stopSequence());
    update.arrival().ifPresent(value -> out.put("arrival", stopTimeEvent(value)));
    update.departure().ifPresent(value -> out.put("departure", stopTimeEvent(value)));
    update.stopId().ifPresent(value -> out.put("stop_id", value));
    out.put("schedule_relationship", update.scheduleRelationship().name());
    update
        .assignedStopId()
        .ifPresent(
            value -> {
              Map<String, Object> properties = new LinkedHashMap<>();
              properties.put("assigned_stop_id", value);
              out.put("stop_time_properties", properties);
            });
    return out;
  }

  private static Map<String, Object> stopTimeEvent(StopTimeEvent event) {
    Map<String, Object> out = new LinkedHashMap<>();
    event.delay().ifPresent(value -> out.put("delay", value));
    event.time().ifPresent(value -> out.put("time", value));
    return out;
  }

  private static Map<String, Object> vehiclePosition(VehiclePosition position) {
    Map<String, Object> out = new LinkedHashMap<>();
    position.trip().ifPresent(value -> out.put("trip", tripDescriptor(value)));
    position.vehicle().ifPresent(value -> out.put("vehicle", vehicleDescriptor(value)));
    position.position().ifPresent(value -> out.put("position", geoPosition(value)));
    position.currentStopSequence().ifPresent(value -> out.put("current_stop_sequence", value));
    position.stopId().ifPresent(value -> out.put("stop_id", value));
    out.put("current_status", position.currentStatus().name());
    out.put("timestamp", position.timestamp());
    position.occupancyStatus().ifPresent(value -> out.put("occupancy_status", value.name()));
    position.occupancyPercentage().ifPresent(value -> out.put("occupancy_percentage", value));
    if (!position.carriages().isEmpty()) {
      List<Map<String, Object>> carriages = new ArrayList<>();
      for (CarriageDetails carriage : position.carriages()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", carriage.id());
        carriage.label().ifPresent(value -> row.put("label", value));
        carriage.occupancyStatus().ifPresent(value -> row.put("occupancy_status", value.name()));
        row.put("occupancy_percentage", carriage.occupancyPercentage().orElse(-1));
        row.put("carriage_sequence", carriage.carriageSequence());
        carriages.add(row);
      }
      out.put("multi_carriage_details", carriages);
    }
    return out;
  }

  private static Map<String, Object> geoPosition(Position position) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("latitude", position.latitude());
    out.put("longitude", position.longitude());
    position.bearing().ifPresent(value -> out.put("bearing", value));
    position.speed().ifPresent(value -> out.put("speed", value));
    return out;
  }

  private static Map<String, Object> vehicleDescriptor(VehicleDescriptor descriptor) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", descriptor.id());
    descriptor.label().ifPresent(value -> out.put("label", value));
    return out;
  }

  private static Map<String, Object> alert(Alert alert) {
    Map<String, Object> out = new LinkedHashMap<>();
    List<Map<String, Object>> periods = new ArrayList<>(alert.activePeriods().size());
    for (TimeRange range : alert.activePeriods()) {
      periods.add(timeRange(range));
    }
    out.put("active_period", periods);
    List<Map<String, Object>> selectors = new ArrayList<>(alert.informedEntities().size());
    for (EntitySelector selector : alert.informedEntities()) {
      selectors.add(entitySelector(selector));
    }
    out.put("informed_entity", selectors);
    out.put("cause", alert.cause().name());
    out.put("effect", alert.effect().name());
    alert.headerText().ifPresent(value -> out.put("header_text", translatedString(value)));
    alert
        .descriptionText()
        .ifPresent(value -> out.put("description_text", translatedString(value)));
    out.put("severity_level", alert.severity().name());
    return out;
  }

  private static Map<String, Object> timeRange(TimeRange range) {
    Map<String, Object> out = new LinkedHashMap<>();
    range.start().ifPresent(value -> out.put("start", value));
    range.end().ifPresent(value -> out.put("end", value));
    return out;
  }

  private static Map<String, Object> entitySelector(EntitySelector selector) {
    Map<String, Object> out = new LinkedHashMap<>();
    selector.agencyId().ifPresent(value -> out.put("agency_id", value));
    selector.routeId().ifPresent(value -> out.put("route_id", value));
    selector.stopId().ifPresent(value -> out.put("stop_id", value));
    return out;
  }

  private static Map<String, Object> translatedString(TranslatedString text) {
    Map<String, Object> out = new LinkedHashMap<>();
    List<Map<String, Object>> translations = new ArrayList<>(text.translations().size());
    for (Translation translation : text.translations()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("text", translation.text());
      translation.language().ifPresent(value -> row.put("language", value));
      translations.add(row);
    }
    out.put("translation", translations);
    return out;
  }
}
