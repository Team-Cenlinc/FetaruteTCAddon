package org.fetarute.fetaruteTCAddon.display.pids.yard;

import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P2;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW2;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.T1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.WORLD;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.cell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.display.Lateness;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.junit.jupiter.api.Test;

/** 站场图数据：几何按图快照缓存；占用按角色着色；车身按车厢所在方块定位。 */
class YardProviderTest {

  private static final YardScope ABC = YardScope.station(new PidsStationKey("SURC", "ABC"));
  private static final PidsLayout.Yard WIDGET = new PidsLayout.Yard(0, 32, 640, 232, 6, 10);

  private final AtomicReference<Optional<RailGraph>> graph =
      new AtomicReference<>(Optional.of(YardFixtures.graph()));
  private final List<OccupancyClaim> claims = new ArrayList<>();
  private final List<YardTrain> trains = new ArrayList<>();
  private final YardProvider provider =
      new YardProvider(
          world -> world.equals(WORLD) ? graph.get() : Optional.empty(),
          () -> List.copyOf(claims),
          world -> List.copyOf(trains),
          id -> Optional.empty());

  @Test
  void reportsWhyNothingCanBeDrawn() {
    graph.set(Optional.empty());
    assertEquals(
        Optional.of(YardPicture.Problem.GRAPH_NOT_READY),
        provider.picture(ABC, WORLD, WIDGET).problem());

    graph.set(Optional.of(YardFixtures.graph()));
    assertEquals(
        Optional.of(YardPicture.Problem.NOT_IN_GRAPH),
        provider
            .picture(YardScope.station(new PidsStationKey("SURC", "NOPE")), WORLD, WIDGET)
            .problem());
  }

  @Test
  void geometryIsReusedUntilTheGraphChanges() {
    YardGeometry first = provider.picture(ABC, WORLD, WIDGET).geometry().orElseThrow();
    YardGeometry again = provider.picture(ABC, WORLD, WIDGET).geometry().orElseThrow();
    graph.set(Optional.of(YardFixtures.graph()));
    YardGeometry rebuilt = provider.picture(ABC, WORLD, WIDGET).geometry().orElseThrow();

    assertSame(first, again, "同一份图快照只算一次");
    assertNotSame(first, rebuilt, "图换了重算");
  }

  @Test
  void unchangedOccupancyGivesAnEqualPicture() {
    claims.add(claim(EdgeId.undirected(T1, P1), ClaimRole.MOVEMENT_REQUIRED));

    assertEquals(provider.picture(ABC, WORLD, WIDGET), provider.picture(ABC, WORLD, WIDGET));
  }

  @Test
  void claimsColourEdgesByRoleAndHeldWins() {
    EdgeId route = EdgeId.undirected(T1, P1);
    EdgeId held = EdgeId.undirected(P1, SW2);
    EdgeId both = EdgeId.undirected(SW1, P2);
    claims.add(claim(route, ClaimRole.MOVEMENT_REQUIRED));
    claims.add(claim(held, ClaimRole.PROTECTIVE_RETAIN));
    claims.add(claim(both, ClaimRole.MOVEMENT_REQUIRED));
    claims.add(claim(both, ClaimRole.HOLD_ONLY));
    claims.add(claim(EdgeId.undirected(P2, SW2), ClaimRole.QUEUE_POSITION));
    claims.add(
        new OccupancyClaim(
            OccupancyResource.forNode(P1),
            "node",
            Optional.empty(),
            Instant.EPOCH,
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.MOVEMENT_REQUIRED));

    assertEquals(
        Set.of(
            new YardPicture.EdgeMark(route, YardPicture.Mark.ROUTE),
            new YardPicture.EdgeMark(held, YardPicture.Mark.HELD),
            new YardPicture.EdgeMark(both, YardPicture.Mark.HELD)),
        Set.copyOf(provider.picture(ABC, WORLD, WIDGET).marks()),
        "排队位与节点资源不画");
  }

  @Test
  void trainsArePlacedByTheirCarsAndColouredByLateness() {
    trains.add(
        new YardTrain(
            "train-a",
            "1234",
            Optional.of(0xF28C28),
            120,
            List.of(cell(-10, 0, false), cell(-12, 0, false), cell(-14, 0, false))));
    trains.add(
        new YardTrain("train-far", "9999", Optional.empty(), 0, List.of(cell(500, 0, false))));

    List<YardPicture.TrainMark> marks = provider.picture(ABC, WORLD, WIDGET).trains();

    assertEquals(1, marks.size(), "车厢都不在站场图上的车不画");
    YardPicture.TrainMark train = marks.get(0);
    assertEquals("1234", train.label());
    assertEquals(Lateness.LATE, train.lateness());
    assertEquals(1, train.runs().size());
    assertEquals(3, train.runs().get(0).size());
    assertTrue(train.anchor().x() > train.runs().get(0).get(2).x(), "第一节是车次窗的锚点");
  }

