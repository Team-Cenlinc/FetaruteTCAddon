package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 停站任务要能认出"这次停站已被别的流程接手"。
 *
 * <p>2026-09-27 实服 NTA：终点待命车 9675 复用为 1673、换上新交路自行开走后，NTA:2 的停站任务仍每秒替它判"从本站发车"，一直刷新它在 NTA
 * 单线区段上的排队位，对向进站的 4936 被挡半小时以上。
 */
class AutoStationStopSessionTest {

  private static final UUID ARRIVED_ROUTE = UUID.fromString("7adaffd5-d49b-4b5d-8427-c3479c02efe1");
  private static final UUID NEXT_ROUTE = UUID.fromString("49888919-0000-4000-8000-000000000000");

  @Test
  void layoverReuseRenameEndsTheStopSession() {
    TrainProperties current = train("SURC-WS-LC-1673", NEXT_ROUTE);

    assertTrue(
        AutoStationSignAction.stopSessionSuperseded("SURC-WS-LN-9675", ARRIVED_ROUTE, current));
  }

  @Test
  void renameAloneEndsTheStopSession() {
    TrainProperties current = train("SURC-WS-LC-1673", ARRIVED_ROUTE);

    assertTrue(
        AutoStationSignAction.stopSessionSuperseded("SURC-WS-LN-9675", ARRIVED_ROUTE, current));
  }

  @Test
  void reassignmentToAnotherRouteEndsTheStopSessionEvenWithTheSameName() {
    TrainProperties current = train("SURC-WS-LN-9675", NEXT_ROUTE);

    assertTrue(
        AutoStationSignAction.stopSessionSuperseded("SURC-WS-LN-9675", ARRIVED_ROUTE, current));
  }

  @Test
  void theTrainStillDwellingOnItsOwnStopKeepsTheSession() {
    TrainProperties current = train("SURC-WS-LN-9675", ARRIVED_ROUTE);

    assertFalse(
        AutoStationSignAction.stopSessionSuperseded("SURC-WS-LN-9675", ARRIVED_ROUTE, current));
  }

  /** 读不到当前名字或交路不算接手：没有确凿证据时停站任务照旧运行。 */
  @Test
  void missingEvidenceKeepsTheSession() {
    TrainProperties unnamedNoRoute = mock(TrainProperties.class);
    when(unnamedNoRoute.getTrainName()).thenReturn("");
    when(unnamedNoRoute.hasTags()).thenReturn(false);

    assertFalse(
        AutoStationSignAction.stopSessionSuperseded(
            "SURC-WS-LN-9675", ARRIVED_ROUTE, unnamedNoRoute));
    assertFalse(
        AutoStationSignAction.stopSessionSuperseded("SURC-WS-LN-9675", ARRIVED_ROUTE, null));
    assertFalse(
        AutoStationSignAction.stopSessionSuperseded(
            "unknown", ARRIVED_ROUTE, train("SURC-WS-LC-1673", ARRIVED_ROUTE)),
        "停站时就没取到名字，名字不同不说明任何事");
  }

  private static TrainProperties train(String name, UUID routeId) {
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(name);
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags())
        .thenReturn(List.of("FTA_OPERATOR_CODE=op", "FTA_ROUTE_ID=" + routeId));
    return properties;
  }
}
