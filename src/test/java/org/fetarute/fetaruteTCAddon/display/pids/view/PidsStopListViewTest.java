package org.fetarute.fetaruteTCAddon.display.pids.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory.RouteStop;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsStopListView.Kind;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsStopListView.Note;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsStopListView.Stop;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Tone;
import org.junit.jupiter.api.Test;

/** 2×1 停站屏：取下一班可以上车的车，列出本站之后的停车站，直通换色、经由标记与备注同一口径。 */
class PidsStopListViewTest {

  private static final Instant NOW = Instant.parse("2026-10-01T13:40:00Z");
  private static final PidsStationKey PPK = new PidsStationKey("SURC", "PPK");
  private static final String THROUGH = "SURC:MT:THRU";
  private static final String PLAIN = "SURC:MT:PLAIN";
  private static final String LOCAL = "SURC:MT:LOCAL";
  private static final String UNTYPED = "SURC:MT:UNTYPED";
  private static final int MT = 0xD920D9;
  private static final int WS = 0x70DEEE;
  private static final int DS = 0xF6A000;

  /** PPK → SPB → XYZ 通过 → HHU（换 WS）→ KPO → NTA 终到 → 回库。 */
  private static final List<RouteStop> THROUGH_STOPS =
      List.of(
          stop("SURC:PPK", true, null),
          stop("SURC:SPB", true, null),
          stop("SURC:XYZ", false, null),
          stop("SURC:HHU", true, new RouteApi.LineRef("SURC", "WS")),
          stop("SURC:KPO", true, null),
          stop("SURC:NTA", true, null),
          new RouteStop(Optional.empty(), false, Optional.empty()));

  /** PPK → SPB → KPO → NTA 终到，不换线。 */
  private static final List<RouteStop> PLAIN_STOPS =
      List.of(
          stop("SURC:PPK", true, null),
          stop("SURC:SPB", true, null),
          stop("SURC:KPO", true, null),
          stop("SURC:NTA", true, null));

  private static final String TRUNK = "SURC:MT:TRUNK";
  private static final String BRANCH = "SURC:MT:BRANCH";
  private static final String FEEDER = "SURC:MT:FEEDER";

  /** 主干：PPK → SPB → HHU 终到（在这里分行）。 */
  private static final List<RouteStop> TRUNK_STOPS =
      List.of(
          stop("SURC:PPK", true, null), stop("SURC:SPB", true, null), stop("SURC:HHU", true, null));

  /** 支线：HHU → KPO → NTA；也当回程支线联挂以后接着跑的主干。 */
  private static final List<RouteStop> BRANCH_STOPS =
      List.of(
          stop("SURC:HHU", true, null), stop("SURC:KPO", true, null), stop("SURC:NTA", true, null));

  /** 回程支线：PPK → HHU（联挂站）。 */
  private static final List<RouteStop> FEEDER_STOPS =
      List.of(stop("SURC:PPK", true, null), stop("SURC:HHU", true, null));

  private final Net net = new Net();
  private final PidsViewBuilder builder =
      new PidsViewBuilder(
          net,
          new PidsVocabulary(
              key ->
                  switch (key) {
                    case "pids.board.stop-list.through-from" -> "<station>起";
                    case "pids.board.remark.through" -> "直通";
                    case "pids.board.remark.through-secondary" -> "thru";
                    case "pids.board.remark.via" -> "经由";
                    case "pids.board.remark.via-secondary" -> "via";
                    case "pids.board.stop-list.following" -> "后续列车";
                    case "pids.board.stop-list.following-secondary" -> "Following trains";
                    case "pids.board.remark.division" -> "分行";
                    case "pids.board.remark.division-secondary" -> "Divides";
                    case "pids.board.remark.division-here" -> "本站分行";
                    case "pids.board.remark.division-here-secondary" -> "Divides here";
                    case "pids.board.remark.join" -> "联挂";
                    case "pids.board.remark.join-secondary" -> "Joins";
                    case "pids.board.division.front" -> "前";
                    case "pids.board.division.front-secondary" -> "Front";
                    case "pids.board.division.rear" -> "后";
                    case "pids.board.division.rear-secondary" -> "Rear";
                    case "pids.board.division.ends-here" -> "<end> <cars> 节本站止";
                    case "pids.board.division.ends-here-secondary" -> "<end> <cars> cars end here";
                    case "pids.board.stop-list.ends-there" -> "<end> <cars> 节到此止";
                    case "pids.board.stop-list.ends-there-secondary" -> "<end> <cars> cars end";
                    case "pids.board.stop-list.ends-here" -> "<end> <cars> 节本站止";
                    case "pids.board.stop-list.ends-here-secondary" -> "<end> <cars> cars end";
                    case "pids.board.stop-list.joins-there" -> "联挂后无需换乘";
                    case "pids.board.stop-list.joins-there-secondary" -> "Stay on board";
                    case "pids.board.stop-list.only" -> "仅<end> <cars> 节";
                    case "pids.board.stop-list.only-secondary" -> "<end> <cars> only";
                    case "pids.board.stop-list.board-part" -> "前往<destination>请乘<end> <cars> 节";
                    case "pids.board.stop-list.board-part-secondary" -> "<end> <cars> cars to <destination>";
                    default -> key;
                  }));

