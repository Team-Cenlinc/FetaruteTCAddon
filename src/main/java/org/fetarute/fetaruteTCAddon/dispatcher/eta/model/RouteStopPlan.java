package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;

/**
 * 一趟车眼中的交路停靠：声明节点、实际节点与停靠配置三者按 {@code waypoints()} 下标对齐。
 *
 * <p>ETA 要回答的几个问题都以下标为锚：下一个停车点在哪、途中要停几次各停多久、目标是不是尚未选台的 DYNAMIC。 按节点字符串找位置会在两处出错——DYNAMIC
 * 选台后实际股道不等于声明的占位股道；同一节点在交路里出现两次时只能找到第一次。
 *
 * <ul>
 *   <li><b>声明节点</b>：{@code RouteDefinition.waypoints()}，DYNAMIC 处是占位股道 {@code OP:S:CODE:fromTrack}。
 *   <li><b>实际节点</b>：运行时的有效节点，DYNAMIC 选台后换成选中的股道；未发车的票据与声明节点相同。
 *   <li><b>停靠配置</b>：{@code RouteDefinitionCache#listStops}，与声明节点一一对应；缺失时视为“不知道哪里停车”， 既不累加停站也不拆段。
 * </ul>
 */
public final class RouteStopPlan {

  private final List<NodeId> declared;
  private final List<NodeId> effective;
  private final List<RouteStop> stops;

  private RouteStopPlan(List<NodeId> declared, List<NodeId> effective, List<RouteStop> stops) {
    this.declared = List.copyOf(declared);
    this.effective =
        List.copyOf(
            effective == null || effective.size() != declared.size() ? declared : effective);
    this.stops =
        stops == null || stops.size() != declared.size()
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(stops));
  }

  /**
   * @param declared 交路声明节点
   * @param effective 运行时实际节点；为空或长度不符时按声明节点
   * @param stops 与声明节点对齐的停靠配置；长度不符时视为缺失
   */
  public static RouteStopPlan of(
      List<NodeId> declared, List<NodeId> effective, List<RouteStop> stops) {
    Objects.requireNonNull(declared, "declared");
    return new RouteStopPlan(declared, effective, stops);
  }

  /** 节点数。 */
  public int size() {
    return declared.size();
  }

  /** 实际节点序列（DYNAMIC 已选台处为选中的股道）。 */
  public List<NodeId> effectiveNodes() {
    return effective;
  }

  /** 下标处的实际节点。 */
  public NodeId node(int index) {
    return effective.get(index);
  }

  /** 下标处的停靠配置。 */
  public Optional<RouteStop> stop(int index) {
    if (index < 0 || index >= stops.size()) {
      return Optional.empty();
    }
    return Optional.ofNullable(stops.get(index));
  }

  /** 列车是否在该下标停车；没有停靠配置时一律按“不知道”返回 false。 */
  public boolean stopsAt(int index) {
    return stop(index).map(RouteStop::stops).orElse(false);
  }

  /** 该下标的计划停站秒数（PASS 与无配置为 0，停车没配 dwell 取运行时缺省）。 */
  public int plannedDwellSec(int index) {
    return stop(index).map(RouteStop::plannedDwellSeconds).orElse(0);
  }

  /** {@code after} 之后第一个停车点的下标。 */
  public OptionalInt nextStoppingIndex(int after) {
    for (int i = Math.max(0, after + 1); i < size(); i++) {
      if (stopsAt(i)) {
        return OptionalInt.of(i);
      }
    }
    return OptionalInt.empty();
  }

  /** {@code from}、{@code to} 之间（均不含）的停车点下标，升序。 */
  public List<Integer> stoppingIndicesBetween(int from, int to) {
    List<Integer> out = new ArrayList<>();
    for (int i = Math.max(0, from + 1); i < Math.min(to, size()); i++) {
      if (stopsAt(i)) {
        out.add(i);
      }
    }
    return out;
  }

  /** {@code from}、{@code to} 之间（均不含）各停车点的计划停站之和。 */
  public int dwellBetween(int from, int to) {
    int total = 0;
    for (int index : stoppingIndicesBetween(from, to)) {
      total += plannedDwellSec(index);
    }
    return total;
  }

  /**
   * {@code from} 起第一个声明节点或实际节点等于 {@code node} 的下标。
   *
   * <p>两者都认：调用方拿 DYNAMIC 占位节点来问时，选台后应当落到实际股道所在的那一站，而不是找不到。
   */
  public OptionalInt indexOfNode(NodeId node, int from) {
    if (node == null) {
      return OptionalInt.empty();
    }
    for (int i = Math.max(0, from); i < size(); i++) {
      if (node.equals(effective.get(i)) || node.equals(declared.get(i))) {
        return OptionalInt.of(i);
      }
    }
    return OptionalInt.empty();
  }

  /**
   * 该下标是否是尚未选台的 DYNAMIC 停靠：停靠配置是 DYNAMIC，且实际节点仍是声明的占位股道。
   *
   * <p>选中的恰好是占位股道时也会返回 true；此时按车站级估算与按该股道估算只差站内几格，不值得为此多接一路状态。
   */
  public boolean unresolvedDynamic(int index) {
    return stop(index).map(DynamicStopMatcher::isDynamicStop).orElse(false)
        && effective.get(index).equals(declared.get(index));
  }

  /**
   * 途中停车点在展开路径里的位置，供行程时间按“停车—起步”拆段。
   *
   * <p>按下标顺序在路径里向后找各停车点的实际节点；找不到的停车点（路径没经过它）不拆段。首尾位置不算——起点是列车当前位置， 终点就是目标本身。
   *
   * @param pathNodes 从列车当前位置到目标的展开路径
   * @param from 列车当前下标
   * @param to 目标下标
   * @return 停车点在 {@code pathNodes} 中的位置，严格递增
   */
  public List<Integer> stopPositions(List<NodeId> pathNodes, int from, int to) {
    List<Integer> positions = new ArrayList<>();
    if (pathNodes == null || pathNodes.size() < 3) {
      return positions;
    }
    int cursor = 1;
    for (int index : stoppingIndicesBetween(from, to)) {
      NodeId node = effective.get(index);
      for (int p = cursor; p < pathNodes.size() - 1; p++) {
        if (node.equals(pathNodes.get(p))) {
          positions.add(p);
          cursor = p + 1;
          break;
        }
      }
    }
    return positions;
  }
}
