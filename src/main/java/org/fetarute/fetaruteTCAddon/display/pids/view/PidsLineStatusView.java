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
 * @param page 第几页（0 起）；按运营商轮播时是这一家的第几页
 * @param pages 共几页；按运营商轮播时是这一家共几页
 * @param rotation 按运营商轮播时依次轮到的几家（运营商代码，页头标签用）；不轮播时为空
 * @param rotationIndex 这一页是其中第几家（0 起）；不轮播时为 0
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
    int pages,
    List<String> rotation,
    int rotationIndex) {

  /** 不按运营商轮播的一页。 */
  public PidsLineStatusView(
      PidsTheme theme,
      String clock,
      Names title,
      Names operator,
      Labels labels,
      List<Row> rows,
      boolean roomy,
      int page,
      int pages) {
    this(theme, clock, title, operator, labels, rows, roomy, page, pages, List.of(), 0);
  }

  public PidsLineStatusView {
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(labels, "labels");
    rows = List.copyOf(rows);
    rotation = List.copyOf(rotation);
    if (!rotation.isEmpty() && (rotationIndex < 0 || rotationIndex >= rotation.size())) {
      throw new IllegalArgumentException(
          "rotationIndex 超出轮播家数: " + rotationIndex + "/" + rotation.size());
    }
  }

  /** 按运营商轮播（不止一家）。 */
  public boolean rotating() {
    return rotation.size() > 1;
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
