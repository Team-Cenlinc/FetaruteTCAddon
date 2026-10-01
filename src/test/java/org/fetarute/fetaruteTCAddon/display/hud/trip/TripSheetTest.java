package org.fetarute.fetaruteTCAddon.display.hud.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges.LineRef;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext.StationDisplay;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContextResolver.UpcomingStop;
import org.junit.jupiter.api.Test;

class TripSheetTest {

  private static final LineRef MT = new LineRef("OFL", "MT");
  private static final LineRef WS = new LineRef("OFL", "WS");
  private static final TrainHudContext.Transfer DS =
      new TrainHudContext.Transfer("DS", "德胜线", "Desheng", "#F6A000");

  private static final TripSheet.Texts TEXTS =
      new TripSheet.Texts(
          "{line} 开往 {dest_eop}",
          "列车 {train_name}",
          "下一站 {station}",
          "{station}",
          "终点 {station}",
          List.of("换乘 {?transfer_lines}", "晚点 {?delay_minutes} 分钟", "本站起改为 {?line_change}"));

  private static UpcomingStop stop(
      int sequence,
      String name,
      LineRef line,
      List<TrainHudContext.Transfer> transfers,
      boolean terminal) {
    return new UpcomingStop(
        sequence,
        StationDisplay.of(name, name, name),
        EtaResult.unavailable("-", List.of()),
        "1",
        Optional.of(line),
        transfers,
        OptionalLong.empty(),
        terminal);
  }

  private static TripSheet build(List<UpcomingStop> stops, int total, boolean transfersOnly) {
    return build(stops, total, 12, transfersOnly);
  }

  private static TripSheet build(
      List<UpcomingStop> stops, int total, int limit, boolean transfersOnly) {
    Map<String, String> base = Map.of("line", "大都会线", "dest_eop", "蒲塘桥", "train_name", "0366");
    return TripSheet.build(
        TEXTS,
        base,
        stops,
        total,
        limit,
        transfersOnly,
        Optional.of(MT),
        (stop, sequence) -> {
          Map<String, String> row = new HashMap<>(base);
          row.put("station", stop.display().label());
          row.put("transfer_lines", stop.transfers().isEmpty() ? "-" : "DS");
          row.put("delay_minutes", "-");
          if (!stop.line().orElseThrow().sameLine(MT)) {
            row.put("line", "浦蓝线");
          }
          return row;
        });
  }

  private static String plain(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  private final List<UpcomingStop> stops =
      List.of(
          stop(1, "新笛矢", MT, List.of(DS), false),
          stop(2, "镇西", MT, List.of(), false),
          stop(3, "主城湾", WS, List.of(), false),
          stop(4, "蒲塘桥", WS, List.of(DS), true));

  @Test
  void rowsCarryTheirKindAndOnlyTheDetailsThatHaveValues() {
    TripSheet sheet = build(stops, 6, false);

    assertEquals("大都会线 开往 蒲塘桥", plain(sheet.title()));
    assertEquals(
        List.of(
            TripSheet.Kind.NEXT, TripSheet.Kind.STOP, TripSheet.Kind.STOP, TripSheet.Kind.TERMINAL),
        sheet.rows().stream().map(TripSheet.Row::kind).toList());
    assertEquals("下一站 新笛矢\n换乘 DS", plain(sheet.rows().get(0).text()));
    assertEquals("镇西", plain(sheet.rows().get(1).text()));
    assertEquals("主城湾\n本站起改为 浦蓝线", plain(sheet.rows().get(2).text()), "直通换线只标在换线的那一站");
    assertEquals("终点 蒲塘桥\n换乘 DS", plain(sheet.rows().get(3).text()));
    assertEquals(2, sheet.hidden(), "超出上限的站计入未列出");
  }

  @Test
  void transfersOnlyKeepsNextStopTerminalAndLineChanges() {
    TripSheet sheet = build(stops, 4, true);

    assertEquals(
        List.of("下一站 新笛矢", "主城湾", "终点 蒲塘桥"),
        sheet.rows().stream().map(row -> plain(row.text()).split("\n")[0]).toList());
    assertEquals(1, sheet.hidden());
  }

  @Test
  void transfersFurtherAheadThanTheLimitAreStillListed() {
    List<UpcomingStop> longLine = new ArrayList<>();
    for (int i = 1; i <= 20; i++) {
      longLine.add(stop(i, "站" + i, MT, i == 18 ? List.of(DS) : List.of(), i == 20));
    }

    TripSheet all = build(longLine, 20, 3, false);
    assertEquals(3, all.rows().size(), "最多列出 limit 站");
    assertEquals(17, all.hidden());

    TripSheet transfers = build(longLine, 20, 3, true);
    assertEquals(
        List.of("下一站 站1", "站18", "终点 站20"),
        transfers.rows().stream().map(row -> plain(row.text()).split("\n")[0]).toList(),
        "先筛后截：第 18 站的换乘站也能列出");
  }
}
