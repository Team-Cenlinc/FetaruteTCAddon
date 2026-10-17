package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LiveRailFootprintResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling.CoLocatedPairs;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SimpleTicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;

/**
 * 拆挂：判定能不能动、在指定处拆开、维护同股道列车对（见 {@link CoLocatedPairs}）。只在服务器主线程使用。
 *
 * <p>拆开后两车按现场车身重新占用（下一 tick），之后取两车共用的占用资源登记成对；之后每秒检查一次，两车不再共占任何资源（一方开走或被删）就解除。 对写进两车的标签 {@link
 * #TAG_CO_LOCATED}，重启后两车仍共占资源就恢复。
 */
public final class CouplerService {

  /** 同股道列车对：{@code <对方车名>|<离开方向 x>,<离开方向 z>}。 */
  public static final String TAG_CO_LOCATED = "FTA_COLOCATED";

  /** 拆开后隔多久（tick）开始登记成对：两车先要按现场车身重新占用。 */
  private static final long PAIR_FIRST_DELAY_TICKS = 2L;

  /** 登记成对时两车占用还没就位的重试间隔与次数。 */
  private static final long PAIR_RETRY_TICKS = 10L;

  private static final int PAIR_ATTEMPTS = 6;

  /** 对的检查间隔（tick）。 */
  private static final long MAINTAIN_PERIOD_TICKS = 20L;

  /** 每隔几次检查按标签找回一次对（要读每列车的标签；只有重启或列车迟加载后才找得到）。 */
  private static final int RESTORE_EVERY_MAINTAINS = 30;

  private final FetaruteTCAddon plugin;
  private final CoLocatedPairs pairs;
  private BukkitTask maintainTask;
  private int maintainsUntilRestore;
  private WrenchItem wrench;
  private CouplerWrenchListener wrenchListener;
  private CouplerLinkGate linkGate;
  private final DecoupleService decoupling;
  private final CoupleService coupling;

  public CouplerService(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.pairs = new CoLocatedPairs(this::nodePosition);
    this.decoupling = new DecoupleService(plugin, this);
    this.coupling = new CoupleService(plugin, this);
  }

  /** 联挂指令 COPL 的执行。 */
  public CoupleService coupling() {
    return coupling;
  }

  /** 解编指令 DECP 的执行。 */
  public DecoupleService decoupling() {
    return decoupling;
  }

  /** 接入占用判定、开始维护同股道列车对，接上扳手的交互与碰撞联挂的车钩判定。 */
  public void start() {
    if (plugin.getOccupancyManager() instanceof SimpleOccupancyManager occupancy) {
      // 同股道列车对（拆开后背离对方的一方先走）与联挂进站（后到的车开进对方所在站台）两种豁免
      occupancy.setCoLocationPolicy(
          (request, resource, claim) ->
              pairs.exempts(request, resource, claim)
                  || coupling.exempts(request, resource, claim));
    }
    if (maintainTask == null) {
      maintainTask =
          Bukkit.getScheduler()
              .runTaskTimer(
                  plugin, this::maintainPairs, MAINTAIN_PERIOD_TICKS, MAINTAIN_PERIOD_TICKS);
    }
    if (wrenchListener == null) {
      wrenchListener = new CouplerWrenchListener(plugin, this, wrench());
      linkGate =
          new CouplerLinkGate(
              () ->
                  plugin
                      .getRuntimeDispatchService()
                      .map(dispatch -> dispatch.coupling().operations()));
      Bukkit.getPluginManager().registerEvents(wrenchListener, plugin);
      Bukkit.getPluginManager().registerEvents(linkGate, plugin);
    }
    wrenchListener.start();
    decoupling.start();
    coupling.start();
  }

