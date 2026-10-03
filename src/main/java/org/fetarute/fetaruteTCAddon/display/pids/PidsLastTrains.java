package org.fetarute.fetaruteTCAddon.display.pids;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;

/**
 * 一个车站的末班车：每个服务日从本站开往同一终点的最后一班。
 *
 * <p>只看已发布时刻表：本站的计划发车去掉取消与在本站终到的，按（服务日，终点站）分组，每组计划发车最晚的一班就是末班。
 * 终点取交路的运营终点（与站牌终点同一口径）；开往同一终点的车分属几条线、几种停站方式时放在一起比，乘客只关心还有没有车去那里。
 *
 * <p>到发行按交路、本站停靠序号与计划到站（预计到站减晚点）对上某一班末班才标末班车；对不上（不按表跑、晚点不明）就不标——宁可少标， 不能把还有后续的车说成末班。
 */
final class PidsLastTrains {

  /** 到发行的计划到站与末班的计划到站差在这之内算同一班。 */
  static final Duration MATCH_TOLERANCE = Duration.ofSeconds(60);

  /** 没有末班信息。 */
  static final PidsLastTrains NONE = new PidsLastTrains(List.of());

  /**
   * 一班末班在本站的停靠。
   *
   * @param routeCode 交路代码（{@code 运营商:线路:交路}）
   * @param stopSequence 本站停靠序号
   * @param plannedArrival 计划到站
   */
  record Last(String routeCode, int stopSequence, Instant plannedArrival) {}

  private final List<Last> lasts;

  private PidsLastTrains(List<Last> lasts) {
    this.lasts = List.copyOf(lasts);
  }

  /**
   * 从本站的计划发车里找出各组末班。
   *
   * @param departures 本站的计划发车（已核对运营商），须覆盖到各服务日的最后一班
   * @param routes 按交路 ID 取交路详情（终点与交路代码）
   */
  static PidsLastTrains of(
      List<TimetableApi.Departure> departures,
      Function<UUID, Optional<RouteApi.RouteDetail>> routes) {
    Objects.requireNonNull(routes, "routes");
    Map<UUID, Optional<RouteApi.RouteDetail>> details = new HashMap<>();
    Map<Group, Candidate> latest = new HashMap<>();
    for (TimetableApi.Departure departure : departures) {
      if (departure.cancelled() || departure.terminating()) {
        continue;
      }
      Optional<RouteApi.RouteDetail> route =
          details.computeIfAbsent(departure.routeId(), routes::apply);
      Optional<String> destination =
          route.flatMap(
              detail ->
                  RouteTerminals.stationIdentityOfNode(detail.terminal().endOfOperationNodeId())
                      .map(ref -> ref.operatorCode() + ":" + ref.stationCode()));
      if (route.isEmpty() || destination.isEmpty()) {
        continue;
      }
      Candidate candidate = new Candidate(route.get().info().code(), departure);
      latest.merge(
          new Group(departure.serviceDate(), destination.get().toUpperCase(Locale.ROOT)),
          candidate,
          (old, next) ->
              next.departure().plannedDeparture().isAfter(old.departure().plannedDeparture())
                  ? next
                  : old);
    }
    return new PidsLastTrains(
        latest.values().stream()
            .map(
                candidate ->
                    new Last(
                        candidate.routeCode(),
                        candidate.departure().stopSequence(),
                        candidate.departure().plannedArrival()))
            .toList());
  }

  /**
   * 这一行是不是末班车。
   *
   * @param routeId 到发行的交路代码（{@code 运营商:线路:交路}）
   * @param stopSequence 本站停靠序号
   * @param expectedAt 预计到站
   * @param delaySeconds 晚点秒数；不按表运行时为空，按预计到站即计划到站对
   */
  boolean matches(String routeId, int stopSequence, Instant expectedAt, OptionalLong delaySeconds) {
    Instant planned = expectedAt.minusSeconds(delaySeconds.orElse(0L));
    return lasts.stream()
        .anyMatch(
            last ->
                last.routeCode().equalsIgnoreCase(routeId)
                    && last.stopSequence() == stopSequence
                    && Duration.between(last.plannedArrival(), planned)
                            .abs()
                            .compareTo(MATCH_TOLERANCE)
                        <= 0);
  }

  /** 末班分组：服务日与终点站（{@code 运营商:站码}，大写）。 */
  private record Group(LocalDate serviceDate, String destination) {}

  private record Candidate(String routeCode, TimetableApi.Departure departure) {}
}
