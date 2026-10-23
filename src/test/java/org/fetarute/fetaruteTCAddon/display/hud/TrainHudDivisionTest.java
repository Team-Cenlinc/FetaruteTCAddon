package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.coupler.DivisionForecast;
import org.junit.jupiter.api.Test;

/** 分行占位符按玩家所在的车厢（从车头数起）取分行后的那一段；在终点分行时接着开的车厢不报终点站。 */
class TrainHudDivisionTest {

  private static final DivisionForecast.Part FRONT =
      new DivisionForecast.Part(4, false, Optional.of("SURC:AAA"), "甲站");
  private static final DivisionForecast.Part REAR =
      new DivisionForecast.Part(2, true, Optional.empty(), "");

  private static final DivisionForecast FORECAST =
      new DivisionForecast("SURC:MT:MT-3", 5, "SURC:SSS", "分行站", List.of(FRONT, REAR));

  @Test
  void eachCarFallsInItsSegment() {
    assertEquals(Optional.of(FRONT), TrainHudContextResolver.partOf(FORECAST, 1));
    assertEquals(Optional.of(FRONT), TrainHudContextResolver.partOf(FORECAST, 4));
    assertEquals(Optional.of(REAR), TrainHudContextResolver.partOf(FORECAST, 5));
    assertEquals(Optional.of(REAR), TrainHudContextResolver.partOf(FORECAST, 6));
  }

  /** 在运营终点分行：这节车厢接着开的乘客不报终点站；这节车厢到此止、模板没写分行状态、分行站不是这一站时照旧。 */
  @Test
  void aCarContinuingPastADivisionAtTheTerminusIsNotToldToAlight() {
    java.util.Map<String, String> continuing =
        java.util.Map.of(
            "division_station_code", "HHU",
            "car_destination", "南渡",
            "next_station_code", "HHU",
            "current_station_code", "HHU");
    java.util.Map<String, String> ending =
        java.util.Map.of(
            "division_station_code", "HHU",
            "car_destination", "-",
            "next_station_code", "HHU",
            "current_station_code", "HHU");
    java.util.Map<String, String> elsewhere =
        java.util.Map.of(
            "division_station_code", "PPK",
            "car_destination", "南渡",
            "next_station_code", "HHU",
            "current_station_code", "HHU");

    assertEquals(
        HudState.DIVIDE_ARRIVING,
        HudStateTracker.applyDivision(HudState.TERM_ARRIVING, continuing, true, true));
    assertEquals(
        HudState.AT_DIVIDE_STATION,
        HudStateTracker.applyDivision(HudState.AT_LAST_STATION, continuing, true, true));
    assertEquals(
        HudState.AT_LAST_STATION,
        HudStateTracker.applyDivision(HudState.AT_LAST_STATION, ending, true, true),
        "这节车厢到此止：照旧请下车");
    assertEquals(
        HudState.TERM_ARRIVING,
        HudStateTracker.applyDivision(HudState.TERM_ARRIVING, continuing, false, false),
        "旧模板没有这一状态");
    assertEquals(
        HudState.AT_LAST_STATION,
        HudStateTracker.applyDivision(HudState.AT_LAST_STATION, elsewhere, true, true),
        "分行站不是这一站");
    assertEquals(
        HudState.IN_TRIP, HudStateTracker.applyDivision(HudState.IN_TRIP, continuing, true, true));
  }

  @Test
  void carsOutsideTheForecastHaveNoSegment() {
    assertEquals(Optional.empty(), TrainHudContextResolver.partOf(FORECAST, 0), "不知道第几节");
    assertEquals(Optional.empty(), TrainHudContextResolver.partOf(FORECAST, 7), "编组变了，对不上");
  }
}
