package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

class PhysicalInterlockingOccupancyIntegrationTest {

  @Test
  void graphDisjointCrossingEdgesBlockUntilPhysicalZoneIsReleased() {
    EdgeFixture fixture = crossingFixture(true);
    SimpleOccupancyManager manager = manager();
    Instant now = Instant.parse("2026-07-18T14:16:46Z");
    OccupancyRequest mt = request("SURC-MT-LO-3495", now, fixture.graph(), fixture.first());
    OccupancyRequest ds =
        request("SURC-DS-LW-5912", now.plusMillis(1), fixture.graph(), fixture.second());

    assertTrue(manager.acquire(mt).allowed());
    assertFalse(manager.acquire(ds).allowed());

    manager.releaseByTrain("SURC-MT-LO-3495");

    assertTrue(manager.acquire(ds).allowed());
  }

  @Test
  void incompleteCoverageSerializesEvenGeometricallyDisjointEdges() {
    EdgeFixture fixture = crossingFixture(false);
    SimpleOccupancyManager manager = manager();
    Instant now = Instant.parse("2026-07-18T14:16:46Z");

    assertTrue(manager.acquire(request("first", now, fixture.graph(), fixture.first())).allowed());
    assertFalse(
        manager
            .acquire(request("second", now.plusMillis(1), fixture.graph(), fixture.second()))
            .allowed());
  }

