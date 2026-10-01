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
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 未发车票据的走行模型：同一交路的车种推断在票据之间、站牌重算之间复用，交路定义变化后重新推断；数据取自交路缓存，不读库。 */
class EtaSpawnTrainConfigCacheTest {

  @Test
  // 站牌重算时每张票据都要估算走行；推断次数必须与票据数无关。
  void ticketsOfTheSameRouteInferOnce() {
    Fixture fixture = new Fixture();

    for (int i = 0; i < 25; i++) {
      fixture.estimate();
    }

    verify(fixture.definitions, times(1)).findRecord(fixture.routeUuid);
    verify(fixture.definitions, times(1)).listStops(fixture.definition.id());
  }

  @Test
  void routeDefinitionChangeTriggersReinference() {
    Fixture fixture = new Fixture();
    fixture.estimate();

    fixture.routeChangeListener.getValue().run();
    fixture.estimate();

    verify(fixture.definitions, times(2)).findRecord(fixture.routeUuid);
  }

  @Test
  void attachingConfigSourcesTriggersReinference() {
    Fixture fixture = new Fixture();
    fixture.estimate();

    fixture.service.attachConfigSources(new SignNodeRegistry(), () -> fixture.config);
    fixture.estimate();

    verify(fixture.definitions, times(2)).findRecord(fixture.routeUuid);
  }

  /** 一条交路，首站 CRET 指向一个未注册的车库（推断结果为默认车种）。 */
  private static final class Fixture {
    private final UUID routeUuid = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final RouteDefinition definition =
        new RouteDefinition(
            RouteId.of("SURC:L1:R1"),
            List.of(NodeId.of("SURC:S:OFL:1"), NodeId.of("SURC:S:HHU:1")),
            Optional.empty());
    private final RouteDefinitionCache definitions = mock(RouteDefinitionCache.class);
    private final ArgumentCaptor<Runnable> routeChangeListener =
        ArgumentCaptor.forClass(Runnable.class);
    private final ConfigManager.ConfigView config =
        ConfigManager.parse(new YamlConfiguration(), Logger.getLogger("eta-test"));
    private final EtaService service;

    private Fixture() {
      Instant now = Instant.now();
      Route route =
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
              now);
      when(definitions.findRecord(routeUuid))
          .thenReturn(
              Optional.of(
                  new RouteDefinitionCache.RouteRecord(
                      mock(Operator.class), mock(Line.class), route)));
      when(definitions.findById(routeUuid)).thenReturn(Optional.of(definition));
      when(definitions.listStops(definition.id()))
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

      service = new EtaService(new TrainSnapshotStore(), mock(RailGraphService.class), definitions);
      verify(definitions).addChangeListener(routeChangeListener.capture());
      service.attachConfigSources(new SignNodeRegistry(), () -> config);
    }

    private void estimate() {
      service.resolveTravelTimeModelForRoute(routeUuid, worldId, Instant.now());
    }
  }
}
