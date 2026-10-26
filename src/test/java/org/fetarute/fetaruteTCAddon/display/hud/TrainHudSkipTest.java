package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
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
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.BossBarHudTemplate;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.storage.SampleTransitNetwork;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HUD：信号员令越过的站不当下一站，前方停靠列表里保留并标出不停车。 */
class TrainHudSkipTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private Route route;
  private TrainHudContextResolver resolver;
  private EtaService eta;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    SampleTransitNetwork net = new SampleTransitNetwork(storage);
    // KPO 停 → PPK 停 → HHU:1 停 → WYB:1 终到
    route = storage.route(net.ws, "WS-L", RoutePatternType.LOCAL, RouteOperationType.OPERATION);
    storage.stops(
        route,
        new String[] {"STOP", "SURC:S:KPO:1"},
        new String[] {"STOP", "SURC:S:PPK:1"},
        new String[] {"STOP", "SURC:S:HHU:1"},
        new String[] {"TERMINATE", "SURC:S:WYB:1"});

    RouteDefinitionCache routes = new RouteDefinitionCache(message -> {});
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
    LocaleManager locale = mock(LocaleManager.class);
    when(locale.text(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    when(locale.enumText(anyString(), any())).thenReturn("-");
    when(locale.getCurrentLocale()).thenReturn("zh_CN");
    resolver =
        new TrainHudContextResolver(
            plugin,
            locale,
            eta,
            routes,
            new RouteProgressRegistry(),
            mock(LayoverRegistry.class),
            templates,
            message -> {});
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private TrainHudContext atKpo() {
    return atIndex(0);
  }

  /** 交路进度停在这一下标（0 为 KPO，1 为 PPK）。 */
  private TrainHudContext atIndex(int index) {
    List<String> tags =
        new ArrayList<>(List.of("FTA_ROUTE_ID=" + route.id(), "FTA_ROUTE_INDEX=" + index));
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn("hud-train");
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags()).thenReturn(tags);
    return resolver.resolveContext(properties, true, 8.0).orElseThrow();
  }

  @Test
  void theNextStationSkipsTheOrderedStop() {
    assertEquals("PPK", placeholders(atKpo()).get("next_station_code"));

    resolver.setSkippedStops((train, routeUuid, index) -> "hud-train".equals(train) && index == 1);

    assertEquals("HHU", placeholders(atKpo()).get("next_station_code"));
  }

  private Map<String, String> placeholders(TrainHudContext context) {
    return resolver.buildPlaceholders(context, 0.0f);
  }

  /** 进度到了被越的站（正在通过）：越站的占位符清空，下一站是它后面那一站。 */
  @Test
  void passingTheSkippedStationClearsTheSkipAndKeepsTheNextStop() {
    resolver.setSkippedStops((train, routeUuid, index) -> "hud-train".equals(train) && index == 1);

    Map<String, String> values = placeholders(atIndex(1));

    assertEquals("HHU", values.get("next_station_code"));
    assertEquals("-", values.get("skip_station_code"));
    assertEquals("-", values.get("skip_passing"));
  }

  @Test
  void theSkippedStationIsAvailableToTheTemplatesOnlyWhileOrdered() {
    assertEquals("-", placeholders(atKpo()).get("skip_station_code"), "没有越站令时与别的可选占位符一样写 -");

    resolver.setSkippedStops((train, routeUuid, index) -> "hud-train".equals(train) && index == 1);

    assertEquals("PPK", placeholders(atKpo()).get("skip_station_code"));
  }

  @Test
  void theUpcomingListKeepsTheSkippedStationAndMarksIt() {
    resolver.setSkippedStops((train, routeUuid, index) -> "hud-train".equals(train) && index == 1);

    List<TrainHudContextResolver.UpcomingStop> upcoming =
        resolver.resolveUpcomingStops(atKpo(), 5).stops();

    assertEquals("PPK", upcoming.get(0).display().code());
    assertTrue(upcoming.get(0).skipped());
    assertFalse(upcoming.get(1).skipped());
  }

  @Test
  void theSkippedRowSaysNotStoppingInsteadOfAnArrivalTime() {
    resolver.setSkippedStops((train, routeUuid, index) -> "hud-train".equals(train) && index == 1);
    TrainHudContext context = atKpo();
    List<TrainHudContextResolver.UpcomingStop> upcoming =
        resolver.resolveUpcomingStops(context, 5).stops();

    Map<String, String> row =
        resolver.stopPlaceholders(
            placeholders(context), Optional.of(upcoming.get(0)), 1, context.currentLine());

    assertEquals("不停车", row.get("eta"));
    assertEquals("不停车", row.get("eta_status"));
  }

  /** 被越的站不在那里下车：换乘与晚点两项说明也不写。 */
  @Test
  void theSkippedRowDropsTransfersAndDelay() {
    TrainHudContext context = atKpo();
    TrainHudContextResolver.UpcomingStop stop =
        new TrainHudContextResolver.UpcomingStop(
            1,
            TrainHudContext.StationDisplay.of("平坪口", "PPK", "Ping Ping Kou"),
            EtaResult.unavailable("-", List.of()),
            "1",
            Optional.empty(),
            List.of(new TrainHudContext.Transfer("DS", "德胜线", "Desheng", "#F6A000")),
            java.util.OptionalLong.of(300L),
            false,
            true);

    Map<String, String> row =
        resolver.stopPlaceholders(
            placeholders(context), Optional.of(stop), 1, context.currentLine());

    assertEquals("-", row.get("transfer_lines"));
    assertEquals("-", row.get("delay_minutes"));
  }

  @Test
  void approachingTheSkippedStationTheTemplatesCanSayItIsPassedThrough() {
    resolver.setSkippedStops((train, routeUuid, index) -> "hud-train".equals(train) && index == 1);
    assertEquals("-", placeholders(atKpo()).get("skip_passing"), "还没接近被越的站");

    when(eta.getForTrain(
            org.mockito.ArgumentMatchers.eq("hud-train"),
            org.mockito.ArgumentMatchers.eq(
                new org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget.StopIndex(1))))
        .thenReturn(
            new EtaResult(
                true,
                "Arriving",
                0L,
                0,
                0,
                0,
                0,
                List.of(),
                org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaConfidence.HIGH));

    assertEquals("PPK", placeholders(atKpo()).get("skip_passing_code"));
  }

  @Test
  void passingTheSkippedStationGetsItsOwnStateWhenTheTemplateHasIt() {
    Map<String, String> passing = Map.of("skip_passing", "平坪口");
    Map<String, String> notPassing = Map.of("skip_passing", "-");

    assertEquals(
        HudState.SKIP_PASSING, HudStateTracker.applySkipPassing(HudState.IN_TRIP, passing, true));
    assertEquals(
        HudState.SKIP_PASSING, HudStateTracker.applySkipPassing(HudState.DEPARTING, passing, true));
    assertEquals(
        HudState.IN_TRIP, HudStateTracker.applySkipPassing(HudState.IN_TRIP, notPassing, true));
    assertEquals(
        HudState.IN_TRIP,
        HudStateTracker.applySkipPassing(HudState.IN_TRIP, passing, false),
        "旧模板没写这一状态");
    assertEquals(
        HudState.AT_STATION, HudStateTracker.applySkipPassing(HudState.AT_STATION, passing, true));
  }

  @Test
  void theDefaultTemplatesSayTheTrainPassesThrough() throws Exception {
    YamlConfiguration bundled;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("default_hud_template.yml")) {
      bundled =
          YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    Map<String, String> values = Map.of("skip_passing", "平坪口", "skip_station", "平坪口");
    for (String key : List.of("bossbar.template", "actionbar.template")) {
      BossBarHudTemplate template = BossBarHudTemplate.parse(bundled.getString(key), message -> {});
      assertTrue(template.defines(HudState.SKIP_PASSING), key);
      String line = template.resolveLine(HudState.SKIP_PASSING, 0L, values).orElseThrow();
      String text = HudText.apply(line, values).replaceAll("<[^<>]*>", "");
      assertTrue(text.contains("列车通过") && text.contains("平坪口"), text);
    }
  }
}
