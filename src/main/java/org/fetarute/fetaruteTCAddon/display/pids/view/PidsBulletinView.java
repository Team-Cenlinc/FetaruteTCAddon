package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 公告页的一页：左上图标块（一般钢蓝、重要琥珀）、标签与标题，正文按排版结果逐行画，底部与主页同一条线路色带。
 *
 * <p>纯数据，{@code equals} 即是否需要重绘。
 *
 * @param theme 配色
 * @param important 重要公告
 * @param label 标签“公告 / Notice”或“重要公告 / Important”
 * @param title 标题（中英；横屏标题每页都画）
 * @param page 这一页的排版
 * @param index 第几页（0 起）
 * @param pages 共几页
 * @param bandColors 色带颜色，与主页一致
 */
public record PidsBulletinView(
    PidsTheme theme,
    boolean important,
    Names label,
    Names title,
    PidsBulletinTypesetter.Page page,
    int index,
    int pages,
    List<Integer> bandColors) {

  public PidsBulletinView {
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(page, "page");
    bandColors = List.copyOf(bandColors);
  }

  /** 页码“1/2”；只有一页时为空串。 */
  public String pageLabel() {
    return pages > 1 ? (index + 1) + "/" + pages : "";
  }
}
