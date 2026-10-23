package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.actions.WaitAction;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.CompositeSpawnPattern;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistKey;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.DepotSpawnPattern;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.RouteSpawnServices;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TimetableSpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

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

  /** 车站停站计时开始后还要连续停稳多久才动手（tick）：停站计时在站台对位之前就开始了。 */
  private static final int STATION_DWELL_SETTLE_TICKS = 20;

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

  /** 出车编组写法 → 各段节数（车头到车尾）；解析不出的记为空。主线程写，任意线程读。 */
  private final Map<String, Optional<List<Integer>>> partSizes = new ConcurrentHashMap<>();

  /** 别的线程查编组时缓存里还没有的交路：主线程下一次刷新预测时解析（编组写法与车库牌子只能在主线程读）。 */
  private final Set<UUID> patternRequests = ConcurrentHashMap.newKeySet();

  /** 在运营终点分行、解下的一段接着载客时，留下（在本站终到、不再载客）的那一段：车名键 → 分行时的交路与下标。车离开这一站（进度变了）或不在了就删。 主线程写，任意线程读。 */
  private final Map<String, Remainder> remainders = new ConcurrentHashMap<>();

  private record Remainder(String routeId, int index) {}

  /**
   * 刚在这一站拆过、留下的一段（车名键）→ 拆开时的交路、下标与站台。留下的一段往前开时车头会再压过本站牌子：这一站不能再拆一次，AutoStation 也不再停一次；
   * 离开这一站（进度变了）或不在了就删。主线程写，任意线程读。
   */
  private final Map<String, SplitAt> splitDone = new ConcurrentHashMap<>();

  private record SplitAt(String routeId, int index, NodeId platform) {}

  /**
   * 刚在本站解下、等着接下一班的车（车名键）→ 那一班的计划发车：重新触发本站牌子时停站延到这个时刻，车门开到快发车再关，不先关门干等。
   * 车被派走（不在待命池）或过了这个时刻一分钟就删。主线程写，任意线程读。
   */
  private final Map<String, DoorHold> doorHolds = new ConcurrentHashMap<>();

  private record DoorHold(String trainName, Instant departure) {}

  /**
   * 刚在本站拆开、解下的车（车名键）→ 拆开以后的车厢标识（{@link DivisionForecast#divided}）与留下那一段停在哪里。两段都还停在分行站时有效：
   * 解下的车不再待命（开走了）或留下的一段离开就删。主线程写，任意线程读。
   */
  private final Map<String, Divided> divided = new ConcurrentHashMap<>();

  private record Divided(
      DivisionForecast forecast,
      String detachedName,
      String keptKey,
      String keptRoute,
      int keptIndex) {}

  /** 没写 {@code spawn_train_pattern} 的主干交路 → 首站 CRET 车库牌子上的编组。主线程写，任意线程读。 */
  private final Map<UUID, Optional<String>> depotPatterns = new ConcurrentHashMap<>();

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
    partSizes.clear();
    depotPatterns.clear();
    patternRequests.clear();
    remainders.clear();
    splitDone.clear();
    doorHolds.clear();
    divided.clear();
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
    if (directive.isEmpty() || detachedAt(properties, route, index)) {
      return 0;
    }
    String key = key(trainName);
    SplitAt done = splitDone.get(key);
    if (done != null && done.index() == index && done.routeId().equalsIgnoreCase(route.value())) {
      // 这一站已经拆过：留下的一段往前开时又压过了本站的牌子
      return 0;
    }
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
   * AutoStation 停站：刚在本站解下、等着接下一班的车，停站要延到的时刻（那一班的计划发车）。任意线程可读。
   *
   * @return 不是这样的车时为空
   */
  public Optional<Instant> holdDoorsUntil(String trainName) {
    return trainName == null
        ? Optional.empty()
        : Optional.ofNullable(doorHolds.get(key(trainName))).map(DoorHold::departure);
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

  /** 这列车是刚在站台 {@code platform} 拆开后留下的一段、还没离开这一站。 */
  public boolean splitHere(String trainName, NodeId platform) {
    SplitAt done = trainName == null ? null : splitDone.get(key(trainName));
    return done != null && done.platform().equals(platform);
  }

  /** 解下的车正等着被接走（闲置回收不收它）。 */
  public boolean holds(String trainName) {
    return trainName != null && waiting.containsKey(key(trainName));
  }

  /**
   * 这辆车是在交路 {@code routeId} 第 {@code index} 站（运营终点）分行后留下、在这里终到不再载客的一段，而解下的一段接着载客。站牌据此只列解下的一段。
   * 任意线程可读。
   */
  public boolean remainsAt(String trainName, String routeId, int index) {
    Remainder remainder = trainName == null ? null : remainders.get(key(trainName));
    return remainder != null
        && remainder.index() == index
        && remainder.routeId().equalsIgnoreCase(routeId);
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
    if (detachedAt(item.properties(), item.route(), item.index())) {
      // 就是在这一站解下的那一段：照搬了主干的交路与进度，到站通知可能先于解下时写的标记到来
      debug("DECP_SKIPPED train=" + item.trainName() + " reason=detached-here");
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

  /** 这辆车是在交路 {@code route} 第 {@code index} 站解下来的（带着解下时写的标记）。 */
  private boolean detachedAt(TrainProperties properties, RouteId route, int index) {
    Optional<DetachedHold.Hold> hold =
        TrainTagHelper.readTagValue(properties, DetachedHold.TAG).flatMap(DetachedHold.Hold::parse);
    if (hold.isEmpty() || hold.get().index() != index) {
      return false;
    }
    return plugin
        .getRouteDefinitionCache()
        .flatMap(cache -> cache.findUuid(route))
        .filter(hold.get().routeId()::equals)
        .isPresent();
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
    if (group.isMoving() || stillToMove(group)) {
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

  /** 停妥：车站等停站计时开始后再停稳一秒（一直没开始时停稳三秒），路径点停稳一秒。 */
  private boolean stopSettled(Pending item, int ticks) {
    if (item.station()) {
      return (remainingDwell(item).isPresent() && ticks >= STATION_DWELL_SETTLE_TICKS)
          || ticks >= STATION_SETTLE_TICKS;
    }
    return ticks >= WAYPOINT_SETTLE_TICKS;
  }

  /** 车上还挂着等待以外的动作（站台对位、发车）：车还要动。对位途中车速可能有一拍降到零，这时拆开会丢掉对位动作，两段车停在对位点之前（欠标）。 */
  private static boolean stillToMove(MinecartGroup group) {
    return group.getActions().getScheduledActions().stream()
        .anyMatch(action -> !(action instanceof WaitAction));
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
    if (item.station()) {
      // 按表接下一班：停站延到那一班的计划发车（重新触发牌子时 AutoStation 来取）
      plan.child()
          .flatMap(child -> timetable().flatMap(service -> service.firstTripOf(child)))
          .map(TimetableService.DueTrip::departure)
          .filter(departure -> departure.isAfter(now))
          .ifPresent(
              departure -> doorHolds.put(key(detachedName), new DoorHold(detachedName, departure)));
    }
    if (item.station()) {
      // 解下的一段没有再压过站牌，本站的停站会话只管留下的一段：给它重新触发本站牌子，自己开门、停站、关门
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () ->
                  StationSignTrigger.arrive(plugin, detached, node)
                      .ifPresent(
                          reason ->
                              warn(
                                  "DECP_ARRIVAL_FAILED train="
                                      + detachedName
                                      + " platform="
                                      + node.value()
                                      + " reason="
                                      + reason)));
    }
    if (item.station()
        && atOperationEnd(item)
        && destinations.detached(item.route(), directive, plan).inService()) {
      remainders.put(key(item.trainName()), new Remainder(item.route().value(), item.index()));
    }
    splitDone.put(key(item.trainName()), new SplitAt(item.route().value(), item.index(), node));
    if (item.station()) {
      rememberDivided(item, detachedName, cut.get());
    }
    // 先记好谁接它，再登记待命：登记会立即触发待处理票据派发
    Waiting held = arrange(item, plan, detachedName, readyAt, now);
    if (held != null) {
      waiting.put(key(detachedName), held);
    }
    register(detachedName, detached.getProperties(), node, readyAt);
    return true;
  }

  /** 拆开那一刻把这一站的分行预测转给解下的车（站台屏按它画拆开后的车厢标识）：取切下的那一头接着跑的交路。没有这一站的预测时不记。 */
  private void rememberDivided(Pending item, String detachedName, DecoupleCut.Cut cut) {
    DivisionForecast before = forecasts.get(key(item.trainName()));
    if (before == null
        || before.stopIndex() != item.index()
        || !before.routeId().equalsIgnoreCase(item.route().value())) {
      return;
    }
    // 预测里解下的一段要与实际切下的那一头对得上（现场改切另一头时两段的排法就不是预测的了，不记）
    boolean frontDetached = cut.end() == RouteCouplingDirectives.End.FRONT;
    List<DivisionForecast.Part> parts = before.parts();
    Optional<DivisionForecast.Part> part =
        parts.stream()
            .filter(DivisionForecast.Part::detached)
            .findFirst()
            .filter(found -> (parts.indexOf(found) == 0) == frontDetached);
    part.flatMap(before::dividedFor)
        .ifPresent(
            forecast ->
                divided.put(
                    key(detachedName),
                    new Divided(
                        forecast,
                        detachedName,
                        key(item.trainName()),
                        item.route().value(),
                        item.index())));
  }

  /**
   * 刚在本站拆开、解下的这辆车：拆开以后的车厢标识（{@link DivisionForecast#divided} 为 true），交路与下标是它接着跑的那一班。两段都还停在分行站时才有。
   * 任意线程可读。
   */
  public Optional<DivisionForecast> dividedHere(String trainName) {
    Divided found = trainName == null ? null : divided.get(key(trainName));
    return found == null ? Optional.empty() : Optional.of(found.forecast());
  }

  /** 解编站就是本交路的运营终点。 */
  private boolean atOperationEnd(Pending item) {
    OptionalInt end =
        plugin
            .getRouteDefinitionCache()
            .map(cache -> RouteTerminals.endOfOperationIndex(cache.listStops(item.route())))
            .orElse(OptionalInt.empty());
    return end.isPresent() && end.getAsInt() == item.index();
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
    // 站台屏（异步）查过、缓存里还没有的编组：在这里解析，每条交路只解析一次
    for (UUID routeId : List.copyOf(patternRequests)) {
      patternRequests.remove(routeId);
      plannedPatternOf(routeId);
    }
    Optional<RouteProgressRegistry> registry = plugin.getRouteProgressRegistry();
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (registry.isEmpty() || cache.isEmpty()) {
      forecasts = Map.of();
      return;
    }
    Map<RouteId, List<Integer>> decouples = new HashMap<>();
    Map<String, DivisionForecast> next = new HashMap<>();
    Map<String, RouteProgressRegistry.RouteProgressEntry> progress = registry.get().snapshot();
    // 拆开以后的车厢标识：解下的车开走（不再待命）、留下的一段离开分行站或不在了就撤掉
    Optional<LayoverRegistry> layovers = plugin.getLayoverRegistry();
    divided
        .values()
        .removeIf(
            entry ->
                layovers.flatMap(r -> r.get(entry.detachedName())).isEmpty()
                    || progress.values().stream()
                        .noneMatch(
                            found ->
                                key(found.trainName()).equals(entry.keptKey())
                                    && found.currentIndex() == entry.keptIndex()
                                    && found
                                        .routeId()
                                        .value()
                                        .equalsIgnoreCase(entry.keptRoute())));
    // 解下的车被派走（不在待命池）或早过了那一班的发车：不再延停站
    Instant now = Instant.now();
    doorHolds
        .entrySet()
        .removeIf(
            hold ->
                now.isAfter(hold.getValue().departure().plusSeconds(60))
                    || layovers.flatMap(found -> found.get(hold.getValue().trainName())).isEmpty());
    // 拆过的一段离开这一站（进度变了）或不在了：以后再到这一站照常拆
    splitDone
        .entrySet()
        .removeIf(
            done ->
                progress.values().stream()
                    .noneMatch(
                        entry ->
                            key(entry.trainName()).equals(done.getKey())
                                && entry.currentIndex() == done.getValue().index()
                                && entry
                                    .routeId()
                                    .value()
                                    .equalsIgnoreCase(done.getValue().routeId())));
    // 留下的一段离开分行站（进度变了）或不在了：站牌照常列它
    remainders
        .entrySet()
        .removeIf(
            remainder ->
                progress.values().stream()
                    .noneMatch(
                        entry ->
                            key(entry.trainName()).equals(remainder.getKey())
                                && entry.currentIndex() == remainder.getValue().index()
                                && entry
                                    .routeId()
                                    .value()
                                    .equalsIgnoreCase(remainder.getValue().routeId())));
    for (RouteProgressRegistry.RouteProgressEntry entry : progress.values()) {
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
   * 分行站就是运营终点时，只在解下的一段接着载客（GOTO 到载客交路）时算，留下的一段在这里不再载客。这一趟已经跑完、停在终点等下一趟时按下一趟算
   * （站牌按下一趟列出这辆车，下一趟要分行就提前写上）。
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
    Pending here = pending.get(key(entry.trainName()));
    for (int candidate : indices) {
      boolean ahead = candidate > entry.currentIndex();
      boolean dividingHere =
          candidate == entry.currentIndex()
              && here != null
              && here.index() == candidate
              && here.route().equals(entry.routeId());
      if (ahead || dividingHere) {
        TimetableService.SplitPlan plan =
            plugin
                .getTimetableService()
                .map(service -> service.splitPlanOf(entry.trainName()))
                .orElse(TimetableService.SplitPlan.UNBOUND);
        return forecastAt(entry.trainName(), entry.routeId(), stops, candidate, plan);
      }
    }
    OptionalInt end = RouteTerminals.endOfOperationIndex(stops);
    if (end.isEmpty() || entry.currentIndex() < end.getAsInt()) {
      // 这一趟还没跑完：不拿下一趟的分行说这一趟
      return Optional.empty();
    }
    return nextTripForecast(entry.trainName(), cache, decouples);
  }

  /** 停在终点等下一趟的车：下一趟（按表已定）要分行时的预测。 */
  private Optional<DivisionForecast> nextTripForecast(
      String trainName, RouteDefinitionCache cache, Map<RouteId, List<Integer>> decouples) {
    Optional<TimetableService> service = plugin.getTimetableService();
    Optional<RouteDefinition> next =
        service
            .flatMap(timetables -> timetables.nextDepartureOf(trainName))
            .flatMap(due -> cache.findById(due.trip().routeId()));
    if (next.isEmpty()) {
      return Optional.empty();
    }
    RouteId route = next.get().id();
    List<RouteStop> stops = cache.listStops(route);
    List<Integer> indices = decouples.computeIfAbsent(route, id -> decoupleIndices(stops));
    if (indices.isEmpty()) {
      return Optional.empty();
    }
    return forecastAt(
        trainName, route, stops, indices.get(0), service.get().splitPlanOfNext(trainName));
  }

  /** 运行中的列车在交路 {@code route} 第 {@code index} 站分行的预测：按车上各节的来源编组切。 */
  private Optional<DivisionForecast> forecastAt(
      String trainName,
      RouteId route,
      List<RouteStop> stops,
      int index,
      TimetableService.SplitPlan plan) {
    MinecartGroup group = groupOf(trainName);
    if (group == null) {
      return Optional.empty();
    }
    return forecastAt(
        route,
        stops,
        index,
        plan,
        () -> {
          CarTags.stampOrigins(group);
          List<Optional<CarConsist.Origin>> cars = new ArrayList<>(group.size());
          for (MinecartMember<?> member : group) {
            cars.add(
                CarTags.read(member, CarConsist.TAG_CAR_CONSIST).flatMap(CarConsist.Origin::parse));
          }
          return cars;
        });
  }

  /**
   * 交路 {@code route} 第 {@code index} 站分行的预测；不给乘客看（越过运营终点、路径点、表上不拆、切不开、两段都不再载客）时为空。
   *
   * @param cars 车头到车尾各节的来源编组（前面几条都过了才取）
   */
  private Optional<DivisionForecast> forecastAt(
      RouteId route,
      List<RouteStop> stops,
      int index,
      TimetableService.SplitPlan plan,
      Supplier<List<Optional<CarConsist.Origin>>> cars) {
    OptionalInt end = RouteTerminals.endOfOperationIndex(stops);
    if (end.isPresent() && index > end.getAsInt()) {
      // 越过运营终点才拆（回库途中）：车上已经没有乘客
      return Optional.empty();
    }
    boolean atTerminus = end.isPresent() && index == end.getAsInt();
    Optional<String> stationId = destinations.stationIdOf(stops.get(index));
    Optional<RouteCouplingDirectives.Decouple> directive =
        RouteCouplingDirectives.decouple(stops.get(index));
    // 路径点上的解编不给乘客看；表上这一班不拆时不预测
    if (stationId.isEmpty() || directive.isEmpty() || plan.skips()) {
      return Optional.empty();
    }
    List<Optional<CarConsist.Origin>> origins = cars.get();
    Optional<DecoupleCut.Cut> cut =
        DecoupleCut.resolve(origins, directive.get().end(), directive.get().cars());
    if (cut.isEmpty()) {
      return Optional.empty();
    }
    DivisionForecast.Destination detached = destinations.detached(route, directive.get(), plan);
    Optional<DivisionForecast.Destination> kept =
        DivisionForecast.keptDestination(
            atTerminus, destinations.along(route, stops, index), detached);
    if (kept.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new DivisionForecast(
            route.value(),
            index,
            stationId.get(),
            destinations.stationName(stationId.get()),
            DivisionForecast.parts(origins.size(), cut.get(), kept.get(), detached),
            detachedTrip(plan)));
  }

  /** 解下的一段按表接着跑的子交路首班的车次标识；不按表、子交路只回库时为空。 */
  private Optional<String> detachedTrip(TimetableService.SplitPlan plan) {
    if (plan.child().isEmpty() || plan.tripless()) {
      return Optional.empty();
    }
    return plugin
        .getSpawnManager()
        .filter(TimetableSpawnManager.class::isInstance)
        .map(TimetableSpawnManager.class::cast)
        .flatMap(manager -> manager.firstServiceTripOf(plan.child().get()));
  }

  /**
   * 表上还没出车的一班（站牌上的预测行、待发行）在哪一站分行：交路里第一个写了 DECP 的车站，按出车编组各段的节数切，与现场按来源编组切同一条规则。 其余同 {@link
   * #forecast}：表上这一班不拆、切不开、两段都不再载客时为空。任意线程可调；编组写法只在主线程解析，别的线程遇到没解析过的写法时为空。
   *
   * @param planned 表上那一班
   */
  public Optional<DivisionForecast> plannedForecast(TimetableService.PlannedTrip planned) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (planned == null || planned.split().skips() || cache.isEmpty()) {
      return Optional.empty();
    }
    Optional<RouteDefinition> route = cache.get().findById(planned.routeId());
    if (route.isEmpty()) {
      return Optional.empty();
    }
    List<RouteStop> stops = cache.get().listStops(route.get().id());
    List<Integer> indices = decoupleIndices(stops);
    if (indices.isEmpty()) {
      return Optional.empty();
    }
    Optional<PlannedPattern> pattern = plannedPatternOf(planned.routeId());
    if (pattern.isEmpty()) {
      return Optional.empty();
    }
    return forecastAt(
        route.get().id(),
        stops,
        indices.get(0),
        planned.split(),
        () -> plannedCars(pattern.get().sizes()));
  }

  /**
   * 交路出车的组合编组：各段写法与节数（来源见 {@link #trunkPatternOf}）。站牌按表预告分行、联挂用。任意线程可调：缓存里没有时，主线程上当场解析，
   * 别的线程登记下来、等主线程下一次刷新预测时解析（这一次为空）。不是组合写法时为空。
   *
   * @param routeId 交路
   */
  public Optional<PlannedPattern> plannedPatternOf(UUID routeId) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (routeId == null || cache.isEmpty()) {
      return Optional.empty();
    }
    Optional<RouteDefinition> route = cache.get().findById(routeId);
    if (route.isEmpty()) {
      return Optional.empty();
    }
    Optional<String> pattern =
        trunkPatternOf(routeId, cache.get(), cache.get().listStops(route.get().id()))
            .flatMap(ConsistKey::tidy);
    Optional<List<Integer>> sizes = pattern.flatMap(this::partSizesOf);
    // 只登记还没读过的：车库牌子没读过，或组合写法还没解析过；已知没有的不再反复登记
    boolean unread =
        pattern.isEmpty()
            ? !depotPatterns.containsKey(routeId)
            : CompositeSpawnPattern.isComposite(pattern.get())
                && !partSizes.containsKey(pattern.get());
    if (sizes.isEmpty() && unread && !Bukkit.isPrimaryThread()) {
      patternRequests.add(routeId);
    }
    return sizes.map(
        found -> new PlannedPattern(CompositeSpawnPattern.parts(pattern.get()), found));
  }

  /**
   * 组合编组。
   *
   * @param parts 各段写法
   * @param sizes 各段节数，与 {@code parts} 一一对应
   */
  public record PlannedPattern(List<String> parts, List<Integer> sizes) {

    public PlannedPattern {
      parts = List.copyOf(parts);
      sizes = List.copyOf(sizes);
    }

    /** 一共几节。 */
    public int cars() {
      return sizes.stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * 整列里首段或末段是 {@code part} 这几段（一方车底的编组，不分大小写）时它们的节数；对不上时为空。纯计算。
     *
     * @param part 一方车底的编组写法
     */
    public java.util.OptionalInt carsOfEnd(String part) {
      List<String> wanted = CompositeSpawnPattern.parts(part);
      int n = wanted.size();
      if (n == 0 || n >= parts.size()) {
        return java.util.OptionalInt.empty();
      }
      if (sameParts(parts.subList(0, n), wanted)) {
        return java.util.OptionalInt.of(
            sizes.subList(0, n).stream().mapToInt(Integer::intValue).sum());
      }
      if (sameParts(parts.subList(parts.size() - n, parts.size()), wanted)) {
        return java.util.OptionalInt.of(
            sizes.subList(parts.size() - n, parts.size()).stream()
                .mapToInt(Integer::intValue)
                .sum());
      }
      return java.util.OptionalInt.empty();
    }

    private static boolean sameParts(List<String> first, List<String> second) {
      for (int i = 0; i < first.size(); i++) {
        if (!first.get(i).equalsIgnoreCase(second.get(i))) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * 主干交路分行前的编组，与编表切分时同一个来源：交路写明的 {@code spawn_train_pattern}，其次首站 CRET 指向的车库牌子。交路定的车型是出车时的编组，
   * 中途挂上回程支线的车以后就不是分行时的编组，不能用。车库牌子只在主线程、区块已加载时读，读到的按交路记下。
   */
  private Optional<String> trunkPatternOf(
      UUID routeId, RouteDefinitionCache cache, List<RouteStop> stops) {
    Optional<String> declared =
        cache.findRecord(routeId).flatMap(record -> DepotSpawnPattern.fromRoute(record.route()));
    if (declared.isPresent()) {
      return declared;
    }
    Optional<String> known = depotPatterns.get(routeId);
    SignNodeRegistry registry = plugin.getSignNodeRegistry();
    if (known != null || registry == null || !Bukkit.isPrimaryThread()) {
      return known == null ? Optional.empty() : known;
    }
    Optional<NodeId> depot =
        SpawnDirectiveParser.findDirectiveTarget(stops, "CRET").map(NodeId::of);
    if (depot.isEmpty()) {
      depotPatterns.put(routeId, Optional.empty());
      return Optional.empty();
    }
    DepotSpawnPattern.SignRead read = DepotSpawnPattern.readLoaded(registry, depot.get());
    if (read.loaded()) {
      depotPatterns.put(routeId, read.pattern());
    }
    return read.pattern();
  }

  /** 组合写法各段的节数；不是组合写法、解析不出时为空。见 {@link #plannedForecast} 的线程说明。 */
  private Optional<List<Integer>> partSizesOf(String pattern) {
    if (!CompositeSpawnPattern.isComposite(pattern)) {
      return Optional.empty();
    }
    Optional<List<Integer>> known = partSizes.get(pattern);
    if (known != null || !Bukkit.isPrimaryThread()) {
      return known == null ? Optional.empty() : known;
    }
    Optional<List<Integer>> parsed =
        CompositeSpawnPattern.parse(TrainCarts.plugin, pattern)
            .map(CompositeSpawnPattern.Parsed::partSizes);
    partSizes.put(pattern, parsed);
    return parsed;
  }

  /** 按各段节数排出还没出车的编组：每一段是一个整列的来源编组。纯计算。 */
  static List<Optional<CarConsist.Origin>> plannedCars(List<Integer> sizes) {
    List<Optional<CarConsist.Origin>> cars = new ArrayList<>();
    for (int part = 0; part < sizes.size(); part++) {
      CarConsist.Origin origin = new CarConsist.Origin("planned", "part-" + part, sizes.get(part));
      for (int car = 0; car < origin.size(); car++) {
        cars.add(Optional.of(origin));
      }
    }
    return List.copyOf(cars);
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