  @Test
  void carsOffTheDiagramSplitTheBody() {
    trains.add(
        new YardTrain(
            "train-b",
            "5678",
            Optional.empty(),
            0,
            List.of(cell(-60, 0, false), cell(-100, 0, false), cell(-63, 0, false))));

    YardPicture.TrainMark train = provider.picture(ABC, WORLD, WIDGET).trains().get(0);

    assertEquals(2, train.runs().size(), "中间那节不在图上，两边不连成一条车身");
  }

  /** 后台算几何：第一次显示“正在绘制”；图换了以后算好之前照旧用上一张图；用不着的旧图几何在下一次排算时清掉。 */
  @Test
  void geometryIsBuiltInTheBackgroundAndStaleGraphsAreDropped() {
    List<Runnable> queued = new ArrayList<>();
    YardProvider deferred =
        new YardProvider(
            world -> world.equals(WORLD) ? graph.get() : Optional.empty(),
            () -> List.copyOf(claims),
            world -> List.copyOf(trains),
            id -> Optional.empty(),
            queued::add,
            () -> 0L,
            0L);
    YardScope other = YardScope.station(new PidsStationKey("SURC", "NOPE"));

    assertEquals(
        Optional.of(YardPicture.Problem.BUILDING), deferred.picture(ABC, WORLD, WIDGET).problem());
    deferred.picture(ABC, WORLD, WIDGET);
    assertEquals(1, queued.size(), "同一张图只排一次");
    deferred.picture(other, WORLD, WIDGET);
    queued.forEach(Runnable::run);
    queued.clear();
    YardGeometry first = deferred.picture(ABC, WORLD, WIDGET).geometry().orElseThrow();

    graph.set(Optional.of(YardFixtures.graph()));
    assertSame(
        first, deferred.picture(ABC, WORLD, WIDGET).geometry().orElseThrow(), "新图算好之前照旧显示上一张");
    assertEquals(
        Optional.of(YardPicture.Problem.BUILDING),
        deferred.picture(other, WORLD, WIDGET).problem(),
        "另一片的旧图几何已被清掉，重新排算");
    queued.forEach(Runnable::run);
    assertNotSame(first, deferred.picture(ABC, WORLD, WIDGET).geometry().orElseThrow());
  }

  /** 共用时间内各屏共用一份占用与列车位置；车次窗信息只查落在图上的车。 */
  @Test
  void claimsAndTrainsAreSharedAndDetailsResolvedOnlyForTrainsOnTheDiagram() {
    long[] now = {0L};
    int[] claimReads = {0};
    int[] trainReads = {0};
    List<String> resolved = new ArrayList<>();
    YardProvider shared =
        new YardProvider(
            world -> world.equals(WORLD) ? graph.get() : Optional.empty(),
            () -> {
              claimReads[0]++;
              return List.copyOf(claims);
            },
            world -> {
              trainReads[0]++;
              return List.of(
                  new YardTrain(
                      "near",
                      List.of(cell(-10, 0, false)),
                      () -> {
                        resolved.add("near");
                        return new YardTrain.Details("1234", Optional.empty(), 0);
                      }),
                  new YardTrain(
                      "far",
                      List.of(cell(500, 0, false)),
                      () -> {
                        resolved.add("far");
                        return new YardTrain.Details("9999", Optional.empty(), 0);
                      }));
            },
            id -> Optional.empty(),
            Runnable::run,
            () -> now[0],
            500L);

    shared.picture(ABC, WORLD, WIDGET);
    shared.picture(ABC, WORLD, WIDGET);
    assertEquals(1, claimReads[0], "共用时间内只取一次占用");
    assertEquals(1, trainReads[0], "共用时间内只读一次列车");
    assertEquals(List.of("near"), resolved, "不在图上的车不查车次窗信息，查过的记住");

    now[0] = 500L;
    shared.picture(ABC, WORLD, WIDGET);
    assertEquals(2, claimReads[0]);
    assertEquals(2, trainReads[0]);
  }

  private static OccupancyClaim claim(EdgeId edge, ClaimRole role) {
    return new OccupancyClaim(
        OccupancyResource.forEdge(edge),
        "train",
        Optional.empty(),
        Instant.EPOCH,
        Duration.ZERO,
        Optional.empty(),
        role);
  }
}
