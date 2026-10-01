package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 宣传页或安全提示页：左边图标、中间标题、右边说明，底部与主页同一条线路色带。
 *
 * <p>纯数据，{@code equals} 即是否需要重绘。
 *
 * @param theme 配色
 * @param notice 哪一页
 * @param title 标题（中英）
 * @param body 说明（中英）
 * @param bandColors 色带颜色，与主页一致
 */
public record PidsNoticeView(
    PidsTheme theme, PidsNotice notice, Names title, Names body, List<Integer> bandColors) {

  public PidsNoticeView {
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(notice, "notice");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(body, "body");
    bandColors = List.copyOf(bandColors);
  }
}
