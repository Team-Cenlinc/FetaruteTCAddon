package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * GTFS-Realtime 的中间模型：字段与官方 {@code gtfs-realtime.proto} 一一对应，protobuf 编码器与 JSON 调试输出都从这里出发。
 *
 * <p>为什么不直接拼字节：三种实时 feed 共用同一份中间模型，编码器、JSON 调试、单测反解码核对都对着同一个结构；任何一处的字段偏移都只需要改一个地方。
 *
 * <p>缺失的字段一律用 {@link Optional} 表达，与插件其它地方的口径一致。枚举的 {@link #number()} 就是 proto 里的取值，编码器不再另写一份映射。
 */
public final class GtfsRealtimeFeed {

  /** GTFS-Realtime 规范版本。 */
  public static final String VERSION_2 = "2.0";

  private GtfsRealtimeFeed() {}

  /** 一条 FeedMessage：报文头加若干实体。 */
  public record FeedMessage(Header header, List<Entity> entities) {

    public FeedMessage {
      Objects.requireNonNull(header, "header");
      entities = entities == null ? List.of() : List.copyOf(entities);
    }
  }

  /**
   * FeedHeader。
   *
   * @param gtfsRealtimeVersion 规范版本（{@code 2.0}）
   * @param timestamp 生成时刻（epoch 秒）
   * @param feedVersion 对应的静态包版本；中转据此核对静态包与实时源
   */
  public record Header(String gtfsRealtimeVersion, long timestamp, Optional<String> feedVersion) {

    public Header {
      gtfsRealtimeVersion =
          gtfsRealtimeVersion == null || gtfsRealtimeVersion.isBlank()
              ? VERSION_2
              : gtfsRealtimeVersion;
      feedVersion = feedVersion == null ? Optional.empty() : feedVersion;
    }
  }

  /** FeedEntity：一条实体最多带一种内容。 */
  public record Entity(
      String id,
      Optional<TripUpdate> tripUpdate,
      Optional<VehiclePosition> vehiclePosition,
      Optional<Alert> alert) {

    public Entity {
      Objects.requireNonNull(id, "id");
      tripUpdate = tripUpdate == null ? Optional.empty() : tripUpdate;
      vehiclePosition = vehiclePosition == null ? Optional.empty() : vehiclePosition;
      alert = alert == null ? Optional.empty() : alert;
    }
  }

  /** TripUpdate：一趟车次实例的进度与预测。 */
  public record TripUpdate(
      TripDescriptor trip,
      Optional<VehicleDescriptor> vehicle,
      List<StopTimeUpdate> stopTimeUpdates,
      long timestamp,
      OptionalInt delay) {

    public TripUpdate {
      Objects.requireNonNull(trip, "trip");
      vehicle = vehicle == null ? Optional.empty() : vehicle;
      stopTimeUpdates = stopTimeUpdates == null ? List.of() : List.copyOf(stopTimeUpdates);
      delay = delay == null ? OptionalInt.empty() : delay;
    }
  }

  /** TripDescriptor：指向静态包里的车次实例。 */
  public record TripDescriptor(
      Optional<String> tripId,
      Optional<String> routeId,
      Optional<String> startTime,
      Optional<String> startDate,
      TripScheduleRelationship scheduleRelationship) {

    public TripDescriptor {
      tripId = tripId == null ? Optional.empty() : tripId;
      routeId = routeId == null ? Optional.empty() : routeId;
      startTime = startTime == null ? Optional.empty() : startTime;
      startDate = startDate == null ? Optional.empty() : startDate;
      scheduleRelationship =
          scheduleRelationship == null ? TripScheduleRelationship.SCHEDULED : scheduleRelationship;
    }
  }

  /** 车次与静态时刻表的关系。 */
  public enum TripScheduleRelationship {
    /** 按静态时刻表运行（允许晚点）。 */
    SCHEDULED(0),
    /** 按间隔发车、没有表定时刻（{@code frequencies.txt} 的 {@code exact_times=0}）。 */
    UNSCHEDULED(2),
    /** 整趟取消。 */
    CANCELED(3);

    private final int number;

    TripScheduleRelationship(int number) {
      this.number = number;
    }

    /** proto 里的枚举值。 */
    public int number() {
      return number;
    }
  }

  /**
   * 一个停靠点的到达或发车预测。
   *
   * @param delay 相对计划的偏差（秒，正数为晚点）
   * @param time 绝对时刻（epoch 秒）；与 {@code delay} 同时给时以 {@code time} 为准
   */
  public record StopTimeEvent(OptionalInt delay, OptionalLong time) {

    public StopTimeEvent {
      delay = delay == null ? OptionalInt.empty() : delay;
      time = time == null ? OptionalLong.empty() : time;
    }
  }

  /** 一个停靠点的更新。 */
  public record StopTimeUpdate(
      int stopSequence,
      Optional<StopTimeEvent> arrival,
      Optional<StopTimeEvent> departure,
      Optional<String> stopId,
      StopScheduleRelationship scheduleRelationship,
      Optional<String> assignedStopId) {

    public StopTimeUpdate {
      arrival = arrival == null ? Optional.empty() : arrival;
      departure = departure == null ? Optional.empty() : departure;
      stopId = stopId == null ? Optional.empty() : stopId;
      scheduleRelationship =
          scheduleRelationship == null ? StopScheduleRelationship.SCHEDULED : scheduleRelationship;
      assignedStopId = assignedStopId == null ? Optional.empty() : assignedStopId;
    }
  }

  /** 停靠点与静态时刻表的关系。 */
  public enum StopScheduleRelationship {
    /** 按计划停靠。 */
    SCHEDULED(0),
    /** 不停（越站、取消）。 */
    SKIPPED(1),
    /** 没有时刻数据（只给站台分配时用）。 */
    NO_DATA(2);

    private final int number;

    StopScheduleRelationship(int number) {
      this.number = number;
    }

    /** proto 里的枚举值。 */
    public int number() {
      return number;
    }
  }

  /**
   * VehiclePosition：一列车此刻的位置与载客。
   *
   * @param carriages 各节车的载客（{@code multi_carriage_details}，车头在前）；读不到车辆模型时为空
   */
  public record VehiclePosition(
      Optional<TripDescriptor> trip,
      Optional<VehicleDescriptor> vehicle,
      Optional<Position> position,
      OptionalInt currentStopSequence,
      Optional<String> stopId,
      VehicleStopStatus currentStatus,
      long timestamp,
      Optional<OccupancyStatus> occupancyStatus,
      OptionalInt occupancyPercentage,
      List<CarriageDetails> carriages) {

    /** 不带各节车载客。 */
    public VehiclePosition(
        Optional<TripDescriptor> trip,
        Optional<VehicleDescriptor> vehicle,
        Optional<Position> position,
        OptionalInt currentStopSequence,
        Optional<String> stopId,
        VehicleStopStatus currentStatus,
        long timestamp,
        Optional<OccupancyStatus> occupancyStatus,
        OptionalInt occupancyPercentage) {
      this(
          trip,
          vehicle,
          position,
          currentStopSequence,
          stopId,
          currentStatus,
          timestamp,
          occupancyStatus,
          occupancyPercentage,
          List.of());
    }

    public VehiclePosition {
      trip = trip == null ? Optional.empty() : trip;
      vehicle = vehicle == null ? Optional.empty() : vehicle;
      position = position == null ? Optional.empty() : position;
      currentStopSequence = currentStopSequence == null ? OptionalInt.empty() : currentStopSequence;
      stopId = stopId == null ? Optional.empty() : stopId;
      currentStatus = currentStatus == null ? VehicleStopStatus.IN_TRANSIT_TO : currentStatus;
      occupancyStatus = occupancyStatus == null ? Optional.empty() : occupancyStatus;
      occupancyPercentage = occupancyPercentage == null ? OptionalInt.empty() : occupancyPercentage;
      carriages = carriages == null ? List.of() : List.copyOf(carriages);
    }
  }

  /**
   * 一节车的载客（{@code CarriageDetails}）。
   *
   * @param id 车厢 ID：{@code 列车名#节序}，同一列车里唯一
   * @param label 给乘客看的车厢号（节序）
   * @param occupancyStatus 载客状态；这节车没有座位时为空
   * @param occupancyPercentage 在座占座位的百分比；这节车没有座位时为空（编码时写规范的 -1）
   * @param carriageSequence 节序，车头为 1
   */
  public record CarriageDetails(
      String id,
      Optional<String> label,
      Optional<OccupancyStatus> occupancyStatus,
      OptionalInt occupancyPercentage,
      int carriageSequence) {

    public CarriageDetails {
      Objects.requireNonNull(id, "id");
      label = label == null ? Optional.empty() : label;
      occupancyStatus = occupancyStatus == null ? Optional.empty() : occupancyStatus;
      occupancyPercentage = occupancyPercentage == null ? OptionalInt.empty() : occupancyPercentage;
      if (carriageSequence < 1) {
        throw new IllegalArgumentException("carriageSequence 从 1 起: " + carriageSequence);
      }
    }
  }

  /** 车辆相对当前站的状态。 */
  public enum VehicleStopStatus {
    /** 即将到达当前站。 */
    INCOMING_AT(0),
    /** 正停在当前站。 */
    STOPPED_AT(1),
    /** 已离开上一站，正在前往当前站。 */
    IN_TRANSIT_TO(2);

    private final int number;

    VehicleStopStatus(int number) {
      this.number = number;
    }

    /** proto 里的枚举值。 */
    public int number() {
      return number;
    }
  }

  /** 载客率。 */
  public enum OccupancyStatus {
    /** 空车。 */
    EMPTY(0),
    /** 空位很多。 */
    MANY_SEATS_AVAILABLE(1),
    /** 空位不多。 */
    FEW_SEATS_AVAILABLE(2),
    /** 只剩站位。 */
    STANDING_ROOM_ONLY(3),
    /** 站位也很挤。 */
    CRUSHED_STANDING_ROOM_ONLY(4),
    /** 满员，仍可上客。 */
    FULL(5),
    /** 不载客。 */
    NOT_ACCEPTING_PASSENGERS(6),
    /** 没有载客数据。 */
    NO_DATA_AVAILABLE(7),
    /** 不可上车。 */
    NOT_BOARDABLE(8);

    private final int number;

    OccupancyStatus(int number) {
      this.number = number;
    }

    /** proto 里的枚举值。 */
    public int number() {
      return number;
    }
  }

  /**
   * 车辆位置。
   *
   * @param latitude 纬度（度）
   * @param longitude 经度（度）
   * @param bearing 朝向（度，正北顺时针）；采样不到时为空
   * @param speed 速度（米/秒）；采样不到时为空
   */
  public record Position(
      double latitude, double longitude, OptionalDouble bearing, OptionalDouble speed) {

    public Position {
      bearing = bearing == null ? OptionalDouble.empty() : bearing;
      speed = speed == null ? OptionalDouble.empty() : speed;
    }
  }

  /** 车辆标识。 */
  public record VehicleDescriptor(String id, Optional<String> label) {

    public VehicleDescriptor {
      Objects.requireNonNull(id, "id");
      label = label == null ? Optional.empty() : label;
    }
  }

  /** ServiceAlerts：一条服务告警。 */
  public record Alert(
      List<TimeRange> activePeriods,
      List<EntitySelector> informedEntities,
      AlertCause cause,
      AlertEffect effect,
      Optional<TranslatedString> headerText,
      Optional<TranslatedString> descriptionText,
      AlertSeverity severity) {

    public Alert {
      activePeriods = activePeriods == null ? List.of() : List.copyOf(activePeriods);
      informedEntities = informedEntities == null ? List.of() : List.copyOf(informedEntities);
      cause = cause == null ? AlertCause.UNKNOWN_CAUSE : cause;
      effect = effect == null ? AlertEffect.UNKNOWN_EFFECT : effect;
      headerText = headerText == null ? Optional.empty() : headerText;
      descriptionText = descriptionText == null ? Optional.empty() : descriptionText;
      severity = severity == null ? AlertSeverity.UNKNOWN_SEVERITY : severity;
    }
  }

  /** 告警原因。 */
  public enum AlertCause {
    UNKNOWN_CAUSE(1),
    OTHER_CAUSE(2),
    MAINTENANCE(9);

    private final int number;

    AlertCause(int number) {
      this.number = number;
    }

    /** proto 里的枚举值。 */
    public int number() {
      return number;
    }
  }

  /** 告警影响。 */
  public enum AlertEffect {
    NO_SERVICE(1),
    REDUCED_SERVICE(2),
    SIGNIFICANT_DELAYS(3),
    UNKNOWN_EFFECT(8);

    private final int number;

    AlertEffect(int number) {
      this.number = number;
    }

    /** proto 里的枚举值。 */
    public int number() {
      return number;
    }
  }

  /** 告警严重程度。 */
  public enum AlertSeverity {
    UNKNOWN_SEVERITY(1),
    INFO(2),
    WARNING(3),
    SEVERE(4);

    private final int number;

    AlertSeverity(int number) {
      this.number = number;
    }

    /** proto 里的枚举值。 */
    public int number() {
      return number;
    }
  }

  /** 告警生效时段；两端都可省略（省略即不限）。 */
  public record TimeRange(OptionalLong start, OptionalLong end) {

    public TimeRange {
      start = start == null ? OptionalLong.empty() : start;
      end = end == null ? OptionalLong.empty() : end;
    }
  }

  /**
   * 告警作用对象。
   *
   * <p>多个 {@link EntitySelector} 之间是并集；同一个选择器里写了几个字段则是同时满足。因此「只影响某条线路」要写 {@code (agency,
   * route)}，不能把运营商选择器单独放进去（那会变成全网告警）。
   */
  public record EntitySelector(
      Optional<String> agencyId, Optional<String> routeId, Optional<String> stopId) {

    public EntitySelector {
      agencyId = agencyId == null ? Optional.empty() : agencyId;
      routeId = routeId == null ? Optional.empty() : routeId;
      stopId = stopId == null ? Optional.empty() : stopId;
    }
  }

  /** 多语言文本。 */
  public record TranslatedString(List<Translation> translations) {

    public TranslatedString {
      translations = translations == null ? List.of() : List.copyOf(translations);
      if (translations.isEmpty()) {
        throw new IllegalArgumentException("TranslatedString 至少要有一句");
      }
    }

    /** 单句、不标语言。 */
    public static TranslatedString of(String text) {
      return new TranslatedString(List.of(new Translation(text, Optional.empty())));
    }
  }

  /**
   * 一句译文。
   *
   * @param text 文本
   * @param language BCP-47 语言代码；未知时为空
   */
  public record Translation(String text, Optional<String> language) {

    public Translation {
      text = text == null ? "" : text;
      language = language == null ? Optional.empty() : language;
    }
  }
}
