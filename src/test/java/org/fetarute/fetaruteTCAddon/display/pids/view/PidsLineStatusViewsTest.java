package org.fetarute.fetaruteTCAddon.display.pids.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus.Condition;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus.Detail;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusView.Row;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusView.Style;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 线路运行状况屏的视图：范围与顺序、密度与翻页、说明。 */
class PidsLineStatusViewsTest {

  private static final Instant NOW = Instant.parse("2026-10-02T13:40:00Z");
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final PidsStationKey HHU = new PidsStationKey("SURC", "HHU");

  private List<String> codes = List.of("BS", "DS", "MT", "WS");
  private List<String> serving = List.of("WS", "MT");
  private List<RouteApi.LineRef> refs;
  private final List<String> asked = new ArrayList<>();
  private PidsLineStatusViews views;

  @BeforeEach
  void setUp() throws Exception {
    YamlConfiguration lang = new YamlConfiguration();
    lang.load(
        new InputStreamReader(
            getClass().getClassLoader().getResourceAsStream("lang/zh_CN.yml"),
            StandardCharsets.UTF_8));
    views =
        new PidsLineStatusViews(
            new Directory(), new PidsVocabulary(key -> lang.getString(key, key)));
  }

  @Test
  void linesServingThisStationComeFirstAndTheScreenFilterApplies() {
    assertEquals(List.of("WS", "MT", "BS", "DS"), codes(build(Set.of(), NOW, good())));
    assertEquals(List.of("MT", "BS"), codes(build(Set.of("MT", "BS"), NOW, good())));
  }

  @Test
  void operatorOnlyScreensListTheOperatorsLinesInTheirOwnOrder() {
    PidsLineStatusView view =
        views.build(
            new PidsLineStatusViews.Request(
                "SURC", Optional.empty(), Set.of(), PidsTheme.DARK, NOW, SHANGHAI, 3, 5),
            good());

    assertEquals(List.of("BS", "DS", "MT", "WS"), codes(view));
  }

  @Test
  void aSameCodeLineOfAnotherOperatorDoesNotPromoteThisOperatorsLine() {
    refs = List.of(new RouteApi.LineRef("SURN", "MT"), new RouteApi.LineRef("SURC", "WS"));

    assertEquals(List.of("WS", "BS", "DS", "MT"), codes(build(Set.of(), NOW, good())));
  }

  @Test
  void aFilterMatchingNoLineSaysSoInsteadOfNoInformation() {
    PidsLineStatusView view = build(Set.of("XYZ"), NOW, good());

    assertTrue(view.rows().isEmpty());
    assertEquals(
        new Names("本屏线路过滤没有匹配的线路", "No lines match this screen's filter"), view.labels().empty());
    assertEquals(
        new Names("暂无线路信息", "No line information"),
        views
            .build(
                new PidsLineStatusViews.Request(
                    "XYZ",
                    Optional.of(new PidsStationKey("XYZ", "HHU")),
                    Set.of(),
                    PidsTheme.DARK,
                    NOW,
                    SHANGHAI,
                    3,
                    5),
                good())
            .labels()
            .empty());
  }

  @Test
  void lineStatusScreensFilterOverTheOperatorsLines() {
    Directory directory = new Directory();

    assertEquals(
        List.of("BS", "DS", "MT", "WS"),
        PidsLineStatusViews.operatorLineChips(directory, HHU.operatorCode()).stream()
            .map(PidsView.LineChip::code)
            .toList());
  }

  @Test
  void fewLinesUseRoomyRowsAndManyLinesPageEveryFifteenSeconds() {
    codes = List.of("BS", "DS", "MT");
    PidsLineStatusView roomy = build(Set.of(), NOW, good());
    assertTrue(roomy.roomy());
    assertEquals(1, roomy.pages());

    codes = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7");
    serving = List.of();
    Instant pageStart = Instant.ofEpochSecond(NOW.getEpochSecond() / 30 * 30);
    PidsLineStatusView first = build(Set.of(), pageStart.plusSeconds(14), good());
    PidsLineStatusView second = build(Set.of(), pageStart.plusSeconds(15), good());

    assertFalse(first.roomy(), "4 条起用小行");
    assertEquals(2, first.pages());
    assertEquals(List.of("A1", "A2", "A3", "A4", "A5"), codes(first));
    assertEquals(1, second.page());
    assertEquals(List.of("A6", "A7"), codes(second));
    assertEquals(List.of("A6", "A7"), asked.subList(asked.size() - 2, asked.size()), "只取当前页的状况");
    assertEquals(0, build(Set.of(), pageStart.plusSeconds(30), good()).page());
  }

