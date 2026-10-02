package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;

/**
 * 把 build 输入的 route 按交路组分组、按（起点站台组, 终点站台组）分方向。
 *
 * <p>分类落在 route 上，不在组上：OPERATION 与中途有 STOP 的 CREATE / RETURN 是<b>带客 route</b>，它是班次，上它所属组的子网格； 中途没有
 * STOP 的 CREATE / RETURN 是<b>纯走行</b>，只做交路的两头。CREATE / RETURN / CRET / DSTY 只决定车的生灭，不决定它是不是班次——DS 那种
 * {@code CREATE → RETURN} 完整生灭的一对和 WS 用 {@code OPERATION + CRET} 写的出库班是同一回事。
 *
 * <p>组名取 route metadata 的 {@code spawn_group}；没配的一律进 {@link #DEFAULT_GROUP}——一条线没分组就是一个组，
 * 往返对才能锚在一起。 按起点站推导是错的：正向从 A 出发、反向从 B 出发会被拆成两组，谁也锚不到谁。纯数据，确定性：组按名字、方向按键、候选按 code 排序。
 */
public final class ServiceGroupClassifier {

  /** 没配 {@code spawn_group} 的 route 所属的组。 */
  public static final String DEFAULT_GROUP = "default";

  private ServiceGroupClassifier() {}

  /**
   * 一个方向：同组内起点站台组与终点站台组相同的带客 route 共用一张子网格，按 weight 切份额。
   *
   * @param originGroup 起点站台组（解析不出组时用节点 id）
   * @param terminalGroup 终点站台组
   * @param candidates 该方向的候选，按 code 排序；weight 是方向内的目标比例
   * @param routeIds 与 candidates 对齐
   */
  public record Direction(
      String originGroup,
      String terminalGroup,
      List<WeightedTripAllocator.Candidate> candidates,
      List<UUID> routeIds) {

    public Direction {
      originGroup = originGroup == null ? "" : originGroup;
      terminalGroup = terminalGroup == null ? "" : terminalGroup;
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
      routeIds = routeIds == null ? List.of() : List.copyOf(routeIds);
    }

    /** 方向键：相位表与报告用。 */
    public String key() {
      return originGroup + "→" + terminalGroup;
    }

    /** 反方向的键：往返对锚定用。 */
    public String reverseKey() {
      return terminalGroup + "→" + originGroup;
    }
  }

  /**
   * 一个交路组。
   *
   * @param name 组名
   * @param directions 带客 route 的方向，按键排序
   * @param pureLegs 纯走行 route（只做交路两头）
   */
  public record Group(String name, List<Direction> directions, List<UUID> pureLegs) {
    public Group {
      name = name == null ? "" : name;
      directions = directions == null ? List.of() : List.copyOf(directions);
      pureLegs = pureLegs == null ? List.of() : List.copyOf(pureLegs);
    }
  }

  /** 分类结果；组按名字排序。 */
  public record Classification(List<Group> groups, List<String> warnings) {
    public Classification {
      groups = groups == null ? List.of() : List.copyOf(groups);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 某条 route 所在的组名。 */
    public String groupOf(UUID routeId) {
      for (Group group : groups) {
        for (Direction direction : group.directions()) {
          if (direction.routeIds().contains(routeId)) {
            return group.name();
          }
        }
        if (group.pureLegs().contains(routeId)) {
          return group.name();
        }
      }
      return "";
    }
  }