  /** 撤下占用判定的接入、停止维护，推着的车就地停下，撤下扳手的交互。 */
  public void stop() {
    if (maintainTask != null) {
      maintainTask.cancel();
      maintainTask = null;
    }
    if (wrenchListener != null) {
      wrenchListener.stop();
      HandlerList.unregisterAll(wrenchListener);
      wrenchListener = null;
    }
    if (linkGate != null) {
      HandlerList.unregisterAll(linkGate);
      linkGate = null;
    }
    if (plugin.getOccupancyManager() instanceof SimpleOccupancyManager occupancy) {
      occupancy.setCoLocationPolicy(null);
    }
    pairs.clear();
    decoupling.stop();
    coupling.stop();
  }

  /** 扳手物品。 */
  public WrenchItem wrench() {
    if (wrench == null) {
      wrench = new WrenchItem(plugin, plugin.getLocaleManager());
    }
    return wrench;
  }

  /** 同股道列车对。 */
  public CoLocatedPairs pairs() {
    return pairs;
  }

  /** 当前的扳手配置。 */
  public ConfigManager.CouplerSettings settings() {
    ConfigManager manager = plugin.getConfigManager();
    return manager == null
        ? ConfigManager.CouplerSettings.defaults()
        : manager.current().couplerSettings();
  }

  /** 是不是调度管着的列车。 */
  public boolean managed(MinecartGroup group) {
    TrainProperties properties = group == null ? null : group.getProperties();
    return properties != null
        && plugin
            .getRuntimeDispatchService()
            .map(dispatch -> dispatch.hasFtaRuntimeTag(properties))
            .orElse(false);
  }

  /** 列车名：受管列车取调度认的车名，否则取 TrainCarts 车名。 */
  public String displayName(MinecartGroup group) {
    TrainProperties properties = group.getProperties();
    Optional<RuntimeDispatchService> dispatch = plugin.getRuntimeDispatchService();
    return managed(group) && dispatch.isPresent()
        ? trainName(dispatch.get(), properties)
        : properties.getTrainName();
  }

  /** 登记调车用的车名：TrainCarts 车名，受管列车另加调度认的车名。 */
  Set<String> controlNames(MinecartGroup group) {
    Set<String> names = new HashSet<>();
    names.add(group.getProperties().getTrainName());
    names.add(displayName(group));
    return names;
  }

