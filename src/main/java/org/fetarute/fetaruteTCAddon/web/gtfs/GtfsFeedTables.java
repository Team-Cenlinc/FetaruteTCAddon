package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;

/**
 * 把 {@link GtfsFeed} 排成 GTFS 的各张 CSV 表并打成 zip。
 *
 * <p>表顺序、行顺序与字段写法全部固定：同一个 feed 两次生成逐字节一致。只发布本插件确实有的东西——没有换乘就不写 {@code transfers.txt}，没有英文名就不写
 * {@code translations.txt}；没有按间隔发车的交路就不写 {@code frequencies.txt}；{@code calendar_dates.txt}、{@code
 * shapes.txt} 等留到以后。
 *
 * <p>时刻写法允许超过 24 点（跨零点的站写 {@code 24:xx:xx}）；{@code stop_sequence} 直接用车次在交路上的节点下标，与实时源的 {@code
 * stop_sequence} 是同一个数。
 */
public final class GtfsFeedTables {

  /** 静态包在源站的缓存时间（秒）：重新发布时刻表后最多这么久中转就能拿到新包。 */
  public static final int MAX_AGE_SECONDS = 300;

  private GtfsFeedTables() {}

  /**
   * 打成 zip。
   *
   * @param feed 算好的 feed
   */
  public static byte[] zip(GtfsFeed feed) {
    List<GtfsZip.Entry> entries = new ArrayList<>();
    for (GtfsTable table : tables(feed)) {
      entries.add(new GtfsZip.Entry(table.fileName(), table.bytes()));
    }
    return GtfsZip.write(entries);
  }

  /**
   * 全部表，按 zip 里的顺序。
   *
   * @param feed 算好的 feed
   */
  static List<GtfsTable> tables(GtfsFeed feed) {
    List<GtfsTable> tables = new ArrayList<>();
    tables.add(agencies(feed));
    tables.add(stops(feed));
    tables.add(routes(feed));
    tables.add(trips(feed));
    tables.add(stopTimes(feed));
    tables.add(calendar(feed));
    tables.add(feedInfo(feed));
    List<List<String>> translations = translations(feed);
    if (!translations.isEmpty()) {
      tables.add(
          new GtfsTable(
              "translations",
              List.of(
                  "table_name",
                  "field_name",
                  "language",
                  "translation",
                  "record_id",
                  "record_sub_id",
                  "field_value"),
              translations));
    }
    List<List<String>> frequencies = frequencies(feed);
    if (!frequencies.isEmpty()) {
      tables.add(
          new GtfsTable(
              "frequencies",
              List.of("trip_id", "start_time", "end_time", "headway_secs", "exact_times"),
              frequencies));
    }
    List<List<String>> transfers = transfers(feed);
    if (!transfers.isEmpty()) {
      tables.add(
          new GtfsTable(
              "transfers",
              List.of(
                  "from_stop_id",
                  "to_stop_id",
                  "transfer_type",
                  "min_transfer_time",
                  "from_trip_id",
                  "to_trip_id"),
              transfers));
    }
    return List.copyOf(tables);
  }

  private static GtfsTable agencies(GtfsFeed feed) {
    List<List<String>> rows = new ArrayList<>();
    for (GtfsFeed.Agency agency : feed.agencies()) {
      rows.add(List.of(agency.id(), agency.name(), agency.url(), agency.timezone(), agency.lang()));
    }
    return new GtfsTable(
        "agency",
        List.of("agency_id", "agency_name", "agency_url", "agency_timezone", "agency_lang"),
        rows);
  }

  private static GtfsTable stops(GtfsFeed feed) {
    List<List<String>> rows = new ArrayList<>();
    for (GtfsFeed.Stop stop : feed.stops()) {
      rows.add(
          List.of(
              stop.id(),
              stop.name(),
              GtfsGeo.format(stop.latitude()),
              GtfsGeo.format(stop.longitude()),
              Integer.toString(stop.locationType()),
              stop.parentStation().orElse(""),
              stop.platformCode().orElse("")));
    }
    return new GtfsTable(
        "stops",
        List.of(
            "stop_id",
            "stop_name",
            "stop_lat",
            "stop_lon",
            "location_type",
            "parent_station",
            "platform_code"),
        rows);
  }

  private static GtfsTable routes(GtfsFeed feed) {
    List<List<String>> rows = new ArrayList<>();
    for (GtfsFeed.Route route : feed.routes()) {
      rows.add(
          List.of(
              route.id(),
              route.agencyId(),
              route.shortName(),
              route.longName(),
              Integer.toString(route.routeType()),
              route.color().orElse(""),
              route.textColor().orElse(""),
              Integer.toString(route.sortOrder())));
    }
    return new GtfsTable(
        "routes",
        List.of(
            "route_id",
            "agency_id",
            "route_short_name",
            "route_long_name",
            "route_type",
            "route_color",
            "route_text_color",
            "route_sort_order"),
        rows);
  }

  private static GtfsTable trips(GtfsFeed feed) {
    List<List<String>> rows = new ArrayList<>();
    for (GtfsFeed.Trip trip : feed.trips()) {
      rows.add(
          List.of(trip.routeId(), trip.serviceId(), trip.id(), trip.headsign(), trip.shortName()));
    }
    return new GtfsTable(
        "trips",
        List.of("route_id", "service_id", "trip_id", "trip_headsign", "trip_short_name"),
        rows);
  }

