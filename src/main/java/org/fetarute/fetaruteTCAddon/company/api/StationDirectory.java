package org.fetarute.fetaruteTCAddon.company.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;
import org.fetarute.fetaruteTCAddon.company.repository.StationGroupRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 车站目录：车站身份、车站组与停靠线路的内存索引。公开 API 与 HUD 按运营商代码 + 站码找车站都走这里，保证两边给出同一个车站。
 *
 * <p>两层数据，各自的刷新时机不同：
 *
 * <ul>
 *   <li><b>主数据</b>（运营商、线路、车站、车站组）：{@link #reload} 从存储整份重读（固定几次查询，与车站数量无关）。 车站、线路、运营商、车站组变化后由改动方调用。
 *   <li><b>派生索引</b>（每条交路各停靠点对应的车站、每个车站的停靠线路）：由主数据与 {@link RouteDefinitionCache} 算出； 交路缓存任何变化都会同步回调
 *       {@link #rebuildRoutes}，不读存储。主数据没变时，停靠表没变的交路直接沿用上一版的解析结果。
 * </ul>
 *
 * <p>每次重建产出一个不可变的 {@link Snapshot}，整体替换；查询只读当前快照、只做查表，可在任意线程调用。 {@link #revision()}
 * 每次重建递增，外部据此判断数据是否变化。
 *
 * <p>车站解析口径：
 *
 * <ul>
 *   <li>节点属于哪座车站由 {@link RouteTerminals#stationIdentityOf} / {@link
 *       RouteTerminals#stationIdentityOfNode} 定义（站台、咽喉、DYNAMIC
 *       占位），这里不另写解析。节点解析不出车站时，再看有没有车站把它绑定为图节点。
 *   <li>运营商代码不区分大小写；跨公司重名时按公司列表顺序先到先得（与 HUD 公司显示同一规则）。 解析交路停靠点时优先取交路自己的运营商（代码相同的话），跨公司同名运营商也不会串站。
 * </ul>
 *
 * <p>停靠线路按列车在该站所属的线路统计（直通运转，见 {@link RouteLineChanges}）：换线之后的各站算新线路， 换线站本身两条线都算（列车以原线路到达、以新线路发车）。
 */
public final class StationDirectory {

  /** 节点 → 车站的解析缓存上限；超过后仍能查询，只是不再缓存新节点。 */
  static final int NODE_MEMO_LIMIT = 16_384;

  private final RouteDefinitionCache routes;
  private final Consumer<String> debugLogger;
  private final Object rebuildLock = new Object();
  private final AtomicLong revisionCounter = new AtomicLong();
  private volatile Catalog catalog = Catalog.EMPTY;
  private volatile Snapshot snapshot = Snapshot.empty();

  /**
   * @param routes 交路缓存；注册变更监听，交路变化时自动重算派生索引
   * @param debugLogger 调试日志
   */
  public StationDirectory(RouteDefinitionCache routes, Consumer<String> debugLogger) {
    this.routes = routes;
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
    if (routes != null) {
      routes.addChangeListener(this::rebuildRoutes);
    }
  }

  /**
   * 从存储重读主数据并重建全部索引。
   *
   * <p>读存储期间持有重建锁：两次重读不会乱序覆盖。建议在主线程调用。
   *
   * @param provider 已就绪的存储
   */
  public void reload(StorageProvider provider) {
    if (provider == null) {
      return;
    }
    synchronized (rebuildLock) {
      Catalog loaded;
      try {
        loaded = Catalog.load(provider, debugLogger);
      } catch (RuntimeException ex) {
        debugLogger.accept(
            "车站目录加载失败，保留旧数据: " + ex + (ex.getCause() == null ? "" : " cause=" + ex.getCause()));
        return;
      }
      catalog = loaded;
      publish();
    }
  }

  /** 只按交路缓存重算派生索引（主数据不变，不读存储）。 */
  public void rebuildRoutes() {
    synchronized (rebuildLock) {
      publish();
    }
  }

  /** 数据版本：每次重建递增。 */
  public long revision() {
    return snapshot.revision();
  }

  /** 当前快照（不可变）。 */
  public Snapshot snapshot() {
    return snapshot;
  }

  /**
   * 没有任何主数据的快照：停靠点只按节点解析站码（站名退回站码、没有车站 ID），没有车站组与停靠线路。
   *
   * <p>给还没接上车站目录的调用方（以及测试）用。
   */
  public static Snapshot detachedSnapshot() {
    return Snapshot.empty();
  }

  private void publish() {
    try {
      snapshot = Snapshot.build(catalog, routes, snapshot, revisionCounter.incrementAndGet());
    } catch (RuntimeException ex) {
      debugLogger.accept("车站目录重建失败，保留旧快照: " + ex);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 数据模型
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * 车站及其所属运营商。
   *
   * @param station 车站记录
   * @param operator 所属运营商
   */
  public record StationEntry(Station station, Operator operator) {
    public StationEntry {
      Objects.requireNonNull(station, "station");
      Objects.requireNonNull(operator, "operator");
    }

    public UUID id() {
      return station.id();
    }

    public String code() {
      return station.code();
    }

    public String name() {
      return station.name();
    }

    /** 车站所属公司（即运营商所属公司）。 */
    public UUID companyId() {
      return operator.companyId();
    }
  }

  /**
   * 停靠点解析出的车站身份。
   *
   * @param stationId 车站记录；按站码查不到记录时为空
   * @param stationCode 站码；非车站节点为空
   * @param stationName 车站记录的站名，查不到记录时退回站码；非车站节点（车库、区间点）为空
   */
  public record StopStation(
      Optional<UUID> stationId, Optional<String> stationCode, Optional<String> stationName) {

    static final StopStation NONE =
        new StopStation(Optional.empty(), Optional.empty(), Optional.empty());

    public StopStation {
      stationId = stationId == null ? Optional.empty() : stationId;
      stationCode = stationCode == null ? Optional.empty() : stationCode;
      stationName = stationName == null ? Optional.empty() : stationName;
    }
  }

  /**
   * 车站组成员（已关联车站记录）。
   *
   * @param member 成员记录
   * @param station 成员车站
   */
  public record MemberEntry(StationGroupMember member, StationEntry station) {}

  /**
   * 车站组及其成员（按 sortOrder、运营商代码、站码排序；指向已删除车站的成员已剔除）。
   *
   * @param group 车站组
   * @param members 成员
   */
  public record GroupEntry(StationGroup group, List<MemberEntry> members) {
    public GroupEntry {
      members = List.copyOf(members);
    }

    /** 查找成员。 */
    public Optional<MemberEntry> member(UUID stationId) {
      for (MemberEntry entry : members) {
        if (entry.station().id().equals(stationId)) {
          return Optional.of(entry);
        }
      }
      return Optional.empty();
    }
  }

  /**
   * 线路及其所属运营商（停靠某车站的一条线路，或按代码查到的线路）。
   *
   * @param line 线路
   * @param operator 线路所属运营商
   */
  public record LineAtStation(Line line, Operator operator) {

    /** 线路色；缺失时回退运营商主题色。 */
    public Optional<String> color() {
      Optional<String> color = line.color().filter(value -> !value.isBlank());
      return color.isPresent() ? color : operator.colorTheme().filter(value -> !value.isBlank());
    }
  }

  /**
   * 查询站可乘坐/可换乘的一条线路。
   *
   * @param line 线路与运营商
   * @param station 这条线实际停靠的车站（可能是同组的另一站）
   * @param transferType 相对查询站的换乘方式；查询站自身的线路为空
   * @param walkSeconds 换乘步行秒数
   * @param sortOrder 实际停靠车站在组内的排序（不在组内为 0）
   */
  public record ServingLineEntry(
      LineAtStation line,
      StationEntry station,
      Optional<StationTransferType> transferType,
      Optional<Integer> walkSeconds,
      int sortOrder) {}

  // ─────────────────────────────────────────────────────────────────────────────
  // 主数据
  // ─────────────────────────────────────────────────────────────────────────────

  /** 主数据：公司、运营商、线路、车站、车站组。不可变。 */
  static final class Catalog {

    static final Catalog EMPTY =
        new Catalog(
            false, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(), List.of());

    /** 是否从存储加载过；没加载过时线路、运营商以交路缓存里的实体为准。 */
    private final boolean loaded;

    private final Map<UUID, Company> companiesById;
    private final Map<UUID, Operator> operatorsById;
    private final Map<String, Operator> operatorsByCode;
    private final Map<UUID, Line> linesById;
    private final Map<String, Line> linesByOperatorAndCode;
    private final Map<UUID, StationEntry> stationsById;
    private final Map<String, StationEntry> stationsByOperatorAndCode;
    private final Map<String, StationEntry> stationsByGraphNode;
    private final List<StationGroup> groups;
    private final List<StationGroupMember> members;

    private Catalog(
        boolean loaded,
        Map<UUID, Company> companiesById,
        Map<UUID, Operator> operatorsById,
        Map<String, Operator> operatorsByCode,
        Map<UUID, Line> linesById,
        Map<String, Line> linesByOperatorAndCode,
        Map<UUID, StationEntry> stationsById,
        Map<String, StationEntry> stationsByOperatorAndCode,
        Map<String, StationEntry> stationsByGraphNode,
        List<StationGroup> groups,
        List<StationGroupMember> members) {
      this.loaded = loaded;
      this.companiesById = Map.copyOf(companiesById);
      this.operatorsById = Map.copyOf(operatorsById);
      this.operatorsByCode = Map.copyOf(operatorsByCode);
      this.linesById = Map.copyOf(linesById);
      this.linesByOperatorAndCode = Map.copyOf(linesByOperatorAndCode);
      this.stationsById = Map.copyOf(stationsById);
      this.stationsByOperatorAndCode = Map.copyOf(stationsByOperatorAndCode);
      this.stationsByGraphNode = Map.copyOf(stationsByGraphNode);
      this.groups = List.copyOf(groups);
      this.members = List.copyOf(members);
    }

    /** 固定几次查询：公司、各公司运营商、全部线路、全部车站、车站组与成员。 */
    static Catalog load(StorageProvider provider, Consumer<String> debugLogger) {
      Map<UUID, Company> companiesById = new HashMap<>();
      Map<UUID, Operator> operatorsById = new HashMap<>();
      Map<String, Operator> operatorsByCode = new HashMap<>();
      for (Company company : provider.companies().listAll()) {
        if (company == null) {
          continue;
        }
        companiesById.put(company.id(), company);
        for (Operator operator : provider.operators().listByCompany(company.id())) {
          if (operator == null) {
            continue;
          }
          operatorsById.put(operator.id(), operator);
          String operatorKey = lower(operator.code());
          if (!operatorKey.isEmpty()) {
            // 跨公司同代码时先到先得（与 HUD 公司显示同一规则）。
            operatorsByCode.putIfAbsent(operatorKey, operator);
          }
        }
      }
      Map<UUID, Line> linesById = new HashMap<>();
      Map<String, Line> linesByKey = new HashMap<>();
      for (Line line : nonNull(provider.lines().listAll())) {
        if (line != null && operatorsById.containsKey(line.operatorId())) {
          linesById.put(line.id(), line);
          String key = stationKey(line.operatorId(), line.code());
          if (!key.isEmpty()) {
            linesByKey.putIfAbsent(key, line);
          }
        }
      }
      Map<UUID, StationEntry> stationsById = new HashMap<>();
      Map<String, StationEntry> stationsByKey = new HashMap<>();
      Map<String, StationEntry> stationsByGraphNode = new HashMap<>();
      for (Station station : nonNull(provider.stations().listAll())) {
        Operator operator = station == null ? null : operatorsById.get(station.operatorId());
        if (operator == null) {
          continue;
        }
        StationEntry entry = new StationEntry(station, operator);
        stationsById.put(station.id(), entry);
        String key = stationKey(operator.id(), station.code());
        if (!key.isEmpty()) {
          stationsByKey.putIfAbsent(key, entry);
        }
        station
            .graphNodeId()
            .map(String::trim)
            .filter(node -> !node.isEmpty())
            .ifPresent(node -> stationsByGraphNode.putIfAbsent(node, entry));
      }
      List<StationGroup> groups = List.of();
      List<StationGroupMember> members = List.of();
      StationGroupRepository groupRepository = provider.stationGroups();
      if (groupRepository != null) {
        try {
          groups = nonNull(groupRepository.listAll());
          members = nonNull(groupRepository.listAllMembers());
        } catch (StorageException ex) {
          debugLogger.accept("车站组加载失败，按无车站组处理: " + ex.getMessage());
          groups = List.of();
          members = List.of();
        }
      }
      return new Catalog(
          true,
          companiesById,
          operatorsById,
          operatorsByCode,
          linesById,
          linesByKey,
          stationsById,
          stationsByKey,
          stationsByGraphNode,
          groups,
          members);
    }

    Optional<StationEntry> station(UUID stationId) {
      return stationId == null
          ? Optional.empty()
          : Optional.ofNullable(stationsById.get(stationId));
    }

    /** 运营商代码所属的公司；跨公司同代码时先到先得，与 {@link #findStation} 同一规则。 */
    Optional<Company> companyOfOperator(String operatorCode) {
      return Optional.ofNullable(operatorsByCode.get(lower(operatorCode)))
          .map(operator -> companiesById.get(operator.companyId()));
    }

    /**
     * 按运营商代码 + 站码找车站；运营商代码与 {@code preferred} 相同时优先在它名下找。
     *
     * @param operatorCode 运营商代码（不区分大小写）
     * @param stationCode 站码（不区分大小写）
     * @param preferred 优先运营商（通常是交路所属运营商），可为 null
     */
    Optional<StationEntry> findStation(
        String operatorCode, String stationCode, Operator preferred) {
      if (operatorCode == null || stationCode == null) {
        return Optional.empty();
      }
      if (preferred != null && preferred.code().trim().equalsIgnoreCase(operatorCode.trim())) {
        StationEntry entry = stationsByOperatorAndCode.get(stationKey(preferred.id(), stationCode));
        if (entry != null) {
          return Optional.of(entry);
        }
      }
      Operator operator = operatorsByCode.get(lower(operatorCode));
      if (operator == null) {
        return Optional.empty();
      }
      return Optional.ofNullable(
          stationsByOperatorAndCode.get(stationKey(operator.id(), stationCode)));
    }

    /**
     * 线路的当前实体（名称、颜色以主数据为准）。
     *
     * <p>主数据已加载却没有这条线路时说明线路（或其运营商、公司）已被删除，而交路缓存还没跟上：返回空，不再把它算作停靠线路。
     */
    Optional<Line> line(Line fallback) {
      Line current = linesById.get(fallback.id());
      if (current != null) {
        return Optional.of(current);
      }
      return loaded ? Optional.empty() : Optional.of(fallback);
    }

    Operator operator(UUID operatorId, Operator fallback) {
      return operatorsById.getOrDefault(operatorId, fallback);
    }

    /**
     * 按运营商代码 + 线路代码找线路（均不区分大小写）；运营商代码与 {@code preferred} 相同时优先在它名下找。
     *
     * @param operatorCode 运营商代码
     * @param lineCode 线路代码
     * @param preferred 优先运营商（通常是交路所属运营商），可为 null
     */
    Optional<LineAtStation> findLine(String operatorCode, String lineCode, Operator preferred) {
      if (operatorCode == null || lineCode == null) {
        return Optional.empty();
      }
      if (preferred != null && preferred.code().trim().equalsIgnoreCase(operatorCode.trim())) {
        Line line = linesByOperatorAndCode.get(stationKey(preferred.id(), lineCode));
        if (line != null) {
          return Optional.of(new LineAtStation(line, operator(line.operatorId(), preferred)));
        }
      }
      Operator operator = operatorsByCode.get(lower(operatorCode));
      if (operator == null) {
        return Optional.empty();
      }
      Line line = linesByOperatorAndCode.get(stationKey(operator.id(), lineCode));
      return line == null ? Optional.empty() : Optional.of(new LineAtStation(line, operator));
    }

    /**
     * 解析停靠点对应的车站。
     *
     * <p>优先级：绑定的 stationId → 车站身份（{@link RouteTerminals#stationIdentityOf}：DYNAMIC 车站规范、站台、咽喉）→
     * waypoint 节点被某车站绑定为图节点。车库、区间点等非车站节点三项皆空。
     */
    StopStation resolveStop(RouteStop stop, Operator routeOperator) {
      if (stop == null) {
        return StopStation.NONE;
      }
      Optional<RouteTerminals.StationRef> key = RouteTerminals.stationIdentityOf(stop);
      if (stop.stationId().isPresent()) {
        Optional<StationEntry> bound = station(stop.stationId().get());
        if (bound.isPresent()) {
          return fromEntry(bound.get());
        }
        // 绑定的车站不在目录里（刚建、尚未刷新）：身份按绑定，名称按节点站码兜底。
        Optional<String> code = key.map(RouteTerminals.StationRef::stationCode);
        return new StopStation(stop.stationId(), code, code);
      }
      if (key.isPresent()) {
        RouteTerminals.StationRef ref = key.get();
        return findStation(ref.operatorCode(), ref.stationCode(), routeOperator)
            .map(Catalog::fromEntry)
            .orElseGet(
                () ->
                    new StopStation(
                        Optional.empty(),
                        Optional.of(ref.stationCode()),
                        Optional.of(ref.stationCode())));
      }
      return stop.waypointNodeId()
          .map(String::trim)
          .map(stationsByGraphNode::get)
          .map(Catalog::fromEntry)
          .orElse(StopStation.NONE);
    }

    /** 节点所属车站：先按车站身份（站台、咽喉、DYNAMIC 占位）找，找不到再看是否被某车站绑定为图节点。 */
    Optional<StationEntry> stationOfNode(String nodeId) {
      if (nodeId == null || nodeId.isBlank()) {
        return Optional.empty();
      }
      Optional<StationEntry> byCode =
          RouteTerminals.stationIdentityOfNode(nodeId)
              .flatMap(ref -> findStation(ref.operatorCode(), ref.stationCode(), null));
      if (byCode.isPresent()) {
        return byCode;
      }
      return Optional.ofNullable(stationsByGraphNode.get(nodeId.trim()));
    }

    private static StopStation fromEntry(StationEntry entry) {
      return new StopStation(
          Optional.of(entry.id()), Optional.of(entry.code()), Optional.of(entry.name()));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 快照
  // ─────────────────────────────────────────────────────────────────────────────

  /** 一次重建的全部结果。不可变（节点解析缓存除外：它只追加，结果只取决于主数据）。 */
  public static final class Snapshot {

    private final long revision;
    private final Catalog catalog;
    private final Map<UUID, RouteStations> routeStations;
    private final List<GroupEntry> groups;
    private final Map<UUID, GroupEntry> groupsById;
    private final Map<UUID, GroupEntry> groupByStation;
    private final Map<UUID, List<LineAtStation>> linesAtStation;
    private final Map<UUID, List<ServingLineEntry>> servingByStation;
    private final ConcurrentHashMap<String, Optional<UUID>> stationByNode;

    private Snapshot(
        long revision,
        Catalog catalog,
        Map<UUID, RouteStations> routeStations,
        List<GroupEntry> groups,
        Map<UUID, GroupEntry> groupByStation,
        Map<UUID, List<LineAtStation>> linesAtStation,
        Map<UUID, List<ServingLineEntry>> servingByStation,
        Map<String, Optional<UUID>> stationByNode) {
      this.revision = revision;
      this.catalog = catalog;
      this.routeStations = Map.copyOf(routeStations);
      this.groups = List.copyOf(groups);
      Map<UUID, GroupEntry> byId = new HashMap<>();
      for (GroupEntry group : groups) {
        byId.put(group.group().id(), group);
      }
      this.groupsById = Map.copyOf(byId);
      this.groupByStation = Map.copyOf(groupByStation);
      this.linesAtStation = Map.copyOf(linesAtStation);
      this.servingByStation = Map.copyOf(servingByStation);
      this.stationByNode = new ConcurrentHashMap<>(stationByNode);
    }

    static Snapshot empty() {
      return new Snapshot(
          0L, Catalog.EMPTY, Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * 按主数据与交路缓存重建。
     *
     * @param catalog 主数据
     * @param routes 交路缓存（只读 {@link RouteDefinitionCache#entries()}，每条交路的定义、归属与停靠表彼此一致）
     * @param previous 上一版快照：主数据没变时，停靠表没变的交路与节点解析结果直接沿用
     * @param revision 新版本号
     */
    static Snapshot build(
        Catalog catalog, RouteDefinitionCache routes, Snapshot previous, long revision) {
      boolean sameCatalog = previous != null && previous.catalog == catalog;
      Map<String, Optional<UUID>> stationByNode =
          sameCatalog ? new HashMap<>(previous.stationByNode) : new HashMap<>();
      Map<UUID, RouteStations> routeStations = new HashMap<>();
      Map<UUID, LinkedHashMap<UUID, LineAtStation>> own = new HashMap<>();
      if (routes != null) {
        for (RouteDefinitionCache.RouteEntry entry : routes.entries()) {
          RouteDefinitionCache.RouteRecord record = entry.record();
          List<RouteStop> stops = entry.stops();
          RouteStations cached = sameCatalog ? previous.routeStations.get(entry.routeId()) : null;
          List<StopStation> resolved;
          if (cached != null && cached.stops() == stops) {
            resolved = cached.stations();
          } else {
            List<StopStation> fresh = new ArrayList<>(stops.size());
            for (RouteStop stop : stops) {
              fresh.add(catalog.resolveStop(stop, record.operator()));
            }
            resolved = List.copyOf(fresh);
            for (NodeId node : entry.definition().waypoints()) {
              String key = node.value().trim();
              stationByNode.computeIfAbsent(
                  key, ignored -> catalog.stationOfNode(key).map(StationEntry::id));
            }
          }
          routeStations.put(entry.routeId(), new RouteStations(stops, resolved));

          Optional<LineAtStation> routeServing =
              catalog
                  .line(record.line())
                  .map(
                      line ->
                          new LineAtStation(
                              line, catalog.operator(line.operatorId(), record.operator())));
          RouteLineChanges.LineRef routeLine =
              new RouteLineChanges.LineRef(record.operator().code(), record.line().code());
          List<RouteLineChanges.LineRef> lines = RouteLineChanges.linesByIndex(stops, routeLine);
          for (int i = 0; i < stops.size(); i++) {
            RouteStop stop = stops.get(i);
            // 只统计停车（STOP/TERMINATE）；出库、回库、运营各阶段都算，调用方可按交路阶段自行过滤。
            if (stop == null || !stop.stops()) {
              continue;
            }
            Optional<UUID> stationId = resolved.get(i).stationId();
            if (stationId.isEmpty()) {
              continue;
            }
            LinkedHashMap<UUID, LineAtStation> atStation =
                own.computeIfAbsent(stationId.get(), ignored -> new LinkedHashMap<>());
            // 直通运转：本站按列车在此所属的线路算；换线站以原线路到达，原线路也算。
            List<RouteLineChanges.LineRef> serving = new ArrayList<>(2);
            if (i > 0 && !lines.get(i - 1).sameLine(lines.get(i))) {
              serving.add(lines.get(i - 1));
            }
            serving.add(lines.get(i));
            for (RouteLineChanges.LineRef ref : serving) {
              Optional<LineAtStation> line =
                  ref.sameLine(routeLine)
                      ? routeServing
                      : catalog.findLine(ref.operatorCode(), ref.lineCode(), record.operator());
              line.ifPresent(value -> atStation.putIfAbsent(value.line().id(), value));
            }
          }
        }
      }
      if (!sameCatalog) {
        for (String node : catalog.stationsByGraphNode.keySet()) {
          stationByNode.computeIfAbsent(
              node, ignored -> catalog.stationOfNode(node).map(StationEntry::id));
        }
      }

      Map<UUID, List<LineAtStation>> linesAtStation = new HashMap<>();
      for (Map.Entry<UUID, LinkedHashMap<UUID, LineAtStation>> entry : own.entrySet()) {
        List<LineAtStation> lines = new ArrayList<>(entry.getValue().values());
        lines.sort(LINE_ORDER);
        linesAtStation.put(entry.getKey(), List.copyOf(lines));
      }

      List<GroupEntry> groups = sameCatalog ? previous.groups : buildGroups(catalog);
      Map<UUID, GroupEntry> groupByStation =
          sameCatalog ? previous.groupByStation : indexByStation(groups);
      Map<UUID, List<ServingLineEntry>> serving = new HashMap<>();
      for (StationEntry station : catalog.stationsById.values()) {
        List<ServingLineEntry> lines =
            buildServing(station, groupByStation.get(station.id()), linesAtStation);
        if (!lines.isEmpty()) {
          serving.put(station.id(), lines);
        }
      }
      return new Snapshot(
          revision,
          catalog,
          routeStations,
          groups,
          groupByStation,
          linesAtStation,
          serving,
          stationByNode);
    }

    private static Map<UUID, GroupEntry> indexByStation(List<GroupEntry> groups) {
      Map<UUID, GroupEntry> byStation = new HashMap<>();
      for (GroupEntry group : groups) {
        for (MemberEntry member : group.members()) {
          byStation.put(member.station().id(), group);
        }
      }
      return byStation;
    }

    private static List<GroupEntry> buildGroups(Catalog catalog) {
      Map<UUID, List<MemberEntry>> membersByGroup = new HashMap<>();
      for (StationGroupMember member : catalog.members) {
        if (member == null) {
          continue;
        }
        // 成员指向已删除的车站时丢掉，不让半条记录进索引。
        catalog
            .station(member.stationId())
            .ifPresent(
                station ->
                    membersByGroup
                        .computeIfAbsent(member.groupId(), ignored -> new ArrayList<>())
                        .add(new MemberEntry(member, station)));
      }
      List<GroupEntry> groups = new ArrayList<>();
      for (StationGroup group : catalog.groups) {
        if (group == null) {
          continue;
        }
        List<MemberEntry> members =
            new ArrayList<>(membersByGroup.getOrDefault(group.id(), List.of()));
        members.sort(
            Comparator.comparingInt((MemberEntry m) -> m.member().sortOrder())
                .thenComparing(m -> lower(m.station().operator().code()))
                .thenComparing(m -> lower(m.station().code())));
        groups.add(new GroupEntry(group, members));
      }
      groups.sort(
          Comparator.comparing((GroupEntry g) -> lower(g.group().code()))
              .thenComparing(g -> g.group().companyId()));
      return groups;
    }

    /**
     * 查询站的停靠线路：本站自身的线路（换乘方式为空）+ 同组其他车站的线路（带该成员的换乘方式与步行秒数）。
     *
     * <p>同一条线路既停本站又停同组其他站时只列一次，保留本站那一条。排序：成员 sortOrder → 运营商代码 → 线路代码。
     */
    private static List<ServingLineEntry> buildServing(
        StationEntry station, GroupEntry group, Map<UUID, List<LineAtStation>> linesAtStation) {
      Map<UUID, ServingLineEntry> byLine = new LinkedHashMap<>();
      int ownSort =
          group == null ? 0 : group.member(station.id()).map(m -> m.member().sortOrder()).orElse(0);
      for (LineAtStation line : linesAtStation.getOrDefault(station.id(), List.of())) {
        byLine.putIfAbsent(
            line.line().id(),
            new ServingLineEntry(line, station, Optional.empty(), Optional.empty(), ownSort));
      }
      if (group != null) {
        for (MemberEntry member : group.members()) {
          if (member.station().id().equals(station.id())) {
            continue;
          }
          for (LineAtStation line : linesAtStation.getOrDefault(member.station().id(), List.of())) {
            byLine.putIfAbsent(
                line.line().id(),
                new ServingLineEntry(
                    line,
                    member.station(),
                    Optional.of(member.member().transferType()),
                    member.member().walkSeconds(),
                    member.member().sortOrder()));
          }
        }
      }
      if (byLine.isEmpty()) {
        return List.of();
      }
      List<ServingLineEntry> result = new ArrayList<>(byLine.values());
      result.sort(
          Comparator.comparingInt(ServingLineEntry::sortOrder)
              .thenComparing(ServingLineEntry::line, LINE_ORDER));
      return List.copyOf(result);
    }

    /** 数据版本。 */
    public long revision() {
      return revision;
    }

    /** 车站记录。 */
    public Optional<StationEntry> station(UUID stationId) {
      return catalog.station(stationId);
    }

    /** 全部车站，按运营商代码、站码排序；主数据未加载时为空。 */
    public List<StationEntry> stations() {
      List<StationEntry> all = new ArrayList<>(catalog.stationsById.values());
      all.sort(
          Comparator.comparing((StationEntry entry) -> lower(entry.operator().code()))
              .thenComparing(entry -> lower(entry.code())));
      return List.copyOf(all);
    }

    /**
     * 运营商代码（不区分大小写）所属的公司；HUD 的公司占位符用它，与车站、线路查找同一套运营商代码口径。
     *
     * @param operatorCode 运营商代码
     * @return 公司；运营商不存在或主数据未加载时为空
     */
    public Optional<Company> companyOfOperator(String operatorCode) {
      return catalog.companyOfOperator(operatorCode);
    }

    /**
     * 按运营商代码 + 站码找车站（均不区分大小写）；HUD 与公开 API 共用这一个口径。
     *
     * @param operatorCode 运营商代码
     * @param stationCode 站码
     * @return 车站；运营商或车站不存在时为空
     */
    public Optional<StationEntry> findStation(String operatorCode, String stationCode) {
      return catalog.findStation(operatorCode, stationCode, null);
    }

    /**
     * 按运营商代码 + 线路代码找线路（均不区分大小写）；跨公司同名运营商先到先得，与 {@link #findStation} 同一规则。
     *
     * <p>直通运转指令里写的代码大小写可能与主数据不同，显示前用它换成主数据的写法、取线路名与颜色。
     *
     * @param operatorCode 运营商代码
     * @param lineCode 线路代码
     * @return 线路与所属运营商；运营商或线路不存在时为空
     */
    public Optional<LineAtStation> findLine(String operatorCode, String lineCode) {
      return catalog.findLine(operatorCode, lineCode, null);
    }

    /**
     * 线路代码的规范写法：线路存在时换成主数据的运营商、线路代码，否则原样返回。
     *
     * <p>直通运转指令与列车标签里的代码是作者手写的（常见小写），公开 API、HUD、站牌都经这里统一，不各自再换一遍。
     *
     * @param line 线路
     * @return 规范写法的线路
     */
    public RouteLineChanges.LineRef canonicalLine(RouteLineChanges.LineRef line) {
      return findLine(line.operatorCode(), line.lineCode())
          .map(found -> new RouteLineChanges.LineRef(found.operator().code(), found.line().code()))
          .orElse(line);
    }

    /**
     * 节点所属车站：站台 {@code OP:S:CODE:TRACK}、咽喉 {@code OP:S:CODE:TRACK:SEQ}、DYNAMIC 占位 {@code
     * OP:S:CODE:fromTrack} 均可；解析不出时再看是否被某车站绑定为图节点。区间点、车库等为空。
     *
     * <p>交路途经节点与车站绑定的图节点在重建时已解析好，其余节点首次查询时解析一次后缓存；两种路径用同一个函数，结果一致。
     */
    public Optional<StationEntry> stationOfNode(String nodeId) {
      return stationIdOfNode(nodeId).flatMap(catalog::station);
    }

    /** 同 {@link #stationOfNode}，只返回车站 ID。 */
    public Optional<UUID> stationIdOfNode(String nodeId) {
      if (nodeId == null || nodeId.isBlank()) {
        return Optional.empty();
      }
      String key = nodeId.trim();
      Optional<UUID> cached = stationByNode.get(key);
      if (cached != null) {
        return cached;
      }
      Optional<UUID> resolved = catalog.stationOfNode(key).map(StationEntry::id);
      if (stationByNode.size() < NODE_MEMO_LIMIT) {
        Optional<UUID> raced = stationByNode.putIfAbsent(key, resolved);
        if (raced != null) {
          return raced;
        }
      }
      return resolved;
    }

    /**
     * 交路各停靠点的车站身份，与 {@code stops} 下标一一对应。
     *
     * <p>{@code stops} 就是重建时的那一份（交路缓存没再变）时直接返回预先算好的结果；否则按当前主数据现算（只查内存）。
     *
     * @param routeUuid 交路 UUID
     * @param stops 交路缓存的停靠表（{@link RouteDefinitionCache#listStops}）
     * @param routeOperator 交路所属运营商（用于同代码优先）
     */
    public List<StopStation> stopStations(
        UUID routeUuid, List<RouteStop> stops, Optional<Operator> routeOperator) {
      if (stops == null || stops.isEmpty()) {
        return List.of();
      }
      RouteStations cached = routeUuid == null ? null : routeStations.get(routeUuid);
      if (cached != null && cached.stops() == stops) {
        return cached.stations();
      }
      List<StopStation> result = new ArrayList<>(stops.size());
      for (RouteStop stop : stops) {
        result.add(catalog.resolveStop(stop, routeOperator.orElse(null)));
      }
      return List.copyOf(result);
    }

    /** 全部车站组（按组代码排序）。 */
    public List<GroupEntry> groups() {
      return groups;
    }

    /** 按 ID 找车站组。 */
    public Optional<GroupEntry> group(UUID groupId) {
      return groupId == null ? Optional.empty() : Optional.ofNullable(groupsById.get(groupId));
    }

    /** 车站所属的车站组。 */
    public Optional<GroupEntry> groupOfStation(UUID stationId) {
      return stationId == null
          ? Optional.empty()
          : Optional.ofNullable(groupByStation.get(stationId));
    }

    /** 只停靠这一个车站记录的线路（不含同组其他站），按运营商代码、线路代码排序。 */
    public List<LineAtStation> linesAt(UUID stationId) {
      return stationId == null ? List.of() : linesAtStation.getOrDefault(stationId, List.of());
    }

    /** 停靠本站及同组各站的全部线路，见 {@link #buildServing}。 */
    public List<ServingLineEntry> linesServing(UUID stationId) {
      return stationId == null ? List.of() : servingByStation.getOrDefault(stationId, List.of());
    }

    /** 有停靠线路（含同组换乘线路）的全部车站。 */
    public Map<UUID, List<ServingLineEntry>> servingByStation() {
      return servingByStation;
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 工具
  // ─────────────────────────────────────────────────────────────────────────────

  private static final Comparator<LineAtStation> LINE_ORDER =
      Comparator.comparing((LineAtStation l) -> lower(l.operator().code()))
          .thenComparing(l -> lower(l.line().code()))
          .thenComparing(l -> l.line().id());

  private record RouteStations(List<RouteStop> stops, List<StopStation> stations) {}

  private static String stationKey(UUID operatorId, String stationCode) {
    String code = lower(stationCode);
    if (operatorId == null || code.isEmpty()) {
      return "";
    }
    return operatorId + ":" + code;
  }

  private static String lower(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static <T> List<T> nonNull(List<T> values) {
    return values == null ? List.of() : values;
  }
}
