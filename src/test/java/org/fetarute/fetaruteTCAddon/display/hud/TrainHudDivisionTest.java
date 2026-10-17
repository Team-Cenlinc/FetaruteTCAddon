package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.coupler.DivisionForecast;
import org.junit.jupiter.api.Test;

/** 分行占位符按玩家所在的车厢（从车头数起）取分行后的那一段。 */
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

  @Test
  void carsOutsideTheForecastHaveNoSegment() {
    assertEquals(Optional.empty(), TrainHudContextResolver.partOf(FORECAST, 0), "不知道第几节");
    assertEquals(Optional.empty(), TrainHudContextResolver.partOf(FORECAST, 7), "编组变了，对不上");
  }
}
