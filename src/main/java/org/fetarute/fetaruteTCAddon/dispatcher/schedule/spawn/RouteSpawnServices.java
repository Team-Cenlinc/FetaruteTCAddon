package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 给一条交路出票时用的发车服务：发车计划里有就用计划里的，没有（不按间隔发车的交路）就按交路的归属现拼一个。 */
public final class RouteSpawnServices {

  private RouteSpawnServices() {}

  /**
   * @param spawnManager 发车计划
   * @param cache 交路缓存
   * @param provider 存储（取公司代码）
   * @param routeId 交路
   * @param debug 拼不出时的调试日志
   * @return 发车服务；交路不在缓存里或拼不出时为空
   */
  public static Optional<SpawnService> forRoute(
      SpawnManager spawnManager,
      RouteDefinitionCache cache,
      Optional<StorageProvider> provider,
      UUID routeId,
      Consumer<String> debug) {
    for (SpawnService service : spawnManager.snapshotPlan().services()) {
      if (service != null && routeId.equals(service.routeId())) {
        return Optional.of(service);
      }
    }
    Optional<RouteDefinitionCache.RouteRecord> record = cache.findRecord(routeId);
    Optional<RouteDefinition> definition = cache.findById(routeId);
    if (record.isEmpty() || definition.isEmpty() || provider.isEmpty()) {
      return Optional.empty();
    }
    List<RouteStop> stops = cache.listStops(definition.get().id());
    String depotNode =
        stops.isEmpty()
            ? definition.get().waypoints().get(0).value()
            : SpawnDirectiveParser.findDirectiveTarget(stops.get(0), "CRET")
                .orElse(definition.get().waypoints().get(0).value());
    UUID companyId = record.get().operator().companyId();
    String companyCode =
        provider
            .get()
            .companies()
            .findById(companyId)
            .map(company -> company.code())
            .orElse(record.get().operator().code());
    try {
      return Optional.of(
          new SpawnService(
              new SpawnServiceKey(routeId),
              companyId,
              companyCode,
              record.get().operator().id(),
              record.get().operator().code(),
              record.get().line().id(),
              record.get().line().code(),
              routeId,
              record.get().route().code(),
              Duration.ofMinutes(10),
              depotNode));
    } catch (IllegalArgumentException ex) {
      if (debug != null) {
        debug.accept("拼发车服务失败 route=" + routeId + " error=" + ex.getMessage());
      }
      return Optional.empty();
    }
  }
}