  @Test
  void detailsFollowTheCondition() {
    Map<String, PidsLineStatus> statuses =
        Map.of(
            "BS",
            PidsLineStatus.of(Condition.SUSPENDED),
            "DS",
            new PidsLineStatus(
                Condition.PART_SUSPENDED,
                new Detail.Closed(new PidsDirectory.Section("SURC:SPB", "SURC:XYZ"))),
            "MT",
            new PidsLineStatus(
                Condition.ENDED, new Detail.FirstTrain(Instant.parse("2026-10-02T21:30:00Z"))),
            "WS",
            PidsLineStatus.of(Condition.GOOD));

    PidsLineStatusView view = build(Set.of(), NOW, (line, now) -> statuses.get(line.chip().code()));

    Row ws = view.rows().get(0);
    assertEquals(new Names("运行正常", "Good service"), ws.status());
    assertEquals(Style.GOOD, ws.style());
    assertTrue(ws.detail().isEmpty(), "运行正常不写说明");
    Row mt = view.rows().get(1);
    assertEquals(Style.MUTED, mt.style());
    assertEquals(Optional.of(new Names("首班 05:30", "First train 05:30")), mt.detail(), "按屏幕时区写");
    Row bs = view.rows().get(2);
    assertEquals(Style.RED, bs.style());
    assertEquals(Optional.of(new Names("线路检修", "Under maintenance")), bs.detail());
    assertEquals(
        Optional.of(new Names("主城湾—XYZ 暂停运营", "No service Spawn Bay – XYZ")),
        view.rows().get(3).detail(),
        "不知道站名时写站码");
    assertEquals("21:40", view.clock());
    assertEquals(new Names("南城铁路", "SURcentral"), view.operator());
  }

  @Test
  void unknownOperatorsShowTheirCode() {
    PidsLineStatusView view =
        views.build(
            new PidsLineStatusViews.Request(
                "XYZ",
                Optional.of(new PidsStationKey("XYZ", "HHU")),
                Set.of(),
                PidsTheme.DARK,
                NOW,
                SHANGHAI,
                3,
                5),
            good());

    assertEquals(new Names("XYZ", ""), view.operator());
    assertTrue(view.rows().isEmpty());
    assertEquals(1, view.pages());
  }

  private PidsLineStatusView build(Set<String> lines, Instant now, PidsLineStatusSource source) {
    return views.build(
        new PidsLineStatusViews.Request(
            HHU.operatorCode(), Optional.of(HHU), lines, PidsTheme.DARK, now, SHANGHAI, 3, 5),
        (line, at) -> {
          asked.add(line.chip().code());
          return source.statusOf(line, at);
        });
  }

  private static PidsLineStatusSource good() {
    return (line, now) -> PidsLineStatus.of(Condition.GOOD);
  }

  private static List<String> codes(PidsLineStatusView view) {
    return view.rows().stream().map(row -> row.line().code()).toList();
  }

  private final class Directory implements PidsDirectory {
    @Override
    public Optional<Names> stationName(String stationId) {
      return stationId.equals("SURC:SPB")
          ? Optional.of(new Names("主城湾", "Spawn Bay"))
          : Optional.empty();
    }

    @Override
    public Optional<LineStyle> line(String operatorCode, String lineCode) {
      return Optional.empty();
    }

    @Override
    public Optional<RouteApi.OperationType> serviceType(String routeId) {
      return Optional.empty();
    }

    @Override
    public List<PidsView.LineChip> linesServing(PidsStationKey station) {
      return serving.stream().map(PidsLineStatusViewsTest::chip).toList();
    }

    @Override
    public List<RouteApi.LineRef> lineRefsServing(PidsStationKey station) {
      return refs != null ? refs : PidsDirectory.super.lineRefsServing(station);
    }

    @Override
    public List<PidsView.LineChip> linesServingPlatform(PidsStationKey station, String platform) {
      return List.of();
    }

    @Override
    public List<OperatorLine> operatorLines(String operatorCode) {
      if (!operatorCode.equals("SURC")) {
        return List.of();
      }
      return codes.stream()
          .map(
              code ->
                  new OperatorLine(
                      UUID.randomUUID(),
                      "SURC",
                      chip(code),
                      LineApi.LineStatus.ACTIVE,
                      Optional.empty()))
          .toList();
    }

    @Override
    public Optional<Names> operatorName(String operatorCode) {
      return operatorCode.equals("SURC")
          ? Optional.of(new Names("南城铁路", "SURcentral"))
          : Optional.empty();
    }
  }

  private static PidsView.LineChip chip(String code) {
    return new PidsView.LineChip(code, 0x336699, new Names(code + "线", code + " Line"));
  }
}
