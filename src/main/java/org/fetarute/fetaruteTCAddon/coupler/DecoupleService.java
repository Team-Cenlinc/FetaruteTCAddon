package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ReclaimManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.RouteSpawnServices;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;

/**
 * 解编指令 {@code DECP}（见 {@link RouteCouplingDirectives}）：列车在写了 DECP 的那一站停车时切下指定的一段。
 *
 * <p>到站回调记一条待执行，之后每两 tick 看一次：车停稳、停站计时开始后才动手（路径点停车没有停站计时，停稳一秒后动手）。切点按车厢来源编组判（{@link
 * DecoupleCut}），切不开就整列继续本交路并告警。没被指定的部分保留原身份继续本交路；切下的部分登记成本站待命车，主干停站结束才就绪， 再按下面接票：
 *
 * <ul>
 *   <li>按表运行（主干车绑着时刻表交路）：由表上这一班生出的子交路接它（{@link TimetableService#pinSplit}），不看指令里的动作； 表上这一班不拆时整列不拆。
 *   <li>GOTO：出一张只给它接的票（目标交路）；{@link #GOTO_WAIT} 内没被接走就退成 IDLE。
 *   <li>IDLE：普通待命车，由普通发车票或闲置回收接走。
 *   <li>RETURN：交给回收立即派回库（与叫来的车跑完回库同一入口），派不出去隔一会儿再试。
 * </ul>
 *
 * <p>只在服务器主线程使用（到站回调、停站与票据派发都在主线程）。
 */
public final class DecoupleService {

  /** 检查待执行解编的间隔（tick）。 */
  private static final long TICK_PERIOD = 2L;

  /** 路径点停车（没有停站计时）停稳多久后动手（tick）。 */
  private static final int WAYPOINT_SETTLE_TICKS = 20;

  /** 车站停稳后停站计时一直没开始（停站另有处理）时，停稳多久也动手（tick）。 */
  private static final int STATION_SETTLE_TICKS = 60;

  /** 到站后等停稳的上限：过了就放弃这一站的解编。 */
  private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(60);

  /** 有人值乘时等开门、结算的上限（人工驾驶对位、开门都要时间）。 */
  private static final Duration CREW_SETTLE_TIMEOUT = Duration.ofSeconds(180);

  /** GOTO 的票等多久没被接走就退成 IDLE（从就绪起算）。 */
  static final Duration GOTO_WAIT = Duration.ofSeconds(120);

  /** 按表运行时解下的车等子交路首班的上限（从就绪起算）：过了子交路记为落空，车成普通待命车。 */
  static final Duration TIMETABLE_WAIT = Duration.ofMinutes(10);

  /** 回库派不出去时的重试间隔与放弃时限。 */
  private static final Duration RETURN_RETRY = Duration.ofSeconds(5);

  private static final Duration RETURN_GIVE_UP = Duration.ofMinutes(5);

  /** 到站时记下的待执行解编。 */
  private record Pending(
      String trainName,
      TrainProperties properties,
      RouteId route,
      int index,
      RouteCouplingDirectives.Decouple directive,
      boolean station,
      Instant armedAt) {}

  /**
   * 解下等着被接走的车。
   *
   * @param trainName 解下的车
   * @param ticketId GOTO 出的票；按表或 IDLE/RETURN 时为空
   * @param child 按表运行时的子交路
   * @param returning 等着派回库
   * @param readyAt 就绪时刻
   * @param deadline 等到这时还没被接走就放手
   * @param nextReturnAt 下一次派回库的时刻
   */
  private record Waiting(
      String trainName,
      Optional<UUID> ticketId,
      Optional<TimetableService.DutyKey> child,
      boolean returning,
      Instant readyAt,
      Instant deadline,
      Instant nextReturnAt) {

    Waiting retryReturnAt(Instant at) {
      return new Waiting(trainName, ticketId, child, returning, readyAt, deadline, at);
    }
  }

  private final FetaruteTCAddon plugin;
  private final CouplerService coupler;

