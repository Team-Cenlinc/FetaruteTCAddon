package org.fetarute.fetaruteTCAddon.dispatcher.schedule.export;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ScheduleWindow;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ScheduledStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ServiceTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDepot;

/**
 * 将计划窗口导出为 stop-level CSV。
 *
 * <p>CSV 是人工审阅格式，不作为 runtime source of truth。时间字段使用 ISO-8601 UTC 字符串，service_date 使用计划发车时刻对应的 UTC
 * 日期，便于运营侧在表格中排序和筛选。
 */
public final class ScheduleCsvExporter {

  public static final String HEADER =
      "service_date,trip_id,source,company,operator,line,route,direction,stop_sequence,"
          + "station_code,node_id,planned_arrival,planned_departure,dwell_seconds,"
          + "depot_candidates,priority,notes";

  private ScheduleCsvExporter() {}

  /**
   * 导出完整计划窗口。
   *
   * @param window 计划窗口
   * @return CSV 文本；空窗口只包含表头
   */
  public static String export(ScheduleWindow window) {
    StringBuilder out = new StringBuilder(HEADER).append('\n');
    if (window == null || window.trips().isEmpty()) {
      return out.toString();
    }
    for (ServiceTrip trip : window.trips()) {
      for (ScheduledStop stop : trip.plannedStops()) {
        out.append(row(trip, stop)).append('\n');
      }
    }
    return out.toString();
  }

  private static String row(ServiceTrip trip, ScheduledStop stop) {
    StringJoiner row = new StringJoiner(",");
    row.add(csv(trip.plannedDeparture().atZone(ZoneOffset.UTC).toLocalDate().toString()));
    row.add(csv(trip.tripId()));
    row.add(csv(trip.source().name()));
    row.add(csv(trip.companyCode()));
    row.add(csv(trip.operatorCode()));
    row.add(csv(trip.lineCode()));
    row.add(csv(trip.routeCode()));
    row.add(csv(trip.direction().orElse("")));
    row.add(csv(String.valueOf(stop.stopSequence())));
    row.add(csv(stop.stationCode().orElse("")));
    row.add(csv(stop.nodeId().orElse("")));
    row.add(csv(stop.plannedArrival().map(DateTimeFormatter.ISO_INSTANT::format).orElse("")));
    row.add(csv(stop.plannedDeparture().map(DateTimeFormatter.ISO_INSTANT::format).orElse("")));
    row.add(csv(stop.dwell().map(value -> String.valueOf(value.toSeconds())).orElse("")));
    row.add(csv(formatDepotCandidates(trip)));
    row.add(csv(String.valueOf(trip.priority())));
    row.add(csv(stop.notes().or(() -> trip.notes()).orElse("")));
    return row.toString();
  }

  /** 将 depot 候选格式化为便于人工阅读的 {@code nodeId} 或 {@code nodeId*weight} 列表。 */
  public static String formatDepotCandidates(ServiceTrip trip) {
    if (trip == null || trip.depotCandidates().isEmpty()) {
      return "";
    }
    StringJoiner joiner = new StringJoiner(";");
    for (SpawnDepot depot : trip.depotCandidates()) {
      if (depot == null) {
        continue;
      }
      joiner.add(depot.weight() == 1 ? depot.nodeId() : depot.nodeId() + "*" + depot.weight());
    }
    return joiner.toString();
  }

  private static String csv(String value) {
    String text = value == null ? "" : value;
    boolean needsQuotes =
        text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0;
    if (!needsQuotes) {
      return text;
    }
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }
}
