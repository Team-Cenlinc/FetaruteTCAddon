package org.fetarute.fetaruteTCAddon.display.pids.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
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
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Remark;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.RemarkPart;
import org.junit.jupiter.api.Test;

/** 备注：末班车、直通、经由（配置优先，没配置按换乘线路数、站台数、直通站推断）。 */
class PidsRemarksTest {

  private static final Instant NOW = Instant.parse("2026-10-01T13:40:00Z");
  private static final String ROUTE = "SURC:MT:R1";
  private static final int WS = 0x70DEEE;

  /** PPK →SPB → HHU（换 WS）→ XYZ 通过 → KPO → NTA 终到 → 回库。 */
  private static final List<RouteStop> STOPS =
      List.of(
          stop("SURC:PPK", true, null),
          stop("SURC:SPB", true, null),
          stop("SURC:HHU", true, new RouteApi.LineRef("SURC", "WS")),
          stop("SURC:XYZ", false, null),
          stop("SURC:KPO", true, null),
          stop("SURC:NTA", true, null),
          new RouteStop(Optional.empty(), false, Optional.empty()));

  private final Net net = new Net();
  private final PidsRemarks remarks = new PidsRemarks(net, new PidsVocabulary(key -> key));

  @Test
  void inferredViaIsTheStationWithTheMostLines() {
    Remark remark = remarks.of(row(0, false), PidsTheme.DARK).orElseThrow();

    assertEquals(
        List.of(
            new RemarkPart("pids.board.remark.through", WS, List.of("浦蓝线"), Optional.of("WS")),
            new RemarkPart(
                "pids.board.remark.via",
                PidsTheme.DARK.amber(),
                List.of("新笛矢·壑湖"),
                Optional.empty())),
        remark.parts(),
        "直通写换入线路名（短写法为线路代码）；经由推换乘线路最多的新笛矢·壑湖，站名里的间隔号收紧");
  }

  @Test
  void lastTrainComesFirstInRed() {
    Remark remark = remarks.of(row(0, true), PidsTheme.LIGHT).orElseThrow();

    assertEquals(
        new RemarkPart(
            "pids.board.remark.last-train", PidsTheme.LIGHT.red(), List.of(), Optional.empty()),
        remark.parts().get(0));
    assertEquals(3, remark.parts().size());
  }

  @Test
  void configuredViaIsUsedInConfiguredOrderAndPassedStationsAreDropped() {
    net.via.put(ROUTE, List.of("kpo", "SPB", "PPK"));

    assertEquals(
        List.of("KPO 站", "主城湾"), via(remarks.of(row(0, false), PidsTheme.DARK)), "按配置顺序；本站不算经由");
    assertEquals(List.of("KPO 站"), via(remarks.of(row(2, false), PidsTheme.DARK)), "已经过的站不写");
  }

  @Test
  void configuredViaThatIsAllBehindTheTrainIsNotReplacedByAGuess() {
    net.via.put(ROUTE, List.of("SPB"));

    assertEquals(List.of(), via(remarks.of(row(4, false), PidsTheme.DARK)));
  }

  @Test
  void atTheChangeStationThereIsNoThroughButTheNextBigStationIsTheVia() {
    Remark remark = remarks.of(row(2, false), PidsTheme.DARK).orElseThrow();

    assertEquals(1, remark.parts().size(), "本站就是换线站：列车已按新线路发车，不写直通");
    assertEquals(List.of("KPO 站"), remark.parts().get(0).items());
  }

  @Test
  void smallStationsWithOneLineAreNotGuessedAsVia() {
    net.lines.put("SURC:KPO", 1);
    net.platforms.put("SURC:KPO", 2);

    assertEquals(Optional.empty(), remarks.of(row(2, false), PidsTheme.DARK));
  }

  @Test
  void manyPlatformsAloneMakeABigStation() {
    net.lines.put("SURC:KPO", 1);
    net.platforms.put("SURC:KPO", PidsRemarks.BIG_STATION_PLATFORMS);

    assertEquals(List.of("KPO 站"), via(remarks.of(row(2, false), PidsTheme.DARK)));
  }

  @Test
  void rowsThatPassersCannotBoardHaveNoRemark() {
    PidsRow base = row(0, true);
    for (PidsRow row :
        List.of(
            copy(base, PidsRow.Status.CANCELLED, false, false, false),
            copy(base, base.status(), true, false, false),
            copy(base, base.status(), false, true, false),
            copy(base, base.status(), false, false, true))) {
      assertEquals(Optional.empty(), remarks.of(row, PidsTheme.DARK), row.toString());
    }
  }