  @Test
  void switcherConnectedEdgesStayOnPrimaryModelWithoutDuplicatePhysicalZone() {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000059");
    NodeId west = NodeId.of("WEST");
    NodeId switcher = NodeId.of("SWITCHER:Towny:1:64:1");
    NodeId east = NodeId.of("EAST");
    RailFootprintCell sharedEndpoint = new RailFootprintCell(1, 64, 1);
    RailEdge incoming =
        edge(
            west.value(), switcher.value(), new RailEdgeFootprint(1, true, Set.of(sharedEndpoint)));
    RailEdge outgoing =
        edge(
            switcher.value(), east.value(), new RailEdgeFootprint(1, true, Set.of(sharedEndpoint)));
    Map<EdgeId, RailEdge> edges = Map.of(incoming.id(), incoming, outgoing.id(), outgoing);
    RailInterlockingState state =
        RailInterlockingState.from(
            worldId,
            edges.keySet(),
            Map.of(
                incoming.id(),
                new RailEdgeFootprint(1, true, Set.of(sharedEndpoint)),
                outgoing.id(),
                new RailEdgeFootprint(1, true, Set.of(sharedEndpoint))));
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                west,
                    new SignRailNode(
                        west, NodeType.WAYPOINT, new Vector(), Optional.empty(), Optional.empty()),
                switcher,
                    new SignRailNode(
                        switcher,
                        NodeType.SWITCHER,
                        new Vector(1, 64, 1),
                        Optional.empty(),
                        Optional.empty()),
                east,
                    new SignRailNode(
                        east,
                        NodeType.WAYPOINT,
                        new Vector(2, 64, 1),
                        Optional.empty(),
                        Optional.empty())),
            edges,
            Set.of(),
            state);

    List<OccupancyResource> incomingResources =
        OccupancyResourceResolver.resourcesForEdge(graph, incoming);
    List<OccupancyResource> outgoingResources =
        OccupancyResourceResolver.resourcesForEdge(graph, outgoing);
    OccupancyResource switcherResource =
        OccupancyResource.forConflict("switcher:" + switcher.value());

    assertTrue(state.exactZones().isEmpty());
    assertTrue(incomingResources.contains(switcherResource));
    assertTrue(outgoingResources.contains(switcherResource));
    assertFalse(
        incomingResources.stream().anyMatch(OccupancyResourceResolver::isInterlockingConflict));
    assertFalse(
        outgoingResources.stream().anyMatch(OccupancyResourceResolver::isInterlockingConflict));

    SimpleOccupancyManager manager = manager();
    Instant now = Instant.parse("2026-07-18T14:16:46Z");
    assertTrue(manager.acquire(request("incoming", now, graph, incoming)).allowed());
    assertFalse(manager.acquire(request("outgoing", now.plusMillis(1), graph, outgoing)).allowed());
  }

  @Test
  void trainLengthRearGuardRetainsCrossingUntilHeadHasPassedEnoughTrackDistance() {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000058");
    RailFootprintCell crossing = new RailFootprintCell(-520, 77, 1390);
    RailEdge ab = edge("A", "B", new RailEdgeFootprint(1, true, Set.of(crossing)));
    RailEdge bc =
        edge(
            "B",
            "C",
            new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(-519, 77, 1391))));
    RailEdge cd =
        edge(
            "C",
            "D",
            new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(-518, 77, 1392))));
    RailEdge crossingRoute =
        edge("DS-IN", "DS-OUT", new RailEdgeFootprint(1, true, Set.of(crossing)));
    Map<EdgeId, RailEdge> edges =
        Map.of(
            ab.id(), ab,
            bc.id(), bc,
            cd.id(), cd,
            crossingRoute.id(), crossingRoute);
    Map<EdgeId, RailEdgeFootprint> footprints =
        Map.of(
            ab.id(), new RailEdgeFootprint(1, true, Set.of(crossing)),
            bc.id(), new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(-519, 77, 1391))),
            cd.id(), new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(-518, 77, 1392))),
            crossingRoute.id(), new RailEdgeFootprint(1, true, Set.of(crossing)));
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(),
            edges,
            Set.of(),
            RailInterlockingState.from(worldId, edges.keySet(), footprints));
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 1, 0, 1, 0, 0L, 1, 5L, message -> {});
    List<NodeId> route = List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C"), NodeId.of("D"));
    OccupancyRequest atC =
        builder.buildRearGuardRequestFromNodes("MT", Optional.empty(), route, 2, Instant.now(), 0);
    OccupancyResource crossingZone =
        OccupancyResourceResolver.resourcesForEdge(graph, ab).stream()
            .filter(
                resource ->
                    resource.kind() == ResourceKind.CONFLICT
                        && resource.key().startsWith("interlocking:"))
            .findFirst()
            .orElseThrow();
    assertTrue(atC.resourceList().contains(crossingZone));

    SimpleOccupancyManager manager = manager();
    assertTrue(manager.acquire(atC).allowed());
    OccupancyRequest ds = request("DS", Instant.now(), graph, crossingRoute);
    assertFalse(manager.canEnter(ds).allowed());

    OccupancyRequest atD =
        builder.buildRearGuardRequestFromNodes("MT", Optional.empty(), route, 3, Instant.now(), 0);
    assertFalse(atD.resourceList().contains(crossingZone));
    assertTrue(manager.acquire(atD).allowed());
    Set<OccupancyResource> keep = Set.copyOf(atD.resourceList());
    manager.snapshotClaims().stream()
        .filter(claim -> claim.trainName().equals("MT"))
        .map(OccupancyClaim::resource)
        .filter(resource -> !keep.contains(resource))
        .toList()
        .forEach(resource -> manager.releaseResource(resource, Optional.of("MT")));

    assertTrue(manager.canEnter(ds).allowed());
  }

  /**
   * 实服 2026-09-27 OFL 车库口：DS 停在 MLU:1:004，车头刚越过节点牌子，节点模型认为它进了 MLU:1:004→岔口这条边； 边末端与 1
   * 号库线交叠（联锁区），车体离交叠格还有二十几格，回库的 MT 要从交叠格进库。
   *
   * <p>按当前边整体保持时 MT 进不了库——这就是顶牛的起点；停稳且足迹没压到交叠格时停车保持不再扣它；车体真压上了照旧挡住。当前节点与当前边本身不受影响。
   */
  @Test
  void stationaryHoldReleasesForwardCrossingItsBodyDoesNotCover() {
    JunctionFixture fixture = oflDepotJunction();
    OccupancyResource crossing = fixture.crossingZone();

    OccupancyRequest edgeWide = fixture.holdAtApproach(PositionZoneEvidence.EDGE_WIDE);
    OccupancyRequest bodyClear =
        fixture.holdAtApproach(PositionZoneEvidence.stationaryFootprint(Set.of()));
    OccupancyRequest bodyOnCrossing =
        fixture.holdAtApproach(PositionZoneEvidence.stationaryFootprint(Set.of(crossing)));

    assertTrue(edgeWide.resourceList().contains(crossing));
    assertFalse(fixture.depotRunInAdmittedWhileHolding(edgeWide));
    assertFalse(bodyClear.resourceList().contains(crossing));
    assertTrue(
        bodyClear.resourceList().contains(OccupancyResource.forEdge(fixture.approach().id())));
    assertTrue(bodyClear.resourceList().contains(OccupancyResource.forNode(fixture.current())));
    assertTrue(fixture.depotRunInAdmittedWhileHolding(bodyClear));
    assertFalse(fixture.depotRunInAdmittedWhileHolding(bodyOnCrossing));
  }

  /** 当前位置保护与停车保持用同一条规则。 */
  @Test
  void stationaryCurrentPositionReleasesForwardCrossingItsBodyDoesNotCover() {
    JunctionFixture fixture = oflDepotJunction();
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(fixture.graph(), 1, 0, 0, 0);

    OccupancyRequest bodyClear =
        builder.buildCurrentPositionRequest(
            "SURC-DS-LH-0368",
            Optional.empty(),
            fixture.current(),
            Optional.of(fixture.junction()),
            Instant.parse("2026-09-27T07:42:00Z"),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Optional.empty(),
            PositionZoneEvidence.stationaryFootprint(Set.of()));

    assertFalse(bodyClear.resourceList().contains(fixture.crossingZone()));
    assertTrue(
        bodyClear.resourceList().contains(OccupancyResource.forEdge(fixture.approach().id())));
    assertTrue(fixture.depotRunInAdmittedWhileHolding(bodyClear));
  }

  /** 停稳证据只收窄当前边：同一联锁区挂在尾部保护的边上时照旧保持。 */
  @Test
  void stationaryEvidenceDoesNotNarrowTheRearGuard() {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000061");
    RailFootprintCell crossing = new RailFootprintCell(-520, 77, 1390);
    RailEdgeFootprint abFootprint = new RailEdgeFootprint(1, true, Set.of(crossing));
    RailEdgeFootprint bcFootprint =
        new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(-519, 77, 1391)));
    RailEdgeFootprint cdFootprint =
        new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(-518, 77, 1392)));
    RailEdgeFootprint crossingRouteFootprint = new RailEdgeFootprint(1, true, Set.of(crossing));
    RailEdge ab = edge("A", "B", abFootprint);
    RailEdge bc = edge("B", "C", bcFootprint);
    RailEdge cd = edge("C", "D", cdFootprint);
    RailEdge crossingRoute = edge("DS-IN", "DS-OUT", crossingRouteFootprint);
    Map<EdgeId, RailEdge> edges =
        Map.of(ab.id(), ab, bc.id(), bc, cd.id(), cd, crossingRoute.id(), crossingRoute);
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(),
            edges,
            Set.of(),
            RailInterlockingState.from(
                worldId,
                edges.keySet(),
                Map.of(
                    ab.id(), abFootprint,
                    bc.id(), bcFootprint,
                    cd.id(), cdFootprint,
                    crossingRoute.id(), crossingRouteFootprint)));
    OccupancyResource crossingZone = interlockingZoneOf(graph, ab);
    List<NodeId> route = List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C"), NodeId.of("D"));

    OccupancyRequest holdAtC =
        new OccupancyRequestBuilder(graph, 1, 0, 1, 0, 0L, 1, 5L, message -> {})
            .buildHoldPositionRequest(
                "MT",
                Optional.empty(),
                NodeId.of("C"),
                Optional.of(NodeId.of("D")),
                route,
                2,
                Instant.parse("2026-09-27T07:42:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE,
                Optional.empty(),
                PositionZoneEvidence.stationaryFootprint(Set.of()));

    assertTrue(holdAtC.resourceList().contains(crossingZone));
  }

  /** 照实服 OFL 车库口：MLU:1:004→岔口 的边末端与 1 号库线交叠一格。 */
  private static JunctionFixture oflDepotJunction() {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000060");
    RailFootprintCell crossing = new RailFootprintCell(-516, 77, 2272);
    RailEdgeFootprint approachFootprint =
        new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(-516, 77, 2290), crossing));
    RailEdgeFootprint depotLeadFootprint =
        new RailEdgeFootprint(1, true, Set.of(crossing, new RailFootprintCell(-515, 77, 2274)));
    RailEdge approach =
        edge("SURC:OFL:MLU:1:004", "SWITCHER:Towny:-516:77:2268", approachFootprint);
    RailEdge depotLead =
        edge("SWITCHER:Towny:-515:77:2272", "SURC:D:OFL:1:001", depotLeadFootprint);
    Map<EdgeId, RailEdge> edges = Map.of(approach.id(), approach, depotLead.id(), depotLead);
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(),
            edges,
            Set.of(),
            RailInterlockingState.from(
                worldId,
                edges.keySet(),
                Map.of(approach.id(), approachFootprint, depotLead.id(), depotLeadFootprint)));
    return new JunctionFixture(graph, approach, depotLead, interlockingZoneOf(graph, approach));
  }

  private static OccupancyResource interlockingZoneOf(RailGraph graph, RailEdge edge) {
    return OccupancyResourceResolver.resourcesForEdge(graph, edge).stream()
        .filter(OccupancyResourceResolver::isInterlockingConflict)
        .findFirst()
        .orElseThrow();
  }

  /** OFL 车库口夹具：DS 停在 {@code approach} 起点，MT 走 {@code depotLead} 进库。 */
  private record JunctionFixture(
      RailGraph graph, RailEdge approach, RailEdge depotLead, OccupancyResource crossingZone) {

    NodeId current() {
      return approach.from();
    }

    NodeId junction() {
      return approach.to();
    }

    OccupancyRequest holdAtApproach(PositionZoneEvidence evidence) {
      return new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
          .buildHoldPositionRequest(
              "SURC-DS-LH-0368",
              Optional.empty(),
              current(),
              Optional.of(junction()),
              List.of(current(), junction()),
              0,
              Instant.parse("2026-09-27T07:42:00Z"),
              0,
              AuthorizationPurpose.RUNTIME_MOVE,
              Optional.empty(),
              evidence);
    }

    /** DS 以 {@code dsHold} 停着时，MT 的进库请求能否获准。 */
    boolean depotRunInAdmittedWhileHolding(OccupancyRequest dsHold) {
      SimpleOccupancyManager manager = manager();
      assertTrue(manager.acquire(dsHold).allowed());
      return manager
          .canEnter(
              request("SURC-MT-LO-6900", Instant.parse("2026-09-27T07:42:01Z"), graph, depotLead))
          .allowed();
    }
  }

  private static EdgeFixture crossingFixture(boolean complete) {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000057");
    RailFootprintCell crossing = new RailFootprintCell(-520, 77, 1390);
    RailEdgeFootprint firstFootprint = new RailEdgeFootprint(1, complete, Set.of(crossing));
    RailEdgeFootprint secondFootprint =
        new RailEdgeFootprint(
            1,
            complete,
            complete ? Set.of(crossing) : Set.of(new RailFootprintCell(-516, 77, 1387)));
    RailEdge first = edge("MT-IN", "MT-OUT", firstFootprint);
    RailEdge second = edge("DS-IN", "DS-OUT", secondFootprint);
    Map<EdgeId, RailEdge> edges = Map.of(first.id(), first, second.id(), second);
    RailInterlockingState state =
        RailInterlockingState.from(
            worldId,
            edges.keySet(),
            Map.of(first.id(), firstFootprint, second.id(), secondFootprint));
    RailGraph graph = new SimpleRailGraph(Map.of(), edges, Set.of(), state);
    return new EdgeFixture(graph, first, second);
  }

  private static RailEdge edge(String from, String to, RailEdgeFootprint footprint) {
    NodeId fromId = NodeId.of(from);
    NodeId toId = NodeId.of(to);
    return new RailEdge(
        EdgeId.undirected(fromId, toId), fromId, toId, 10, 0.0, true, Optional.empty());
  }

  private static OccupancyRequest request(
      String trainName, Instant now, RailGraph graph, RailEdge edge) {
    List<OccupancyResource> resources = OccupancyResourceResolver.resourcesForEdge(graph, edge);
    return new OccupancyRequest(trainName, Optional.empty(), now, resources, Map.of());
  }

  private static SimpleOccupancyManager manager() {
    return new SimpleOccupancyManager(
        (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
  }

  private record EdgeFixture(RailGraph graph, RailEdge first, RailEdge second) {}
}
