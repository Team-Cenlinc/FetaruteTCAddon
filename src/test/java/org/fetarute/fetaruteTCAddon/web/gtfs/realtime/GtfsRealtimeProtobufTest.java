package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.transit.realtime.GtfsRealtime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Alert;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.AlertCause;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.AlertEffect;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.AlertSeverity;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Entity;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.EntitySelector;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.FeedMessage;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Header;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.OccupancyStatus;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Position;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.StopScheduleRelationship;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.StopTimeEvent;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.StopTimeUpdate;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TimeRange;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TranslatedString;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Translation;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TripDescriptor;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TripScheduleRelationship;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TripUpdate;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.VehicleDescriptor;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.VehiclePosition;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.VehicleStopStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("手写 GTFS-Realtime protobuf 编码器")
class GtfsRealtimeProtobufTest {

  private static final long TIMESTAMP = 1_790_000_000L;

  @Test
  @DisplayName("TripUpdate：车次身份、负偏差、绝对时刻、动态选台、越站与取消用官方绑定反解码都对得上")
  void encodesTripUpdates() throws Exception {
    GtfsRealtimeFeed.FeedMessage message =
        message(
            new Entity(
                "tu:1",
                Optional.of(
                    new TripUpdate(
                        new TripDescriptor(
                            Optional.of("sv:SURC:WS:WS_TEMP:WS-001"),
                            Optional.empty(),
                            Optional.of("23:50:00"),
                            Optional.of("20261025"),
                            TripScheduleRelationship.SCHEDULED),
                        Optional.of(new VehicleDescriptor("TRAIN-1", Optional.of("WS-001"))),
                        List.of(
                            new StopTimeUpdate(
                                2,
                                Optional.of(
                                    new StopTimeEvent(
                                        OptionalInt.empty(), OptionalLong.of(1_790_000_600L))),
                                Optional.of(
                                    new StopTimeEvent(OptionalInt.of(-30), OptionalLong.empty())),
                                Optional.of("sv:SURC:S:HHU:1"),
                                StopScheduleRelationship.SCHEDULED,
                                Optional.of("sv:SURC:S:HHU:2")),
                            new StopTimeUpdate(
                                3,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("sv:SURC:S:LDF:1"),
                                StopScheduleRelationship.SKIPPED,
                                Optional.empty()),
                            new StopTimeUpdate(
                                4,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                StopScheduleRelationship.NO_DATA,
                                Optional.of("sv:SURC:S:LDF:2"))),
                        TIMESTAMP,
                        OptionalInt.of(45))),
                Optional.empty(),
                Optional.empty()),
            new Entity(
                "tu:cancel",
                Optional.of(
                    new TripUpdate(
                        new TripDescriptor(
                            Optional.of("sv:SURC:WS:WS_TEMP:WS-090"),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of("20261025"),
                            TripScheduleRelationship.CANCELED),
                        Optional.empty(),
                        List.of(),
                        TIMESTAMP,
                        OptionalInt.empty())),
                Optional.empty(),
                Optional.empty()));

    GtfsRealtime.FeedMessage decoded =
        GtfsRealtime.FeedMessage.parseFrom(GtfsRealtimeProtobuf.encode(message));

    assertEquals("2.0", decoded.getHeader().getGtfsRealtimeVersion());
    assertEquals(TIMESTAMP, decoded.getHeader().getTimestamp());
    assertEquals("sv-20261025-0123abcd", decoded.getHeader().getFeedVersion());
    assertEquals(2, decoded.getEntityCount());

    GtfsRealtime.TripUpdate update = decoded.getEntity(0).getTripUpdate();
    assertEquals("sv:SURC:WS:WS_TEMP:WS-001", update.getTrip().getTripId());
    assertEquals("23:50:00", update.getTrip().getStartTime());
    assertEquals("20261025", update.getTrip().getStartDate());
    assertEquals(
        GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED,
        update.getTrip().getScheduleRelationship());
    assertEquals("TRAIN-1", update.getVehicle().getId());
    assertEquals("WS-001", update.getVehicle().getLabel());
    assertEquals(45, update.getDelay());
    assertEquals(3, update.getStopTimeUpdateCount());

    GtfsRealtime.TripUpdate.StopTimeUpdate first = update.getStopTimeUpdate(0);
    assertEquals(2, first.getStopSequence());
    assertEquals(1_790_000_600L, first.getArrival().getTime());
    assertEquals(-30, first.getDeparture().getDelay());
    assertEquals("sv:SURC:S:HHU:1", first.getStopId());
    assertEquals("sv:SURC:S:HHU:2", first.getStopTimeProperties().getAssignedStopId());
    assertEquals(
        GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SCHEDULED,
        first.getScheduleRelationship());
    assertEquals(
        GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED,
        update.getStopTimeUpdate(1).getScheduleRelationship());
    assertEquals("sv:SURC:S:LDF:1", update.getStopTimeUpdate(1).getStopId());
    GtfsRealtime.TripUpdate.StopTimeUpdate assignedOnly = update.getStopTimeUpdate(2);
    assertEquals(
        GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA,
        assignedOnly.getScheduleRelationship());
    assertEquals("sv:SURC:S:LDF:2", assignedOnly.getStopTimeProperties().getAssignedStopId());
    assertTrue(!assignedOnly.hasStopId(), "换台时只写 assigned_stop_id");

    GtfsRealtime.TripUpdate cancelled = decoded.getEntity(1).getTripUpdate();
    assertEquals(
        GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED,
        cancelled.getTrip().getScheduleRelationship());
  }

