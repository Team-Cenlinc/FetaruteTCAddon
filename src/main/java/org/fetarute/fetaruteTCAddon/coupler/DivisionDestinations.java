package org.fetarute.fetaruteTCAddon.coupler;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;

/**
 * 分行后两段各开往哪里：留下的一段是本交路的运营终点；解下的一段按表是子交路首班的运营终点（只回库的子交路不载客）， 否则按指令：GOTO 是目标交路的运营终点，IDLE、RETURN
 * 不载客。车站与站牌同一口径（{@code 运营商:站码}）。
 */
final class DivisionDestinations {

  private final FetaruteTCAddon plugin;

  DivisionDestinations(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  /** 交路的运营终点；查不到交路或终点不是车站时不载客。 */
  DivisionForecast.Destination ofRoute(Optional<UUID> route) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (route.isEmpty() || cache.isEmpty()) {
      return DivisionForecast.Destination.NOT_IN_SERVICE;
    }
    return ofStops(
        cache
            .get()
            .findById(route.get())
            .map(d -> cache.get().listStops(d.id()))
            .orElse(List.of()));
  }

  /** 停靠表的运营终点（最后一个载客车站）。 */
  DivisionForecast.Destination ofStops(List<RouteStop> stops) {
    OptionalInt end = RouteTerminals.endOfOperationIndex(stops);
    if (end.isEmpty()) {
      return DivisionForecast.Destination.NOT_IN_SERVICE;
    }
    return stationIdOf(stops.get(end.getAsInt()))
        .map(id -> new DivisionForecast.Destination(Optional.of(id), stationName(id)))
        .orElse(DivisionForecast.Destination.NOT_IN_SERVICE);
  }

  /** 解下的一段开往哪里（见类说明）。 */
  DivisionForecast.Destination detached(
      RouteId trunk, RouteCouplingDirectives.Decouple directive, TimetableService.SplitPlan plan) {
    if (plan.child().isPresent()) {
      if (plan.tripless()) {
        return DivisionForecast.Destination.NOT_IN_SERVICE;
      }
      return ofRoute(
          plugin
              .getTimetableService()
              .flatMap(service -> service.firstRouteOf(plan.child().get())));
    }
    if (directive.action() != RouteCouplingDirectives.Action.GOTO) {
      return DivisionForecast.Destination.NOT_IN_SERVICE;
    }
    return ofRoute(gotoTarget(trunk, directive));
  }

  /** GOTO 的目标交路（与主干同线、按交路码找）。 */
  Optional<UUID> gotoTarget(RouteId trunk, RouteCouplingDirectives.Decouple directive) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    return cache.flatMap(
        c ->
            c.findUuid(trunk)
                .flatMap(c::findRecord)
                .flatMap(
                    record ->
                        c.findByCodes(
                            record.operator().code(),
                            record.line().code(),
                            directive.target().orElse("")))
                .flatMap(definition -> c.findUuid(definition.id())));
  }

  /** 停靠点所属的车站（{@code 运营商:站码}）；不是车站时为空。 */
  Optional<String> stationIdOf(RouteStop stop) {
    Optional<String> identity =
        RouteTerminals.stationIdentityOf(stop)
            .map(ref -> ref.operatorCode() + ":" + ref.stationCode());
    if (identity.isPresent() || stop.stationId().isEmpty()) {
      return identity;
    }
    return plugin
        .getStationDirectory()
        .flatMap(directory -> directory.snapshot().station(stop.stationId().get()))
        .map(entry -> entry.operator().code() + ":" + entry.code());
  }

  /** 车站显示名；查不到时为站码。 */
  String stationName(String stationId) {
    int colon = stationId.indexOf(':');
    String code = colon < 0 ? stationId : stationId.substring(colon + 1);
    if (colon <= 0) {
      return code;
    }
    String operator = stationId.substring(0, colon);
    return plugin
        .getStationDirectory()
        .map(StationDirectory::snapshot)
        .flatMap(snapshot -> snapshot.findStation(operator, code))
        .map(StationDirectory.StationEntry::name)
        .filter(name -> !name.isBlank())
        .orElse(code);
  }
}