  @Test
  void listsTheStopsAfterThisStationAndRecolorsAfterTheChange() {
    PidsStopListView view = view(row(THROUGH, PidsRow.Status.EN_ROUTE, "2"));
    List<Stop> stops = view.train().orElseThrow().stops();

    assertEquals(
        List.of("主城湾", "新笛矢·壑湖", "KPO 站", "南渡"),
        stops.stream().map(stop -> stop.names().primary()).toList(),
        "只列停车站，通过站与回库段不列");
    assertEquals(
        List.of(Kind.NEXT, Kind.CHANGE, Kind.STOP, Kind.TERMINAL),
        stops.stream().map(Stop::kind).toList(),
        "换线站即使也是推出来的经由站，仍标直通");
    assertEquals(List.of(MT, MT, WS, WS), stops.stream().map(Stop::color).toList());
    assertEquals(List.of(MT, WS, WS, WS), stops.stream().map(Stop::after).toList());
    assertEquals(
        List.of(List.of(), List.of(DS), List.of(DS), List.of()),
        stops.stream().map(Stop::transfers).toList(),
        "可换乘线路不含列车所属的线路（换线站两条都不算）");
  }

  /** 分行车：停站表到分行站为止接着列开往终点那一段跑的支线，分行站标“分行”，之后的站细线、写“仅前 4 节”；终点下面一行写分行站与另一段到此止，中英随备注轮换。 */
  @Test
  void aDividingTrainListsThePartThatGoesToItsDestination() {
    PidsRow row = coupled(TRUNK, 0, Optional.of(division()), Optional.empty());

    PidsStopListView view = view(row);
    List<Stop> stops = view.train().orElseThrow().stops();

    assertEquals(
        List.of("主城湾", "新笛矢·壑湖", "KPO 站", "南渡"),
        stops.stream().map(stop -> stop.names().primary()).toList());
    assertEquals(
        List.of(Kind.NEXT, Kind.DIVIDE, Kind.STOP, Kind.TERMINAL),
        stops.stream().map(Stop::kind).toList());
    assertEquals(List.of(false, false, true, true), stops.stream().map(Stop::partial).toList());
    assertEquals(Optional.of(new Names("仅前 4 节", "Front 4 only")), stops.get(2).only());
    assertTrue(stops.get(1).only().isEmpty(), "分行站整列都到");
    assertEquals(
        Optional.of(
            new PidsStopListView.Coupling(
                new Names("分行", "Divides"),
                new Names("新笛矢·壑湖", "Neo Fueya - Hor Huu"),
                new Names("后 4 节到此止", "Rear 4 cars end"),
                false)),
        view.coupling());
    assertTrue(view.note().isEmpty(), "分行说明优先于直通与经由");
    assertTrue(view.noted());
    assertTrue(view.train().orElseThrow().advice().isEmpty(), "前方分行不写表尾");
    assertTrue(builder.stopList(request(true, row)).coupling().orElseThrow().english(), "轮到备注时写英文");
  }

