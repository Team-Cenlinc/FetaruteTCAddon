package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.DepotSpawnPattern;
import org.junit.jupiter.api.Test;

/**
 * 未发车票据的车种推断。
 *
 * <p>编组名：地铁/轻轨型先于电动车组判定，推断不出来交给默认车种。缓存：同一交路只推断一次，区块未加载时沿用上次结果，绝不因此回落成默认车种。
 */
class SpawnTrainConfigResolverTest {

  private static final Instant T0 = Instant.parse("2026-09-30T12:00:00Z");
  private static final NodeId DEPOT = NodeId.of("SURC:D:OFL:1");
  private static final ConfigManager.TrainConfigSettings EMU_DEFAULT =
      new ConfigManager.TrainConfigSettings("emu", Map.of());

  @Test
  void metroTramAndLightRailNamesMapToMetro() {
    assertEquals(
        Optional.of(TrainType.METRO),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("Metro6A"));
    assertEquals(
        Optional.of(TrainType.METRO),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("tram_train"));
    assertEquals(
        Optional.of(TrainType.METRO),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("LIGHT_RAIL_2"));
  }

  @Test
  void metroIsCheckedBeforeEmu() {
    assertEquals(
        Optional.of(TrainType.METRO),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("metro_emu"));
    assertEquals(
        Optional.of(TrainType.EMU), SpawnTrainConfigResolver.inferTrainTypeFromPattern("DS_EMU"));
  }

  @Test
  void otherKnownNamesKeepTheirTypes() {
    assertEquals(
        Optional.of(TrainType.DMU), SpawnTrainConfigResolver.inferTrainTypeFromPattern("dmu3"));
    assertEquals(
        Optional.of(TrainType.DIESEL_PUSH_PULL),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("diesel_push"));
    assertEquals(
        Optional.of(TrainType.ELECTRIC_LOCO),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("electric_loco_8"));
  }

  @Test
  void unknownOrBlankNamesAreLeftToTheDefaultType() {
    assertTrue(SpawnTrainConfigResolver.inferTrainTypeFromPattern("M9A").isEmpty());
    assertTrue(SpawnTrainConfigResolver.inferTrainTypeFromPattern(" ").isEmpty());
    assertTrue(SpawnTrainConfigResolver.inferTrainTypeFromPattern(null).isEmpty());
  }

  @Test
  void depotSignOfTheCreateStopDecidesTheType() {
    Sources sources = new Sources();

    TrainConfig config = sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, T0);

