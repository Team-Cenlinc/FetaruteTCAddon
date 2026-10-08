package org.fetarute.fetaruteTCAddon.display.pids.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Arrival;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.ArrivalMode;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Tone;
import org.junit.jupiter.api.Test;

/** 快照到显示内容：按站台过滤、解析名称与颜色、每行的状态词与色调。 */
class PidsViewBuilderTest {

  private static final Instant NOW = Instant.parse("2026-09-30T13:40:00Z");
  private static final PidsStationKey STATION = new PidsStationKey("SURC", "NTA");
  private static final UUID WORLD =
      UUID.nameUUIDFromBytes("world".getBytes(StandardCharsets.UTF_8));
  private static final int WS = 0x70DEEE;
  private static final int MT = 0xD920D9;

  private final PidsViewBuilder builder =
      new PidsViewBuilder(new MapDirectory(), new PidsVocabulary(PidsViewBuilderTest::text));

  @Test
  void runningRowsShowMinutesAndDelayTiers() {
    List<PidsView.Row> rows =
        build(
            row(PidsRow.Status.EN_ROUTE, 90, OptionalLong.empty()),
            row(PidsRow.Status.EN_ROUTE, 120, OptionalLong.of(59)),
            row(PidsRow.Status.EN_ROUTE, 300, OptionalLong.of(180)),
            row(PidsRow.Status.PENDING, 600, OptionalLong.of(360)));

    assertEquals(2, rows.get(0).arrival().minutes(), "90 秒向上取整为 2 分");
    assertTrue(rows.get(0).arrival().status().isEmpty(), "不按表运行时不报准点");
    assertEquals("准点", rows.get(1).arrival().status().orElseThrow().text().primary());
    assertEquals(
        new Names("晚点 3 分", "Late 3 min"), rows.get(2).arrival().status().orElseThrow().text());
    assertEquals(Tone.AMBER, rows.get(2).arrival().status().orElseThrow().tone());
    assertEquals("严重晚点 6 分", rows.get(3).arrival().status().orElseThrow().text().primary());
    assertEquals(
        Optional.of(new Names("晚点 6 分", "Late 6 min")),
        rows.get(3).arrival().status().orElseThrow().compact(),
        "放不下时缩成“晚点 N 分”，红色仍表示严重");
    assertEquals(Tone.RED, rows.get(3).arrival().status().orElseThrow().tone());
  }

  @Test
  void namesColorsAndTypesComeFromTheDirectory() {
    PidsView view = view(row(PidsRow.Status.EN_ROUTE, 120, OptionalLong.empty()));
    PidsView.Row row = view.rows().get(0);

    assertEquals("WS", row.badge().code());
    assertEquals(WS, row.badge().color());
    assertEquals(Optional.of("各停"), row.badge().type());
    assertEquals(
        new Names("新笛矢·壑湖", "Neo Fueya - Hor Huu"), row.destination().names(), "间隔号两侧不留空格");
    assertEquals(new Names("南渡", "Nam Toa"), view.station().orElseThrow());
    assertEquals(List.of(WS), view.bandColors(), "目录查不到站台线路时按到发行的线路");
    assertEquals("21:40", view.clock(), "服务器时区 HH:mm");
  }

  @Test
  void unknownLinesAndStationsFallBackToCodes() {
    PidsRow unknown =
        new PidsRow(
            PidsRow.Status.EN_ROUTE,
            "XX",
            "SURC:XX:R1",
            "ABC",
            Optional.of("SURC:ABC"),
            "2",
            NOW.plusSeconds(60),
            OptionalLong.empty(),
            1,
            false,
            false,
            false,
            Optional.empty());

    PidsView.Row row = build(unknown).get(0);

    assertEquals("XX", row.badge().code());
    assertEquals(PidsTheme.DARK.outline(), row.badge().color());
    assertTrue(row.badge().type().isEmpty());
    assertEquals(new Names("ABC", ""), row.destination().names());
  }