  /** 本站分行：第一行写“本站分行”，说明写另一段本站止；之后各站都只有部分车厢开到；最底下一行写前往终点请乘哪几节。 */
  @Test
  void dividingHereSaysWhichCarsToBoard() {
    PidsStopListView view = view(coupled(TRUNK, 2, Optional.of(division()), Optional.empty()));

    PidsStopListView.Train train = view.train().orElseThrow();
    assertEquals(
        List.of(true, true), train.stops().stream().map(Stop::partial).toList(), "本站之后都只有前 4 节开到");
    assertEquals(new Names("本站分行", "Divides here"), view.coupling().orElseThrow().place());
    assertEquals(new Names("后 4 节本站止", "Rear 4 cars end"), view.coupling().orElseThrow().detail());
    assertEquals(Optional.of(new Names("前往南渡请乘前 4 节", "Front 4 cars to Nam Toa")), train.advice());
  }

  /** 回程支线车：停站表到联挂站为止接着列主干，联挂站标“联挂”；终点下面一行写联挂站与无需换乘。 */
  @Test
  void aJoiningTrainListsTheStopsAfterTheJoin() {
    PidsRow row =
        coupled(
            FEEDER,
            0,
            Optional.empty(),
            Optional.of(new PidsRow.Joining(1, "SURC:HHU", 4, 4, false, Optional.of(BRANCH), 0)));

    PidsStopListView view = view(row);
    List<Stop> stops = view.train().orElseThrow().stops();

    assertEquals(
        List.of(Kind.JOIN, Kind.STOP, Kind.TERMINAL), stops.stream().map(Stop::kind).toList());
    assertTrue(stops.stream().noneMatch(Stop::partial), "联挂不分段");
    assertEquals(
        new PidsStopListView.Coupling(
            new Names("联挂", "Joins"),
            new Names("新笛矢·壑湖", "Neo Fueya - Hor Huu"),
            new Names("联挂后无需换乘", "Stay on board"),
            false),
        view.coupling().orElseThrow());
  }

  /** 在 HHU（主干下标 2）分行：前 4 节解下开往南渡（接着跑支线，从下标 0 起），后 4 节到此止。 */
  private static PidsRow.Division division() {
    return new PidsRow.Division(
        2,
        "SURC:HHU",
        List.of(
            new PidsRow.Division.Part(4, true, Optional.of("SURC:NTA"), Optional.of(BRANCH), 0),
            new PidsRow.Division.Part(4, false, Optional.empty())));
  }

  /** 开往南渡、停在本站台的运行中列车，带分行或联挂。 */
  private static PidsRow coupled(
      String route,
      int stopSequence,
      Optional<PidsRow.Division> division,
      Optional<PidsRow.Joining> joining) {
    return new PidsRow(
        PidsRow.Status.EN_ROUTE,
        "MT",
        route,
        "NTA",
        Optional.of("SURC:NTA"),
        "2",
        NOW.plusSeconds(120),
        OptionalLong.empty(),
        stopSequence,
        false,
        false,
        false,
        Optional.of("train"),
        false,
        List.of(),
        List.of(),
        Optional.empty(),
        false,
        division,
        joining);
  }

  @Test
  void theNoteShowsTheThroughLineBeforeAnyVia() {
    Note note =
        view(row(THROUGH, PidsRow.Status.EN_ROUTE, "2")).train().orElseThrow().note().orElseThrow();

    assertEquals(new Names("直通", "thru"), note.tag());
    assertEquals(WS, note.color());
    assertEquals("浦蓝线", note.primary());
    assertEquals(Optional.of("WS"), note.chip());
    assertEquals("新笛矢·壑湖起", note.secondary());
  }

  @Test
  void withoutAThroughTheNoteIsTheFirstViaStation() {
    net.via.put(PLAIN, List.of("KPO"));

    PidsStopListView.Train train =
        view(row(PLAIN, PidsRow.Status.PLANNED, "2")).train().orElseThrow();

    assertEquals(
        new Note(new Names("经由", "via"), PidsTheme.DARK.amber(), "KPO 站", Optional.empty(), "KPO"),
        train.note().orElseThrow());
    assertEquals(
        List.of(Kind.NEXT, Kind.VIA, Kind.TERMINAL),
        train.stops().stream().map(Stop::kind).toList());
  }

