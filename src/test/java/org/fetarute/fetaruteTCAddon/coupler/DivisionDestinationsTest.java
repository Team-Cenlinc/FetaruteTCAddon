package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.junit.jupiter.api.Test;

/** 分行后一段还停几个车站：分行站之后、到这条交路的运营终点为止，通过不停的车站、路径点与运营终点之后的回库段都不算。 */
class DivisionDestinationsTest {

  private static final UUID ROUTE = UUID.randomUUID();

  private static RouteStop stop(int sequence, String node, RouteStopPassType type) {
    return new RouteStop(
        ROUTE,
        sequence,
        Optional.empty(),
        Optional.of(node),
        Optional.empty(),
        type,
        Optional.empty());
  }

  private static final List<RouteStop> STOPS =
      List.of(
          stop(0, "SURC:S:PPK:1", RouteStopPassType.STOP),
          stop(1, "SURC:PPK:RVS:1:001", RouteStopPassType.PASS),
          stop(2, "SURC:S:RVS:1", RouteStopPassType.PASS),
          stop(3, "SURC:S:PTK:1", RouteStopPassType.STOP),
          stop(4, "SURC:S:HHU:4", RouteStopPassType.TERMINATE),
          stop(5, "SURC:D:HHU:1", RouteStopPassType.STOP));

  @Test
  void countsStoppingStationsAfterTheDivisionUpToTheEndOfOperation() {
    assertEquals(2, DivisionDestinations.stopsAfter(STOPS, 0), "通过的车站与路径点不算，回库段不算");
    assertEquals(1, DivisionDestinations.stopsAfter(STOPS, 3));
    assertEquals(0, DivisionDestinations.stopsAfter(STOPS, 4), "在运营终点分行：这一段之后不再停站");
    assertEquals(0, DivisionDestinations.stopsAfter(List.of(), 0));
  }
}
