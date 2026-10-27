package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 手写的 GTFS-Realtime protobuf 编码器。
 *
 * <p>线格式只用到三种：varint、长度前缀、float32/double；整份协议十几种消息，自己编码比打包官方绑定（protobuf-java 与生成代码，约 2 MB，还带着 2021
 * 年的 protobuf-java 版本）干净得多，插件 jar 不增加任何运行时依赖。字段号以官方 {@code gtfs-realtime.proto} 为准。
 *
 * <p>只写非缺省字段：proto2 的 {@code optional} 字段没写就是缺省值，读取方拿到的结果与显式写缺省值相同。字符串一律 UTF-8。
 *
 * <p>单测用只在测试期引入的官方绑定反解码核对；打出来的插件 jar 不带它。
 */
public final class GtfsRealtimeProtobuf {

  private static final int WIRE_VARINT = 0;

  private static final int WIRE_LENGTH_DELIMITED = 2;

  private static final int WIRE_FIXED32 = 5;

  private GtfsRealtimeProtobuf() {}

  /**
   * 编码一条 {@code FeedMessage}。
   *
   * @param message 报文
   */
  public static byte[] encode(FeedMessage message) {
    Writer out = new Writer();
    out.message(1, header(message.header()));
    for (Entity entity : message.entities()) {
      out.message(2, entity(entity));
    }
    return out.toByteArray();
  }

  /**
   * 只编码实体（不含报文头），供计算 ETag：报文头里的时间戳每次生成都会变，不代表内容变了。
   *
   * @param entities 实体
   */
  static byte[] entitiesPayload(List<Entity> entities) {
    Writer out = new Writer();
    for (Entity entity : entities) {
      byte[] encoded = entity(entity);
      out.rawBytes(encoded);
    }
    return out.toByteArray();
  }

  private static byte[] header(Header header) {
    Writer out = new Writer();
    out.string(1, header.gtfsRealtimeVersion());
    out.uint64(3, header.timestamp());
    header.feedVersion().ifPresent(value -> out.string(4, value));
    return out.toByteArray();
  }

  private static byte[] entity(Entity entity) {
    Writer out = new Writer();
    out.string(1, entity.id());
    entity.tripUpdate().ifPresent(value -> out.message(3, tripUpdate(value)));
    entity.vehiclePosition().ifPresent(value -> out.message(4, vehiclePosition(value)));
    entity.alert().ifPresent(value -> out.message(5, alert(value)));
    return out.toByteArray();
  }

  private static byte[] tripUpdate(TripUpdate update) {
    Writer out = new Writer();
    out.message(1, tripDescriptor(update.trip()));
    for (StopTimeUpdate stopTimeUpdate : update.stopTimeUpdates()) {
      out.message(2, stopTimeUpdate(stopTimeUpdate));
    }
    update.vehicle().ifPresent(value -> out.message(3, vehicleDescriptor(value)));
    out.uint64(4, update.timestamp());
    if (update.delay().isPresent()) {
      out.int32(5, update.delay().getAsInt());
    }
    return out.toByteArray();
  }

  private static byte[] tripDescriptor(TripDescriptor descriptor) {
    Writer out = new Writer();
    descriptor.tripId().ifPresent(value -> out.string(1, value));
    descriptor.startTime().ifPresent(value -> out.string(2, value));
    descriptor.startDate().ifPresent(value -> out.string(3, value));
    if (descriptor.scheduleRelationship().number() != 0) {
      out.enumValue(4, descriptor.scheduleRelationship().number());
    }
    descriptor.routeId().ifPresent(value -> out.string(5, value));
    return out.toByteArray();
  }

  private static byte[] stopTimeUpdate(StopTimeUpdate update) {
    Writer out = new Writer();
    out.uint32(1, update.stopSequence());
    update.arrival().ifPresent(value -> out.message(2, stopTimeEvent(value)));
    update.departure().ifPresent(value -> out.message(3, stopTimeEvent(value)));
    update.stopId().ifPresent(value -> out.string(4, value));
    if (update.scheduleRelationship().number() != 0) {
      out.enumValue(5, update.scheduleRelationship().number());
    }
    update
        .assignedStopId()
        .ifPresent(
            value -> {
              Writer properties = new Writer();
              properties.string(1, value);
              out.message(6, properties.toByteArray());
            });
    return out.toByteArray();
  }

  private static byte[] stopTimeEvent(StopTimeEvent event) {
    Writer out = new Writer();
    event.delay().ifPresent(value -> out.int32(1, value));
    event.time().ifPresent(value -> out.int64(2, value));
    return out.toByteArray();
  }

  private static byte[] vehiclePosition(VehiclePosition position) {
    Writer out = new Writer();
    position.trip().ifPresent(value -> out.message(1, tripDescriptor(value)));
    position.position().ifPresent(value -> out.message(2, geoPosition(value)));
    if (position.currentStopSequence().isPresent()) {
      out.uint32(3, position.currentStopSequence().getAsInt());
    }
    out.enumValue(4, position.currentStatus().number());
    out.uint64(5, position.timestamp());
    position.stopId().ifPresent(value -> out.string(7, value));
    position.vehicle().ifPresent(value -> out.message(8, vehicleDescriptor(value)));
    position.occupancyStatus().ifPresent(value -> out.enumValue(9, value.number()));
    if (position.occupancyPercentage().isPresent()) {
      out.uint32(10, position.occupancyPercentage().getAsInt());
    }
    for (CarriageDetails carriage : position.carriages()) {
      out.message(11, carriage(carriage));
    }
    return out.toByteArray();
  }

