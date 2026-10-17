package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives.End;
import org.junit.jupiter.api.Test;

/** 分行预测的两段：车头在前，切车尾时解下的在后，切车头时解下的在前；不载客的一段没有终点。 */
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
}
