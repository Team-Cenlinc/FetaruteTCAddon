package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher.DynamicSpec;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerPlatformDesk;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResourceResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 信号员改停股道（{@link SignallerPlatformDesk} 的调度侧实现）：只对 {@code DYNAMIC:} 停站。
 *
 * <p>信号员的选择是计划站台的一个来源（{@link StationStopCoordinator#plannedPlatform}）：仅次于联挂搭档，先于时刻表排定的计划站台与预先指定的站台。
 * 它和计划站台一样只是选台偏好——只在空闲候选里起作用，从不让被占的股道变得可选；站牌经同一份偏好显示，显示的就是车会去的那条。
 *
 * <p>改选只在内核允许改选的时机生效：这一站已经选了台、车又已经走上或被授权走上通往原股道的分岔（见 {@link #committedToward}）时，改选会让车在咽喉里换目的地，
 * 一律拒绝。还没锁闭时撤掉选台器里这一站的缓存与运行时的有效节点覆盖，唤醒这列车，下一拍按新偏好重新选台；站台落定时以被改掉的那条作为“原来的”， 站台变更照现有事件链发出。
 *
 * <p>选择按规范车名、交路与停站下标记在内存里，重启即失效；车改名时随车迁移，车销毁、换下一趟任务时清掉。只在主线程写；读（站牌）可在任意线程。
 */
public final class SignallerPlatforms implements SignallerPlatformDesk {

  /** 停站现在显示的站台（计划、信号员选的或暂定的），由停靠协调器提供。 */
  @FunctionalInterface
  interface PlatformDisplay {
    Optional<NodeId> of(String trainName, RouteDefinition route, int stopIndex);
  }

  /** 说明车在那里、或可以开到那里的占用角色；排队、前瞻、解锁预约不算。 */
  private static final Set<ClaimRole> COMMITTING_ROLES =
      EnumSet.of(
          ClaimRole.MOVEMENT_REQUIRED,
          ClaimRole.PHYSICAL_FOOTPRINT,
          ClaimRole.HOLD_ONLY,
          ClaimRole.PROTECTIVE_RETAIN);

  /** 一个停站的键：规范车名、交路、停站下标。 */
  private record StopKey(String train, String route, int stopIndex) {}

  /** 选择所在的那一站，以及解析时的现场。 */
  private record Target(
      String trainName,
      StopKey key,
      RouteDefinition route,
      int stopIndex,
      List<NodeId> candidates,
      Optional<NodeId> allocated,
      Optional<NodeId> current,
      Optional<NodeId> chosen) {

    Options options() {
      return new Options(stopIndex, candidates, current, chosen);
    }
  }

  private final Consumer<String> debugLogger;
  private final RouteDefinitionCache routeDefinitions;
  private final RouteProgressRegistry progress;
  private final RailGraphService graphs;
  private final OccupancyManager occupancy;
  private final DynamicPlatformAllocator allocator;
  private final Map<String, ? extends Map<Integer, ?>> effectiveNodeOverrides;
  private final Consumer<String> wake;

  private volatile PlatformDisplay display = (trainName, route, stopIndex) -> Optional.empty();

  /** 信号员的选择。 */
  private final ConcurrentMap<StopKey, NodeId> choices = new ConcurrentHashMap<>();

  /** 被信号员改掉的原站台：这一站下一次写定股道时作为“原来的”，站台屏据此播报站台变更。 */
  private final ConcurrentMap<StopKey, NodeId> displaced = new ConcurrentHashMap<>();

  /**
   * @param effectiveNodeOverrides 运行时的有效节点覆盖（车名 → 停站下标 → 覆盖）；改选时撤掉对应一项
   * @param wake 下一 tick 重评估这列车
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "占用账本、选台器与有效节点覆盖都是运行时共享状态；改选必须读写同一份，不能复制。")
  SignallerPlatforms(
      Consumer<String> debugLogger,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progress,
      RailGraphService graphs,
      OccupancyManager occupancy,
      DynamicPlatformAllocator allocator,
      Map<String, ? extends Map<Integer, ?>> effectiveNodeOverrides,
      Consumer<String> wake) {
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
    this.routeDefinitions = routeDefinitions;
    this.progress = progress;
    this.graphs = graphs;
    this.occupancy = occupancy;
    this.allocator = allocator;
    this.effectiveNodeOverrides = effectiveNodeOverrides;
    this.wake = wake == null ? trainName -> {} : wake;
  }

  /** 没有接上调度运行时的实例：没有可改的停站，选择一律不成。 */
  static SignallerPlatforms detached() {
    return new SignallerPlatforms(null, null, null, null, null, null, null, null);
  }

  /** 接上停站显示（停靠协调器构造时）。 */
  void attachDisplay(PlatformDisplay next) {
    this.display = next == null ? (trainName, route, stopIndex) -> Optional.empty() : next;
  }

  @Override
  public Optional<Options> options(String trainName) {
    try {
      return resolve(trainName).map(Target::options);
    } catch (RuntimeException ex) {
      debugLogger.accept("SIGNALLER_PLATFORM_OPTIONS_FAILED train=" + trainName + " error=" + ex);
      return Optional.empty();
    }
  }

  @Override
  public Choice choose(String trainName, NodeId platform) {
    try {
      return chooseResolved(trainName, platform);
    } catch (RuntimeException ex) {
      debugLogger.accept("SIGNALLER_PLATFORM_CHOOSE_FAILED train=" + trainName + " error=" + ex);
      return Choice.UNAVAILABLE;
    }
  }

  private Choice chooseResolved(String trainName, NodeId platform) {
    Optional<Target> resolved = resolve(trainName);
    if (platform == null || resolved.isEmpty() || !resolved.get().options().offers(platform)) {
      return Choice.UNAVAILABLE;
    }
    Target target = resolved.get();
    Optional<NodeId> allocated = target.allocated();
    boolean reselect = allocated.isPresent() && !allocated.get().equals(platform);
    if (reselect && committedToward(target, allocated.get(), platform)) {
      debugLogger.accept(
          "SIGNALLER_PLATFORM_REJECTED train="
              + target.trainName()
              + " stopIndex="
              + target.stopIndex()
              + " chosen="
              + platform.value()
              + " allocated="
              + allocated.get().value()
              + " reason=route-locked");
      return Choice.ROUTE_LOCKED;
    }
    Optional<NodeId> before = target.current();
    choices.put(target.key(), platform);
    if (before.isPresent() && !before.get().equals(platform)) {
      displaced.put(target.key(), before.get());
    }
    if (reselect) {
      revoke(target);
    }
    debugLogger.accept(
        "SIGNALLER_PLATFORM_CHOSEN train="
            + target.trainName()
            + " route="
            + target.route().id().value()
            + " stopIndex="
            + target.stopIndex()
            + " chosen="
            + platform.value()
            + " before="
            + before.map(NodeId::value).orElse("-")
            + " reselect="
            + reselect);
    wake.accept(target.trainName());
    return Choice.CHOSEN;
  }

  @Override
  public void forget(String trainName) {
    String trainKey = TrainNameNormalizer.normalizeKey(trainName);
    if (trainKey.isEmpty() || choices.isEmpty()) {
      return;
    }
    try {
      forgetResolved(trainName, trainKey);
    } catch (RuntimeException ex) {
      debugLogger.accept("SIGNALLER_PLATFORM_FORGET_FAILED train=" + trainName + " error=" + ex);
    }
  }

  private void forgetResolved(String trainName, String trainKey) {
    Optional<Target> target = resolve(trainName);
    boolean removed = false;
    for (StopKey key : new ArrayList<>(choices.keySet())) {
      if (!key.train().equals(trainKey)) {
        continue;
      }
      NodeId chosen = choices.remove(key);
      if (chosen == null) {
        continue;
      }
      removed = true;
      displaced.remove(key);
      if (target.isEmpty() || !target.get().key().equals(key)) {
        continue;
      }
      Optional<NodeId> allocated = target.get().allocated();
      if (allocated.isEmpty()) {
        // 还没选台：站牌随即退回计划或暂定的那条，选台时以信号员选过的这条作为“原来的”。
        displaced.put(key, chosen);
      } else if (allocated.get().equals(chosen)
          && !committedTowardAgainstAll(target.get(), chosen)) {
        displaced.put(key, chosen);
        revoke(target.get());
      }
    }
    if (removed) {
      wake.accept(target.map(Target::trainName).orElse(trainName));
    }
  }

  /**
   * 计划站台的信号员来源：信号员给这一站选过的股道。没有任何选择时不分配。
   *
   * @param trainName 列车名
   * @param route 列车当前交路
   * @param stopIndex 停站下标
   */
  Optional<NodeId> chosen(String trainName, RouteDefinition route, int stopIndex) {
    if (choices.isEmpty() || route == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(choices.get(key(trainName, route, stopIndex)));
  }

  /**
   * 这一站被信号员改掉的原站台，取出即删：站台落定时作为“原来的”，只用一次。
   *
   * @param trainName 列车名
   * @param route 列车当前交路
   * @param stopIndex 停站下标
   */
  Optional<NodeId> takeDisplaced(String trainName, RouteDefinition route, int stopIndex) {
    if (displaced.isEmpty() || route == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(displaced.remove(key(trainName, route, stopIndex)));
  }

  /**
   * 列车改名：选择随车迁到新名下。
   *
   * @param from 旧名
   * @param to 新名
   */
  void renamed(String from, String to) {
    String fromKey = TrainNameNormalizer.normalizeKey(from);
    String toKey = TrainNameNormalizer.normalizeKey(to);
    if (fromKey.isEmpty() || toKey.isEmpty() || fromKey.equals(toKey)) {
      return;
    }
    move(choices, fromKey, toKey);
    move(displaced, fromKey, toKey);
  }

  /**
   * 静默丢掉这列车的全部选择：车已销毁、不归调度管，或换下一趟任务。不撤选台缓存、不唤醒（这些由调用方的清理负责）。
   *
   * @param trainName 列车名
   */
  void discard(String trainName) {
    if (choices.isEmpty() && displaced.isEmpty()) {
      return;
    }
    String trainKey = TrainNameNormalizer.normalizeKey(trainName);
    if (trainKey.isEmpty()) {
      return;
    }
    choices.keySet().removeIf(key -> key.train().equals(trainKey));
    displaced.keySet().removeIf(key -> key.train().equals(trainKey));
  }

  private static void move(ConcurrentMap<StopKey, NodeId> map, String fromKey, String toKey) {
    if (map.isEmpty()) {
      return;
    }
    for (StopKey key : new ArrayList<>(map.keySet())) {
      if (!key.train().equals(fromKey)) {
        continue;
      }
      NodeId value = map.remove(key);
      if (value != null) {
        map.put(new StopKey(toKey, key.route(), key.stopIndex()), value);
      }
    }
  }

  private static StopKey key(String trainName, RouteDefinition route, int stopIndex) {
    return new StopKey(TrainNameNormalizer.normalizeKey(trainName), route.id().value(), stopIndex);
  }

  /** 这列车下一个还没开始停的 DYNAMIC 停站（进度下标之后），连同候选与现场。 */
  private Optional<Target> resolve(String trainName) {
    if (trainName == null
        || trainName.isBlank()
        || routeDefinitions == null
        || progress == null
        || graphs == null) {
      return Optional.empty();
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt = progress.get(trainName);
    if (entryOpt.isEmpty() || entryOpt.get().routeUuid() == null) {
      return Optional.empty();
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(entry.routeUuid());
    if (routeOpt.isEmpty()) {
      return Optional.empty();
    }
    RouteDefinition route = routeOpt.get();
    for (int index = Math.max(0, entry.currentIndex() + 1);
        index < route.waypoints().size();
        index++) {
      Optional<RouteStop> stop = routeDefinitions.findStop(route.id(), index);
      Optional<DynamicSpec> spec = stop.flatMap(DynamicStopMatcher::parseDynamicSpec);
      if (spec.isEmpty()) {
        continue;
      }
      List<NodeId> candidates = candidates(spec.get());
      if (candidates.isEmpty()) {
        return Optional.empty();
      }
      String resolvedName = entry.trainName();
      StopKey key = key(resolvedName, route, index);
      Optional<NodeId> allocated =
          allocator == null
              ? Optional.empty()
              : allocator.getAllocation(resolvedName, route.id(), stop.get().sequence());
      Optional<NodeId> current =
          allocated.isPresent() ? allocated : display.of(resolvedName, route, index);
      return Optional.of(
          new Target(
              resolvedName,
              key,
              route,
              index,
              candidates,
              allocated,
              current == null ? Optional.empty() : current,
              Optional.ofNullable(choices.get(key))));
    }
    return Optional.empty();
  }

  /** DYNAMIC 规范在图上的候选股道，与选台器同一份枚举（按股道号）。 */
  private List<NodeId> candidates(DynamicSpec spec) {
    for (RailGraphService.RailGraphSnapshot snapshot : graphs.snapshotAll().values()) {
      if (snapshot == null || snapshot.graph() == null) {
        continue;
      }
      List<NodeId> found = DynamicStopMatcher.candidateNodes(spec, snapshot.graph());
      if (!found.isEmpty()) {
        return found;
      }
    }
    return List.of();
  }

  /**
   * 车是否已经走上、或已被授权走上通往 {@code from} 股道的分岔，从而不能再改去 {@code to}。
   *
   * <p>从这一站前一个交路节点出发，到两条股道的最短路（与运行时按相邻交路节点逐段寻路同一口径）先共用一段进路，在分岔点分开。分岔点（含它的道岔冲突）及其后 {@code from}
   * 一侧的节点、区间与区间上的冲突资源里，车只要持有任何实际占用（行车、车身、停车保持、保护性），或最后经过的节点已在那一侧，就算已锁闭：
   * 授权越过了分岔，改选会让车在咽喉里换目的地，要么瞬停，要么过了分岔解不出新计划。共用进路上的占用不算——授权还没越过分岔时改选，下一拍重新选台、 按新股道申请进路并在同一拍改写
   * destination，车在分岔前停得住。
   *
   * <p>图、路径或占用账本读不到时按已锁闭处理（不改选）。
   *
   * @param target 这一站
   * @param from 车现在要去的股道
   * @param to 想改去的股道
   */
  private boolean committedToward(Target target, NodeId from, NodeId to) {
    if (occupancy == null || graphs == null || from == null || to == null) {
      return true;
    }
    RailGraph graph = graphContaining(from, to);
    if (graph == null) {
      return true;
    }
    NodeId start = approachStart(target, graph);
    if (start == null) {
      return true;
    }
    RailGraphPathFinder finder = new RailGraphPathFinder();
    Optional<RailGraphPath> toFrom =
        finder.shortestPath(graph, start, from, RailGraphPathFinder.Options.shortestDistance());
    Optional<RailGraphPath> toTo =
        finder.shortestPath(graph, start, to, RailGraphPathFinder.Options.shortestDistance());
    if (toFrom.isEmpty() || toTo.isEmpty() || toFrom.get().nodes().isEmpty()) {
      return true;
    }
    List<NodeId> fromNodes = toFrom.get().nodes();
    List<NodeId> toNodes = toTo.get().nodes();
    int shared = 0;
    while (shared < fromNodes.size()
        && shared < toNodes.size()
        && fromNodes.get(shared).equals(toNodes.get(shared))) {
      shared++;
    }
    int fork = Math.max(0, shared - 1);
    Set<NodeId> branchNodes = new HashSet<>(fromNodes.subList(fork, fromNodes.size()));
    Set<OccupancyResource> branch = new HashSet<>();
    List<RailEdge> edges = toFrom.get().edges();
    for (int index = fork; index < edges.size(); index++) {
      branch.addAll(OccupancyResourceResolver.resourcesForEdge(graph, edges.get(index)));
    }
    for (int index = 0; index < fork && index < edges.size(); index++) {
      branch.removeAll(OccupancyResourceResolver.resourcesForEdge(graph, edges.get(index)));
    }
    for (NodeId node : branchNodes) {
      graph
          .findNode(node)
          .ifPresentOrElse(
              railNode -> branch.addAll(OccupancyResourceResolver.resourcesForNode(railNode)),
              () -> branch.add(OccupancyResource.forNode(node)));
    }
    Optional<NodeId> lastPassed =
        progress == null ? Optional.empty() : progress.lastPassedGraphNode(target.trainName());
    // 车最后经过的节点已在分岔点或其后原股道一侧（进站起点本身除外：车还停在前一个交路节点上）。
    if (lastPassed.isPresent()
        && branchNodes.contains(lastPassed.get())
        && !lastPassed.get().equals(start)) {
      return true;
    }
    List<OccupancyClaim> claims = occupancy.snapshotClaims();
    if (claims == null) {
      return true;
    }
    for (OccupancyClaim claim : claims) {
      if (claim != null
          && claim.resource() != null
          && COMMITTING_ROLES.contains(claim.role())
          && branch.contains(claim.resource())
          && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), target.trainName())) {
        return true;
      }
    }
    return false;
  }

  /** 已锁闭了通往 {@code platform} 的分岔、不能再改去任何一条别的候选。没有别的候选时不算。 */
  private boolean committedTowardAgainstAll(Target target, NodeId platform) {
    for (NodeId other : target.candidates()) {
      if (!other.equals(platform) && committedToward(target, platform, other)) {
        return true;
      }
    }
    return false;
  }

  /** 同时含有两条股道的那张图（各世界的调度图）；都没有时为 null。 */
  private RailGraph graphContaining(NodeId first, NodeId second) {
    for (RailGraphService.RailGraphSnapshot snapshot : graphs.snapshotAll().values()) {
      RailGraph graph = snapshot == null ? null : snapshot.graph();
      if (graph != null
          && graph.findNode(first).isPresent()
          && graph.findNode(second).isPresent()) {
        return graph;
      }
    }
    return null;
  }

  /** 进站进路的起点：这一站前一个交路节点；它不在图上（例如也是 DYNAMIC 占位）时取车最后经过的图节点。 */
  private NodeId approachStart(Target target, RailGraph graph) {
    int previous = target.stopIndex() - 1;
    if (previous >= 0 && previous < target.route().waypoints().size()) {
      NodeId declared = target.route().waypoints().get(previous);
      if (declared != null && graph.findNode(declared).isPresent()) {
        return declared;
      }
    }
    if (progress == null) {
      return null;
    }
    return progress
        .lastPassedGraphNode(target.trainName())
        .filter(node -> graph.findNode(node).isPresent())
        .orElse(null);
  }

  /** 撤掉这一站已缓存的选台与有效节点覆盖：下一拍按新偏好重新选台。 */
  private void revoke(Target target) {
    if (allocator != null) {
      allocator.revokeAllocation(target.trainName(), target.route(), target.stopIndex());
    }
    if (effectiveNodeOverrides != null) {
      String trainKey = TrainNameNormalizer.normalizeKey(target.trainName());
      Map<Integer, ?> overrides = effectiveNodeOverrides.get(trainKey);
      if (overrides != null) {
        overrides.remove(target.stopIndex());
        if (overrides.isEmpty()) {
          effectiveNodeOverrides.remove(trainKey, overrides);
        }
      }
    }
  }
}
