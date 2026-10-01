package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
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
  void colorTagAcceptsHexWithOrWithoutHashAndNamedColors() {
    assertEquals("#F6A000", HudText.colorTag("#f6a000"));
    assertEquals("#F6A000", HudText.colorTag("F6A000"), "不带 # 时 MiniMessage 会把它当成文字");
    assertEquals("aqua", HudText.colorTag("AQUA"));
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
