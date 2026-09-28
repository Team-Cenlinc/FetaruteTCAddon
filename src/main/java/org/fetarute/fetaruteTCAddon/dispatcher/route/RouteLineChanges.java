package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;

/**
 * 直通运转（CHANGE）口径的唯一定义：对乘客而言，列车在交路的每一站属于哪条线路。
 *
 * <p>停靠点备注里的 {@code CHANGE:<运营商>:<线路>} 在列车到达该站时执行：运行时只改写列车的运营商、线路标签 （{@code FTA_OPERATOR_CODE} /
 * {@code FTA_LINE_CODE}），不换交路、不改进度下标。它是一条<b>通知</b>：列车从该站起改按另一条线对乘客运营；
 * 交路、交路组、时刻表与调度的管理归属仍是交路自身的线路，不随换线变化。本类只用于面向乘客的显示与查询。
 *
 * <ul>
 *   <li><b>某站所属线路</b>：该站及之前最后一个有效 CHANGE 的目标；没有时为交路自身的线路。换线站本身按新线路算—— 列车以原线路到达、以新线路发车，到站那一刻标签就已改写。
 *   <li><b>换线</b>：目标与此前所属线路不同（运营商、线路代码均不区分大小写）。写了同一条线的 CHANGE 不算换线。
 *   <li><b>有效</b>：运营商与线路代码都不为空。格式无效的 CHANGE 运行时不执行，这里也不算。
 *   <li><b>列车当前线路</b>：以列车的线路标签为准（出车与 CHANGE 写入）；标签不全时为交路自身的线路。 运行时执行 CHANGE
 *       必然写标签，所以标签就是“通知有没有发生”的事实，不按进度去猜。
 * </ul>
 *
 * <p>本类只回答“哪条线”，不负责显示；HUD、公开 API、停靠线路、站牌共用这一把尺子。管理侧（时刻表、回收、优先级） 不要拿它或换线后的标签判断归属。下标与 {@link
 * RouteDefinitionCache#listStops} 对齐（与运行时执行 CHANGE 时的 {@code findStop} 同一份停靠表）。
 */
public final class RouteLineChanges {

  private RouteLineChanges() {}

  /**
   * 线路标识：运营商代码 + 线路代码（均已去掉首尾空白）。
   *
   * @param operatorCode 运营商代码
   * @param lineCode 线路代码
   */
  public record LineRef(String operatorCode, String lineCode) {
    public LineRef {
      operatorCode = Objects.requireNonNull(operatorCode, "operatorCode").trim();
      lineCode = Objects.requireNonNull(lineCode, "lineCode").trim();
    }

    /** 两端都不为空时构造，否则为空。 */
    public static Optional<LineRef> of(String operatorCode, String lineCode) {
      if (operatorCode == null
          || lineCode == null
          || operatorCode.isBlank()
          || lineCode.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new LineRef(operatorCode, lineCode));
    }

    /** 交路自身的线路。 */
    public static Optional<LineRef> of(RouteMetadata metadata) {
      return metadata == null ? Optional.empty() : of(metadata.operator(), metadata.lineId());
    }

    /** 是否同一条线路（代码不区分大小写）。 */
    public boolean sameLine(LineRef other) {
      return other != null
          && operatorCode.equalsIgnoreCase(other.operatorCode)
          && lineCode.equalsIgnoreCase(other.lineCode);
    }
  }

  /**
   * 一条 CHANGE 指令的解析结果。
   *
   * @param valid 运营商与线路代码都不为空
   * @param operatorCode 目标运营商代码；无效时为空串
   * @param lineCode 目标线路代码；无效时为空串
   * @param raw 指令原文（去掉 {@code CHANGE:} 前缀）
   * @param reason 无效原因：{@code format}（缺少线路段）、{@code blank}（有空段）；有效时为 {@code ok}
   */
  public record Directive(
      boolean valid, String operatorCode, String lineCode, String raw, String reason) {

    /** 有效时的目标线路。 */
    public Optional<LineRef> target() {
      return valid ? LineRef.of(operatorCode, lineCode) : Optional.empty();
    }
  }

  /**
   * 一次换线。
   *
   * @param index 换线站在停靠表中的下标
   * @param from 到站时所属线路
   * @param to 发车时所属线路
   */
  public record Change(int index, LineRef from, LineRef to) {}