  @Test
  void theNextRideableTrainIsChosen() {
    PidsRow passing = copy(row(THROUGH, PidsRow.Status.ARRIVING, "2"), true, false, false);
    PidsRow terminating = copy(row(THROUGH, PidsRow.Status.EN_ROUTE, "2"), false, true, false);
    PidsRow outOfService = copy(row(THROUGH, PidsRow.Status.EN_ROUTE, "2"), false, false, true);
    PidsRow cancelled = row(THROUGH, PidsRow.Status.CANCELLED, "2");
    PidsRow elsewhere = row(PLAIN, PidsRow.Status.EN_ROUTE, "5");
    PidsRow next = row(PLAIN, PidsRow.Status.PLANNED, "2");

    PidsStopListView view = view(passing, terminating, outOfService, cancelled, elsewhere, next);

    assertEquals(3, view.train().orElseThrow().stops().size(), "取不换线的那班（PLAIN）");
    assertTrue(view(passing, cancelled).train().isEmpty(), "没有可以上车的车");
  }

  /** 站台待定、晚点这类要提醒的状态单独给出；准点不写。 */
  @Test
  void attentionStatusesAreCarried() {
    PidsRow pending =
        new PidsRow(
            PidsRow.Status.EN_ROUTE,
            "MT",
            PLAIN,
            "NTA",
            Optional.of("SURC:NTA"),
            "-",
            NOW.plusSeconds(120),
            OptionalLong.empty(),
            0,
            false,
            false,
            false,
            Optional.of("train"),
            true,
            List.of("2", "3"));
    PidsRow onTime =
        new PidsRow(
            PidsRow.Status.EN_ROUTE,
            "MT",
            PLAIN,
            "NTA",
            Optional.of("SURC:NTA"),
            "2",
            NOW.plusSeconds(120),
            OptionalLong.of(0),
            0,
            false,
            false,
            false,
            Optional.of("train"));

    assertEquals(
        Optional.of(Tone.AMBER),
        view(pending).train().orElseThrow().status().map(PidsView.Label::tone),
        "站台待定");
    assertEquals(Optional.empty(), view(onTime).train().orElseThrow().status(), "准点不写");
  }

  /** 下一班之前本站台被取消的班次写在终点下面一行，盖过直通与经由。 */
  @Test
  void aCancelledTrainBeforeTheNextOneTakesTheNoteLine() {
    PidsRow cancelled =
        new PidsRow(
            PidsRow.Status.CANCELLED,
            "MT",
            THROUGH,
            "NTA",
            Optional.of("SURC:NTA"),
            "2",
            NOW.plusSeconds(60),
            OptionalLong.empty(),
            0,
            false,
            false,
            false,
            Optional.empty());

    PidsStopListView view = view(cancelled, row(THROUGH, PidsRow.Status.EN_ROUTE, "2"));

    Note note = view.note().orElseThrow();
    assertEquals(PidsTheme.DARK.red(), note.color());
    assertEquals("南渡 21:41", note.primary(), "终点与计划时刻（服务器时区）");
    assertEquals("Nam Toa", note.secondary());
    assertTrue(view.train().orElseThrow().note().isPresent(), "直通仍在，只是这一行先写取消");
    assertTrue(view(cancelled).train().isEmpty());
    assertTrue(view(cancelled).note().isPresent(), "没有下一班时也写出取消");
  }

  /** 下一班已在进站或停靠：终点下面一行只写这一班自己的直通或经由，之前取消的班次不占这一行；停站表按这一班画。 */
  @Test
  void aTrainAtThePlatformKeepsTheNoteLine() {
    PidsRow cancelled = at(row(PLAIN, PidsRow.Status.CANCELLED, "2"), 30);
    for (PidsRow.Status status : List.of(PidsRow.Status.ARRIVING, PidsRow.Status.BOARDING)) {
      PidsRow here = at(row(THROUGH, status, "2"), 60);

      PidsStopListView view = view(cancelled, here);

      assertTrue(view.cancelled().isEmpty(), status + "：不写取消");
      assertEquals(Optional.of("浦蓝线"), view.note().map(Note::primary), status + "：写这一班的直通");
      assertEquals(4, view.train().orElseThrow().stops().size(), status + "：按在站的这一班画");
    }
  }