  @Test
  void unknownStopSequenceOnlyKeepsTheLastTrain() {
    Remark remark = remarks.of(row(-1, true), PidsTheme.DARK).orElseThrow();

    assertEquals(1, remark.parts().size());
    assertEquals("pids.board.remark.last-train", remark.parts().get(0).tag());
  }

  @Test
  void theViewOnlyCarriesRemarksWhileTheyAreShown() {
    PidsViewBuilder builder = new PidsViewBuilder(net, new PidsVocabulary(key -> key));
    PidsSnapshot snapshot =
        new PidsSnapshot(new PidsStationKey("SURC", "PPK"), NOW, List.of(row(0, true)));

    PidsView english = builder.build(request(snapshot, false));
    PidsView remark = builder.build(request(snapshot, true));

    assertTrue(english.rows().get(0).destination().remark().isEmpty());
    assertEquals(3, remark.rows().get(0).destination().remark().orElseThrow().parts().size());
    assertEquals(
        english.rows().get(0).destination().names(), remark.rows().get(0).destination().names());
  }

  private static PidsViewBuilder.Request request(PidsSnapshot snapshot, boolean remarks) {
    return new PidsViewBuilder.Request(
        snapshot,
        NOW,
        ZoneId.of("Asia/Shanghai"),
        PidsTheme.DARK,
        Set.of(),
        List.of(),
        6,
        true,
        Optional.empty(),
        remarks);
  }

  private static List<String> via(Optional<Remark> remark) {
    return remark.stream()
        .flatMap(found -> found.parts().stream())
        .filter(part -> part.tag().equals("pids.board.remark.via"))
        .flatMap(part -> part.items().stream())
        .toList();
  }

  private static RouteStop stop(String station, boolean stops, RouteApi.LineRef change) {
    return new RouteStop(Optional.of(station), stops, Optional.ofNullable(change));
  }

  private static PidsRow row(int stopSequence, boolean lastTrain) {
    return new PidsRow(
        PidsRow.Status.EN_ROUTE,
        "MT",
        ROUTE,
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
        lastTrain);
  }

  private static PidsRow copy(
      PidsRow row,
      PidsRow.Status status,
      boolean passing,
      boolean terminating,
      boolean outOfService) {
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
        passing,
        terminating,
        outOfService,
        row.trainName(),
        row.platformPending(),
        row.platformCandidates(),
        row.cars(),
        row.previousPlatform(),
        row.lastTrain());
  }

  /** PPK、SPB 只停 MT；HHU 三条线六个站台；KPO 两条线四个站台。 */
  private static final class Net implements PidsDirectory {
    final Map<String, List<String>> via = new HashMap<>();
    final Map<String, Integer> lines =
        new HashMap<>(Map.of("SURC:HHU", 3, "SURC:KPO", 2, "SURC:SPB", 1, "SURC:PPK", 2));
    final Map<String, Integer> platforms =
        new HashMap<>(Map.of("SURC:HHU", 6, "SURC:KPO", 4, "SURC:SPB", 2));

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
        case "MT" -> Optional.of(new LineStyle("MT", 0xD920D9));
        default -> Optional.empty();
      };
    }

    @Override
    public Optional<RouteApi.OperationType> serviceType(String routeId) {
      return Optional.of(RouteApi.OperationType.RAPID);
    }

    @Override
    public List<PidsView.LineChip> linesServing(PidsStationKey station) {
      int count = lines.getOrDefault(station.toString(), 0);
      return java.util.Collections.nCopies(
          count, new PidsView.LineChip("MT", 0xD920D9, new Names("大都会线", "")));
    }

    @Override
    public List<PidsView.LineChip> linesServingPlatform(PidsStationKey station, String platform) {
      return List.of();
    }

    @Override
    public List<RouteStop> stops(String routeId) {
      return ROUTE.equals(routeId) ? STOPS : List.of();
    }

    @Override
    public List<String> via(String routeId) {
      return via.getOrDefault(routeId, List.of());
    }

    @Override
    public int platformCount(String stationId) {
      return platforms.getOrDefault(stationId, 0);
    }

    @Override
    public Optional<Names> lineName(String operatorCode, String lineCode) {
      return "WS".equals(lineCode)
          ? Optional.of(new Names("浦蓝线", "Waterside Line"))
          : Optional.empty();
    }
  }
}