  /**
   * 解析停靠点上的 CHANGE 指令。
   *
   * @param stop 停靠点
   * @return 解析结果（含无效指令）；没有 CHANGE 指令时为空
   */
  public static Optional<Directive> directive(RouteStop stop) {
    Optional<String> remainderOpt = RouteStopDirectives.target(stop, "CHANGE");
    if (remainderOpt.isEmpty()) {
      return Optional.empty();
    }
    String remainder = remainderOpt.get();
    String[] parts = remainder.split(":", -1);
    if (parts.length < 2) {
      return Optional.of(new Directive(false, "", "", remainder, "format"));
    }
    String operatorCode = parts[0].trim();
    String lineCode = parts[1].trim();
    if (operatorCode.isBlank() || lineCode.isBlank()) {
      return Optional.of(new Directive(false, "", "", remainder, "blank"));
    }
    return Optional.of(new Directive(true, operatorCode, lineCode, remainder, "ok"));
  }

  /** 停靠点上有效 CHANGE 的目标线路（不管与此前线路是否相同）。 */
  public static Optional<LineRef> target(RouteStop stop) {
    return directive(stop).flatMap(Directive::target);
  }

  /**
   * 各站所属线路，与 {@code stops} 下标一一对应。
   *
   * @param stops 停靠表（与交路 waypoints 对齐）
   * @param routeLine 交路自身的线路
   * @return 不可变列表；停靠表为空时为空列表
   */
  public static List<LineRef> linesByIndex(List<RouteStop> stops, LineRef routeLine) {
    Objects.requireNonNull(routeLine, "routeLine");
    if (stops == null || stops.isEmpty()) {
      return List.of();
    }
    List<LineRef> lines = new ArrayList<>(stops.size());
    LineRef current = routeLine;
    for (RouteStop stop : stops) {
      Optional<LineRef> target = target(stop);
      if (target.isPresent()) {
        current = target.get();
      }
      lines.add(current);
    }
    return List.copyOf(lines);
  }

  /**
   * 下标 {@code index} 处（到站之后）所属线路。
   *
   * @param stops 停靠表
   * @param index 停靠表下标；负数表示尚未到达第一站，取交路自身线路；越过末尾按最后一站算
   * @param routeLine 交路自身的线路
   */
  public static LineRef lineAt(List<RouteStop> stops, int index, LineRef routeLine) {
    Objects.requireNonNull(routeLine, "routeLine");
    if (stops == null || stops.isEmpty() || index < 0) {
      return routeLine;
    }
    int last = Math.min(index, stops.size() - 1);
    for (int i = last; i >= 0; i--) {
      Optional<LineRef> target = target(stops.get(i));
      if (target.isPresent()) {
        return target.get();
      }
    }
    return routeLine;
  }

  /**
   * 各站是否换线，与 {@code stops} 下标一一对应。
   *
   * @param stops 停靠表
   * @param routeLine 交路自身的线路
   * @return 换线站为新线路；该站没有有效 CHANGE、或 CHANGE 的目标就是此前所属线路时为空
   */
  public static List<Optional<LineRef>> changesByIndex(List<RouteStop> stops, LineRef routeLine) {
    List<LineRef> lines = linesByIndex(stops, routeLine);
    List<Optional<LineRef>> changes = new ArrayList<>(lines.size());
    LineRef previous = routeLine;
    for (LineRef line : lines) {
      changes.add(line.sameLine(previous) ? Optional.empty() : Optional.of(line));
      previous = line;
    }
    return List.copyOf(changes);
  }

  /**
   * 下标 {@code index} 之后的第一次换线（不含 {@code index} 本身：到站即已换线）。
   *
   * @param index 列车最近到达的下标；负数表示尚未到达第一站
   */
  public static Optional<Change> nextChangeAfter(
      List<RouteStop> stops, int index, LineRef routeLine) {
    Objects.requireNonNull(routeLine, "routeLine");
    if (stops == null || stops.isEmpty()) {
      return Optional.empty();
    }
    LineRef current = lineAt(stops, index, routeLine);
    for (int i = Math.max(0, index + 1); i < stops.size(); i++) {
      Optional<LineRef> target = target(stops.get(i));
      if (target.isEmpty()) {
        continue;
      }
      if (!target.get().sameLine(current)) {
        return Optional.of(new Change(i, current, target.get()));
      }
    }
    return Optional.empty();
  }

  /**
   * 列车当前对乘客显示的线路：线路标签（运营商、线路两个都有时）优先，否则为交路自身的线路。
   *
   * @param lineTag 列车的 {@code FTA_OPERATOR_CODE} + {@code FTA_LINE_CODE}（见 {@link
   *     LineRef#of(String, String)}）
   * @param routeLine 交路自身的线路
   * @return 两者都没有时为空
   */
  public static Optional<LineRef> current(Optional<LineRef> lineTag, Optional<LineRef> routeLine) {
    Optional<LineRef> tagged = lineTag == null ? Optional.empty() : lineTag;
    return tagged.or(() -> routeLine == null ? Optional.empty() : routeLine);
  }
}
