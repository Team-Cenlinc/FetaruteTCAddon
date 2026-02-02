package org.fetarute.fetaruteTCAddon.api;

import java.util.Optional;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.eta.EtaApi;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.occupancy.OccupancyApi;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;

/**
 * FetaruteTCAddon 公开 API 入口点。
 *
 * <p>外部插件通过此类获取 API 实例，访问调度图、列车状态、路线等只读数据。
 *
 * <h2>使用方式</h2>
 *
 * <pre>{@code
 * // 在插件 onEnable 中获取 API
 * FetaruteApi api = FetaruteApi.getInstance();
 * if (api == null) {
 *     getLogger().warning("FetaruteTCAddon 未加载或版本不兼容");
 *     return;
 * }
 *
 * // 使用子模块
 * api.graph().getSnapshot(worldId).ifPresent(snapshot -> {
 *     getLogger().info("节点数: " + snapshot.nodeCount());
 * });
 * }</pre>
 *
 * <h2>线程安全</h2>
 *
 * <p>所有 API 方法返回的数据均为不可变快照，可安全在任意线程使用。 但建议在主线程调用 API 方法以获取最新数据。
 *
 * <h2>版本兼容</h2>
 *
 * <p>API 遵循语义版本控制：
 *
 * <ul>
 *   <li>主版本号变更表示不兼容的 API 修改
 *   <li>次版本号变更表示向后兼容的功能新增
 *   <li>修订号变更表示向后兼容的问题修正
 * </ul>
 *
 * @see GraphApi
 * @see TrainApi
 * @see RouteApi
 * @see OccupancyApi
 * @see StationApi
 * @see OperatorApi
 * @see LineApi
 * @see EtaApi
 */
public final class FetaruteApi {

  /** 当前 API 版本（语义版本）。 */
  public static final String API_VERSION = "1.2.0";

  private static volatile FetaruteApi instance;

  private final GraphApi graphApi;
  private final TrainApi trainApi;
  private final RouteApi routeApi;
  private final OccupancyApi occupancyApi;
  private final StationApi stationApi;
  private final OperatorApi operatorApi;
  private final LineApi lineApi;
  private final EtaApi etaApi;

  private FetaruteApi(
      GraphApi graphApi,
      TrainApi trainApi,
      RouteApi routeApi,
      OccupancyApi occupancyApi,
      StationApi stationApi,
      OperatorApi operatorApi,
      LineApi lineApi,
      EtaApi etaApi) {
    this.graphApi = graphApi;
    this.trainApi = trainApi;
    this.routeApi = routeApi;
    this.occupancyApi = occupancyApi;
    this.stationApi = stationApi;
    this.operatorApi = operatorApi;
    this.lineApi = lineApi;
    this.etaApi = etaApi;
  }

  /**
   * 获取 API 实例。
   *
   * <p>若 FetaruteTCAddon 未加载或未初始化完成，返回 {@code null}。
   *
   * @return API 实例，或 {@code null}
   */
  public static FetaruteApi getInstance() {
    return instance;
  }

  /**
   * 获取 API 实例（Optional 包装）。
   *
   * <p>推荐使用此方法以避免空指针检查。
   *
   * @return API 实例的 Optional
   */
  public static Optional<FetaruteApi> get() {
    return Optional.ofNullable(instance);
  }

  /**
   * 检查指定插件是否依赖了兼容版本的 API。
   *
   * @param plugin 调用方插件
   * @param requiredVersion 要求的最低 API 版本（如 "1.0.0"）
   * @return 若当前 API 版本 &gt;= requiredVersion 则返回 true
   */
  public static boolean isCompatible(Plugin plugin, String requiredVersion) {
    if (instance == null || requiredVersion == null) {
      return false;
    }
    return compareVersions(API_VERSION, requiredVersion) >= 0;
  }

  /**
   * 调度图 API：节点、边、路径查询。
   *
   * @return 调度图 API
   */
  public GraphApi graph() {
    return graphApi;
  }

  /**
   * 列车 API：列车状态、位置、速度、ETA。
   *
   * @return 列车 API
   */
  public TrainApi trains() {
    return trainApi;
  }

  /**
   * 路线 API：路线定义、站点、停靠信息。
   *
   * @return 路线 API
   */
  public RouteApi routes() {
    return routeApi;
  }

  /**
   * 占用 API：轨道占用、信号状态、队列。
   *
   * @return 占用 API
   */
  public OccupancyApi occupancy() {
    return occupancyApi;
  }

  /**
   * 站点 API：站点信息查询。
   *
   * @return 站点 API
   */
  public StationApi stations() {
    return stationApi;
  }

  /**
   * 运营商 API：运营商信息查询。
   *
   * @return 运营商 API
   */
  public OperatorApi operators() {
    return operatorApi;
  }

  /**
   * 线路 API：线路信息查询。
   *
   * @return 线路 API
   */
  public LineApi lines() {
    return lineApi;
  }

  /**
   * ETA API：列车到达时间与站牌列表。
   *
   * @return ETA API
   */
  public EtaApi eta() {
    return etaApi;
  }

  /**
   * 当前 API 版本。
   *
   * @return 语义版本字符串
   */
  public String version() {
    return API_VERSION;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 内部方法（仅供 FetaruteTCAddon 调用）
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * 初始化 API 实例（仅供 {@link FetaruteTCAddon} 调用）。
   *
   * @param graphApi 调度图 API 实现
   * @param trainApi 列车 API 实现
   * @param routeApi 路线 API 实现
   * @param occupancyApi 占用 API 实现
   * @param stationApi 站点 API 实现
   * @param operatorApi 运营商 API 实现
   * @param lineApi 线路 API 实现
   * @param etaApi ETA API 实现
   */
  public static void initialize(
      GraphApi graphApi,
      TrainApi trainApi,
      RouteApi routeApi,
      OccupancyApi occupancyApi,
      StationApi stationApi,
      OperatorApi operatorApi,
      LineApi lineApi,
      EtaApi etaApi) {
    instance =
        new FetaruteApi(
            graphApi, trainApi, routeApi, occupancyApi, stationApi, operatorApi, lineApi, etaApi);
  }

  /** 销毁 API 实例（仅供 {@link FetaruteTCAddon} 调用）。 */
  public static void shutdown() {
    instance = null;
  }

  /** 简单的语义版本比较。 */
  private static int compareVersions(String v1, String v2) {
    String[] parts1 = v1.split("\\.");
    String[] parts2 = v2.split("\\.");
    int len = Math.max(parts1.length, parts2.length);
    for (int i = 0; i < len; i++) {
      int n1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
      int n2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
      if (n1 != n2) {
        return Integer.compare(n1, n2);
      }
    }
    return 0;
  }

  private static int parseVersionPart(String part) {
    try {
      return Integer.parseInt(part.replaceAll("[^0-9]", ""));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
