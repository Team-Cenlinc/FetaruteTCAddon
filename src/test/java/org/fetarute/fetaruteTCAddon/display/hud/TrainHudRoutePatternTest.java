package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.storage.SampleTransitNetwork;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HUD 的种别（各站停/快速/特快…）以库里交路的 pattern_type 为准。
 *
 * <p>2026-09-29 实服：MT-3（RAPID）的车没有自己的车库出车，全靠折返复用接班，复用只改交路/线路/终点标签，不改出车时写下的 {@code FTA_PATTERN}；HUD
 * 又优先读这个标签，于是快速列车一直显示「各站停」。
 */
class TrainHudRoutePatternTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private Route rapid;
  private RouteDefinitionCache routes;
  private StationDirectory directory;
  private FetaruteTCAddon plugin;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    SampleTransitNetwork net = new SampleTransitNetwork(storage);
    rapid = storage.route(net.ws, "WS-EXP", RoutePatternType.RAPID, RouteOperationType.OPERATION);
    storage.stops(
        rapid,
        new String[] {"STOP", "SURC:S:KPO:1"},
        new String[] {"STOP", "SURC:S:PPK:1"},
        new String[] {"TERMINATE", "SURC:S:WYB:1"});

    routes = new RouteDefinitionCache(message -> {});
    routes.reload(storage.provider());
    directory = new StationDirectory(routes, message -> {});
    directory.reload(storage.provider());
    plugin = mock(FetaruteTCAddon.class);
    when(plugin.getStationDirectory()).thenReturn(Optional.of(directory));
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private TrainHudContextResolver resolver(boolean storageReady) {
    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(storageReady);
    when(storageManager.provider())
        .thenReturn(storageReady ? Optional.of(storage.provider()) : Optional.empty());
    when(plugin.getStorageManager()).thenReturn(storageManager);
    HudTemplateService templates = new HudTemplateService(storageManager, message -> {});
    templates.reload();
    EtaService eta = mock(EtaService.class);
    when(eta.getForTrain(any(), any())).thenReturn(EtaResult.unavailable("-", List.of()));
    LocaleManager locale = mock(LocaleManager.class);
    when(locale.text(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    when(locale.enumText(anyString(), any())).thenReturn("-");
    return new TrainHudContextResolver(
        plugin,
        locale,
        eta,
        routes,
        new RouteProgressRegistry(),
        mock(LayoverRegistry.class),
        templates,
        message -> {});
  }

  private static Optional<TrainHudContext> resolve(
      TrainHudContextResolver resolver, String... tags) {
    List<String> values = new ArrayList<>(List.of(tags));
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn("hud-train");
    when(properties.hasTags()).thenReturn(!values.isEmpty());
    when(properties.getTags()).thenReturn(values);
    return resolver.resolveContext(properties, true, 8.0);
  }

  /** 复用前跑的是各站停交路：出车时写下的 LOCAL 不能盖过现在这条快速交路。 */
  @Test
  void theRoutePatternInTheDatabaseWinsOverAStaleTag() {
    TrainHudContextResolver resolver = resolver(true);
    TrainHudContext context =
        resolve(resolver, "FTA_ROUTE_ID=" + rapid.id(), "FTA_ROUTE_INDEX=0", "FTA_PATTERN=LOCAL")
            .orElseThrow();

    assertEquals(Optional.of(RoutePatternType.RAPID), context.routePatternType());
    assertEquals("快速", resolver.buildPlaceholders(context, 0.0f).get("route_pattern_zh_CN"));
  }

  @Test
  void aTrainWithoutThePatternTagStillGetsTheRoutePattern() {
    TrainHudContextResolver resolver = resolver(true);
    TrainHudContext context =
        resolve(resolver, "FTA_ROUTE_ID=" + rapid.id(), "FTA_ROUTE_INDEX=0").orElseThrow();

    assertEquals(Optional.of(RoutePatternType.RAPID), context.routePatternType());
  }

  /** 存储没就绪（或库里查不到交路）时才退回标签，不让种别整段消失。 */
  @Test
  void theTagIsOnlyTheFallbackWhenTheDatabaseCannotAnswer() {
    TrainHudContextResolver resolver = resolver(false);
    TrainHudContext context =
        resolve(resolver, "FTA_ROUTE_ID=" + rapid.id(), "FTA_ROUTE_INDEX=0", "FTA_PATTERN=EXPRESS")
            .orElseThrow();

    assertTrue(context.routePatternType().isPresent());
    assertEquals(RoutePatternType.EXPRESS, context.routePatternType().orElseThrow());
  }
}
