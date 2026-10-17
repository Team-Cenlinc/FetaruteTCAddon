package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.TCConfig;
import com.bergerkiller.bukkit.tc.actions.GroupAction;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DynamicPlatformAllocator;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopCoordinator;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ShuntingRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CoLocationPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

/**
 * 联挂指令 {@code COPL}（见 {@link RouteCouplingDirectives}）：回程支线车与主干在联挂站挂成一列，沿用主干的身份。
 *
 * <p>先到的车在站台等，后到的车开进同一条股道挂上。按表运行时回程支线车先到（编表让主干等它）；主干先到时同一套，只是对调。
 *
 * <ol>
 *   <li>等：回程支线车在联挂站终到、主干会来时扣住它（不被别的票接走、不被回收）——按表时等表上那一班主干，否则在 WAIT 内等任何一列开往本站的主干；
 *       主干先到、回程支线车会来时，主干停站扣留最多 WAIT（不超过发车扣留上限）。
 *   <li>选台：后到的车遇 DYNAMIC 选对方停着的那条股道，对方的占用与预订对它不算（{@link
 *       DynamicPlatformAllocator.CouplingPartners}）。
 *   <li>站外：后到的车照常被对方的占用挡在站台外，停稳一秒、在对方 {@code approach-max-blocks} 以内、驶向对方那条股道、合并后不超节数上限，才开始进站。
 *       车上有驾驶员时先请他让出控制（{@link CoupleCrew}），挂上后再交还。
 *   <li>进站：后到的车登记调车中（调度让出控制），这一对之间的占用互相不挡（{@link CoLocationPolicy}），调度据此把进路排进站台；授权进了对方的资源后，
 *       由本类的动作以进站速度推它，沿轨道看得见对方后最后几格按推车速度，空隙不超过联挂距离停车（{@link ApproachRules}）。
 *   <li>挂上：主干保留身份（{@link CouplerService#linkInService}）。回程支线车先到时，挂上后对合并的列车重新触发一次那一站的车站牌子，
 *       照常记到站、开门、停站、发车（这一站不再多停 {@code couple-seconds}：进站已经用掉了）。
 *   <li>退路：没开动就放弃时下一拍可再试；开动后失败时两车登记成同股道列车对（背离对方的一方先走），双方的等候都放掉，各走各的。
 * </ol>
 *
 * <p>只在服务器主线程使用（到站回调、选台、占用判定与票据派发都在主线程）。
 */
public final class CoupleService {

  /** 每拍间隔（tick）。 */
  private static final long TICK_PERIOD = 2L;

  /** 后到的车停稳多久才开始进站（tick）。 */
  private static final int STILL_TICKS = 20;

  /** 进站：等调度把进路排进对方资源的上限（tick）。 */
  private static final int CLAIM_TICKS = 200;

  /** 没开动就放弃后隔多久再试。 */
  private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

  /** 按表时回程支线车等主干的上限（主干晚太多时时刻表自己会把它换下）。 */
  private static final Duration TIMETABLE_FEEDER_CAP = Duration.ofMinutes(15);

  /** 进站进行中对方的发车扣留每次续多久。 */
  private static final Duration APPROACH_HOLD = Duration.ofSeconds(30);

  /** 停车后最多等多少拍再去挂。 */
  private static final int SETTLE_ROUNDS = 10;

  /** 沿轨道找对方最多看多远（格）。 */
  private static final double SIGHT_BLOCKS = 48.0;

  /**
   * 回程支线车在联挂站等主干。
   *
   * @param name 回程支线车
   * @param platform 它停着的股道
   * @param joins 它可以并进的联挂
   * @param trunk 按表时只等这辆主干；否则为空（任何开往本站的主干）
   * @param deadline 等到这时放手
   */
  private record FeederHold(
      String name,
      NodeId platform,
      List<CouplingIndex.Join> joins,
      Optional<String> trunk,
      Instant deadline) {}

  /**
   * 主干先到、在联挂站等回程支线车。
   *
   * @param name 主干
   * @param join 这一处联挂
   * @param platform 主干停着的股道；到站进度还没记下时为空
   * @param feeder 按表时只等这辆回程支线车；否则为空
   * @param deadline 扣留到这时
   */
  private record TrunkWait(
      String name,
      CouplingIndex.Join join,
      Optional<NodeId> platform,
      Optional<String> feeder,
      Instant deadline) {

    TrunkWait withPlatform(NodeId node) {
      return new TrunkWait(name, join, Optional.of(node), feeder, deadline);
    }
  }

  /** 挂上后重新触发到站时，这一站不再多停联挂加时。 */
  private record CoupledAt(UUID route, int index) {}

  private enum Phase {
    CLAIMING,
    PUSHING,
    SETTLING
  }

  /** 一次进站。 */
  private static final class Approach {
    final String moverName;
    final String standingName;
    final String trunkName;
    final String feederName;
    final boolean feederFirst;
    final CouplingIndex.Join join;
    final NodeId platform;
    final MinecartGroup mover;
    final MinecartGroup standing;
    final MinecartMember<?> lead;
    final CouplerState.End leadEnd;
    final Set<String> controlNames;
    final double budget;
    final Vector standingAnchor;
    Phase phase = Phase.CLAIMING;
    int ticks;
    int pushTicks;
    Vector start;
    double savedSpeedLimit = Double.NaN;
    OptionalDouble lastGap = OptionalDouble.empty();
    MinecartMember<?> target;
    ApproachRules.Step result = ApproachRules.Step.CONTINUE;
    boolean moved;
    int settleRounds;
    GroupAction action;

    Approach(
        String moverName,
        String standingName,
        boolean feederFirst,
        CouplingIndex.Join join,
        NodeId platform,
        MinecartGroup mover,
        MinecartGroup standing,
        MinecartMember<?> lead,
        CouplerState.End leadEnd,
        Set<String> controlNames,
        double budget,
        Vector standingAnchor) {
      this.moverName = moverName;
      this.standingName = standingName;
      this.feederFirst = feederFirst;
      this.trunkName = feederFirst ? moverName : standingName;
      this.feederName = feederFirst ? standingName : moverName;
      this.join = join;
      this.platform = platform;
      this.mover = mover;
      this.standing = standing;
      this.lead = lead;
      this.leadEnd = leadEnd;
      this.controlNames = controlNames;
      this.budget = budget;
      this.standingAnchor = standingAnchor;
    }
  }

