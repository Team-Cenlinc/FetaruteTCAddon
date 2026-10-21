package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingEdgeSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.network.RailNetworkCache;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailComponentCautionRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailInterlockingSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailComponentCautionRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailInterlockingSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 管理各世界的 RailGraph 快照，供命令与运行时调度复用。 */
public final class RailGraphService {

  private final Consumer<String> debugLogger;
  private final ConcurrentMap<UUID, RailGraphSnapshot> snapshots = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, RailInterlockingState> lastActivatedInterlockingStates =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, RailGraphStaleState> staleStates = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, RailGraphComponentIndex> componentIndexes =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, ConcurrentMap<EdgeId, RailEdgeOverrideRecord>> edgeOverrides =
      new ConcurrentHashMap<>();

  /**
   * {@link #edgeOverrides(UUID)} 的只读快照：运行时每次解析调度图都要取一次，覆盖却只在运维命令与加载时变。
   *
   * <p>改覆盖与建快照都在 {@link #edgeOverrideLock} 里做，建好的快照不会漏掉一次修改；读已有快照不加锁。
   */
  private final ConcurrentMap<UUID, Map<EdgeId, RailEdgeOverrideRecord>> edgeOverrideSnapshots =
      new ConcurrentHashMap<>();

  private final Object edgeOverrideLock = new Object();

  /** 快照每次增删递增，跨世界路网据此判断要不要重建。 */
  private final java.util.concurrent.atomic.AtomicLong snapshotVersion =
      new java.util.concurrent.atomic.AtomicLong();

  private volatile boolean crossWorld;
  private volatile org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry
      portalLinks;

  /** 跨世界路网：输入变了或定时封锁到期才重建，能用旧网时放到后台建（见 {@link RailNetworkCache}）。 */
  private final RailNetworkCache networkCache;

  /** 运行时图可能变了（换图、移出、覆盖增删、路网换新）之后的回调，见 {@link #addRuntimeGraphListener}。 */
  private final List<Runnable> runtimeGraphListeners = new CopyOnWriteArrayList<>();

  private final ConcurrentMap<UUID, ConcurrentMap<String, RailComponentCautionRecord>>
      componentCautions = new ConcurrentHashMap<>();
  private volatile BooleanSupplier snapshotActivationGuard = () -> true;
  private volatile Executor backgroundExecutor;

  public RailGraphService() {
    this(message -> {});
  }

  public RailGraphService(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.networkCache = new RailNetworkCache(this::fireRuntimeGraphChanged, this.debugLogger);
  }

  /**
   * 注册运行时图变化的回调：快照激活或移出、边覆盖增删、跨世界路网换新之后各调一次。
   *
   * <p>回调可能在任意线程上被调用（路网后台重建在后台线程换新），必须线程安全、不阻塞；只适合登记一个“稍后处理”的请求。 回调抛的异常只记日志。
   */
  public void addRuntimeGraphListener(Runnable listener) {
    if (listener != null) {
      runtimeGraphListeners.add(listener);
    }
  }

  /** 移除运行时图变化的回调。 */
  public void removeRuntimeGraphListener(Runnable listener) {
    runtimeGraphListeners.remove(listener);
  }

  /**
   * 后台执行器：跨世界路网后台重建、快照激活后的索引与路网预热、运行时从库重载时组装图都交给它；{@code null}（默认）时路网一律在调用方线程上
   * 同步重建，激活后不预热（第一次查询时懒加载），从库重载同步做。
   *
   * <p>建图命令在切换前已用 {@link #prepareSnapshot} 建好；启动加载与各命令的同步兜底切换前没有准备，激活后由这里预热，主线程的第一次占用/授权查询
   * 多半直接命中。索引预热到一半时主线程来查，会等它建完，不比自己建更久。
   *
   * <p>插件停用时先置空再关执行器。跨世界关闭时不建路网，与此无关。
   */
  public void setBackgroundExecutor(Executor executor) {
    this.backgroundExecutor = executor;
    networkCache.setExecutor(executor);
  }

  /** 判断路网里定时封锁是否到期用的时钟（测试注入）。 */
  void setNetworkClock(Clock clock) {
    networkCache.setClock(clock);
  }

  private void fireRuntimeGraphChanged() {
    for (Runnable listener : runtimeGraphListeners) {
      try {
        listener.run();
      } catch (RuntimeException ex) {
        debugLogger.accept("运行时图变化回调失败: " + ex);
      }
    }
  }

  /** 图快照版本：任何世界的快照切换、移出都会让它变；按它判断按旧图算的缓存是否作废。 */
  public long snapshotVersion() {
    return snapshotVersion.get();
  }

