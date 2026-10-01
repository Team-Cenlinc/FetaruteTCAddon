package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
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

/** 三块 HUD 共用一个解析器：同一 tick 内同一列车只算一次，停靠表里不随列车变化的部分按交路缓存。 */
class TrainHudFrameSharingTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private SampleTransitNetwork net;
  private RouteDefinitionCache routes;
  private LocaleManager locale;
  private EtaService eta;
  private final AtomicLong tick = new AtomicLong(100L);
  private TrainHudContextResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    net = new SampleTransitNetwork(storage);
    routes = new RouteDefinitionCache(message -> {});
    routes.reload(storage.provider());
    StationDirectory directory = new StationDirectory(routes, message -> {});
    directory.reload(storage.provider());

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(storage.provider()));
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(plugin.getStationDirectory()).thenReturn(Optional.of(directory));
    HudTemplateService templates = new HudTemplateService(storageManager, message -> {});
    templates.reload();
    eta = mock(EtaService.class);
    when(eta.getForTrain(any(), any())).thenReturn(EtaResult.unavailable("-", List.of()));
    locale = mock(LocaleManager.class);
    when(locale.text(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    when(locale.enumText(anyString(), any())).thenReturn("-");
    resolver =
        new TrainHudContextResolver(
            plugin,
            locale,
            eta,
            routes,
            new RouteProgressRegistry(),
            mock(LayoverRegistry.class),
            templates,
            message -> {},
            tick::get);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private TrainHudContext context() {
    TrainProperties properties = mock(TrainProperties.class);
    List<String> tags =
        new ArrayList<>(List.of("FTA_ROUTE_ID=" + net.ws1.id(), "FTA_ROUTE_INDEX=0"));
    when(properties.getTrainName()).thenReturn("hud-train");
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags()).thenReturn(tags);
    return resolver.resolveContext(properties, true, 8.0).orElseThrow();
  }

  @Test
  void placeholdersAreBuiltOncePerTickAndEachChannelGetsItsOwnCopy() {
    TrainHudContext context = context();

    Map<String, String> bossBar = resolver.buildPlaceholders(context, 0.25f);
    long perBuild = unitLookups();
    Map<String, String> actionBar = resolver.buildPlaceholders(context, 0.75f);

    assertEquals(perBuild, unitLookups(), "同一 tick 第二块 HUD 不再重算");
    assertNotSame(bossBar, actionBar);
    assertEquals("0.250", bossBar.get("progress"), "进度是各块 HUD 自己的");
    assertEquals("0.750", actionBar.get("progress"));

    tick.incrementAndGet();
    resolver.buildPlaceholders(context, 0.25f);
    assertEquals(perBuild * 2, unitLookups(), "下一个 tick 重算");
  }

  private long unitLookups() {
    return mockingDetails(locale).getInvocations().stream()
        .filter(call -> call.getArguments().length > 0)
        .filter(call -> "display.hud.bossbar.unit.kmh".equals(call.getArguments()[0]))
        .count();
  }

  @Test
  void upcomingStopsShareTheStaticTableAcrossTicksAndOnlyDisplayedRowsAskForEta() {
    TrainHudContext context = context();
    List<TrainHudContextResolver.StopInfo> first = resolver.upcomingStops(context);

    tick.incrementAndGet();
    List<TrainHudContextResolver.StopInfo> second = resolver.upcomingStops(context);

    assertSame(first.get(0), second.get(0), "停靠表实例与目录版本没变：沿用同一张静态表");
    resolver.upcomingStop(context, second, 0);
    resolver.upcomingStop(context, second, 0);
    verify(eta, times(1)).getForTrain("hud-train", new EtaTarget.StopIndex(first.get(0).index()));
  }

  @Test
  void routeCacheReloadRebuildsTheStaticTable() {
    TrainHudContext context = context();
    List<TrainHudContextResolver.StopInfo> before = resolver.upcomingStops(context);

    routes.reload(storage.provider());
    tick.incrementAndGet();
    List<TrainHudContextResolver.StopInfo> after = resolver.upcomingStops(context());

    assertNotSame(before.get(0), after.get(0));
    assertEquals(before.get(0).display(), after.get(0).display());
  }
}
