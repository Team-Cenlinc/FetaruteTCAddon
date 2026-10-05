package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.display.pids.PidsPlatformNode;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.LineChip;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 由公开 API 实现的名称目录。
 *
 * <p>线路、车站、运营商的列表接口每次都读库，不能放在渲染路径上：{@link #refresh()} 在异步线程整体重建一份不可变索引，查询只读当前索引。
 * 索引建好之前查询都为空，名称退回代码；重建后视图随之变化，屏幕自然重绘。
 *
 * <p>站台线路由交路停靠表推出：只算停车与终到（不含通过与动态站台）；出库、运营、回库交路都算。换线站两条线都算（以原线路到达、以新线路发车），其后各站算新线路。
 *
 * <p>运营商按代码查找。同一代码属于多家公司时，这个代码下的车站与线路一律不收录并告警：显示代码而不是可能张冠李戴的名称， 也免得另开公司抢同名代码就能改别人屏幕上的站名。
 *
 * <p>单个车站、单个交路读取失败只跳过那一项并告警，不让整份索引作废。
 *
 * <p>另记各交路的途经节点与各世界调度图节点的坐标，空位页据此定车头朝屏幕哪一侧（前进方向）； 以及各交路的停靠点、配置的经由站与各车站的站台数，备注据此写“经由”“直通”。
 *
 * <p>线路运行状况屏要的运营商线路（不含筹建中的）与封锁区间也在这里：交路相邻两个途经节点在调度图里本来连通、去掉被封锁的边就不通时， 这一段算断开，取断开处前后最近的停车站，
 * 记在当时对乘客显示的线路（换线站以新线路发车）下；每条线路只记按交路代码排序遇到的第一处。
 */
public final class ApiPidsDirectory implements PidsDirectory {

  /** 线路代码排序：字母段不分大小写按字典序，数字段按数值（L2 在 L10 之前）。 */
  static final Comparator<String> LINE_CODE_ORDER = ApiPidsDirectory::compareCodes;

  private static final Pattern CODE_PARTS = Pattern.compile("\\d+|\\D+");

  /** 线路与运营商都没有颜色时的色牌颜色。 */
  static final int DEFAULT_LINE_COLOR = 0x696969;

  private final OperatorApi operators;
  private final LineApi lines;
  private final StationApi stations;
  private final RouteApi routes;
  private final GraphApi graph;
  private final Consumer<String> warn;
  private final Set<String> warnedCodes = ConcurrentHashMap.newKeySet();
  private volatile Index index = Index.EMPTY;
  private volatile int lastFailures;

  /**
   * @param warn 告警出口（运营商代码重复、单项读取失败）
   */
  public ApiPidsDirectory(
      OperatorApi operators,
      LineApi lines,
      StationApi stations,
      RouteApi routes,
      Consumer<String> warn) {
    this(operators, lines, stations, routes, null, warn);
  }

  /**
   * @param graph 调度图；为 null 时不记节点坐标
   * @param warn 告警出口（运营商代码重复、单项读取失败）
   */
  public ApiPidsDirectory(
      OperatorApi operators,
      LineApi lines,
      StationApi stations,
      RouteApi routes,
      GraphApi graph,
      Consumer<String> warn) {
    this.graph = graph;
    this.operators = Objects.requireNonNull(operators, "operators");
    this.lines = Objects.requireNonNull(lines, "lines");
    this.stations = Objects.requireNonNull(stations, "stations");
    this.routes = Objects.requireNonNull(routes, "routes");
    this.warn = Objects.requireNonNull(warn, "warn");
  }

  /**
   * 重建索引。会读库，不要在主线程调用；失败时保留旧索引并抛出异常。
   *
   * @throws RuntimeException 存储不可用等
   */
  public void refresh() {
    index = build();
  }

  @Override
  public Optional<Names> stationName(String stationId) {
    return Optional.ofNullable(stationId).map(ApiPidsDirectory::key).map(index.stations::get);
  }

  @Override
  public Optional<LineStyle> line(String operatorCode, String lineCode) {
    return Optional.ofNullable(index.lines.get(key(operatorCode, lineCode))).map(Line::style);
  }

  @Override
  public Optional<RouteApi.OperationType> serviceType(String routeId) {
    return Optional.ofNullable(routeId).map(ApiPidsDirectory::key).map(index.serviceTypes::get);
  }

  @Override
  public List<LineChip> linesServing(PidsStationKey station) {
    return index.stationLines.getOrDefault(key(station.toString()), List.of());
  }

  @Override
  public List<RouteApi.LineRef> lineRefsServing(PidsStationKey station) {
    return index.stationLineRefs.getOrDefault(key(station.toString()), List.of());
  }

  @Override
  public Optional<RouteApi.RouteStage> routeStage(String routeId) {
    return Optional.ofNullable(routeId).map(ApiPidsDirectory::key).map(index.stages::get);
  }

  @Override
  public List<LineChip> linesServingPlatform(PidsStationKey station, String platform) {
    return index.platformLines.getOrDefault(platformKey(station.toString(), platform), List.of());
  }

  @Override
  public List<String> waypoints(String routeId) {
    return routeId == null ? List.of() : index.waypoints.getOrDefault(key(routeId), List.of());
  }

  @Override
  public Optional<GraphApi.Position> nodePosition(UUID worldId, String nodeId) {
    if (worldId == null || nodeId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(index.positions.getOrDefault(worldId, Map.of()).get(key(nodeId)));
  }

  @Override
  public List<RouteStop> stops(String routeId) {
    return routeId == null ? List.of() : index.routeStops.getOrDefault(key(routeId), List.of());
  }

  @Override
  public List<String> via(String routeId) {
    return routeId == null ? List.of() : index.via.getOrDefault(key(routeId), List.of());
  }

  @Override
  public int platformCount(String stationId) {
    return stationId == null ? 0 : index.platformCounts.getOrDefault(key(stationId), 0);
  }

  @Override
  public Optional<Names> lineName(String operatorCode, String lineCode) {
    return Optional.ofNullable(index.lines.get(key(operatorCode, lineCode))).map(Line::names);
  }

  @Override
  public List<OperatorLine> operatorLines(String operatorCode) {
    return index.operatorLines.getOrDefault(key(operatorCode), List.of());
  }

  @Override
  public Optional<Names> operatorName(String operatorCode) {
    return Optional.ofNullable(index.operatorNames.get(key(operatorCode)));
  }

  @Override
  public Optional<UUID> companyOfOperator(String operatorCode) {
    return Optional.ofNullable(index.operatorCompanies.get(key(operatorCode)));
  }

  @Override
  public Optional<RouteApi.LineRef> lineOfRoute(UUID routeId) {
    return Optional.ofNullable(routeId).map(index.routeLines::get);
  }

  private Index build() {
    Map<UUID, OperatorApi.OperatorInfo> operatorsById = new HashMap<>();
    Map<String, Set<UUID>> companiesByCode = new HashMap<>();
    for (OperatorApi.OperatorInfo operator : operators.listAllOperators()) {
      companiesByCode
          .computeIfAbsent(key(operator.code()), ignored -> new HashSet<>())
          .add(operator.companyId());
      operatorsById.put(operator.id(), operator);
    }
    Set<String> ambiguous = new HashSet<>();
    companiesByCode.forEach(
        (code, companies) -> {
          if (companies.size() > 1) {
            ambiguous.add(code);
            if (warnedCodes.add(code)) {
              warn.accept(
                  "运营商代码 " + code + " 属于 " + companies.size() + " 家公司，站台屏不显示该代码下的站名与线路名；请改成唯一代码");
            }
          }
        });
    operatorsById.values().removeIf(operator -> ambiguous.contains(key(operator.code())));
    Map<String, Names> operatorNames = new HashMap<>();
    Map<String, UUID> operatorCompanies = new HashMap<>();
    operatorsById
        .values()
        .forEach(
            operator -> {
              operatorNames.put(
                  key(operator.code()),
                  new Names(operator.name(), operator.secondaryName().orElse("")));
              operatorCompanies.put(key(operator.code()), operator.companyId());
            });
    int failures = 0;
    Map<String, Line> lineIndex = new HashMap<>();
    List<LineDraft> drafts = new ArrayList<>();
    for (LineApi.LineInfo line : lines.listAllLines()) {
      OperatorApi.OperatorInfo operator = operatorsById.get(line.operatorId());
      if (operator == null) {
        continue;
      }
      int color =
          parseColor(line.color())
              .or(() -> parseColor(operator.colorTheme()))
              .orElse(DEFAULT_LINE_COLOR);
      Line entry =
          new Line(
              new LineStyle(line.code(), color),
              new Names(line.name(), line.secondaryName().orElse("")));
      lineIndex.put(key(operator.code(), line.code()), entry);
      if (line.status() != LineApi.LineStatus.PLANNING) {
        drafts.add(new LineDraft(line.id(), operator.code(), entry, line.status()));
      }
    }
    Map<String, Names> stationIndex = new HashMap<>();
    Map<String, List<LineChip>> stationLines = new HashMap<>();
    Map<String, List<RouteApi.LineRef>> stationLineRefs = new HashMap<>();
    for (StationApi.StationInfo station : stations.listAllStations()) {
      OperatorApi.OperatorInfo operator = operatorsById.get(station.operatorId());
      if (operator == null) {
        continue;
      }
      String stationKey = key(operator.code(), station.code());
      stationIndex.put(stationKey, new Names(station.name(), station.secondaryName().orElse("")));
      Map<String, LineChip> chips = new LinkedHashMap<>();
      Map<String, RouteApi.LineRef> refs = new LinkedHashMap<>();
      try {
        for (StationApi.ServingLine serving : stations.linesServing(station.id())) {
          Optional.ofNullable(lineIndex.get(key(serving.operatorCode(), serving.lineCode())))
              .ifPresent(
                  line -> {
                    if (chips.putIfAbsent(line.style().code(), line.chip()) == null) {
                      refs.put(
                          line.style().code(),
                          new RouteApi.LineRef(serving.operatorCode(), serving.lineCode()));
                    }
                  });
        }
      } catch (RuntimeException ex) {
        failures++;
      }
      stationLines.put(stationKey, List.copyOf(chips.values()));
      stationLineRefs.put(stationKey, List.copyOf(refs.values()));
    }
    Map<String, RouteApi.OperationType> serviceTypes = new HashMap<>();
    Map<String, RouteApi.RouteStage> stages = new HashMap<>();
    Map<String, List<String>> waypoints = new HashMap<>();
    Map<String, List<RouteStop>> routeStops = new HashMap<>();
    Map<String, List<String>> via = new HashMap<>();
    Map<String, Set<String>> platformLineKeys = new HashMap<>();
    Map<UUID, RouteApi.LineRef> routeLines = new HashMap<>();
    List<Track> tracks = new ArrayList<>();
    for (RouteApi.RouteInfo route : routes.listRoutes()) {
      if (ambiguous.contains(key(route.operatorCode()))) {
        continue;
      }
      String routeKey = key(route.operatorCode(), route.lineCode(), route.routeCode());
      serviceTypes.put(routeKey, route.operationType());
      stages.put(routeKey, route.stage());
      routeLines.put(route.id(), new RouteApi.LineRef(route.operatorCode(), route.lineCode()));
      try {
        routes
            .getRoute(route.id())
            .ifPresent(
                detail -> {
                  collectPlatformLines(route, detail, platformLineKeys);
                  List<RouteStop> stops =
                      List.copyOf(
                          detail.stops().stream().map(ApiPidsDirectory::routeStop).toList());
                  waypoints.put(routeKey, List.copyOf(detail.waypoints()));
                  routeStops.put(routeKey, stops);
                  tracks.add(
                      new Track(
                          routeKey,
                          key(route.operatorCode(), route.lineCode()),
                          List.copyOf(detail.waypoints()),
                          stops));
                  if (!detail.via().isEmpty()) {
                    via.put(routeKey, detail.via());
                  }
                });
      } catch (RuntimeException ex) {
        failures++;
      }
    }
    if (failures > 0 && failures != lastFailures) {
      warn.accept("站台屏名称目录有 " + failures + " 个车站或交路读取失败，已跳过");
    }
    lastFailures = failures;
    Map<String, List<LineChip>> platformLines = new HashMap<>();
    platformLineKeys.forEach(
        (platform, lineKeys) ->
            platformLines.put(
                platform,
                lineKeys.stream()
                    .map(lineIndex::get)
                    .filter(Objects::nonNull)
                    .map(Line::chip)
                    .sorted(Comparator.comparing(LineChip::code))
                    .toList()));
    GraphIndex graphIndex = graphIndex(waypoints, tracks);
    return new Index(
        Map.copyOf(stationIndex),
        Map.copyOf(lineIndex),
        Map.copyOf(serviceTypes),
        Map.copyOf(stationLines),
        Map.copyOf(platformLines),
        Map.copyOf(waypoints),
        graphIndex.positions(),
        Map.copyOf(routeStops),
        Map.copyOf(via),
        graphIndex.platformCounts(),
        operatorLines(drafts, graphIndex.sections()),
        Map.copyOf(operatorNames),
        Map.copyOf(routeLines),
        Map.copyOf(stationLineRefs),
        Map.copyOf(stages),
        Map.copyOf(operatorCompanies));
  }

  /** 各运营商的线路，按代码排序；断开的区间按显示代码对上。 */
  private static Map<String, List<OperatorLine>> operatorLines(
      List<LineDraft> drafts, Map<String, Section> sections) {
    Map<String, List<OperatorLine>> out = new HashMap<>();
    drafts.stream()
        .sorted(Comparator.comparing(draft -> draft.line().style().code(), LINE_CODE_ORDER))
        .forEach(
            draft ->
                out.computeIfAbsent(key(draft.operatorCode()), ignored -> new ArrayList<>())
                    .add(
                        new OperatorLine(
                            draft.id(),
                            draft.operatorCode(),
                            draft.line().chip(),
                            draft.status(),
                            Optional.ofNullable(
                                sections.get(
                                    key(draft.operatorCode(), draft.line().style().code()))))));
    out.replaceAll((operator, lines) -> List.copyOf(lines));
    return Map.copyOf(out);
  }

  /** 交路停靠点：车站按节点认（站台、DYNAMIC 占位股道），区间点、咽喉、车库不算车站。 */
  private static RouteStop routeStop(RouteApi.StopInfo stop) {
    return new RouteStop(
        RouteTerminals.stationRefOfNode(stop.nodeId())
            .map(ref -> key(ref.operatorCode(), ref.stationCode())),
        stop.passType() != RouteApi.PassType.PASS,
        stop.lineChange());
  }

  /**
   * 调度图里读出的两样东西：空位页要用的节点坐标（各交路的途经节点与车站站台，按世界分开），与各车站的站台数（备注认大站）。 没有调度图接口时都为空。
   *
   * <p>两样都只是辅助：读失败只告警并沿用上一份，不让站名、线路这些主数据跟着停在旧版本。
   */
  private GraphIndex graphIndex(Map<String, List<String>> waypoints, List<Track> tracks) {
    if (graph == null) {
      return new GraphIndex(Map.of(), Map.of(), Map.of());
    }
    Set<String> wanted = new HashSet<>();
    waypoints.values().forEach(nodes -> nodes.forEach(node -> wanted.add(key(node))));
    try {
      Map<UUID, Map<String, GraphApi.Position>> positions = new HashMap<>();
      Map<String, Set<String>> platforms = new HashMap<>();
      Map<String, Section> sections = new HashMap<>();
      List<Track> ordered = tracks.stream().sorted(Comparator.comparing(Track::routeKey)).toList();
      for (GraphApi.WorldGraphEntry entry : graph.listAllSnapshots()) {
        collectSections(entry.snapshot(), ordered, sections);
        Map<String, GraphApi.Position> nodes = new HashMap<>();
        for (GraphApi.ApiNode node : entry.snapshot().nodes()) {
          String nodeKey = key(node.id());
          if (node.type() == GraphApi.NodeType.STATION || wanted.contains(nodeKey)) {
            nodes.put(nodeKey, node.position());
          }
          if (node.type() == GraphApi.NodeType.STATION) {
            PidsPlatformNode.parse(node.id())
                .ifPresent(
                    platform ->
                        platforms
                            .computeIfAbsent(
                                key(platform.station().toString()), ignored -> new HashSet<>())
                            .add(key(platform.platform())));
          }
        }
        positions.put(entry.worldId(), Map.copyOf(nodes));
      }
      Map<String, Integer> counts = new HashMap<>();
      platforms.forEach((station, numbers) -> counts.put(station, numbers.size()));
      return new GraphIndex(Map.copyOf(positions), Map.copyOf(counts), Map.copyOf(sections));
    } catch (RuntimeException ex) {
      warn.accept("读取调度图节点失败，空位页车头朝向、车站站台数与封锁区间沿用上一份: " + ex);
      return new GraphIndex(index.positions, index.platformCounts, previousSections());
    }
  }

  /** 上一份索引里的封锁区间，按线路键重新收拢。 */
  private Map<String, Section> previousSections() {
    Map<String, Section> out = new HashMap<>();
    index.operatorLines.values().stream()
        .flatMap(List::stream)
        .forEach(
            line ->
                line.suspended()
                    .ifPresent(
                        section -> out.put(key(line.operatorCode(), line.chip().code()), section)));
    return out;
  }

  /** 一个世界里各交路被封锁区间断开的地方：比较“全部边”与“去掉被封锁的边”两种连通性，前者连通、后者不通的相邻途经节点即断开。 世界里没有被封锁的边时直接跳过。 */
  private static void collectSections(
      GraphApi.GraphSnapshot snapshot, List<Track> tracks, Map<String, Section> out) {
    if (snapshot.edges().stream().noneMatch(GraphApi.ApiEdge::blocked)) {
      return;
    }
    Components all = new Components();
    Components open = new Components();
    for (GraphApi.ApiEdge edge : snapshot.edges()) {
      all.union(key(edge.nodeA()), key(edge.nodeB()));
      if (!edge.blocked()) {
        open.union(key(edge.nodeA()), key(edge.nodeB()));
      }
    }
    for (Track track : tracks) {
      String line = track.lineKey();
      int size = Math.min(track.waypoints().size(), track.stops().size());
      for (int i = 0; i + 1 < size; i++) {
        Optional<RouteApi.LineRef> change = track.stops().get(i).lineChange();
        if (change.isPresent()) {
          line = key(change.get().operatorCode(), change.get().lineCode());
        }
        String a = key(track.waypoints().get(i));
        String b = key(track.waypoints().get(i + 1));
        if (out.containsKey(line) || a.equals(b) || !all.connected(a, b) || open.connected(a, b)) {
          continue;
        }
        Optional<String> from = stationBefore(track.stops(), i);
        Optional<String> to = stationAfter(track.stops(), i + 1, size);
        if (from.isPresent() && to.isPresent() && !from.equals(to)) {
          out.put(line, new Section(from.get(), to.get()));
        }
      }
    }
  }

  /** 下标 {@code index} 及之前最近的停车站。 */
  private static Optional<String> stationBefore(List<RouteStop> stops, int index) {
    for (int i = index; i >= 0; i--) {
      RouteStop stop = stops.get(i);
      if (stop.stops() && stop.stationId().isPresent()) {
        return stop.stationId();
      }
    }
    return Optional.empty();
  }

  /** 下标 {@code index} 及之后最近的停车站。 */
  private static Optional<String> stationAfter(List<RouteStop> stops, int index, int size) {
    for (int i = index; i < size; i++) {
      RouteStop stop = stops.get(i);
      if (stop.stops() && stop.stationId().isPresent()) {
        return stop.stationId();
      }
    }
    return Optional.empty();
  }

  /** 无向图连通分量（并查集）；没出现过的节点自成一个分量。 */
  private static final class Components {
    private final Map<String, String> parent = new HashMap<>();

    void union(String a, String b) {
      String rootA = find(a);
      String rootB = find(b);
      if (!rootA.equals(rootB)) {
        parent.put(rootA, rootB);
      }
    }

    boolean connected(String a, String b) {
      return find(a).equals(find(b));
    }

    private String find(String node) {
      String root = node;
      String next;
      while ((next = parent.get(root)) != null) {
        root = next;
      }
      String cursor = node;
      while (!cursor.equals(root)) {
        String up = parent.get(cursor);
        parent.put(cursor, root);
        cursor = up;
      }
      return root;
    }
  }

  private record GraphIndex(
      Map<UUID, Map<String, GraphApi.Position>> positions,
      Map<String, Integer> platformCounts,
      Map<String, Section> sections) {}

  /**
   * 一个交路的途经节点与停靠点（同下标），找封锁区间用。
   *
   * @param routeKey 交路键
   * @param lineKey 交路本身所属线路的键
   * @param waypoints 途经节点
   * @param stops 停靠点
   */
  private record Track(
      String routeKey, String lineKey, List<String> waypoints, List<RouteStop> stops) {}

  /** 还没对上封锁区间的运营商线路。 */
  private record LineDraft(UUID id, String operatorCode, Line line, LineApi.LineStatus status) {}

  private static void collectPlatformLines(
      RouteApi.RouteInfo route, RouteApi.RouteDetail detail, Map<String, Set<String>> out) {
    String current = key(route.operatorCode(), route.lineCode());
    for (RouteApi.StopInfo stop : detail.stops()) {
      Optional<String> next =
          stop.lineChange().map(change -> key(change.operatorCode(), change.lineCode()));
      if (stop.passType() != RouteApi.PassType.PASS && !stop.dynamic()) {
        String arriving = current;
        platformOf(stop.nodeId())
            .ifPresent(
                platform -> {
                  Set<String> lineKeys =
                      out.computeIfAbsent(platform, ignored -> new LinkedHashSet<>());
                  lineKeys.add(arriving);
                  next.ifPresent(lineKeys::add);
                });
      }
      if (next.isPresent()) {
        current = next.get();
      }
    }
  }

  /** 站台节点的索引键；咽喉、车库与区间点为空。 */
  private static Optional<String> platformOf(String nodeId) {
    return PidsPlatformNode.parse(nodeId)
        .map(node -> platformKey(node.station().toString(), node.platform()));
  }

  private static String platformKey(String stationId, String platform) {
    return key(stationId) + "#" + key(platform);
  }

  private static Optional<Integer> parseColor(Optional<String> hex) {
    return hex.map(String::trim)
        .map(value -> value.startsWith("#") ? value.substring(1) : value)
        .filter(value -> value.matches("[0-9A-Fa-f]{6}"))
        .map(value -> Integer.parseInt(value, 16));
  }

  private static int compareCodes(String a, String b) {
    Matcher left = CODE_PARTS.matcher(a);
    Matcher right = CODE_PARTS.matcher(b);
    while (true) {
      boolean hasLeft = left.find();
      boolean hasRight = right.find();
      if (!hasLeft || !hasRight) {
        return hasLeft == hasRight ? a.compareTo(b) : (hasLeft ? 1 : -1);
      }
      String x = left.group();
      String y = right.group();
      boolean digits = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0));
      int order =
          digits ? new BigInteger(x).compareTo(new BigInteger(y)) : x.compareToIgnoreCase(y);
      if (order != 0) {
        return order;
      }
    }
  }

  private static String key(String... parts) {
    List<String> normalized = new ArrayList<>(parts.length);
    for (String part : parts) {
      normalized.add(part == null ? "" : part.trim().toUpperCase(Locale.ROOT));
    }
    return String.join(":", normalized);
  }

  private record Line(LineStyle style, Names names) {
    LineChip chip() {
      return new LineChip(style.code(), style.color(), names);
    }
  }

  private record Index(
      Map<String, Names> stations,
      Map<String, Line> lines,
      Map<String, RouteApi.OperationType> serviceTypes,
      Map<String, List<LineChip>> stationLines,
      Map<String, List<LineChip>> platformLines,
      Map<String, List<String>> waypoints,
      Map<UUID, Map<String, GraphApi.Position>> positions,
      Map<String, List<RouteStop>> routeStops,
      Map<String, List<String>> via,
      Map<String, Integer> platformCounts,
      Map<String, List<OperatorLine>> operatorLines,
      Map<String, Names> operatorNames,
      Map<UUID, RouteApi.LineRef> routeLines,
      Map<String, List<RouteApi.LineRef>> stationLineRefs,
      Map<String, RouteApi.RouteStage> stages,
      Map<String, UUID> operatorCompanies) {
    static final Index EMPTY =
        new Index(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }
}
