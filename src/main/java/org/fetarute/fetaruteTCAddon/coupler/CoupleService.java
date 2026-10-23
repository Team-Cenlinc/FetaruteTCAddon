package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.TCConfig;
import com.bergerkiller.bukkit.tc.actions.GroupAction;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
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
 *   <li>出库：按表的主干从车库出来就到联挂站、回程支线车已经停在那儿占着出库要走的资源时，只有那辆回程支线车的占用不挡它出库（{@link
 *       #admitDepotTrunk}），出车后登记调车中、停在车库股道上（{@link #holdDepotTrunk}），照上一条从车库进站；进站一直开不了头就放弃这次联挂。
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

  /** 出库去联挂的主干停在车库股道上多久还没开始进站，就放弃这次联挂。 */
  private static final Duration DEPOT_TRUNK_APPROACH_LIMIT = Duration.ofSeconds(45);

  /** 登记了出库、迟迟没出车的主干过多久作废。 */
  private static final Duration DEPOT_TRUNK_ADMIT_TTL = Duration.ofSeconds(30);

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
   * @param scheduled 按表时表上那一班主干；不按表时为空（任何开往本站的主干）
   * @param deadline 等到这时放手
   */
  private record FeederHold(
      String name,
      NodeId platform,
      List<CouplingIndex.Join> joins,
      Optional<TimetableService.JoinTrunk> scheduled,
      Instant deadline) {

    boolean timetabled() {
      return scheduled.isPresent();
    }

    /** 按表时跑那一班的主干车；主干交路还没出车、或不按表时为空。 */
    Optional<String> trunk() {
      return scheduled.flatMap(TimetableService.JoinTrunk::train);
    }

    /** 等的是不是这列主干：按表只认表上那一辆（还没出车时谁都不认），不按表时开往本站的都算。 */
    boolean awaits(String trainName) {
      return scheduled.isEmpty() || trunk().filter(t -> sameTrain(t, trainName)).isPresent();
    }

    FeederHold rescheduled(TimetableService.JoinTrunk next) {
      return new FeederHold(name, platform, joins, Optional.of(next), deadline);
    }
  }

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

  /**
   * 出库去联挂的主干：要挂的回程支线车已经停在联挂站、占着出库要走的资源。
   *
   * @param name 主干（出车用的车名）
   * @param feeder 它要挂的回程支线车
   * @param admittedAt 最近一次出库预检登记的时刻
   * @param heldAt 出车后扣在车库股道上（登记调车中）的时刻；还没出车时为空
   */
  private record DepotTrunk(
      String name, String feeder, Instant admittedAt, Optional<Instant> heldAt) {

    DepotTrunk held(Instant now) {
      return new DepotTrunk(name, feeder, admittedAt, Optional.of(now));
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

    /** 进站的计划进路（调度图上从后到的车最后经过的节点到那条股道的最短路）经过的区间；算不出时为空。 */
    final Set<EdgeId> routeEdges;

    Phase phase = Phase.CLAIMING;
    int ticks;
    int pushTicks;
    Vector start;
    double savedSpeedLimit = Double.NaN;
    OptionalDouble lastGap = OptionalDouble.empty();
    MinecartMember<?> target;
    ApproachRules.Step result = ApproachRules.Step.CONTINUE;
    boolean moved;

    /** 判成挡路时挡着的是什么（日志用）：碰到的列车与车厢，或找车失败的原因。 */
    String obstacle = "-";

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
        Vector standingAnchor,
        Set<EdgeId> routeEdges) {
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
      this.routeEdges = Set.copyOf(routeEdges);
    }
  }

  private final FetaruteTCAddon plugin;
  private final CouplerService coupler;

  private volatile CouplingIndex index = CouplingIndex.EMPTY;
  private volatile boolean indexDirty = true;
  private RouteDefinitionCache indexedCache;

  private final Map<String, FeederHold> feeders = new ConcurrentHashMap<>();
  private final Map<String, TrunkWait> trunks = new ConcurrentHashMap<>();

  /** 出库去联挂的主干（车名键）。 */
  private final Map<String, DepotTrunk> depotTrunks = new ConcurrentHashMap<>();

  /** 这一次联挂已经放弃的回程支线车（车名键 → 车名）：等满了还没挂上，或主干从车库进不了站。离开待命池以前不再扣它。 */
  private final Map<String, String> abandonedFeeders = new ConcurrentHashMap<>();

  /** 后到的车（车名键，调度名与 TrainCarts 名都记）→ 进站。 */
  private final Map<String, Approach> approaches = new ConcurrentHashMap<>();

  /** 主干车名键 → 挂上的那一站。 */
  private final Map<String, CoupledAt> coupledAt = new ConcurrentHashMap<>();

  /** 主干车名键 → 已经挂上的那一站：联挂预测不再写这一站。主干离开这一站（进度变了）或不在了就删。 */
  private final Map<String, CoupledAt> joined = new ConcurrentHashMap<>();

  /** 刚在站台上挂上的列车：挂上后车站牌子再触发时只开一个停站（见 {@link LinkedStops}）。 */
  private final LinkedStops linkedStops = new LinkedStops();

  /** 车名键 → 这一站因联挂多停的秒数：车站停站时 AutoStation 取走。 */
  private final Map<String, Integer> stationDwell = new ConcurrentHashMap<>();

  /** 车名键 → 停稳的 tick 数（后到的车）。 */
  private final Map<String, Integer> stillTicks = new ConcurrentHashMap<>();

  /** 一对车（“后到|先到”）→ 不早于这时再试进站。 */
  private final Map<String, Instant> retryAfter = new ConcurrentHashMap<>();

  /** 主干（车名键|交路|下标）→ 选台等回程支线车等到几点。 */
  private final Map<String, Instant> deferredUntil = new ConcurrentHashMap<>();

  private BukkitTask task;

  /** 联挂预测多久重算一次（拍数）。 */
  private static final int JOIN_FORECAST_ROUNDS = 10;

  private int joinForecastCountdown;

  /** 车名键 → 联挂预测：主线程整份换上，任意线程读（站台屏、公开 API）。 */
  private volatile Map<String, JoinForecast> joinForecasts = Map.of();

  /** 联挂站的站名与 ID（与分行同一口径）。 */
  private final DivisionDestinations stations;

  /** 两车上值乘的人；驾驶模块接入。 */
  private volatile CoupleCrew crew = CoupleCrew.NONE;

  CoupleService(FetaruteTCAddon plugin, CouplerService coupler) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.coupler = Objects.requireNonNull(coupler, "coupler");
    this.stations = new DivisionDestinations(plugin);
  }

  /**
   * 回程支线联挂之后接着跑的主干。
   *
   * @param trunkRoute 主干交路
   * @param joinIndex 联挂站在主干交路里的下标
   */
  public record Continuation(UUID trunkRoute, int joinIndex) {

    public Continuation {
      Objects.requireNonNull(trunkRoute, "trunkRoute");
    }
  }

  /**
   * 这条交路的车联挂之后接着跑哪条主干（乘客看到的去向按主干的终点）：只挂上一条主干时有，不挂或挂上不同的主干时为空。
   *
   * <p>读的是联挂服务在主线程上随交路变化重建的索引，任意线程可调。
   */
  public Optional<Continuation> continuationOf(UUID feederRoute) {
    return index
        .continuationOf(feederRoute)
        .map(join -> new Continuation(join.trunkRoute(), join.stopIndex()));
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
    for (DepotTrunk depot : List.copyOf(depotTrunks.values())) {
      releaseDepotTrunk(depot, "plugin-stop", false);
    }
    abandonedFeeders.clear();
    feeders.clear();
    trunks.clear();
    coupledAt.clear();
    stationDwell.clear();
    stillTicks.clear();
    retryAfter.clear();
    deferredUntil.clear();
    joinForecasts = Map.of();
    joined.clear();
    linkedStops.clear();
  }

  /** 这列车前方（或此刻停着的这一站）的联挂预测；不联挂时为空。任意线程可读。 */
  public Optional<JoinForecast> joinForecast(String trainName) {
    return trainName == null
        ? Optional.empty()
        : Optional.ofNullable(joinForecasts.get(key(trainName)));
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
                    && entry.getValue().awaits(trainName)
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
    if (expectedFeeder(join, plan).isEmpty()) {
      return;
    }
    // 按表只等表上那一辆；不按表时开往本站的回程支线车都算
    Optional<String> feeder =
        plan.filter(TimetableService.JoinPlan::bound)
            .flatMap(TimetableService.JoinPlan::feederTrain);
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

  /**
   * 车站牌子触发时问：这列车是不是刚在这条股道挂上（挂上后 TrainCarts 重新检测牌子、我们重新触发到站都会再触发一次）。只开一个停站：开进来的是主干时第一次停站；
   * 主干先到时它的停站还没放行（发车许可锁还在）就让它继续，已经放行了第一次重新停站；其余都是重复。
   *
   * @param trainName 列车名
   * @param platform 触发的车站节点
   */
  public LinkedStops.Arrival linkedArrival(String trainName, NodeId platform) {
    return trainName == null
        ? LinkedStops.Arrival.NONE
        : linkedStops.take(
            key(trainName),
            platform,
            Bukkit.getCurrentTick(),
            () ->
                plugin
                    .getRuntimeDispatchService()
                    .map(dispatch -> dispatch.hasDepartureGate(trainName))
                    .orElse(false));
  }

  /**
   * 先到联挂站、停在站台上等对方挂上来。
   *
   * @param mergedCars 挂上以后整列几节
   * @param midpointLeadBlocks 先到那列的第一节车厢中心停在整列中点前方多少格（股道上没有停车位置标时按它对准车站牌子，见 {@link JoinSpan}）；量不出时为
   *     {@code NaN}
   */
  public record AwaitedJoin(int mergedCars, double midpointLeadBlocks) {}

  /**
   * 先到联挂站、停在站台上等对方挂上来的车：挂上以后整列几节、停在哪。AutoStation 据此按挂上以后的节数选停车位置标，或没有标志时让整列中点对准车站牌子，在身后给后到的车留出位置
   * （后到的车从后面挂上来，整列的车头就是先到这列的车头）。等不等、等谁与扣车同一口径：回程支线车见 {@link #holdFeeders}，主干见 {@link
   * #expectFeeder}。 挂上以后超过节数上限（挂不上）的不算。
   *
   * @param trainName 列车名
   * @param routeId 这列车此刻的交路
   * @param node 停的车站节点
   * @param own 这列车
   * @return 这一站它不等谁挂上来、或推不出对方几节时为空
   */
  public Optional<AwaitedJoin> awaitedJoin(
      String trainName, UUID routeId, NodeId node, MinecartGroup own) {
    if (trainName == null || routeId == null || node == null || own == null || own.isEmpty()) {
      return Optional.empty();
    }
    int ownCars = own.size();
    Optional<Partner> partner = awaitedPartner(trainName, routeId, node, ownCars);
    if (partner.isEmpty()) {
      return Optional.empty();
    }
    int merged = ownCars + partner.get().cars();
    int maxCars = coupler.settings().maxCars();
    if ((maxCars > 0 && merged > maxCars)
        || (com.bergerkiller.bukkit.tc.TCConfig.maxCartsPerTrain >= 0
            && merged > com.bergerkiller.bukkit.tc.TCConfig.maxCartsPerTrain)) {
      return Optional.empty();
    }
    List<JoinSpan.CarSize> ownSizes = new ArrayList<>();
    for (MinecartMember<?> member : own) {
      ownSizes.add(new JoinSpan.CarSize(CarEnds.halfLength(member), CarEnds.couplerLength(member)));
    }
    List<JoinSpan.CarSize> partnerSizes = new ArrayList<>();
    partner
        .get()
        .group()
        .filter(group -> group.size() == partner.get().cars())
        .ifPresent(
            group -> {
              for (MinecartMember<?> member : group) {
                partnerSizes.add(
                    new JoinSpan.CarSize(
                        CarEnds.halfLength(member), CarEnds.couplerLength(member)));
              }
            });
    return Optional.of(
        new AwaitedJoin(
            merged, JoinSpan.midpointLead(ownSizes, partnerSizes, partner.get().cars())));
  }

  /**
   * 等着挂上来的那一列。
   *
   * @param cars 几节
   * @param group 已经出车时是它（车厢次序不一定从挂上的那一端起，只用来量车长）
   */
  private record Partner(int cars, Optional<MinecartGroup> group) {}

  /** 先到联挂站的车等着挂上来的那一列（见 {@link #awaitedJoin}）。 */
  private Optional<Partner> awaitedPartner(
      String trainName, UUID routeId, NodeId node, int ownCars) {
    CouplingIndex current = currentIndex();
    for (CouplingIndex.Join join : current.ofFeeder(routeId)) {
      if (!join.atStation(node)) {
        continue;
      }
      Optional<TimetableService.JoinTrunk> scheduled =
          plugin.getTimetableService().flatMap(service -> service.joinTrunkOf(trainName));
      Optional<String> trunk;
      if (scheduled.isPresent()) {
        if (!scheduled.get().coming()) {
          return Optional.empty();
        }
        trunk = scheduled.get().train();
      } else {
        trunk = nearestApproaching(join.trunkRoute(), join.stopIndex());
        if (trunk.isEmpty()) {
          continue;
        }
        if (waitSeconds(join) <= 0) {
          return Optional.empty();
        }
      }
      Optional<MinecartGroup> trunkGroup = trunk.flatMap(CoupleService::group);
      int trunkCars = trunkGroup.map(MinecartGroup::size).orElse(0);
      if (trunkCars <= 0) {
        // 主干还没出车：整列（主干交路的组合编组）减去本车
        trunkCars =
            coupler
                .decoupling()
                .plannedPatternOf(join.trunkRoute())
                .map(pattern -> Math.max(0, pattern.cars() - ownCars))
                .orElse(0);
      }
      return trunkCars > 0 ? Optional.of(new Partner(trunkCars, trunkGroup)) : Optional.empty();
    }
    List<CouplingIndex.Join> trunkJoins = current.ofTrunk(routeId);
    if (trunkJoins.isEmpty()) {
      return Optional.empty();
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> progress =
        plugin.getRouteProgressRegistry().flatMap(registry -> registry.get(trainName));
    CoupledAt done = joined.get(key(trainName));
    for (CouplingIndex.Join join : trunkJoins) {
      if (!join.atStation(node)
          || (progress.isPresent() && progress.get().currentIndex() > join.stopIndex())
          || (done != null
              && done.route().equals(join.trunkRoute())
              && done.index() == join.stopIndex())) {
        continue;
      }
      Optional<String> feeder = expectedFeeder(trainName, join);
      if (feeder.isEmpty()
          || Math.min(waitSeconds(join), StationStopCoordinator.HOLD_CEILING.toSeconds()) <= 0) {
        continue;
      }
      Optional<MinecartGroup> feederGroup = group(feeder.get());
      int feederCars = feederGroup.map(MinecartGroup::size).orElse(0);
      return feederCars > 0 ? Optional.of(new Partner(feederCars, feederGroup)) : Optional.empty();
    }
    return Optional.empty();
  }

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

  /** 主干先到、停在站台上等回程支线车挂上来（含对方正在进站）：AutoStation 开着门等，挂上了或不等了再关。 */
  public boolean awaitsFeeder(String trainName) {
    if (trainName == null) {
      return false;
    }
    String key = key(trainName);
    for (Approach approach : distinctApproaches()) {
      if (!approach.feederFirst && key(approach.standingName).equals(key)) {
        return true;
      }
    }
    TrunkWait wait = trunks.get(key);
    return wait != null && Instant.now().isBefore(wait.deadline());
  }

  /** 进站的这一对、出库去联挂的主干与它要挂的回程支线车之间的占用互相不挡。 */
  boolean exempts(OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim) {
    if (request == null || claim == null) {
      return false;
    }
    if (!depotTrunks.isEmpty() && depotPartners(request.trainName(), claim.trainName())) {
      return true;
    }
    if (approaches.isEmpty()) {
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

  /** 出库预检与出车占用期间（还没扣在车库股道上）的这一对；扣下以后不再豁免，信号照常停车，等进站时登记的豁免。 */
  private boolean depotPartners(String first, String second) {
    DepotTrunk depot = depotTrunks.get(key(first));
    if (depot != null && depot.heldAt().isEmpty() && sameTrain(depot.feeder(), second)) {
      return true;
    }
    depot = depotTrunks.get(key(second));
    return depot != null && depot.heldAt().isEmpty() && sameTrain(depot.feeder(), first);
  }

  /**
   * 车库出车之前（出库预检、出车占用之前）问：这张票开的是不是回程支线车正停在联挂站等着并进的那一班主干。是的话登记下来， 那辆回程支线车的占用不挡它出库（{@link
   * #exempts}）；别的车照常挡。
   *
   * @param trainName 出车用的车名
   * @param intent 票的交路意图
   */
  public void admitDepotTrunk(String trainName, TimetableService.TicketIntent intent) {
    if (trainName == null || intent == null) {
      return;
    }
    for (FeederHold hold : List.copyOf(feeders.values())) {
      Optional<TimetableService.JoinTrunk> scheduled =
          hold.scheduled().filter(trunk -> trunk.runBy(intent));
      if (scheduled.isEmpty()
          || !hold.trunk().map(t -> sameTrain(t, trainName)).orElse(true)
          || !joinsRightOutOfDepot(hold, intent)) {
        continue;
      }
      // 车名先记上：出车前的选台与占用判定就认得出它是搭档
      feeders.replace(
          key(hold.name()),
          hold,
          hold.rescheduled(
              new TimetableService.JoinTrunk(
                  true,
                  Optional.of(trainName),
                  scheduled.get().duty(),
                  scheduled.get().tripIndex())));
      DepotTrunk previous = depotTrunks.get(key(trainName));
      depotTrunks.put(
          key(trainName),
          new DepotTrunk(
              trainName,
              hold.name(),
              Instant.now(),
              previous == null ? Optional.empty() : previous.heldAt()));
      if (previous == null) {
        debug("COPL_DEPOT_TRUNK_ADMITTED train=" + trainName + " feeder=" + hold.name());
      }
      return;
    }
  }

  /**
   * 这张票的主干出库后第一个点就是回程支线车停着的联挂站，且车库离那条股道不超过 {@code approach-max-blocks}（沿调度图）：
   * 出车后停在车库股道上就能直接开始进站。认不出时为否（不放宽出库）。
   */
  private boolean joinsRightOutOfDepot(FeederHold hold, TimetableService.TicketIntent intent) {
    Optional<UUID> trunkRoute =
        plugin
            .getTimetableService()
            .flatMap(service -> service.plannedJoinOf(intent))
            .filter(TimetableService.PlannedJoin::trunk)
            .map(TimetableService.PlannedJoin::trunkRouteId);
    Optional<NodeId> depot =
        trunkRoute.flatMap(
            route ->
                plugin
                    .getRouteDefinitionCache()
                    .flatMap(cache -> cache.findById(route))
                    .map(RouteDefinition::waypoints)
                    .filter(waypoints -> !waypoints.isEmpty())
                    .map(waypoints -> waypoints.get(0)));
    Optional<UUID> world =
        group(hold.name()).map(MinecartGroup::getWorld).map(org.bukkit.World::getUID);
    if (trunkRoute.isEmpty() || depot.isEmpty() || world.isEmpty()) {
      return false;
    }
    boolean firstStop =
        hold.joins().stream()
            .anyMatch(join -> join.trunkRoute().equals(trunkRoute.get()) && join.stopIndex() == 1);
    OptionalDouble distance = pathLength(world.get(), depot.get(), hold.platform());
    return firstStop
        && distance.isPresent()
        && distance.getAsDouble() <= coupler.settings().approachMaxBlocks();
  }

  /**
   * 出库去联挂的主干出了车、第一拍信号之前：出车拿到的占用和回程支线车有重叠时登记调车中（调度让出控制，不发车、不刹车）， 停在车库股道上等进站把它推进去；
   * 没有重叠时照常发车，到站外被挡住再进站。
   *
   * @param trainName 出车用的车名
   */
  public void holdDepotTrunk(String trainName) {
    DepotTrunk depot = trainName == null ? null : depotTrunks.get(key(trainName));
    if (depot == null) {
      return;
    }
    if (coupler.sharedClaimsOf(trainName, depot.feeder()).isEmpty()) {
      releaseDepotTrunk(depot, "no-overlap", false);
      return;
    }
    if (plugin
        .getRuntimeDispatchService()
        .map(dispatch -> dispatch.hasDepartureGate(trainName))
        .orElse(false)) {
      // 车库接车、手动提前出车自己扣着车（门控放开后由人或到点发车）：不登记调车，让出与回程支线车共占的资源，照普通出车被对方挡住
      yieldShared(depot);
      releaseDepotTrunk(depot, "gated-spawn", false);
      return;
    }
    ShuntingRegistry.begin(trainName);
    depotTrunks.replace(key(trainName), depot, depot.held(Instant.now()));
    debug("COPL_DEPOT_TRUNK_HELD train=" + trainName + " feeder=" + depot.feeder());
  }

  /** 这列车是出库去联挂、出车后停在车库股道上等进站的主干。 */
  private boolean heldAtDepot(String trainName) {
    DepotTrunk depot = trainName == null ? null : depotTrunks.get(key(trainName));
    return depot != null && depot.heldAt().isPresent();
  }

  /**
   * 出库去联挂的主干：迟迟没出车、回程支线车不再等它（走了、放手了）或车没了就作废；停在车库股道上 {@link #DEPOT_TRUNK_APPROACH_LIMIT}
   * 还没开始进站就放弃这次联挂。作废时让出与回程支线车共占的资源、撤销调车登记，交还调度照常被挡。
   */
  private void maintainDepotTrunks(Instant now) {
    for (DepotTrunk depot : List.copyOf(depotTrunks.values())) {
      FeederHold hold = feeders.get(key(depot.feeder()));
      boolean awaited =
          hold != null && hold.trunk().map(t -> sameTrain(t, depot.name())).orElse(true);
      if (depot.heldAt().isEmpty()) {
        if (!awaited || now.isAfter(depot.admittedAt().plus(DEPOT_TRUNK_ADMIT_TTL))) {
          releaseDepotTrunk(depot, awaited ? "not-spawned" : "feeder-released", false);
          if (awaited) {
            // 没出成车：先记上的车名撤掉，换一张票出车时照样认
            forgetAdmittedTrunk(hold, depot.name());
          }
        }
        continue;
      }
      if (participating(key(depot.name()))) {
        // 进站中：推车动作控车，挂上或放弃时各自收尾
        continue;
      }
      if (group(depot.name()).isEmpty()) {
        releaseDepotTrunk(depot, "trunk-gone", false);
      } else if (!awaited) {
        releaseDepotTrunk(depot, "feeder-released", true);
      } else if (now.isAfter(depot.heldAt().get().plus(DEPOT_TRUNK_APPROACH_LIMIT))) {
        abandonFeeder(hold, "depot-approach-not-started");
        releaseDepotTrunk(depot, "approach-not-started", true);
      }
    }
  }

  /** 让出出库主干与回程支线车共占的资源（豁免撤了，这些资源本不该是它的）。 */
  private void yieldShared(DepotTrunk depot) {
    if (plugin.getOccupancyManager() == null) {
      return;
    }
    for (OccupancyResource resource : coupler.sharedClaimsOf(depot.name(), depot.feeder())) {
      plugin.getOccupancyManager().releaseResource(resource, Optional.of(depot.name()));
    }
  }

  /** 放弃这次联挂：放掉回程支线车的扣留，离开待命池以前不再扣它。 */
  private void abandonFeeder(FeederHold hold, String reason) {
    feeders.remove(key(hold.name()), hold);
    abandonedFeeders.put(key(hold.name()), hold.name());
    debug("COPL_FEEDER_RELEASED train=" + hold.name() + " reason=" + reason);
  }

  private void forgetAdmittedTrunk(FeederHold hold, String trainName) {
    plugin
        .getTimetableService()
        .flatMap(service -> service.joinTrunkOf(hold.name()))
        .filter(TimetableService.JoinTrunk::coming)
        .filter(fresh -> fresh.train().isEmpty())
        .ifPresent(
            fresh ->
                feeders.computeIfPresent(
                    key(hold.name()),
                    (name, current) ->
                        current.trunk().filter(t -> sameTrain(t, trainName)).isPresent()
                            ? current.rescheduled(fresh)
                            : current));
  }

  /**
   * 作废一次出库联挂：撤销车库扣车的调车登记，重算信号。
   *
   * @param yieldShared 先让出与回程支线车共占的资源（没进过站时；豁免撤了，这些资源本不该是它的）
   */
  private void releaseDepotTrunk(DepotTrunk depot, String reason, boolean yieldShared) {
    if (!depotTrunks.remove(key(depot.name()), depot)) {
      return;
    }
    if (depot.heldAt().isPresent()) {
      if (yieldShared) {
        yieldShared(depot);
      }
      if (!participating(key(depot.name()))) {
        ShuntingRegistry.end(depot.name());
      }
      plugin
          .getRuntimeDispatchService()
          .ifPresent(dispatch -> group(depot.name()).ifPresent(dispatch::refreshSignal));
    }
    debug(
        "COPL_DEPOT_TRUNK_RELEASED train="
            + depot.name()
            + " feeder="
            + depot.feeder()
            + " reason="
            + reason);
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
      if (hold.joins().contains(join.get()) && hold.awaits(trainName)) {
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
        if (hold.joins().contains(asTrunk.get()) && hold.awaits(trainName)) {
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
      return hold.timetabled()
          ? hold.awaits(trainName)
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
    if (--joinForecastCountdown <= 0) {
      joinForecastCountdown = JOIN_FORECAST_ROUNDS;
      refreshJoinForecasts(current);
    }
    if (current.isEmpty()
        && feeders.isEmpty()
        && trunks.isEmpty()
        && approaches.isEmpty()
        && depotTrunks.isEmpty()) {
      return;
    }
    Instant now = Instant.now();
    for (Approach approach : List.copyOf(distinctApproaches())) {
      advance(approach);
    }
    holdFeeders(current, now);
    expire(now);
    maintainDepotTrunks(now);
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

  // ---------------------------------------------------------------- 联挂预测

  /**
   * 按交路进度重算每列车的联挂预测，整份换上。回程支线车看本交路终点（联挂站）；主干看本站或前方最近的联挂站。对方是哪一列车与主干等不等它同一口径（{@link
   * #expectFeeder}）：按表时只认表上那一班，否则取扣在联挂站或正开来的车；认不出对方时主干不写联挂（它到了也不会等）。
   */
  private void refreshJoinForecasts(CouplingIndex current) {
    Optional<RouteProgressRegistry> registry = plugin.getRouteProgressRegistry();
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (registry.isEmpty() || cache.isEmpty() || current.isEmpty()) {
      joinForecasts = Map.of();
      return;
    }
    Map<String, RouteProgressRegistry.RouteProgressEntry> progress = new java.util.HashMap<>();
    for (RouteProgressRegistry.RouteProgressEntry entry : registry.get().snapshot().values()) {
      progress.put(key(entry.trainName()), entry);
    }
    joined
        .entrySet()
        .removeIf(
            entry ->
                Optional.ofNullable(progress.get(entry.getKey()))
                    .filter(
                        found ->
                            entry.getValue().route().equals(found.routeUuid())
                                && entry.getValue().index() == found.currentIndex())
                    .isEmpty());
    Optional<TimetableService> timetable = plugin.getTimetableService();
    // 按表：主干 → 表上这一班挂不挂、挂哪辆；回程支线车 → 它要并进的主干
    Map<String, TimetableService.JoinPlan> plans = new java.util.HashMap<>();
    Map<String, String> trunkOfFeeder = new java.util.HashMap<>();
    for (RouteProgressRegistry.RouteProgressEntry entry : progress.values()) {
      if (current.ofTrunk(entry.routeUuid()).isEmpty() || timetable.isEmpty()) {
        continue;
      }
      TimetableService.JoinPlan plan = timetable.get().joinPlanOf(entry.trainName());
      plans.put(key(entry.trainName()), plan);
      plan.feederTrain().ifPresent(feeder -> trunkOfFeeder.put(key(feeder), entry.trainName()));
    }
    Map<String, JoinForecast> next = new java.util.HashMap<>();
    for (RouteProgressRegistry.RouteProgressEntry entry : progress.values()) {
      try {
        joinForecastOf(entry, current, cache.get(), progress, plans, trunkOfFeeder)
            .ifPresent(found -> next.put(key(entry.trainName()), found));
      } catch (RuntimeException ex) {
        debug("COPL_FORECAST_FAILED train=" + entry.trainName() + " error=" + ex);
      }
    }
    joinForecasts = Map.copyOf(next);
  }

  /**
   * @param progress 车名键 → 交路进度
   * @param plans 主干车名键 → 按表这一班挂不挂（不按表的车没有）
   * @param trunkOfFeeder 回程支线车名键 → 按表它要并进的主干
   */
  private Optional<JoinForecast> joinForecastOf(
      RouteProgressRegistry.RouteProgressEntry entry,
      CouplingIndex current,
      RouteDefinitionCache cache,
      Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
      Map<String, TimetableService.JoinPlan> plans,
      Map<String, String> trunkOfFeeder) {
    int ownCars = group(entry.trainName()).map(MinecartGroup::size).orElse(0);
    Optional<CouplingIndex.Join> asFeeder =
        current
            .continuationOf(entry.routeUuid())
            .filter(join -> entry.currentIndex() < join.feederEndIndex());
    if (asFeeder.isPresent()) {
      CouplingIndex.Join join = asFeeder.get();
      // 按表时是表上那一班主干；否则是在联挂站等着的、或离联挂站最近的开来的主干
      Optional<String> trunk =
          Optional.ofNullable(trunkOfFeeder.get(key(entry.trainName())))
              .or(
                  () ->
                      trunks.values().stream()
                          .filter(
                              wait ->
                                  wait.join().equals(join)
                                      && wait.feeder()
                                          .map(f -> sameTrain(f, entry.trainName()))
                                          .orElse(true))
                          .map(TrunkWait::name)
                          .findFirst())
              .or(
                  () ->
                      nearestOn(
                          progress,
                          join.trunkRoute(),
                          join.stopIndex(),
                          // 已在这一站挂上别的车的主干不算
                          name ->
                              Optional.ofNullable(joined.get(key(name)))
                                  .filter(
                                      at ->
                                          at.route().equals(join.trunkRoute())
                                              && at.index() == join.stopIndex())
                                  .isEmpty()));
      boolean standing =
          trunk
              .map(name -> progress.get(key(name)))
              .filter(
                  found ->
                      join.trunkRoute().equals(found.routeUuid())
                          && found.currentIndex() == join.stopIndex())
              .isPresent();
      int trunkCars = trunk.flatMap(CoupleService::group).map(MinecartGroup::size).orElse(0);
      if (trunkCars <= 0 && ownCars > 0) {
        // 主干还没出车：整列（主干交路的组合编组）减去本车
        trunkCars =
            coupler
                .decoupling()
                .plannedPatternOf(join.trunkRoute())
                .map(pattern -> Math.max(0, pattern.cars() - ownCars))
                .orElse(0);
      }
      return forecastAt(
          entry.routeId().value(),
          cache.listStops(entry.routeId()),
          join.feederEndIndex(),
          false,
          ownCars,
          trunkCars,
          standing);
    }
    CouplingIndex.Join nearest = null;
    CoupledAt done = joined.get(key(entry.trainName()));
    for (CouplingIndex.Join join : current.ofTrunk(entry.routeUuid())) {
      if (done != null
          && done.route().equals(join.trunkRoute())
          && done.index() == join.stopIndex()) {
        continue;
      }
      if (join.stopIndex() >= entry.currentIndex()
          && (nearest == null || join.stopIndex() < nearest.stopIndex())) {
        nearest = join;
      }
    }
    if (nearest == null) {
      return Optional.empty();
    }
    CouplingIndex.Join join = nearest;
    String trunkName = entry.trainName();
    Optional<FeederHold> held =
        feeders.values().stream()
            .filter(hold -> hold.joins().contains(join) && hold.awaits(trunkName))
            .findFirst();
    TimetableService.JoinPlan plan =
        plans.getOrDefault(key(trunkName), TimetableService.JoinPlan.UNBOUND);
    Optional<String> feeder =
        plan.bound()
            // 按表：只认表上那一班的回程支线车，扣在联挂站或正开往联挂站
            ? plan.feederTrain()
                .filter(
                    name ->
                        held.filter(hold -> sameTrain(hold.name(), name)).isPresent()
                            || Optional.ofNullable(progress.get(key(name)))
                                .filter(
                                    found ->
                                        join.feederRoute().equals(found.routeUuid())
                                            && found.currentIndex() <= join.feederEndIndex())
                                .isPresent())
            : held.map(FeederHold::name)
                .or(
                    () ->
                        nearestOn(
                            progress, join.feederRoute(), join.feederEndIndex(), name -> true));
    if (feeder.isEmpty()) {
      return Optional.empty();
    }
    boolean standing =
        held.filter(hold -> sameTrain(hold.name(), feeder.get())).isPresent()
            || Optional.ofNullable(progress.get(key(feeder.get())))
                .filter(
                    found ->
                        join.feederRoute().equals(found.routeUuid())
                            && found.currentIndex() >= join.feederEndIndex())
                .isPresent();
    return forecastAt(
        entry.routeId().value(),
        cache.listStops(entry.routeId()),
        join.stopIndex(),
        true,
        ownCars,
        feeder.flatMap(CoupleService::group).map(MinecartGroup::size).orElse(0),
        standing);
  }

  /** 交路 {@code route} 上还没过下标 {@code target}（含停在那里）、{@code eligible} 认的车里离它最近的一辆。 */
  private static Optional<String> nearestOn(
      Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
      UUID route,
      int target,
      java.util.function.Predicate<String> eligible) {
    return progress.values().stream()
        .filter(
            found ->
                route.equals(found.routeUuid())
                    && found.currentIndex() <= target
                    && eligible.test(found.trainName()))
        .max(
            java.util.Comparator.comparingInt(
                RouteProgressRegistry.RouteProgressEntry::currentIndex))
        .map(RouteProgressRegistry.RouteProgressEntry::trainName);
  }

  /**
   * 表上还没出车的一班的联挂预测（站牌票据与预测行按表提前写联挂页与“本站联挂”）：两边各几节由主干交路的组合编组与一方车底的编组推出，推不出时为 0。
   * 任意线程可调：只读联挂索引与编组缓存（缓存里没有的编组由主线程补上，这一次节数为 0）。
   *
   * @param planned 表上那一班在联挂里的情况
   * @return 联挂站不是车站、索引里没有这一处联挂时为空
   */
  public Optional<JoinForecast> plannedJoinForecast(TimetableService.PlannedJoin planned) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (planned == null || cache.isEmpty()) {
      return Optional.empty();
    }
    Optional<CouplingIndex.Join> join =
        index.ofTrunk(planned.trunkRouteId()).stream()
            .filter(found -> found.feederRoute().equals(planned.feederRouteId()))
            .findFirst();
    Optional<RouteDefinition> route = cache.get().findById(planned.routeId());
    if (join.isEmpty() || route.isEmpty()) {
      return Optional.empty();
    }
    Optional<DecoupleService.PlannedPattern> pattern =
        coupler.decoupling().plannedPatternOf(planned.trunkRouteId());
    int total = pattern.map(DecoupleService.PlannedPattern::cars).orElse(0);
    java.util.OptionalInt trunkCars =
        pattern
            .flatMap(found -> planned.trunkConsist().map(found::carsOfEnd))
            .orElse(java.util.OptionalInt.empty());
    java.util.OptionalInt feederCars =
        pattern
            .flatMap(found -> planned.feederConsist().map(found::carsOfEnd))
            .orElse(java.util.OptionalInt.empty());
    if (trunkCars.isEmpty() && feederCars.isPresent()) {
      trunkCars = java.util.OptionalInt.of(total - feederCars.getAsInt());
    } else if (feederCars.isEmpty() && trunkCars.isPresent()) {
      feederCars = java.util.OptionalInt.of(total - trunkCars.getAsInt());
    }
    int trunk = trunkCars.orElse(0);
    int feeder = feederCars.orElse(0);
    return forecastAt(
        route.get().id().value(),
        cache.get().listStops(route.get().id()),
        planned.trunk() ? join.get().stopIndex() : join.get().feederEndIndex(),
        planned.trunk(),
        planned.trunk() ? trunk : feeder,
        planned.trunk() ? feeder : trunk,
        false);
  }

  /** 联挂站是车站时的预测；路径点上的联挂不给乘客看。 */
  private Optional<JoinForecast> forecastAt(
      String routeId,
      List<org.fetarute.fetaruteTCAddon.company.model.RouteStop> stops,
      int index,
      boolean trunk,
      int ownCars,
      int joiningCars,
      boolean standing) {
    if (index < 0 || index >= stops.size()) {
      return Optional.empty();
    }
    return stations
        .stationIdOf(stops.get(index))
        .map(
            stationId ->
                new JoinForecast(
                    routeId,
                    index,
                    stationId,
                    stations.stationName(stationId),
                    trunk,
                    ownCars,
                    joiningCars,
                    standing));
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
          || abandonedFeeders.containsKey(key)
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
      Optional<TimetableService.JoinTrunk> trunk =
          plugin
              .getTimetableService()
              .flatMap(service -> service.joinTrunkOf(candidate.trainName()));
      Optional<FeederHold> hold = Optional.empty();
      if (trunk.isPresent()) {
        if (trunk.get().coming()) {
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
                    + (held.timetabled() ? held.trunk().orElse("pending") : "*"));
          });
    }
  }

  /** 等到点、车离开了或按表主干不会来了就放手。 */
  private void expire(Instant now) {
    deferredUntil.values().removeIf(until -> now.isAfter(until.plus(Duration.ofMinutes(5))));
    retryAfter.values().removeIf(until -> now.isAfter(until.plus(Duration.ofMinutes(5))));
    Optional<LayoverRegistry> registry = plugin.getLayoverRegistry();
    for (Map.Entry<String, FeederHold> entry : List.copyOf(feeders.entrySet())) {
      FeederHold hold = entry.getValue();
      if (!hold.timetabled() || participating(entry.getKey())) {
        continue;
      }
      // 主干交路出了车，车名才知道
      Optional<TimetableService.JoinTrunk> next =
          plugin
              .getTimetableService()
              .flatMap(service -> service.joinTrunkOf(hold.name()))
              .filter(TimetableService.JoinTrunk::coming);
      if (next.flatMap(TimetableService.JoinTrunk::train).isPresent()
          && !next.equals(hold.scheduled())) {
        feeders.replace(entry.getKey(), hold, hold.rescheduled(next.get()));
        next.get()
            .train()
            .ifPresent(
                train -> debug("COPL_FEEDER_TRUNK train=" + hold.name() + " trunk=" + train));
      }
    }
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
                  hold.timetabled()
                      && !plugin
                          .getTimetableService()
                          .flatMap(service -> service.joinTrunkOf(hold.name()))
                          .map(TimetableService.JoinTrunk::coming)
                          .orElse(false);
              if (gone || trunkGone || now.isAfter(hold.deadline())) {
                if (!gone && !trunkGone && hold.timetabled()) {
                  // 按表等满了：离开待命池以前不再为这一次联挂扣它（主干交路还算会来，不记下就会马上又扣上）
                  abandonedFeeders.put(entry.getKey(), hold.name());
                }
                debug(
                    "COPL_FEEDER_RELEASED train="
                        + hold.name()
                        + " reason="
                        + (gone ? "left" : trunkGone ? "trunk-not-coming" : "wait-expired"));
                return true;
              }
              return false;
            });
    abandonedFeeders
        .entrySet()
        .removeIf(abandoned -> registry.flatMap(r -> r.get(abandoned.getValue())).isEmpty());
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
    if (hold.timetabled()) {
      return hold.trunk().map(List::of).orElse(List.of());
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
    if ((ShuntingRegistry.isShunting(moverName) && !heldAtDepot(moverName))
        || !headingTo(mover.get(), entry.get(), platform)) {
      return false;
    }
    if (heldAtDepot(moverName) && !boundAsTrunkOf(moverName, standingName)) {
      // 出车事务还没落定（交路还没绑上这辆车）：等它落定再推，免得出车回滚时车已在咽喉里
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
            standingCenter.get(),
            routeEdges(mover.get(), entry.get(), platform));
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

  /** 时刻表上回程支线车等着的那一班主干此刻由这辆车跑（交路已经绑上它）。 */
  private boolean boundAsTrunkOf(String trunkName, String feederName) {
    return plugin
        .getTimetableService()
        .flatMap(service -> service.joinTrunkOf(feederName))
        .flatMap(TimetableService.JoinTrunk::train)
        .filter(train -> sameTrain(train, trunkName))
        .isPresent();
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
    return pathLength(group.getWorld().getUID(), entry.lastPassedGraphNode().get(), platform);
  }

  /** 后到的车进站的计划进路经过的区间（与 {@link #graphDistance} 同一条最短路）；算不出时为空。 */
  private Set<EdgeId> routeEdges(
      MinecartGroup group, RouteProgressRegistry.RouteProgressEntry entry, NodeId platform) {
    if (group.getWorld() == null || entry.lastPassedGraphNode().isEmpty()) {
      return Set.of();
    }
    Set<EdgeId> edges = new HashSet<>();
    shortestPath(group.getWorld().getUID(), entry.lastPassedGraphNode().get(), platform)
        .ifPresent(path -> path.edges().forEach(edge -> edges.add(edge.id())));
    return edges;
  }

  /** 调度图上两点间最短路的长度（格）；图没建好或不连通时为空。 */
  private OptionalDouble pathLength(UUID worldId, NodeId from, NodeId to) {
    if (from != null && from.equals(to)) {
      return OptionalDouble.of(0.0);
    }
    return shortestPath(worldId, from, to)
        .map(p -> OptionalDouble.of(p.totalLengthBlocks()))
        .orElse(OptionalDouble.empty());
  }

  /** 调度图上两点间的最短路；图没建好或不连通时为空。 */
  private Optional<RailGraphPath> shortestPath(UUID worldId, NodeId from, NodeId to) {
    RailGraphService graphs = plugin.getRailGraphService();
    if (graphs == null || worldId == null || from == null || to == null) {
      return Optional.empty();
    }
    return graphs
        .getSnapshot(worldId)
        .flatMap(
            snapshot ->
                new RailGraphPathFinder()
                    .shortestPath(
                        snapshot.graph(),
                        from,
                        to,
                        RailGraphPathFinder.Options.shortestDistance()));
  }

  /**
   * 推车往前找车时先碰到的别的列车其实不在进站的计划进路上：找车顺着道岔此刻的开向走，别的车刚把前方道岔扳向另一支（例如回库车刚开过）时， 会拐进那一支、碰到那里的车。车开到道岔前
   * TrainCarts 会按目的地把道岔扳过来，那辆车不挡路。
   *
   * <p>只在两头都对得上时才这样判：挡路那节车厢压着的区间都不在计划进路上，而推着的车车头还在计划进路上（车头压着的区间与进路相交，或车头不在任何区间上，
   * 例如还在车库股道尽头）。车头已经压到进路以外的区间（真开进了那一支）、计划进路算不出、图的足迹索引不可用或查不到那节车厢压着哪一段时，照旧当前面有车。
   */
  private boolean offRouteObstacle(Approach approach, MinecartMember<?> hit) {
    if (approach.routeEdges.isEmpty() || approach.mover.getWorld() == null) {
      return false;
    }
    RailGraphService graphs = plugin.getRailGraphService();
    Optional<RailInterlockingState> interlocking =
        graphs == null
            ? Optional.empty()
            : graphs
                .getSnapshot(approach.mover.getWorld().getUID())
                .map(RailGraphService.RailGraphSnapshot::graph)
                .filter(RailGraphInterlockingSupport.class::isInstance)
                .map(graph -> ((RailGraphInterlockingSupport) graph).interlockingState())
                .filter(RailInterlockingState::cellCoverageAvailable);
    if (interlocking.isEmpty()) {
      return false;
    }
    return ApproachRules.obstacleOffRoute(
        approach.routeEdges,
        edgesUnder(interlocking.get(), approach.lead),
        edgesUnder(interlocking.get(), hit));
  }

  /** 车厢此刻压着的轨道方块所在的区间；车厢不在轨道上或方块不在任何区间上时为空。 */
  private static Set<EdgeId> edgesUnder(
      RailInterlockingState interlocking, MinecartMember<?> member) {
    org.bukkit.block.Block rail = member == null || member.isUnloaded() ? null : member.getBlock();
    return rail == null
        ? Set.of()
        : interlocking.edgesForCell(new RailFootprintCell(rail.getX(), rail.getY(), rail.getZ()));
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
        // 调度按豁免把进路排进了对方的资源，或已经排到对方那条股道：开推。从车库出来的要穿过咽喉，须排到那条股道
        boolean toPlatform =
            coupler.claims(approach.moverName, OccupancyResource.forNode(approach.platform));
        boolean fromDepot = depotTrunks.containsKey(key(approach.moverName));
        if (toPlatform
            || (!fromDepot
                && !coupler.sharedClaimsOf(approach.moverName, approach.standingName).isEmpty())) {
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
        } else if (offRouteObstacle(approach, hit)) {
          // 找车走岔了（道岔还没扳到）：当作这一眼没看见对方，照常往前推；已经看见过的留着上一次的空隙
          if (approach.target == null) {
            approach.lastGap = OptionalDouble.empty();
          }
        } else {
          other = true;
          approach.obstacle =
              "hit:"
                  + coupler.displayName(hit.getGroup())
                  + "#"
                  + (hit.getGroup().indexOf(hit) + 1)
                  + "/"
                  + hit.getGroup().size();
        }
      } else if (search
          .failure()
          .filter(
              failure ->
                  ApproachRules.finderBlocks(
                      failure, nearestCarDistance(leadAt.get(), approach.standing)))
          .isPresent()) {
        // 先碰到车厢中间，或车头不在轨道上、车头所在处区块没加载、对方已经很近却看不见：都不再往前推
        other = true;
        approach.obstacle = "finder:" + search.failure().get();
      } else if (approach.target == null) {
        // 距离内没有车，或前方区块没加载、对方还远：还没看见对方
        approach.lastGap = OptionalDouble.empty();
      }
      // 已经看见过对方、这一眼偶尔没找到：留着上一次量的空隙，照旧慢速对位，不跳回进站速度
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

  /** 推着的车车头到对方最近一节车厢（实体位置）的直线距离；量不出时为 {@code NaN}。 */
  private static double nearestCarDistance(Vector from, MinecartGroup standing) {
    double nearest = Double.NaN;
    for (MinecartMember<?> member : standing) {
      Optional<Vector> at = CarEnds.center(member);
      if (at.isPresent()) {
        double distance = from.distance(at.get());
        if (Double.isNaN(nearest) || distance < nearest) {
          nearest = distance;
        }
      }
    }
    return nearest;
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
    // 从车库进站的主干：调车登记已随进站撤销
    depotTrunks.remove(key(approach.trunkName));
    joined.put(
        key(approach.trunkName),
        new CoupledAt(approach.join.trunkRoute(), approach.join.stopIndex()));
    MinecartGroup merged = result.get().merged().orElse(null);
    // 挂上后车站牌子还会再触发：只开一个停站
    long tick = Bukkit.getCurrentTick();
    linkedStops.record(key(approach.trunkName), approach.platform, !approach.feederFirst, tick);
    if (merged != null) {
      linkedStops.record(
          key(merged.getProperties().getTrainName()),
          approach.platform,
          !approach.feederFirst,
          tick);
    }
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
    StationSignTrigger.arrive(plugin, group, platform)
        .ifPresent(
            reason ->
                warn(
                    "sign-missing".equals(reason)
                        ? "COPL_ARRIVAL_SIGN_MISSING platform=" + platform.value()
                        : "COPL_ARRIVAL_FAILED platform=" + platform.value() + " error=" + reason));
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
      // 开动过：两车已经登记成同股道列车对，共占的资源归它管
      Optional.ofNullable(depotTrunks.get(key(approach.trunkName)))
          .ifPresent(depot -> releaseDepotTrunk(depot, "approach-failed", false));
    } else {
      retryAfter.put(
          key(approach.moverName) + "|" + key(approach.standingName),
          Instant.now().plus(RETRY_AFTER));
      if (heldAtDepot(approach.moverName)) {
        // 还停在车库股道上：进站撤销了调车登记，接着扣住（不让调度把它放进对方的股道）
        ShuntingRegistry.begin(approach.moverName);
      }
    }
    warn(
        "COPL_APPROACH_FAILED mover="
            + approach.moverName
            + " standing="
            + approach.standingName
            + " reason="
            + reason
            + " moved="
            + approach.moved
            + " obstacle="
            + approach.obstacle);
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

  /** 主干在联挂站会等的回程支线车（见 {@link #expectFeeder}）。 */
  private Optional<String> expectedFeeder(String trunkName, CouplingIndex.Join join) {
    return expectedFeeder(
        join, plugin.getTimetableService().map(service -> service.joinPlanOf(trunkName)));
  }

  /**
   * 主干在联挂站会等的回程支线车：按表时是表上那一班（正开往联挂站时才等），否则是开往联挂站、离得最近的一辆。
   *
   * @param plan 主干这一班按表挂不挂、挂哪辆；没有时刻表服务时为空
   */
  private Optional<String> expectedFeeder(
      CouplingIndex.Join join, Optional<TimetableService.JoinPlan> plan) {
    if (plan.isPresent() && plan.get().bound()) {
      if (plan.get().feeder().isEmpty()) {
        return Optional.empty();
      }
      return plan.get()
          .feederTrain()
          .filter(feeder -> approachingOnRoute(feeder, join.feederRoute(), join.feederEndIndex()));
    }
    return nearestApproaching(join.feederRoute(), join.feederEndIndex());
  }

  /** 正跑着这条交路、还没到第 {@code target} 个节点的车里离它最近的一辆。 */
  private Optional<String> nearestApproaching(UUID route, int target) {
    return plugin
        .getRouteProgressRegistry()
        .flatMap(
            registry ->
                registry.snapshot().values().stream()
                    .filter(
                        entry -> route.equals(entry.routeUuid()) && entry.currentIndex() < target)
                    .max(
                        java.util.Comparator.comparingInt(
                            RouteProgressRegistry.RouteProgressEntry::currentIndex))
                    .map(RouteProgressRegistry.RouteProgressEntry::trainName));
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
