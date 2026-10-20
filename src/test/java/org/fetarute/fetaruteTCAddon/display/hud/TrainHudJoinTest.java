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
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.coupler.CoupleService;
import org.fetarute.fetaruteTCAddon.coupler.CouplerService;
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

/** HUD：回程支线的车去向写联挂后主干的终点，联挂站报联挂而不报终点站。 */
class TrainHudJoinTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private Route feeder;
  private Route trunk;
  private CoupleService coupling;
  private FetaruteTCAddon plugin;
  private TrainHudContextResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    SampleTransitNetwork net = new SampleTransitNetwork(storage);
    // 回程支线：葵坪 → 平坪口 → 海湖终到（联挂站）；主干：海湖 → 湾油埠终到
    feeder = storage.route(net.ws, "WS-BB", RoutePatternType.LOCAL, RouteOperationType.OPERATION);
    storage.stops(
        feeder,
        new String[] {"STOP", "SURC:S:KPO:1"},
        new String[] {"STOP", "SURC:S:PPK:1"},
        new String[] {"TERMINATE", "SURC:S:HHU:1"});
    trunk = storage.route(net.ws, "WS-RR", RoutePatternType.LOCAL, RouteOperationType.OPERATION);
    storage.stops(
        trunk, new String[] {"STOP", "SURC:S:HHU:1"}, new String[] {"TERMINATE", "SURC:S:WYB:1"});

    RouteDefinitionCache routes = new RouteDefinitionCache(message -> {});
    routes.reload(storage.provider());
    StationDirectory directory = new StationDirectory(routes, message -> {});
    directory.reload(storage.provider());

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(storage.provider()));
    plugin = mock(FetaruteTCAddon.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(plugin.getStationDirectory()).thenReturn(Optional.of(directory));
    coupling = mock(CoupleService.class);
    when(coupling.continuationOf(any())).thenReturn(Optional.empty());
    when(coupling.continuationOf(feeder.id()))
        .thenReturn(Optional.of(new CoupleService.Continuation(trunk.id(), 0)));
    CouplerService coupler = mock(CouplerService.class);
    when(coupler.coupling()).thenReturn(coupling);
    when(plugin.getCouplerService()).thenReturn(Optional.of(coupler));
    HudTemplateService templates = new HudTemplateService(storageManager, message -> {});
    templates.reload();
    EtaService eta = mock(EtaService.class);
    when(eta.getForTrain(any(), any())).thenReturn(EtaResult.unavailable("-", List.of()));

    LocaleManager locale = mock(LocaleManager.class);
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
            message -> {});
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private TrainHudContext resolve(Route route, int index) {
    List<String> values =
        new ArrayList<>(List.of("FTA_ROUTE_ID=" + route.id(), "FTA_ROUTE_INDEX=" + index));
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn("hud-train");
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags()).thenReturn(values);
    return resolver.resolveContext(properties, true, 8.0).orElseThrow();
  }

  @Test
  void aFeederHeadsForTheTrunkTerminusAndNamesTheJoinStation() {
    TrainHudContext context = resolve(feeder, 0);
    Map<String, String> values = resolver.buildPlaceholders(context, 0.0f);

    assertEquals("湾油埠", values.get("dest_eop"), "联挂后接着跑主干：去向是主干的终点");
    assertEquals("海湖", values.get("join_station"));
    assertEquals("HHU", values.get("join_station_code"));
    assertEquals("海湖", context.joinStation().label());
  }

  @Test
  void otherRoutesKeepTheirOwnTerminusAndHaveNoJoin() {
    TrainHudContext context = resolve(trunk, 0);
    Map<String, String> values = resolver.buildPlaceholders(context, 0.0f);

    assertEquals("湾油埠", values.get("dest_eop"));
    assertEquals("-", values.get("join_station"));
    assertTrue(context.joinStation().isEmpty());

    when(plugin.getCouplerService()).thenReturn(Optional.empty());
    assertEquals(
        "海湖",
        resolver.buildPlaceholders(resolve(feeder, 0), 0.0f).get("dest_eop"),
        "联挂服务没加载：照本交路的终点");
  }

  /** 后续站点对话框：联挂站那一行写联挂后开往哪里；分行站那一行写本节车厢分行后去哪；别的行都没有。 */
  @Test
  void tripRowsCarryTheJoinAndDivisionNotesOnTheirOwnStation() {
    TrainHudContext context = resolve(feeder, 0);
    Map<String, String> base = resolver.buildPlaceholders(context, 0.0f);
    base.put("division_station_code", "PPK");
    base.put("car_destination", "湾油埠");
    base.put("car_terminates", "-");
    List<TrainHudContextResolver.UpcomingStop> stops =
        resolver.resolveUpcomingStops(context, 5).stops();
    Map<String, Map<String, String>> byCode = new java.util.HashMap<>();
    for (int i = 0; i < stops.size(); i++) {
      Map<String, String> row =
          resolver.stopPlaceholders(base, Optional.of(stops.get(i)), i + 1, Optional.empty());
      byCode.put(row.get("station_code"), row);
    }

    assertEquals("湾油埠", byCode.get("HHU").get("stop_join_destination"));
    assertEquals("-", byCode.get("PPK").get("stop_join_destination"));
    assertEquals("湾油埠", byCode.get("PPK").get("stop_car_destination"));
    assertEquals("-", byCode.get("PPK").get("stop_car_terminates"));
    assertEquals("-", byCode.get("HHU").get("stop_car_destination"));
  }

  @Test
  void joinStatesOnlyReplaceTheTerminusWhenTheTemplateDefinesThem() {
    BossBarHudTemplate legacy =
        BossBarHudTemplate.parse("TERM_ARRIVING: 终点\nAT_LAST_STATION: 终点站", message -> {});
    BossBarHudTemplate current =
        BossBarHudTemplate.parse(
            "TERM_ARRIVING: 终点\nJOIN_ARRIVING: 联挂\nAT_LAST_STATION: 终点站\nAT_JOIN_STATION: 本站联挂",
            message -> {});

    assertEquals(
        HudState.TERM_ARRIVING,
        HudStateTracker.applyJoin(
            HudState.TERM_ARRIVING,
            true,
            legacy.defines(HudState.JOIN_ARRIVING),
            legacy.defines(HudState.AT_JOIN_STATION)),
        "旧模板没有这一状态：照旧显示");
    assertEquals(
        HudState.JOIN_ARRIVING,
        HudStateTracker.applyJoin(
            HudState.TERM_ARRIVING,
            true,
            current.defines(HudState.JOIN_ARRIVING),
            current.defines(HudState.AT_JOIN_STATION)));
    assertEquals(
        HudState.AT_JOIN_STATION,
        HudStateTracker.applyJoin(HudState.AT_LAST_STATION, true, true, true));
    assertEquals(
        HudState.AT_LAST_STATION,
        HudStateTracker.applyJoin(HudState.AT_LAST_STATION, false, true, true),
        "不联挂");
    assertEquals(HudState.IN_TRIP, HudStateTracker.applyJoin(HudState.IN_TRIP, true, true, true));
  }
}
