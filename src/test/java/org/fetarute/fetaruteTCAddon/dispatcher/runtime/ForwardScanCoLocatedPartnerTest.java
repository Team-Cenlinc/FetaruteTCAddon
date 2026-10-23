package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.graphWithConflictFreeLinearPath;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorityHandoffSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 前方列车扫描与占用判定同一份同股道豁免。
 *
 * <p>拆开以后两车共用站台与出站那几段资源，资源上排第一的往往是留在原地的另一列。背离对方开走的那一列扫前方时，不能把它当成前车降成 CAUTION——占用判定本来就不挡它。
 *
 * <p>独立成类：{@code RuntimeDispatchServiceTest} 已贴着 SpotBugs 单类 1000 方法的上限。
 */
class ForwardScanCoLocatedPartnerTest {

  private static final NodeId A = NodeId.of("A");
  private static final NodeId B = NodeId.of("B");
  private static final NodeId C = NodeId.of("C");
  private static final NodeId D = NodeId.of("D");
  private static final OccupancyResource AHEAD = OccupancyResource.forNode(B);

  /** 对照：一辆普通的车压在前方一格，照常降级。 */
  @Test
  void anOrdinaryTrainJustAheadStillDowngrades() {
    assertNotEquals(SignalAspect.PROCEED, aspectWithClaimAhead(false, false));
  }

  /** 前方那条占用属于豁免的同股道伙伴：不当前车，照常 PROCEED。 */
  @Test
  void theCoLocatedPartnerIsNotTakenForATrainAhead() {
    assertEquals(SignalAspect.PROCEED, aspectWithClaimAhead(true, false));
  }

  /** 伙伴排第一、同一资源上还压着第三列车：豁免的只是伙伴那一条，第三列车照样算前车。 */
  @Test
  void aThirdTrainBehindThePartnersClaimStillDowngrades() {
    assertNotEquals(SignalAspect.PROCEED, aspectWithClaimAhead(true, true));
  }

  /**
   * @param exempt 前方那条占用（伙伴的）是否豁免
   * @param thirdTrain 同一资源上是否还有第三列车的占用（排在伙伴后面、不豁免）
   */
  private static SignalAspect aspectWithClaimAhead(boolean exempt, boolean thirdTrain) {
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(A, D), Optional.empty());
    TagStore tags =
        new TagStore(
            "leaving",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 40.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithConflictFreeLinearPath(List.of(A, B, C, D), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(40.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyClaim partner =
        new OccupancyClaim(
            AHEAD, "partner", Optional.empty(), Instant.now(), Duration.ZERO, Optional.empty());
    OccupancyManager occupancyManager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) occupancyManager).holdsHardAuthority(any())).thenReturn(true);
    when(((AuthorityHandoffSupport) occupancyManager)
            .migrateAuthorityOwner(anyString(), anyString()))
        .thenReturn(true);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.getClaim(AHEAD)).thenReturn(Optional.of(partner));
    when(occupancyManager.getClaim(argThat(resource -> !AHEAD.equals(resource))))
        .thenReturn(Optional.empty());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            inv ->
                new OccupancyDecision(
                    true,
                    inv.<OccupancyRequest>getArgument(0).now(),
                    SignalAspect.PROCEED,
                    List.of()));
    when(occupancyManager.acquire(any()))
        .thenAnswer(
            inv ->
                new OccupancyDecision(
                    true,
                    inv.<OccupancyRequest>getArgument(0).now(),
                    SignalAspect.PROCEED,
                    List.of()));
    when(occupancyManager.exemptsCoLocated(any(), eq(AHEAD), eq(partner))).thenReturn(exempt);
    if (thirdTrain) {
      OccupancyClaim third =
          new OccupancyClaim(
              AHEAD, "third", Optional.empty(), Instant.now(), Duration.ZERO, Optional.empty());
      when(occupancyManager.claimsOn(AHEAD)).thenReturn(List.of(partner, third));
    }

    RouteProgressRegistry registry = new RouteProgressRegistry();
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            registry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    service.handleSignalTick(new FakeTrain(worldId, tags.properties(), false, 0.0), true);
    return registry.get("leaving").orElseThrow().lastSignal();
  }
}