  /** 取消的班次过了计划发车就不再写：只在它本该开出之前提醒乘客。 */
  @Test
  void aCancelledTrainIsOnlyNotedBeforeItsPlannedDeparture() {
    PidsRow expired = at(row(PLAIN, PidsRow.Status.CANCELLED, "2"), -30);
    PidsRow due = at(row(PLAIN, PidsRow.Status.CANCELLED, "2"), 0);
    PidsRow next = row(THROUGH, PidsRow.Status.EN_ROUTE, "2");

    assertTrue(view(expired, due, next).cancelled().isEmpty());
    assertTrue(view(expired).note().isEmpty(), "没有下一班时也不写过了时刻的取消");
    assertTrue(
        following(4, row(PLAIN, PidsRow.Status.EN_ROUTE, "2"), expired, next).trains().stream()
            .noneMatch(train -> train.badge().hollow()),
        "后续列车页也不列");
  }

  /** 后续列车页放不下时真会来的车优先，取消的班次只占剩下的位置。 */
  @Test
  void followingTrainsPutRealTrainsFirst() {
    PidsRow next = row(PLAIN, PidsRow.Status.EN_ROUTE, "2");
    PidsRow cancelled = at(row(THROUGH, PidsRow.Status.CANCELLED, "2"), 150);
    PidsRow later = at(row(THROUGH, PidsRow.Status.PLANNED, "2"), 300);
    PidsRow last = at(row(LOCAL, PidsRow.Status.PLANNED, "2"), 600);

    assertEquals(
        List.of(false, false),
        following(2, next, cancelled, later, last).trains().stream()
            .map(train -> train.badge().hollow())
            .toList());
  }

  /** 后续列车页：下一班之后可以上车的列车与取消的班次，按先后、不多于一页放得下的；通过、本站终到、回库与别的站台的不列。 */
  @Test
  void followingTrainsComeAfterTheNextOne() {
    PidsRow next = row(PLAIN, PidsRow.Status.EN_ROUTE, "2");
    PidsRow passing = copy(row(THROUGH, PidsRow.Status.EN_ROUTE, "2"), true, false, false);
    PidsRow terminating = copy(row(THROUGH, PidsRow.Status.EN_ROUTE, "2"), false, true, false);
    PidsRow cancelled = row(THROUGH, PidsRow.Status.CANCELLED, "2");
    PidsRow elsewhere = row(PLAIN, PidsRow.Status.EN_ROUTE, "5");
    PidsRow later = row(THROUGH, PidsRow.Status.PLANNED, "2");
    PidsRow last = row(LOCAL, PidsRow.Status.PLANNED, "2");

    PidsFollowingView view =
        following(4, next, passing, terminating, cancelled, elsewhere, later, last);

    assertEquals(new Names("后续列车", "Following trains"), view.title());
    assertEquals(
        List.of(true, false, false),
        view.trains().stream().map(train -> train.badge().hollow()).toList(),
        "取消的班次（空心色牌）在前，下一班本身不列");
    assertTrue(view.trains().get(0).destination().struck());
    assertEquals(2, following(2, next, cancelled, later, last).trains().size(), "不多于一页放得下的");
    assertEquals(
        1,
        following(4, passing, cancelled, later, last).trains().size(),
        "下一班之前取消的班次写在停站表页，不进后续列车");
    assertTrue(following(4, passing, cancelled).trains().isEmpty(), "没有可以上车的下一班时不列");
  }

  /** 后续列车页不带备注：轮到备注时内容也不变，免得备注轮换让这一页反复重绘。 */
  @Test
  void followingTrainsIgnoreTheRemarkTurn() {
    net.via.put(THROUGH, List.of("KPO"));
    PidsRow[] rows = {
      row(PLAIN, PidsRow.Status.EN_ROUTE, "2"), row(THROUGH, PidsRow.Status.PLANNED, "2")
    };

    PidsFollowingView english = builder.following(request(false, rows), 4);
    PidsFollowingView remark = builder.following(request(true, rows), 4);

    assertEquals(english, remark);
    assertTrue(remark.trains().get(0).destination().remark().isEmpty());
  }

