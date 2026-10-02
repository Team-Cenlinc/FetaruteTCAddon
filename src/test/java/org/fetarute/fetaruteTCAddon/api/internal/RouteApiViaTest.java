package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteViaMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.storage.SampleTransitNetwork;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 交路详情里的经由站（1.9.0）：按 metadata 里配置的顺序给出，存库读回不变；没配置为空。 */
class RouteApiViaTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private SampleTransitNetwork net;
  private Route route;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    net = new SampleTransitNetwork(storage);
    route = storage.route(net.ws, "WS-VIA", RoutePatternType.RAPID, RouteOperationType.OPERATION);
    storage.stops(
        route,
        new String[] {"STOP", "SURC:S:KPO:1"},
        new String[] {"STOP", "SURC:S:PPK:1"},
        new String[] {"STOP", "SURC:S:HHU:1"},
        new String[] {"TERMINATE", "SURC:S:WYB:1"});
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  @Test
  void configuredViaComesBackInOrder() {
    Map<String, Object> metadata = new HashMap<>(route.metadata());
    metadata.put(RouteViaMetadata.KEY, List.of("HHU", "PPK"));
    storage.provider().routes().save(withMetadata(route, metadata));

    assertEquals(List.of("HHU", "PPK"), api().getRoute(route.id()).orElseThrow().via());
  }

  @Test
  void routesWithoutViaGiveAnEmptyList() {
    assertEquals(List.of(), api().getRoute(route.id()).orElseThrow().via());
  }

  private RouteApi api() {
    StorageProvider provider = storage.provider();
    RouteDefinitionCache routes = new RouteDefinitionCache(message -> {});
    StationDirectory directory = new StationDirectory(routes, message -> {});
    routes.reload(provider);
    directory.reload(provider);
    return new RouteApiImpl(routes, directory);
  }

  private static Route withMetadata(Route route, Map<String, Object> metadata) {
    return new Route(
        route.id(),
        route.code(),
        route.lineId(),
        route.name(),
        route.secondaryName(),
        route.patternType(),
        route.operationType(),
        route.distanceMeters(),
        route.runtimeSeconds(),
        metadata,
        route.createdAt(),
        Instant.now());
  }
}
