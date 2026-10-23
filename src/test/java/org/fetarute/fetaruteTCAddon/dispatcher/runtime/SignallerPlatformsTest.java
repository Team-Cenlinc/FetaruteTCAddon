package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerPlatformDesk;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;

/** 信号员改停股道：选择是计划站台的一个来源（联挂搭档之后、时刻表与预先指定之前），只对下一个还没开始停的 DYNAMIC 停站， 改选撤掉选台缓存与有效节点覆盖，锁闭了原股道进路时拒绝。 */
class SignallerPlatformsTest {

  private static final String TRAIN = "SURC-WS-A-0001";
  private static final UUID ROUTE_UUID = UUID.randomUUID();
  private static final NodeId PPK1 = NodeId.of("SURC:S:PPK:1");
  private static final NodeId PPK2 = NodeId.of("SURC:S:PPK:2");
  private static final NodeId PPK3 = NodeId.of("SURC:S:PPK:3");
  private static final NodeId BBB = NodeId.of("SURC:S:BBB:1");
  private static final NodeId S1 = NodeId.of("SWITCHER:Towny:10:64:0");
  private static final NodeId S2 = NodeId.of("SWITCHER:Towny:20:64:0");
  private static final RouteDefinition ROUTE =
      new RouteDefinition(
          RouteId.of("SURC:WS:WS-N"),
          List.of(
              NodeId.of("SURC:S:AAA:1"),
              NodeId.of("SURC:S:BBB:1"),
              NodeId.of("DYNAMIC:SURC:S:PPK:[1:3]"),
              NodeId.of("SURC:S:CCC:1")),
          Optional.empty());

  /** 夹具：交路第 2 站是 DYNAMIC（PPK 1–3 道），图上有这三条股道。 */
  private static final class Fixture {
    final RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
    final RouteProgressRegistry progress = new RouteProgressRegistry();
    final SimpleOccupancyManager occupancy =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    final List<String> logs = new ArrayList<>();
    final DynamicPlatformAllocator allocator;
    final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Object>> overrides =
        new ConcurrentHashMap<>();
    final List<String> woken = new ArrayList<>();
    final SignallerPlatforms platforms;
    final StationStopCoordinator stops;
    final List<PlatformResolution> resolutions = new ArrayList<>();

    Fixture() {
      when(cache.findById(ROUTE_UUID)).thenReturn(Optional.of(ROUTE));
      when(cache.findUuid(ROUTE.id())).thenReturn(Optional.of(ROUTE_UUID));
      when(cache.findStop(ROUTE.id(), 2))
          .thenReturn(
              Optional.of(
                  RuntimeDispatchTestFixtures.dynamicStop(
                      2, ROUTE.waypoints().get(2), "DYNAMIC:SURC:S:PPK:[1:3]")));
      RailGraphService graphs = mock(RailGraphService.class);
      when(graphs.snapshotAll())
          .thenReturn(
              Map.of(
                  UUID.randomUUID(),
                  new RailGraphService.RailGraphSnapshot(throatGraph(), Instant.now())));
      allocator = new DynamicPlatformAllocator(cache, occupancy, logs::add);
      platforms =
          new SignallerPlatforms(
              logs::add, cache, progress, graphs, occupancy, allocator, overrides, woken::add);
      stops = TestServices.minimal(logs, cache).stationStops();
      stops.attachPlatforms(allocator);
      allocator.setPreference(stops::plannedPlatform);
      stops.attachSignallerPlatforms(platforms);
      stops.setObserver(
          new StationStopObserver() {
            @Override
            public void onStationArrival(StationStopEvent event) {}

            @Override
            public void onStationDeparture(StationStopEvent event) {}

            @Override
            public void onPlatformResolved(PlatformResolution resolution) {
              resolutions.add(resolution);
            }
          });
      at(0);
    }

    void at(int index) {
      progress.advance(
          TRAIN,
          ROUTE_UUID,
          ROUTE,
          index,
          new RuntimeDispatchTestFixtures.TagStore(TRAIN).properties(),
          Instant.now());
    }

    /** 模拟这一站已经选了台：选台器缓存 + 运行时有效节点覆盖。 */
    void materialize(NodeId node) {
      allocator.recordMaterialization(TRAIN, ROUTE, 2, node);
      overrides
          .computeIfAbsent(
              org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer
                  .normalizeKey(TRAIN),
              key -> new ConcurrentHashMap<>())
          .put(2, node);
    }

