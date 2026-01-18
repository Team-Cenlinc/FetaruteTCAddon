package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

class RuntimeDispatchServiceTest {

  @Test
  void handleSignalTickSkipsWhenRouteIndexMissing() {
    TagStore tags =
        new TagStore("train-1", "FTA_OPERATOR_CODE=op", "FTA_LINE_CODE=l1", "FTA_ROUTE_CODE=r1");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            configManager,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);

    verifyNoInteractions(occupancyManager);
  }

  @Test
  void handleSignalTickInitializesProgressRegistryAndAvoidsRepeatedLaunch() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("A"), NodeId.of("B"), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            signNodeRegistry,
            configManager,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);
    assertTrue(train.launchCalls == 0);
  }

  @Test
  void handleSignalTickDoesNotLaunchWhenGroupIsMoving() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("A"), NodeId.of("B"), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.CAUTION, Instant.now());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            registry,
            signNodeRegistry,
            configManager,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), true);

    service.handleSignalTick(train, false);
    assertTrue(train.launchCalls == 0);
  }

  @Test
  void handleSignalTickUsesHoldLease() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("A"), NodeId.of("B"), 1), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            signNodeRegistry,
            configManager,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);

    ArgumentCaptor<OccupancyRequest> requestCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    verify(occupancyManager).acquire(requestCaptor.capture());
    assertFalse(requestCaptor.getValue().resourceList().isEmpty());
  }

  @Test
  void handleTrainRemovedReleasesOccupancyAndClearsProgress() {
    String trainName = "train-1";
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags = new TagStore(trainName, "FTA_ROUTE_INDEX=0");

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    OccupancyManager occupancyManager = mock(OccupancyManager.class);

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(trainName, tags.properties(), route);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            registry,
            signNodeRegistry,
            configManager,
            new TrainConfigResolver(),
            null);

    service.handleTrainRemoved(trainName);

    verify(occupancyManager).releaseByTrain(trainName);
    assertTrue(registry.get(trainName).isEmpty());
  }

  @Test
  void rebuildOccupancySnapshotReleasesOrphanClaims() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("A"), NodeId.of("B"), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    new OccupancyResource(ResourceKind.EDGE, "A~B"),
                    "ghost",
                    Optional.empty(),
                    Instant.now(),
                    Duration.ZERO)));

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            signNodeRegistry,
            configManager,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.rebuildOccupancySnapshot(List.of(train));

    verify(occupancyManager).releaseByTrain("ghost");
  }

  @Test
  void cleanupOrphanOccupancyClaimsRemovesProgressEntries() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags = new TagStore("old-train", "FTA_ROUTE_INDEX=0");

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("old-train", tags.properties(), route);

    RuntimeDispatchService serviceWithRegistry =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            registry,
            mock(SignNodeRegistry.class),
            configManager,
            new TrainConfigResolver(),
            null);

    serviceWithRegistry.cleanupOrphanOccupancyClaims(java.util.Set.of("new-train"));

    assertTrue(registry.get("old-train").isEmpty());
  }

  @Test
  void handleSignalTickMigratesProgressOnRename() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "new-train",
            "FTA_TRAIN_NAME=old-train",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("A"), NodeId.of("B"), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("old-train", tags.properties(), route);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            registry,
            signNodeRegistry,
            configManager,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);

    verify(occupancyManager).releaseByTrain("old-train");
    assertTrue(registry.get("old-train").isEmpty());
    assertTrue(registry.get("new-train").isPresent());
  }

  private static Answer<OccupancyDecision> allowProceed() {
    return inv -> {
      OccupancyRequest request = inv.getArgument(0);
      return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
    };
  }

  private static RailGraph graphWithSingleEdge(NodeId a, NodeId b, int lengthBlocks) {
    RailEdge edge =
        new RailEdge(EdgeId.undirected(a, b), a, b, lengthBlocks, -1.0, true, Optional.empty());
    return new RailGraph() {
      @Override
      public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes() {
        return List.of();
      }

      @Override
      public java.util.Collection<RailEdge> edges() {
        return List.of(edge);
      }

      @Override
      public Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> findNode(NodeId id) {
        return Optional.empty();
      }

      @Override
      public java.util.Set<RailEdge> edgesFrom(NodeId id) {
        if (id.equals(a) || id.equals(b)) {
          return java.util.Set.of(edge);
        }
        return java.util.Set.of();
      }

      @Override
      public boolean isBlocked(EdgeId id) {
        return false;
      }
    };
  }

  private static ConfigManager.ConfigView testConfigView(
      int intervalTicks, double defaultSpeedBps) {
    ConfigManager.StorageSettings storage =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings("data/test.sqlite"),
            Optional.empty(),
            new ConfigManager.PoolSettings(1, 1000, 1000, 1000));
    ConfigManager.GraphSettings graph = new ConfigManager.GraphSettings(defaultSpeedBps);
    ConfigManager.AutoStationSettings autoStation =
        new ConfigManager.AutoStationSettings("", 1.0f, 1.0f);
    ConfigManager.RuntimeSettings runtime =
        new ConfigManager.RuntimeSettings(intervalTicks, 1, 3, 0.0, 6.0);
    ConfigManager.TrainTypeSettings typeDefaults = new ConfigManager.TrainTypeSettings(1.0, 1.0);
    ConfigManager.TrainConfigSettings train =
        new ConfigManager.TrainConfigSettings(
            "emu", typeDefaults, typeDefaults, typeDefaults, typeDefaults);
    return new ConfigManager.ConfigView(
        1, false, "zh_CN", storage, graph, autoStation, runtime, train);
  }

  private static final class TagStore {
    private final TrainProperties properties;
    private final List<String> tags;

    private TagStore(String trainName, String... initial) {
      this.tags = new ArrayList<>(Arrays.asList(initial));
      this.properties = mock(TrainProperties.class);
      when(properties.getTrainName()).thenReturn(trainName);
      when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
      when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
      when(properties.toString()).thenReturn("TrainProperties(" + trainName + ")");
    }

    private TrainProperties properties() {
      return properties;
    }
  }

  private static final class FakeTrain implements RuntimeTrainHandle {
    private final UUID worldId;
    private final TrainProperties properties;
    private final boolean moving;
    private int launchCalls = 0;

    private FakeTrain(UUID worldId, TrainProperties properties, boolean moving) {
      this.worldId = worldId;
      this.properties = properties;
      this.moving = moving;
    }

    @Override
    public boolean isValid() {
      return true;
    }

    @Override
    public boolean isMoving() {
      return moving;
    }

    @Override
    public UUID worldId() {
      return worldId;
    }

    @Override
    public TrainProperties properties() {
      return properties;
    }

    @Override
    public void stop() {}

    @Override
    public void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
      launchCalls++;
    }
  }
}