  /** 同一交路相邻两班色牌、终点、停站都一样，身份不同：运行中的按列车名，计划班次按计划时刻。 */
  @Test
  void trainsOfTheSameRouteHaveDifferentIds() {
    String a =
        view(named(row(PLAIN, PidsRow.Status.EN_ROUTE, "2"), "a")).train().orElseThrow().id();
    String b =
        view(named(row(PLAIN, PidsRow.Status.EN_ROUTE, "2"), "b")).train().orElseThrow().id();
    String arriving =
        view(named(row(PLAIN, PidsRow.Status.ARRIVING, "2"), "a")).train().orElseThrow().id();
    String planned = view(row(PLAIN, PidsRow.Status.PLANNED, "2")).train().orElseThrow().id();

    assertNotEquals(a, b);
    assertEquals(a, arriving, "同一辆车状态变了身份不变");
    assertNotEquals(a, planned);
  }

  /** 不同线路、或同一线路的快速与各停都在本站台停车，才放“确认终点”；通过车与别的站台的车不算。 */
  @Test
  void mixedServicesNeedTwoLinesOrTwoStoppingPatterns() {
    PidsRow rapid = row(PLAIN, PidsRow.Status.EN_ROUTE, "2");
    PidsRow otherRapid = row(THROUGH, PidsRow.Status.PLANNED, "2");
    PidsRow local = row(LOCAL, PidsRow.Status.PLANNED, "2");
    PidsRow waterside =
        new PidsRow(
            PidsRow.Status.PLANNED,
            "WS",
            "SURC:WS:PLAIN",
            "NTA",
            Optional.of("SURC:NTA"),
            "2",
            NOW.plusSeconds(300),
            OptionalLong.empty(),
            0,
            false,
            false,
            false,
            Optional.empty());

    assertFalse(mixed(rapid, otherRapid), "同一线路的快速");
    assertTrue(mixed(rapid, local), "快速与各停");
    assertTrue(mixed(rapid, waterside), "两条线路");
    assertFalse(mixed(rapid, copy(local, true, false, false)), "通过车不算");
    assertFalse(mixed(rapid, row(LOCAL, PidsRow.Status.PLANNED, "5")), "别的站台不算");
    assertFalse(mixed(rapid, row(UNTYPED, PidsRow.Status.PLANNED, "2")), "读不到停站类型的不算另一种");
  }

  @Test
  void anUnknownStopSequenceGivesAnEmptyList() {
    PidsRow row = row(THROUGH, PidsRow.Status.EN_ROUTE, "2");
    PidsRow unknown =
        new PidsRow(
            row.status(),
            row.lineName(),
            row.routeId(),
            row.destination(),
            row.destinationId(),
            row.platform(),
            row.expectedAt(),
            row.delaySeconds(),
            -1,
            false,
            false,
            false,
            row.trainName());

    PidsStopListView.Train train = view(unknown).train().orElseThrow();

    assertTrue(train.stops().isEmpty());
    assertTrue(train.note().isEmpty());
    assertEquals("MT", train.badge().code());
  }

  private PidsStopListView view(PidsRow... rows) {
    return builder.stopList(request(rows));
  }

  private PidsFollowingView following(int limit, PidsRow... rows) {
    return builder.following(request(rows), limit);
  }

  private boolean mixed(PidsRow... rows) {
    return builder.mixedServices(request(rows));
  }

  private static PidsViewBuilder.Request request(PidsRow... rows) {
    return request(false, rows);
  }

  private static PidsViewBuilder.Request request(boolean remarks, PidsRow... rows) {
    return new PidsViewBuilder.Request(
        new PidsSnapshot(PPK, NOW, List.of(rows)),
        NOW,
        ZoneId.of("Asia/Shanghai"),
        PidsTheme.DARK,
        Set.of("2"),
        List.of("2"),
        0,
        false,
        Optional.empty(),
        remarks);
  }

  private static PidsRow named(PidsRow row, String train) {
    return new PidsRow(
        row.status(),
        row.lineName(),
        row.routeId(),
        row.destination(),
        row.destinationId(),
        row.platform(),
        row.expectedAt(),
        row.delaySeconds(),
        row.stopSequence(),
        false,
        false,
        false,
        Optional.of(train));
  }

