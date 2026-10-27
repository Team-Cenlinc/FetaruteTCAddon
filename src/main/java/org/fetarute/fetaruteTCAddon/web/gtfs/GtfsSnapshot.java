package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;

/**
 * 生成一份静态 GTFS 需要的全部数据，一次读齐。
 *
 * <p>由插件在主线程之外的 web 线程从各服务的只读快照拼出来（见 {@link GtfsSource}）。这里只放已经不可变的数据，构建器不再回读任何服务，因此同一个快照永远
 * 产出同一份包；不同请求之间也不会拿到半新半旧的组合。
 *
 * <p>数据来源与口径：
 *
 * <ul>
 *   <li>运营商、线路、车站、车站组：车站目录的内存快照（不读库）。
 *   <li>已发布时刻表：{@code TimetableService#publishedTimetables}，已排除被顶掉的表。
 *   <li>股道：调度图里 {@code STATION} 类型的节点；站咽喉、区间点、车库、道岔都不发布。
 *   <li>计划股道：{@code TimetableService#plannedPlatform}，只读内存计划表。
 * </ul>
 *
 * @param serverId 本服的服务器 ID（{@code config.yml} 的 {@code server-id}）；没配时 ID 不带前缀
 * @param zone 服务器时区，即 {@code agency_timezone} 与时刻表换算时区
 * @param publisherName GTFS 发布方名称（{@code web-api.gtfs.publisher-name}）
 * @param publisherUrl GTFS 发布方地址（{@code web-api.gtfs.publisher-url}），也用作各运营商的 {@code agency_url}
 * @param generatedAt 快照时刻；日历日期、{@code feed_info} 与 ETag 以外的展示字段按它算
 * @param timetableEnabled 按表运行是否启用；关闭时时刻表只能查、不驱动行车，不发布车次
 * @param operators 全部运营商
 * @param lines 全部线路
 * @param stations 全部车站及其所属运营商
 * @param groups 全部车站组（跨站换乘）
 * @param platforms 调度图里的股道节点
 * @param timetables 已发布时刻表
 * @param routes 交路 UUID 到归属信息的映射
 * @param plannedPlatforms 计划股道查询
 * @param stationsOfPublishedLines 有非规划线路经过的车站：没有车次停靠的线路也要发布它们的车站（设计稿 Q4）。 只在该站有坐标（或至少一条股道）时才会真正出现在
 *     stops.txt
 * @param platformPlansVersion 计划股道的内容版本（{@code TimetableService#platformPlansVersion}）；股道计划不在
 *     {@link Timetable} 里， 用版本号让输入 hash 能发现它变了
 * @param headwayRoutes 按间隔发车、不受时刻表管辖的交路，发布成 {@code frequencies.txt}
 */
public record GtfsSnapshot(
    Optional<String> serverId,
    ZoneId zone,
    String publisherName,
    String publisherUrl,
    Instant generatedAt,
    boolean timetableEnabled,
    List<Operator> operators,
    List<Line> lines,
    List<StationDirectory.StationEntry> stations,
    List<StationDirectory.GroupEntry> groups,
    List<GtfsPlatformNode> platforms,
    List<Timetable> timetables,
    Map<UUID, GtfsRouteRef> routes,
    GtfsPlatformPlans plannedPlatforms,
    Set<UUID> stationsOfPublishedLines,
    long platformPlansVersion,
    List<GtfsHeadwayRoute> headwayRoutes) {

  public GtfsSnapshot {
    serverId =
        serverId == null ? Optional.empty() : serverId.map(String::trim).filter(s -> !s.isEmpty());
    Objects.requireNonNull(zone, "zone");
    publisherName = Objects.requireNonNull(publisherName, "publisherName").trim();
    publisherUrl = Objects.requireNonNull(publisherUrl, "publisherUrl").trim();
    Objects.requireNonNull(generatedAt, "generatedAt");
    operators = operators == null ? List.of() : List.copyOf(operators);
    lines = lines == null ? List.of() : List.copyOf(lines);
    stations = stations == null ? List.of() : List.copyOf(stations);
    groups = groups == null ? List.of() : List.copyOf(groups);
    platforms = platforms == null ? List.of() : List.copyOf(platforms);
    timetables = timetables == null ? List.of() : List.copyOf(timetables);
    routes = routes == null ? Map.of() : Map.copyOf(routes);
    plannedPlatforms = plannedPlatforms == null ? GtfsPlatformPlans.none() : plannedPlatforms;
    stationsOfPublishedLines =
        stationsOfPublishedLines == null ? Set.of() : Set.copyOf(stationsOfPublishedLines);
    headwayRoutes = headwayRoutes == null ? List.of() : List.copyOf(headwayRoutes);
  }

  /** 兼容调用：没有按间隔发车的交路。 */
  public GtfsSnapshot(
      Optional<String> serverId,
      ZoneId zone,
      String publisherName,
      String publisherUrl,
      Instant generatedAt,
      boolean timetableEnabled,
      List<Operator> operators,
      List<Line> lines,
      List<StationDirectory.StationEntry> stations,
      List<StationDirectory.GroupEntry> groups,
      List<GtfsPlatformNode> platforms,
      List<Timetable> timetables,
      Map<UUID, GtfsRouteRef> routes,
      GtfsPlatformPlans plannedPlatforms,
      Set<UUID> stationsOfPublishedLines,
      long platformPlansVersion) {
    this(
        serverId,
        zone,
        publisherName,
        publisherUrl,
        generatedAt,
        timetableEnabled,
        operators,
        lines,
        stations,
        groups,
        platforms,
        timetables,
        routes,
        plannedPlatforms,
        stationsOfPublishedLines,
        platformPlansVersion,
        List.of());
  }

  /** 兼容调用：没有额外的「有线路经过」车站与计划股道版本（测试与只关心车次的调用方）。 */
  public GtfsSnapshot(
      Optional<String> serverId,
      ZoneId zone,
      String publisherName,
      String publisherUrl,
      Instant generatedAt,
      boolean timetableEnabled,
      List<Operator> operators,
      List<Line> lines,
      List<StationDirectory.StationEntry> stations,
      List<StationDirectory.GroupEntry> groups,
      List<GtfsPlatformNode> platforms,
      List<Timetable> timetables,
      Map<UUID, GtfsRouteRef> routes,
      GtfsPlatformPlans plannedPlatforms,
      Set<UUID> stationsOfPublishedLines) {
    this(
        serverId,
        zone,
        publisherName,
        publisherUrl,
        generatedAt,
        timetableEnabled,
        operators,
        lines,
        stations,
        groups,
        platforms,
        timetables,
        routes,
        plannedPlatforms,
        stationsOfPublishedLines,
        0L);
  }

  /** 兼容调用：没有额外的「有线路经过」车站与计划股道版本（测试与只关心车次的调用方）。 */
  public GtfsSnapshot(
      Optional<String> serverId,
      ZoneId zone,
      String publisherName,
      String publisherUrl,
      Instant generatedAt,
      boolean timetableEnabled,
      List<Operator> operators,
      List<Line> lines,
      List<StationDirectory.StationEntry> stations,
      List<StationDirectory.GroupEntry> groups,
      List<GtfsPlatformNode> platforms,
      List<Timetable> timetables,
      Map<UUID, GtfsRouteRef> routes,
      GtfsPlatformPlans plannedPlatforms) {
    this(
        serverId,
        zone,
        publisherName,
        publisherUrl,
        generatedAt,
        timetableEnabled,
        operators,
        lines,
        stations,
        groups,
        platforms,
        timetables,
        routes,
        plannedPlatforms,
        Set.of());
  }
}