  @Test
  void highlightedPhases() {
    List<PidsView.Row> rows =
        build(
            row(PidsRow.Status.BOARDING, 0, OptionalLong.empty()),
            row(PidsRow.Status.ARRIVING, 30, OptionalLong.empty()),
            passing(PidsRow.Status.ARRIVING),
            passing(PidsRow.Status.EN_ROUTE));

    assertEquals(ArrivalMode.HIGHLIGHT, rows.get(0).arrival().mode());
    assertEquals("停靠中", rows.get(0).arrival().status().orElseThrow().text().primary());
    assertEquals("进站", rows.get(1).arrival().status().orElseThrow().text().primary());
    assertEquals("通过", rows.get(2).arrival().status().orElseThrow().text().primary());
    assertEquals(ArrivalMode.COUNTDOWN, rows.get(3).arrival().mode());
    assertEquals("通过", rows.get(3).arrival().status().orElseThrow().text().primary(), "通过优先于晚点");
    assertEquals(Tone.MUTED, rows.get(3).destination().tone());
  }

  @Test
  void cancelledPlannedTerminatingAndOutOfService() {
    PidsRow cancelled =
        withStatus(
            row(PidsRow.Status.EN_ROUTE, 120, OptionalLong.empty()), PidsRow.Status.CANCELLED);
    PidsRow planned = row(PidsRow.Status.PLANNED, 900, OptionalLong.empty());
    PidsRow terminating =
        new PidsRow(
            PidsRow.Status.EN_ROUTE,
            "WS",
            "SURC:WS:R1",
            "NTA",
            Optional.of("SURC:NTA"),
            "1",
            NOW.plusSeconds(120),
            OptionalLong.empty(),
            3,
            false,
            true,
            false,
            Optional.of("t1"));
    PidsRow outOfService =
        new PidsRow(
            PidsRow.Status.EN_ROUTE,
            "WS",
            "SURC:WS:R1",
            "回库",
            Optional.of("OUT_OF_SERVICE"),
            "1",
            NOW.plusSeconds(120),
            OptionalLong.empty(),
            4,
            false,
            false,
            true,
            Optional.of("t2"));

    List<PidsView.Row> rows = build(cancelled, planned, terminating, outOfService);

    PidsView.Row c = rows.get(0);
    assertTrue(c.badge().hollow());
    assertTrue(c.destination().struck());
    assertTrue(c.platform().hollow());
    assertEquals(ArrivalMode.DASH, c.arrival().mode());
    assertEquals(Tone.RED, c.arrival().status().orElseThrow().tone());
    PidsView.Row p = rows.get(1);
    assertEquals(Tone.MUTED, p.arrival().minutesTone());
    assertTrue(p.arrival().status().orElseThrow().boxed());
    assertEquals(new Names("本站终到", "Terminates here"), rows.get(2).destination().names());
    PidsView.Row o = rows.get(3);
    assertEquals("—", o.badge().code());
    assertTrue(o.badge().hollow());
    assertEquals(new Names("回库", "Not in Service"), o.destination().names());
    assertEquals(ArrivalMode.DASH, o.arrival().mode());
    assertTrue(o.arrival().status().isEmpty());
  }

  @Test
  void platformFilterAndCapacity() {
    PidsRow onTwo =
        new PidsRow(
            PidsRow.Status.EN_ROUTE,
            "WS",
            "SURC:WS:R1",
            "HHU",
            Optional.of("SURC:HHU"),
            "2",
            NOW.plusSeconds(60),
            OptionalLong.empty(),
            1,
            false,
            false,
            false,
            Optional.empty());
    PidsSnapshot snapshot =
        new PidsSnapshot(
            STATION,
            NOW,
            List.of(
                onTwo,
                row(PidsRow.Status.EN_ROUTE, 120, OptionalLong.empty()),
                row(PidsRow.Status.EN_ROUTE, 240, OptionalLong.empty()),
                row(PidsRow.Status.EN_ROUTE, 360, OptionalLong.empty())));

    PidsView view =
        builder.build(
            new PidsViewBuilder.Request(
                snapshot,
                NOW,
                ZoneId.of("Asia/Shanghai"),
                PidsTheme.DARK,
                Set.of("1"),
                List.of("1"),
                2,
                true));

    assertEquals(2, view.rows().size(), "按布局行数截断");
    assertTrue(view.rows().stream().allMatch(r -> r.platform().number().equals("1")));
    assertEquals(List.of("1"), view.platforms());
  }

