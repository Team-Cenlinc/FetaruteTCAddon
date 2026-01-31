package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

class RouteIndexResolverTest {

  @Test
  void resolveCurrentIndexFallsBackToStationKeyWhenTrackDiffers() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(NodeId.of("A"), NodeId.of("SURN:S:PTK:1"), NodeId.of("B")),
            java.util.Optional.empty());

    int idx =
        RouteIndexResolver.resolveCurrentIndex(route, OptionalInt.of(0), NodeId.of("SURN:S:PTK:2"));

    assertEquals(1, idx);
  }

  @Test
  void resolveCurrentIndex_throatDoesNotMatchStationBody() {
    // Route 定义中只有站点本体，没有咽喉
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(
                NodeId.of("SURC:S:CSB:1"), // index 0
                NodeId.of("SURC:S:JBS:2"), // index 1 - 站点本体
                NodeId.of("SURC:S:SPB:1")), // index 2
            java.util.Optional.empty());

    // 列车经过站咽喉 SURC:S:JBS:2:001（5 段格式），不应该匹配到站点本体（4 段）
    int idx =
        RouteIndexResolver.resolveCurrentIndex(
            route, OptionalInt.of(0), NodeId.of("SURC:S:JBS:2:001"));

    // 应该返回 -1，因为咽喉不在 route 定义中，且不应容错匹配到站点本体
    assertEquals(-1, idx);
  }

  @Test
  void resolveCurrentIndex_stationBodyMatchesDifferentTrack() {
    // Route 定义中有站点本体
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(
                NodeId.of("SURC:S:CSB:1"), // index 0
                NodeId.of("SURC:S:JBS:2"), // index 1
                NodeId.of("SURC:S:SPB:1")), // index 2
            java.util.Optional.empty());

    // 列车到达 SURC:S:JBS:3（同站不同站台，都是 4 段），应该容错匹配
    int idx =
        RouteIndexResolver.resolveCurrentIndex(route, OptionalInt.of(0), NodeId.of("SURC:S:JBS:3"));

    // 应该返回 1（匹配到 SURC:S:JBS:2）
    assertEquals(1, idx);
  }

  @Test
  void resolveCurrentIndex_throatMatchesThroatDifferentTrack() {
    // Route 定义中有站咽喉
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(
                NodeId.of("SURC:S:CSB:1"), // index 0
                NodeId.of("SURC:S:JBS:2:001"), // index 1 - 站咽喉
                NodeId.of("SURC:S:JBS:2"), // index 2 - 站点本体
                NodeId.of("SURC:S:SPB:1")), // index 3
            java.util.Optional.empty());

    // 列车经过同站不同股道的咽喉 SURC:S:JBS:3:001，应该容错匹配到咽喉（index 1）
    int idx =
        RouteIndexResolver.resolveCurrentIndex(
            route, OptionalInt.of(0), NodeId.of("SURC:S:JBS:3:001"));

    // 应该返回 1（匹配到同站的咽喉 SURC:S:JBS:2:001）
    assertEquals(1, idx);
  }

  @Test
  void resolveCurrentIndex_exactMatchTakesPrecedence() {
    // Route 定义中同时有咽喉和站点本体
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(
                NodeId.of("SURC:S:JBS:2:001"), // index 0 - 咽喉
                NodeId.of("SURC:S:JBS:2")), // index 1 - 站点本体
            java.util.Optional.empty());

    // 精确匹配咽喉
    int idx1 =
        RouteIndexResolver.resolveCurrentIndex(
            route, OptionalInt.empty(), NodeId.of("SURC:S:JBS:2:001"));
    assertEquals(0, idx1);

    // 精确匹配站点本体
    int idx2 =
        RouteIndexResolver.resolveCurrentIndex(
            route, OptionalInt.empty(), NodeId.of("SURC:S:JBS:2"));
    assertEquals(1, idx2);
  }
}