  /** 分类。只看 route 定义与停靠配置，不需要时分。 */
  public static Classification classify(List<TimetableBuilder.RouteInput> routes) {
    Objects.requireNonNull(routes, "routes");
    Map<String, Map<String, List<TimetableBuilder.RouteInput>>> byGroupAndDirection =
        new TreeMap<>();
    Map<String, List<UUID>> pureLegsByGroup = new TreeMap<>();
    List<String> warnings = new ArrayList<>();
    for (TimetableBuilder.RouteInput route : routes) {
      if (route == null || route.definition().waypoints().size() < 2) {
        continue;
      }
      String group = groupNameOf(route);
      if (!carriesPassengers(route)) {
        pureLegsByGroup.computeIfAbsent(group, key -> new ArrayList<>()).add(route.routeId());
        continue;
      }
      String key = originGroupOf(route) + "→" + terminalGroupOf(route);
      byGroupAndDirection
          .computeIfAbsent(group, ignored -> new TreeMap<>())
          .computeIfAbsent(key, ignored -> new ArrayList<>())
          .add(route);
    }
    List<Group> groups = new ArrayList<>();
    for (String name :
        new TreeMap<>(merge(byGroupAndDirection.keySet(), pureLegsByGroup.keySet())).keySet()) {
      List<Direction> directions = new ArrayList<>();
      Map<String, List<TimetableBuilder.RouteInput>> perDirection =
          byGroupAndDirection.getOrDefault(name, Map.of());
      for (List<TimetableBuilder.RouteInput> members : perDirection.values()) {
        List<TimetableBuilder.RouteInput> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(TimetableBuilder.RouteInput::routeCode));
        List<WeightedTripAllocator.Candidate> candidates = new ArrayList<>(sorted.size());
        List<UUID> ids = new ArrayList<>(sorted.size());
        for (TimetableBuilder.RouteInput member : sorted) {
          candidates.add(new WeightedTripAllocator.Candidate(member.routeCode(), member.weight()));
          ids.add(member.routeId());
        }
        directions.add(
            new Direction(
                originGroupOf(sorted.get(0)), terminalGroupOf(sorted.get(0)), candidates, ids));
      }
      directions.sort(Comparator.comparing(Direction::key));
      List<UUID> legs = pureLegsByGroup.getOrDefault(name, List.of());
      if (directions.isEmpty() && !legs.isEmpty()) {
        warnings.add("交路组 " + name + " 只有纯走行 route，没有班次，间隔配置不起作用");
      }
      groups.add(new Group(name, directions, legs));
    }
    return new Classification(groups, warnings);
  }

  /**
   * 带客，是班次：OPERATION 永远是；CREATE / RETURN 要中途至少一个 STOP（首末站不算）。
   *
   * <p>首末站不算是因为出库 {@code DEP→A}、回库 {@code A→DEP} 两头也都是 STOP，看两头分不出走行段和班次；而 OPERATION 不看中途是因为 {@code
   * A→B} 两站的小交路本来就没有中途，它当然带客。
   */
  public static boolean carriesPassengers(TimetableBuilder.RouteInput route) {
    if (route.operationType() == RouteOperationType.OPERATION) {
      return true;
    }
    List<RouteStop> stops = route.stops();
    for (int i = 1; i + 1 < stops.size(); i++) {
      RouteStop stop = stops.get(i);
      if (stop != null && stop.passType() == RouteStopPassType.STOP) {
        return true;
      }
    }
    return false;
  }

  /** 这一班实体化一辆车：CREATE 类型，或首站带 CRET。 */
  public static boolean spawnsVehicle(TimetableBuilder.RouteInput route) {
    return route.operationType() == RouteOperationType.CREATE || startsAtDepot(route.stops());
  }

  /** 这一班跑完车就销毁：RETURN 类型，或以 DSTY 收尾。 */
  public static boolean destroysVehicle(TimetableBuilder.RouteInput route) {
    return route.operationType() == RouteOperationType.RETURN || endsAtDepot(route.stops());
  }

  /** 首站带 CRET 指令：与发车侧 {@code startsWithCret} 判法一致。 */
  static boolean startsAtDepot(List<RouteStop> stops) {
    return stops != null
        && !stops.isEmpty()
        && SpawnDirectiveParser.findDirectiveTarget(stops.get(0), "CRET").isPresent();
  }

  /** 以销毁收尾：与运行时 {@link RouteDefinition#resolveMode} 同一条规则。 */
  static boolean endsAtDepot(List<RouteStop> stops) {
    return RouteDefinition.resolveMode(stops) == RouteLifecycleMode.DESTROY_AFTER_TERM;
  }

  /** 组名：metadata 的 spawn_group；没配就是 {@link #DEFAULT_GROUP}。 */
  static String groupNameOf(TimetableBuilder.RouteInput route) {
    return route
        .spawnGroup()
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .orElse(DEFAULT_GROUP);
  }