  @Test
  @DisplayName("VehiclePosition：坐标、朝向、速度、停站状态与载客率按 proto 字段号编码")
  void encodesVehiclePositions() throws Exception {
    GtfsRealtimeFeed.FeedMessage message =
        message(
            new Entity(
                "vp:TRAIN-2",
                Optional.empty(),
                Optional.of(
                    new VehiclePosition(
                        Optional.empty(),
                        Optional.of(new VehicleDescriptor("TRAIN-2", Optional.empty())),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        VehicleStopStatus.INCOMING_AT,
                        TIMESTAMP,
                        Optional.empty(),
                        OptionalInt.empty())),
                Optional.empty()),
            new Entity(
                "vp:TRAIN-1",
                Optional.empty(),
                Optional.of(
                    new VehiclePosition(
                        Optional.of(
                            new TripDescriptor(
                                Optional.of("sv:SURC:WS:WS_TEMP:WS-001"),
                                Optional.empty(),
                                Optional.of("23:50:00"),
                                Optional.of("20261025"),
                                TripScheduleRelationship.SCHEDULED)),
                        Optional.of(new VehicleDescriptor("TRAIN-1", Optional.of("WS-001"))),
                        Optional.of(
                            new Position(
                                22.5, 114.0, OptionalDouble.of(90.0), OptionalDouble.of(7.5))),
                        OptionalInt.of(2),
                        Optional.of("sv:SURC:S:HHU:1"),
                        VehicleStopStatus.STOPPED_AT,
                        TIMESTAMP,
                        Optional.of(OccupancyStatus.FULL),
                        OptionalInt.of(100))),
                Optional.empty()));

    GtfsRealtime.FeedMessage decoded =
        GtfsRealtime.FeedMessage.parseFrom(GtfsRealtimeProtobuf.encode(message));
    GtfsRealtime.VehiclePosition position = vehicle(decoded, "vp:TRAIN-1");

    assertEquals("sv:SURC:WS:WS_TEMP:WS-001", position.getTrip().getTripId());
    assertEquals(22.5, position.getPosition().getLatitude(), 1e-4);
    assertEquals(114.0, position.getPosition().getLongitude(), 1e-4);
    assertEquals(90.0, position.getPosition().getBearing(), 1e-4);
    assertEquals(7.5, position.getPosition().getSpeed(), 1e-4);
    assertEquals(2, position.getCurrentStopSequence());
    assertEquals("sv:SURC:S:HHU:1", position.getStopId());
    assertEquals(
        GtfsRealtime.VehiclePosition.VehicleStopStatus.STOPPED_AT, position.getCurrentStatus());
    assertEquals(GtfsRealtime.VehiclePosition.OccupancyStatus.FULL, position.getOccupancyStatus());
    assertEquals(100, position.getOccupancyPercentage());
    assertEquals("TRAIN-1", position.getVehicle().getId());
    assertEquals(
        GtfsRealtime.VehiclePosition.VehicleStopStatus.INCOMING_AT,
        vehicle(decoded, "vp:TRAIN-2").getCurrentStatus(),
        "INCOMING_AT 是枚举 0，必须显式编码，否则会退回默认的 IN_TRANSIT_TO");
  }