    /** 这列车以给定意图持有这些资源。 */
    void holds(ResourceIntent intent, OccupancyResource... resources) {
      Map<OccupancyResource, ResourceIntent> intents = new java.util.LinkedHashMap<>();
      for (OccupancyResource resource : resources) {
        intents.put(resource, intent);
      }
      assertTrue(
          occupancy
              .acquire(
                  new OccupancyRequest(
                      TRAIN,
                      Optional.empty(),
                      Instant.now(),
                      List.of(resources),
                      Map.of(),
                      Map.of(),
                      0,
                      AuthorizationPurpose.RUNTIME_MOVE,
                      Map.of(),
                      intents))
              .allowed());
    }

    Optional<NodeId> allocation() {
      return allocator.getAllocation(TRAIN, ROUTE.id(), 2);
    }

    void timetablePlans(NodeId node) {
      ScheduledDeparturePlan plan = mock(ScheduledDeparturePlan.class);
      when(plan.plannedPlatformOf(any(), any(), anyInt())).thenReturn(Optional.of(node.value()));
      stops.setPlan(plan);
    }
  }

  /** 站前咽喉：{@code BBB:1 — S1 — PPK:1}，{@code S1 — S2 — PPK:2 / PPK:3}。 */
  private static RailGraph throatGraph() {
    Map<NodeId, RailNode> nodes = new java.util.LinkedHashMap<>();
    nodes.put(BBB, new RailNodeTest(BBB));
    nodes.put(S1, new RailNodeTest(S1, NodeType.SWITCHER, Optional.empty()));
    nodes.put(S2, new RailNodeTest(S2, NodeType.SWITCHER, Optional.empty()));
    nodes.put(PPK1, new RailNodeTest(PPK1));
    nodes.put(PPK2, new RailNodeTest(PPK2));
    nodes.put(PPK3, new RailNodeTest(PPK3));
    List<RailEdge> edges =
        List.of(edge(BBB, S1), edge(S1, PPK1), edge(S1, S2), edge(S2, PPK2), edge(S2, PPK3));
    return new RailGraph() {
      @Override
      public java.util.Collection<RailNode> nodes() {
        return nodes.values();
      }

      @Override
      public java.util.Collection<RailEdge> edges() {
        return edges;
      }

      @Override
      public Optional<RailNode> findNode(NodeId id) {
        return Optional.ofNullable(nodes.get(id));
      }

      @Override
      public java.util.Set<RailEdge> edgesFrom(NodeId id) {
        return edges.stream()
            .filter(edge -> edge.from().equals(id) || edge.to().equals(id))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
      }

      @Override
      public boolean isBlocked(EdgeId id) {
        return false;
      }
    };
  }

  private static RailEdge edge(NodeId from, NodeId to) {
    return new RailEdge(EdgeId.undirected(from, to), from, to, 10, -1.0, true, Optional.empty());
  }

  @Test
  void optionsOfferTheNextDynamicStopWithCandidatesInTrackOrder() {
    Fixture fixture = new Fixture();
    fixture.timetablePlans(PPK3);

    SignallerPlatformDesk.Options options = fixture.platforms.options(TRAIN).orElseThrow();

    assertEquals(2, options.stopIndex());
    assertEquals(List.of(PPK1, PPK2, PPK3), options.candidates());
    assertEquals(Optional.of(PPK3), options.current(), "还没选台：现在要去的是站牌显示的计划站台");
    assertEquals(Optional.empty(), options.chosen());
  }

  @Test
  void signallerChoiceRanksAfterCouplingAndBeforeTimetableAndPin() {
    Fixture fixture = new Fixture();
    fixture.timetablePlans(PPK3);
    fixture.stops.setPinnedPlatforms((train, routeId, index) -> Optional.of(PPK1.value()));

    assertTrue(fixture.platforms.choose(TRAIN, PPK2));

    assertEquals(Optional.of(PPK2), fixture.stops.plannedPlatform(TRAIN, ROUTE, 2), "信号员先于时刻表与叫车");
    assertEquals(Optional.of(PPK2), fixture.stops.displayPlatform(TRAIN, ROUTE, 2), "站牌显示车会去的那条");
    assertEquals(Optional.of(PPK2), fixture.platforms.options(TRAIN).orElseThrow().chosen());
    assertEquals(Optional.of(PPK2), fixture.platforms.options(TRAIN).orElseThrow().current());
    assertEquals(List.of(TRAIN), fixture.woken, "选完唤醒这列车重评估");

    fixture.stops.setCouplingPartners(
        new DynamicPlatformAllocator.CouplingPartners() {
          @Override
          public Optional<NodeId> platformOf(String trainName, UUID routeId, int stopIndex) {
            return Optional.of(PPK1);
          }

          @Override
          public boolean partner(String trainName, String claimant) {
            return false;
          }
        });
    assertEquals(Optional.of(PPK1), fixture.stops.plannedPlatform(TRAIN, ROUTE, 2), "联挂搭档最先");
  }

