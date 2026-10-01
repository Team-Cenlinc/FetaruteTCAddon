package org.fetarute.fetaruteTCAddon.display.hud.trip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.display.hud.HudText;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContextResolver.UpcomingStop;

/**
 * 后续站点对话框的内容：标题、列车概况与前方各站，全部是已渲染的组件，不依赖服务端的对话框实现。
 *
 * <p>文字全部来自语言文件里的 HUD 模板写法（{@code {key}} 占位符 + MiniMessage），占位符与车内显示屏的列表行相同，
 * 所以管理员改文案不需要学第二套语法。每站一行标题加若干说明行，说明行里的条件占位符 {@code {?key}} 缺值时整行省略。
 *
 * @param title 对话框标题
 * @param summary 列车概况（车次、编组、所在车厢）
 * @param rows 前方各站
 * @param hidden 没有列出的站数（超出上限，或“只看换乘站”时被筛掉的）
 * @param transfersOnly 是否只列换乘站
 */
public record TripSheet(
    Component title, Component summary, List<Row> rows, int hidden, boolean transfersOnly) {

  public TripSheet {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(summary, "summary");
    rows = List.copyOf(rows);
  }

  /** 站的种类，决定对话框里这一行的图标。 */
  public enum Kind {
    /** 下一站。 */
    NEXT,
    /** 普通停靠站。 */
    STOP,
    /** 有换乘的停靠站。 */
    TRANSFER,
    /** 终点站。 */
    TERMINAL
  }

  /**
   * 一站。
   *
   * @param kind 种类
   * @param text 标题行与说明行（多行）
   */
  public record Row(Kind kind, Component text) {
    public Row {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(text, "text");
    }
  }

  /**
   * 语言文件里的对话框文案。
   *
   * @param title 标题模板
   * @param summary 概况模板
   * @param next 下一站标题行
   * @param stop 普通站标题行
   * @param terminal 终点站标题行
   * @param details 每站的说明行
   */
  public record Texts(
      String title,
      String summary,
      String next,
      String stop,
      String terminal,
      List<String> details) {
    public Texts {
      details = List.copyOf(details);
    }

    /**
     * 从语言文件读取。
     *
     * @param text 单行文案（键 → 原文）
     * @param lines 多行文案（键 → 原文列表）
     */
    public static Texts load(Function<String, String> text, Function<String, List<String>> lines) {
      return new Texts(
          text.apply("display.trip.title"),
          text.apply("display.trip.summary"),
          text.apply("display.trip.row.next"),
          text.apply("display.trip.row.stop"),
          text.apply("display.trip.row.terminal"),
          lines.apply("display.trip.row.details"));
    }
  }

  /**
   * 组装对话框内容。
   *
   * <p>行内占位符额外提供 {@code line_change}：直通运转在这一站换到另一条线时为新线路名，否则缺值。 “只看换乘站”时仍保留下一站、终点站和换线站，乘客始终知道车往哪开。
   *
   * @param texts 文案
   * @param placeholders 列车的全局占位符
   * @param stops 前方停靠站（已按上限截取）
   * @param total 前方停靠站总数
   * @param transfersOnly 是否只列换乘站
   * @param currentLine 列车当前所属线路
   * @param rowPlaceholders 一行的占位符（全局占位符、停靠站、行序号）
   */
  public static TripSheet build(
      Texts texts,
      Map<String, String> placeholders,
      List<UpcomingStop> stops,
      int total,
      boolean transfersOnly,
      Optional<RouteLineChanges.LineRef> currentLine,
      BiFunction<UpcomingStop, Integer, Map<String, String>> rowPlaceholders) {
    List<Row> rows = new ArrayList<>();
    Optional<RouteLineChanges.LineRef> previousLine = currentLine;
    for (int i = 0; i < stops.size(); i++) {
      UpcomingStop stop = stops.get(i);
      Map<String, String> values = new HashMap<>(rowPlaceholders.apply(stop, i + 1));
      boolean lineChange =
          stop.line().isPresent()
              && previousLine.map(previous -> !previous.sameLine(stop.line().get())).orElse(false);
      values.put("line_change", lineChange ? values.getOrDefault("line", "") : HudText.MISSING);
      if (stop.line().isPresent()) {
        previousLine = stop.line();
      }
      Kind kind = kind(i, stop);
      if (transfersOnly && kind == Kind.STOP && !lineChange) {
        continue;
      }
      rows.add(new Row(kind, rowText(texts, kind, values)));
    }
    return new TripSheet(
        HudText.render(texts.title(), placeholders, null),
        HudText.render(texts.summary(), placeholders, null),
        rows,
        Math.max(0, total - rows.size()),
        transfersOnly);
  }

  private static Kind kind(int index, UpcomingStop stop) {
    if (stop.terminal()) {
      return Kind.TERMINAL;
    }
    if (index == 0) {
      return Kind.NEXT;
    }
    return stop.transfers().isEmpty() ? Kind.STOP : Kind.TRANSFER;
  }

  private static Component rowText(Texts texts, Kind kind, Map<String, String> values) {
    String headline =
        switch (kind) {
          case NEXT -> texts.next();
          case TERMINAL -> texts.terminal();
          case STOP, TRANSFER -> texts.stop();
        };
    StringBuilder out = new StringBuilder(HudText.apply(headline, values));
    for (String detail : texts.details()) {
      if (HudText.shown(detail, values)) {
        out.append('\n').append(HudText.apply(detail, values));
      }
    }
    return HudText.parse(out.toString(), null);
  }
}