  @Test
  void bandSkipsOutOfServiceRowsAndViewsCompareByValue() {
    PidsRow outOfService =
        new PidsRow(
            PidsRow.Status.EN_ROUTE,
            "WS",
            "SURC:WS:R1",
            "回库",
            Optional.of("OUT_OF_SERVICE"),
            "1",
            NOW.plusSeconds(60),
            OptionalLong.empty(),
            4,
            false,
            false,
            true,
            Optional.empty());

    PidsView first = view(outOfService);
    PidsView again = view(outOfService);

    assertTrue(first.bandColors().isEmpty(), "回库车不进色带");
    assertEquals(first, again, "相同输入得到相同视图，可直接作为是否重绘的判断");
    assertFalse(first.equals(view(row(PidsRow.Status.EN_ROUTE, 120, OptionalLong.empty()))));
  }

  @Test
  // 共用站台：色带按目录给出的停靠线路分段，即使眼下的到发行只有其中一条线。
  void sharedPlatformBandHasOneSegmentPerLine() {
    PidsRow onThree =
        new PidsRow(
            PidsRow.Status.EN_ROUTE,
            "WS",
            "SURC:WS:R1",
            "NFY",
            Optional.of("SURC:NFY"),
            "3",
            NOW.plusSeconds(60),
            OptionalLong.empty(),
            1,
            false,
            false,
            false,
            Optional.empty());

    PidsView view =
        builder.build(
            new PidsViewBuilder.Request(
                new PidsSnapshot(STATION, NOW, List.of(onThree)),
                NOW,
                ZoneId.of("Asia/Shanghai"),
                PidsTheme.DARK,
                Set.of("3"),
                List.of("3"),
                3,
                true));

    assertEquals(List.of(MT, WS), view.bandColors());
  }

  @Test
  void pendingPlatformIsAHollowDashOnScreensWithAPlatformColumn() {
    PidsView.Row row = build(pending(PidsRow.Status.EN_ROUTE, List.of("1", "2"))).get(0);

    assertEquals(new PidsView.PlatformCell("-", true), row.platform());
    assertEquals("准点", row.arrival().status().orElseThrow().text().primary(), "有站台列时状态照常，不另写待定");
  }

  @Test
  void singlePlatformScreensListPendingTrainsThatMayCallHere() {
    PidsRow arriving = pending(PidsRow.Status.ARRIVING, List.of("1", "2"));

    PidsView onTwo = platformScreen("2", arriving);
    PidsView onThree = platformScreen("3", arriving);

    assertEquals(1, onTwo.rows().size(), "候选里有本站台：列出");
    PidsView.Arrival arrival = onTwo.rows().get(0).arrival();
    assertEquals(ArrivalMode.COUNTDOWN, arrival.mode(), "可能停别的站台，不写“进站”");
    assertEquals("站台待定", arrival.status().orElseThrow().text().primary());
    assertEquals(Tone.AMBER, arrival.status().orElseThrow().tone());
    assertTrue(onThree.rows().isEmpty(), "候选里没有本站台：不列");
  }

  /** 空位页：首行是可以上车的运行中列车时，各节车厢按在座比例分宽松、较挤、拥挤（一半、八成为界），并给出空位数；色牌与终点同主页首行。 */
  @Test
  void theFirstTrainHasAVacancyPage() {
    PidsRow first =
        loaded(
            PidsRow.Status.ARRIVING,
            false,
            List.of(new PidsRow.Car(4, 0), new PidsRow.Car(4, 2), new PidsRow.Car(5, 4)));

    PidsVacancyView vacancy =
        builder
            .vacancy(request(first, row(PidsRow.Status.EN_ROUTE, 300, OptionalLong.empty())))
            .orElseThrow();

    assertEquals("WS", vacancy.badge().code());
    assertEquals(
        List.of(
            new PidsVacancyView.Car(PidsVacancyView.Level.MANY, 4),
            new PidsVacancyView.Car(PidsVacancyView.Level.SOME, 2),
            new PidsVacancyView.Car(PidsVacancyView.Level.FEW, 1)),
        vacancy.cars());
    assertEquals("请优先考虑较空的车厢", vacancy.labels().advice().primary());
    assertTrue(vacancy.front().isEmpty(), "不知道屏幕朝向：车头画在左侧");
    assertEquals(ArrivalMode.HIGHLIGHT, vacancy.arrival().mode(), "到站与主页首行相同：进站");
  }

