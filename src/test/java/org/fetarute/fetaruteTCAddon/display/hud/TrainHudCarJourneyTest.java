package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.coupler.CoupleService;
import org.fetarute.fetaruteTCAddon.coupler.CouplerService;
import org.fetarute.fetaruteTCAddon.coupler.DecoupleService;
import org.fetarute.fetaruteTCAddon.coupler.DivisionForecast;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
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

/** 分行时按玩家所在车厢的行程：去向、前方各站、本节车厢到此止的状态与换乘提示；待命车下一趟的发车时刻。 */
class TrainHudCarJourneyTest {

  private static final String TRAIN = "hud-train";

  @TempDir Path dir;
  private TransitTestStorage storage;
  private RouteDefinitionCache routes;
  private Route trunk;
  private Route branch;
  private String trunkId;
  private String branchId;
  private FetaruteTCAddon plugin;
  private DecoupleService decoupling;
  private LayoverRegistry layovers;
  private EtaService eta;
  private LocaleManager locale;
  private HudTemplateService templates;
  private TrainHudContextResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    SampleTransitNetwork net = new SampleTransitNetwork(storage);
    station(net, "NDU", "南渡", "Nandu");
    station(net, "SHW", "沙湾", "Shawan");
    // 主干：葵坪 → 平坪口（分行站）→ 海湖 → 湾油埠终到；支线：平坪口 → 沙湾 → 南渡终到
    trunk = storage.route(net.ws, "WS-M", RoutePatternType.LOCAL, RouteOperationType.OPERATION);
    storage.stops(
        trunk,
        new String[] {"STOP", "SURC:S:KPO:1"},
        new String[] {"STOP", "SURC:S:PPK:1"},
        new String[] {"STOP", "SURC:S:HHU:1"},
        new String[] {"TERMINATE", "SURC:S:WYB:1"});
    branch = storage.route(net.ws, "WS-N", RoutePatternType.LOCAL, RouteOperationType.OPERATION);
    storage.stops(
        branch,
        new String[] {"STOP", "SURC:S:PPK:2"},
        new String[] {"STOP", "SURC:S:SHW:1"},
        new String[] {"TERMINATE", "SURC:S:NDU:1"});

