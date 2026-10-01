package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.BossBarHudTemplate;
import org.junit.jupiter.api.Test;

class HudTextTest {

  @Test
  void replacesKnownPlaceholdersAndKeepsUnknownOnes() {
    assertEquals(
        "L1-Central-{unknown}",
        HudText.apply(
            "{line}-{next_station}-{unknown}", Map.of("line", "L1", "next_station", "Central")));
  }

  @Test
  void conditionalPlaceholderRendersLikeAPlainOne() {
    assertEquals("可换乘 DS", HudText.apply("可换乘 {?transfer_lines}", Map.of("transfer_lines", "DS")));
  }

  @Test
  void lineIsHiddenWhenAConditionalPlaceholderIsMissing() {
    String line = "可换乘 {?transfer_lines} ┃ {next_station}";
    assertTrue(HudText.shown(line, Map.of("transfer_lines", "DS")));
    assertFalse(HudText.shown(line, Map.of("transfer_lines", "-")), "解析器把缺失数据写成 -");
    assertFalse(HudText.shown(line, Map.of("transfer_lines", " ")));
    assertFalse(HudText.shown(line, Map.of()), "没提供的 key 同样算缺失");
    assertTrue(HudText.shown("下一站 {next_station}", Map.of()), "普通占位符不影响显示");
  }

  @Test
  void interactiveTagsFromNamesAreNotParsed() {
    Component line =
        HudText.render(
            "下一站 {station}",
            Map.of(
                "station",
                "<click:open_url:'https://example.com'>东山</click><hover:show_text:'x'>站</hover>"),
            null);

    assertTrue(noEvents(line), "名称里的点击、悬停不能生效");
    assertTrue(
        PlainTextComponentSerializer.plainText().serialize(line).contains("<click:"), "按原文显示");
    assertTrue(
        HudText.parse("<red>{x}</red><key:key.swapOffhand>", null).children().size() > 0,
        "颜色与按键名照常解析");
  }

  @Test
  void identicalTextIsParsedOnce() {
    String line = "<yellow>下一站</yellow> <white>新笛矢·壑湖</white>";

    assertSame(HudText.parse(line, null), HudText.parse(line, null), "同样的文字共用解析结果");
  }

  private static boolean noEvents(Component component) {
    return component.clickEvent() == null
        && component.hoverEvent() == null
        && component.children().stream().allMatch(HudTextTest::noEvents);
  }

  @Test
  void transferNamesAreEscaped() {
    String chips =
        TrainHudContextResolver.transferChips(
            List.of(
                new TrainHudContext.Transfer(
                    "DS", "<click:run_command:'/op x'>东山线", "", "#F6A000")),
            TrainHudContext.Transfer::name);

    assertTrue(noEvents(HudText.parse(chips, null)));
  }

  @Test
  void colorTagAcceptsHexWithOrWithoutHashAndNamedColors() {
    assertEquals("#F6A000", HudText.colorTag("#f6a000"));
    assertEquals("#F6A000", HudText.colorTag("F6A000"), "不带 # 时 MiniMessage 会把它当成文字");
    assertEquals("aqua", HudText.colorTag("AQUA"));
    assertEquals("dark_gray", HudText.colorTag("dark_grey"), "MiniMessage 也认的英式拼写");
    assertEquals("white", HudText.colorTag("not-a-color"));
    assertEquals("white", HudText.colorTag(null));
  }

  @Test
  void hiddenLinesDropOutOfRotationAndEmptyStatesFallBackToDefault() {
    BossBarHudTemplate template =
        BossBarHudTemplate.parse(
            String.join(
                "\n",
                "rotate_ticks: 20",
                "DEFAULT: 开往 {dest_eop}",
                "ARRIVING_1: 到了 {next_station}",
                "ARRIVING_2: 可换乘 {?transfer_lines}",
                "IN_TRIP: 晚点 {?delay_minutes} 分钟"),
            message -> {});
    Map<String, String> noTransfer = Map.of("transfer_lines", "-", "delay_minutes", "-");

    for (long tick = 0; tick < 200; tick += 20) {
      assertEquals(
          "到了 {next_station}",
          template.resolveLine(HudState.ARRIVING, tick, noTransfer).orElseThrow(),
          "没有换乘的站只轮播第一行");
    }
    assertEquals(
        "可换乘 {?transfer_lines}",
        template.resolveLine(HudState.ARRIVING, 20, Map.of("transfer_lines", "DS")).orElseThrow());
    assertEquals(
        "开往 {dest_eop}",
        template.resolveLine(HudState.IN_TRIP, 0, noTransfer).orElseThrow(),
        "状态的行全部隐藏时与没写这个状态一样");
  }
}
