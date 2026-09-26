package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.fetarute.fetaruteTCAddon.display.hud.HudLanguageRotation.Language;
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.BossBarHudTemplate;
import org.junit.jupiter.api.Test;

/** 取自默认模板的真实行：横栏与 ActionBar 在任何时刻都必须显示同一种语言。 */
class HudLanguageRotationTest {

  private static final String BOSSBAR =
      String.join(
          "\n",
          "rotate_ticks: 60",
          "AT_STATION_1: <white>{train_name}</white> <dark_gray>┃</dark_gray> <white>开往 {dest_eop}</white>",
          "AT_STATION_2: <white>{train_name}</white> <dark_gray>┃</dark_gray> <white>To {dest_eop_lang2}</white>",
          "IN_TRIP_1: <white>{line}</white> ┃ {speed} ┃ <gray>车厢 ⟨</gray>{player_carriage_no}",
          "IN_TRIP_2: <white>{line_lang2}</white> ┃ {speed} ┃ <gray>Car. ⟨</gray>{player_carriage_no}");

  private static final String ACTIONBAR =
      String.join(
          "\n",
          "rotate_ticks: 60",
          "DEPARTING_1: <aqua>▶</aqua> <white>欢迎乘坐</white> <aqua>{operator}</aqua>",
          "DEPARTING_2: <aqua>▶</aqua> <white>本次列车开往</white> <yellow>{dest_eop}</yellow>",
          "DEPARTING_3: <aqua>▶</aqua> <white>Welcome aboard</white> <yellow>{dest_eop_lang2}</yellow>",
          "IN_TRIP_1: <white>下一站</white> <yellow>{next_station}</yellow>",
          "IN_TRIP_2: <white>Next</white> <yellow>{next_station_lang2}</yellow>");

  @Test
  void classifiesDefaultTemplateLines() {
    assertEquals(Language.PRIMARY, HudLanguageRotation.classify("<white>开往 {dest_eop}</white>"));
    assertEquals(
        Language.SECONDARY, HudLanguageRotation.classify("<white>To {dest_eop_lang2}</white>"));
    assertEquals(
        Language.NEUTRAL,
        HudLanguageRotation.classify(
            "<{line_color_tag}>█</{line_color_tag}> <white>{line}</white>"));
    assertEquals(
        Language.SECONDARY, HudLanguageRotation.classify("<gray>{eta_status_en_US}</gray>"));
    assertEquals(Language.PRIMARY, HudLanguageRotation.classify("<gray>{eta_status_zh_CN}</gray>"));
  }

  @Test
  void barsShowTheSameLanguageAtEveryTick() {
    BossBarHudTemplate bossbar = BossBarHudTemplate.parse(BOSSBAR, message -> {});
    BossBarHudTemplate actionbar = BossBarHudTemplate.parse(ACTIONBAR, message -> {});
    // 修复前：AT_STATION 两行按 (t/60)%2、DEPARTING 三行按 (t/60)%3 轮播，一块中文一块英文。
    for (long tick = 0; tick < 60L * 12; tick += 20) {
      Language top =
          HudLanguageRotation.classify(
              bossbar.resolveLine(HudState.AT_STATION, tick).orElseThrow());
      Language bottom =
          HudLanguageRotation.classify(
              actionbar.resolveLine(HudState.DEPARTING, tick).orElseThrow());
      assertEquals(top, bottom, "tick=" + tick);
    }
  }

  @Test
  void differentTemplatePeriodsStillSwitchLanguageTogether() {
    // 实服已落盘的模板：侧边栏 page_duration_ticks 80，横栏 rotate_ticks 60。
    // 修复前各按自己的周期交替，80 与 60 的相位每隔几轮就错开。
    List<String> lcd = List.of("开往 {dest_eop}", "To {dest_eop_lang2}");
    List<String> bar = List.of("下一站 {next_station}", "Next {next_station_lang2}");
    for (long tick = 0; tick < 60L * 40; tick += 10) {
      String a = HudLanguageRotation.select(lcd, HudLanguageRotation::classify, tick, 80, 60);
      String b = HudLanguageRotation.select(bar, HudLanguageRotation::classify, tick, 60, 60);
      assertEquals(
          HudLanguageRotation.classify(a), HudLanguageRotation.classify(b), "tick=" + tick);
    }
  }

  @Test
  void secondLineOfSameLanguageStillShows() {
    BossBarHudTemplate actionbar = BossBarHudTemplate.parse(ACTIONBAR, message -> {});
    // DEPARTING 的两行中文轮流出现在中文相位里，不会因为按语言分组而永远只显示第一行。
    assertEquals(
        "<aqua>▶</aqua> <white>欢迎乘坐</white> <aqua>{operator}</aqua>",
        actionbar.resolveLine(HudState.DEPARTING, 0).orElseThrow());
    assertEquals(
        Language.SECONDARY,
        HudLanguageRotation.classify(actionbar.resolveLine(HudState.DEPARTING, 60).orElseThrow()));
    assertEquals(
        "<aqua>▶</aqua> <white>本次列车开往</white> <yellow>{dest_eop}</yellow>",
        actionbar.resolveLine(HudState.DEPARTING, 120).orElseThrow());
  }

  @Test
  void singleLanguageListsKeepPlainRotation() {
    List<String> items = List.of("a1", "a2", "a3");
    assertEquals("a1", HudLanguageRotation.select(items, s -> Language.NEUTRAL, 0, 60));
    assertEquals("a2", HudLanguageRotation.select(items, s -> Language.NEUTRAL, 60, 60));
    assertEquals("a3", HudLanguageRotation.select(items, s -> Language.NEUTRAL, 120, 60));
    assertEquals("a1", HudLanguageRotation.select(items, s -> Language.NEUTRAL, 180, 60));
  }
}