    routes = new RouteDefinitionCache(message -> {});
    routes.reload(storage.provider());
    trunkId = routes.findById(trunk.id()).orElseThrow().id().value();
    branchId = routes.findById(branch.id()).orElseThrow().id().value();
    StationDirectory directory = new StationDirectory(routes, message -> {});
    directory.reload(storage.provider());

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(storage.provider()));
    plugin = mock(FetaruteTCAddon.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(plugin.getStationDirectory()).thenReturn(Optional.of(directory));
    CoupleService coupling = mock(CoupleService.class);
    when(coupling.continuationOf(any())).thenReturn(Optional.empty());
    decoupling = mock(DecoupleService.class);
    when(decoupling.forecast(anyString())).thenReturn(Optional.empty());
    CouplerService coupler = mock(CouplerService.class);
    when(coupler.coupling()).thenReturn(coupling);
    when(coupler.decoupling()).thenReturn(decoupling);
    when(plugin.getCouplerService()).thenReturn(Optional.of(coupler));
    templates = new HudTemplateService(storageManager, message -> {});
    templates.reload();
    eta = mock(EtaService.class);
    when(eta.getForTrain(any(), any())).thenReturn(EtaResult.unavailable("-", List.of()));
    // 本车交路上的站都晚点 10 分钟：另一条交路上的站不该问到本车的晚点
    when(eta.arrivalDeviationSeconds(any(), anyInt())).thenReturn(OptionalLong.of(600L));

    locale = mock(LocaleManager.class);
    when(locale.text(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    when(locale.enumText(anyString(), any())).thenReturn("-");
    layovers = mock(LayoverRegistry.class);
    when(layovers.get(anyString())).thenReturn(Optional.empty());
    resolver = newResolver(() -> -1L);
  }

  private TrainHudContextResolver newResolver(LongSupplier tick) {
    return new TrainHudContextResolver(
        plugin,
        locale,
        eta,
        routes,
        new RouteProgressRegistry(),
        layovers,
        templates,
        message -> {},
        tick);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private void station(SampleTransitNetwork net, String code, String name, String lang2) {
    Instant now = Instant.now();
    storage
        .provider()
        .stations()
        .save(
            new Station(
                UUID.randomUUID(),
                code,
                net.surcOp.id(),
                Optional.empty(),
                name,
                Optional.of(lang2),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Map.of(),
                now,
                now));
  }

  private TrainHudContext resolve(Route route, int index) {
    return resolve(resolver, route, index);
  }

  private static TrainHudContext resolve(TrainHudContextResolver resolver, Route route, int index) {
    List<String> values =
        new ArrayList<>(List.of("FTA_ROUTE_ID=" + route.id(), "FTA_ROUTE_INDEX=" + index));
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(TRAIN);
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags()).thenReturn(values);
    return resolver.resolveContext(properties, true, 8.0).orElseThrow();
  }

  /** 前 4 节留下跑完主干到湾油埠；后 2 节在平坪口解下，改跑支线到南渡。 */
  private DivisionForecast dividing() {
    return new DivisionForecast(
        trunkId,
        1,
        "SURC:PPK",
        "平坪口",
        List.of(
            new DivisionForecast.Part(
                4, false, Optional.of("SURC:WYB"), "湾油埠", 2, Optional.of(trunkId), 1),
            new DivisionForecast.Part(
                2, true, Optional.of("SURC:NDU"), "南渡", 2, Optional.of(branchId), 0)));
  }

  /** 后 {@code rearCars} 节在平坪口解下后不再载客（就地待命）。 */
  private DivisionForecast rearEnds(int frontCars, int rearCars) {
    return new DivisionForecast(
        trunkId,
        1,
        "SURC:PPK",
        "平坪口",
        List.of(
            new DivisionForecast.Part(
                frontCars, false, Optional.of("SURC:WYB"), "湾油埠", 2, Optional.of(trunkId), 1),
            new DivisionForecast.Part(rearCars, true, Optional.empty(), "")));
  }

  private Map<String, String> forCar(TrainHudContext context, int carriageNo) {
    Map<String, String> values = resolver.buildPlaceholders(context, 0.0f);
    resolver.putDivisionPlaceholders(values, TRAIN, carriageNo);
    return values;
  }

  private static List<String> codes(List<TrainHudContextResolver.StopInfo> stops) {
    return stops.stream().map(stop -> stop.display().code()).toList();
  }

  @Test
  void aCarHeadingElsewhereShowsItsOwnDestination() {
    when(decoupling.forecast(TRAIN)).thenReturn(Optional.of(dividing()));
    TrainHudContext context = resolve(trunk, 0);

    Map<String, String> rear = forCar(context, 5);
    assertEquals("南渡", rear.get("dest_eop"), "后 2 节分行后开往南渡：乘客留在车里到的是南渡");
    assertEquals("NDU", rear.get("dest_eop_code"));
    assertEquals("Nandu", rear.get("dest_eop_lang2"));
    assertEquals("南渡", rear.get("car_destination"));

    Map<String, String> front = forCar(context, 2);
    assertEquals("湾油埠", front.get("dest_eop"), "留下的一段跑完本交路：与整列车相同");
    assertEquals("WYB", front.get("dest_eop_code"));

    Map<String, String> unknown = forCar(context, 0);
    assertEquals("湾油埠", unknown.get("dest_eop"), "不知道第几节：照整列车");
    assertEquals("-", unknown.get("car_destination"));

    when(decoupling.forecast(TRAIN)).thenReturn(Optional.empty());
    assertEquals("湾油埠", forCar(context, 5).get("dest_eop"), "不分行：照整列车");
  }

  @Test
  void aCarThatSwitchesRouteListsThatRoutesStopsAfterTheDivision() {
    when(decoupling.forecast(TRAIN)).thenReturn(Optional.of(dividing()));
    TrainHudContext context = resolve(trunk, 0);

    List<TrainHudContextResolver.StopInfo> rear = resolver.upcomingStops(context, 5);
    assertEquals(List.of("PPK", "SHW", "NDU"), codes(rear));
    assertEquals(
        List.of(false, false, true),
        rear.stream().map(TrainHudContextResolver.StopInfo::terminal).toList(),
        "分行站不是这节车厢的终点，南渡才是");
    assertEquals(
        List.of(false, true, true),
        rear.stream().map(TrainHudContextResolver.StopInfo::otherRoute).toList());

    assertEquals(List.of("PPK", "HHU", "WYB"), codes(resolver.upcomingStops(context, 1)));
    assertEquals(List.of("PPK", "HHU", "WYB"), codes(resolver.upcomingStops(context, 0)), "不知道第几节");

    // 另一条交路上的站：下标不是本车交路的，不问本车的晚点
    List<TrainHudContextResolver.UpcomingStop> rows = new ArrayList<>();
    for (int i = 0; i < rear.size(); i++) {
      rows.add(resolver.upcomingStop(context, rear, i));
    }
    assertEquals(OptionalLong.of(600L), rows.get(0).delaySeconds());
    assertEquals(OptionalLong.empty(), rows.get(1).delaySeconds());
    assertEquals(OptionalLong.empty(), rows.get(2).delaySeconds());
    assertTrue(rows.get(2).terminal());
    assertEquals(List.of(1, 2, 3), rows.stream().map(row -> row.sequence()).toList());

    // 同一 tick 里两段车厢的列表在分行站之后不同：同一行序不能串到另一段去
    TrainHudContextResolver sameTick = newResolver(() -> 100L);
    TrainHudContext shared = resolve(sameTick, trunk, 0);
    assertEquals(
        "SHW",
        sameTick.upcomingStop(shared, sameTick.upcomingStops(shared, 5), 1).display().code());
    assertEquals(
        "HHU",
        sameTick.upcomingStop(shared, sameTick.upcomingStops(shared, 1), 1).display().code());
  }

  @Test
  void aCarThatEndsAtTheDivisionStopsThereAndIsToldWhereToChange() {
    when(decoupling.forecast(TRAIN)).thenReturn(Optional.of(rearEnds(4, 2)));
    TrainHudContext context = resolve(trunk, 0);

    List<TrainHudContextResolver.StopInfo> rear = resolver.upcomingStops(context, 6);
    assertEquals(List.of("PPK"), codes(rear), "这节车厢到分行站为止");
    assertTrue(rear.get(0).terminal(), "分行站记为这节车厢的终点");

    Map<String, String> values = forCar(context, 6);
    assertEquals("湾油埠", values.get("dest_eop"), "这节车厢不再载客：去向照整列车");
    assertEquals("平坪口", values.get("car_terminates"));
    assertEquals("湾油埠", values.get("car_change_destination"));
    assertEquals("4", values.get("car_change_cars"));
    assertEquals("4 cars", values.get("car_change_cars_lang2"));
    assertEquals("前", values.get("car_change_end"), "另一段是车头一段");
    assertEquals("front", values.get("car_change_end_lang2"));

    Map<String, String> continuing = forCar(context, 1);
    assertEquals("-", continuing.get("car_change_destination"), "这节车厢接着载客：不必换车");
    assertEquals("-", continuing.get("car_change_end"));
  }

  @Test
  void theOtherPartBehindIsTheRearOne() {
    // 前 2 节在终点留下不载客，后 1 节解下改跑支线
    DivisionForecast forecast =
        new DivisionForecast(
            trunkId,
            3,
            "SURC:WYB",
            "湾油埠",
            List.of(
                new DivisionForecast.Part(2, false, Optional.empty(), ""),
                new DivisionForecast.Part(
                    1, true, Optional.of("SURC:NDU"), "南渡", 2, Optional.of(branchId), 0)));
    when(decoupling.forecast(TRAIN)).thenReturn(Optional.of(forecast));
    TrainHudContext context = resolve(trunk, 0);

    Map<String, String> values = forCar(context, 1);
    assertEquals("南渡", values.get("car_change_destination"));
    assertEquals("1", values.get("car_change_cars"));
    assertEquals("1 car", values.get("car_change_cars_lang2"));
    assertEquals("后", values.get("car_change_end"));
    assertEquals("rear", values.get("car_change_end_lang2"));
    assertEquals(List.of("PPK", "HHU", "WYB"), codes(resolver.upcomingStops(context, 1)));
    assertTrue(resolver.upcomingStops(context, 1).get(2).terminal(), "终点分行：这节车厢到湾油埠止");
  }

  /** 本节车厢到此止的状态：途中、终点分行都换，模板没写时照旧；接着载客的车厢、不在分行站时不换。 */
  @Test
  void carEndStatesReplaceArrivalAndStopOnlyWhenTheTemplateDefinesThem() {
    Map<String, String> ending =
        Map.of(
            "division_station_code", "PPK",
            "car_destination", "-",
            "car_terminates", "平坪口",
            "next_station_code", "PPK",
            "current_station_code", "PPK");
    Map<String, String> continuing = new HashMap<>(ending);
    continuing.put("car_destination", "南渡");
    continuing.put("car_terminates", "-");
    Map<String, String> elsewhere = new HashMap<>(ending);
    elsewhere.put("next_station_code", "HHU");
    elsewhere.put("current_station_code", "KPO");

    assertEquals(
        HudState.CAR_ENDS_ARRIVING,
        HudStateTracker.applyCarEnd(HudState.ARRIVING, ending, true, true));
    assertEquals(
        HudState.AT_CAR_END, HudStateTracker.applyCarEnd(HudState.AT_STATION, ending, true, true));
    assertEquals(
        HudState.ARRIVING,
        HudStateTracker.applyCarEnd(HudState.ARRIVING, ending, false, false),
        "旧模板没有这一状态");
    assertEquals(
        HudState.AT_STATION,
        HudStateTracker.applyCarEnd(HudState.AT_STATION, continuing, true, true));
    assertEquals(
        HudState.ARRIVING, HudStateTracker.applyCarEnd(HudState.ARRIVING, elsewhere, true, true));
    assertEquals(
        HudState.AT_STATION,
        HudStateTracker.applyCarEnd(HudState.AT_STATION, elsewhere, true, true));
    assertEquals(
        HudState.IN_TRIP, HudStateTracker.applyCarEnd(HudState.IN_TRIP, ending, true, true));
  }

  /** 终点分行、这节车厢到此止：照管理器的顺序先分行后到此止，新状态优先于终点站；模板没写时仍报终点站。 */
  @Test
  void atATerminusDivisionTheEndingCarPrefersTheNewStates() {
    Map<String, String> ending =
        Map.of(
            "division_station_code", "WYB",
            "car_destination", "-",
            "car_terminates", "湾油埠",
            "next_station_code", "WYB",
            "current_station_code", "WYB");
    BossBarHudTemplate legacy =
        BossBarHudTemplate.parse(
            "TERM_ARRIVING: 终点\nAT_LAST_STATION: 终点站\nDIVIDE_ARRIVING: 分行\nAT_DIVIDE_STATION: 本站分行",
            message -> {});
    BossBarHudTemplate current =
        BossBarHudTemplate.parse(
            "TERM_ARRIVING: 终点\nAT_LAST_STATION: 终点站\nCAR_ENDS_ARRIVING: 本节车厢终点\nAT_CAR_END: 本节车厢到此止",
            message -> {});

    assertEquals(HudState.CAR_ENDS_ARRIVING, chain(HudState.TERM_ARRIVING, ending, current));
    assertEquals(HudState.AT_CAR_END, chain(HudState.AT_LAST_STATION, ending, current));
    assertEquals(HudState.TERM_ARRIVING, chain(HudState.TERM_ARRIVING, ending, legacy));
    assertEquals(HudState.AT_LAST_STATION, chain(HudState.AT_LAST_STATION, ending, legacy));
  }

  private static HudState chain(
      HudState resolved, Map<String, String> placeholders, BossBarHudTemplate template) {
    HudState state =
        HudStateTracker.applyDivision(
            resolved,
            placeholders,
            template.defines(HudState.DIVIDE_ARRIVING),
            template.defines(HudState.AT_DIVIDE_STATION));
    return HudStateTracker.applyCarEnd(
        state,
        placeholders,
        template.defines(HudState.CAR_ENDS_ARRIVING),
        template.defines(HudState.AT_CAR_END));
  }

  /** 默认模板写了到此止的两个状态，ActionBar 报换乘另一段的提示。 */
  @Test
  void theDefaultTemplateTellsTheEndingCarWhereToChange() throws Exception {
    YamlConfiguration bundled;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("default_hud_template.yml")) {
      bundled =
          YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    BossBarHudTemplate bossbar =
        BossBarHudTemplate.parse(bundled.getString("bossbar.template"), message -> {});
    BossBarHudTemplate actionbar =
        BossBarHudTemplate.parse(bundled.getString("actionbar.template"), message -> {});
    for (BossBarHudTemplate template : List.of(bossbar, actionbar)) {
      assertTrue(template.defines(HudState.CAR_ENDS_ARRIVING));
      assertTrue(template.defines(HudState.AT_CAR_END));
    }

    when(decoupling.forecast(TRAIN)).thenReturn(Optional.of(rearEnds(4, 2)));
    Map<String, String> values = forCar(resolve(trunk, 0), 6);
    String line = actionbar.resolveLine(HudState.AT_CAR_END, 0L, values).orElseThrow();
    String text = HudText.apply(line, values).replaceAll("<[^<>]*>", "");
    assertTrue(text.contains("前往 湾油埠 的乘客请下车，经站台换乘前 4 节"), text);
  }

  @Test
  void nextTripClockAndMinutes() {
    Instant now = Instant.parse("2026-10-20T02:00:00Z");
    ZoneId zone = ZoneId.of("Asia/Shanghai");

    assertEquals(
        "10:05",
        TrainHudContextResolver.nextTripClock(
            Optional.of(Instant.parse("2026-10-20T02:05:00Z")), zone));
    assertEquals("-", TrainHudContextResolver.nextTripClock(Optional.empty(), zone));

    Optional<Instant> departure = Optional.of(now.plusSeconds(330));
    assertEquals(
        "6",
        TrainHudContextResolver.nextTripMinutes(departure, Optional.of(now.minusSeconds(60)), now),
        "5 分 30 秒向上取整");
    assertEquals(
        "5",
        TrainHudContextResolver.nextTripMinutes(
            Optional.of(now.plusSeconds(300)), Optional.empty(), now));
    assertEquals(
        "10",
        TrainHudContextResolver.nextTripMinutes(departure, Optional.of(now.plusSeconds(550)), now),
        "整备好得比计划发车晚：按整备好的时刻");
    assertEquals(
        "0",
        TrainHudContextResolver.nextTripMinutes(
            Optional.of(now.minusSeconds(120)), Optional.empty(), now),
        "过了发车时刻：不为负");
    assertEquals(
        "-",
        TrainHudContextResolver.nextTripMinutes(
            Optional.empty(), Optional.of(now.plusSeconds(60)), now),
        "不按表");
  }

  @Test
  void aTrainOnLayoverShowsItsNextDeparture() {
    Instant now = Instant.now();
    Instant departure = now.plusSeconds(440);
    when(layovers.get(TRAIN))
        .thenReturn(
            Optional.of(
                new LayoverRegistry.LayoverCandidate(
                    TRAIN, "SURC:WYB", NodeId.of("SURC:S:WYB:1"), now.minusSeconds(30), Map.of())));
    TimetableService timetable = mock(TimetableService.class);
    TimetableTrip trip =
        new TimetableTrip(
            UUID.randomUUID(), UUID.randomUUID(), branch.id(), 0, "N01", 3600, Optional.empty());
    when(timetable.nextDepartureOf(TRAIN))
        .thenReturn(
            Optional.of(new TimetableService.DueTrip(null, trip, LocalDate.now(), departure)));
    when(plugin.getTimetableService()).thenReturn(Optional.of(timetable));

    Map<String, String> values = resolver.buildPlaceholders(resolve(trunk, 3), 0.0f);
    assertEquals("南渡", values.get("next_trip_destination"));
    assertEquals(
        TrainHudContextResolver.nextTripClock(Optional.of(departure), ZoneId.systemDefault()),
        values.get("next_trip_departure"));
    assertEquals("8", values.get("next_trip_minutes"), "7 分 20 秒向上取整");

    when(plugin.getTimetableService()).thenReturn(Optional.empty());
    Map<String, String> unknown = resolver.buildPlaceholders(resolve(trunk, 3), 0.0f);
    assertEquals("-", unknown.get("next_trip_departure"), "不按表");
    assertEquals("-", unknown.get("next_trip_minutes"));

    when(layovers.get(TRAIN)).thenReturn(Optional.empty());
    when(plugin.getTimetableService()).thenReturn(Optional.of(timetable));
    assertEquals(
        "-",
        resolver.buildPlaceholders(resolve(trunk, 3), 0.0f).get("next_trip_departure"),
        "不在待命");
  }
}