  /** 受管列车被推动过又没挂上：按现场车身重新占用，下一 tick 刷新信号。插件停用中不做（停用后不能再排任务，重启时本来就按现场重建）。 */
  void settle(MinecartGroup group) {
    Optional<RuntimeDispatchService> dispatch = plugin.getRuntimeDispatchService();
    if (!plugin.isEnabled() || dispatch.isEmpty() || !managed(group)) {
      return;
    }
    dispatch.get().coupling().requestPhysicalRehydration(displayName(group));
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (group.isValid()) {
                dispatch.get().refreshSignal(group);
              }
            });
  }

  // ---------------------------------------------------------------- 判定

  /** 能不能动这列车：非受管列车总能动，受管列车只动不在运营中的。 */
  public CouplerEligibility.Verdict eligibility(MinecartGroup group) {
    if (group == null || !group.isValid()) {
      return CouplerEligibility.Verdict.MOVING;
    }
    TrainProperties properties = group.getProperties();
    Optional<RuntimeDispatchService> dispatch = plugin.getRuntimeDispatchService();
    if (properties == null || dispatch.isEmpty() || !dispatch.get().hasFtaRuntimeTag(properties)) {
      return group.isMoving()
          ? CouplerEligibility.Verdict.MOVING
          : CouplerEligibility.Verdict.ALLOWED;
    }
    String name = trainName(dispatch.get(), properties);
    Optional<LayoverRegistry.LayoverCandidate> layover =
        plugin.getLayoverRegistry().flatMap(registry -> registry.get(name));
    CouplerEligibility.Facts facts =
        new CouplerEligibility.Facts(
            group.isMoving(),
            plugin.getControlAuthority().hasDriver(name),
            plugin.getControlAuthority().guardOperatesDoors(properties),
            plugin
                .getDwellRegistry()
                .flatMap(registry -> registry.remainingSeconds(name))
                .filter(seconds -> seconds > 0)
                .isPresent(),
            dispatch.get().hasDepartureGate(name),
            layover.isPresent(),
            layover.flatMap(LayoverRegistry.LayoverCandidate::dispatchAttempt).isPresent(),
            TrainTagHelper.readTagValue(properties, SimpleTicketAssigner.TAG_CALLED_TRAIN)
                    .isPresent()
                || TrainTagHelper.readTagValue(properties, SimpleTicketAssigner.TAG_CALL_TURNBACK)
                    .isPresent(),
            TrainTagHelper.readTagValue(properties, SimpleTicketAssigner.TAG_EARLY_SPAWN_HOLD)
                .isPresent(),
            plugin
                .getTimetableService()
                .flatMap(service -> service.dutyBindingOf(name))
                .isPresent(),
            returnOutOfService(properties));
    return CouplerEligibility.judge(facts);
  }

  /** 回库车且已过最后一个载客站。 */
  private boolean returnOutOfService(TrainProperties properties) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    Optional<UUID> routeId =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID)
            .flatMap(CouplerService::parseUuid);
    if (cache.isEmpty() || routeId.isEmpty()) {
      return false;
    }
    Optional<RouteOperationType> type =
        cache.get().findRecord(routeId.get()).map(record -> record.route().operationType());
    Optional<RouteDefinition> definition = cache.get().findById(routeId.get());
    if (type.isEmpty() || definition.isEmpty()) {
      return false;
    }
    List<RouteStop> stops = cache.get().listStops(definition.get().id());
    int index =
        TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX).orElse(-1);
    return RouteTerminals.outOfService(type.get(), stops, index);
  }

  // ---------------------------------------------------------------- 拆分

  /**
   * 在第 {@code at} 节车之前拆开（第 0..at-1 节保留原身份）。受管列车先判能不能动；拆开后两车登记成同股道列车对。
   *
   * @return 判定结果与拆分结果；判定不通过时不拆
   */
  public SplitAttempt split(MinecartGroup group, int at) {
    CouplerEligibility.Verdict verdict = eligibility(group);
    if (verdict != CouplerEligibility.Verdict.ALLOWED) {
      return new SplitAttempt(verdict, Optional.empty());
    }
    Optional<RuntimeDispatchService> dispatch = plugin.getRuntimeDispatchService();
    if (dispatch.isEmpty()) {
      return new SplitAttempt(CouplerEligibility.Verdict.ALLOWED, Optional.empty());
    }
    TrainSplitter.Result result = splitAndPair(dispatch.get(), group, at, false);
    return new SplitAttempt(CouplerEligibility.Verdict.ALLOWED, Optional.of(result));
  }

  /**
   * 解编：交路指令在停站的列车上拆开。不判能不能动——车在运营中、停站，拆开本身就是这一站的作业。
   *
   * @param at 后一半第一节在编组里的下标
   * @param keepRear 后一半保留原身份（切车头）；否则前一半保留
   * @return 拆分结果；调度没启用时为空
   */
  Optional<TrainSplitter.Result> splitInService(MinecartGroup group, int at, boolean keepRear) {
    return plugin
        .getRuntimeDispatchService()
        .map(dispatch -> splitAndPair(dispatch, group, at, keepRear));
  }

  /** 拆开，两半按各自的车厢来源重写编组键；受管列车的两半登记成同股道列车对。 */
  private TrainSplitter.Result splitAndPair(
      RuntimeDispatchService dispatch, MinecartGroup group, int at, boolean keepRear) {
    TrainSplitter splitter =
        new TrainSplitter(
            dispatch,
            task -> Bukkit.getScheduler().runTask(plugin, task),
            message -> plugin.getLoggerManager().debug(message));
    CarTags.stampOrigins(group);
    TrainSplitter.Result result =
        keepRear ? splitter.splitKeepingRear(group, at) : splitter.split(group, at);
    if (result.outcome() == TrainSplitter.Outcome.SPLIT) {
      CarTags.rewriteConsist(group);
      result.detached().ifPresent(CarTags::rewriteConsist);
    }
    if (result.outcome() == TrainSplitter.Outcome.SPLIT
        && result.detachedName().isPresent()
        && dispatch.hasFtaRuntimeTag(group.getProperties())) {
      String keptName = trainName(dispatch, group.getProperties());
      // 编组键刚按留下的车厢重写：待命登记里的标签快照（编组方案按它认车）跟着刷新。
      TrainLinker.refreshStandby(plugin.getLayoverRegistry(), group, keptName);
      MinecartGroup detached = result.detached().orElseThrow();
      schedulePairing(
          keptName,
          group,
          result.detachedName().get(),
          detached,
          PAIR_ATTEMPTS,
          PAIR_FIRST_DELAY_TICKS);
    }
    return result;
  }

  /**
   * 把 {@code moving} 所在的列车挂到 {@code standing} 所在的列车上（两节车须是各自列车的端车、几乎贴着）。两列受管列车都要不在运营中。
   *
   * @return 判定结果与联挂结果；判定不通过时不挂
   */
  public LinkAttempt link(MinecartMember<?> moving, MinecartMember<?> standing, int maxCars) {
    MinecartGroup movingGroup = moving == null ? null : moving.getGroup();
    MinecartGroup standingGroup = standing == null ? null : standing.getGroup();
    CouplerEligibility.Verdict verdict = eligibility(movingGroup);
    if (verdict == CouplerEligibility.Verdict.ALLOWED) {
      verdict = eligibility(standingGroup);
    }
    if (verdict != CouplerEligibility.Verdict.ALLOWED) {
      return new LinkAttempt(verdict, Optional.empty());
    }
    Optional<RuntimeDispatchService> dispatch = plugin.getRuntimeDispatchService();
    if (dispatch.isEmpty()) {
      return new LinkAttempt(CouplerEligibility.Verdict.ALLOWED, Optional.empty());
    }
    TrainLinker linker =
        new TrainLinker(
            dispatch.get(),
            pairs,
            plugin.getLayoverRegistry(),
            task -> Bukkit.getScheduler().runTask(plugin, task),
            message -> plugin.getLoggerManager().debug(message));
    return new LinkAttempt(
        CouplerEligibility.Verdict.ALLOWED, Optional.of(linker.link(moving, standing, maxCars)));
  }

  /**
   * 联挂指令：把回程支线车挂到主干上，主干保留身份。不判能不能动——两车都在运营中，联挂本身就是这一站的作业。调用方已让两车贴着（空隙不超过联挂距离）。
   *
   * @param feeder 回程支线车朝着主干的端车
   * @param trunk 主干朝着回程支线车的端车
   * @return 联挂结果；调度没启用时为空
   */
  Optional<TrainLinker.Result> linkInService(MinecartMember<?> feeder, MinecartMember<?> trunk) {
    Optional<RuntimeDispatchService> dispatch = plugin.getRuntimeDispatchService();
    if (dispatch.isEmpty()) {
      return Optional.empty();
    }
    TrainLinker linker =
        new TrainLinker(
            dispatch.get(),
            pairs,
            plugin.getLayoverRegistry(),
            task -> Bukkit.getScheduler().runTask(plugin, task),
            message -> plugin.getLoggerManager().debug(message));
    // TrainLinker 保留“被接上的一方”的身份与编组对象：主干当作被接上的一方
    return Optional.of(linker.link(feeder, trunk, settings().maxCars()));
  }

  /** 联挂进站失败、两车已经挨在同一段轨道上：登记成同股道列车对，背离对方的一方先走（与拆开后同一套）。 */
  void pairAfterFailedApproach(
      String first, MinecartGroup firstGroup, String second, MinecartGroup secondGroup) {
    schedulePairing(first, firstGroup, second, secondGroup, PAIR_ATTEMPTS, PAIR_FIRST_DELAY_TICKS);
  }

  /** 这列车占着这个资源。 */
  boolean claims(String trainName, OccupancyResource resource) {
    OccupancyManager occupancy = plugin.getOccupancyManager();
    if (occupancy == null || resource == null) {
      return false;
    }
    for (OccupancyClaim claim : occupancy.snapshotClaims()) {
      if (claim != null
          && resource.equals(claim.resource())
          && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return true;
      }
    }
    return false;
  }

  /** 两车共占的资源（已按现场车身水合）。 */
  Set<OccupancyResource> sharedClaimsOf(String first, String second) {
    return sharedClaims(first, second);
  }

  /**
   * 一次联挂的结果。
   *
   * @param verdict 两列车能不能动；不是 {@link CouplerEligibility.Verdict#ALLOWED} 时没有挂
   * @param result 联挂结果；没挂时为空
   */
  public record LinkAttempt(
      CouplerEligibility.Verdict verdict, Optional<TrainLinker.Result> result) {}

  /**
   * 一次拆分的结果。
   *
   * @param verdict 能不能动；不是 {@link CouplerEligibility.Verdict#ALLOWED} 时没有拆
   * @param result 拆分结果；没拆时为空
   */
  public record SplitAttempt(
      CouplerEligibility.Verdict verdict, Optional<TrainSplitter.Result> result) {}

  // ---------------------------------------------------------------- 同股道列车对

  private void schedulePairing(
      String first,
      MinecartGroup firstGroup,
      String second,
      MinecartGroup secondGroup,
      int attempts,
      long delay) {
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (!firstGroup.isValid() || !secondGroup.isValid()) {
                return;
              }
              if (!pair(first, firstGroup, second, secondGroup) && attempts > 1) {
                schedulePairing(
                    first, firstGroup, second, secondGroup, attempts - 1, PAIR_RETRY_TICKS);
              }
            },
            delay);
  }

  /** 取两车共用的占用资源登记成对，并写进两车标签；两车占用还没就位（没有共用资源）时为 false。 */
  private boolean pair(
      String first, MinecartGroup firstGroup, String second, MinecartGroup secondGroup) {
    Set<OccupancyResource> shared = sharedClaims(first, second);
    Optional<Vector> firstCenter = center(firstGroup);
    Optional<Vector> secondCenter = center(secondGroup);
    if (shared.isEmpty() || firstCenter.isEmpty() || secondCenter.isEmpty()) {
      return false;
    }
    Optional<Vector> awayFirst =
        CoLocatedPairs.awayDirection(firstCenter.get(), secondCenter.get());
    if (awayFirst.isEmpty() || firstGroup.getWorld() == null) {
      return false;
    }
    Vector awaySecond = awayFirst.get().clone().multiply(-1.0);
    pairs.register(
        new CoLocatedPairs.Pair(
            first, second, firstGroup.getWorld().getUID(), shared, awayFirst.get(), awaySecond));
    TrainTagHelper.writeTag(
        firstGroup.getProperties(), TAG_CO_LOCATED, formatTag(second, awayFirst.get()));
    TrainTagHelper.writeTag(
        secondGroup.getProperties(), TAG_CO_LOCATED, formatTag(first, awaySecond));
    plugin
        .getLoggerManager()
        .debug("COUPLER_PAIR first=" + first + " second=" + second + " shared=" + shared.size());
    return true;
  }

  /** 检查每一对：两车仍共占的资源收窄共用集；不再共占或一方不在了就解除。再按标签找回重启前的对。 */
  void maintainPairs() {
    for (CoLocatedPairs.Pair pair : pairs.pairs()) {
      Optional<TrainProperties> first = liveProperties(pair.first());
      Optional<TrainProperties> second = liveProperties(pair.second());
      if (first.isEmpty() || second.isEmpty()) {
        dissolve(pair, first, second);
        continue;
      }
      Set<OccupancyResource> next = new HashSet<>(pair.shared());
      next.retainAll(sharedClaims(pair.first(), pair.second()));
      if (next.isEmpty()) {
        dissolve(pair, first, second);
      } else if (!next.equals(pair.shared())) {
        pairs.register(pair.withShared(next));
      }
    }
    if (maintainsUntilRestore-- <= 0) {
      maintainsUntilRestore = RESTORE_EVERY_MAINTAINS;
      restorePairsFromTags();
    }
  }

  private void dissolve(
      CoLocatedPairs.Pair pair, Optional<TrainProperties> first, Optional<TrainProperties> second) {
    pairs.dissolve(pair.first());
    first.ifPresent(properties -> TrainTagHelper.removeTagKey(properties, TAG_CO_LOCATED));
    second.ifPresent(properties -> TrainTagHelper.removeTagKey(properties, TAG_CO_LOCATED));
    plugin
        .getLoggerManager()
        .debug("COUPLER_PAIR_DISSOLVED first=" + pair.first() + " second=" + pair.second());
  }

  /** 重启后按标签找回对：两车互相指向、仍共占资源时恢复；对方已不在时删掉标签。 */
  private void restorePairsFromTags() {
    Optional<RuntimeDispatchService> dispatch = plugin.getRuntimeDispatchService();
    if (dispatch.isEmpty()) {
      return;
    }
    for (MinecartGroup group : MinecartGroupStore.getGroups()) {
      TrainProperties properties = group == null ? null : group.getProperties();
      if (properties == null) {
        continue;
      }
      Optional<String> raw = TrainTagHelper.readTagValue(properties, TAG_CO_LOCATED);
      if (raw.isEmpty()) {
        continue;
      }
      String name = trainName(dispatch.get(), properties);
      if (pairs.pairOf(name).isPresent()) {
        continue;
      }
      Optional<PairTag> tag = PairTag.parse(raw.get());
      Optional<TrainProperties> partner = tag.flatMap(found -> liveProperties(found.partner()));
      if (tag.isEmpty() || partner.isEmpty()) {
        TrainTagHelper.removeTagKey(properties, TAG_CO_LOCATED);
        continue;
      }
      Optional<PairTag> back =
          TrainTagHelper.readTagValue(partner.get(), TAG_CO_LOCATED).flatMap(PairTag::parse);
      if (back.isEmpty() || !TrainNameNormalizer.sameLogicalTrain(back.get().partner(), name)) {
        TrainTagHelper.removeTagKey(properties, TAG_CO_LOCATED);
        continue;
      }
      Set<OccupancyResource> shared = sharedClaims(name, tag.get().partner());
      if (shared.isEmpty() && hasClaims(name) && hasClaims(tag.get().partner())) {
        // 两车都已按现场车身占用、却不再共占任何资源：对早已不成立，标签作废（留着会在日后两车再碰到一起时按旧方向配对）。
        TrainTagHelper.removeTagKey(properties, TAG_CO_LOCATED);
        TrainTagHelper.removeTagKey(partner.get(), TAG_CO_LOCATED);
        continue;
      }
      if (shared.isEmpty() || group.getWorld() == null) {
        continue;
      }
      pairs.register(
          new CoLocatedPairs.Pair(
              name,
              tag.get().partner(),
              group.getWorld().getUID(),
              shared,
              tag.get().away(),
              back.get().away()));
    }
  }

  /** 发车票能不能接这列待命车：同股道列车对里，交路第一段朝对方开的那一方不接（它被对方挡着，接了只能干等），让背向对方的一方先走。 方向算不出时放行（占用判定照样会挡）。 */
  public boolean allowsLayoverDispatch(SpawnTicket ticket, String trainName) {
    if (org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ShuntingRegistry.isShunting(
        trainName)) {
      return false;
    }
    if (!decoupling.allowsLayoverDispatch(ticket, trainName)
        || !coupling.allowsLayoverDispatch(trainName)) {
      return false;
    }
    if (partialConsist(trainName) && !returnTicket(ticket)) {
      return false;
    }
    Optional<CoLocatedPairs.Pair> pair = pairs.pairOf(trainName);
    if (pair.isEmpty() || ticket == null || ticket.service() == null) {
      return true;
    }
    Optional<Vector> away = pair.get().awayOf(trainName);
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (away.isEmpty() || cache.isEmpty()) {
      return true;
    }
    Optional<RouteDefinition> definition = cache.get().findById(ticket.service().routeId());
    if (definition.isEmpty() || definition.get().waypoints().size() < 2) {
      return true;
    }
    List<NodeId> waypoints = definition.get().waypoints();
    Optional<Vector> direction =
        firstEdgeDirection(pair.get().worldId(), waypoints.get(0), waypoints.get(1));
    return direction.isEmpty() || CoLocatedPairs.movingAway(direction.get(), away.get());
  }

  /** 从 {@code from} 走最短路到 {@code to} 的第一条边的方向（起点指向终点）：与占用判定认行进方向的口径相同；走不通时为空。 */
  private Optional<Vector> firstEdgeDirection(UUID worldId, NodeId from, NodeId to) {
    RailGraphService graphs = plugin.getRailGraphService();
    if (graphs == null || worldId == null) {
      return Optional.empty();
    }
    Optional<RailGraphPath> path =
        graphs
            .getSnapshot(worldId)
            .flatMap(
                snapshot ->
                    new RailGraphPathFinder()
                        .shortestPath(
                            snapshot.graph(),
                            from,
                            to,
                            RailGraphPathFinder.Options.shortestDistance()));
    if (path.isEmpty() || path.get().nodes().size() < 2) {
      return Optional.empty();
    }
    Optional<Vector> start = nodePosition(worldId, path.get().nodes().get(0));
    Optional<Vector> next = nodePosition(worldId, path.get().nodes().get(1));
    if (start.isEmpty() || next.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(next.get().clone().subtract(start.get()));
  }

  /** 待命车是拆出来的不完整编组：读待命登记的标签快照（拆挂后会刷新），不查 TrainCarts 的列车属性。 */
  private boolean partialConsist(String trainName) {
    return plugin
        .getLayoverRegistry()
        .flatMap(registry -> registry.get(trainName))
        .map(
            candidate ->
                candidate.tags().keySet().stream()
                    .anyMatch(CarConsist.TAG_CONSIST_PARTIAL::equalsIgnoreCase))
        .orElse(false);
  }

  /** 这张票是回库票（交路运营类型为 RETURN）。 */
  private boolean returnTicket(SpawnTicket ticket) {
    if (ticket == null || ticket.service() == null) {
      return false;
    }
    return plugin
        .getRouteDefinitionCache()
        .flatMap(cache -> cache.findRecord(ticket.service().routeId()))
        .map(record -> record.route().operationType() == RouteOperationType.RETURN)
        .orElse(false);
  }

  // ---------------------------------------------------------------- 工具

  /** 这列车眼下有没有任何占用（已按现场车身水合）。 */
  private boolean hasClaims(String trainName) {
    OccupancyManager occupancy = plugin.getOccupancyManager();
    if (occupancy == null) {
      return false;
    }
    for (OccupancyClaim claim : occupancy.snapshotClaims()) {
      if (claim != null && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 受管列车推车的路径守得住：沿途经过的联锁区（道岔、交叉）都已被两车之一占着、没有别的列车占着。两车之间是一段被两端车身堵住的轨道，别的列车只能经联锁区进来；
   * 联锁目录不全或读不到图时为假（不推）。
   *
   * @param cells 推车会走过的轨道方块
   */
  boolean pushPathGuarded(MinecartGroup mover, MinecartGroup target, Set<RailFootprintCell> cells) {
    RailGraphService graphs = plugin.getRailGraphService();
    OccupancyManager occupancy = plugin.getOccupancyManager();
    if (graphs == null || occupancy == null || mover.getWorld() == null || cells.isEmpty()) {
      return false;
    }
    Optional<RailGraph> graph =
        graphs.getSnapshot(mover.getWorld().getUID()).map(snapshot -> snapshot.graph());
    if (graph.isEmpty()) {
      return false;
    }
    LiveRailFootprintResolver.Resolution zones =
        LiveRailFootprintResolver.resolve(graph.get(), cells);
    if (!zones.complete()) {
      return false;
    }
    String moverName = displayName(mover);
    String targetName = displayName(target);
    Set<OccupancyResource> held = new HashSet<>();
    for (OccupancyClaim claim : occupancy.snapshotClaims()) {
      if (claim == null || !zones.resources().contains(claim.resource())) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), moverName)
          || TrainNameNormalizer.sameLogicalTrain(claim.trainName(), targetName)) {
        held.add(claim.resource());
      } else {
        return false;
      }
    }
    return held.containsAll(zones.resources());
  }

  /** 两车都占着的资源。 */
  private Set<OccupancyResource> sharedClaims(String first, String second) {
    OccupancyManager occupancy = plugin.getOccupancyManager();
    if (occupancy == null) {
      return Set.of();
    }
    Set<OccupancyResource> firstClaims = new HashSet<>();
    Set<OccupancyResource> secondClaims = new HashSet<>();
    for (OccupancyClaim claim : occupancy.snapshotClaims()) {
      if (claim == null) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), first)) {
        firstClaims.add(claim.resource());
      } else if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), second)) {
        secondClaims.add(claim.resource());
      }
    }
    firstClaims.retainAll(secondClaims);
    return Set.copyOf(firstClaims);
  }

  private Optional<Vector> nodePosition(UUID worldId, NodeId nodeId) {
    RailGraphService graphs = plugin.getRailGraphService();
    if (graphs == null || worldId == null || nodeId == null) {
      return Optional.empty();
    }
    return graphs
        .getSnapshot(worldId)
        .flatMap(snapshot -> snapshot.graph().findNode(nodeId))
        .map(RailNode::worldPosition)
        .map(Vector::clone);
  }

  /** 编组各节车厢实体位置的平均。 */
  static Optional<Vector> center(MinecartGroup group) {
    if (group == null || group.isEmpty()) {
      return Optional.empty();
    }
    Vector sum = new Vector();
    int count = 0;
    for (MinecartMember<?> member : group) {
      Entity entity =
          member == null || member.getEntity() == null ? null : member.getEntity().getEntity();
      if (entity == null) {
        continue;
      }
      sum.add(entity.getLocation().toVector());
      count++;
    }
    return count == 0 ? Optional.empty() : Optional.of(sum.multiply(1.0 / count));
  }

  private static String trainName(RuntimeDispatchService dispatch, TrainProperties properties) {
    return dispatch.resolveTrackedTrainName(properties).orElse(properties.getTrainName());
  }

  private static Optional<TrainProperties> liveProperties(String trainName) {
    try {
      return TrainPropertiesStore.exists(trainName)
          ? Optional.ofNullable(TrainPropertiesStore.get(trainName))
          : Optional.empty();
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private static Optional<UUID> parseUuid(String raw) {
    try {
      return Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  static String formatTag(String partner, Vector away) {
    return partner
        + "|"
        + String.format(Locale.ROOT, "%.4f", away.getX())
        + ","
        + String.format(Locale.ROOT, "%.4f", away.getZ());
  }

  /** 标签里的对：对方车名与本车离开方向。 */
  record PairTag(String partner, Vector away) {

    static Optional<PairTag> parse(String raw) {
      if (raw == null) {
        return Optional.empty();
      }
      int bar = raw.lastIndexOf('|');
      if (bar <= 0) {
        return Optional.empty();
      }
      String[] parts = raw.substring(bar + 1).split(",");
      if (parts.length != 2) {
        return Optional.empty();
      }
      try {
        Vector away = new Vector(Double.parseDouble(parts[0]), 0.0, Double.parseDouble(parts[1]));
        double length = away.length();
        if (!(length > 1.0e-6) || !Double.isFinite(length)) {
          return Optional.empty();
        }
        return Optional.of(new PairTag(raw.substring(0, bar).trim(), away.multiply(1.0 / length)));
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }
    }
  }
}
