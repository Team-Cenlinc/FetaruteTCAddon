package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GTFS-Realtime 数据源：出库途中的车")
class GtfsRealtimeSnapshotFactoryTest {

  private static final UUID CREATE = new UUID(0L, 1L);
  private static final UUID OPERATION = new UUID(0L, 2L);
  private static final UUID DEADHEAD = new UUID(0L, 3L);
  private static final UUID THROAT_STOP = new UUID(0L, 4L);
  private static final UUID UNKNOWN = new UUID(0L, 5L);

  /** 出库交路：车库 → 区间点 → 第一个载客站（下标 2）→ 终点。 */
  private static final List<RouteStop> CREATE_STOPS =
      List.of(
          stop(CREATE, 0, "SURC:D:LWN:1", RouteStopPassType.PASS),
          stop(CREATE, 1, "SURC:LWN:NTA:1:001", RouteStopPassType.PASS),
          stop(CREATE, 2, "SURC:S:NTA:1", RouteStopPassType.STOP),
          stop(CREATE, 3, "SURC:S:HHU:1", RouteStopPassType.TERMINATE));

  /** 运营交路：首站就是载客站。 */
  private static final List<RouteStop> OPERATION_STOPS =
      List.of(
          stop(OPERATION, 0, "SURC:S:NTA:1", RouteStopPassType.STOP),
          stop(OPERATION, 1, "SURC:S:TPC:1", RouteStopPassType.PASS),
          stop(OPERATION, 2, "SURC:S:HHU:1", RouteStopPassType.TERMINATE));

  /** 纯走行：一个载客站都没有。 */
  private static final List<RouteStop> DEADHEAD_STOPS =
      List.of(
          stop(DEADHEAD, 0, "SURC:D:LWN:1", RouteStopPassType.PASS),
          stop(DEADHEAD, 1, "SURC:S:NTA:1", RouteStopPassType.PASS));

  /** 在站咽喉写了 STOP：咽喉不是车站本体，不算开始载客。 */
  private static final List<RouteStop> THROAT_STOPS =
      List.of(
          stop(THROAT_STOP, 0, "SURC:D:LWN:1", RouteStopPassType.PASS),
          stop(THROAT_STOP, 1, "SURC:S:NTA:1:001", RouteStopPassType.STOP),
          stop(THROAT_STOP, 2, "SURC:S:NTA:1", RouteStopPassType.STOP));

  @Test
  @DisplayName("到第一个载客站之前排除；到站之后、运营交路、找不到交路的车都不排除；咽喉不算载客站")
  void excludesTrainsBeforeTheirFirstPassengerStop() {
    Map<String, TrainRuntimeSnapshot> trains =
        Map.of(
            "leaving-depot", train(CREATE, 0),
            "approaching", train(CREATE, 1),
            "at-first-station", train(CREATE, 2),
            "in-service", train(OPERATION, 0),
            "deadhead", train(DEADHEAD, 1),
            "at-throat", train(THROAT_STOP, 1),
            "past-throat", train(THROAT_STOP, 2),
            "unknown-route", train(UNKNOWN, 0));
    Map<UUID, List<RouteStop>> routes =
        Map.of(
            CREATE,
            CREATE_STOPS,
            OPERATION,
            OPERATION_STOPS,
            DEADHEAD,
            DEADHEAD_STOPS,
            THROAT_STOP,
            THROAT_STOPS);

    Set<String> hidden =
        GtfsRealtimeSnapshotFactory.beforeService(
            trains, routeId -> Optional.ofNullable(routes.get(routeId)));

    assertEquals(Set.of("leaving-depot", "approaching", "deadhead", "at-throat"), hidden);
  }

  private static RouteStop stop(
      UUID routeId, int sequence, String nodeId, RouteStopPassType passType) {
    return new RouteStop(
        routeId,
        sequence,
        Optional.empty(),
        Optional.of(nodeId),
        Optional.empty(),
        passType,
        Optional.empty());
  }

  private static TrainRuntimeSnapshot train(UUID routeUuid, int routeIndex) {
    return new TrainRuntimeSnapshot(
        1L,
        Instant.parse("2026-10-27T04:00:00Z"),
        UUID.randomUUID(),
        routeUuid,
        RouteId.of("SURC:WS:R"),
        routeIndex,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        OptionalDouble.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        OptionalDouble.empty(),
        TrainRuntimeSnapshot.HoldTimeline.EMPTY,
        Optional.empty(),
        Optional.empty(),
        TrainRuntimeSnapshot.Motion.NONE,
        Optional.empty(),
        Optional.empty());
  }
}