  @Test
  void noChoiceKeepsThePlannedPlatformUntouched() {
    Fixture fixture = new Fixture();
    fixture.timetablePlans(PPK3);

    assertEquals(Optional.of(PPK3), fixture.stops.plannedPlatform(TRAIN, ROUTE, 2));
    assertEquals(Optional.of(PPK3), fixture.stops.plannedPlatform("other", ROUTE, 2));
  }

  @Test
  void nonCandidateIsRejected() {
    Fixture fixture = new Fixture();

    assertFalse(fixture.platforms.choose(TRAIN, NodeId.of("SURC:S:PPK:4")));
    assertFalse(fixture.platforms.choose(TRAIN, NodeId.of("SURC:S:BBB:1")));
    assertFalse(fixture.platforms.choose(TRAIN, null));
    assertFalse(fixture.platforms.choose("unknown-train", PPK2));
    assertEquals(Optional.empty(), fixture.stops.plannedPlatform(TRAIN, ROUTE, 2));
    assertTrue(fixture.woken.isEmpty());
  }

  @Test
  void aStopAlreadyStartedIsNotOffered() {
    Fixture fixture = new Fixture();
    fixture.at(2);

    assertTrue(fixture.platforms.options(TRAIN).isEmpty(), "正在这一站停着：不能再改");
    assertFalse(fixture.platforms.choose(TRAIN, PPK2));
  }

  @Test
  void choosingAnotherPlatformRevokesTheCachedAllocationAndOverride() {
    Fixture fixture = new Fixture();
    fixture.materialize(PPK3);
    assertEquals(Optional.of(PPK3), fixture.allocation());
    assertEquals(Optional.of(PPK3), fixture.platforms.options(TRAIN).orElseThrow().current());

    assertTrue(fixture.platforms.choose(TRAIN, PPK2));

    assertEquals(Optional.empty(), fixture.allocation(), "撤掉选台器里这一站的缓存");
    assertTrue(fixture.overrides.isEmpty(), "撤掉运行时的有效节点覆盖，下一拍重新选台");
    assertEquals(List.of(TRAIN), fixture.woken);
  }

  @Test
  void reselectionAnnouncesAPlatformChangeFromTheDisplacedPlatform() {
    Fixture fixture = new Fixture();
    fixture.materialize(PPK3);
    assertTrue(fixture.platforms.choose(TRAIN, PPK2));

    // 下一拍选台按新偏好落定：上一次的覆盖已被撤掉，previous 为空
    fixture.stops.platformRecorded(TRAIN, ROUTE, 2, Optional.empty(), PPK2);

    assertEquals(1, fixture.resolutions.size());
    PlatformResolution resolution = fixture.resolutions.get(0);
    assertEquals(Optional.of(PPK3), resolution.previousNode());
    assertEquals(PlatformResolution.Reason.CHANGED, resolution.reason(), "站台屏照现有事件链播报站台变更");

    fixture.stops.platformRecorded(TRAIN, ROUTE, 2, Optional.empty(), PPK2);
    assertEquals(
        PlatformResolution.Reason.ASSIGNED, fixture.resolutions.get(1).reason(), "原站台只用一次");
  }

  @Test
  void choosingIsRefusedOnceTheRouteIntoTheOldPlatformIsLocked() {
    Fixture fixture = new Fixture();
    fixture.materialize(PPK3);
    assertTrue(
        fixture
            .occupancy
            .acquire(
                new OccupancyRequest(
                    TRAIN,
                    Optional.empty(),
                    Instant.now(),
                    List.of(OccupancyResource.forNode(PPK3)),
                    Map.of()))
            .allowed());

    assertFalse(fixture.platforms.choose(TRAIN, PPK2), "进了咽喉再换股道不安全");
    assertEquals(Optional.of(PPK3), fixture.allocation());
    assertEquals(Optional.empty(), fixture.platforms.options(TRAIN).orElseThrow().chosen());
    assertTrue(fixture.platforms.choose(TRAIN, PPK3), "选的就是已锁闭的那条：照收");
  }

  /** 授权只到共用进路（还没到分岔道岔）：改选照收，下一拍按新股道申请进路、改写 destination，车在分岔前停得住。 */
  @Test
  void reselectionIsAcceptedWhileTheAuthorityStopsShortOfTheFork() {
    Fixture fixture = new Fixture();
    fixture.materialize(PPK3);
    fixture.holds(
        ResourceIntent.MOVEMENT_REQUIRED,
        OccupancyResource.forEdge(EdgeId.undirected(BBB, S1)),
        OccupancyResource.forConflict("switcher:" + S1.value()),
        OccupancyResource.forEdge(EdgeId.undirected(S1, S2)));

    assertTrue(fixture.platforms.choose(TRAIN, PPK2), "PPK:2 与 PPK:3 在 S2 才分岔，授权还没越过 S2");
    assertEquals(Optional.empty(), fixture.allocation());
  }

