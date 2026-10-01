package org.fetarute.fetaruteTCAddon.display.pids.view;

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
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
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
 */
public final class ApiPidsDirectory implements PidsDirectory {

  /** 线路与运营商都没有颜色时的色牌颜色。 */
  static final int DEFAULT_LINE_COLOR = 0x696969;

  private final OperatorApi operators;
  private final LineApi lines;
  private final StationApi stations;
  private final RouteApi routes;
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
  public List<LineChip> linesServingPlatform(PidsStationKey station, String platform) {
    return index.platformLines.getOrDefault(platformKey(station.toString(), platform), List.of());
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
    int failures = 0;
    Map<String, Line> lineIndex = new HashMap<>();
    for (LineApi.LineInfo line : lines.listAllLines()) {
      OperatorApi.OperatorInfo operator = operatorsById.get(line.operatorId());
      if (operator == null) {
        continue;
      }
      int color =
          parseColor(line.color())
              .or(() -> parseColor(operator.colorTheme()))
              .orElse(DEFAULT_LINE_COLOR);
      lineIndex.put(
          key(operator.code(), line.code()),
          new Line(
              new LineStyle(line.code(), color),
              new Names(line.name(), line.secondaryName().orElse(""))));
    }
    Map<String, Names> stationIndex = new HashMap<>();
    Map<String, List<LineChip>> stationLines = new HashMap<>();
    for (StationApi.StationInfo station : stations.listAllStations()) {
      OperatorApi.OperatorInfo operator = operatorsById.get(station.operatorId());
      if (operator == null) {
        continue;
      }
      String stationKey = key(operator.code(), station.code());
      stationIndex.put(stationKey, new Names(station.name(), station.secondaryName().orElse("")));
      Map<String, LineChip> chips = new LinkedHashMap<>();
      try {
        for (StationApi.ServingLine serving : stations.linesServing(station.id())) {
          Optional.ofNullable(lineIndex.get(key(serving.operatorCode(), serving.lineCode())))
              .ifPresent(line -> chips.putIfAbsent(line.style().code(), line.chip()));
        }
      } catch (RuntimeException ex) {
        failures++;
      }
      stationLines.put(stationKey, List.copyOf(chips.values()));
    }
    Map<String, RouteApi.OperationType> serviceTypes = new HashMap<>();
    Map<String, Set<String>> platformLineKeys = new HashMap<>();
    for (RouteApi.RouteInfo route : routes.listRoutes()) {
      if (ambiguous.contains(key(route.operatorCode()))) {
        continue;
      }
      serviceTypes.put(
          key(route.operatorCode(), route.lineCode(), route.routeCode()), route.operationType());
      try {
        routes
            .getRoute(route.id())
            .ifPresent(detail -> collectPlatformLines(route, detail, platformLineKeys));
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
    return new Index(
        Map.copyOf(stationIndex),
        Map.copyOf(lineIndex),
        Map.copyOf(serviceTypes),
        Map.copyOf(stationLines),
        Map.copyOf(platformLines));
  }

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
      Map<String, List<LineChip>> platformLines) {
    static final Index EMPTY = new Index(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }
}
