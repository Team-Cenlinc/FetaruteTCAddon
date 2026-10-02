package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

/** 最小 RuntimeDispatchService 装配，供只关心单个判定的用例复用。 */
final class TestServices {

  private TestServices() {}

  static RuntimeDispatchService minimal(List<String> debug) {
    return minimal(debug, mock(RouteDefinitionCache.class));
  }

  /** 需要控制时间的用例（如发车门锁超时）用这个变体注入自己的时钟。 */
  static RuntimeDispatchService minimal(
      List<String> debug, java.util.function.Supplier<java.time.Instant> clock) {
    return minimal(debug, mock(RouteDefinitionCache.class), clock);
  }

  /** 需要对 RouteStop 序列做断言的用例（如入库走行证明）用这个变体注入自己的 cache。 */
  static RuntimeDispatchService minimal(List<String> debug, RouteDefinitionCache routeDefinitions) {
    return minimal(debug, routeDefinitions, java.time.Instant::now);
  }

  /** 需要钓住“OBSERVE_ONLY 不得改账本”的用例用这个变体。 */
  static RuntimeDispatchService minimal(
      List<String> debug,
      SmartDispatcherMode mode,
      java.util.function.Supplier<java.time.Instant> clock) {
    return minimal(debug, mock(RouteDefinitionCache.class), clock, mode);
  }

  static RuntimeDispatchService minimal(
      List<String> debug,
      RouteDefinitionCache routeDefinitions,
      java.util.function.Supplier<java.time.Instant> clock) {
    return minimal(debug, routeDefinitions, clock, SmartDispatcherMode.ENFORCE);
  }

  /** 需要直接向账本播种占用的用例用这个变体（服务不暴露 occupancy manager）。 */
  static RuntimeDispatchService minimal(
      List<String> debug,
      SimpleOccupancyManager occupancy,
      SmartDispatcherMode mode,
      java.util.function.Supplier<java.time.Instant> clock) {
    return minimal(debug, mock(RouteDefinitionCache.class), clock, mode, occupancy);
  }

  static RuntimeDispatchService minimal(
      List<String> debug,
      RouteDefinitionCache routeDefinitions,
      java.util.function.Supplier<java.time.Instant> clock,
      SmartDispatcherMode mode) {
    return minimal(
        debug,
        routeDefinitions,
        clock,
        mode,
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()));
  }

  static RuntimeDispatchService minimal(
      List<String> debug,
      RouteDefinitionCache routeDefinitions,
      java.util.function.Supplier<java.time.Instant> clock,
      SmartDispatcherMode mode,
      SimpleOccupancyManager occupancy) {
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(20, 20.0);
    when(configManager.current())
        .thenReturn(
            new ConfigManager.ConfigView(
                base.configVersion(),
                base.debugEnabled(),
                base.locale(),
                base.storageSettings(),
                base.graphSettings(),
                base.autoStationSettings(),
                base.runtimeSettings(),
                base.spawnSettings(),
                base.trainConfigSettings(),
                base.reclaimSettings(),
                new ConfigManager.SmartDispatcherSettings(mode),
                base.healthSettings()));
    return new RuntimeDispatchService(
        occupancy,
        mock(RailGraphService.class),
        routeDefinitions,
        new RouteProgressRegistry(),
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        new TrainConfigResolver(),
        debug::add,
        clock);
  }
}