  @Test
  @DisplayName("Alert：时段、多语言文本、作用对象与严重程度按 proto 字段号编码")
  void encodesAlerts() throws Exception {
    GtfsRealtimeFeed.FeedMessage message =
        message(
            new Entity(
                "alert:1",
                Optional.empty(),
                Optional.empty(),
                Optional.of(
                    new Alert(
                        List.of(
                            new TimeRange(
                                OptionalLong.of(1_789_000_000L), OptionalLong.of(1_791_000_000L))),
                        List.of(
                            new EntitySelector(
                                Optional.of("sv:SURC"),
                                Optional.of("sv:SURC:WS"),
                                Optional.empty()),
                            new EntitySelector(
                                Optional.of("sv:SURC"),
                                Optional.empty(),
                                Optional.of("sv:SURC:S:HHU"))),
                        AlertCause.MAINTENANCE,
                        AlertEffect.NO_SERVICE,
                        Optional.of(
                            new TranslatedString(
                                List.of(
                                    new Translation("西线停运", Optional.of("zh")),
                                    new Translation("West Line suspended", Optional.of("en"))))),
                        Optional.of(TranslatedString.of("线路检修")),
                        AlertSeverity.WARNING))));

    GtfsRealtime.FeedMessage decoded =
        GtfsRealtime.FeedMessage.parseFrom(GtfsRealtimeProtobuf.encode(message));
    GtfsRealtime.Alert alert = decoded.getEntity(0).getAlert();

    assertEquals(1, alert.getActivePeriodCount());
    assertEquals(1_789_000_000L, alert.getActivePeriod(0).getStart());
    assertEquals(1_791_000_000L, alert.getActivePeriod(0).getEnd());
    assertEquals(2, alert.getInformedEntityCount());
    assertEquals("sv:SURC", alert.getInformedEntity(0).getAgencyId());
    assertEquals("sv:SURC:WS", alert.getInformedEntity(0).getRouteId());
    assertEquals("sv:SURC:S:HHU", alert.getInformedEntity(1).getStopId());
    assertEquals(GtfsRealtime.Alert.Cause.MAINTENANCE, alert.getCause());
    assertEquals(GtfsRealtime.Alert.Effect.NO_SERVICE, alert.getEffect());
    assertEquals(GtfsRealtime.Alert.SeverityLevel.WARNING, alert.getSeverityLevel());
    assertEquals(2, alert.getHeaderText().getTranslationCount());
    assertEquals("zh", alert.getHeaderText().getTranslation(0).getLanguage());
    assertEquals("西线停运", alert.getHeaderText().getTranslation(0).getText());
    assertEquals("en", alert.getHeaderText().getTranslation(1).getLanguage());
    assertEquals("线路检修", alert.getDescriptionText().getTranslation(0).getText());
  }

  @Test
  @DisplayName("算 ETag 的实体负载只含实体：同一份实体两次编码逐字节一致")
  void entityPayloadIsStable() {
    Entity entity =
        new Entity(
            "vp:TRAIN-1",
            Optional.empty(),
            Optional.of(
                new VehiclePosition(
                    Optional.empty(),
                    Optional.of(new VehicleDescriptor("TRAIN-1", Optional.empty())),
                    Optional.empty(),
                    OptionalInt.empty(),
                    Optional.empty(),
                    VehicleStopStatus.IN_TRANSIT_TO,
                    TIMESTAMP,
                    Optional.empty(),
                    OptionalInt.empty())),
            Optional.empty());

    byte[] first = GtfsRealtimeProtobuf.entitiesPayload(List.of(entity));
    byte[] second = GtfsRealtimeProtobuf.entitiesPayload(List.of(entity));

    assertTrue(first.length > 0);
    assertTrue(hasSameBytes(first, second));
  }

  private static GtfsRealtime.VehiclePosition vehicle(GtfsRealtime.FeedMessage message, String id) {
    for (GtfsRealtime.FeedEntity entity : message.getEntityList()) {
      if (entity.getId().equals(id)) {
        return entity.getVehicle();
      }
    }
    throw new AssertionError("没有车辆实体 " + id);
  }

  private static boolean hasSameBytes(byte[] a, byte[] b) {
    return java.util.Arrays.equals(a, b);
  }

  private static GtfsRealtimeFeed.FeedMessage message(Entity... entities) {
    return new FeedMessage(
        new Header("2.0", TIMESTAMP, Optional.of("sv-20261025-0123abcd")), List.of(entities));
  }
}
