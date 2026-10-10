package org.fetarute.fetaruteTCAddon.call;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 一个车站（及屏幕绑定的站台）上能叫的车：按“线路 + 运营类型 + 开往 + 站台”分方向，每个方向列出能跑这一趟的交路。
 *
 * <p>收载客的交路：运营交路（{@code OPERATION}）、首站是车库的出库交路（{@code CREATE}）与沿途停站的回库交路（{@code RETURN}），
 * 在本站停车、本站不是运营终点的。车从哪里来见 {@link Origin}；不论哪种，本站上游有合适的区间点时都可以在那里生成。
 */
public final class CallCatalog {

  private CallCatalog() {}

  /** 交路本来的车源（区间生成不在此列，各种交路都可以）。 */
  public enum Origin {
    /** 首站带 {@code CRET}：从车库出车。 */
    DEPOT,
    /** 运营交路、首站不是车库：接首站的待命车。 */
    STANDBY,
    /** 载客回库交路：只能区间生成或靠折返车（先开进首站再折返），跑完直接进库。 */
    ENTRY
  }

  /**
   * 能跑这一趟的一条交路。
   *
   * @param routeId 交路 UUID
   * @param routeCode 交路代码
   * @param stopIndex 本站在交路停靠表里的下标
   * @param origin 交路本来的车源
   * @param startNode 交路首个节点（待命车按它找）
   */
  public record CallRoute(
      UUID routeId, String routeCode, int stopIndex, Origin origin, String startNode) {
    public CallRoute {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(startNode, "startNode");
    }

    /** 首站带 {@code CRET}，从车库出车。 */
    public boolean fromDepot() {
      return origin == Origin.DEPOT;
    }
  }

  /**
   * 一个可叫车的方向。
   *
   * @param key 方向的稳定标识（线路、运营类型、开往、站台）
   * @param operatorCode 线路所属运营商
   * @param lineId 线路 UUID
   * @param lineCode 线路代码
   * @param pattern 运营类型（各停、快速等）
   * @param destination 开往的车站
   * @param platforms 在本站停的站台（股道号）；DYNAMIC 停靠不限股道时为空
   * @param routes 能跑这一趟的交路
   */
  public record CallDirection(
      String key,
      String operatorCode,
      UUID lineId,
      String lineCode,
      RoutePatternType pattern,
      PidsStationKey destination,
      Set<String> platforms,
      List<CallRoute> routes) {
    public CallDirection {
      Objects.requireNonNull(key, "key");
      platforms = Set.copyOf(platforms);
      routes = List.copyOf(routes);
    }

    /** 站台号按数字排序，用 “/” 连起来；不限股道时为空串。 */
    public String platformLabel() {
      return String.join("/", sortedPlatforms(platforms));
    }
  }

