package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 2×1 停站屏的后续列车页：本站台下一班之后的几班车。
 *
 * <p>是纯数据，{@code equals} 即脏标记。每班车沿用站台屏一行的写法（色牌、终点、多久到达与状态），渲染器按停站表组件的版式排成每班三行。
 *
 * @param theme 配色
 * @param clock 当前时间（{@code HH:mm}），布局带时钟组件时显示
 * @param platforms 站台号方块里写的站台
 * @param trains 下一班之后可以上车的列车与取消的班次，按到站先后，不多于一页放得下的数量
 * @param title 页标题“后续列车 / Following trains”
 * @param minutes “分 / min”
 * @param bandColors 线路色带
 */
public record PidsFollowingView(
    PidsTheme theme,
    String clock,
    List<String> platforms,
    List<PidsView.Row> trains,
    Names title,
    Names minutes,
    List<Integer> bandColors) {

  public PidsFollowingView {
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(clock, "clock");
    platforms = List.copyOf(platforms);
    trains = List.copyOf(trains);
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(minutes, "minutes");
    bandColors = List.copyOf(bandColors);
  }
}