  /** 授权越过了分岔道岔、还没到股道相邻区间：照样拒绝（只看股道节点与相邻区间会漏掉这种情形）。 */
  @Test
  void reselectionIsRefusedOnceTheAuthorityCoversTheFork() {
    Fixture fixture = new Fixture();
    fixture.materialize(PPK3);
    fixture.holds(
        ResourceIntent.MOVEMENT_REQUIRED,
        OccupancyResource.forEdge(EdgeId.undirected(BBB, S1)),
        OccupancyResource.forConflict("switcher:" + S1.value()),
        OccupancyResource.forEdge(EdgeId.undirected(S1, S2)),
        OccupancyResource.forConflict("switcher:" + S2.value()));

    assertFalse(fixture.platforms.choose(TRAIN, PPK2), "S2 的道岔已锁闭向 PPK:3");
    assertFalse(fixture.platforms.choose(TRAIN, PPK1), "PPK:1 在 S1 就分岔，S1→S2 已锁闭");
    assertEquals(Optional.of(PPK3), fixture.allocation());
  }

  /** 改去在更早道岔分岔的股道：共用进路更短，越过那个道岔的授权就算锁闭。 */
  @Test
  void anEarlierForkIsCheckedForTheFartherCandidate() {
    Fixture fixture = new Fixture();
    fixture.materialize(PPK3);
    fixture.holds(
        ResourceIntent.MOVEMENT_REQUIRED, OccupancyResource.forEdge(EdgeId.undirected(S1, S2)));

    assertTrue(fixture.platforms.choose(TRAIN, PPK2), "PPK:2 在 S2 分岔，S1→S2 是共用进路");
    fixture.materialize(PPK3);
    assertFalse(fixture.platforms.choose(TRAIN, PPK1), "PPK:1 在 S1 分岔，S1→S2 已在原股道一侧");
  }

  /** 车身停在分岔之后（停车保持、车身占用都算），或最后经过的节点已在原股道一侧：拒绝。 */
  @Test
  void aTrainAlreadyPastTheForkCannotBeRedirected() {
    Fixture holding = new Fixture();
    holding.materialize(PPK3);
    holding.holds(ResourceIntent.HOLD_ONLY, OccupancyResource.forEdge(EdgeId.undirected(S2, PPK3)));
    assertFalse(holding.platforms.choose(TRAIN, PPK2));

    Fixture passed = new Fixture();
    passed.materialize(PPK3);
    passed.progress.updateLastPassedGraphNode(TRAIN, S2, Instant.now());
    assertFalse(passed.platforms.choose(TRAIN, PPK2), "最后经过的是分岔道岔");
  }

  @Test
  void forgetDropsChoicesRevokesTheirAllocationAndWakes() {
    Fixture fixture = new Fixture();
    fixture.timetablePlans(PPK3);
    assertTrue(fixture.platforms.choose(TRAIN, PPK2));
    fixture.materialize(PPK2);
    fixture.woken.clear();

    fixture.platforms.forget(TRAIN);

    assertEquals(Optional.of(PPK3), fixture.stops.plannedPlatform(TRAIN, ROUTE, 2), "退回时刻表");
    assertEquals(Optional.empty(), fixture.allocation(), "没锁闭：撤掉信号员选的那条的选台缓存");
    assertTrue(fixture.overrides.isEmpty());
    assertEquals(List.of(TRAIN), fixture.woken);

    fixture.woken.clear();
    fixture.platforms.forget(TRAIN);
    assertTrue(fixture.woken.isEmpty(), "没有选择时什么都不做");
  }

  @Test
  void choicesFollowARenameAndAreDiscardedWithTheTrain() {
    Fixture fixture = new Fixture();
    assertTrue(fixture.platforms.choose(TRAIN, PPK2));

    fixture.platforms.renamed(TRAIN, "SURC-WS-A-0002");

    assertEquals(Optional.empty(), fixture.stops.plannedPlatform(TRAIN, ROUTE, 2));
    assertEquals(Optional.of(PPK2), fixture.stops.plannedPlatform("SURC-WS-A-0002", ROUTE, 2));

    fixture.platforms.discard("surc-ws-a-0002");
    assertEquals(Optional.empty(), fixture.stops.plannedPlatform("SURC-WS-A-0002", ROUTE, 2));
  }

  @Test
  void detachedDeskOffersNothing() {
    SignallerPlatforms detached = SignallerPlatforms.detached();

    assertTrue(detached.options(TRAIN).isEmpty());
    assertFalse(detached.choose(TRAIN, PPK2));
    detached.forget(TRAIN);
  }
}