  /** 车头朝屏幕哪一侧：站台节点（NTA:1，原点）到下一个途经节点（BBB:1，东边）向东，屏幕朝南时“向右”是东——车头在右；屏幕朝北时在左； 屏幕朝东（与轨道垂直）说不清。 */
  @Test
  void theFrontFollowsTheScreen() {
    PidsRow first =
        loaded(
            PidsRow.Status.ARRIVING, false, List.of(new PidsRow.Car(4, 0), new PidsRow.Car(4, 0)));

    assertEquals(
        Optional.of(PidsVacancyView.Front.RIGHT),
        builder
            .vacancy(placed(new PidsViewBuilder.Placement(WORLD, 1, 0), first))
            .flatMap(PidsVacancyView::front));
    assertEquals(
        Optional.of(PidsVacancyView.Front.LEFT),
        builder
            .vacancy(placed(new PidsViewBuilder.Placement(WORLD, -1, 0), first))
            .flatMap(PidsVacancyView::front));
    assertEquals(
        Optional.empty(),
        builder
            .vacancy(placed(new PidsViewBuilder.Placement(WORLD, 0, -1), first))
            .flatMap(PidsVacancyView::front),
        "屏幕与轨道垂直：说不清车头朝哪一侧");
  }

  /** 首行本站终到、通过、还没开出或读不到载客：没有空位页，轮播照常放宣传页。 */
  @Test
  void vacancyIsOnlyShownForATrainYouCanBoard() {
    List<PidsRow.Car> cars = List.of(new PidsRow.Car(4, 1));

    assertTrue(
        builder.vacancy(request(loaded(PidsRow.Status.EN_ROUTE, true, cars))).isEmpty(), "本站终到");
    assertTrue(
        builder.vacancy(request(loaded(PidsRow.Status.PENDING, false, cars))).isEmpty(), "还没开出");
    assertTrue(
        builder.vacancy(request(loaded(PidsRow.Status.EN_ROUTE, false, List.of()))).isEmpty(),
        "没有载客");
    assertTrue(
        builder
            .vacancy(
                request(loaded(PidsRow.Status.EN_ROUTE, false, List.of(new PidsRow.Car(0, 0)))))
            .isEmpty(),
        "全车没有座位");
    assertTrue(builder.vacancy(request(passing(PidsRow.Status.ARRIVING))).isEmpty(), "通过");
  }

  /** 站台变更：统屏站台方块变色、状态写“站台变更”；进站照旧反白。 */
  @Test
  void aChangedPlatformIsMarked() {
    PidsView.Row enRoute = build(changed(PidsRow.Status.EN_ROUTE, "1", "2")).get(0);
    PidsView.Row arriving = build(changed(PidsRow.Status.ARRIVING, "1", "2")).get(0);

    assertEquals(new PidsView.PlatformCell("1", false, true), enRoute.platform());
    assertEquals("站台变更", enRoute.arrival().status().orElseThrow().text().primary());
    assertEquals(Tone.AMBER, enRoute.arrival().status().orElseThrow().tone());
    assertEquals(ArrivalMode.HIGHLIGHT, arriving.arrival().mode());
    assertEquals("进站", arriving.arrival().status().orElseThrow().text().primary());
  }

  /** 原定停本站台、改去别处的车仍在本站台的屏上列出，写“改至 N 站台”，不写“进站”；新站台的屏照常写进站。 */
  @Test
  void theOldPlatformTellsWhereTheTrainWent() {
    PidsRow moved = changed(PidsRow.Status.ARRIVING, "1", "2");

    PidsView.Arrival onOld = platformScreen("2", moved).rows().get(0).arrival();
    PidsView.Arrival onNew = platformScreen("1", moved).rows().get(0).arrival();

    assertEquals(ArrivalMode.COUNTDOWN, onOld.mode());
    assertEquals("改至 1 站台", onOld.status().orElseThrow().text().primary());
    assertEquals(Tone.AMBER, onOld.status().orElseThrow().tone());
    assertEquals(ArrivalMode.HIGHLIGHT, onNew.mode());
    assertTrue(platformScreen("3", moved).rows().isEmpty(), "与这两个站台都无关的屏不列");
  }

