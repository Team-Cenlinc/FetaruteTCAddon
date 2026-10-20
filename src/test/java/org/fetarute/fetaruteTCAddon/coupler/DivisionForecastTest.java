package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives.End;
import org.junit.jupiter.api.Test;

/** 分行预测的两段：车头在前，切车尾时解下的在后，切车头时解下的在前；不载客的一段没有终点；在运营终点拆时留下的一段到此止。 */
class DivisionForecastTest {

  private static final DivisionForecast.Destination NTA =
      new DivisionForecast.Destination(Optional.of("SURC:NTA"), "南渡");
  private static final DivisionForecast.Destination TPC =
      new DivisionForecast.Destination(Optional.of("SURC:TPC"), "大港城");

  @Test
  void rearCutPutsTheDetachedPartBehind() {
    List<DivisionForecast.Part> parts =
        DivisionForecast.parts(8, new DecoupleCut.Cut(4, End.REAR), NTA, TPC);

    assertEquals(
        List.of(
            new DivisionForecast.Part(4, false, Optional.of("SURC:NTA"), "南渡"),
            new DivisionForecast.Part(4, true, Optional.of("SURC:TPC"), "大港城")),
        parts);
  }

  @Test
  void frontCutPutsTheDetachedPartInFront() {
    List<DivisionForecast.Part> parts =
        DivisionForecast.parts(
            6, new DecoupleCut.Cut(2, End.FRONT), NTA, DivisionForecast.Destination.NOT_IN_SERVICE);

    assertEquals(2, parts.get(0).cars());
    assertTrue(parts.get(0).detached());
    assertFalse(parts.get(0).inService(), "就地待命、回库的一段分行后不载客");
    assertEquals("", parts.get(0).destination());
    assertEquals(new DivisionForecast.Part(4, false, Optional.of("SURC:NTA"), "南渡"), parts.get(1));
  }

  /** 主目的地写开得更远的一段：分行后停站多的那段；一样多时取留下的一段（本交路的终点）；不载客的一段不算。 */
  @Test
  void thePrimaryPartIsTheOneThatGoesFurther() {
    DivisionForecast.Part keptToHhu =
        new DivisionForecast.Part(
            4, false, Optional.of("SURC:HHU"), "新笛矢·壑湖", 2, Optional.of("SURC:MT:TRUNK"), 5);
    DivisionForecast.Part detachedToNta =
        new DivisionForecast.Part(
            4, true, Optional.of("SURC:NTA"), "南渡", 3, Optional.of("SURC:MT:BRANCH"), 0);
    DivisionForecast.Part detachedEqual =
        new DivisionForecast.Part(
            4, true, Optional.of("SURC:NTA"), "南渡", 2, Optional.of("SURC:MT:BRANCH"), 0);
    DivisionForecast.Part keptEnds =
        new DivisionForecast.Part(4, false, Optional.empty(), "", 0, Optional.empty(), 0);

    assertEquals(Optional.of(detachedToNta), forecast(keptToHhu, detachedToNta).primary());
    assertEquals(
        Optional.of(keptToHhu), forecast(keptToHhu, detachedEqual).primary(), "一样多取本交路的终点");
    assertEquals(
        Optional.of(detachedToNta), forecast(detachedToNta, keptEnds).primary(), "不载客的一段不算");
    assertEquals(
        Optional.empty(),
        forecast(keptEnds, new DivisionForecast.Part(4, true, Optional.empty(), "")).primary());
  }

  private static DivisionForecast forecast(
      DivisionForecast.Part first, DivisionForecast.Part second) {
    return new DivisionForecast("SURC:MT:TRUNK", 5, "SURC:HHU", "新笛矢·壑湖", List.of(first, second));
  }

  /** 在运营终点拆：解下的一段接着载客时照样分行，留下的一段到此止；两段都不再载客时不分行。 */
  @Test
  void atTheTerminusOnlyAnInServiceDetachedPartMakesItADivision() {
    DivisionForecast.Destination none = DivisionForecast.Destination.NOT_IN_SERVICE;

    assertEquals(
        Optional.of(TPC), DivisionForecast.keptDestination(false, TPC, NTA), "不在终点：留下的一段到交路终点");
    assertEquals(Optional.of(none), DivisionForecast.keptDestination(true, TPC, NTA));
    assertEquals(Optional.empty(), DivisionForecast.keptDestination(true, TPC, none));
    assertEquals(
        Optional.of(TPC), DivisionForecast.keptDestination(false, TPC, none), "途中拆：解下的回库也照样分行");
  }
}
