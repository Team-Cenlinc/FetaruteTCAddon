package org.fetarute.fetaruteTCAddon.signaller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;

/**
 * 切临时小区的单元与相邻关系（见 {@link ZoneSplitter}）。本类不依赖服务器对象。
 *
 * <ul>
 *   <li>每个成员车站、车库按自己推出一片站场（{@link YardArea}，与控制区同一认法）；
 *   <li>两个成员的站场有共同的节点，或一个的出口直接接到另一个的站场里（中间不经区间点，只隔道岔与咽喉，例如车站和它的车库）， 合成一个单元，免得分界落在道岔上；
 *   <li>管理员划好、嵌在大区里的小区整体算一个单元；
 *   <li>一个单元的出口通往另一个单元的成员（中间只隔区间），两个单元相邻：分界总落在区间上，与嵌套小区的出入口一个认法。
 * </ul>
 *
 * @param units 单元，按各单元第一个成员在控制区里的先后排
 * @param links 相邻的两个单元的编号
 */
record ZoneUnits(List<Unit> units, List<int[]> links) {

  ZoneUnits {
    units = List.copyOf(units);
    links = List.copyOf(links);
  }

  /**
   * 一个单元。
   *
   * @param members 成员（按控制区里的先后）
   * @param nodes 站场节点
   * @param x 站场节点的平均位置
   * @param z 站场节点的平均位置
   * @param switches 道岔数
   * @param tracks 站台股道与库线数
   */
  record Unit(
      List<YardArea.Member> members,
      Set<NodeId> nodes,
      double x,
      double z,
      int switches,
      int tracks) {
    Unit {
      members = List.copyOf(members);
      nodes = Set.copyOf(nodes);
    }
  }

  /**
   * 推出单元。
   *
   * @param graph 控制区所在世界的调度图
   * @param operator 运营商代码
   * @param members 控制区成员
   * @param fixed 嵌在里面、管理员划好的小区的成员（各整体算一个单元）
   * @return 一个成员都不在图上时为空
   */
  static Optional<ZoneUnits> of(
      RailGraph graph,
      String operator,
      List<YardArea.Member> members,
      List<List<YardArea.Member>> fixed) {
    List<YardArea.Member> present = new ArrayList<>();
    List<YardArea> areas = new ArrayList<>();
    for (YardArea.Member member : members) {
      Optional<YardArea> area = YardArea.of(graph, operator, List.of(member));
      if (area.isPresent()) {
        present.add(member);
        areas.add(area.get());
      }
    }
    if (present.isEmpty()) {
      return Optional.empty();
    }
    int[] parent = new int[present.size()];
    for (int i = 0; i < parent.length; i++) {
      parent[i] = i;
    }
    for (List<YardArea.Member> group : fixed) {
      int first = -1;
      for (YardArea.Member member : group) {
        int at = present.indexOf(member);
        if (at < 0) {
          continue;
        }
        if (first < 0) {
          first = at;
        } else {
          union(parent, first, at);
        }
      }
    }
    for (int i = 0; i < present.size(); i++) {
      for (int j = i + 1; j < present.size(); j++) {
        if (joined(areas.get(i), areas.get(j))) {
          union(parent, i, j);
        }
      }
    }
    Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
    for (int i = 0; i < present.size(); i++) {
      groups.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(i);
    }
    List<Unit> units = new ArrayList<>();
    Map<Integer, Integer> unitOfMember = new HashMap<>();
    for (List<Integer> group : groups.values()) {
      int unit = units.size();
      List<YardArea.Member> unitMembers = new ArrayList<>();
      Set<NodeId> nodes = new LinkedHashSet<>();
      int tracks = 0;
      for (int member : group) {
        unitMembers.add(present.get(member));
        nodes.addAll(areas.get(member).nodes());
        tracks += areas.get(member).tracks().size() + areas.get(member).depotTracks().size();
        unitOfMember.put(member, unit);
      }
      units.add(unit(graph, unitMembers, nodes, tracks));
    }
    Map<String, Integer> memberByCode = new HashMap<>();
    for (int i = 0; i < present.size(); i++) {
      memberByCode.putIfAbsent(present.get(i).code(), i);
    }
    Set<Long> seen = new LinkedHashSet<>();
    List<int[]> links = new ArrayList<>();
    for (int i = 0; i < present.size(); i++) {
      for (YardArea.Exit exit : areas.get(i).exits()) {
        Integer other = exit.towards().map(memberByCode::get).orElse(null);
        if (other == null) {
          continue;
        }
        int a = unitOfMember.get(i);
        int b = unitOfMember.get(other);
        if (a == b) {
          continue;
        }
        int low = Math.min(a, b);
        int high = Math.max(a, b);
        if (seen.add(((long) low << 32) | high)) {
          links.add(new int[] {low, high});
        }
      }
    }
    return Optional.of(new ZoneUnits(units, links));
  }

  /** 两个成员的站场只隔道岔与咽喉就相通：有共同的节点，或一个的出口直接接到另一个的站场里。 */
  private static boolean joined(YardArea a, YardArea b) {
    for (NodeId node : a.nodes()) {
      if (b.nodes().contains(node)) {
        return true;
      }
    }
    for (YardArea.Exit exit : a.exits()) {
      if (b.nodes().contains(exit.outer())) {
        return true;
      }
    }
    for (YardArea.Exit exit : b.exits()) {
      if (a.nodes().contains(exit.outer())) {
        return true;
      }
    }
    return false;
  }

  private static Unit unit(
      RailGraph graph, List<YardArea.Member> members, Set<NodeId> nodes, int tracks) {
    double x = 0.0;
    double z = 0.0;
    int placed = 0;
    int switches = 0;
    for (NodeId id : nodes) {
      Optional<RailNode> node = graph.findNode(id);
      if (node.isEmpty()) {
        continue;
      }
      if (node.get().type() == NodeType.SWITCHER) {
        switches++;
      }
      Vector position = node.get().worldPosition();
      if (position != null) {
        x += position.getX();
        z += position.getZ();
        placed++;
      }
    }
    if (placed > 0) {
      x /= placed;
      z /= placed;
    }
    return new Unit(members, nodes, x, z, switches, tracks);
  }

  private static int find(int[] parent, int at) {
    while (parent[at] != at) {
      parent[at] = parent[parent[at]];
      at = parent[at];
    }
    return at;
  }

  private static void union(int[] parent, int a, int b) {
    int rootA = find(parent, a);
    int rootB = find(parent, b);
    if (rootA != rootB) {
      parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
    }
  }
}
