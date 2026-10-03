package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.LineChip;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 线路运行状况屏的一页。
 *
 * @param theme 配色
 * @param clock 当前时间（{@code HH:mm}）
 * @param title 页标题
 * @param operator 运营商名；不知道时写运营商代码
 * @param labels 表头与空表提示
 * @param rows 这一页的线路
 * @param roomy 线路少、一页放得下时用大行（字大一档）；否则用小行并翻页
 * @param page 第几页（0 起）
 * @param pages 共几页
 */
public record PidsLineStatusView(
    PidsTheme theme,
    String clock,
    Names title,
    Names operator,
    Labels labels,
    List<Row> rows,
    boolean roomy,
    int page,
    int pages) {

  public PidsLineStatusView {
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(labels, "labels");
    rows = List.copyOf(rows);
  }

  /**
   * 表头与空表提示。
   *
   * @param line 线路
   * @param status 运行状况
   * @param details 说明
   * @param empty 没有线路时写在表里
   */
  public record Labels(Names line, Names status, Names details, Names empty) {}

  /** 状况的样式。 */
  public enum Style {
    /** 运行正常：绿字。 */
    GOOD,
    /** 已结束运营、暂无列车：灰字。 */
    MUTED,
    /** 轻微延误、部分班次取消：琥珀色块反白。 */
    AMBER,
    /** 严重延误、部分停运、全线停运：红色块反白。 */
    RED
  }

  /**
   * 一条线路。
   *
   * @param line 线路色牌与中英文名
   * @param status 状况中英文
   * @param style 状况样式
   * @param detail 说明中英文；运行正常、暂无列车时为空
   */
  public record Row(LineChip line, Names status, Style style, Optional<Names> detail) {

    public Row {
      Objects.requireNonNull(line, "line");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(style, "style");
      detail = detail == null ? Optional.empty() : detail;
    }
  }
}
