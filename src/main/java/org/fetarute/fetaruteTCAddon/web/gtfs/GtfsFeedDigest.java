package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

/**
 * 静态包的内容摘要：{@code feed_version} 与 GTFS-Realtime 的 {@code FeedHeader.feed_version}
 * 用同一个值，中转据此核对静态包与实时源是不是同一版。
 *
 * <p>摘要只覆盖内容：日历起止日期与生成时刻每天都在变，不进摘要；重新发布时刻表、改车站、改线路名才会换版本。这也让「同一份内容」在 300 秒缓存刷新、日期滚动时
 * 保持同一个版本号，消费方复验不会无故失效。
 */
final class GtfsFeedDigest {

  private GtfsFeedDigest() {}

  /**
   * 算版本号：{@code {S}-{摘要前 12 位}}；没配服务器 ID 时只有摘要。
   *
   * <p>版本号里不放日期：内容没变时隔天也是同一个版本，与类注释“只覆盖内容”一致。
   *
   * @param ids ID 规则（提供服务器前缀）
   * @param zone 服务器时区
   * @param serviceId 日历服务 ID
   * @param publisherName 发布方名称
   * @param publisherUrl 发布方地址
   * @param agencies 运营商
   * @param routes 线路
   * @param stops 停靠点
   * @param trips 车次
   * @param transfers 换乘
   */
  static String feedVersion(
      GtfsIds ids,
      ZoneId zone,
      String serviceId,
      String publisherName,
      String publisherUrl,
      List<GtfsFeed.Agency> agencies,
      List<GtfsFeed.Route> routes,
      List<GtfsFeed.Stop> stops,
      List<GtfsFeed.Trip> trips,
      List<GtfsFeed.Transfer> transfers) {
    StringBuilder canonical = new StringBuilder();
    field(canonical, ids.prefix());
    field(canonical, zone.getId());
    field(canonical, serviceId);
    field(canonical, publisherName);
    field(canonical, publisherUrl);
    for (GtfsFeed.Agency agency : agencies) {
      field(canonical, "agency");
      field(canonical, agency.id());
      field(canonical, agency.operatorCode());
      field(canonical, agency.name());
      field(canonical, agency.secondaryName().orElse(""));
      field(canonical, agency.url());
      field(canonical, agency.timezone());
      field(canonical, agency.lang());
    }
    for (GtfsFeed.Route route : routes) {
      field(canonical, "route");
      field(canonical, route.id());
      field(canonical, route.agencyId());
      field(canonical, route.operatorCode());
      field(canonical, route.lineCode());
      field(canonical, route.shortName());
      field(canonical, route.longName());
      field(canonical, route.secondaryName().orElse(""));
      field(canonical, Integer.toString(route.routeType()));
      field(canonical, route.color().orElse(""));
      field(canonical, route.textColor().orElse(""));
      field(canonical, Integer.toString(route.sortOrder()));
    }
    for (GtfsFeed.Stop stop : stops) {
      field(canonical, "stop");
      field(canonical, stop.id());
      field(canonical, stop.name());
      field(canonical, stop.secondaryName().orElse(""));
      field(canonical, GtfsGeo.format(stop.latitude()));
      field(canonical, GtfsGeo.format(stop.longitude()));
      field(canonical, Integer.toString(stop.locationType()));
      field(canonical, stop.parentStation().orElse(""));
      field(canonical, stop.platformCode().orElse(""));
    }
    for (GtfsFeed.Trip trip : trips) {
      field(canonical, "trip");
      field(canonical, trip.id());
      field(canonical, trip.routeId());
      field(canonical, trip.serviceId());
      field(canonical, trip.shortName());
      field(canonical, trip.headsign());
      field(canonical, trip.headsignSecondary().orElse(""));
      field(canonical, Integer.toString(trip.startSecondOfDay()));
      field(canonical, trip.timetableId().toString());
      field(canonical, trip.timetableCode());
      field(canonical, trip.tripCode());
      field(canonical, trip.tripUuid().toString());
      field(canonical, Integer.toString(trip.segment()));
      field(canonical, Integer.toString(trip.firstStopSequence()));
      field(canonical, Integer.toString(trip.lastStopSequence()));
      for (GtfsFeed.StopTime stopTime : trip.stopTimes()) {
        field(canonical, "stop_time");
        field(canonical, Integer.toString(stopTime.stopSequence()));
        field(canonical, stopTime.stopId());
        field(canonical, Integer.toString(stopTime.arrivalSecondOfDay()));
        field(canonical, Integer.toString(stopTime.departureSecondOfDay()));
        field(canonical, Integer.toString(stopTime.pickupType()));
        field(canonical, Integer.toString(stopTime.dropOffType()));
      }
    }
    for (GtfsFeed.Transfer transfer : transfers) {
      field(canonical, "transfer");
      field(canonical, transfer.fromStopId());
      field(canonical, transfer.toStopId());
      field(canonical, Integer.toString(transfer.type()));
      field(
          canonical,
          transfer.minTransferSeconds().isPresent()
              ? Integer.toString(transfer.minTransferSeconds().getAsInt())
              : "");
      field(canonical, transfer.fromTripId().orElse(""));
      field(canonical, transfer.toTripId().orElse(""));
    }
    String digest = shortDigest(canonical.toString());
    String prefix = ids.prefix();
    return prefix.isEmpty() ? digest : prefix.substring(0, prefix.length() - 1) + "-" + digest;
  }

  /** 字段写成「长度:内容;」：名字里出现任何分隔符都不会让两份不同的内容拼成同一个串。 */
  private static void field(StringBuilder out, String value) {
    String text = value == null ? "" : value;
    out.append(text.length()).append(':').append(text).append(';');
  }

  private static String shortDigest(String canonical) {
    byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      return HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException ex) {
      return Integer.toHexString(java.util.Arrays.hashCode(bytes));
    }
  }
}
