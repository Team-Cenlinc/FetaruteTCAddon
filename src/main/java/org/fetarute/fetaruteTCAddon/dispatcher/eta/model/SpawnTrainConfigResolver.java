package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.DepotSpawnPattern;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;

/**
 * 推断未发车列车的车种（加减速），供未发车票据的 ETA 使用。
 *
 * <h2>推断顺序</h2>
 *
 * <ol>
 *   <li>交路 metadata 写明的编组（与出库同一来源，见 {@link DepotSpawnPattern}）
 *   <li>交路首站 CRET 指向的车库牌子第 4 行
 *   <li>编组名推断不出车种，或以上都没有时，用配置的默认车种
 * </ol>
 *
 * <h2>缓存</h2>
 *
 * <p>站牌每次重算都要为经过本站的每张票据估算走行，同一交路的票据推断结果相同，因此按交路缓存，命中时不读库、不读牌子。
 *
 * <ul>
 *   <li>只缓存推断出的车种；加减速每次按当前配置取，配置重载不必失效。
 *   <li>交路定义变化时由调用方 {@link #invalidateAll()}。
 *   <li>每条结果 {@link #TTL} 后重新推断，车库牌子被改写最迟在这么久后生效。
 *   <li>车库牌子所在区块未加载时不加载区块，沿用这条交路上次的结果；从未读到过时按默认车种。
 * </ul>
 */
public final class SpawnTrainConfigResolver {

  /** 推断结果的有效期。 */
  static final Duration TTL = Duration.ofSeconds(60);

  private static final String CREATE_DIRECTIVE = "CRET";

  private final Function<UUID, Optional<Route>> routes;
  private final Function<UUID, List<RouteStop>> routeStops;
  private final Function<NodeId, DepotSpawnPattern.SignRead> depotSigns;
  private final ConcurrentMap<UUID, Inferred> inferred = new ConcurrentHashMap<>();

  /**
   * @param routes 按 UUID 读交路
   * @param routeStops 按交路 UUID 读停靠表；只在交路没写编组时读
   * @param depotSigns 读车库牌子编组；实现不得为此加载区块
   */
  public SpawnTrainConfigResolver(
      Function<UUID, Optional<Route>> routes,
      Function<UUID, List<RouteStop>> routeStops,
      Function<NodeId, DepotSpawnPattern.SignRead> depotSigns) {
    this.routes = Objects.requireNonNull(routes, "routes");
    this.routeStops = Objects.requireNonNull(routeStops, "routeStops");
    this.depotSigns = Objects.requireNonNull(depotSigns, "depotSigns");
  }

  /**
   * 交路上未发车列车的配置。
   *
   * @param routeUuid 交路
   * @param settings 当前车种配置
   * @param now 当前时刻，用于判断缓存是否过期
   */
  public TrainConfig resolve(
      UUID routeUuid, ConfigManager.TrainConfigSettings settings, Instant now) {
    Objects.requireNonNull(routeUuid, "routeUuid");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(now, "now");
    TrainType type = inferredType(routeUuid, now).orElse(settings.defaultTrainType());
    ConfigManager.TrainTypeSettings motion = settings.forType(type);
    return new TrainConfig(type, motion.accelBps2(), motion.decelBps2());
  }

  /** 清空全部推断结果；交路定义变化或数据源更换时调用。 */
  public void invalidateAll() {
    inferred.clear();
  }

  private Optional<TrainType> inferredType(UUID routeUuid, Instant now) {
    Inferred cached = inferred.get(routeUuid);
    if (cached != null && cached.freshAt(now)) {
      return cached.type();
    }
    Inference inference = infer(routeUuid);
    Optional<TrainType> type =
        inference.signUnread() && cached != null ? cached.type() : inference.type();
    inferred.put(routeUuid, new Inferred(type, now));
    return type;
  }

  private Inference infer(UUID routeUuid) {
    Optional<Route> route = routes.apply(routeUuid);
    if (route.isEmpty()) {
      return Inference.known(Optional.empty());
    }
    Optional<String> routePattern = DepotSpawnPattern.fromRoute(route.get());
    if (routePattern.isPresent()) {
      return Inference.known(routePattern);
    }
    Optional<NodeId> depot = createDepot(routeStops.apply(routeUuid));
    if (depot.isEmpty()) {
      return Inference.known(Optional.empty());
    }
    DepotSpawnPattern.SignRead read = depotSigns.apply(depot.get());
    return read.loaded() ? Inference.known(read.pattern()) : Inference.unread();
  }

  private static Optional<NodeId> createDepot(List<RouteStop> stops) {
    if (stops == null || stops.isEmpty()) {
      return Optional.empty();
    }
    return SpawnDirectiveParser.findDirectiveTarget(stops.get(0), CREATE_DIRECTIVE).map(NodeId::of);
  }

  /**
   * 从 spawn pattern 推断 TrainType。
   *
   * <p>解析规则：
   *
   * <ul>
   *   <li>若 pattern 匹配已知 savedTrain 命名约定（如包含 metro/tram/emu/dmu/diesel/electric），则推断类型；
   *       metro/tram/light_rail 先于 emu 判定，{@code metro_emu} 这类名字归地铁型
   *   <li>否则返回 empty，由调用方使用默认类型
   * </ul>
   */
  static Optional<TrainType> inferTrainTypeFromPattern(String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return Optional.empty();
    }

    String lower = pattern.toLowerCase(Locale.ROOT);

    // 按常见命名约定推断
    if (lower.contains("metro") || lower.contains("tram") || lower.contains("light_rail")) {
      return Optional.of(TrainType.METRO);
    }
    if (lower.contains("emu") || lower.contains("electric_multiple")) {
      return Optional.of(TrainType.EMU);
    }
    if (lower.contains("dmu") || lower.contains("diesel_multiple")) {
      return Optional.of(TrainType.DMU);
    }
    if (lower.contains("diesel") && (lower.contains("push") || lower.contains("pull"))) {
      return Optional.of(TrainType.DIESEL_PUSH_PULL);
    }
    if (lower.contains("electric") && lower.contains("loco")) {
      return Optional.of(TrainType.ELECTRIC_LOCO);
    }

    // 无法推断，返回 empty
    return Optional.empty();
  }

  /**
   * 一次推断的结果。
   *
   * @param type 推断出的车种；为空表示按默认车种
   * @param signUnread 车库牌子所在区块未加载、这次没读到；此时 {@code type} 没有意义
   */
  private record Inference(Optional<TrainType> type, boolean signUnread) {

    static Inference known(Optional<String> pattern) {
      return new Inference(
          pattern.flatMap(SpawnTrainConfigResolver::inferTrainTypeFromPattern), false);
    }

    static Inference unread() {
      return new Inference(Optional.empty(), true);
    }
  }

  private record Inferred(Optional<TrainType> type, Instant at) {

    boolean freshAt(Instant now) {
      return now.isBefore(at.plus(TTL));
    }
  }
}
