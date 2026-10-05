package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/** 走行估算按车上的车种：运行中的车读快照里的车种标签；未发车的票在 route 绑了编组方案时按方案预计的车型。 */
class EtaTrainMotionTest {

  private static final ConfigManager.ConfigView CONFIG =
      ConfigManager.parse(new YamlConfiguration(), Logger.getLogger("eta-motion-test"));

  private final UUID routeUuid = UUID.randomUUID();
  private final UUID worldId = UUID.randomUUID();
  private final NodeId start = NodeId.of("SURN:S:AAA:1");
  private final NodeId next = NodeId.of("SURN:S:BBB:1");
  private final RouteDefinition route =
      new RouteDefinition(RouteId.of("SURN:L1:R1"), List.of(start, next), Optional.empty());

  @Test
  void runningTrainUsesItsOwnTrainType() {
    int metro = travelSec(TrainRuntimeSnapshot.Motion.NONE);
    int dmu =
        travelSec(
            new TrainRuntimeSnapshot.Motion(
                Optional.of(TrainType.DMU), OptionalDouble.empty(), OptionalDouble.empty()));
    int sluggish =
        travelSec(
            new TrainRuntimeSnapshot.Motion(
                Optional.of(TrainType.DMU), OptionalDouble.of(0.2), OptionalDouble.empty()));

    assertTrue(dmu > metro, () -> "内燃动车组起步慢，应比默认的地铁型更久: dmu=" + dmu + " metro=" + metro);
    assertTrue(sluggish > dmu, () -> "加速度标签优先于车种预设: sluggish=" + sluggish + " dmu=" + dmu);
  }

  @Test
  void runningTrainIsCappedByItsMaxSpeed() {
    int free =
        travelSec(
            new TrainRuntimeSnapshot.Motion(
                Optional.of(TrainType.METRO), OptionalDouble.empty(), OptionalDouble.empty()));
    int capped =
        travelSec(
            new TrainRuntimeSnapshot.Motion(
                Optional.of(TrainType.METRO),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.of(2.0)));

    assertTrue(capped > free, () -> "车型最高速度封顶边限速: capped=" + capped + " free=" + free);
  }

  @Test
  void pendingTicketEstimatesWithItsOwnConsist() {
    EtaService service =
        new EtaService(
            new TrainSnapshotStore(),
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class));
    service.attachConfigSources(new SignNodeRegistry(), () -> CONFIG);
    List<Optional<String>> asked = new java.util.ArrayList<>();
    service.attachPlannedConsist(
        (id, consist) -> {
          asked.add(consist);
          return Optional.of(new TrainConfig(TrainType.DMU, 0.6, 0.9));
        });

    service.resolveTravelTimeModelForRoute(routeUuid, Optional.of("m8"), worldId, Instant.now());
    service.resolveTravelTimeModelForRoute(routeUuid, worldId, Instant.now());

    assertEquals(List.of(Optional.of("m8"), Optional.empty()), asked, "表定票按票上的车型，间隔票按方案预计");
  }

  @Test
  void motionResolvesLikeTheController() {
    ConfigManager.TrainConfigSettings settings = CONFIG.trainConfigSettings();
    assertEquals(
        new TrainConfig(TrainType.METRO, 1.1, 1.2),
        TrainRuntimeSnapshot.Motion.NONE.resolve(settings));
    assertEquals(
        new TrainConfig(TrainType.EMU, 0.9, 0.5),
        new TrainRuntimeSnapshot.Motion(
                Optional.of(TrainType.EMU), OptionalDouble.of(-1), OptionalDouble.of(0.5))
            .resolve(settings),
        "非正数的标签按缺失处理");
  }

  @Test
  void plannedConsistReplacesDepotSignInference() {
    RouteDefinitionCache definitions = mock(RouteDefinitionCache.class);
    EtaService service =
        new EtaService(new TrainSnapshotStore(), mock(RailGraphService.class), definitions);
    service.attachConfigSources(new SignNodeRegistry(), () -> CONFIG);
    service.attachPlannedConsist(
        (id, consist) -> Optional.of(new TrainConfig(TrainType.DMU, 0.6, 0.9)));

    service.resolveTravelTimeModelForRoute(routeUuid, worldId, Instant.now());

    verify(definitions, never()).findRecord(routeUuid);
  }

  private int travelSec(TrainRuntimeSnapshot.Motion motion) {
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph(), Instant.now())));
    // 与实服一致：边没写限速时有效限速就是默认速度（不是 0）。
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), org.mockito.ArgumentMatchers.anyDouble()))
        .thenAnswer(invocation -> invocation.getArgument(3));
    RouteDefinitionCache definitions = mock(RouteDefinitionCache.class);
    when(definitions.findById(routeUuid)).thenReturn(Optional.of(route));
    OccupancyManager occupancy = mock(OccupancyManager.class);
    when(occupancy.canEnter(any()))
        .thenReturn(new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()));

    TrainSnapshotStore snapshots = new TrainSnapshotStore();
    snapshots.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            route.id(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty(),
            OptionalDouble.of(0.0),
            java.util.OptionalInt.empty(),
            java.util.OptionalInt.empty(),
            OptionalDouble.empty(),
            TrainRuntimeSnapshot.HoldTimeline.EMPTY,
            Optional.empty(),
            Optional.empty(),
            motion));
    EtaService service = new EtaService(snapshots, railGraphService, definitions);
    service.attachConfigSources(new SignNodeRegistry(), () -> CONFIG);
    return service.getForTrain("train-1", EtaTarget.nextStop()).travelSec();
  }

  private RailGraph graph() {
    SignRailNode a =
        new SignRailNode(
            start, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
    SignRailNode b =
        new SignRailNode(
            next, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
    EdgeId edgeId = EdgeId.undirected(start, next);
    RailEdge edge = new RailEdge(edgeId, start, next, 400, 0.0, true, Optional.empty());
    return new SimpleRailGraph(Map.of(start, a, next, b), Map.of(edgeId, edge), Set.of());
  }
}