  /** 车名键 → 待执行的解编。 */
  private final Map<String, Pending> pending = new HashMap<>();

  /** 车名键 → 这一站因解编多停的秒数：车站停站时 AutoStation 取走。 */
  private final Map<String, Integer> stationDwell = new ConcurrentHashMap<>();

  /** 解下的车（车名键）→ 等着被接走的情况。 */
  private final Map<String, Waiting> waiting = new HashMap<>();

  /** GOTO 票 → 只给哪辆车（车名键）。 */
  private final Map<UUID, String> gotoTickets = new HashMap<>();

  /** 车名键 → 停稳的 tick 数（路径点停车用）。 */
  private final Map<String, Integer> stillTicks = new HashMap<>();

  private BukkitTask task;

  /** 车上值乘的人；驾驶模块接入。 */
  private volatile DecoupleCrew crew = DecoupleCrew.NONE;

  /** 每隔多少拍重算一次分行预测（约一秒）。 */
  private static final int FORECAST_ROUNDS = 10;

  private int forecastCountdown;

  /** 车名键 → 分行预测：主线程整份换上，任意线程读（站台屏、公开 API）。 */
  private volatile Map<String, DivisionForecast> forecasts = Map.of();

  private final DivisionDestinations destinations;

  DecoupleService(FetaruteTCAddon plugin, CouplerService coupler) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.coupler = Objects.requireNonNull(coupler, "coupler");
    this.destinations = new DivisionDestinations(plugin);
  }

  /** 接入值乘的人（驾驶员、车掌）；{@code null} 恢复为没有。 */
  public void setCrew(DecoupleCrew next) {
    this.crew = next == null ? DecoupleCrew.NONE : next;
  }

  /** 拆开的任一段正等值乘的人选择或换段时停站扣到几点（发车侧另由 {@link #allowsLayoverDispatch} 把住）；不扣时为空。 */
  public Optional<Instant> crewHolding(String trainName) {
    return trainName == null ? Optional.empty() : crew.holding(trainName);
  }

  void start() {
    if (task == null) {
      task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
    }
  }

  void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
    // 已为解编结算过的值乘的人接着记本趟
    pending.values().forEach(item -> crew.notSplit(item.trainName()));
    pending.clear();
    stationDwell.clear();
    stillTicks.clear();
    // 等着被接走的车留在待命池里照常是待命车；只撤下只给它接的票
    plugin.getSpawnManager().ifPresent(manager -> gotoTickets.keySet().forEach(manager::withdraw));
    gotoTickets.clear();
    waiting.clear();
    forecasts = Map.of();
  }

  // ---------------------------------------------------------------- 到站

  /**
   * 列车到达或经过交路第 {@code index} 个节点（与换模器并在同一个交路节点监听上）。
   *
   * @return 这一站因解编要多停的秒数（不停车、没有 DECP 时为 0）
   */
  public int onRouteNode(
      String trainName, TrainProperties properties, RouteId route, int index, boolean dwelling) {
    if (!dwelling || trainName == null || properties == null || route == null) {
      return 0;
    }
    List<RouteStop> stops =
        plugin.getRouteDefinitionCache().map(cache -> cache.listStops(route)).orElse(List.of());
    if (index < 0 || index >= stops.size()) {
      return 0;
    }
    Optional<RouteCouplingDirectives.Decouple> directive =
        RouteCouplingDirectives.decouple(stops.get(index));
    if (directive.isEmpty()) {
      return 0;
    }
    String key = key(trainName);
    boolean station = RouteTerminals.isStationStop(stops.get(index));
    Pending previous = pending.get(key);
    if (previous == null || !previous.route().equals(route) || previous.index() != index) {
      pending.put(
          key,
          new Pending(
              trainName, properties, route, index, directive.get(), station, Instant.now()));
      stillTicks.remove(key);
    }
    int seconds = RouteCouplingDirectives.decoupleSeconds();
    if (station) {
      stationDwell.put(key, seconds);
    }
    return seconds;
  }

  /**
   * AutoStation 停站：取走这一站因解编要多停的秒数（只取一次）。
   *
   * @return 秒数；这一站不解编时为 0
   */
  public int takeStationDwell(String trainName) {
    Integer seconds = trainName == null ? null : stationDwell.remove(key(trainName));
    return seconds == null ? 0 : seconds;
  }

  // ---------------------------------------------------------------- 发车侧

  /** 发车票能不能接这列待命车：GOTO 的票只接解下的那辆车，解下等着被接走的车只接给它出的票（按表运行时由时刻表把关、回库由回收派）。 */
  public boolean allowsLayoverDispatch(SpawnTicket ticket, String trainName) {
    if (crewHolding(trainName).isPresent()) {
      // 值乘的人还在选继续哪一段：哪张票都先不接
      return false;
    }
    String key = trainName == null ? null : key(trainName);
    if (ticket != null && gotoTickets.containsKey(ticket.id())) {
      return gotoTickets.get(ticket.id()).equals(key);
    }
    Waiting held = key == null ? null : waiting.get(key);
    if (held == null || held.child().isPresent()) {
      return true;
    }
    return ticket != null && held.ticketId().filter(ticket.id()::equals).isPresent();
  }

  /** 发车侧派出了一张票：GOTO 的票派出了就不再管那辆车。 */
  public void onDispatched(SpawnTicket ticket, String trainName) {
    if (ticket == null) {
      return;
    }
    String pinned = gotoTickets.remove(ticket.id());
    if (pinned != null) {
      waiting.remove(pinned);
      debug("DECP_GOTO_DISPATCHED from=" + pinned + " train=" + trainName);
    }
  }

  /** 列车此刻停着的这一站要分行：到站记了解编、表上这一班也要拆（车上值乘的人据此先在这一站结算）。 */
  public boolean dividesHere(String trainName) {
    Pending item = trainName == null ? null : pending.get(key(trainName));
    return item != null && !planOf(item).skips();
  }

  /** 解下的车正等着被接走（闲置回收不收它）。 */
  public boolean holds(String trainName) {
    return trainName != null && waiting.containsKey(key(trainName));
  }

  /** 这列车前方（或此刻停着的这一站）的分行预测；不分行时为空。任意线程可读。 */
  public Optional<DivisionForecast> forecast(String trainName) {
    return trainName == null
        ? Optional.empty()
        : Optional.ofNullable(forecasts.get(key(trainName)));
  }

  // ---------------------------------------------------------------- 每拍

  void tick() {
    Instant now = Instant.now();
    if (!pending.isEmpty()) {
      for (Pending item : List.copyOf(pending.values())) {
        if (attempt(item, now)) {
          pending.remove(key(item.trainName()));
          stillTicks.remove(key(item.trainName()));
        }
      }
    }
    if (!waiting.isEmpty()) {
      followUp(now);
    }
    if (--forecastCountdown <= 0) {
      forecastCountdown = FORECAST_ROUNDS;
      refreshForecasts();
    }
  }

  /** 试着执行一条待执行解编；办完（拆了、放弃了）时为 true。 */
  private boolean attempt(Pending item, Instant now) {
    MinecartGroup group = holder(item.properties());
    if (group == null || !group.isValid()) {
      // 车上值乘的人可能已在这一站结算、等着拆：告诉他们没拆，免得一直记着
      crew.notSplit(item.trainName());
      return true;
    }
    if (!stillAt(item.properties(), item)) {
      warn("DECP_SKIPPED train=" + item.trainName() + " reason=left-station");
      crew.notSplit(item.trainName());
      return true;
    }
    TimetableService.SplitPlan plan = planOf(item);
    if (plan.skips()) {
      debug("DECP_SKIPPED train=" + item.trainName() + " reason=timetable-no-split");
      crew.notSplit(item.trainName());
      return true;
    }
    if (!settled(group, item)) {
      Duration limit = crewed(item) ? CREW_SETTLE_TIMEOUT : SETTLE_TIMEOUT;
      if (now.isAfter(item.armedAt().plus(limit))) {
        warn("DECP_SKIPPED train=" + item.trainName() + " reason=not-stopped");
        crew.notSplit(item.trainName());
        return true;
      }
      return false;
    }
    if (!execute(item, plan, group, now)) {
      crew.notSplit(item.trainName());
    }
    return true;
  }

  /** 按表运行时表上这一班拆不拆：车没绑交路按指令自己的动作。 */
  private TimetableService.SplitPlan planOf(Pending item) {
    return plugin
        .getTimetableService()
        .map(service -> service.splitPlanOf(item.trainName()))
        .orElse(TimetableService.SplitPlan.UNBOUND);
  }

  /** 车还在 DECP 那一站（交路与下标没变）。 */
  private boolean stillAt(TrainProperties properties, Pending item) {
    Optional<UUID> routeUuid =
        plugin.getRouteDefinitionCache().flatMap(cache -> cache.findUuid(item.route()));
    Optional<String> tagRoute =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID);
    boolean sameRoute =
        routeUuid.isEmpty()
            || tagRoute
                .filter(raw -> raw.trim().equalsIgnoreCase(routeUuid.get().toString()))
                .isPresent();
    return sameRoute
        && TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX)
            .filter(index -> index == item.index())
            .isPresent();
  }

  /** 车停稳了、停站计时已经开始（车站；计时一直没开始时停稳三秒）或停稳一秒（路径点）。 */
  private boolean settled(MinecartGroup group, Pending item) {
    String key = key(item.trainName());
    if (group.isMoving()) {
      stillTicks.remove(key);
      return false;
    }
    int ticks = stillTicks.merge(key, (int) TICK_PERIOD, Integer::sum);
    boolean stopped = stopSettled(item, ticks);
    if (crewed(item)) {
      // 有人值乘：站台先接手这一站，再等这一站开过门、这一趟像终点一样结算之后再拆
      return stopped && crew.readyToSplit(item.trainName());
    }
    return stopped;
  }

  /** 停妥：车站等停站计时开始（一直没开始时停稳三秒），路径点停稳一秒。 */
  private boolean stopSettled(Pending item, int ticks) {
    if (item.station()) {
      return remainingDwell(item).isPresent() || ticks >= STATION_SETTLE_TICKS;
    }
    return ticks >= WAYPOINT_SETTLE_TICKS;
  }

  /** 列车此刻停着的这一站要分行，且已停妥（停站计时开始，或停稳三秒；路径点停稳一秒）。车上值乘的人没有停站可做时（站台没交来停站）以此为准在这一站结算。 */
  public boolean settledHere(String trainName) {
    Pending item = trainName == null ? null : pending.get(key(trainName));
    return item != null
        && !planOf(item).skips()
        && stopSettled(item, stillTicks.getOrDefault(key(trainName), 0));
  }

  /**
   * 拆：按表运行时由表上这一班生出的子交路接解下的车，车没绑交路按指令自己的动作。
   *
   * @return 拆开了
   */
  private boolean execute(
      Pending item, TimetableService.SplitPlan plan, MinecartGroup group, Instant now) {
    RouteCouplingDirectives.Decouple directive = item.directive();
    Optional<NodeId> stationNode = stationNode(item);
    if (stationNode.isEmpty()) {
      warn("DECP_SKIPPED train=" + item.trainName() + " reason=station-node-unknown");
      return false;
    }
    NodeId node = stationNode.get();
    CarTags.stampOrigins(group);
    List<Optional<CarConsist.Origin>> cars = new ArrayList<>(group.size());
    for (MinecartMember<?> member : group) {
      cars.add(CarTags.read(member, CarConsist.TAG_CAR_CONSIST).flatMap(CarConsist.Origin::parse));
    }
    Optional<DecoupleCut.Cut> cut = DecoupleCut.resolve(cars, directive.end(), directive.cars());
    if (cut.isEmpty()) {
      warn(
          "DECP_FAILED train="
              + item.trainName()
              + " reason=cut-not-on-consist-boundary cars="
              + group.size()
              + " end="
              + directive.end()
              + " count="
              + directive.cars());
      plan.child().ifPresent(child -> timetable().ifPresent(service -> service.splitFailed(child)));
      return false;
    }
    boolean keepRear = cut.get().end() == RouteCouplingDirectives.End.FRONT;
    Optional<TrainSplitter.Result> result = coupler.splitInService(group, cut.get().at(), keepRear);
    if (result.isEmpty()
        || result.get().outcome() != TrainSplitter.Outcome.SPLIT
        || result.get().detached().isEmpty()
        || result.get().detachedName().isEmpty()) {
      warn(
          "DECP_FAILED train="
              + item.trainName()
              + " reason=split-"
              + result.map(r -> r.outcome().name()).orElse("NO_DISPATCH"));
      plan.child().ifPresent(child -> timetable().ifPresent(service -> service.splitFailed(child)));
      return false;
    }
    MinecartGroup detached = result.get().detached().get();
    String detachedName = result.get().detachedName().get();
    Instant readyAt = now.plusSeconds(Math.max(0, remainingDwell(item).orElse(0)));
    Optional<UUID> routeUuid =
        plugin.getRouteDefinitionCache().flatMap(cache -> cache.findUuid(item.route()));
    routeUuid.ifPresent(
        id -> DetachedHold.mark(detached.getProperties(), detachedName, id, item.index(), node));
    debug(
        "DECP_SPLIT train="
            + item.trainName()
            + " detached="
            + detachedName
            + " cars="
            + detached.size()
            + " end="
            + cut.get().end()
            + (cut.get().end() != directive.end() ? " (other-end)" : "")
            + " action="
            + (plan.child().isPresent() ? "timetable" : directive.action().name()));
    if (crewed(item)) {
      // 值乘的人选继续哪一段：在登记待命、出票之前交给他们，两段车都扣着直到选完
      crew.split(
          new DecoupleCrew.Split(
              item.trainName(),
              detachedName,
              segmentLabel(
                  cut.get().end() != RouteCouplingDirectives.End.FRONT,
                  group.size(),
                  Optional.of(destinations.ofRoute(routeUuid).name())
                      .filter(name -> !name.isBlank())
                      .orElse("?")),
              segmentLabel(
                  cut.get().end() == RouteCouplingDirectives.End.FRONT,
                  detached.size(),
                  detachedDestination(item, plan))));
    }
    // 先记好谁接它，再登记待命：登记会立即触发待处理票据派发
    Waiting held = arrange(item, plan, detachedName, readyAt, now);
    if (held != null) {
      waiting.put(key(detachedName), held);
    }
    register(detachedName, detached.getProperties(), node, readyAt);
    return true;
  }

  /** 解下的车由谁接：按表由子交路，否则按指令的动作。返回等着被接走的情况；IDLE 时为 null。 */
  private Waiting arrange(
      Pending item,
      TimetableService.SplitPlan plan,
      String detachedName,
      Instant readyAt,
      Instant now) {
    if (plan.child().isPresent()) {
      TimetableService.DutyKey child = plan.child().get();
      timetable().ifPresent(service -> service.pinSplit(child, detachedName, plan.tripless()));
      return new Waiting(
          detachedName,
          Optional.empty(),
          Optional.of(child),
          false,
          readyAt,
          readyAt.plus(TIMETABLE_WAIT),
          now);
    }
    RouteCouplingDirectives.Decouple directive = item.directive();
    return switch (directive.action()) {
      case GOTO -> gotoTicket(item, directive.target().orElse(""), detachedName, readyAt, now)
          .map(
              ticketId ->
                  new Waiting(
                      detachedName,
                      Optional.of(ticketId),
                      Optional.empty(),
                      false,
                      readyAt,
                      readyAt.plus(GOTO_WAIT),
                      now))
          .orElse(null);
      case RETURN -> new Waiting(
          detachedName,
          Optional.empty(),
          Optional.empty(),
          true,
          readyAt,
          readyAt.plus(RETURN_GIVE_UP),
          readyAt);
      case IDLE -> null;
    };
  }

  /** 给解下的车出一张 GOTO 交路的票（只给它接）；交路找不到或拼不出发车服务时为空（退成 IDLE）。 */
  private Optional<UUID> gotoTicket(
      Pending item, String targetCode, String detachedName, Instant readyAt, Instant now) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    Optional<SpawnManager> spawnManager = plugin.getSpawnManager();
    Optional<UUID> target = destinations.gotoTarget(item.route(), item.directive());
    Optional<SpawnService> service =
        target.flatMap(
            id ->
                spawnManager.flatMap(
                    manager ->
                        RouteSpawnServices.forRoute(
                            manager,
                            cache.get(),
                            plugin.getStorageManager().provider(),
                            id,
                            this::debug)));
    if (service.isEmpty() || spawnManager.isEmpty()) {
      warn(
          "DECP_GOTO_UNAVAILABLE train=" + detachedName + " target=" + targetCode + " action=idle");
      return Optional.empty();
    }
    UUID ticketId = UUID.randomUUID();
    SpawnTicket ticket =
        new SpawnTicket(
            ticketId,
            service.get(),
            readyAt,
            readyAt,
            now,
            0,
            0L,
            Optional.empty(),
            Optional.empty(),
            Optional.of("decp:" + detachedName),
            TripSource.SCHEDULED,
            0,
            Optional.empty());
    gotoTickets.put(ticketId, key(detachedName));
    spawnManager.get().requeue(ticket);
    return Optional.of(ticketId);
  }

  /** 解下的车登记成本站待命车（主干停站结束才就绪），并通知发车侧。 */
  private void register(
      String trainName, TrainProperties properties, NodeId node, Instant readyAt) {
    Optional<LayoverRegistry> registry = plugin.getLayoverRegistry();
    if (registry.isEmpty()) {
      return;
    }
    registry
        .get()
        .register(
            trainName,
            TerminalKeyResolver.toTerminalKey(node),
            node,
            readyAt,
            TrainLinker.trainLevelTags(properties));
    registry
        .get()
        .get(trainName)
        .ifPresent(
            candidate ->
                plugin
                    .getSpawnTicketAssigner()
                    .ifPresent(assigner -> assigner.onLayoverRegistered(candidate)));
  }

  // ---------------------------------------------------------------- 分行预测

  /** 按交路进度重算每列车的分行预测，整份换上。 */
  private void refreshForecasts() {
    Optional<RouteProgressRegistry> registry = plugin.getRouteProgressRegistry();
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (registry.isEmpty() || cache.isEmpty()) {
      forecasts = Map.of();
      return;
    }
    Map<RouteId, List<Integer>> decouples = new HashMap<>();
    Map<String, DivisionForecast> next = new HashMap<>();
    for (RouteProgressRegistry.RouteProgressEntry entry : registry.get().snapshot().values()) {
      try {
        forecastOf(entry, cache.get(), decouples)
            .ifPresent(found -> next.put(key(entry.trainName()), found));
      } catch (RuntimeException ex) {
        debug("DECP_FORECAST_FAILED train=" + entry.trainName() + " error=" + ex);
      }
    }
    forecasts = Map.copyOf(next);
  }

  /**
   * 一列车的下一个分行站：交路里此后第一个写了 DECP 的车站（此刻停着的这一站只在还没拆时算），按表时表上这一班要拆、按现场编组切得开才算。
   *
   * @param decouples 本轮已查过的交路 → DECP 下标（同一交路的车只解析一次）
   */
  private Optional<DivisionForecast> forecastOf(
      RouteProgressRegistry.RouteProgressEntry entry,
      RouteDefinitionCache cache,
      Map<RouteId, List<Integer>> decouples) {
    List<RouteStop> stops = cache.listStops(entry.routeId());
    List<Integer> indices =
        decouples.computeIfAbsent(entry.routeId(), id -> decoupleIndices(stops));
    if (indices.isEmpty()) {
      return Optional.empty();
    }
    Pending here = pending.get(key(entry.trainName()));
    int index = -1;
    for (int candidate : indices) {
      boolean ahead = candidate > entry.currentIndex();
      boolean dividingHere =
          candidate == entry.currentIndex()
              && here != null
              && here.index() == candidate
              && here.route().equals(entry.routeId());
      if (ahead || dividingHere) {
        index = candidate;
        break;
      }
    }
    OptionalInt end = RouteTerminals.endOfOperationIndex(stops);
    if (index < 0 || (end.isPresent() && index >= end.getAsInt())) {
      // 在运营终点（或之后）拆：乘客都在终点下车，不给乘客看
      return Optional.empty();
    }
    Optional<String> stationId = destinations.stationIdOf(stops.get(index));
    Optional<RouteCouplingDirectives.Decouple> directive =
        RouteCouplingDirectives.decouple(stops.get(index));
    TimetableService.SplitPlan plan =
        plugin
            .getTimetableService()
            .map(service -> service.splitPlanOf(entry.trainName()))
            .orElse(TimetableService.SplitPlan.UNBOUND);
    MinecartGroup group = groupOf(entry.trainName());
    // 路径点上的解编不给乘客看；表上这一班不拆时不预测
    if (stationId.isEmpty() || directive.isEmpty() || plan.skips() || group == null) {
      return Optional.empty();
    }
    CarTags.stampOrigins(group);
    List<Optional<CarConsist.Origin>> cars = new ArrayList<>(group.size());
    for (MinecartMember<?> member : group) {
      cars.add(CarTags.read(member, CarConsist.TAG_CAR_CONSIST).flatMap(CarConsist.Origin::parse));
    }
    Optional<DecoupleCut.Cut> cut =
        DecoupleCut.resolve(cars, directive.get().end(), directive.get().cars());
    if (cut.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new DivisionForecast(
            entry.routeId().value(),
            index,
            stationId.get(),
            destinations.stationName(stationId.get()),
            DivisionForecast.parts(
                group.size(),
                cut.get(),
                destinations.ofStops(stops),
                destinations.detached(entry.routeId(), directive.get(), plan))));
  }

  /** 交路里写了 DECP 的停靠点下标，从前往后。 */
  private static List<Integer> decoupleIndices(List<RouteStop> stops) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < stops.size(); i++) {
      if (RouteCouplingDirectives.decouple(stops.get(i)).isPresent()) {
        out.add(i);
      }
    }
    return List.copyOf(out);
  }

  /** 按车名找到的列车；不在了时为空。 */
  private static MinecartGroup groupOf(String trainName) {
    com.bergerkiller.bukkit.tc.properties.TrainProperties properties =
        com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore.get(trainName);
    MinecartGroup group = properties == null ? null : properties.getHolder();
    return group != null && group.isValid() ? group : null;
  }

  /** 车上有人值乘（解编前问一次）。 */
  private boolean crewed(Pending item) {
    return crew.crewed(item.trainName());
  }

  /** 一段车厢的说明：“前 4 节，开往南渡”。 */
  private String segmentLabel(boolean front, int cars, String destination) {
    String end =
        plugin.getLocaleManager().text(front ? "coupler.decouple.front" : "coupler.decouple.rear");
    return plugin
        .getLocaleManager()
        .text("coupler.decouple.segment")
        .replace("<end>", end)
        .replace("<cars>", String.valueOf(cars))
        .replace("<destination>", destination);
  }

  /** 解下那一段去哪里（值乘的人看的说明）：不载客时写本站待命或回库。 */
  private String detachedDestination(Pending item, TimetableService.SplitPlan plan) {
    DivisionForecast.Destination destination =
        destinations.detached(item.route(), item.directive(), plan);
    if (destination.stationId().isPresent()) {
      return destination.name();
    }
    boolean returning =
        plan.child().isPresent()
            ? plan.tripless()
            : item.directive().action() == RouteCouplingDirectives.Action.RETURN;
    return plugin
        .getLocaleManager()
        .text(returning ? "coupler.decouple.return" : "coupler.decouple.idle");
  }

  /** 解编站的站台节点：实际到达的（DYNAMIC 选台结果）优先，没有到达记录时取交路声明的。 */
  private Optional<NodeId> stationNode(Pending item) {
    Optional<NodeId> arrived =
        plugin
            .getRouteProgressRegistry()
            .flatMap(registry -> registry.arrivalNodeAt(item.trainName(), item.index()));
    if (arrived.isPresent()) {
      return arrived;
    }
    return plugin
        .getRouteDefinitionCache()
        .flatMap(cache -> cache.findUuid(item.route()).flatMap(cache::findById))
        .map(RouteDefinition::waypoints)
        .filter(waypoints -> item.index() < waypoints.size())
        .map(waypoints -> waypoints.get(item.index()));
  }

  /** 主干车这一站停站还剩几秒：停站计时按调度车名或 TrainCarts 车名记，两个都查。 */
  private Optional<Integer> remainingDwell(Pending item) {
    return plugin
        .getDwellRegistry()
        .flatMap(
            registry ->
                registry
                    .remainingSeconds(item.trainName())
                    .or(() -> registry.remainingSeconds(item.properties().getTrainName())));
  }

  /** 等着被接走的车：被派走或不在了就不再管；到点没被接走就放手；回库的按时派。 */
  private void followUp(Instant now) {
    Optional<LayoverRegistry> registry = plugin.getLayoverRegistry();
    Iterator<Map.Entry<String, Waiting>> it = waiting.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Waiting> entry = it.next();
      String key = entry.getKey();
      Waiting held = entry.getValue();
      Optional<LayoverRegistry.LayoverCandidate> candidate =
          registry.flatMap(r -> r.get(held.trainName()));
      if (candidate.isEmpty()) {
        // 被派走（派出时改了名）或车没了
        release(key, held, false);
        it.remove();
        continue;
      }
      String trainName = candidate.get().trainName();
      if (now.isAfter(held.deadline())) {
        warn("DECP_WAIT_EXPIRED train=" + trainName + " action=idle");
        release(key, held, true);
        it.remove();
        continue;
      }
      if (held.returning()
          && !now.isBefore(held.nextReturnAt())
          && crewHolding(trainName).isPresent()) {
        // 值乘的人还在选继续哪一段（可能改驾这一段）：选完再派回库
        entry.setValue(held.retryReturnAt(now.plus(RETURN_RETRY)));
        continue;
      }
      if (held.returning() && !now.isBefore(held.nextReturnAt())) {
        ReclaimManager.CalledReturn result =
            plugin
                .getReclaimManager()
                .map(reclaim -> reclaim.returnCalledTrain(trainName, now))
                .orElse(ReclaimManager.CalledReturn.BLOCKED);
        if (result == ReclaimManager.CalledReturn.BLOCKED) {
          entry.setValue(held.retryReturnAt(now.plus(RETURN_RETRY)));
        } else {
          debug("DECP_RETURN train=" + trainName + " result=" + result);
          it.remove();
        }
      }
    }
  }

  /** 不再管这辆车：撤下只给它接的票；到点放手的按表子交路记为落空。 */
  private void release(String key, Waiting held, boolean expired) {
    held.ticketId()
        .ifPresent(
            ticketId -> {
              gotoTickets.remove(ticketId);
              if (expired) {
                plugin.getSpawnManager().ifPresent(manager -> manager.withdraw(ticketId));
              }
            });
    held.child()
        .ifPresent(
            child ->
                timetable()
                    .ifPresent(
                        service -> {
                          service.unpinSplit(key);
                          if (expired) {
                            service.splitFailed(child);
                          }
                        }));
  }

  // ---------------------------------------------------------------- 工具

  private Optional<TimetableService> timetable() {
    return plugin.getTimetableService();
  }

  private void debug(String message) {
    plugin.getLoggerManager().debug(message);
  }

  private void warn(String message) {
    plugin.getLoggerManager().warn(message);
  }

  private static MinecartGroup holder(TrainProperties properties) {
    try {
      return properties.getHolder();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String key(String trainName) {
    return trainName.trim().toLowerCase(Locale.ROOT);
  }
}
