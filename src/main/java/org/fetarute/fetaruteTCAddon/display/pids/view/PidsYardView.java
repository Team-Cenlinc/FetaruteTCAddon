package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardPicture;

/**
 * 站场图屏的一帧。
 *
 * @param theme 配色
 * @param clock 当前时间（{@code HH:mm}）
 * @param station 站名
 * @param legend 图例文案
 * @param picture 站场图
 * @param message 画不出来时写在站场图中间的说明
 * @param note 有区间用直线代替时写在站场图左下角的说明
 */
public record PidsYardView(
    PidsTheme theme,
    String clock,
    Names station,
    Legend legend,
    YardPicture picture,
    Optional<Names> message,
    Optional<Names> note) {

  public PidsYardView {
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(legend, "legend");
    Objects.requireNonNull(picture, "picture");
    message = message == null ? Optional.empty() : message;
    note = note == null ? Optional.empty() : note;
  }

  /**
   * 图例文案。
   *
   * @param free 空闲
   * @param route 进路锁闭
   * @param held 保护占用
   * @param occupied 车占用
   * @param train 车次窗
   * @param note 车次窗颜色说明
   */
  public record Legend(
      Names free, Names route, Names held, Names occupied, Names train, Names note) {}
}