    assertEquals(TrainType.METRO, config.type());
    assertEquals(Optional.of(DEPOT), sources.lastDepot);
  }

  @Test
  void routePatternWinsWithoutReadingStopsOrSign() {
    Sources sources = new Sources();
    sources.metadata = Map.of(DepotSpawnPattern.ROUTE_METADATA_KEY, "dmu3");

    assertEquals(
        TrainType.DMU, sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, T0).type());
    assertEquals(0, sources.stopReads);
    assertEquals(0, sources.signReads);
  }

  @Test
  void missingRouteOrCreateStopFallsBackToTheDefaultType() {
    Sources noRoute = new Sources();
    noRoute.routeExists = false;
    Sources noCreate = new Sources();
    noCreate.stops = List.of(stop(Optional.empty()));

    assertEquals(
        TrainType.EMU, noRoute.resolver.resolve(noRoute.routeUuid, EMU_DEFAULT, T0).type());
    assertEquals(
        TrainType.EMU, noCreate.resolver.resolve(noCreate.routeUuid, EMU_DEFAULT, T0).type());
    assertEquals(0, noCreate.signReads);
  }

  @Test
  // 站牌每张票据都会问一次；同一交路在有效期内只推断一次，不随票据数增长。
  void repeatedQueriesWithinTtlInferOnce() {
    Sources sources = new Sources();

    for (int i = 0; i < 20; i++) {
      sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, T0.plusSeconds(i));
    }

    assertEquals(1, sources.routeReads);
    assertEquals(1, sources.stopReads);
    assertEquals(1, sources.signReads);
  }

  @Test
  void rewrittenSignTakesEffectAfterTtl() {
    Sources sources = new Sources();
    sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, T0);
    sources.sign = DepotSpawnPattern.SignRead.loaded(Optional.of("dmu3"));

    Instant justBefore = T0.plus(SpawnTrainConfigResolver.TTL).minusMillis(1);
    assertEquals(
        TrainType.METRO,
        sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, justBefore).type());
    Instant expired = T0.plus(SpawnTrainConfigResolver.TTL);
    assertEquals(
        TrainType.DMU, sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, expired).type());
  }

  @Test
  // 区块卸载后读不到牌子：沿用上次读到的车种，而不是跳回默认车种让 ETA 来回跳。
  void unloadedDepotKeepsTheLastKnownType() {
    Sources sources = new Sources();
    sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, T0);
    sources.sign = DepotSpawnPattern.SignRead.unloaded();

    Instant expired = T0.plus(SpawnTrainConfigResolver.TTL);
    assertEquals(
        TrainType.METRO, sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, expired).type());
    assertEquals(2, sources.signReads, "过期后仍会尝试重读，只是读不到时沿用旧值");
  }

  @Test
  void unloadedDepotWithoutHistoryUsesTheDefaultTypeUntilItLoads() {
    Sources sources = new Sources();
    sources.sign = DepotSpawnPattern.SignRead.unloaded();

    assertEquals(
        TrainType.EMU, sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, T0).type());
    sources.sign = DepotSpawnPattern.SignRead.loaded(Optional.of("metro6"));
    assertEquals(
        TrainType.METRO,
        sources
            .resolver
            .resolve(sources.routeUuid, EMU_DEFAULT, T0.plus(SpawnTrainConfigResolver.TTL))
            .type());
  }

  @Test
  void invalidateAllForcesAFreshInference() {
    Sources sources = new Sources();
    sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, T0);
    sources.metadata = Map.of(DepotSpawnPattern.ROUTE_METADATA_KEY, "dmu3");

    sources.resolver.invalidateAll();

    assertEquals(
        TrainType.DMU, sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, T0).type());
    assertEquals(2, sources.routeReads);
  }

  @Test
  // 缓存的是车种，加减速按当次传入的配置取：配置重载后立即生效，不必清缓存。
  void accelerationFollowsTheCurrentSettings() {
    Sources sources = new Sources();
    sources.resolver.resolve(sources.routeUuid, EMU_DEFAULT, T0);
    ConfigManager.TrainConfigSettings tuned =
        new ConfigManager.TrainConfigSettings(
            "emu", Map.of(TrainType.METRO, new ConfigManager.TrainTypeSettings(1.25, 0.75)));

    TrainConfig config = sources.resolver.resolve(sources.routeUuid, tuned, T0.plusSeconds(1));

    assertEquals(1.25, config.accelBps2());
    assertEquals(0.75, config.decelBps2());
    assertEquals(1, sources.routeReads);
  }

  private static RouteStop stop(Optional<String> notes) {
    return new RouteStop(
        UUID.randomUUID(),
        0,
        Optional.empty(),
        Optional.of("SURC:S:OFL:1"),
        Optional.empty(),
        RouteStopPassType.STOP,
        notes);
  }

  /** 可计数、可替换的交路、停靠表与车库牌子来源。默认：交路未写编组，首站 CRET 指向的车库牌子写着地铁编组。 */
  private static final class Sources {
    private final UUID routeUuid = UUID.randomUUID();
    private boolean routeExists = true;
    private Map<String, Object> metadata = Map.of();
    private List<RouteStop> stops = List.of(stop(Optional.of("CRET " + DEPOT.value())));
    private DepotSpawnPattern.SignRead sign =
        DepotSpawnPattern.SignRead.loaded(Optional.of("metro6"));
    private Optional<NodeId> lastDepot = Optional.empty();
    private int routeReads;
    private int stopReads;
    private int signReads;

    private final SpawnTrainConfigResolver resolver =
        new SpawnTrainConfigResolver(this::route, this::stops, this::sign);

    private Optional<Route> route(UUID uuid) {
      routeReads++;
      if (!routeExists) {
        return Optional.empty();
      }
      return Optional.of(
          new Route(
              uuid,
              "R1",
              UUID.randomUUID(),
              "Local",
              Optional.empty(),
              RoutePatternType.LOCAL,
              RouteOperationType.OPERATION,
              Optional.empty(),
              Optional.empty(),
              metadata,
              T0,
              T0));
    }

    private List<RouteStop> stops(UUID uuid) {
      stopReads++;
      return stops;
    }

    private DepotSpawnPattern.SignRead sign(NodeId depot) {
      signReads++;
      lastDepot = Optional.of(depot);
      return sign;
    }
  }
}