  public void putSnapshot(World world, RailGraph graph, Instant builtAt) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(builtAt, "builtAt");
    activateSnapshot(world.getUID(), graph, builtAt);
  }

  /**
   * 设置图快照激活前的静默条件检查。
   *
   * <p>只有 old/new 物理联锁资源投影发生变化时才调用该 guard。运行时应注入“当前没有任何占用 claim”；默认恒为 true，以保持独立图测试与启动预热兼容。
   */
  public void setSnapshotActivationGuard(BooleanSupplier snapshotActivationGuard) {
    this.snapshotActivationGuard =
        Objects.requireNonNull(snapshotActivationGuard, "snapshotActivationGuard");
  }

  /**
   * 在持久化新快照前校验其物理联锁资源投影是否允许切换。
   *
   * <p>图构建命令在同一主线程调用链中先执行本校验、再提交 SQL 事务、最后调用 {@link #putSnapshot(World, RailGraph, Instant)}。这样既不会在
   * active claim 存在时先改磁盘，也不会在 SQL 失败时先改内存。
   *
   * @throws IllegalStateException 资源投影变化且当前仍有占用 claim
   */
  public void validateSnapshotActivation(World world, RailGraph graph) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(graph, "graph");
    validateSnapshotActivation(world.getUID(), graph);
  }

  /**
   * 激活一张在旧图上只增不改的增补图，跳过"仍有任何占用就拒绝切换联锁投影"的全局闸。
   *
   * <p>调用方必须已经逐键核验：受影响的旧区间（资源键会变的那些）当前没有任何占用与排队。全局闸只看"有没有占用"，运行中几乎总是有，
   * 增补便永远做不了；逐键核验覆盖的正是它要防的事——旧键被持有时换图，新旧申请会互相看不见。
   */
  public void putVerifiedAppendSnapshot(World world, RailGraph graph, Instant builtAt) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(builtAt, "builtAt");
    activateSnapshot(world.getUID(), graph, builtAt, false, null);
  }

  /** 同 {@link #putSnapshot(World, RailGraph, Instant)}，切换的是切换前已准备好的图。 */
  public void putSnapshot(World world, PreparedSnapshot prepared, Instant builtAt) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(prepared, "prepared");
    Objects.requireNonNull(builtAt, "builtAt");
    activateSnapshot(world.getUID(), prepared.graph(), builtAt, true, prepared);
  }

  /** 同 {@link #putVerifiedAppendSnapshot(World, RailGraph, Instant)}，切换的是切换前已准备好的图。 */
  public void putVerifiedAppendSnapshot(World world, PreparedSnapshot prepared, Instant builtAt) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(prepared, "prepared");
    Objects.requireNonNull(builtAt, "builtAt");
    activateSnapshot(world.getUID(), prepared.graph(), builtAt, false, prepared);
  }

  /**
   * 切换前为新图建好派生数据，可在任意线程调用（只读不可变的图与线程安全的快照表、覆盖表、传送门登记）。
   *
   * <p>建好连通分量、冲突走廊与单线 section 三份索引；跨世界开启且有传送门连接时，再按此刻各世界的快照（本世界换成新图）与运维覆盖 拼一张路网候选（{@link
   * RailNetworkCache#prebuild}）。切换时只要期间各世界快照、运维覆盖与传送门连接都没变，候选就交给路网缓存换上；变过则丢弃，
   * 路网照旧由缓存按需重建。建索引出错不抛出，留给第一次查询懒加载。
   *
   * @param worldId 新图所属世界
   * @param graph 将要切换上去的图
   */
  public PreparedSnapshot prepareSnapshot(UUID worldId, RailGraph graph) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(graph, "graph");
    if (graph instanceof SimpleRailGraph simple && !simple.prewarmIndexes()) {
      debugLogger.accept("调度图索引预建失败，第一次查询时再建: world=" + worldId);
    }
    org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry links = portalLinks;
    if (!crossWorld || links == null) {
      return new PreparedSnapshot(graph, null, null);
    }
    // 先记版本与连接修订号，再读快照与连接：期间有任何变化，切换时版本或修订号就对不上。
    long version = snapshotVersion.get();
    long revision = links.revision();
    RailNetworkCache.Inputs inputs = networkInputs(version, revision, links, worldId, graph);
    if (inputs.links().isEmpty()) {
      return new PreparedSnapshot(graph, null, null);
    }
    return new PreparedSnapshot(graph, networkCache.prebuild(inputs), links);
  }

  private void activateSnapshot(UUID worldId, RailGraph graph, Instant builtAt) {
    activateSnapshot(worldId, graph, builtAt, true, null);
  }

  private void activateSnapshot(
      UUID worldId,
      RailGraph graph,
      Instant builtAt,
      boolean enforceProjectionGuard,
      PreparedSnapshot prepared) {
    if (enforceProjectionGuard) {
      validateSnapshotActivation(worldId, graph);
    }
    RailInterlockingState nextState = interlockingState(graph);
    RailGraphComponentIndex nextComponentIndex = componentIndexOf(graph);
    long previousVersion = snapshotVersion.get();
    snapshots.put(worldId, new RailGraphSnapshot(graph, builtAt));
    long version = snapshotVersion.incrementAndGet();
    componentIndexes.put(worldId, nextComponentIndex);
    lastActivatedInterlockingStates.put(worldId, nextState);
    staleStates.remove(worldId);
    traceInterlockingCoverage(worldId, nextState);
    installPreparedNetwork(prepared, graph, previousVersion, version);
    fireRuntimeGraphChanged();
    schedulePrewarm(worldId, graph);
  }

  private static RailGraphComponentIndex componentIndexOf(RailGraph graph) {
    return graph instanceof SimpleRailGraph simple
        ? simple.componentIndex()
        : RailGraphComponentIndex.fromGraph(graph);
  }

  /**
   * 切换时把切换前拼好的路网候选交给 {@link RailNetworkCache} 换上：只在准备之后除本次切换外快照版本没动过、传送门连接没变过时换，否则路网照旧由缓存按需重建。
   *
   * <p>在发出运行时图变化通知之前换上，收到通知去取路网的（交路路径预热等）直接命中。
   *
   * @param previousVersion 本次切换前的快照版本
   * @param version 本次切换后的快照版本
   */
  private void installPreparedNetwork(
      PreparedSnapshot prepared, RailGraph graph, long previousVersion, long version) {
    if (prepared == null || prepared.network == null || prepared.graph() != graph) {
      return;
    }
    org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry links = portalLinks;
    if (!crossWorld || links == null || links != prepared.links) {
      return;
    }
    networkCache.installPrebuilt(prepared.network, previousVersion, version, links.revision());
  }

  /**
   * 把刚激活的快照交给后台执行器预热索引与跨世界路网；轮到时它已被换掉就不再预热。排不上（插件停用中等）时留给第一次查询懒加载。
   *
   * <p>用于切换前没机会准备的路径（启动加载与各命令的同步兜底）；切换前已准备好的图在这里立即返回。
   */
  private void schedulePrewarm(UUID worldId, RailGraph graph) {
    Executor executor = backgroundExecutor;
    if (executor == null || !(graph instanceof SimpleRailGraph simple)) {
      return;
    }
    try {
      executor.execute(
          () -> {
            RailGraphSnapshot current = snapshots.get(worldId);
            if (current == null || current.graph() != graph) {
              return;
            }
            if (!simple.prewarmIndexes()) {
              debugLogger.accept("调度图索引预热失败，第一次查询时再建: world=" + worldId);
            }
            network();
          });
    } catch (RuntimeException ex) {
      debugLogger.accept("调度图索引预热未排上，第一次查询时再建: world=" + worldId + " msg=" + ex.getMessage());
    }
  }

  /**
   * 图激活时报告物理联锁覆盖的可用性。
   *
   * <p>为什么非有不可：{@code cellCoverageAvailable()} 是「车体实际压住哪些区间」这条证据链的**总闸**—— {@code
   * RuntimeDispatchService.livePhysicalEdgeCoverage} 在它为假时一律返回 incomplete， 于是任何以实测覆盖为放行条件的机制（尾部保护释放
   * / Phase 4）都会 fail-closed 到**一个都不放**。
   *
   * <p>而它有两条构建路径，结果天差地别：{@link
   * org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingZoneIndex#from}
   * 走完整图构建， 逐边足迹齐全，索引可用；{@code fromZones} 从持久化快照重建，**按设计只有 Zone、没有逐边足迹， 索引必然为空**。正常重启的服务器走的是后者。
   *
   * <p>构建期特性标志 {@code liveFootprintReverseIndex=true} 只说明代码有这个功能，不说明索引真的建起来了。
   * 缺了这一行，运行时便无法区分这两种状态，依赖该索引的机制可能代码路径俱在、trace 照常输出，却从不触发。
   *
   * <p>每次图激活至多一行，不随 tick 放大。
   */
  private void traceInterlockingCoverage(UUID worldId, RailInterlockingState state) {
    debugLogger.accept(
        "SMART_INTERLOCKING_COVERAGE world="
            + worldId
            + " available="
            + state.available()
            + " cellCoverageAvailable="
            + state.cellCoverageAvailable()
            + " expectedEdges="
            + state.expectedEdges().size()
            + " exactZones="
            + state.exactZoneCount()
            + " indexedZoneCells="
            + state.indexedZoneCellCount()
            + " multiZoneCells="
            + state.multiZoneCellCount());
  }

  private void validateSnapshotActivation(UUID worldId, RailGraph graph) {
    RailGraphSnapshot current = snapshots.get(worldId);
    RailInterlockingState currentState =
        lastActivatedInterlockingStates.getOrDefault(
            worldId,
            current == null
                ? RailInterlockingState.unavailable()
                : interlockingState(current.graph()));
    RailInterlockingState nextState = interlockingState(graph);
    if ((current != null || lastActivatedInterlockingStates.containsKey(worldId))
        && !currentState.sameResourceProjection(nextState)
        && !snapshotActivationGuard.getAsBoolean()) {
      throw new IllegalStateException("仍有列车占用 claim，拒绝切换物理联锁资源投影");
    }
  }

  private static RailInterlockingState interlockingState(RailGraph graph) {
    if (graph instanceof RailGraphInterlockingSupport support) {
      return support.interlockingState();
    }
    return RailInterlockingState.unavailable();
  }

  public Optional<RailGraphSnapshot> getSnapshot(World world) {
    Objects.requireNonNull(world, "world");
    return Optional.ofNullable(snapshots.get(world.getUID()));
  }

  /** 运行时便捷入口：按 worldId 查询内存快照，避免依赖 Bukkit World 实例。 */
  public Optional<RailGraphSnapshot> getSnapshot(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    return Optional.ofNullable(snapshots.get(worldId));
  }

  /**
   * 跨世界设置（{@code graph.cross-world}）与传送门连接。关闭时 {@link #getNetworkSnapshot} 与 {@link #getSnapshot}
   * 完全相同。
   */
  public void configureCrossWorld(
      boolean enabled,
      org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry links) {
    this.crossWorld = enabled;
    this.portalLinks = links;
    networkCache.reset();
  }

  /**
   * 运行时用的图快照：跨世界开启且有传送门连接时，图是站在这个世界看的全网（节点、边全网，物理联锁本世界）；否则与 {@link #getSnapshot} 相同。
   *
   * <p>只给运行时控车、ETA、出车、编表与交路校验用；建图、持久化、联锁目录等按世界处理的流程仍用 {@link #getSnapshot}， 免得把别的世界的节点写进本世界。
   */
  public Optional<RailGraphSnapshot> getNetworkSnapshot(UUID worldId) {
    return getSnapshot(worldId)
        .map(own -> new RailGraphSnapshot(runtimeGraph(this, worldId, own.graph()), own.builtAt()));
  }

  /**
   * 站在这个世界看的路网；跨世界未开启、没有传送门连接或本世界还没有图时为 {@code null}。
   *
   * <p>调用方一般用 {@link #runtimeGraph}，它在这里返回 {@code null} 时退回本世界的图。
   */
  public RailGraph networkGraph(UUID worldId) {
    if (worldId == null || getSnapshot(worldId).isEmpty()) {
      return null;
    }
    org.fetarute.fetaruteTCAddon.dispatcher.graph.network.RailNetwork network = network();
    // 没有传送门的世界里的列车出不了本世界，仍用本世界的图（最短路记忆、封锁都与原来一致）。
    return network == null || !network.hasPortalIn(worldId) ? null : network.view(worldId);
  }

  /** 运行时用的图：跨世界开启时换成站在本世界看的路网，否则就是本世界的图。 */
  public static RailGraph runtimeGraph(RailGraphService service, UUID worldId, RailGraph own) {
    RailGraph network = service == null ? null : service.networkGraph(worldId);
    return network != null ? network : own;
  }

  /**
   * 运行时控车、占用申请与选台实际查询用的图：{@link #runtimeGraph} 再叠加本世界此刻的运维覆盖；本世界没有图时为空。
   *
   * <p>最短路记忆按“底层图实例 + 此刻被覆盖封锁的边”分开记，预热必须用与运行时同一个底层图实例才能命中；各处解析运行时图应当都走这里。
   */
  public Optional<RailGraph> runtimeQueryGraph(UUID worldId, Instant now) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(now, "now");
    return getSnapshot(worldId)
        .map(
            snapshot -> {
              RailGraph graph = runtimeGraph(this, worldId, snapshot.graph());
              Map<EdgeId, RailEdgeOverrideRecord> overrides = edgeOverrides(worldId);
              return overrides.isEmpty() ? graph : new EdgeOverrideRailGraph(graph, overrides, now);
            });
  }

  /** 交路途经节点所在的世界：先按单个世界找，找不到再看跨世界路网（见 {@link #findCrossWorldPath}）。 */
  public Optional<UUID> findNetworkWorldForPath(List<NodeId> nodes) {
    return findWorldIdForPath(nodes).or(() -> findCrossWorldPath(nodes));
  }

  /**
   * 跨世界开启时，途经节点在路网里是否依次连通；连通则返回起点所在世界（再用 {@link #runtimeGraph} 取路网）。只在单个世界里连通的路径由 {@link
   * #findWorldIdForPath} 负责。
   */
  public Optional<UUID> findCrossWorldPath(List<NodeId> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      return Optional.empty();
    }
    org.fetarute.fetaruteTCAddon.dispatcher.graph.network.RailNetwork network = network();
    if (network == null) {
      return Optional.empty();
    }
    for (int i = 0; i + 1 < nodes.size(); i++) {
      if (!network.connected(nodes.get(i), nodes.get(i + 1))) {
        return Optional.empty();
      }
    }
    return network.worldOf(nodes.get(0));
  }

  /** 两个相邻停靠点之间：单个世界连通，或跨世界路网连通（交路校验用）。 */
  public Optional<UUID> findNetworkWorldForConnectedPair(NodeId from, NodeId to) {
    return findWorldIdForConnectedPair(from, to).or(() -> findCrossWorldPath(List.of(from, to)));
  }

  /** 当前的跨世界路网；未开启或没有可用的传送门连接时为 {@code null}。 */
  private org.fetarute.fetaruteTCAddon.dispatcher.graph.network.RailNetwork network() {
    org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry links = portalLinks;
    if (!crossWorld || links == null || links.links().isEmpty()) {
      return null;
    }
    // 先读版本再采集输入：采集期间又有变化时，下一次取到的版本对不上，会再判定一次。
    long version = snapshotVersion.get();
    long revision = links.revision();
    org.fetarute.fetaruteTCAddon.dispatcher.graph.network.RailNetwork network =
        networkCache.get(
            version, revision, () -> networkInputs(version, revision, links, null, null));
    return network.hasPortalEdges() ? network : null;
  }

  /**
   * 拼路网的输入：各世界的图（未叠加覆盖）与覆盖快照、传送门连接。
   *
   * @param replacedWorld 换成 {@code replacement} 的世界（切换前准备用）；不换时为 {@code null}
   */
  private RailNetworkCache.Inputs networkInputs(
      long version,
      long revision,
      org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry links,
      UUID replacedWorld,
      RailGraph replacement) {
    Map<UUID, RailGraph> graphs = new HashMap<>();
    Map<UUID, Map<EdgeId, RailEdgeOverrideRecord>> overrides = new HashMap<>();
    for (Map.Entry<UUID, RailGraphSnapshot> entry : snapshots.entrySet()) {
      graphs.put(entry.getKey(), entry.getValue().graph());
      overrides.put(entry.getKey(), edgeOverrides(entry.getKey()));
    }
    if (replacedWorld != null) {
      graphs.put(replacedWorld, replacement);
      overrides.put(replacedWorld, edgeOverrides(replacedWorld));
    }
    return new RailNetworkCache.Inputs(version, revision, graphs, overrides, links.links());
  }

  /** 返回已加载的图快照数量，用于命令校验/诊断。 */
  public int snapshotCount() {
    return snapshots.size();
  }

  /**
   * 在已加载的快照中查找包含指定路径的世界。
   *
   * <p>路径按相邻节点“可连通”判定：只要两点处于同一连通分量即可；若任一节点缺失则返回 empty。
   */
  public Optional<UUID> findWorldIdForPath(List<NodeId> nodes) {
    if (nodes == null || nodes.size() < 2) {
      return Optional.empty();
    }
    for (Map.Entry<UUID, RailGraphSnapshot> entry : snapshots.entrySet()) {
      UUID worldId = entry.getKey();
      RailGraphSnapshot snapshot = entry.getValue();
      RailGraphComponentIndex index = componentIndexes.get(worldId);
      if (snapshot == null || snapshot.graph() == null || index == null) {
        continue;
      }
      if (pathConnected(index, nodes)) {
        return Optional.of(worldId);
      }
    }
    return Optional.empty();
  }

  /**
   * 在已加载的快照中查找包含指定区间的世界（按连通分量判定）。
   *
   * <p>用于命令侧校验路线是否可达（不触发区块加载）。
   */
  public Optional<UUID> findWorldIdForConnectedPair(NodeId from, NodeId to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    for (Map.Entry<UUID, RailGraphSnapshot> entry : snapshots.entrySet()) {
      UUID worldId = entry.getKey();
      RailGraphSnapshot snapshot = entry.getValue();
      RailGraphComponentIndex index = componentIndexes.get(worldId);
      if (snapshot == null || snapshot.graph() == null || index == null) {
        continue;
      }
      if (pairConnected(index, from, to)) {
        return Optional.of(worldId);
      }
    }
    return Optional.empty();
  }

  /**
   * 在已加载的快照中查找包含指定边的世界。
   *
   * <p>用于命令侧校验路线是否可达（不触发区块加载）。
   */
  public Optional<UUID> findWorldIdForEdge(NodeId from, NodeId to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    for (Map.Entry<UUID, RailGraphSnapshot> entry : snapshots.entrySet()) {
      RailGraphSnapshot snapshot = entry.getValue();
      if (snapshot == null || snapshot.graph() == null) {
        continue;
      }
      if (edgeExists(snapshot.graph(), from, to)) {
        return Optional.of(entry.getKey());
      }
    }
    return Optional.empty();
  }

  public Optional<RailGraphStaleState> getStaleState(World world) {
    Objects.requireNonNull(world, "world");
    return Optional.ofNullable(staleStates.get(world.getUID()));
  }

  private boolean edgeExists(RailGraph graph, NodeId from, NodeId to) {
    // 只检查内存快照，不触发区块加载。
    for (RailEdge edge : graph.edgesFrom(from)) {
      if (edgeConnects(edge, from, to)) {
        return true;
      }
    }
    return false;
  }

  private boolean pathConnected(RailGraphComponentIndex index, List<NodeId> nodes) {
    for (int i = 0; i < nodes.size() - 1; i++) {
      NodeId from = nodes.get(i);
      NodeId to = nodes.get(i + 1);
      if (from == null || to == null) {
        return false;
      }
      if (!pairConnected(index, from, to)) {
        return false;
      }
    }
    return true;
  }

  private boolean pairConnected(RailGraphComponentIndex index, NodeId from, NodeId to) {
    String fromKey = index.componentKey(from);
    String toKey = index.componentKey(to);
    return fromKey != null && fromKey.equals(toKey);
  }

  private boolean edgeConnects(RailEdge edge, NodeId a, NodeId b) {
    // 无向边匹配。
    if (edge == null) {
      return false;
    }
    return (edge.from().equals(a) && edge.to().equals(b))
        || (edge.from().equals(b) && edge.to().equals(a));
  }

  /**
   * 记录快照失效。
   *
   * <p>{@link RailGraphStaleState#snapshotRetained()} 为 true 时只打标记、继续供旧图（变化的节点没有交路在用）；
   * 否则移出内存快照，该世界在重建或恢复前拿不到图。已经移出的快照不会因为后一次"可保留"而回来。
   */
  public void markStale(World world, RailGraphStaleState state) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(state, "state");
    UUID worldId = world.getUID();
    if (state.snapshotRetained() && snapshots.containsKey(worldId)) {
      staleStates.put(worldId, state);
      return;
    }
    snapshots.remove(worldId);
    snapshotVersion.incrementAndGet();
    componentIndexes.remove(worldId);
    staleStates.put(worldId, state.withSnapshotRetained(false));
    fireRuntimeGraphChanged();
  }

  /**
   * 保留旧图期间节点牌子恢复原样（签名与快照重新一致）：内存里的旧图本就与库里的快照相同，只撤掉失效标记，不重载、不让各级缓存作废。
   *
   * @return 是否撤掉了标记；不在供保留旧图时为 false，调用方照旧从库重载
   */
  public boolean clearRetainedStale(World world) {
    Objects.requireNonNull(world, "world");
    UUID worldId = world.getUID();
    if (!isServingRetainedStaleSnapshot(worldId)) {
      return false;
    }
    staleStates.remove(worldId);
    return true;
  }

  /** 正在供保留下来的旧图，而旧图里没有这个节点（失效后新放的节点牌子）。 */
  public boolean isOutsideRetainedStaleSnapshot(UUID worldId, NodeId nodeId) {
    if (nodeId == null || !isServingRetainedStaleSnapshot(worldId)) {
      return false;
    }
    RailGraphSnapshot snapshot = snapshots.get(worldId);
    return snapshot != null && snapshot.graph().findNode(nodeId).isEmpty();
  }

  /** 是否正在供一张已失效但保留下来的旧图。 */
  public boolean isServingRetainedStaleSnapshot(UUID worldId) {
    if (worldId == null) {
      return false;
    }
    RailGraphStaleState state = staleStates.get(worldId);
    return state != null && state.snapshotRetained() && snapshots.containsKey(worldId);
  }

  /**
   * 清空指定世界的内存快照。
   *
   * <p>注意：该方法只影响内存缓存，不会删除 SQL 中的快照记录；若需同时清理持久化数据，应由命令/运维逻辑另行处理。
   *
   * @return 是否存在并成功移除了快照
   */
  public boolean clearSnapshot(World world) {
    Objects.requireNonNull(world, "world");
    UUID worldId = world.getUID();
    staleStates.remove(worldId);
    componentIndexes.remove(worldId);
    boolean removed = snapshots.remove(worldId) != null;
    snapshotVersion.incrementAndGet();
    fireRuntimeGraphChanged();
    return removed;
  }

  /** 返回节点所属连通分量的 key（不存在则 empty）。 */
  public Optional<String> componentKey(UUID worldId, NodeId nodeId) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(nodeId, "nodeId");
    RailGraphComponentIndex index = componentIndexes.get(worldId);
    if (index == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(index.componentKey(nodeId));
  }

  /**
   * 返回指定图的连通分量数量。
   *
   * @param graph 调度图实例
   * @return 连通分量数量
   */
  public int componentCount(RailGraph graph) {
    if (graph == null) {
      return 0;
    }
    // 按这张图自己缓存的分量索引算，不依赖按世界的缓存
    return componentIndexOf(graph).componentCount();
  }

  /** 查询某连通分量的 caution 速度覆盖（blocks/s）。 */
  public OptionalDouble componentCautionSpeedBlocksPerSecond(UUID worldId, String componentKey) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(componentKey, "componentKey");
    RailComponentCautionRecord record =
        componentCautions.getOrDefault(worldId, new ConcurrentHashMap<>()).get(componentKey);
    if (record == null) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(record.cautionSpeedBlocksPerSecond());
  }

  /** 写入或更新某连通分量的 caution 速度覆盖（仅更新内存）。 */
  public void putComponentCaution(RailComponentCautionRecord record) {
    Objects.requireNonNull(record, "record");
    componentCautions
        .computeIfAbsent(record.worldId(), ignored -> new ConcurrentHashMap<>())
        .put(record.componentKey(), record);
  }

  /** 删除某连通分量的 caution 速度覆盖（仅更新内存）。 */
  public void deleteComponentCaution(UUID worldId, String componentKey) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(componentKey, "componentKey");
    ConcurrentMap<String, RailComponentCautionRecord> byWorld = componentCautions.get(worldId);
    if (byWorld == null) {
      return;
    }
    byWorld.remove(componentKey);
    if (byWorld.isEmpty()) {
      componentCautions.remove(worldId, byWorld);
    }
  }

  /** 返回指定世界的连通分量 caution 覆盖快照（只读）。 */
  public Map<String, RailComponentCautionRecord> componentCautions(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    return Map.copyOf(componentCautions.getOrDefault(worldId, new ConcurrentHashMap<>()));
  }

  /** 返回指定世界的边运维覆盖快照（只读）。 */
  public Map<EdgeId, RailEdgeOverrideRecord> edgeOverrides(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    Map<EdgeId, RailEdgeOverrideRecord> cached = edgeOverrideSnapshots.get(worldId);
    if (cached != null) {
      return cached;
    }
    synchronized (edgeOverrideLock) {
      return edgeOverrideSnapshots.computeIfAbsent(
          worldId, id -> Map.copyOf(edgeOverrides.getOrDefault(id, new ConcurrentHashMap<>())));
    }
  }

  /** 查询某条边的运维覆盖。 */
  public Optional<RailEdgeOverrideRecord> getEdgeOverride(UUID worldId, EdgeId edgeId) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeId, "edgeId");
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    return Optional.ofNullable(
        edgeOverrides.getOrDefault(worldId, new ConcurrentHashMap<>()).get(normalized));
  }

  /** 写入或更新某条边的运维覆盖（仅更新内存）。 */
  public void putEdgeOverride(RailEdgeOverrideRecord override) {
    Objects.requireNonNull(override, "override");
    EdgeId normalized = EdgeId.undirected(override.edgeId().a(), override.edgeId().b());
    synchronized (edgeOverrideLock) {
      edgeOverrides
          .computeIfAbsent(override.worldId(), ignored -> new ConcurrentHashMap<>())
          .put(normalized, override);
      edgeOverrideSnapshots.remove(override.worldId());
      snapshotVersion.incrementAndGet();
    }
    fireRuntimeGraphChanged();
  }

  /** 删除某条边的运维覆盖（仅更新内存）。 */
  public void deleteEdgeOverride(UUID worldId, EdgeId edgeId) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeId, "edgeId");
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    synchronized (edgeOverrideLock) {
      ConcurrentMap<EdgeId, RailEdgeOverrideRecord> byWorld = edgeOverrides.get(worldId);
      if (byWorld == null) {
        return;
      }
      byWorld.remove(normalized);
      if (byWorld.isEmpty()) {
        edgeOverrides.remove(worldId, byWorld);
      }
      edgeOverrideSnapshots.remove(worldId);
      snapshotVersion.incrementAndGet();
    }
    fireRuntimeGraphChanged();
  }

  /**
   * 计算某条边的“当前有效限速”（blocks/s）。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>base = edge.baseSpeedLimit &gt; 0 ? edge.baseSpeedLimit : default
   *   <li>normal = override.speedLimit ? override.speedLimit : base
   *   <li>effective = min(normal, override.tempSpeedLimit(if active))
   * </ul>
   */
  public double effectiveSpeedLimitBlocksPerSecond(
      UUID worldId, RailEdge edge, Instant now, double defaultSpeedBlocksPerSecond) {
    return effectiveSpeedLimitBlocksPerSecond(worldId, edge, now, defaultSpeedBlocksPerSecond, 1.0);
  }

  /**
   * 同 {@link #effectiveSpeedLimitBlocksPerSecond(UUID, RailEdge, Instant, double)}，另按倍率放宽或压低限速：大于 1
   * 是晚点追赶，小于 1 是叫来的车追近前车时按需降速。
   *
   * <p>放宽只放<b>写明了的线路限速</b>：边基础限速（牌子写的）或永久限速覆盖，也就是编表按它算表定时分的那个数。 不放宽的有三类：没写限速、按默认速度走的边——没有证据说它扛得住更快；
   * 临时限速——施工、限行是运维硬约束；以及不经过这里的进站限速、CAUTION 与信号给出的速度。压低则作用于算完的边限速（含默认速度与临时限速）。
   *
   * @param lineSpeedFactor 限速倍率；等于 1 或非有限值、非正值时按 1
   */
  public double effectiveSpeedLimitBlocksPerSecond(
      UUID worldId,
      RailEdge edge,
      Instant now,
      double defaultSpeedBlocksPerSecond,
      double lineSpeedFactor) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edge, "edge");
    Objects.requireNonNull(now, "now");
    if (!Double.isFinite(defaultSpeedBlocksPerSecond) || defaultSpeedBlocksPerSecond <= 0.0) {
      throw new IllegalArgumentException("defaultSpeedBlocksPerSecond 必须为正数");
    }
    double factor =
        Double.isFinite(lineSpeedFactor) && lineSpeedFactor > 1.0 ? lineSpeedFactor : 1.0;
    double slowdown =
        Double.isFinite(lineSpeedFactor) && lineSpeedFactor > 0.0 && lineSpeedFactor < 1.0
            ? lineSpeedFactor
            : 1.0;

    double baseFromEdge = edge.baseSpeedLimit();
    boolean baseWritten = Double.isFinite(baseFromEdge) && baseFromEdge > 0.0;
    double base = baseWritten ? baseFromEdge * factor : defaultSpeedBlocksPerSecond;

    EdgeId edgeId = edge.id();
    if (edgeId == null) {
      return base * slowdown;
    }
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    RailEdgeOverrideRecord override =
        edgeOverrides.getOrDefault(worldId, new ConcurrentHashMap<>()).get(normalized);
    double effective = base;
    if (override != null && override.speedLimitBlocksPerSecond().isPresent()) {
      effective = override.speedLimitBlocksPerSecond().getAsDouble() * factor;
    }
    if (override != null && override.isTempSpeedActive(now)) {
      effective = Math.min(effective, override.tempSpeedLimitBlocksPerSecond().getAsDouble());
    }
    if (!Double.isFinite(effective) || effective <= 0.0) {
      return base * slowdown;
    }
    return effective * slowdown;
  }

  public Map<UUID, RailGraphSnapshot> snapshotAll() {
    return Map.copyOf(snapshots);
  }

  /**
   * 从存储后端加载每个世界的持久化调度图到内存。
   *
   * <p>若某世界没有快照记录，将跳过加载。读库、组装图与切换都在调用线程上同步做（启动时用）；运行时重载用 {@link #reloadFromStorage}。
   */
  public void loadFromStorage(StorageProvider provider, java.util.List<World> worlds) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(worlds, "worlds");
    for (World world : worlds) {
      if (world == null) {
        continue;
      }
      StoredGraph stored = readStoredGraph(provider, world.getUID());
      if (stored != null) {
        activateStored(stored, stored.build(), null);
      }
    }
  }

  /**
   * 运行时从库重载一个世界的调度图（牌子签名恢复一致后）。
   *
   * <p>读库仍在调用线程（主线程）上做。设了后台执行器（{@link #setBackgroundExecutor}）且给了主线程执行器时，组装图与切换前准备 （{@link
   * #prepareSnapshot}）交给后台，回到主线程时本世界的快照与失效状态都没变才切换；变过（期间建图命令换了图、牌子又改了等）就放弃这次重载，
   * 以那次变化为准。没有执行器或交不出去时与 {@link #loadFromStorage} 一样同步做。
   *
   * @param mainThread 回到主线程的执行器；{@code null} 时同步做
   * @param afterLoad 处理结束（切换了、放弃了或库里没有可用快照）后在主线程调用
   */
  public void reloadFromStorage(
      StorageProvider provider, World world, Executor mainThread, Runnable afterLoad) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(afterLoad, "afterLoad");
    UUID worldId = world.getUID();
    StoredGraph stored = readStoredGraph(provider, worldId);
    if (stored == null) {
      afterLoad.run();
      return;
    }
    Executor background = backgroundExecutor;
    Executor main = mainThread;
    if (background != null && main != null) {
      RailGraphSnapshot snapshotBefore = snapshots.get(worldId);
      RailGraphStaleState staleBefore = staleStates.get(worldId);
      try {
        background.execute(
            () -> {
              Runnable back;
              try {
                RailGraph graph = stored.build();
                PreparedSnapshot prepared = prepareSnapshot(worldId, graph);
                back =
                    () -> {
                      if (snapshots.get(worldId) == snapshotBefore
                          && staleStates.get(worldId) == staleBefore) {
                        activateStored(stored, graph, prepared);
                      } else {
                        debugLogger.accept("从库重载期间调度图或失效状态有变，放弃这次重载: world=" + worldId);
                      }
                    };
              } catch (Throwable ex) {
                back = () -> debugLogger.accept("从库重载调度图失败: world=" + worldId + " msg=" + ex);
              }
              Runnable finish = back;
              try {
                main.execute(
                    () -> {
                      try {
                        finish.run();
                      } finally {
                        afterLoad.run();
                      }
                    });
              } catch (RuntimeException ex) {
                debugLogger.accept("从库重载调度图回不到主线程，放弃: world=" + worldId);
              }
            });
        return;
      } catch (RuntimeException ex) {
        debugLogger.accept("从库重载调度图交不出后台，改为同步: world=" + worldId);
      }
    }
    activateStored(stored, stored.build(), null);
    afterLoad.run();
  }

  /** 从库里读出的一个世界的调度图记录（签名已核对一致）。 */
  private record StoredGraph(
      UUID worldId,
      java.util.List<RailNodeRecord> nodes,
      java.util.List<RailEdgeRecord> edges,
      Optional<RailInterlockingSnapshotRecord> interlocking,
      Instant builtAt) {

    /** 组装图：只读记录，可在后台线程调用。 */
    RailGraph build() {
      return buildGraphFromRecords(nodes, edges, interlocking);
    }
  }

  private void activateStored(StoredGraph stored, RailGraph graph, PreparedSnapshot prepared) {
    try {
      activateSnapshot(stored.worldId(), graph, stored.builtAt(), true, prepared);
    } catch (IllegalStateException exception) {
      debugLogger.accept(
          "持久化调度图未激活，旧联锁投影保持生效: world=" + stored.worldId() + " msg=" + exception.getMessage());
    }
  }

  /**
   * 读一个世界的运维覆盖、分量限速与调度图记录，并核对节点签名。
   *
   * <p>运维覆盖与分量限速当场换进内存；签名不一致时记失效并移出快照。
   *
   * @return 可以组装并切换的记录；库里没有快照或已失效时为 {@code null}
   */
  private StoredGraph readStoredGraph(StorageProvider provider, UUID worldId) {
    RailNodeRepository nodeRepo = provider.railNodes();
    RailEdgeRepository edgeRepo = provider.railEdges();
    RailEdgeOverrideRepository overrideRepo = provider.railEdgeOverrides();
    RailComponentCautionRepository cautionRepo = provider.railComponentCautions();
    RailGraphSnapshotRepository snapshotRepo = provider.railGraphSnapshots();
    RailInterlockingSnapshotRepository interlockingSnapshotRepo =
        provider.railInterlockingSnapshots();
    try {
      ConcurrentMap<EdgeId, RailEdgeOverrideRecord> overridesById = new ConcurrentHashMap<>();
      for (RailEdgeOverrideRecord override : overrideRepo.listByWorld(worldId)) {
        if (override == null || override.edgeId() == null) {
          continue;
        }
        EdgeId normalized = EdgeId.undirected(override.edgeId().a(), override.edgeId().b());
        overridesById.put(normalized, override);
      }
      synchronized (edgeOverrideLock) {
        if (!overridesById.isEmpty()) {
          edgeOverrides.put(worldId, overridesById);
        } else {
          edgeOverrides.remove(worldId);
        }
        edgeOverrideSnapshots.remove(worldId);
        snapshotVersion.incrementAndGet();
      }
    } catch (Exception ex) {
      debugLogger.accept("读取 rail_edge_overrides 失败: world=" + worldId + " msg=" + ex.getMessage());
    }

    try {
      ConcurrentMap<String, RailComponentCautionRecord> byKey = new ConcurrentHashMap<>();
      for (RailComponentCautionRecord record : cautionRepo.listByWorld(worldId)) {
        if (record == null || record.componentKey() == null || record.componentKey().isBlank()) {
          continue;
        }
        byKey.put(record.componentKey(), record);
      }
      if (!byKey.isEmpty()) {
        componentCautions.put(worldId, byKey);
      } else {
        componentCautions.remove(worldId);
      }
    } catch (Exception ex) {
      debugLogger.accept(
          "读取 rail_component_cautions 失败: world=" + worldId + " msg=" + ex.getMessage());
    }

    Optional<RailGraphSnapshotRecord> snapshotOpt = snapshotRepo.findByWorld(worldId);
    if (snapshotOpt.isEmpty()) {
      return null;
    }
    RailGraphSnapshotRecord snapshot = snapshotOpt.get();
    java.util.List<RailNodeRecord> nodeRecords = nodeRepo.listByWorld(worldId);
    String currentSignature = RailGraphSignature.signatureForNodes(nodeRecords);
    if (snapshot.nodeSignature().isEmpty()
        && !currentSignature.isEmpty()
        && snapshot.nodeCount() != nodeRecords.size()) {
      staleStates.put(
          worldId,
          new RailGraphStaleState(
              snapshot.builtAt(),
              snapshot.nodeSignature(),
              currentSignature,
              snapshot.nodeCount(),
              snapshot.edgeCount(),
              nodeRecords.size()));
      snapshots.remove(worldId);
      snapshotVersion.incrementAndGet();
      return null;
    }

    if (!snapshot.nodeSignature().isEmpty()
        && !currentSignature.isEmpty()
        && !snapshot.nodeSignature().equals(currentSignature)) {
      staleStates.put(
          worldId,
          new RailGraphStaleState(
              snapshot.builtAt(),
              snapshot.nodeSignature(),
              currentSignature,
              snapshot.nodeCount(),
              snapshot.edgeCount(),
              nodeRecords.size()));
      snapshots.remove(worldId);
      snapshotVersion.incrementAndGet();
      return null;
    }

    java.util.List<RailEdgeRecord> edgeRecords = edgeRepo.listByWorld(worldId);
    Optional<RailInterlockingSnapshotRecord> interlockingSnapshot = Optional.empty();
    try {
      interlockingSnapshot = interlockingSnapshotRepo.findByWorld(worldId);
    } catch (Exception ex) {
      debugLogger.accept(
          "读取 rail_interlocking_snapshots 失败，已按 fail-closed 加载: world="
              + worldId
              + " msg="
              + ex.getMessage());
    }
    if (snapshot.nodeSignature().isEmpty() && !currentSignature.isEmpty()) {
      RailGraphSnapshotRecord updated =
          new RailGraphSnapshotRecord(
              worldId,
              snapshot.builtAt(),
              snapshot.nodeCount(),
              snapshot.edgeCount(),
              currentSignature);
      try {
        snapshotRepo.save(updated);
        snapshot = updated;
      } catch (Exception ex) {
        debugLogger.accept(
            "写入 rail_graph_snapshots.node_signature 失败: world="
                + worldId
                + " msg="
                + ex.getMessage());
      }
    }
    return new StoredGraph(
        worldId, nodeRecords, edgeRecords, interlockingSnapshot, snapshot.builtAt());
  }

  /**
   * 从存储记录还原一张 {@link RailGraph}。
   *
   * <p>注意：该方法不会进行“签名一致性”校验；调用方需自行决定是否信任输入数据。
   */
  public static RailGraph buildGraphFromRecords(
      java.util.List<RailNodeRecord> nodeRecords, java.util.List<RailEdgeRecord> edgeRecords) {
    return buildGraphFromRecords(nodeRecords, edgeRecords, Optional.empty());
  }

  /**
   * 从纯 Node/Edge 记录与独立的稀疏联锁快照还原图。
   *
   * <p>快照缺失、Edge 签名不一致或 Zone 引用损坏时，图仍可用于诊断，但全部 Edge 会投影同一个 incomplete sentinel，运行授权保持 fail-closed。
   */
  public static RailGraph buildGraphFromRecords(
      java.util.List<RailNodeRecord> nodeRecords,
      java.util.List<RailEdgeRecord> edgeRecords,
      Optional<RailInterlockingSnapshotRecord> interlockingSnapshot) {
    Objects.requireNonNull(nodeRecords, "nodeRecords");
    Objects.requireNonNull(edgeRecords, "edgeRecords");
    Objects.requireNonNull(interlockingSnapshot, "interlockingSnapshot");
    Map<org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId, RailNode> nodesById = new HashMap<>();
    for (RailNodeRecord node : nodeRecords) {
      SignRailNode railNode =
          new SignRailNode(
              node.nodeId(),
              node.nodeType(),
              new Vector(node.x(), node.y(), node.z()),
              node.trainCartsDestination(),
              node.waypointMetadata(),
              node.doorDirection());
      nodesById.put(railNode.id(), railNode);
    }

    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    for (RailEdgeRecord edge : edgeRecords) {
      EdgeId edgeId = edge.edgeId();
      RailNode a = nodesById.get(edgeId.a());
      RailNode b = nodesById.get(edgeId.b());
      if (a == null || b == null) {
        continue;
      }
      RailEdge railEdge =
          new RailEdge(
              edgeId,
              edgeId.a(),
              edgeId.b(),
              edge.lengthBlocks(),
              edge.baseSpeedLimit(),
              edge.bidirectional(),
              Optional.of(new RailEdgeMetadata(a.waypointMetadata(), b.waypointMetadata())));
      edgesById.put(edgeId, railEdge);
    }

    // 库里带逐边足迹时，用**完整构建**那条路径重建联锁状态——只有它会建出 cell→edge 反向索引。
    //
    // 从 Zone 快照恢复（restoreInterlockingState）按设计只有 Zone、没有逐边足迹，
    // 索引必然为空、cellCoverageAvailable() 为假，于是一切以实测覆盖为放行条件的机制
    // （尾部保护释放 / Phase 4）全部 fail-closed 到一个都不放。
    //
    // 只有**当真有足迹**时才走这条；否则保持原路径，行为一字不变。
    java.util.Map<EdgeId, RailEdgeFootprint> footprintsByEdge = new HashMap<>();
    for (RailEdgeRecord record : edgeRecords) {
      if (record == null || record.edgeId() == null || record.footprintCells().isEmpty()) {
        continue;
      }
      footprintsByEdge.put(
          record.edgeId(),
          new RailEdgeFootprint(
              RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, record.footprintCells()));
    }
    RailInterlockingState interlockingState =
        resolveWorldId(nodeRecords, edgeRecords)
            .map(
                worldId ->
                    footprintsByEdge.isEmpty()
                        ? restoreInterlockingState(
                            worldId, edgesById.keySet(), interlockingSnapshot)
                        : restoreFromFootprints(
                            worldId, edgesById.keySet(), footprintsByEdge, interlockingSnapshot))
            .orElseGet(RailInterlockingState::unavailable);
    return new SimpleRailGraph(nodesById, edgesById, java.util.Set.of(), interlockingState);
  }

  /**
   * 带逐边足迹的还原：足迹齐全也不能自己证明完整——局部合并、刷新、局部删除会保留全部足迹但按不完整发布。完整与否以持久化快照为准，
   * 快照缺失、不匹配或记为不完整时按不完整发布（足迹仍留在索引里）。
   */
  private static RailInterlockingState restoreFromFootprints(
      UUID worldId,
      java.util.Set<EdgeId> expectedEdges,
      Map<EdgeId, RailEdgeFootprint> footprintsByEdge,
      Optional<RailInterlockingSnapshotRecord> snapshotOpt) {
    RailInterlockingState state =
        RailInterlockingState.from(worldId, expectedEdges, footprintsByEdge);
    boolean persistedComplete =
        snapshotOpt
            .filter(snapshot -> worldId.equals(snapshot.worldId()))
            .filter(
                snapshot ->
                    snapshot.formatVersion()
                        == RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION)
            .filter(
                snapshot ->
                    RailInterlockingEdgeSignature.of(expectedEdges)
                        .equals(snapshot.edgeSignature()))
            .map(snapshot -> snapshot.coverage().complete())
            .orElse(false);
    return persistedComplete ? state : state.withCoverageMarkedIncomplete();
  }

  private static RailInterlockingState restoreInterlockingState(
      UUID worldId,
      java.util.Set<EdgeId> expectedEdges,
      Optional<RailInterlockingSnapshotRecord> snapshotOpt) {
    if (snapshotOpt.isEmpty()) {
      return RailInterlockingState.incomplete(worldId, expectedEdges);
    }
    RailInterlockingSnapshotRecord snapshot = snapshotOpt.get();
    String currentSignature = RailInterlockingEdgeSignature.of(expectedEdges);
    if (!worldId.equals(snapshot.worldId())
        || snapshot.formatVersion() != RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION
        || !currentSignature.equals(snapshot.edgeSignature())) {
      return RailInterlockingState.incomplete(worldId, expectedEdges);
    }
    try {
      return RailInterlockingState.fromSnapshot(
          worldId, expectedEdges, snapshot.coverage(), snapshot.zones());
    } catch (IllegalArgumentException exception) {
      return RailInterlockingState.incomplete(worldId, expectedEdges);
    }
  }

  private static Optional<UUID> resolveWorldId(
      java.util.List<RailNodeRecord> nodeRecords, java.util.List<RailEdgeRecord> edgeRecords) {
    if (!edgeRecords.isEmpty()) {
      return Optional.of(edgeRecords.get(0).worldId());
    }
    if (!nodeRecords.isEmpty()) {
      return Optional.of(nodeRecords.get(0).worldId());
    }
    return Optional.empty();
  }

  public record RailGraphSnapshot(RailGraph graph, Instant builtAt) {
    public RailGraphSnapshot {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(builtAt, "builtAt");
    }
  }

  /** {@link #prepareSnapshot} 的结果：派生索引已建好的新图，跨世界开启时附带路网候选。交给带它的切换入口。 */
  public static final class PreparedSnapshot {

    private final RailGraph graph;
    private final RailNetworkCache.Prebuilt network;
    private final org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry links;

    private PreparedSnapshot(
        RailGraph graph,
        RailNetworkCache.Prebuilt network,
        org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry links) {
      this.graph = graph;
      this.network = network;
      this.links = links;
    }

    /** 将要切换上去的图。 */
    public RailGraph graph() {
      return graph;
    }

    /** 附带的路网候选；跨世界未开启或没有传送门连接时为 {@code null}。只供测试核对切换时是否原样换上。 */
    org.fetarute.fetaruteTCAddon.dispatcher.graph.network.RailNetwork network() {
      return network == null ? null : network.network();
    }
  }

  /**
   * 快照已失效：节点集合（签名）与当前 rail_nodes 不一致，旧图应提示重建。
   *
   * @param snapshotRetained 旧图是否继续在用（变化的节点没有交路在用时保留）
   */
  public record RailGraphStaleState(
      Instant builtAt,
      String snapshotSignature,
      String currentSignature,
      int snapshotNodeCount,
      int snapshotEdgeCount,
      int currentNodeCount,
      boolean snapshotRetained) {
    public RailGraphStaleState {
      Objects.requireNonNull(builtAt, "builtAt");
      snapshotSignature = snapshotSignature == null ? "" : snapshotSignature;
      currentSignature = currentSignature == null ? "" : currentSignature;
    }

    /** 旧图已移出内存的失效状态。 */
    public RailGraphStaleState(
        Instant builtAt,
        String snapshotSignature,
        String currentSignature,
        int snapshotNodeCount,
        int snapshotEdgeCount,
        int currentNodeCount) {
      this(
          builtAt,
          snapshotSignature,
          currentSignature,
          snapshotNodeCount,
          snapshotEdgeCount,
          currentNodeCount,
          false);
    }

    RailGraphStaleState withSnapshotRetained(boolean retained) {
      return retained == snapshotRetained
          ? this
          : new RailGraphStaleState(
              builtAt,
              snapshotSignature,
              currentSignature,
              snapshotNodeCount,
              snapshotEdgeCount,
              currentNodeCount,
              retained);
    }
  }
}