  /** 首行原定停本站台、已改去别处：空位页画下一班真会来本站台的车，不画它。 */
  @Test
  void vacancySkipsATrainThatMovedAway() {
    PidsRow moved = changed(PidsRow.Status.ARRIVING, "2", "1");
    PidsRow next =
        loaded(
            PidsRow.Status.EN_ROUTE, false, List.of(new PidsRow.Car(4, 1), new PidsRow.Car(4, 3)));

    PidsViewBuilder.Request onOne = platformRequest("1", moved, next);

    assertTrue(builder.hasVacancy(onOne));
    assertEquals(
        List.of(
            new PidsVacancyView.Car(PidsVacancyView.Level.MANY, 3),
            new PidsVacancyView.Car(PidsVacancyView.Level.SOME, 1)),
        builder.vacancy(onOne).orElseThrow().cars());
    assertFalse(builder.hasVacancy(platformRequest("1", moved)), "只有改去别处的那一班：没有空位页");
  }

  /** 开放叫车时空行写的话：没车写“暂无后续列车”与提示；有车可乘只写提示；只剩终到的车写合并的一句。不开放时照旧。 */
  @Test
  void callableScreensWriteTheHintInEmptyRows() {
    PidsViewBuilder.Calls callable = new PidsViewBuilder.Calls(true, Set.of());
    PidsRow terminating = loaded(PidsRow.Status.BOARDING, true, List.of());

    assertEquals(
        List.of(names("no-more-trains"), names("call-hint")),
        builder.build(withCalls(callable)).emptyMessages());
    assertEquals(
        List.of(names("call-hint")),
        builder
            .build(withCalls(callable, row(PidsRow.Status.EN_ROUTE, 720, OptionalLong.of(0))))
            .emptyMessages(),
        "有车可乘：不改动已有班次，只在空行写提示");
    assertEquals(
        List.of(names("no-more-trains-call")),
        builder.build(withCalls(callable, terminating)).emptyMessages());
    assertEquals(
        List.of(names("no-more-trains")),
        builder.build(withCalls(PidsViewBuilder.Calls.NONE)).emptyMessages(),
        "不开放叫车：照旧只写暂无后续列车");
  }

  /** 叫来的车：状态格写“叫车”，与“准点”同一排法。 */
  @Test
  void calledTrainsShowOnCall() {
    PidsView view =
        builder.build(
            withCalls(
                new PidsViewBuilder.Calls(false, Set.of("train")),
                row(PidsRow.Status.EN_ROUTE, 180, OptionalLong.of(0))));

    Arrival arrival = view.rows().get(0).arrival();
    assertEquals(ArrivalMode.COUNTDOWN, arrival.mode());
    assertEquals(3, arrival.minutes());
    assertEquals(names("status.on-call"), arrival.status().orElseThrow().text());
  }

  private static PidsView.Names names(String key) {
    return new PidsView.Names(text("pids.board." + key), text("pids.board." + key + "-secondary"));
  }

  private static PidsViewBuilder.Request withCalls(PidsViewBuilder.Calls calls, PidsRow... rows) {
    return new PidsViewBuilder.Request(
        new PidsSnapshot(STATION, NOW, List.of(rows)),
        NOW,
        ZoneId.of("Asia/Shanghai"),
        PidsTheme.DARK,
        Set.of(),
        List.of("1"),
        3,
        false,
        Optional.empty(),
        false,
        calls);
  }

  @Test
  void minutesRoundUpAndNeverGoNegative() {
    assertEquals(0, PidsViewBuilder.minutesUntil(NOW.minusSeconds(5), NOW));
    assertEquals(1, PidsViewBuilder.minutesUntil(NOW.plusSeconds(10), NOW));
    assertEquals(2, PidsViewBuilder.minutesUntil(NOW.plusMillis(60_500), NOW));
  }

  /** 单站台屏（没有站台列）。 */
  private PidsView platformScreen(String platform, PidsRow... rows) {
    return builder.build(platformRequest(platform, rows));
  }

  private static PidsViewBuilder.Request platformRequest(String platform, PidsRow... rows) {
    return new PidsViewBuilder.Request(
        new PidsSnapshot(STATION, NOW, List.of(rows)),
        NOW,
        ZoneId.of("Asia/Shanghai"),
        PidsTheme.DARK,
        Set.of(platform),
        List.of(platform),
        3,
        false);
  }

