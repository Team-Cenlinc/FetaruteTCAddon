package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverGuidance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶员 Boss 栏的内容")
class DriverBossBarViewTest {

  private static DriverGuidance.Advice advice(
      DriverGuidance.TargetKind kind,
      double distance,
      double end,
      double suggested,
      boolean brake) {
    return new DriverGuidance.Advice(
        new DriverGuidance.Target(kind, distance, end), suggested, brake);
  }

  private static DriverBossBarView view(
      Optional<DriverStationHint.Hint> hint, DriverGuidance.Advice advice, boolean ato) {
    return DriverBossBarView.of(
        hint, advice, OptionalLong.empty(), 0L, ato, true, false, "人民广场", 400.0);
  }

  @Test
  @DisplayName("前方畅通：绿色满格，写允许速度与建议速度")
  void clear() {
    DriverBossBarView view =
        view(
            Optional.empty(),
            advice(DriverGuidance.TargetKind.CLEAR, Double.NaN, 20.0, 19.5, false),
            false);
    assertEquals("drive.bossbar.clear", view.titleKey());
    assertEquals(Map.of("limit_kmh", "72"), view.values());
    assertEquals(OptionalInt.of(70), view.suggestedKmh());
    assertEquals(DriverBossBarView.Tone.GREEN, view.tone());
    assertEquals(1.0, view.progress(), 1.0e-9);
  }

  @Test
  @DisplayName("停车信号：红色，进度按距离，提示开始制动")
  void stopSignal() {
    DriverBossBarView view =
        view(
            Optional.empty(),
            advice(DriverGuidance.TargetKind.STOP_SIGNAL, 120.0, 0.0, 11.4, true),
            false);
    assertEquals("drive.bossbar.stop-signal", view.titleKey());
    assertEquals(Map.of("distance", "120"), view.values());
    assertTrue(view.brake());
    assertEquals(DriverBossBarView.Tone.RED, view.tone());
    assertEquals(0.3, view.progress(), 1.0e-9);
  }

  @Test
  @DisplayName("前方限速：黄色，写目标限速与距离")
  void speedLimit() {
    DriverBossBarView view =
        view(
            Optional.empty(),
            advice(DriverGuidance.TargetKind.SPEED_LIMIT, 280.0, 12.5, 18.0, false),
            false);
    assertEquals("drive.bossbar.limit", view.titleKey());
    assertEquals(Map.of("limit_kmh", "45", "distance", "280"), view.values());
    assertEquals(DriverBossBarView.Tone.YELLOW, view.tone());
  }

  @Test
  @DisplayName("ATO：不给建议速度，畅通时写下一站")
  void ato() {
    DriverBossBarView approach =
        view(
            Optional.empty(),
            advice(DriverGuidance.TargetKind.STATION, 160.0, 0.0, 15.0, false),
            true);
    assertEquals("drive.bossbar.station-approach", approach.titleKey());
    assertTrue(approach.ato());
    assertTrue(approach.suggestedKmh().isEmpty());

    DriverBossBarView clear =
        view(
            Optional.empty(),
            advice(DriverGuidance.TargetKind.CLEAR, Double.NaN, 0.0, 0.0, false),
            true);
    assertEquals("drive.bossbar.ato-clear", clear.titleKey());
    assertEquals(Map.of("station", "人民广场"), clear.values());
  }

  @Test
  @DisplayName("还没收到行车许可：白色提示")
  void noDirective() {
    DriverBossBarView view =
        DriverBossBarView.of(
            Optional.empty(),
            advice(DriverGuidance.TargetKind.CLEAR, Double.NaN, 0.0, 0.0, false),
            OptionalLong.empty(),
            0L,
            false,
            false,
            true,
            "",
            400.0);
    assertEquals("drive.bossbar.no-signal", view.titleKey());
  }

  @Test
  @DisplayName("停站中显示停站阶段，进度是剩余停站时间")
  void dwell() {
    DriverStationHint.Hint hint =
        new DriverStationHint.Hint(
            DriverStationHint.Kind.DWELL, "", Map.of("station", "人民广场", "seconds", "18"));
    DriverBossBarView view =
        DriverBossBarView.of(
            Optional.of(hint),
            advice(DriverGuidance.TargetKind.CLEAR, Double.NaN, 0.0, 0.0, false),
            OptionalLong.of(800L),
            360L,
            false,
            true,
            true,
            "人民广场",
            400.0);
    assertEquals("drive.bossbar.station.dwell", view.titleKey());
    assertEquals(0.45, view.progress(), 1.0e-9);
    assertFalse(view.brake());
  }

  @Test
  @DisplayName("停妥待开门：键名带站台侧")
  void openDoors() {
    DriverStationHint.Hint hint =
        new DriverStationHint.Hint(
            DriverStationHint.Kind.OPEN_DOORS, "left", Map.of("station", "人民广场"));
    DriverBossBarView view =
        view(
            Optional.of(hint),
            advice(DriverGuidance.TargetKind.CLEAR, Double.NaN, 0.0, 0.0, false),
            false);
    assertEquals("drive.bossbar.station.open-doors.left", view.titleKey());
    assertEquals(DriverBossBarView.Tone.BLUE, view.tone());
  }

  @Test
  @DisplayName("停稳时建议 0 不显示")
  void hideZeroSuggestionWhenStopped() {
    DriverBossBarView view =
        DriverBossBarView.of(
            Optional.empty(),
            advice(DriverGuidance.TargetKind.STOP_SIGNAL, 0.0, 0.0, 0.0, false),
            OptionalLong.empty(),
            0L,
            false,
            true,
            true,
            "",
            400.0);
    assertTrue(view.suggestedKmh().isEmpty());
  }

  @Test
  @DisplayName("停车信号就在车头处：写就地停车")
  void stopHere() {
    DriverBossBarView view =
        view(
            Optional.empty(),
            advice(DriverGuidance.TargetKind.STOP_SIGNAL, 0.0, 0.0, 0.0, false),
            false);
    assertEquals("drive.bossbar.stop-signal-here", view.titleKey());
  }
}
