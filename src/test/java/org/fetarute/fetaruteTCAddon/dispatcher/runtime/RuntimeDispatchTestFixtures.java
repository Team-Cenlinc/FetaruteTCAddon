package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;

/**
 * 调度回归共用的列车、标签、线性图与配置夹具。
 *
 * <p>句柄的计数器和故障注入字段供同包测试直接观察；占用和进度仍由真实业务组件维护。拆分测试类时复用这些夹具，避免复制庞大的运行时测试类或降低静态分析覆盖。
 */
final class RuntimeDispatchTestFixtures {
  private RuntimeDispatchTestFixtures() {}

  /**
   * 构造不携带冲突索引能力的线性图夹具。
   *
   * <p>用于只验证制动窗口、距离扫描或动态 authority 边界的测试，避免 {@link SimpleRailGraph} 自动生成的单线冲突组改变被测语义。
   */
  static RailGraph graphWithConflictFreeLinearPath(List<NodeId> nodes, int lengthBlocks) {
    java.util.Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> railNodes =
        new java.util.LinkedHashMap<>();
    java.util.List<RailEdge> edges = new java.util.ArrayList<>();
    for (NodeId node : nodes) {
      railNodes.put(node, new RailNodeTest(node));
    }
    for (int index = 0; index + 1 < nodes.size(); index++) {
      NodeId from = nodes.get(index);
      NodeId to = nodes.get(index + 1);
      EdgeId edgeId = EdgeId.undirected(from, to);
      edges.add(new RailEdge(edgeId, from, to, lengthBlocks, -1.0, true, Optional.empty()));
    }
    return new RailGraph() {
      @Override
      public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes() {
        return railNodes.values();
      }

      @Override
      public java.util.Collection<RailEdge> edges() {
        return edges;
      }

      @Override
      public Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> findNode(NodeId id) {
        return Optional.ofNullable(railNodes.get(id));
      }

      @Override
      public java.util.Set<RailEdge> edgesFrom(NodeId id) {
        if (id == null) {
          return Set.of();
        }
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

  /**
   * 去掉单线区段与冲突组语义、只保留节点、区间与联锁区的图视图。
   *
   * <p>链状夹具会被 {@link SimpleRailGraph} 识别成单线区段，信号 tick 在单线准入处提前停车；实服的双线区间不构成单线区段。 需要让信号 tick
   * 走到常规授权与位置保持时用这个视图。
   */
  static RailGraph sectionlessGraph(SimpleRailGraph delegate) {
    return new SectionlessGraph(delegate, delegate.interlockingState());
  }

  private record SectionlessGraph(RailGraph delegate, RailInterlockingState interlockingState)
      implements RailGraph, RailGraphInterlockingSupport {

    @Override
    public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes() {
      return delegate.nodes();
    }

    @Override
    public java.util.Collection<RailEdge> edges() {
      return delegate.edges();
    }

    @Override
    public Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> findNode(NodeId id) {
      return delegate.findNode(id);
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      return delegate.edgesFrom(id);
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return delegate.isBlocked(id);
    }
  }

  static ConfigManager.ConfigView testConfigView(int intervalTicks, double defaultSpeedBps) {
    return testConfigView(intervalTicks, defaultSpeedBps, 1, 1);
  }

  /**
   * 构造可调硬授权窗口的配置视图。
   *
   * <p>默认的 {@code lookaheadEdges=1} 只覆盖一条边，因此任何多于一条边的单线 section 都无法证明清出，列车会被 {@code
   * entry-lookahead-exit-not-feasible} 永久挡住。真实部署使用的是 {@code lookahead-edges: 3}；需要让列车真正跑起来的
   * 多车场景必须显式给出与 section 长度匹配的窗口。
   */
  static ConfigManager.ConfigView testConfigView(
      int intervalTicks, double defaultSpeedBps, int lookaheadEdges, int minClearEdges) {
    return testConfigView(
        intervalTicks, defaultSpeedBps, lookaheadEdges, minClearEdges, SmartDispatcherMode.ENFORCE);
  }

  /**
   * 构造可指定 Smart Dispatcher 模式的配置视图。
   *
   * <p>{@code OBSERVE_ONLY} 只禁止 <b>Smart Dispatcher</b> 的副作用（signal / destination / token /
   * occupancy / destroy），基础准入层照常取 claim——所以"观察模式下账本 {@code version()} 增量为 0"
   * 这种断言是不成立的。观察层零副作用只能<b>差分</b>验证：同一场景跑两遍，一遍不驱动 Smart 层、 一遍在 OBSERVE_ONLY 下驱动，账本演化必须完全一致。
   */
  static ConfigManager.ConfigView testConfigView(
      int intervalTicks,
      double defaultSpeedBps,
      int lookaheadEdges,
      int minClearEdges,
      SmartDispatcherMode smartDispatcherMode) {
    ConfigManager.StorageSettings storage =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings("data/test.sqlite"),
            Optional.empty(),
            new ConfigManager.PoolSettings(1, 1000, 1000, 1000));
    ConfigManager.GraphSettings graph = new ConfigManager.GraphSettings(defaultSpeedBps, 6, 2);
    ConfigManager.AutoStationSettings autoStation =
        new ConfigManager.AutoStationSettings("", 1.0f, 1.0f);
    ConfigManager.RuntimeSettings runtime =
        new ConfigManager.RuntimeSettings(
            intervalTicks,
            10,
            lookaheadEdges,
            minClearEdges,
            1,
            3,
            0.0,
            6.0,
            3.5,
            true,
            SpeedCurveType.PHYSICS,
            1.0,
            0.0,
            0.2,
            60,
            true,
            true,
            2.0,
            8.0,
            0.15,
            1.0,
            1.0,
            3,
            true,
            10,
            Optional.empty(),
            false,
            10,
            Optional.empty(),
            false,
            10,
            Optional.empty());
    ConfigManager.TrainTypeSettings typeDefaults = new ConfigManager.TrainTypeSettings(1.0, 1.0);
    ConfigManager.TrainConfigSettings train =
        new ConfigManager.TrainConfigSettings("emu", Map.of(TrainType.EMU, typeDefaults));
    return new ConfigManager.ConfigView(
        10,
        false,
        "zh_CN",
        storage,
        graph,
        autoStation,
        runtime,
        new ConfigManager.SpawnSettings(false, 20, 200, 1, 5, 5, 40, 10, 2.0),
        train,
        new ConfigManager.ReclaimSettings(false, 3600L, 100, 60L),
        new ConfigManager.SmartDispatcherSettings(smartDispatcherMode),
        ConfigManager.HealthSettings.defaults());
  }

  static RouteStop routeStop(int sequence, NodeId nodeId, RouteStopPassType passType) {
    return new RouteStop(
        UUID.randomUUID(),
        sequence,
        Optional.empty(),
        Optional.of(nodeId.value()),
        Optional.empty(),
        passType,
        Optional.empty());
  }

  static RouteStop dynamicStop(int sequence, NodeId nodeId, String notes) {
    return new RouteStop(
        UUID.randomUUID(),
        sequence,
        Optional.empty(),
        Optional.of(nodeId.value()),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.of(notes));
  }

  static final class TagStore {
    final TrainProperties properties;
    final List<String> tags;

    TagStore(String trainName, String... initial) {
      this.tags = new ArrayList<>(Arrays.asList(initial));
      this.properties = mock(TrainProperties.class);
      when(properties.getTrainName()).thenReturn(trainName);
      when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
      when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
      when(properties.toString()).thenReturn("TrainProperties(" + trainName + ")");
      // 支持 addTags 和 removeTags 以测试 TrainTagHelper
      // varargs 在 Mockito doAnswer 时，整个 varargs 作为 Object[] 传入
      lenient()
          .doAnswer(
              inv -> {
                Object[] args = inv.getArguments();
                if (args != null) {
                  for (Object arg : args) {
                    if (arg instanceof String s && !s.isBlank()) {
                      tags.add(s);
                    }
                  }
                }
                return null;
              })
          .when(properties)
          .addTags(any(String[].class));
      lenient()
          .doAnswer(
              inv -> {
                Object[] args = inv.getArguments();
                if (args != null) {
                  for (Object arg : args) {
                    if (arg instanceof String s) {
                      tags.remove(s);
                    }
                  }
                }
                return null;
              })
          .when(properties)
          .removeTags(any(String[].class));
    }

    TrainProperties properties() {
      return properties;
    }

    void removeTagKey(String key) {
      String prefix = key + "=";
      tags.removeIf(tag -> tag != null && (tag.equals(key) || tag.startsWith(prefix)));
    }
  }

  static final class FakeTrain implements RuntimeTrainHandle {
    final UUID worldId;
    final TrainProperties properties;
    final boolean moving;
    final double speedBlocksPerTick;
    final List<String> controlEvents;
    final String controlEventName;
    int launchCalls = 0;
    int destroyCalls = 0;
    int stopCalls = 0;
    int hardStopCalls = 0;
    Optional<Set<RailFootprintCell>> liveRailFootprintCells =
        Optional.of(Set.of(new RailFootprintCell(0, 64, 0)));
    Optional<RailState> railState = Optional.empty();
    OptionalDouble estimatedTrainLengthBlocks = OptionalDouble.of(1.0);
    LinkageError liveRailFootprintFailure;
    LinkageError physicalIdentityFailure;
    boolean physicalIdentityUnavailable;

    FakeTrain(UUID worldId, TrainProperties properties, boolean moving) {
      this(worldId, properties, moving, 0.0);
    }

    FakeTrain(UUID worldId, TrainProperties properties, boolean moving, double speedBlocksPerTick) {
      this(worldId, properties, moving, speedBlocksPerTick, null, null);
    }

    FakeTrain(
        UUID worldId,
        TrainProperties properties,
        boolean moving,
        double speedBlocksPerTick,
        List<String> controlEvents,
        String controlEventName) {
      this.worldId = worldId;
      this.properties = properties;
      this.moving = moving;
      this.speedBlocksPerTick = speedBlocksPerTick;
      this.controlEvents = controlEvents;
      this.controlEventName = controlEventName;
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
    public double currentSpeedBlocksPerTick() {
      return speedBlocksPerTick;
    }

    @Override
    public UUID worldId() {
      return worldId;
    }

    @Override
    public Object physicalRuntimeIdentity() {
      if (physicalIdentityFailure != null) {
        throw physicalIdentityFailure;
      }
      return physicalIdentityUnavailable ? null : this;
    }

    @Override
    public TrainProperties properties() {
      return properties;
    }

    @Override
    public OptionalDouble estimatedTrainLengthBlocks() {
      return estimatedTrainLengthBlocks;
    }

    @Override
    public Optional<Set<RailFootprintCell>> liveRailFootprintCells() {
      if (liveRailFootprintFailure != null) {
        throw liveRailFootprintFailure;
      }
      return liveRailFootprintCells;
    }

    @Override
    public void stop() {
      stopCalls++;
    }

    @Override
    public void stopHard() {
      hardStopCalls++;
      stopCalls++;
      recordControlEvent("hard-stop");
    }

    @Override
    public void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
      launchCalls++;
      recordControlEvent("launch");
    }

    @Override
    public void destroy() {
      destroyCalls++;
    }

    @Override
    public void setRouteIndex(int index) {}

    @Override
    public void setRouteId(String routeId) {}

    @Override
    public void setDestination(String destination) {}

    @Override
    public java.util.Optional<org.bukkit.block.BlockFace> forwardDirection() {
      return java.util.Optional.empty();
    }

    @Override
    public Optional<RailState> railState() {
      return railState;
    }

    @Override
    public void reverse() {}

    void recordControlEvent(String event) {
      if (controlEvents != null && controlEventName != null) {
        controlEvents.add(controlEventName + ":" + event);
      }
    }
  }
}
