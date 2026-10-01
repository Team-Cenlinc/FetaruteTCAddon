package org.fetarute.fetaruteTCAddon.display.pids.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
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
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.ArrivalMode;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Tone;
import org.junit.jupiter.api.Test;

/** 快照到显示内容：按站台过滤、解析名称与颜色、每行的状态词与色调。 */
class PidsViewBuilderTest {

  private static final Instant NOW = Instant.parse("2026-09-30T13:40:00Z");
  private static final PidsStationKey STATION = new PidsStationKey("SURC", "NTA");
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
                2));

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
                3));

    assertEquals(List.of(MT, WS), view.bandColors());
  }

  @Test
  void minutesRoundUpAndNeverGoNegative() {
    assertEquals(0, PidsViewBuilder.minutesUntil(NOW.minusSeconds(5), NOW));
    assertEquals(1, PidsViewBuilder.minutesUntil(NOW.plusSeconds(10), NOW));
    assertEquals(2, PidsViewBuilder.minutesUntil(NOW.plusMillis(60_500), NOW));
  }

  private PidsView view(PidsRow... rows) {
    return builder.build(
        new PidsViewBuilder.Request(
            new PidsSnapshot(STATION, NOW, List.of(rows)),
            NOW,
            ZoneId.of("Asia/Shanghai"),
            PidsTheme.DARK,
            Set.of(),
            List.of("1"),
            6));
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
          Map.entry("pids.board.destination.terminating", "本站终到"),
          Map.entry("pids.board.destination.terminating-secondary", "Terminates here"),
          Map.entry("pids.board.destination.out-of-service", "回库"),
          Map.entry("pids.board.destination.out-of-service-secondary", "Not in Service"),
          Map.entry("pids.board.type.local", "各停"));

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