  /**
   * 列出车站上能叫的车。
   *
   * @param entries 交路缓存的全部交路
   * @param directory 车站目录快照（只绑定 stationId 的停靠靠它认站）
   * @param callable 线路是否开放叫车
   * @param station 车站
   * @param screenPlatforms 屏幕绑定的站台；空表示全站
   */
  public static List<CallDirection> directions(
      Collection<RouteDefinitionCache.RouteEntry> entries,
      StationDirectory.Snapshot directory,
      Predicate<Line> callable,
      PidsStationKey station,
      Set<String> screenPlatforms) {
    if (entries == null || station == null) {
      return List.of();
    }
    Set<String> screen = normalizePlatforms(screenPlatforms);
    Map<String, Builder> byKey = new LinkedHashMap<>();
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      if (entry == null || !callable.test(entry.record().line())) {
        continue;
      }
      List<RouteStop> stops = entry.stops();
      if (stops.size() != entry.definition().waypoints().size() || stops.size() < 2) {
        continue;
      }
      Optional<Origin> origin = originOf(entry.record().route().operationType(), stops.get(0));
      if (origin.isEmpty()) {
        continue;
      }
      List<StationDirectory.StopStation> stations =
          directory == null
              ? List.of()
              : directory.stopStations(
                  entry.routeId(), stops, Optional.of(entry.record().operator()));
      OptionalInt end = RouteTerminals.endOfOperationIndex(stops);
      if (end.isEmpty()) {
        continue;
      }
      Optional<PidsStationKey> destination = stationKeyAt(entry, stops, stations, end.getAsInt());
      if (destination.isEmpty()) {
        continue;
      }
      String startNode = entry.definition().waypoints().get(0).value();
      for (int i = 0; i < end.getAsInt(); i++) {
        RouteStop stop = stops.get(i);
        if (!stop.stops() || !RouteTerminals.isStationStop(stop)) {
          continue;
        }
        if (!stationKeyAt(entry, stops, stations, i).filter(station::equals).isPresent()) {
          continue;
        }
        Set<String> platforms = platformsOf(stop, entry.definition().waypoints().get(i).value());
        Set<String> shown = platforms;
        if (!screen.isEmpty() && !platforms.isEmpty()) {
          shown = new TreeSet<>(platforms);
          shown.retainAll(screen);
          if (shown.isEmpty()) {
            continue;
          }
        }
        Line line = entry.record().line();
        RoutePatternType pattern = entry.record().route().patternType();
        String key =
            line.id()
                + "|"
                + pattern.name()
                + "|"
                + destination.get()
                + "|"
                + String.join("/", sortedPlatforms(shown));
        Set<String> finalShown = shown;
        byKey
            .computeIfAbsent(
                key,
                ignored ->
                    new Builder(
                        key,
                        entry.record().operator().code(),
                        line.id(),
                        line.code(),
                        pattern,
                        destination.get(),
                        finalShown))
            .routes
            .add(
                new CallRoute(
                    entry.routeId(), entry.record().route().code(), i, origin.get(), startNode));
        // 同一条交路在本站只停一次；环线再经过本站算另一趟，不在这里重复列
        break;
      }
    }
    List<CallDirection> out = new ArrayList<>(byKey.size());
    for (Builder builder : byKey.values()) {
      out.add(builder.build());
    }
    out.sort(
        Comparator.comparing((CallDirection d) -> d.lineCode(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(d -> d.pattern().ordinal())
            .thenComparing(d -> d.destination().toString())
            .thenComparing(CallDirection::platformLabel));
    return List.copyOf(out);
  }

  /**
   * 一条线路的载客交路（见 {@link #originOf}）停车的车站（不含终点），按交路顺序去重。
   *
   * @param entries 交路缓存的全部交路
   * @param directory 车站目录快照
   * @param lineId 线路
   */
  public static List<PidsStationKey> stationsServed(
      Collection<RouteDefinitionCache.RouteEntry> entries,
      StationDirectory.Snapshot directory,
      UUID lineId) {
    if (entries == null || lineId == null) {
      return List.of();
    }
    Set<PidsStationKey> out = new java.util.LinkedHashSet<>();
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      if (entry == null || !lineId.equals(entry.record().line().id())) {
        continue;
      }
      List<RouteStop> stops = entry.stops();
      if (stops.size() != entry.definition().waypoints().size()
          || stops.size() < 2
          || originOf(entry.record().route().operationType(), stops.get(0)).isEmpty()) {
        continue;
      }
      OptionalInt end = RouteTerminals.endOfOperationIndex(stops);
      if (end.isEmpty()) {
        continue;
      }
      List<StationDirectory.StopStation> stations =
          directory == null
              ? List.of()
              : directory.stopStations(
                  entry.routeId(), stops, Optional.of(entry.record().operator()));
      for (int i = 0; i < end.getAsInt(); i++) {
        RouteStop stop = stops.get(i);
        if (stop.stops() && RouteTerminals.isStationStop(stop)) {
          stationKeyAt(entry, stops, stations, i).ifPresent(out::add);
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * 交路本来的车源；不收的交路为空。
   *
   * <p>回库交路只区间生成（首站的车多半还要接着跑运营班，车库在它的终点那头）；出库交路首站不是车库时出不了车，不收。
   *
   * @param operationType 交路运营类型
   * @param firstStop 首站
   */
  static Optional<Origin> originOf(RouteOperationType operationType, RouteStop firstStop) {
    if (operationType == null) {
      return Optional.empty();
    }
    if (operationType == RouteOperationType.RETURN) {
      return Optional.of(Origin.ENTRY);
    }
    boolean depot =
        firstStop != null
            && SpawnDirectiveParser.findDirectiveTarget(firstStop, "CRET").isPresent();
    if (depot) {
      return Optional.of(Origin.DEPOT);
    }
    return operationType == RouteOperationType.OPERATION
        ? Optional.of(Origin.STANDBY)
        : Optional.empty();
  }

  /** 停靠点所在车站：站台节点、DYNAMIC 车站规范直接读，只绑定 stationId 的经车站目录查。 */
  static Optional<PidsStationKey> stationKeyAt(
      RouteDefinitionCache.RouteEntry entry,
      List<RouteStop> stops,
      List<StationDirectory.StopStation> stations,
      int index) {
    Optional<RouteTerminals.StationRef> ref = RouteTerminals.stationIdentityOf(stops.get(index));
    if (ref.isPresent()) {
      return key(ref.get().operatorCode(), ref.get().stationCode());
    }
    if (index < stations.size()) {
      Optional<String> code = stations.get(index).stationCode();
      if (code.isPresent()) {
        return key(entry.record().operator().code(), code.get());
      }
    }
    return Optional.empty();
  }

  private static Optional<PidsStationKey> key(String operatorCode, String stationCode) {
    if (operatorCode == null
        || operatorCode.isBlank()
        || stationCode == null
        || stationCode.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new PidsStationKey(operatorCode, stationCode));
  }

  /** 停靠点的站台：DYNAMIC 停靠取股道范围（不限股道时为空），否则取节点的股道号。 */
  static Set<String> platformsOf(RouteStop stop, String nodeId) {
    Optional<DynamicStopMatcher.DynamicSpec> dynamic = DynamicStopMatcher.parseDynamicSpec(stop);
    if (dynamic.isPresent()) {
      DynamicStopMatcher.DynamicSpec spec = dynamic.get();
      if (spec.unbounded() || spec.fromTrack() <= 0 || spec.toTrack() < spec.fromTrack()) {
        return Set.of();
      }
      Set<String> out = new TreeSet<>();
      for (int track = spec.fromTrack(); track <= spec.toTrack(); track++) {
        out.add(String.valueOf(track));
      }
      return out;
    }
    String platform = RouteTerminals.platformOf(nodeId);
    return "-".equals(platform) ? Set.of() : Set.of(platform);
  }

  /** 站台号统一写法：数字去掉前导零，其余转大写。 */
  static Set<String> normalizePlatforms(Set<String> platforms) {
    if (platforms == null || platforms.isEmpty()) {
      return Set.of();
    }
    Set<String> out = new TreeSet<>();
    for (String platform : platforms) {
      if (platform == null || platform.isBlank()) {
        continue;
      }
      String trimmed = platform.trim();
      try {
        out.add(String.valueOf(Integer.parseInt(trimmed)));
      } catch (NumberFormatException ex) {
        out.add(trimmed.toUpperCase(Locale.ROOT));
      }
    }
    return out;
  }

  private static List<String> sortedPlatforms(Set<String> platforms) {
    List<String> out = new ArrayList<>(platforms);
    out.sort(
        Comparator.comparingInt(CallCatalog::numericOrMax)
            .thenComparing(Comparator.naturalOrder()));
    return out;
  }

  private static int numericOrMax(String platform) {
    try {
      return Integer.parseInt(platform);
    } catch (NumberFormatException ex) {
      return Integer.MAX_VALUE;
    }
  }

  private static final class Builder {
    private final String key;
    private final String operatorCode;
    private final UUID lineId;
    private final String lineCode;
    private final RoutePatternType pattern;
    private final PidsStationKey destination;
    private final Set<String> platforms;
    private final List<CallRoute> routes = new ArrayList<>();

    private Builder(
        String key,
        String operatorCode,
        UUID lineId,
        String lineCode,
        RoutePatternType pattern,
        PidsStationKey destination,
        Set<String> platforms) {
      this.key = key;
      this.operatorCode = operatorCode;
      this.lineId = lineId;
      this.lineCode = lineCode;
      this.pattern = pattern;
      this.destination = destination;
      this.platforms = platforms;
    }

    private CallDirection build() {
      return new CallDirection(
          key, operatorCode, lineId, lineCode, pattern, destination, platforms, routes);
    }
  }
}