  /**
   * 起点站台组：第一个<b>落在车站上的停靠点</b>（STOP / TERMINATE；DYNAMIC 取它的目标站）；都找不到才退回首路径点。
   *
   * <p>方向是乘客看到的"从哪到哪"，<b>车库与走行路径点不是站</b>。按首末路径点取会把同一条线的往返拆成互不相识的两个方向：
   * 出库端从车库出发、入库端到车库结束，两者的键都不是站，于是往返对锚不到一起，第二层的组间错开也找不到共用起点。 MT-1N_Short 与 MT-1N_ShortR
   * 就是这么被拆成两个方向、把进站流量算成两倍的。
   */
  static String originGroupOf(TimetableBuilder.RouteInput route) {
    return passengerEndpointGroup(route, true);
  }

  /** 终点站台组：最后一个落在车站上的停靠点；规则同 {@link #originGroupOf}。 */
  static String terminalGroupOf(TimetableBuilder.RouteInput route) {
    return passengerEndpointGroup(route, false);
  }

  /**
   * 乘客端点的站台组。
   *
   * @param route 待分类的 route
   * @param fromStart true 取第一个、false 取最后一个
   */
  private static String passengerEndpointGroup(
      TimetableBuilder.RouteInput route, boolean fromStart) {
    List<RouteStop> stops = route.stops();
    List<NodeId> waypoints = route.definition().waypoints();
    if (stops != null && !stops.isEmpty()) {
      int size = stops.size();
      for (int i = 0; i < size; i++) {
        int index = fromStart ? i : size - 1 - i;
        RouteStop stop = stops.get(index);
        if (stop == null || stop.passType() == RouteStopPassType.PASS) {
          continue;
        }
        String group =
            stationGroupOfStop(stop, index < waypoints.size() ? waypoints.get(index).value() : "");
        if (!group.isBlank()) {
          return group;
        }
      }
    }
    // 一个像样的停靠点都没有（纯走行 route）：退回首末路径点，与旧行为一致。
    NodeId fallback = fromStart ? waypoints.get(0) : waypoints.get(waypoints.size() - 1);
    return stationGroupOf(fallback.value());
  }

  /**
   * 一个停靠点落在哪个<b>车站</b>站台组上；落在车库、走行路径点或解析不出时返回空串。
   *
   * <p>DYNAMIC 停靠的节点 id 是占位，真正的站在 {@code DYNAMIC:} 规范里，按同一形状（{@code 运营商:S:站名}）拼出来，
   * 才能和具体股道的节点落到同一个键上。
   */
  private static String stationGroupOfStop(RouteStop stop, String waypointNodeId) {
    if (DynamicStopMatcher.isDynamicStop(stop)) {
      String group =
          DynamicStopMatcher.parseDynamicSpec(stop)
              .map(spec -> spec.operatorCode() + ":" + spec.nodeType() + ":" + spec.nodeName())
              .orElse("");
      if (isStationGroup(group)) {
        return group;
      }
    }
    String group = TimetableConflictChecker.groupOf(waypointNodeId);
    return isStationGroup(group) ? group : "";
  }

  /** 车站站台组：{@code 运营商:S:站名}。车库（{@code :D:}）不是站——车在库里不算"从这儿开往那儿"。 */
  private static boolean isStationGroup(String group) {
    return group != null && group.split(":").length == 3 && group.split(":")[1].equals("S");
  }

  /** 站台组；解析不出（车库、DYNAMIC 占位）时用节点 id 本身，保证同一节点永远同一键。 */
  static String stationGroupOf(String nodeId) {
    String group = TimetableConflictChecker.groupOf(nodeId);
    return group.isBlank() ? nodeId : group;
  }

  private static Map<String, Boolean> merge(Iterable<String> a, Iterable<String> b) {
    Map<String, Boolean> out = new LinkedHashMap<>();
    for (String name : a) {
      out.put(name, Boolean.TRUE);
    }
    for (String name : b) {
      out.put(name, Boolean.TRUE);
    }
    return out;
  }
}