  private static RouteStop stop(String station, boolean stops, RouteApi.LineRef change) {
    return new RouteStop(Optional.of(station), stops, Optional.ofNullable(change));
  }

  private static PidsRow row(String route, PidsRow.Status status, String platform) {
    return new PidsRow(
        status,
        "MT",
        route,
        "NTA",
        Optional.of("SURC:NTA"),
        platform,
        NOW.plusSeconds(120),
        OptionalLong.empty(),
        0,
        false,
        false,
        false,
        Optional.empty());
  }

  /** 同一行，预计（取消行为计划）在 {@code seconds} 秒后。 */
  private static PidsRow at(PidsRow row, long seconds) {
    return new PidsRow(
        row.status(),
        row.lineName(),
        row.routeId(),
        row.destination(),
        row.destinationId(),
        row.platform(),
        NOW.plusSeconds(seconds),
        row.delaySeconds(),
        row.stopSequence(),
        row.passing(),
        row.terminating(),
        row.outOfService(),
        row.trainName());
  }

  private static PidsRow copy(
      PidsRow row, boolean passing, boolean terminating, boolean outOfService) {
    return new PidsRow(
        row.status(),
        row.lineName(),
        row.routeId(),
        row.destination(),
        row.destinationId(),
        row.platform(),
        row.expectedAt(),
        row.delaySeconds(),
        row.stopSequence(),
        passing,
        terminating,
        outOfService,
        row.trainName());
  }

  /** SPB 只停 MT；HHU 停 MT、DS、WS；KPO 停 WS、DS；NTA 只停 WS。 */
  private static final class Net implements PidsDirectory {
    final Map<String, List<String>> via = new HashMap<>();

    @Override
    public Optional<Names> stationName(String stationId) {
      return Optional.ofNullable(
          Map.of(
                  "SURC:HHU", new Names("新笛矢 · 壑湖", "Neo Fueya - Hor Huu"),
                  "SURC:SPB", new Names("主城湾", "Spawn Bay"),
                  "SURC:KPO", new Names("KPO 站", "KPO"),
                  "SURC:NTA", new Names("南渡", "Nam Toa"))
              .get(stationId));
    }

    @Override
    public Optional<LineStyle> line(String operatorCode, String lineCode) {
      return switch (lineCode) {
        case "WS" -> Optional.of(new LineStyle("WS", WS));
        case "DS" -> Optional.of(new LineStyle("DS", DS));
        default -> Optional.of(new LineStyle("MT", MT));
      };
    }

    @Override
    public Optional<RouteApi.OperationType> serviceType(String routeId) {
      if (UNTYPED.equals(routeId)) {
        return Optional.empty();
      }
      return Optional.of(
          LOCAL.equals(routeId) ? RouteApi.OperationType.LOCAL : RouteApi.OperationType.RAPID);
    }

    @Override
    public List<PidsView.LineChip> linesServing(PidsStationKey station) {
      return switch (station.stationCode()) {
        case "HHU" -> List.of(chip("MT", MT), chip("DS", DS), chip("WS", WS));
        case "KPO" -> List.of(chip("WS", WS), chip("DS", DS));
        case "NTA" -> List.of(chip("WS", WS));
        default -> List.of(chip("MT", MT));
      };
    }

    @Override
    public List<PidsView.LineChip> linesServingPlatform(PidsStationKey station, String platform) {
      return List.of();
    }

    @Override
    public List<RouteStop> stops(String routeId) {
      return switch (routeId) {
        case THROUGH -> THROUGH_STOPS;
        case PLAIN -> PLAIN_STOPS;
        case TRUNK -> TRUNK_STOPS;
        case BRANCH -> BRANCH_STOPS;
        case FEEDER -> FEEDER_STOPS;
        default -> List.of();
      };
    }

    @Override
    public List<String> via(String routeId) {
      return via.getOrDefault(routeId, Collections.emptyList());
    }

    @Override
    public Optional<Names> lineName(String operatorCode, String lineCode) {
      return "WS".equals(lineCode)
          ? Optional.of(new Names("浦蓝线", "Waterside Line"))
          : Optional.empty();
    }

    private static PidsView.LineChip chip(String code, int color) {
      return new PidsView.LineChip(code, color, new Names(code, ""));
    }
  }
}