  private final FetaruteTCAddon plugin;
  private final CouplerService coupler;

  private volatile CouplingIndex index = CouplingIndex.EMPTY;
  private volatile boolean indexDirty = true;
  private RouteDefinitionCache indexedCache;

  private final Map<String, FeederHold> feeders = new ConcurrentHashMap<>();
  private final Map<String, TrunkWait> trunks = new ConcurrentHashMap<>();

  /** 后到的车（车名键，调度名与 TrainCarts 名都记）→ 进站。 */
  private final Map<String, Approach> approaches = new ConcurrentHashMap<>();

  /** 主干车名键 → 挂上的那一站。 */
  private final Map<String, CoupledAt> coupledAt = new ConcurrentHashMap<>();

  /** 车名键 → 这一站因联挂多停的秒数：车站停站时 AutoStation 取走。 */
  private final Map<String, Integer> stationDwell = new ConcurrentHashMap<>();

  /** 车名键 → 停稳的 tick 数（后到的车）。 */
  private final Map<String, Integer> stillTicks = new ConcurrentHashMap<>();

  /** 一对车（“后到|先到”）→ 不早于这时再试进站。 */
  private final Map<String, Instant> retryAfter = new ConcurrentHashMap<>();

  /** 主干（车名键|交路|下标）→ 选台等回程支线车等到几点。 */
  private final Map<String, Instant> deferredUntil = new ConcurrentHashMap<>();

  private BukkitTask task;

  /** 两车上值乘的人；驾驶模块接入。 */
  private volatile CoupleCrew crew = CoupleCrew.NONE;

