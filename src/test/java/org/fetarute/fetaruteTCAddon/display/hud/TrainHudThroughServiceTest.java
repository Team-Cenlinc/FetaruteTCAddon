package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
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

/** HUD：直通运转换线后线路跟当前线路走；回库车越过运营终点后显示「回库」；换乘线路按该站所属线路排除本车。 */
class TrainHudThroughServiceTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private SampleTransitNetwork net;
  private Route wsThrough;
  private TrainHudContextResolver resolver;
  private EtaService eta;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    net = new SampleTransitNetwork(storage);
    // KPO 停 → PPK 停（本站起直通 DS；运行时写入的标签会是指令原文的小写）→ HHU:1 停 → WYB:1 终到
    wsThrough =
        storage.route(net.ws, "WS-DS", RoutePatternType.LOCAL, RouteOperationType.OPERATION);
    storage.stops(
        wsThrough,
        new String[] {"STOP", "SURC:S:KPO:1"},
        new String[] {"STOP", "SURC:S:PPK:1", "CHANGE:surc:ds"},
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

  private Optional<TrainHudContext> resolve(String... tags) {
    List<String> values = new ArrayList<>(List.of(tags));
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn("hud-train");
    when(properties.hasTags()).thenReturn(!values.isEmpty());
    when(properties.getTags()).thenReturn(values);
    return resolver.resolveContext(properties, true, 8.0);
  }

  private Map<String, String> placeholders(TrainHudContext context) {
    return resolver.buildPlaceholders(context, 0.0f);
  }

  @Test
  void beforeTheChangeShowsTheRouteLineAndAnnouncesTheThroughService() {
    TrainHudContext context =
        resolve(
                "FTA_ROUTE_ID=" + wsThrough.id(),
                "FTA_ROUTE_INDEX=0",
                "FTA_OPERATOR_CODE=SURC",
                "FTA_LINE_CODE=WS")
            .orElseThrow();
    Map<String, String> values = placeholders(context);

    assertEquals("西海线", values.get("line"));
    assertEquals("#E60012", values.get("line_color_tag"));
    assertEquals("东山线", values.get("through_line"));
    assertEquals("DS", values.get("through_line_code"));
    assertEquals("平坪口", values.get("through_station"));
    assertEquals("PPK", values.get("through_station_code"));
    assertFalse(context.outOfService());
  }

  @Test
  void afterTheChangeFollowsTheLineTagsWhateverTheirCase() {
    TrainHudContext context =
        resolve(
                "FTA_ROUTE_ID=" + wsThrough.id(),
                "FTA_ROUTE_INDEX=2",
                "FTA_OPERATOR_CODE=surc",
                "FTA_LINE_CODE=ds")
            .orElseThrow();
    Map<String, String> values = placeholders(context);

    assertEquals("东山线", values.get("line"));
    assertEquals("DS", values.get("line_code"));
    assertEquals("white", values.get("line_color_tag"), "DS 没有线路色：与交路本身的线路无关");
    assertEquals("-", values.get("through_line"), "前方不再换线");
    assertEquals("SURC:WS:WS-DS", values.get("route_id"), "交路仍是交路本身");
    assertEquals("SURC", values.get("operator"));
  }

  @Test
  void withoutLineTagsTheRouteLineIsShown() {
    // 运行时执行 CHANGE 必然写标签；没有标签就是没收到换线通知，显示交路本身的线路，不按进度去猜。
    TrainHudContext context =
        resolve("FTA_ROUTE_ID=" + wsThrough.id(), "FTA_ROUTE_INDEX=1").orElseThrow();
    assertEquals(Optional.of(new RouteLineChanges.LineRef("SURC", "WS")), context.currentLine());
    assertEquals("西海线", placeholders(context).get("line"));
  }

  @Test
  void upcomingStopsCarryTheirOwnLine() {
    TrainHudContext context =
        resolve(
                "FTA_ROUTE_ID=" + wsThrough.id(),
                "FTA_ROUTE_INDEX=0",
                "FTA_OPERATOR_CODE=SURC",
                "FTA_LINE_CODE=WS")
            .orElseThrow();
    List<TrainHudContextResolver.UpcomingStop> upcoming =
        resolver.resolveUpcomingStops(context, 5).stops();

    assertEquals(3, upcoming.size());
    assertTrue(
        upcoming.get(0).line().orElseThrow().sameLine(new RouteLineChanges.LineRef("SURC", "DS")));
    Map<String, String> row = placeholders(context);
    resolver.applyLinePlaceholders(row, upcoming.get(1).line().orElseThrow());
    assertEquals("东山线", row.get("line"));
  }

  @Test
  void returnTrainPastTheEndOfOperationIsOutOfService() {
    // DS-R（回库）：WYB:2 停 → HHU:2 终到（运营终点）→ LWN 车库
    TrainHudContext atEnd =
        resolve("FTA_ROUTE_ID=" + net.dsR.id(), "FTA_ROUTE_INDEX=1").orElseThrow();
    assertFalse(atEnd.outOfService());

    TrainHudContext past =
        resolve("FTA_ROUTE_ID=" + net.dsR.id(), "FTA_ROUTE_INDEX=2").orElseThrow();
    assertTrue(past.outOfService());
    Map<String, String> values = placeholders(past);
    assertEquals("回库", values.get("dest_eop"));
    assertEquals("-", values.get("next_station"), "不能退回去显示已经过的运营终点");
  }

  @Test
  void outOfServiceStateOnlyReplacesWhenTheTemplateDefinesIt() {
    BossBarHudTemplate legacy = BossBarHudTemplate.parse("IN_TRIP: 运行中\nIDLE: 临时停车", message -> {});
    BossBarHudTemplate current =
        BossBarHudTemplate.parse("IN_TRIP: 运行中\nOUT_OF_SERVICE: 回库", message -> {});

    assertEquals(
        HudState.IN_TRIP,
        HudStateTracker.applyOutOfService(
            HudState.IN_TRIP, true, legacy.defines(HudState.OUT_OF_SERVICE)),
        "旧模板没有这一状态：照旧显示");
    assertEquals(
        HudState.OUT_OF_SERVICE,
        HudStateTracker.applyOutOfService(
            HudState.IN_TRIP, true, current.defines(HudState.OUT_OF_SERVICE)));
    assertEquals(
        HudState.ON_LAYOVER,
        HudStateTracker.applyOutOfService(HudState.ON_LAYOVER, true, true),
        "折返待命优先");
    assertEquals(
        HudState.IN_TRIP, HudStateTracker.applyOutOfService(HudState.IN_TRIP, false, true));
    assertEquals(Optional.of("回库"), current.resolveLine(HudState.OUT_OF_SERVICE, 0L, Map.of()));
  }

  @Test
  void nextStopTransfersLeaveOutBothLinesOfTheThroughChange() {
    // 下一站 PPK：本车以 WS 到达、以 DS 发车，两条都不是换乘；同组的坪洲（FTA）停狮岭线。
    TrainHudContext context =
        resolve(
                "FTA_ROUTE_ID=" + wsThrough.id(),
                "FTA_ROUTE_INDEX=0",
                "FTA_OPERATOR_CODE=SURC",
                "FTA_LINE_CODE=WS")
            .orElseThrow();

    assertEquals(
        List.of(new TrainHudContext.Transfer("SL", "狮岭线", "狮岭线", "#00A0E9")),
        context.nextStopTransfers());
    assertEquals("<#00A0E9>█</#00A0E9>SL", placeholders(context).get("transfer_lines"));
  }

  @Test
  void afterTheChangeTheLineTheTrainLeftIsATransfer() {
    TrainHudContext context =
        resolve(
                "FTA_ROUTE_ID=" + wsThrough.id(),
                "FTA_ROUTE_INDEX=0",
                "FTA_OPERATOR_CODE=SURC",
                "FTA_LINE_CODE=WS")
            .orElseThrow();
    List<TrainHudContextResolver.UpcomingStop> upcoming =
        resolver.resolveUpcomingStops(context, 5).stops();

    assertEquals(List.of(), upcoming.get(1).transfers(), "海湖只停东山线，本车已是东山线");
    assertEquals(
        List.of("WS"),
        upcoming.get(2).transfers().stream().map(TrainHudContext.Transfer::code).toList(),
        "湾油埠的西海线是本车换线前的线路，在这里可以换乘");
    assertTrue(upcoming.get(2).terminal());
  }

  @Test
  void stationWithoutOtherLinesHasNoTransferPlaceholders() {
    TrainHudContext context =
        resolve(
                "FTA_ROUTE_ID=" + wsThrough.id(),
                "FTA_ROUTE_INDEX=1",
                "FTA_OPERATOR_CODE=surc",
                "FTA_LINE_CODE=ds")
            .orElseThrow();
    Map<String, String> values = placeholders(context);

    assertEquals("-", values.get("transfer_lines"));
    assertEquals("-", values.get("transfer_line_names"));
  }

  @Test
  void delayMinutesFollowTheTimetableDeviationAtTheNextStop() {
    when(eta.arrivalDeviationSeconds(eq("hud-train"), eq(1), any()))
        .thenReturn(OptionalLong.of(185));
    TrainHudContext late =
        resolve("FTA_ROUTE_ID=" + wsThrough.id(), "FTA_ROUTE_INDEX=0").orElseThrow();
    assertEquals("3", placeholders(late).get("delay_minutes"));

    when(eta.arrivalDeviationSeconds(eq("hud-train"), eq(1), any()))
        .thenReturn(OptionalLong.of(45));
    TrainHudContext onTime =
        resolve("FTA_ROUTE_ID=" + wsThrough.id(), "FTA_ROUTE_INDEX=0").orElseThrow();
    assertEquals("-", placeholders(onTime).get("delay_minutes"), "不足 1 分钟算准点");
  }
}