  private PidsView view(PidsRow... rows) {
    return builder.build(request(rows));
  }

  private static PidsViewBuilder.Request placed(
      PidsViewBuilder.Placement placement, PidsRow... rows) {
    return new PidsViewBuilder.Request(
        new PidsSnapshot(STATION, NOW, List.of(rows)),
        NOW,
        ZoneId.of("Asia/Shanghai"),
        PidsTheme.DARK,
        Set.of(),
        List.of("1"),
        6,
        true,
        Optional.of(placement));
  }

  private static PidsViewBuilder.Request request(PidsRow... rows) {
    return new PidsViewBuilder.Request(
        new PidsSnapshot(STATION, NOW, List.of(rows)),
        NOW,
        ZoneId.of("Asia/Shanghai"),
        PidsTheme.DARK,
        Set.of(),
        List.of("1"),
        6,
        true);
  }

  /** 运行中、站台从 {@code from} 改到 {@code to} 的行。 */
  private static PidsRow changed(PidsRow.Status status, String to, String from) {
    return new PidsRow(
        status,
        "WS",
        "SURC:WS:R1",
        "NFY",
        Optional.of("SURC:NFY"),
        to,
        NOW.plusSeconds(120),
        OptionalLong.of(0),
        2,
        false,
        false,
        false,
        Optional.of("train"),
        false,
        List.of(),
        List.of(),
        Optional.of(from));
  }

  private List<PidsView.Row> build(PidsRow... rows) {
    return view(rows).rows();
  }

  private static PidsRow row(PidsRow.Status status, long seconds, OptionalLong delay) {
    return new PidsRow(
        status,
        "WS",
        "SURC:WS:R1",
        "NFY",
        Optional.of("SURC:NFY"),
        "1",
        NOW.plusSeconds(seconds),
        delay,
        2,
        false,
        false,
        false,
        Optional.of("train"));
  }

  private static PidsRow loaded(
      PidsRow.Status status, boolean terminating, List<PidsRow.Car> cars) {
    return new PidsRow(
        status,
        "WS",
        "SURC:WS:R1",
        "NFY",
        Optional.of("SURC:NFY"),
        "1",
        NOW.plusSeconds(60),
        OptionalLong.empty(),
        2,
        false,
        terminating,
        false,
        Optional.of("train"),
        false,
        List.of(),
        cars);
  }

  private static PidsRow pending(PidsRow.Status status, List<String> candidates) {
    return new PidsRow(
        status,
        "WS",
        "SURC:WS:R1",
        "NFY",
        Optional.of("SURC:NFY"),
        "-",
        NOW.plusSeconds(120),
        OptionalLong.of(0),
        2,
        false,
        false,
        false,
        Optional.of("train"),
        true,
        candidates);
  }

  private static PidsRow passing(PidsRow.Status status) {
    return new PidsRow(
        status,
        "WS",
        "SURC:WS:R1",
        "NFY",
        Optional.of("SURC:NFY"),
        "1",
        NOW.plusSeconds(120),
        OptionalLong.of(400),
        2,
        true,
        false,
        false,
        Optional.of("rapid"));
  }

  private static PidsRow withStatus(PidsRow row, PidsRow.Status status) {
    return new PidsRow(
        status,
        row.lineName(),
        row.routeId(),
        row.destination(),
        row.destinationId(),
        row.platform(),
        row.expectedAt(),
        row.delaySeconds(),
        row.stopSequence(),
        row.passing(),
        row.terminating(),
        row.outOfService(),
        row.trainName());
  }

  /** 与 zh_CN.yml 中 pids.board.* 相同的文案。 */
  private static String text(String key) {
    return TEXTS.getOrDefault(key, key);
  }

