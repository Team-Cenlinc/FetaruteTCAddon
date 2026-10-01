package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 未发车票据的走行模型：同一交路的车种推断在票据之间、站牌重算之间复用，交路定义变化后重新推断。 */
class EtaSpawnTrainConfigCacheTest {

  @Test
  // 站牌重算时每张票据都要估算走行；读库次数必须与票据数无关。
  void ticketsOfTheSameRouteReadStorageOnce() {
    Fixture fixture = new Fixture();

    for (int i = 0; i < 25; i++) {
      fixture.estimate();
    }

    verify(fixture.routes, times(1)).findById(fixture.routeUuid);
    verify(fixture.routeStops, times(1)).listByRoute(fixture.routeUuid);
  }

  @Test
  void routeDefinitionChangeTriggersReinference() {
    Fixture fixture = new Fixture();
    fixture.estimate();

    fixture.routeChangeListener.getValue().run();
    fixture.estimate();

    verify(fixture.routes, times(2)).findById(fixture.routeUuid);
  }

  @Test
  void attachingAnotherStorageProviderTriggersReinference() {
    Fixture fixture = new Fixture();
    fixture.estimate();

    fixture.service.attachStorageProvider(fixture.provider);
    fixture.estimate();

    verify(fixture.routes, times(2)).findById(fixture.routeUuid);
  }

  /** 一条交路，首站 CRET 指向一个未注册的车库（推断结果为默认车种）。 */
  private static final class Fixture {
    private final UUID routeUuid = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final RouteRepository routes = mock(RouteRepository.class);
    private final RouteStopRepository routeStops = mock(RouteStopRepository.class);
    private final StorageProvider provider = mock(StorageProvider.class);
    private final ArgumentCaptor<Runnable> routeChangeListener =
        ArgumentCaptor.forClass(Runnable.class);
    private final EtaService service;

    private Fixture() {
      Instant now = Instant.now();
      when(routes.findById(routeUuid))
          .thenReturn(
              Optional.of(
                  new Route(
                      routeUuid,
                      "R1",
                      UUID.randomUUID(),
                      "Local",
                      Optional.empty(),
                      RoutePatternType.LOCAL,
                      RouteOperationType.OPERATION,
                      Optional.empty(),
                      Optional.empty(),
                      Map.of(),
                      now,
                      now)));
      when(routeStops.listByRoute(routeUuid))
          .thenReturn(
              List.of(
                  new RouteStop(
                      routeUuid,
                      0,
                      Optional.empty(),
                      Optional.of("SURC:S:OFL:1"),
                      Optional.empty(),
                      RouteStopPassType.STOP,
                      Optional.of("CRET SURC:D:OFL:1"))));
      when(provider.routes()).thenReturn(routes);
      when(provider.routeStops()).thenReturn(routeStops);

      RouteDefinitionCache definitions = mock(RouteDefinitionCache.class);
      service = new EtaService(new TrainSnapshotStore(), mock(RailGraphService.class), definitions);
      verify(definitions).addChangeListener(routeChangeListener.capture());
      ConfigManager.ConfigView config =
          ConfigManager.parse(new YamlConfiguration(), Logger.getLogger("eta-test"));
      service.attachStorageProvider(provider);
      service.attachConfigSources(new SignNodeRegistry(), () -> config);
    }

    private void estimate() {
      service.resolveTravelTimeModelForRoute(routeUuid, worldId, Instant.now());
    }
  }
}
