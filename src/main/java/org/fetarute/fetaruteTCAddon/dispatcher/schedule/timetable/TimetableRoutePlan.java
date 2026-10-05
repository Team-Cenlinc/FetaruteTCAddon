package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;

/**
 * 一条 route 在时刻表里的计划：站间时分档案 + 目标服务比例。
 *
 * <p>时刻表以<b>线路</b>为单位构建，一条线路下可能有多条 route（上行/下行/快慢车），它们的站序和时分各不相同。 所以站间时分挂在 route 上而不是挂在整份时刻表上——把多条
 * route 的时分揉成一套 profile 会得到一张 谁都对不上的表。
 *
 * <p>{@code kind} 区分三类 route：{@code OPERATION} 进发车表并按 weight 分配份额；{@code CREATE}（车库 → 首站）与 {@code
 * RETURN}（末站 → 车库）不进发车表，只提供车辆交路两端的走行时分与端点。它们同样从路网算出来，不用固定的估计值——回库段用常数顶上， 就会让"每辆车都回库"这条不变量在时间上是假的。
 *
 * <p>{@code weight} 在这里是<b>目标服务比例</b>，不是抽签概率；具体如何转成班次由 {@link WeightedTripAllocator} 决定。
 * CREATE/RETURN 的 weight 恒为 0。
 *
 * @param routeId Route UUID
 * @param routeCode Route code，同时用作确定性排序键
 * @param kind route 类型
 * @param weight 目标服务比例权重
 * @param stops 站间时分档案，相对本 route 首站发车的秒偏移
 * @param originNodeId 起点节点
 * @param terminalNodeId 终点节点
 * @param depotNodeId 出库点；为空时由线路 depot pool 决定
 * @param notes 构建期说明（例如为什么这条 route 被降级）
 * @param external <b>不受本表管辖的走行线路</b>：别的线的，或别的 operator 的（直通运转里显式指定的外方出库/回库）。 它进冲突足迹、进交路，但它所在线路自己的
 *     headway 票照常发
 * @param consist 这份时分是哪个车型的：区分车型编表时，同一条 route 每个允许的车型各有一份（见 {@link ConsistFleet}）； 不区分车型的 route
 *     与基础时分（允许车型里最慢的那份）为空
 */
