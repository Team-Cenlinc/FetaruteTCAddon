package org.fetarute.fetaruteTCAddon.web;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.api.eta.EtaApi;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedService;

/**
 * 把公开 API 的快照排成对外的 JSON 结构（{@link LinkedHashMap}，交给 Gson）。字段用 camelCase，时刻用 ISO-8601（UTC）并附毫秒时间戳；
 * 没有值的字段不输出。不含玩家名等个人信息。纯计算。
 */
final class WebApiViews {

  /** 每个回应顶层的生成时刻字段（不算进 ETag）。 */
  static final String GENERATED_AT = "generatedAt";

  private WebApiViews() {}

  /**
   * {@code /v1/meta}：版本、本服的服务器 ID、服务器时刻与时区、GTFS 版本、接口清单。
   *
   * @param serverId 配置里的 {@code server-id}；没配时不输出，多服聚合方据此认出、核对来源
   * @param gtfs 当前静态包的版本与生成时刻；静态包还没起来时不输出
   */
  static Map<String, Object> meta(
      String apiVersion,
      Optional<String> serverId,
      Instant now,
      ZoneId zone,
      List<String> endpoints,
      Optional<GtfsFeedService.Summary> gtfs) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("apiVersion", apiVersion);
    serverId.ifPresent(value -> out.put("serverId", value));
    out.put(GENERATED_AT, now.toString());
    out.put("timezone", zone.getId());
    gtfs.ifPresent(
        summary -> {
          Map<String, Object> feed = new LinkedHashMap<>();
          feed.put("feedVersion", summary.version());
          feed.put(GENERATED_AT, summary.generatedAt().toString());
          out.put("gtfs", feed);
        });
    out.put("endpoints", List.copyOf(endpoints));
    return out;
  }

  /** {@code /v1/operators}。 */
  static Map<String, Object> operators(
      Instant now, Collection<OperatorApi.OperatorInfo> operators) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (OperatorApi.OperatorInfo operator :
        sorted(operators, Comparator.comparing(OperatorApi.OperatorInfo::code))) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", operator.id().toString());
      row.put("code", operator.code());
      row.put("name", operator.name());
      operator.secondaryName().ifPresent(value -> row.put("secondaryName", value));
      operator.colorTheme().ifPresent(value -> row.put("color", value));
      row.put("priority", operator.priority());
      rows.add(row);
    }
    return envelope(now, "operators", rows);
  }

  /**
   * {@code /v1/lines}。
   *
   * @param liveStatus 线路此刻的运行状况（运营商代码、线路代码）；没有时不写 {@code liveStatus}
   */
  static Map<String, Object> lines(
      Instant now,
      Collection<LineApi.LineInfo> lines,
      Map<UUID, String> operatorCodes,
      BiFunction<String, String, Optional<PidsLineStatus>> liveStatus) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (LineApi.LineInfo line :
        sorted(
            lines,
            Comparator.comparing(
                    (LineApi.LineInfo l) -> operatorCodes.getOrDefault(l.operatorId(), ""))
                .thenComparing(LineApi.LineInfo::code))) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", line.id().toString());
      row.put("code", line.code());
      row.put("operatorId", line.operatorId().toString());
      putIfPresent(row, "operatorCode", operatorCodes.get(line.operatorId()));
      row.put("name", line.name());
      line.secondaryName().ifPresent(value -> row.put("secondaryName", value));
      row.put("serviceType", line.serviceType().name());
      line.color().ifPresent(value -> row.put("color", value));
      row.put("status", line.status().name());
      Optional.ofNullable(operatorCodes.get(line.operatorId()))
          .flatMap(operator -> liveStatus.apply(operator, line.code()))
          .ifPresent(status -> row.put("liveStatus", liveStatus(status)));
      rows.add(row);
    }
    return envelope(now, "lines", rows);
  }

  /** 线路运行状况：{@code condition} 与这种状况的细节。 */
  static Map<String, Object> liveStatus(PidsLineStatus status) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("condition", status.condition().name());
    switch (status.detail()) {
      case PidsLineStatus.Detail.Late late -> out.put("maxDelayMinutes", late.minutes());
      case PidsLineStatus.Detail.Cancelled cancelled -> out.put(
          "cancelledTrips", cancelled.trips());
      case PidsLineStatus.Detail.Closed closed -> {
        out.put("closedFrom", closed.section().from());
        out.put("closedTo", closed.section().to());
      }
      case PidsLineStatus.Detail.FirstTrain first -> out.put(
          "nextServiceAt", first.at().toString());
      case PidsLineStatus.Detail.None none -> {}
    }
    return out;
  }

  /** {@code /v1/stations}：车站（含停靠线路）与车站组。 */
  static Map<String, Object> stations(
      Instant now,
      Collection<StationApi.StationInfo> stations,
      Map<UUID, String> operatorCodes,
      Function<UUID, List<StationApi.ServingLine>> serving,
      Collection<StationApi.StationGroupInfo> groups) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (StationApi.StationInfo station :
        sorted(
            stations,
            Comparator.comparing(
                    (StationApi.StationInfo s) -> operatorCodes.getOrDefault(s.operatorId(), ""))
                .thenComparing(StationApi.StationInfo::code))) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", station.id().toString());
      row.put("code", station.code());
      row.put("operatorId", station.operatorId().toString());
      putIfPresent(row, "operatorCode", operatorCodes.get(station.operatorId()));
      row.put("name", station.name());
      station.secondaryName().ifPresent(value -> row.put("secondaryName", value));
      station.worldName().ifPresent(value -> row.put("world", value));
      station
          .location()
          .ifPresent(
              at -> {
                Map<String, Object> position = new LinkedHashMap<>();
                position.put("x", at.x());
                position.put("y", at.y());
                position.put("z", at.z());
                row.put("position", position);
              });
      List<Map<String, Object>> servingRows = new ArrayList<>();
      for (StationApi.ServingLine line : serving.apply(station.id())) {
        Map<String, Object> servingRow = new LinkedHashMap<>();
        servingRow.put("lineId", line.lineId().toString());
        servingRow.put("operatorCode", line.operatorCode());
        servingRow.put("lineCode", line.lineCode());
        servingRow.put("lineName", line.lineName());
        line.color().ifPresent(value -> servingRow.put("color", value));
        if (!line.stationId().equals(station.id())) {
          // 经车站组换乘到达的线路：停在组里另一站
          servingRow.put("viaStationId", line.stationId().toString());
          servingRow.put("viaStationCode", line.stationCode());
          line.transferType().ifPresent(type -> servingRow.put("transferType", type.name()));
          line.walkSeconds().ifPresent(seconds -> servingRow.put("walkSeconds", seconds));
        }
        servingRows.add(servingRow);
      }
      row.put("lines", servingRows);
      rows.add(row);
    }
    List<Map<String, Object>> groupRows = new ArrayList<>();
    for (StationApi.StationGroupInfo group :
        sorted(groups, Comparator.comparing(StationApi.StationGroupInfo::code))) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", group.id().toString());
      row.put("code", group.code());
      row.put("name", group.name());
      group.secondaryName().ifPresent(value -> row.put("secondaryName", value));
      List<Map<String, Object>> members = new ArrayList<>();
      for (StationApi.StationGroupMember member : group.members()) {
        Map<String, Object> memberRow = new LinkedHashMap<>();
        memberRow.put("stationId", member.stationId().toString());
        memberRow.put("operatorCode", member.operatorCode());
        memberRow.put("stationCode", member.stationCode());
        memberRow.put("stationName", member.stationName());
        memberRow.put("transferType", member.transferType().name());
        members.add(memberRow);
      }
      row.put("members", members);
      groupRows.add(row);
    }
    Map<String, Object> out = envelope(now, "stations", rows);
    out.put("groups", groupRows);
    return out;
  }

  /** {@code /v1/trains}：在线列车（不含到站预测）。 */
  static Map<String, Object> trains(Instant now, Collection<TrainApi.TrainSnapshot> trains) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (TrainApi.TrainSnapshot train :
        sorted(trains, Comparator.comparing(TrainApi.TrainSnapshot::trainName))) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", train.trainName());
      train.operatorCode().ifPresent(value -> row.put("operatorCode", value));
      train.lineCode().ifPresent(value -> row.put("lineCode", value));
      train.routeCode().ifPresent(value -> row.put("routeCode", value));
      train.consist().ifPresent(value -> row.put("consist", value));
      row.put("inService", !train.outOfService());
      train.currentNode().ifPresent(value -> row.put("currentNode", value));
      train.nextNode().ifPresent(value -> row.put("nextNode", value));
      row.put("speedBlocksPerSecond", round(train.speedBps()));
      row.put("signal", train.signal().name());
      row.put("updatedAt", train.updatedAt().toString());
      rows.add(row);
    }
    return envelope(now, "trains", rows);
  }

  /** {@code /v1/stations/{运营商}/{站码}/board}：站牌。 */
  static Map<String, Object> board(
      Instant now, String operator, String stationCode, EtaApi.BoardResult board) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (EtaApi.BoardRow row : board.rows()) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("line", row.lineName());
      out.put("routeId", row.routeId());
      out.put("destination", row.destination());
      row.destinationId().ifPresent(value -> out.put("destinationId", value));
      out.put("platform", row.platform());
      if (row.platformPending()) {
        out.put("platformPending", true);
        out.put("platformCandidates", row.platformCandidates());
      }
      out.put("status", row.statusText());
      out.put("phase", row.phase().name());
      if (row.etaEpochMillis() > 0L) {
        out.put("eta", Instant.ofEpochMilli(row.etaEpochMillis()).toString());
        out.put("etaEpochMillis", row.etaEpochMillis());
      }
      row.delaySeconds().ifPresent(value -> out.put("delaySeconds", value));
      out.put("stopSequence", row.stopSequence());
      out.put("passing", row.passing());
      out.put("terminating", row.terminating());
      out.put("inService", !row.outOfService());
      row.trainName().ifPresent(value -> out.put("train", value));
      if (!row.cars().isEmpty()) {
        List<Map<String, Object>> cars = new ArrayList<>();
        for (EtaApi.CarLoad car : row.cars()) {
          Map<String, Object> carRow = new LinkedHashMap<>();
          carRow.put("seats", car.seats());
          carRow.put("occupied", car.occupied());
          cars.add(carRow);
        }
        out.put("cars", cars);
      }
      rows.add(out);
    }
    Map<String, Object> out = envelope(now, "departures", rows);
    out.put("operatorCode", operator);
    out.put("stationCode", stationCode);
    return out;
  }

  private static Map<String, Object> envelope(
      Instant now, String key, List<Map<String, Object>> rows) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(GENERATED_AT, now.toString());
    out.put(key, rows);
    return out;
  }

  private static void putIfPresent(Map<String, Object> row, String key, Object value) {
    if (value != null) {
      row.put(key, value);
    }
  }

  private static double round(double value) {
    return Double.isFinite(value) ? Math.round(value * 100.0) / 100.0 : 0.0;
  }

  private static <T> List<T> sorted(Collection<T> items, Comparator<T> order) {
    List<T> out = new ArrayList<>(items);
    out.sort(order);
    return out;
  }
}