  private static final Map<String, String> TEXTS =
      Map.ofEntries(
          Map.entry("pids.board.status.on-time", "准点"),
          Map.entry("pids.board.status.on-time-secondary", "On time"),
          Map.entry("pids.board.status.late", "晚点 <minutes> 分"),
          Map.entry("pids.board.status.late-secondary", "Late <minutes> min"),
          Map.entry("pids.board.status.severely-late", "严重晚点 <minutes> 分"),
          Map.entry("pids.board.status.severely-late-secondary", "Late <minutes> min"),
          Map.entry("pids.board.status.cancelled", "取消"),
          Map.entry("pids.board.status.arriving", "进站"),
          Map.entry("pids.board.status.passing", "通过"),
          Map.entry("pids.board.status.boarding", "停靠中"),
          Map.entry("pids.board.status.planned", "计划"),
          Map.entry("pids.board.status.platform-pending", "站台待定"),
          Map.entry("pids.board.destination.terminating", "本站终到"),
          Map.entry("pids.board.destination.terminating-secondary", "Terminates here"),
          Map.entry("pids.board.destination.out-of-service", "回库"),
          Map.entry("pids.board.destination.out-of-service-secondary", "Not in Service"),
          Map.entry("pids.board.type.local", "各停"),
          Map.entry("pids.board.vacancy.advice", "请优先考虑较空的车厢"),
          Map.entry("pids.board.status.platform-changed", "站台变更"),
          Map.entry("pids.board.status.moved", "改至 <platform> 站台"),
          Map.entry("pids.board.minutes", "分"),
          Map.entry("pids.board.no-more-trains", "暂无后续列车"),
          Map.entry("pids.board.no-more-trains-secondary", "No further trains"),
          Map.entry("pids.board.call-hint", "可右键本屏叫车"),
          Map.entry("pids.board.call-hint-secondary", "Right-click to call a train"),
          Map.entry("pids.board.no-more-trains-call", "暂无后续列车，可右键本屏叫车"),
          Map.entry(
              "pids.board.no-more-trains-call-secondary",
              "No further trains. Right-click to call a train."),
          Map.entry("pids.board.status.on-call", "叫车"),
          Map.entry("pids.board.status.on-call-secondary", "On call"));

  /** 只认识 WS 线、NFY 与 NTA 两站。 */
  private static final class MapDirectory implements PidsDirectory {

    @Override
    public Optional<Names> stationName(String stationId) {
      return switch (stationId) {
        case "SURC:NFY" -> Optional.of(new Names("新笛矢 · 壑湖", "Neo Fueya - Hor Huu"));
        case "SURC:NTA" -> Optional.of(new Names("南渡", "Nam Toa"));
        default -> Optional.empty();
      };
    }

    @Override
    public Optional<LineStyle> line(String operatorCode, String lineCode) {
      return "SURC".equals(operatorCode) && "WS".equals(lineCode)
          ? Optional.of(new LineStyle("WS", WS))
          : Optional.empty();
    }

    @Override
    public Optional<RouteApi.OperationType> serviceType(String routeId) {
      return routeId.startsWith("SURC:WS:")
          ? Optional.of(RouteApi.OperationType.LOCAL)
          : Optional.of(RouteApi.OperationType.NORMAL);
    }

    @Override
    public List<PidsView.LineChip> linesServing(PidsStationKey station) {
      return List.of(new PidsView.LineChip("WS", WS, new Names("西海岸线", "West Shore Line")));
    }

    /** WS 的交路 R1：AAA → 路径点 → NTA（停靠序号 2）→ BBB。 */
    @Override
    public List<String> waypoints(String routeId) {
      return "SURC:WS:R1".equals(routeId)
          ? List.of("SURC:S:AAA:1", "SURC:AAA:NTA:1:001", "SURC:S:NTA:1", "SURC:S:BBB:1")
          : List.of();
    }

    /** NTA:1 在原点，BBB:1 在正东 100 格。 */
    @Override
    public Optional<GraphApi.Position> nodePosition(UUID worldId, String nodeId) {
      if (!WORLD.equals(worldId)) {
        return Optional.empty();
      }
      return switch (nodeId) {
        case "SURC:S:NTA:1" -> Optional.of(new GraphApi.Position(0, 64, 0));
        case "SURC:S:BBB:1" -> Optional.of(new GraphApi.Position(100, 64, 0));
        default -> Optional.empty();
      };
    }

    /** 3 站台由 MT 与 WS 共用；其余站台查不到。 */
    @Override
    public List<PidsView.LineChip> linesServingPlatform(PidsStationKey station, String platform) {
      return "3".equals(platform)
          ? List.of(
              new PidsView.LineChip("MT", MT, new Names("大都会线", "Metropolitan Line")),
              new PidsView.LineChip("WS", WS, new Names("浦蓝线", "Waterside Line")))
          : List.of();
    }
  }
}