  CoupleService(FetaruteTCAddon plugin, CouplerService coupler) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.coupler = Objects.requireNonNull(coupler, "coupler");
  }

  /** 接入值乘的人（驾驶员、车掌）；{@code null} 恢复为没有。 */
  public void setCrew(CoupleCrew next) {
    this.crew = next == null ? CoupleCrew.NONE : next;
  }

  void start() {
    if (task == null) {
      task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
    }
    indexDirty = true;
  }

  void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
    for (Approach approach : List.copyOf(distinctApproaches())) {
      fail(approach, "plugin-stop");
    }
    feeders.clear();
    trunks.clear();
    coupledAt.clear();
    stationDwell.clear();
    stillTicks.clear();
    retryAfter.clear();
    deferredUntil.clear();
  }

  // ---------------------------------------------------------------- 到站

  /**
   * 列车到达或经过交路第 {@code index} 个节点（与换模器、解编并在同一个交路节点监听上）。主干正常到了联挂站（没在进站时挂上）：多停联挂加时， 回程支线车会来时等它。
   *
   * @return 这一站因联挂要多停的秒数
   */
  public int onRouteNode(
      String trainName, TrainProperties properties, RouteId route, int index, boolean dwelling) {
    if (!dwelling || trainName == null || route == null) {
      return 0;
    }
    Optional<UUID> routeId =
        plugin.getRouteDefinitionCache().flatMap(cache -> cache.findUuid(route));
    Optional<CouplingIndex.Join> join =
        routeId.flatMap(id -> currentIndex().atTrunkStop(id, index));
    if (join.isEmpty()) {
      return 0;
    }
    String key = key(trainName);
    CoupledAt coupled = coupledAt.remove(key);
    if (coupled != null && coupled.route().equals(routeId.get()) && coupled.index() == index) {
      return 0;
    }
    // 回程支线车在别的股道等它：这一班挂不上了，放它走
    feeders
        .entrySet()
        .removeIf(
            entry ->
                entry.getValue().joins().contains(join.get())
                    && entry.getValue().trunk().map(t -> sameTrain(t, trainName)).orElse(true)
                    && !participating(entry.getKey()));
    expectFeeder(trainName, join.get());
    int seconds = RouteCouplingDirectives.coupleSeconds();
    if (plugin
        .getRouteDefinitionCache()
        .map(cache -> cache.listStops(route))
        .filter(stops -> index < stops.size())
        .map(stops -> RouteTerminals.isStationStop(stops.get(index)))
        .orElse(false)) {
      stationDwell.put(key, seconds);
    }
    return seconds;
  }

  /** 主干先到：回程支线车会来就扣着主干等它（最多 WAIT，不超过发车扣留上限）。 */
  private void expectFeeder(String trunkName, CouplingIndex.Join join) {
    Optional<TimetableService.JoinPlan> plan =
        plugin.getTimetableService().map(service -> service.joinPlanOf(trunkName));
    Optional<String> feeder = Optional.empty();
    if (plan.isPresent() && plan.get().bound()) {
      if (plan.get().feeder().isEmpty() || plan.get().feederTrain().isEmpty()) {
        return;
      }
      feeder = plan.get().feederTrain();
      if (!approachingOnRoute(feeder.get(), join.feederRoute(), join.feederEndIndex())) {
        return;
      }
    } else if (anyApproaching(join.feederRoute(), join.feederEndIndex()).isEmpty()) {
      return;
    }
    long wait = Math.min(waitSeconds(join), StationStopCoordinator.HOLD_CEILING.toSeconds());
    if (wait <= 0) {
      return;
    }
    // 同一站的到站回调可能来不止一次：只在第一次定扣留截止
    TrunkWait previous =
        trunks.putIfAbsent(
            key(trunkName),
            new TrunkWait(
                trunkName, join, Optional.empty(), feeder, Instant.now().plusSeconds(wait)));
    if (previous == null) {
      debug("COPL_TRUNK_WAIT train=" + trunkName + " wait=" + wait + "s");
    }
  }

  /**
   * AutoStation 停站：取走这一站因联挂要多停的秒数（只取一次）。
   *
   * @return 秒数；这一站不联挂时为 0
   */
  public int takeStationDwell(String trainName) {
    Integer seconds = trainName == null ? null : stationDwell.remove(key(trainName));
    return seconds == null ? 0 : seconds;
  }

  // ---------------------------------------------------------------- 对外判定

  /** 这列车正被联挂进站推着：AutoStation 不处理它（挂上后对合并的列车重新触发）。 */
  public boolean approaching(String trainName) {
    return trainName != null && approaches.containsKey(key(trainName));
  }

  /** 联挂的一方正在等或正在进站：闲置回收不收它。 */
  public boolean holds(String trainName) {
    if (trainName == null) {
      return false;
    }
    String key = key(trainName);
    return feeders.containsKey(key) || trunks.containsKey(key) || participating(key);
  }

  /** 等着联挂的回程支线车不接任何票。 */
  boolean allowsLayoverDispatch(String trainName) {
    return trainName == null
        || !(feeders.containsKey(key(trainName)) || participating(key(trainName)));
  }

  /** 停站扣留：主干先到等回程支线车、或对方正开进来时扣着。 */
  public Optional<Instant> couplingHold(String trainName) {
    if (trainName == null) {
      return Optional.empty();
    }
    String key = key(trainName);
    for (Approach approach : distinctApproaches()) {
      if (key(approach.standingName).equals(key)) {
        return Optional.of(Instant.now().plus(APPROACH_HOLD));
      }
    }
    TrunkWait wait = trunks.get(key);
    return wait == null ? Optional.empty() : Optional.of(wait.deadline());
  }

  /** 进站的这一对之间的占用互相不挡。 */
  boolean exempts(OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim) {
    if (approaches.isEmpty() || request == null || claim == null) {
      return false;
    }
    Approach approach = approaches.get(key(request.trainName()));
    if (approach != null && sameTrain(approach.standingName, claim.trainName())) {
      return true;
    }
    for (Approach other : approaches.values()) {
      if (sameTrain(other.standingName, request.trainName())
          && sameTrain(other.moverName, claim.trainName())) {
        return true;
      }
    }
    return false;
  }

  /** 选台：后到的车开进搭档停着的股道。 */
  public DynamicPlatformAllocator.CouplingPartners partners() {
    return new DynamicPlatformAllocator.CouplingPartners() {
      @Override
      public Optional<NodeId> platformOf(String trainName, UUID routeId, int stopIndex) {
        return partnerPlatform(trainName, routeId, stopIndex);
      }

      @Override
      public boolean partner(String trainName, String claimant) {
        return partnerOf(trainName, claimant);
      }

      @Override
      public boolean defer(String trainName, UUID routeId, int stopIndex) {
        return deferForFeeder(trainName, routeId, stopIndex);
      }
    };
  }

  /** 主干（DYNAMIC 停靠）选台时回程支线车还没到、正开往本站：先不选台，在站外等它到了再选它那条股道。最多等 WAIT（不超过发车扣留上限）， 过了照常选台、各走各的。 */
  private boolean deferForFeeder(String trainName, UUID routeId, int stopIndex) {
    if (trainName == null || routeId == null) {
      return false;
    }
    Optional<CouplingIndex.Join> join = currentIndex().atTrunkStop(routeId, stopIndex);
    if (join.isEmpty() || join.get().fixedPlatform()) {
      return false;
    }
    for (FeederHold hold : feeders.values()) {
      if (hold.joins().contains(join.get())
          && hold.trunk().map(t -> sameTrain(t, trainName)).orElse(true)) {
        // 回程支线车已经到了：选它那条股道（platformOf）
        return false;
      }
    }
    String deferKey = key(trainName) + "|" + routeId + "|" + stopIndex;
    Instant now = Instant.now();
    Instant until = deferredUntil.get(deferKey);
    if (until != null) {
      return now.isBefore(until) && feederComing(trainName, join.get());
    }
    if (!feederComing(trainName, join.get())) {
      return false;
    }
    long wait = Math.min(waitSeconds(join.get()), StationStopCoordinator.HOLD_CEILING.toSeconds());
    if (wait <= 0) {
      return false;
    }
    deferredUntil.put(deferKey, now.plusSeconds(wait));
    debug("COPL_TRUNK_DEFER train=" + trainName + " wait=" + wait + "s");
    return true;
  }

  /** 回程支线车正开往联挂站：按表时是表上那一辆，否则任何一列。 */
  private boolean feederComing(String trunkName, CouplingIndex.Join join) {
    Optional<TimetableService.JoinPlan> plan =
        plugin.getTimetableService().map(service -> service.joinPlanOf(trunkName));
    if (plan.isPresent() && plan.get().bound()) {
      return plan.get().feeder().isPresent()
          && plan.get()
              .feederTrain()
              .filter(f -> approachingOnRoute(f, join.feederRoute(), join.feederEndIndex()))
              .isPresent();
    }
    return !anyApproaching(join.feederRoute(), join.feederEndIndex()).isEmpty();
  }

  private Optional<NodeId> partnerPlatform(String trainName, UUID routeId, int stopIndex) {
    if (trainName == null || routeId == null || currentIndex().isEmpty()) {
      return Optional.empty();
    }
    Optional<CouplingIndex.Join> asTrunk = currentIndex().atTrunkStop(routeId, stopIndex);
    if (asTrunk.isPresent()) {
      for (FeederHold hold : feeders.values()) {
        if (hold.joins().contains(asTrunk.get())
            && hold.trunk().map(t -> sameTrain(t, trainName)).orElse(true)) {
          return Optional.of(hold.platform());
        }
      }
    }
    for (CouplingIndex.Join join : currentIndex().ofFeeder(routeId)) {
      if (join.feederEndIndex() != stopIndex) {
        continue;
      }
      for (TrunkWait wait : trunks.values()) {
        if (wait.join().equals(join)
            && wait.platform().isPresent()
            && wait.feeder().map(f -> sameTrain(f, trainName)).orElse(true)) {
          return wait.platform();
        }
      }
      if (join.fixedPlatform()) {
        // 主干停的是写死的股道：回程支线车先停那条，主干到了才挂得上
        return Optional.of(join.stopNode());
      }
    }
    return Optional.empty();
  }

  private boolean partnerOf(String trainName, String claimant) {
    if (trainName == null || claimant == null) {
      return false;
    }
    FeederHold hold = feeders.get(key(claimant));
    if (hold != null) {
      return hold.trunk().isPresent()
          ? sameTrain(hold.trunk().get(), trainName)
          : runningAnyOf(trainName, hold.joins(), true);
    }
    TrunkWait wait = trunks.get(key(claimant));
    if (wait != null) {
      return wait.feeder().isPresent()
          ? sameTrain(wait.feeder().get(), trainName)
          : runningAnyOf(trainName, List.of(wait.join()), false);
    }
    Approach approach = approaches.get(key(trainName));
    return approach != null && sameTrain(approach.standingName, claimant);
  }

  /** 这列车正跑着这些联挂的主干（{@code asTrunk}）或回程支线交路，且还没到联挂站。 */
  private boolean runningAnyOf(String trainName, List<CouplingIndex.Join> joins, boolean asTrunk) {
    Optional<RouteProgressRegistry.RouteProgressEntry> entry =
        plugin.getRouteProgressRegistry().flatMap(registry -> registry.get(trainName));
    if (entry.isEmpty() || entry.get().routeUuid() == null) {
      return false;
    }
    for (CouplingIndex.Join join : joins) {
      UUID route = asTrunk ? join.trunkRoute() : join.feederRoute();
      int target = asTrunk ? join.stopIndex() : join.feederEndIndex();
      if (route.equals(entry.get().routeUuid()) && entry.get().currentIndex() < target) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- 每拍

  void tick() {
    CouplingIndex current = currentIndex();
    if (current.isEmpty() && feeders.isEmpty() && trunks.isEmpty() && approaches.isEmpty()) {
      return;
    }
    Instant now = Instant.now();
    for (Approach approach : List.copyOf(distinctApproaches())) {
      advance(approach);
    }
    holdFeeders(current, now);
    expire(now);
    startApproaches(now);
  }

  private CouplingIndex currentIndex() {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (cache.isEmpty()) {
      return CouplingIndex.EMPTY;
    }
    if (cache.get() != indexedCache) {
      indexedCache = cache.get();
      cache.get().addChangeListener(() -> indexDirty = true);
      indexDirty = true;
    }
    if (indexDirty) {
      indexDirty = false;
      List<CouplingIndex.RouteInfo> routes = new ArrayList<>();
      for (RouteDefinitionCache.RouteEntry entry : cache.get().entries()) {
        routes.add(
            new CouplingIndex.RouteInfo(
                entry.routeId(),
                entry.record().line().id(),
                entry.record().route().code(),
                RouteCouplingDirectives.groupOf(entry.record().route().metadata()),
                entry.stops(),
                entry.definition().waypoints()));
      }
      index = CouplingIndex.build(routes);
    }
    return index;
  }

  /** 回程支线车在联挂站终到、主干会来：扣住它。 */
  private void holdFeeders(CouplingIndex current, Instant now) {
    if (current.isEmpty()) {
      return;
    }
    Optional<LayoverRegistry> registry = plugin.getLayoverRegistry();
    if (registry.isEmpty()) {
      return;
    }
    for (LayoverRegistry.LayoverCandidate candidate : registry.get().snapshot()) {
      String key = key(candidate.trainName());
      if (feeders.containsKey(key)
          || candidate.dispatchAttempt().isPresent()
          || candidate.locationNodeId() == null) {
        continue;
      }
      Optional<UUID> route =
          Optional.ofNullable(candidate.tags())
              .map(tags -> tags.get(RouteProgressRegistry.TAG_ROUTE_ID))
              .flatMap(CoupleService::parseUuid);
      List<CouplingIndex.Join> joins = new ArrayList<>();
      for (CouplingIndex.Join join : current.ofFeeder(route.orElse(null))) {
        if (join.atStation(candidate.locationNodeId())) {
          joins.add(join);
        }
      }
      if (joins.isEmpty()) {
        continue;
      }
      Optional<String> trunk =
          plugin
              .getTimetableService()
              .flatMap(service -> service.joinTrunkOf(candidate.trainName()));
      Optional<FeederHold> hold = Optional.empty();
      if (trunk.isPresent()) {
        if (!trunk.get().isEmpty()) {
          hold =
              Optional.of(
                  new FeederHold(
                      candidate.trainName(),
                      candidate.locationNodeId(),
                      List.copyOf(joins),
                      trunk,
                      now.plus(TIMETABLE_FEEDER_CAP)));
        }
      } else {
        for (CouplingIndex.Join join : joins) {
          if (!anyApproaching(join.trunkRoute(), join.stopIndex()).isEmpty()) {
            long wait = waitSeconds(join);
            if (wait > 0) {
              hold =
                  Optional.of(
                      new FeederHold(
                          candidate.trainName(),
                          candidate.locationNodeId(),
                          List.copyOf(joins),
                          Optional.empty(),
                          now.plusSeconds(wait)));
            }
            break;
          }
        }
      }
      hold.ifPresent(
          held -> {
            feeders.put(key, held);
            debug(
                "COPL_FEEDER_HOLD train="
                    + held.name()
                    + " platform="
                    + held.platform().value()
                    + " trunk="
                    + held.trunk().orElse("*"));
          });
    }
  }

  /** 等到点、车离开了或按表主干不会来了就放手。 */
  private void expire(Instant now) {
    deferredUntil.values().removeIf(until -> now.isAfter(until.plus(Duration.ofMinutes(5))));
    retryAfter.values().removeIf(until -> now.isAfter(until.plus(Duration.ofMinutes(5))));
    Optional<LayoverRegistry> registry = plugin.getLayoverRegistry();
    feeders
        .entrySet()
        .removeIf(
            entry -> {
              if (participating(entry.getKey())) {
                return false;
              }
              FeederHold hold = entry.getValue();
              boolean gone = registry.flatMap(r -> r.get(hold.name())).isEmpty();
              boolean trunkGone =
                  hold.trunk().isPresent()
                      && plugin
                          .getTimetableService()
                          .flatMap(service -> service.joinTrunkOf(hold.name()))
                          .map(String::isEmpty)
                          .orElse(true);
              if (gone || trunkGone || now.isAfter(hold.deadline())) {
                debug(
                    "COPL_FEEDER_RELEASED train="
                        + hold.name()
                        + " reason="
                        + (gone ? "left" : trunkGone ? "trunk-not-coming" : "wait-expired"));
                return true;
              }
              return false;
            });
    for (Map.Entry<String, TrunkWait> entry : List.copyOf(trunks.entrySet())) {
      if (participating(entry.getKey())) {
        continue;
      }
      TrunkWait wait = entry.getValue();
      Optional<RouteProgressRegistry.RouteProgressEntry> progress =
          plugin.getRouteProgressRegistry().flatMap(r -> r.get(wait.name()));
      boolean left =
          progress.isEmpty()
              || !wait.join().trunkRoute().equals(progress.get().routeUuid())
              || progress.get().currentIndex() != wait.join().stopIndex();
      if (left || now.isAfter(wait.deadline())) {
        trunks.remove(entry.getKey(), wait);
        debug(
            "COPL_TRUNK_RELEASED train="
                + wait.name()
                + " reason="
                + (left ? "left" : "wait-expired"));
        continue;
      }
      if (wait.platform().isEmpty()) {
        plugin
            .getRouteProgressRegistry()
            .flatMap(r -> r.arrivalNodeAt(wait.name(), wait.join().stopIndex()))
            .ifPresent(node -> trunks.replace(entry.getKey(), wait, wait.withPlatform(node)));
      }
    }
  }

  /** 后到的车停在站外、驶向对方的股道：开始进站。 */
  private void startApproaches(Instant now) {
    for (FeederHold hold : List.copyOf(feeders.values())) {
      if (participating(key(hold.name()))) {
        continue;
      }
      Optional<MinecartGroup> standing = group(hold.name());
      if (standing.isEmpty() || standing.get().isMoving()) {
        continue;
      }
      for (String trunk : trunkCandidates(hold)) {
        Optional<CouplingIndex.Join> join = joinOfTrunk(trunk, hold.joins());
        if (join.isPresent()
            && tryStart(
                trunk,
                hold.name(),
                standing.get(),
                join.get(),
                hold.platform(),
                join.get().trunkRoute(),
                join.get().stopIndex(),
                true,
                now)) {
          break;
        }
      }
    }
    for (TrunkWait wait : List.copyOf(trunks.values())) {
      if (wait.platform().isEmpty() || participating(key(wait.name()))) {
        continue;
      }
      Optional<MinecartGroup> standing = group(wait.name());
      if (standing.isEmpty() || standing.get().isMoving()) {
        continue;
      }
      List<String> candidates =
          wait.feeder().isPresent()
              ? List.of(wait.feeder().get())
              : anyApproaching(wait.join().feederRoute(), wait.join().feederEndIndex());
      for (String feeder : candidates) {
        if (tryStart(
            feeder,
            wait.name(),
            standing.get(),
            wait.join(),
            wait.platform().get(),
            wait.join().feederRoute(),
            wait.join().feederEndIndex(),
            false,
            now)) {
          break;
        }
      }
    }
  }

  private List<String> trunkCandidates(FeederHold hold) {
    if (hold.trunk().isPresent()) {
      return List.of(hold.trunk().get());
    }
    List<String> out = new ArrayList<>();
    for (CouplingIndex.Join join : hold.joins()) {
      out.addAll(anyApproaching(join.trunkRoute(), join.stopIndex()));
    }
    return out;
  }

  private Optional<CouplingIndex.Join> joinOfTrunk(String trunk, List<CouplingIndex.Join> joins) {
    Optional<RouteProgressRegistry.RouteProgressEntry> entry =
        plugin.getRouteProgressRegistry().flatMap(registry -> registry.get(trunk));
    if (entry.isEmpty()) {
      return Optional.empty();
    }
    for (CouplingIndex.Join join : joins) {
      if (join.trunkRoute().equals(entry.get().routeUuid())) {
        return Optional.of(join);
      }
    }
    return Optional.empty();
  }

  /**
   * 后到的车够不够条件开进去；够就开始进站。
   *
   * @return 开始了
   */
  private boolean tryStart(
      String moverName,
      String standingName,
      MinecartGroup standing,
      CouplingIndex.Join join,
      NodeId platform,
      UUID moverRoute,
      int targetIndex,
      boolean feederFirst,
      Instant now) {
    String moverKey = key(moverName);
    if (participating(moverKey)) {
      return false;
    }
    Instant retry = retryAfter.get(moverKey + "|" + key(standingName));
    if (retry != null && now.isBefore(retry)) {
      return false;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entry =
        plugin.getRouteProgressRegistry().flatMap(registry -> registry.get(moverName));
    Optional<MinecartGroup> mover = group(moverName);
    if (entry.isEmpty()
        || !moverRoute.equals(entry.get().routeUuid())
        || entry.get().currentIndex() >= targetIndex
        || mover.isEmpty()
        || !mover.get().isValid()
        || mover.get() == standing) {
      stillTicks.remove(moverKey);
      return false;
    }
    if (mover.get().isMoving()) {
      stillTicks.remove(moverKey);
      return false;
    }
    int still = stillTicks.merge(moverKey, (int) TICK_PERIOD, Integer::sum);
    if (still < STILL_TICKS) {
      return false;
    }
    if (ShuntingRegistry.isShunting(moverName) || !headingTo(mover.get(), entry.get(), platform)) {
      return false;
    }
    int maxCars = coupler.settings().maxCars();
    int merged = mover.get().size() + standing.size();
    if ((maxCars > 0 && merged > maxCars)
        || (TCConfig.maxCartsPerTrain >= 0 && merged > TCConfig.maxCartsPerTrain)) {
      warn("COPL_SKIPPED mover=" + moverName + " standing=" + standingName + " reason=too-long");
      retryAfter.put(moverKey + "|" + key(standingName), now.plus(Duration.ofMinutes(5)));
      return false;
    }
    OptionalDouble distance = graphDistance(mover.get(), entry.get(), platform);
    double limit = coupler.settings().approachMaxBlocks();
    if (distance.isEmpty() || distance.getAsDouble() > limit) {
      return false;
    }
    Optional<CouplerState.End> leadEnd = leadEnd(mover.get());
    Optional<Vector> standingCenter = CouplerService.center(standing);
    Optional<Vector> leadCenter = CarEnds.center(mover.get().head());
    Optional<Vector> forward = CarEnds.forward(mover.get().head());
    if (leadEnd.isEmpty()
        || standingCenter.isEmpty()
        || leadCenter.isEmpty()
        || forward.isEmpty()
        || CarEnds.outward(forward.get(), leadEnd.get())
                .dot(standingCenter.get().clone().subtract(leadCenter.get()))
            <= 0.0) {
      // 车头不朝着对方（折返后还没调头等）：不推
      return false;
    }
    // 进站与挂车由调度控车：后到的车上的驾驶员先在这里结算、让出控制，回程支线车上的值乘先结束（挂上后它的身份作废）
    CoupleCrew.Approach crewed =
        new CoupleCrew.Approach(
            feederFirst ? moverName : standingName,
            feederFirst ? standingName : moverName,
            moverName);
    crew.yieldBeforeApproach(crewed);
    if (plugin.getControlAuthority().hasDriver(moverName)) {
      return false;
    }
    Set<String> names = coupler.controlNames(mover.get());
    names.add(moverName);
    Approach approach =
        new Approach(
            moverName,
            standingName,
            feederFirst,
            join,
            platform,
            mover.get(),
            standing,
            mover.get().head(),
            leadEnd.get(),
            names,
            distance.getAsDouble() + bodyLength(standing) + 8.0,
            standingCenter.get());
    for (String name : names) {
      approaches.put(key(name), approach);
      ShuntingRegistry.begin(name);
    }
    stillTicks.remove(moverKey);
    debug(
        "COPL_APPROACH_START mover="
            + moverName
            + " standing="
            + standingName
            + " platform="
            + platform.value()
            + " distance="
            + Math.round(distance.getAsDouble()));
    // 让调度立即按新的豁免重排进路
    plugin.getRuntimeDispatchService().ifPresent(dispatch -> dispatch.refreshSignal(mover.get()));
    return true;
  }

  /** 后到的车正驶向对方的股道：交路下一个目标或 TrainCarts 目的地就是它。 */
  private boolean headingTo(
      MinecartGroup group, RouteProgressRegistry.RouteProgressEntry entry, NodeId platform) {
    if (entry.nextTarget().filter(platform::equals).isPresent()) {
      return true;
    }
    String destination = group.getProperties().getDestination();
    String expected =
        plugin
            .getSignNodeRegistry()
            .findByNodeId(platform, null)
            .map(info -> info.definition().trainCartsDestination().orElse(platform.value()))
            .orElse(platform.value());
    return destination != null && destination.equalsIgnoreCase(expected);
  }

  /** 后到的车最后经过的图节点到对方股道的距离（格）。 */
  private OptionalDouble graphDistance(
      MinecartGroup group, RouteProgressRegistry.RouteProgressEntry entry, NodeId platform) {
    RailGraphService graphs = plugin.getRailGraphService();
    if (graphs == null || group.getWorld() == null || entry.lastPassedGraphNode().isEmpty()) {
      return OptionalDouble.empty();
    }
    NodeId from = entry.lastPassedGraphNode().get();
    if (from.equals(platform)) {
      return OptionalDouble.of(0.0);
    }
    Optional<RailGraphPath> path =
        graphs
            .getSnapshot(group.getWorld().getUID())
            .flatMap(
                snapshot ->
                    new RailGraphPathFinder()
                        .shortestPath(
                            snapshot.graph(),
                            from,
                            platform,
                            RailGraphPathFinder.Options.shortestDistance()));
    return path.map(p -> OptionalDouble.of(p.totalLengthBlocks())).orElse(OptionalDouble.empty());
  }

  /** 列车车身长度（格）：各节车长加车钩。 */
  private static double bodyLength(MinecartGroup group) {
    double length = 0.0;
    for (MinecartMember<?> member : group) {
      length += 2.0 * (CarEnds.halfLength(member) + CarEnds.couplerLength(member));
    }
    return length;
  }

  /** 车头的外端（行进方向）：多节时为不挨着第二节的那一端，单节时按运动方向。 */
  private static Optional<CouplerState.End> leadEnd(MinecartGroup group) {
    MinecartMember<?> head = group.head();
    if (head == null) {
      return Optional.empty();
    }
    if (group.size() > 1) {
      return CarEnds.facingEnd(head, group.get(1)).map(CouplerState.End::opposite);
    }
    Optional<Vector> forward = CarEnds.forward(head);
    org.bukkit.block.BlockFace face = head.getDirection();
    if (forward.isEmpty() || face == null) {
      return Optional.empty();
    }
    double dot = forward.get().getX() * face.getModX() + forward.get().getZ() * face.getModZ();
    return Optional.of(dot >= 0.0 ? CouplerState.End.FRONT : CouplerState.End.REAR);
  }

  // ---------------------------------------------------------------- 进站

  private void advance(Approach approach) {
    approach.ticks += (int) TICK_PERIOD;
    if (!approach.mover.isValid()
        || !approach.standing.isValid()
        || approach.mover.isUnloaded()
        || approach.standing.isUnloaded()) {
      fail(approach, "train-lost");
      return;
    }
    double creep = coupler.settings().pushSpeedBlocksPerSecond() / 20.0;
    double speed = coupler.settings().approachSpeedBlocksPerSecond() / 20.0;
    if (approach.ticks
        > CLAIM_TICKS + ApproachRules.maxTicks(approach.budget, speed, creep) + 200) {
      // 看门狗：推车动作没跑（被清掉、列车没在更新）也一定收尾
      fail(approach, "overdue");
      return;
    }
    switch (approach.phase) {
      case CLAIMING -> {
        // 调度按豁免把进路排进了对方的资源，或已经排到对方那条股道：开推
        if (!coupler.sharedClaimsOf(approach.moverName, approach.standingName).isEmpty()
            || coupler.claims(approach.moverName, OccupancyResource.forNode(approach.platform))) {
          beginPush(approach);
        } else if (approach.ticks > CLAIM_TICKS) {
          fail(approach, "route-not-granted");
        }
      }
      case PUSHING -> {
        boolean actionGone =
            approach.action == null
                || !approach.mover.getActions().getScheduledActions().contains(approach.action);
        if (actionGone && approach.result == ApproachRules.Step.CONTINUE) {
          halt(approach);
          fail(approach, "push-interrupted");
        }
      }
      case SETTLING -> {
        if (approach.mover.isMoving() && approach.settleRounds++ < SETTLE_ROUNDS) {
          approach.mover.stop();
          return;
        }
        if (approach.result == ApproachRules.Step.ARRIVED) {
          link(approach);
        } else {
          fail(approach, approach.result.name().toLowerCase(Locale.ROOT));
        }
      }
    }
  }

  private void beginPush(Approach approach) {
    MinecartGroup group = approach.mover;
    approach.start = CarEnds.center(approach.lead).orElse(null);
    if (approach.start == null) {
      fail(approach, "no-position");
      return;
    }
    double approachSpeed = coupler.settings().approachSpeedBlocksPerSecond() / 20.0;
    // 调度停车时挂的动作清掉，推车动作才排得到前面；调度让出了控制，不会再补
    group.getActions().clear();
    TrainProperties properties = group.getProperties();
    approach.savedSpeedLimit = properties.getSpeedLimit();
    if (properties.getSpeedLimit() < approachSpeed) {
      properties.setSpeedLimit(approachSpeed);
    }
    approach.phase = Phase.PUSHING;
    approach.action = group.getActions().addAction(new ApproachAction(approach));
    debug("COPL_APPROACH_PUSH mover=" + approach.moverName + " budget=" + approach.budget);
  }

  /** 推车动作的一拍：判到没到，没到就按速度推。 */
  private ApproachRules.Step observe(Approach approach) {
    approach.pushTicks++;
    Optional<Vector> leadAt = CarEnds.center(approach.lead);
    Optional<Vector> standingAt = CouplerService.center(approach.standing);
    if (leadAt.isEmpty() || standingAt.isEmpty()) {
      return ApproachRules.Step.STALLED;
    }
    boolean look =
        approach.pushTicks % 4 == 1
            || (approach.lastGap.isPresent()
                && approach.lastGap.getAsDouble() < ApproachRules.FINAL_BLOCKS * 2);
    boolean other = false;
    if (look) {
      PushTargetFinder.Search search =
          PushTargetFinder.find(approach.lead, approach.leadEnd, SIGHT_BLOCKS);
      if (search.target().isPresent()) {
        MinecartMember<?> hit = search.target().get().member();
        if (hit.getGroup() == approach.standing) {
          approach.target = hit;
          approach.lastGap = OptionalDouble.of(search.target().get().gap());
        } else {
          other = true;
        }
      } else if (search
          .failure()
          .filter(
              f ->
                  f == PushTargetFinder.Failure.MIDDLE_CAR
                      || f == PushTargetFinder.Failure.NO_TRACK)
          .isPresent()) {
        // 先碰到车厢中间，或车头不在轨道上、区块没加载：都不再往前推
        other = true;
      } else {
        approach.lastGap = OptionalDouble.empty();
      }
    }
    double travelled = leadAt.get().distance(approach.start);
    if (travelled > ApproachRules.STALL_BLOCKS) {
      approach.moved = true;
    }
    double creep = coupler.settings().pushSpeedBlocksPerSecond() / 20.0;
    double speed = coupler.settings().approachSpeedBlocksPerSecond() / 20.0;
    return ApproachRules.step(
        new ApproachRules.Observation(
            approach.target == null ? OptionalDouble.empty() : approach.lastGap,
            other,
            travelled,
            approach.budget,
            approach.pushTicks,
            ApproachRules.maxTicks(approach.budget, speed, creep),
            standingAt.get().distance(approach.standingAnchor)));
  }

  private double pushSpeed(Approach approach) {
    double creep = coupler.settings().pushSpeedBlocksPerSecond() / 20.0;
    double speed = coupler.settings().approachSpeedBlocksPerSecond() / 20.0;
    return ApproachRules.speed(approach.lastGap, speed, creep);
  }

  private static void halt(Approach approach) {
    if (approach.mover.isValid()) {
      approach.mover.setForwardForce(0.0);
      approach.mover.stop();
    }
  }

  private void link(Approach approach) {
    restoreControl(approach);
    removeApproach(approach);
    MinecartMember<?> target = approach.target;
    MinecartMember<?> feederEnd = approach.feederFirst ? target : approach.lead;
    MinecartMember<?> trunkEnd = approach.feederFirst ? approach.lead : target;
    Optional<TrainLinker.Result> result =
        target == null ? Optional.empty() : coupler.linkInService(feederEnd, trunkEnd);
    if (result.isEmpty() || result.get().outcome() != TrainLinker.Outcome.LINKED) {
      failAfterRemoval(
          approach,
          "link-" + result.map(r -> r.outcome().name() + ":" + r.detail()).orElse("no-target"));
      return;
    }
    feeders.remove(key(approach.feederName));
    trunks.remove(key(approach.trunkName));
    MinecartGroup merged = result.get().merged().orElse(null);
    warnInfo(
        "COPL_LINKED trunk="
            + approach.trunkName
            + " feeder="
            + approach.feederName
            + " cars="
            + (merged == null ? "?" : String.valueOf(merged.size())));
    CoupleCrew.Approach crewed = crewApproach(approach);
    if (approach.feederFirst && merged != null) {
      // 主干是开进来的：到站还没记。对合并的列车重新触发这一站的车站牌子，照常记到站、开门、停站、发车。
      coupledAt.put(
          key(approach.trunkName),
          new CoupledAt(approach.join.trunkRoute(), approach.join.stopIndex()));
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                arrive(merged, approach.platform);
                // 到站记上之后再交还驾驶：接着驾驶从这一站记起
                crew.coupled(crewed);
              });
    } else {
      Bukkit.getScheduler().runTask(plugin, () -> crew.coupled(crewed));
    }
  }

  private static CoupleCrew.Approach crewApproach(Approach approach) {
    return new CoupleCrew.Approach(approach.trunkName, approach.feederName, approach.moverName);
  }

  /** 对列车重新触发这条股道的车站牌子。 */
  private void arrive(MinecartGroup group, NodeId platform) {
    if (!group.isValid()) {
      return;
    }
    SignNodeRegistry registry = plugin.getSignNodeRegistry();
    Optional<SignNodeRegistry.SignNodeInfo> sign =
        registry == null ? Optional.empty() : registry.findByNodeId(platform, null);
    World world = sign.map(info -> Bukkit.getWorld(info.worldId())).orElse(null);
    if (sign.isEmpty() || world == null) {
      warn("COPL_ARRIVAL_SIGN_MISSING platform=" + platform.value());
      return;
    }
    Block block = world.getBlockAt(sign.get().x(), sign.get().y(), sign.get().z());
    try {
      SignAction.executeAll(
          new SignActionEvent(RailLookup.TrackedSign.forRealSign(block, true, null), group),
          SignActionType.GROUP_ENTER);
    } catch (RuntimeException ex) {
      warn("COPL_ARRIVAL_FAILED platform=" + platform.value() + " error=" + ex);
    }
  }

  private void fail(Approach approach, String reason) {
    halt(approach);
    restoreControl(approach);
    removeApproach(approach);
    failAfterRemoval(approach, reason);
  }

  /** 进站没成：没开动时过一会儿可再试；开动过就让两车登记成同股道列车对、各走各的。 */
  private void failAfterRemoval(Approach approach, String reason) {
    if (approach.mover.isValid() && approach.action != null) {
      approach.mover.getActions().clear();
    }
    if (approach.moved) {
      crew.notCoupled(crewApproach(approach));
      if (approach.mover.isValid()) {
        coupler.settle(approach.mover);
      }
      if (approach.mover.isValid() && approach.standing.isValid()) {
        coupler.pairAfterFailedApproach(
            approach.moverName, approach.mover, approach.standingName, approach.standing);
      }
      feeders.remove(key(approach.feederName));
      trunks.remove(key(approach.trunkName));
    } else {
      retryAfter.put(
          key(approach.moverName) + "|" + key(approach.standingName),
          Instant.now().plus(RETRY_AFTER));
    }
    warn(
        "COPL_APPROACH_FAILED mover="
            + approach.moverName
            + " standing="
            + approach.standingName
            + " reason="
            + reason
            + " moved="
            + approach.moved);
  }

  private static void restoreControl(Approach approach) {
    if (approach.mover.isValid() && !Double.isNaN(approach.savedSpeedLimit)) {
      approach.mover.getProperties().setSpeedLimit(approach.savedSpeedLimit);
      approach.savedSpeedLimit = Double.NaN;
    }
    approach.controlNames.forEach(ShuntingRegistry::end);
  }

  private void removeApproach(Approach approach) {
    approaches.values().removeIf(value -> value == approach);
  }

  private boolean participating(String key) {
    if (approaches.isEmpty()) {
      return false;
    }
    if (approaches.containsKey(key)) {
      return true;
    }
    for (Approach approach : approaches.values()) {
      if (key(approach.standingName).equals(key)) {
        return true;
      }
    }
    return false;
  }

  private List<Approach> distinctApproaches() {
    List<Approach> out = new ArrayList<>();
    for (Approach approach : approaches.values()) {
      if (out.stream().noneMatch(existing -> existing == approach)) {
        out.add(approach);
      }
    }
    return out;
  }

  /** 推车动作：每 tick 判一次到没到，同一 tick 的各小步施加同一前进力。 */
  private final class ApproachAction extends GroupAction {

    private final Approach approach;
    private int observedTick = -1;

    ApproachAction(Approach approach) {
      this.approach = approach;
    }

    @Override
    public boolean update() {
      if (approach.result != ApproachRules.Step.CONTINUE) {
        return true;
      }
      int tick = elapsedTicks();
      if (tick != observedTick) {
        observedTick = tick;
        approach.result = observe(approach);
      }
      if (approach.result != ApproachRules.Step.CONTINUE) {
        getGroup().setForwardForce(0.0);
        getGroup().stop();
        approach.phase = Phase.SETTLING;
        return true;
      }
      getGroup()
          .setForwardForce(pushSpeed(approach) / Math.max(1, getGroup().getUpdateStepCount()));
      return false;
    }
  }

  // ---------------------------------------------------------------- 工具

  private long waitSeconds(CouplingIndex.Join join) {
    return join.waitSeconds().isPresent()
        ? join.waitSeconds().getAsInt()
        : coupler.settings().coupleWaitSeconds();
  }

  /** 正跑着这条交路、还没到第 {@code target} 个节点的车。 */
  private List<String> anyApproaching(UUID route, int target) {
    List<String> out = new ArrayList<>();
    plugin
        .getRouteProgressRegistry()
        .ifPresent(
            registry ->
                registry
                    .snapshot()
                    .values()
                    .forEach(
                        entry -> {
                          if (route.equals(entry.routeUuid()) && entry.currentIndex() < target) {
                            out.add(entry.trainName());
                          }
                        }));
    return out;
  }

  private boolean approachingOnRoute(String trainName, UUID route, int target) {
    return plugin
        .getRouteProgressRegistry()
        .flatMap(registry -> registry.get(trainName))
        .filter(entry -> route.equals(entry.routeUuid()) && entry.currentIndex() < target)
        .isPresent();
  }

  private static Optional<MinecartGroup> group(String trainName) {
    try {
      if (trainName == null || !TrainPropertiesStore.exists(trainName)) {
        return Optional.empty();
      }
      TrainProperties properties = TrainPropertiesStore.get(trainName);
      MinecartGroup group = properties == null ? null : properties.getHolder();
      return group == null || !group.isValid() ? Optional.empty() : Optional.of(group);
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private static Optional<UUID> parseUuid(String raw) {
    try {
      return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  private static boolean sameTrain(String first, String second) {
    return first != null && second != null && TrainNameNormalizer.sameLogicalTrain(first, second);
  }

  private static String key(String trainName) {
    return trainName == null ? "" : TrainNameNormalizer.normalizeKey(trainName);
  }

  private void debug(String message) {
    plugin.getLoggerManager().debug(message);
  }

  private void warn(String message) {
    plugin.getLoggerManager().warn(message);
  }

  private void warnInfo(String message) {
    plugin.getLoggerManager().info(message);
  }
}