  private static byte[] carriage(CarriageDetails carriage) {
    Writer out = new Writer();
    out.string(1, carriage.id());
    carriage.label().ifPresent(value -> out.string(2, value));
    carriage.occupancyStatus().ifPresent(value -> out.enumValue(3, value.number()));
    // 规范：没有数据时为 -1（int32，负数按 10 字节 varint）
    out.int32(4, carriage.occupancyPercentage().orElse(-1));
    out.uint32(5, carriage.carriageSequence());
    return out.toByteArray();
  }

  private static byte[] geoPosition(Position position) {
    Writer out = new Writer();
    out.float32(1, (float) position.latitude());
    out.float32(2, (float) position.longitude());
    if (position.bearing().isPresent()) {
      out.float32(3, (float) position.bearing().getAsDouble());
    }
    if (position.speed().isPresent()) {
      out.float32(5, (float) position.speed().getAsDouble());
    }
    return out.toByteArray();
  }

  private static byte[] vehicleDescriptor(VehicleDescriptor descriptor) {
    Writer out = new Writer();
    out.string(1, descriptor.id());
    descriptor.label().ifPresent(value -> out.string(2, value));
    return out.toByteArray();
  }

  private static byte[] alert(Alert alert) {
    Writer out = new Writer();
    for (TimeRange range : alert.activePeriods()) {
      out.message(1, timeRange(range));
    }
    for (EntitySelector selector : alert.informedEntities()) {
      out.message(5, entitySelector(selector));
    }
    out.enumValue(6, alert.cause().number());
    out.enumValue(7, alert.effect().number());
    alert.headerText().ifPresent(value -> out.message(10, translatedString(value)));
    alert.descriptionText().ifPresent(value -> out.message(11, translatedString(value)));
    out.enumValue(14, alert.severity().number());
    return out.toByteArray();
  }

  private static byte[] timeRange(TimeRange range) {
    Writer out = new Writer();
    range.start().ifPresent(value -> out.uint64(1, value));
    range.end().ifPresent(value -> out.uint64(2, value));
    return out.toByteArray();
  }

  private static byte[] entitySelector(EntitySelector selector) {
    Writer out = new Writer();
    selector.agencyId().ifPresent(value -> out.string(1, value));
    selector.routeId().ifPresent(value -> out.string(2, value));
    selector.stopId().ifPresent(value -> out.string(5, value));
    return out.toByteArray();
  }

  private static byte[] translatedString(TranslatedString text) {
    Writer out = new Writer();
    for (Translation translation : text.translations()) {
      Writer item = new Writer();
      item.string(1, translation.text());
      translation.language().ifPresent(value -> item.string(2, value));
      out.message(1, item.toByteArray());
    }
    return out.toByteArray();
  }

  /** 长度前缀的 proto 线格式写出器。 */
  private static final class Writer {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    byte[] toByteArray() {
      return out.toByteArray();
    }

    void rawBytes(byte[] value) {
      out.writeBytes(value);
    }

    /** 无符号 varint（负数按 64 位补码编码成 10 字节，与 proto 的 int32/int64 一致）。 */
    void varint(long value) {
      long remaining = value;
      while ((remaining & ~0x7FL) != 0L) {
        out.write((int) (remaining & 0x7F) | 0x80);
        remaining >>>= 7;
      }
      out.write((int) remaining);
    }

    void uint32(int field, int value) {
      tag(field, WIRE_VARINT);
      varint(Integer.toUnsignedLong(value));
    }

    void uint64(int field, long value) {
      tag(field, WIRE_VARINT);
      varint(value);
    }

    void int32(int field, int value) {
      tag(field, WIRE_VARINT);
      varint(value);
    }

    void int64(int field, long value) {
      tag(field, WIRE_VARINT);
      varint(value);
    }

    void enumValue(int field, int value) {
      tag(field, WIRE_VARINT);
      varint(value);
    }

    void string(int field, String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      tag(field, WIRE_LENGTH_DELIMITED);
      varint(bytes.length);
      out.writeBytes(bytes);
    }

    void message(int field, byte[] payload) {
      tag(field, WIRE_LENGTH_DELIMITED);
      varint(payload.length);
      out.writeBytes(payload);
    }

    void float32(int field, float value) {
      tag(field, WIRE_FIXED32);
      int bits = Float.floatToRawIntBits(value);
      out.write(bits & 0xFF);
      out.write(bits >>> 8 & 0xFF);
      out.write(bits >>> 16 & 0xFF);
      out.write(bits >>> 24 & 0xFF);
    }

    private void tag(int field, int wireType) {
      varint(((long) field << 3) | wireType);
    }
  }
}