public record TimetableRoutePlan(
    UUID routeId,
    String routeCode,
    RouteOperationType kind,
    int weight,
    List<TimetableStop> stops,
    String originNodeId,
    String terminalNodeId,
    Optional<String> depotNodeId,
    Optional<String> notes,
    boolean external,
    Optional<ConsistVariant> consist) {

  /**
   * 车型变体：某条 route 按某个车型跑的时分。
   *
   * <p>编表内部变体用自己的 ID（{@link ConsistFleet#variantId}）登记，各环节按它查时分与足迹；落库前折回基础 route 的 ID，
   * 车次的车型由它所在交路的车型决定。
   *
   * @param key 车型键
   * @param baseRouteId 基础 route
   * @param lengthBlocks 车长（格）
   * @param tailBlocks 车尾出清多算的长度（格），见 {@link ConsistFleet#tailBlocks}
   */
  public record ConsistVariant(
      String key, UUID baseRouteId, double lengthBlocks, double tailBlocks) {
    public ConsistVariant {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(baseRouteId, "baseRouteId");
      lengthBlocks = Double.isFinite(lengthBlocks) && lengthBlocks > 0.0 ? lengthBlocks : 0.0;
      tailBlocks = Double.isFinite(tailBlocks) && tailBlocks > 0.0 ? tailBlocks : 0.0;
    }
  }

  /** 不分车型的计划。 */
  public TimetableRoutePlan(
      UUID routeId,
      String routeCode,
      RouteOperationType kind,
      int weight,
      List<TimetableStop> stops,
      String originNodeId,
      String terminalNodeId,
      Optional<String> depotNodeId,
      Optional<String> notes,
      boolean external) {
    this(
        routeId,
        routeCode,
        kind,
        weight,
        stops,
        originNodeId,
        terminalNodeId,
        depotNodeId,
        notes,
        external,
        Optional.empty());
  }

  /** 本 operator 自己的线路。 */
  public TimetableRoutePlan(
      UUID routeId,
      String routeCode,
      RouteOperationType kind,
      int weight,
      List<TimetableStop> stops,
      String originNodeId,
      String terminalNodeId,
      Optional<String> depotNodeId,
      Optional<String> notes) {
    this(
        routeId,
        routeCode,
        kind,
        weight,
        stops,
        originNodeId,
        terminalNodeId,
        depotNodeId,
        notes,
        false);
  }

  public TimetableRoutePlan {
    Objects.requireNonNull(routeId, "routeId");
    routeCode = routeCode == null ? "" : routeCode.trim();
    if (routeCode.isBlank()) {
      throw new IllegalArgumentException("routeCode 不能为空");
    }
    kind = kind == null ? RouteOperationType.OPERATION : kind;
    if (weight < 0) {
      throw new IllegalArgumentException("weight 不能为负");
    }
    if (kind != RouteOperationType.OPERATION) {
      weight = 0;
    }
    stops =
        stops == null
            ? List.of()
            : stops.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(TimetableStop::stopSequence))
                .toList();
    originNodeId = originNodeId == null ? "" : originNodeId.trim();
    terminalNodeId = terminalNodeId == null ? "" : terminalNodeId.trim();
    depotNodeId =
        depotNodeId == null
            ? Optional.empty()
            : depotNodeId.map(String::trim).filter(s -> !s.isBlank());
    notes = notes == null ? Optional.empty() : notes.map(String::trim).filter(s -> !s.isBlank());
    consist = consist == null ? Optional.empty() : consist;
  }

  /** 这份计划所属的 route：车型变体返回基础 route，其余就是 {@code routeId}。 */
  public UUID baseRouteId() {
    return consist.map(ConsistVariant::baseRouteId).orElse(routeId);
  }

  /**
   * 同一条 route 按某个车型跑的变体计划：route 的其余信息照抄，时分换成这个车型的。
   *
   * @param variantId 变体 ID
   * @param variant 车型
   * @param variantStops 这个车型的站间时分
   */
  public TimetableRoutePlan asVariant(
      UUID variantId, ConsistVariant variant, List<TimetableStop> variantStops) {
    return new TimetableRoutePlan(
        variantId,
        routeCode,
        kind,
        weight,
        variantStops,
        originNodeId,
        terminalNodeId,
        depotNodeId,
        notes,
        external,
        Optional.of(variant));
  }

  /** 换一个 route ID，其余不变（变体折回基础 route 时用）。 */
  public TimetableRoutePlan withRouteId(UUID nextRouteId) {
    return new TimetableRoutePlan(
        nextRouteId,
        routeCode,
        kind,
        weight,
        stops,
        originNodeId,
        terminalNodeId,
        depotNodeId,
        notes,
        external,
        consist);
  }

  /** 运营 route 的便捷构造。 */
  public TimetableRoutePlan(
      UUID routeId,
      String routeCode,
      int weight,
      List<TimetableStop> stops,
      String originNodeId,
      String terminalNodeId,
      Optional<String> depotNodeId,
      Optional<String> notes) {
    this(
        routeId,
        routeCode,
        RouteOperationType.OPERATION,
        weight,
        stops,
        originNodeId,
        terminalNodeId,
        depotNodeId,
        notes);
  }

  /** 是否是进发车表的运营 route。 */
  public boolean operation() {
    return kind == RouteOperationType.OPERATION;
  }

  /** 全程时分（首站发车 → 末站到达），秒。 */
  public int totalRunSeconds() {
    return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).arrivalOffsetSeconds();
  }

  /** 按 stopSequence 查档案。 */
  public Optional<TimetableStop> stopAt(int stopSequence) {
    for (TimetableStop stop : stops) {
      if (stop.stopSequence() == stopSequence) {
        return Optional.of(stop);
      }
    }
    return Optional.empty();
  }

  /**
   * 车次终点的停靠序号：第一个 TERMINATE 站；没有时为最后一个停车点（其后只剩回库、折返等通过点）。
   *
   * @return 整条都不停车时为空
   */
  public OptionalInt terminatingSequence() {
    int lastStopping = -1;
    for (TimetableStop stop : stops) {
      if (stop.passType() == RouteStopPassType.TERMINATE) {
        return OptionalInt.of(stop.stopSequence());
      }
      if (stop.stops()) {
        lastStopping = stop.stopSequence();
      }
    }
    return lastStopping < 0 ? OptionalInt.empty() : OptionalInt.of(lastStopping);
  }

  /**
   * 某一站之后（不含）、车次终点为止的第一个停车点。
   *
   * @param stopSequence 停靠序号；传 -1 取首个停车点
   * @return 其后到终点都没有停车点时为空
   */
  public OptionalInt firstStopAfter(int stopSequence) {
    OptionalInt terminating = terminatingSequence();
    if (terminating.isEmpty()) {
      return OptionalInt.empty();
    }
    for (TimetableStop stop : stops) {
      if (stop.stopSequence() > terminating.getAsInt()) {
        break;
      }
      if (stop.stops() && stop.stopSequence() > stopSequence) {
        return OptionalInt.of(stop.stopSequence());
      }
    }
    return OptionalInt.empty();
  }
}