  private static GtfsTable stopTimes(GtfsFeed feed) {
    List<List<String>> rows = new ArrayList<>();
    for (GtfsFeed.Trip trip : feed.trips()) {
      for (GtfsFeed.StopTime stopTime : trip.stopTimes()) {
        rows.add(
            List.of(
                trip.id(),
                GtfsTime.timeOfDay(stopTime.arrivalSecondOfDay()),
                GtfsTime.timeOfDay(stopTime.departureSecondOfDay()),
                Integer.toString(stopTime.stopSequence()),
                stopTime.stopId(),
                Integer.toString(stopTime.pickupType()),
                Integer.toString(stopTime.dropOffType()),
                "1"));
      }
    }
    return new GtfsTable(
        "stop_times",
        List.of(
            "trip_id",
            "arrival_time",
            "departure_time",
            "stop_sequence",
            "stop_id",
            "pickup_type",
            "drop_off_type",
            "timepoint"),
        rows);
  }

  private static GtfsTable calendar(GtfsFeed feed) {
    List<String> row =
        List.of(
            feed.serviceId(),
            "1",
            "1",
            "1",
            "1",
            "1",
            "1",
            "1",
            GtfsTime.date(feed.calendarStart()),
            GtfsTime.date(feed.calendarEnd()));
    return new GtfsTable(
        "calendar",
        List.of(
            "service_id",
            "monday",
            "tuesday",
            "wednesday",
            "thursday",
            "friday",
            "saturday",
            "sunday",
            "start_date",
            "end_date"),
        List.of(row));
  }

  private static GtfsTable feedInfo(GtfsFeed feed) {
    List<String> row =
        List.of(
            feed.publisherName(),
            feed.publisherUrl(),
            "zh",
            "en",
            GtfsTime.date(feed.calendarStart()),
            GtfsTime.date(feed.calendarEnd()),
            feed.version(),
            feed.publisherUrl());
    return new GtfsTable(
        "feed_info",
        List.of(
            "feed_publisher_name",
            "feed_publisher_url",
            "feed_lang",
            "default_lang",
            "feed_start_date",
            "feed_end_date",
            "feed_version",
            "feed_contact_url"),
        List.of(row));
  }

  /** 英文译文：agency_name、stop_name、route_long_name、trip_headsign；没有英文名、或与中文相同的项不写。 */
  private static List<List<String>> translations(GtfsFeed feed) {
    Set<List<String>> rows = new LinkedHashSet<>();
    for (GtfsFeed.Agency agency : feed.agencies()) {
      addTranslation(
          rows, "agency", "agency_name", agency.id(), agency.name(), agency.secondaryName());
    }
    for (GtfsFeed.Stop stop : feed.stops()) {
      addTranslation(rows, "stops", "stop_name", stop.id(), stop.name(), stop.secondaryName());
    }
    for (GtfsFeed.Route route : feed.routes()) {
      addTranslation(
          rows, "routes", "route_long_name", route.id(), route.longName(), route.secondaryName());
    }
    for (GtfsFeed.Trip trip : feed.trips()) {
      addTranslation(
          rows, "trips", "trip_headsign", trip.id(), trip.headsign(), trip.headsignSecondary());
    }
    List<List<String>> out = new ArrayList<>(rows);
    out.sort(
        Comparator.comparing((List<String> row) -> row.get(0))
            .thenComparing(row -> row.get(1))
            .thenComparing(row -> row.get(4)));
    return out;
  }

  private static void addTranslation(
      Set<List<String>> rows,
      String table,
      String field,
      String recordId,
      String primary,
      Optional<String> secondary) {
    if (secondary == null || secondary.isEmpty()) {
      return;
    }
    String english = secondary.get().trim();
    if (english.isEmpty() || english.equalsIgnoreCase(primary.trim())) {
      return;
    }
    rows.add(List.of(table, field, "en", english, recordId, "", ""));
  }

  /** 按间隔发车的模板车次：全天按间隔、不按准点（{@code exact_times=0}）。按间隔发车没有运营时段的概念（线路运营中就全天发），所以窗口是 0 点到 24 点。 */
  private static List<List<String>> frequencies(GtfsFeed feed) {
    List<List<String>> rows = new ArrayList<>();
    for (GtfsFeed.Trip trip : feed.trips()) {
      if (trip.headwaySeconds().isPresent()) {
        rows.add(
            List.of(
                trip.id(),
                GtfsTime.timeOfDay(0),
                GtfsTime.timeOfDay(TimetableTrip.SECONDS_PER_DAY),
                Integer.toString(trip.headwaySeconds().getAsInt()),
                "0"));
      }
    }
    return rows;
  }

  private static List<List<String>> transfers(GtfsFeed feed) {
    List<List<String>> rows = new ArrayList<>();
    for (GtfsFeed.Transfer transfer : feed.transfers()) {
      rows.add(
          List.of(
              transfer.fromStopId(),
              transfer.toStopId(),
              Integer.toString(transfer.type()),
              transfer.minTransferSeconds().isPresent()
                  ? Integer.toString(transfer.minTransferSeconds().getAsInt())
                  : "",
              transfer.fromTripId().orElse(""),
              transfer.toTripId().orElse("")));
    }
    return rows;
  }
}
