package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Arrival;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Badge;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Label;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 2×1 停站屏：本站台下一班可以上车的列车往后停哪些站。
 *
 * <p>是纯数据，{@code equals} 即脏标记。停站多时分页，{@link #page} 由合成器按时钟定，渲染器按布局的每页行数取这一页的站。
 *
 * @param theme 配色
 * @param clock 当前时间（{@code HH:mm}），布局带时钟组件时显示
 * @param platforms 站台号方块里写的站台
 * @param train 下一班；本站台没有可以上车的列车时为空，屏上写“暂无后续列车”
 * @param cancelled 下一班之前（没有下一班时为窗口内）本站台被取消的第一班；终点下面一行优先写它
 * @param page 第几页（0 起）
 * @param labels 固定文案
 * @param bandColors 线路色带
 */
public record PidsStopListView(
    PidsTheme theme,
    String clock,
    List<String> platforms,
    Optional<Train> train,
    Optional<Note> cancelled,
    int page,
    Labels labels,
    List<Integer> bandColors) {

  public PidsStopListView {
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(clock, "clock");
    platforms = List.copyOf(platforms);
    train = train == null ? Optional.empty() : train;
    cancelled = cancelled == null ? Optional.empty() : cancelled;
    Objects.requireNonNull(labels, "labels");
    bandColors = List.copyOf(bandColors);
  }

  /** 翻到另一页。 */
  public PidsStopListView withPage(int page) {
    return new PidsStopListView(
        theme, clock, platforms, train, cancelled, page, labels, bandColors);
  }

  /** 终点下面一行写什么：取消的班次优先，其次是下一班的直通或经由。 */
  public Optional<Note> note() {
    return cancelled.or(() -> train.flatMap(Train::note));
  }

  /**
   * 下一班。
   *
   * @param id 这一班的身份：运行中的车为列车名，计划班次为交路与计划时刻，其余（票据、预测）为交路与停靠序号；同一交路相邻两班也不相同，停站屏据此换车从第 1 页起
   * @param badge 线路色牌（与站台屏首行相同）
   * @param destination 终点
   * @param arrival 多久到达（与站台屏首行相同）
   * @param status 要提醒的状态：站台待定、站台变更、晚点（琥珀或红色）；准点、计划不写。写在最底下一行左侧
   * @param note 终点下面一行：直通或经由；都没有时为空
   * @param stops 本站之后的停车站，到终点为止
   */
  public record Train(
      String id,
      Badge badge,
      Names destination,
      Arrival arrival,
      Optional<Label> status,
      Optional<Note> note,
      List<Stop> stops) {

    public Train {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(badge, "badge");
      Objects.requireNonNull(destination, "destination");
      Objects.requireNonNull(arrival, "arrival");
      status = status == null ? Optional.empty() : status;
      note = note == null ? Optional.empty() : note;
      stops = List.copyOf(stops);
    }
  }

  /**
   * 终点下面一行：左侧方形标签（取消用红色，直通 / thru 用换入线路的颜色，经由 / via 用琥珀色），右侧两行字。
   *
   * @param tag 标签（中英文上下叠放）
   * @param color 标签底色；字色按底色亮度取黑白
   * @param primary 第一行：取消班次的终点与计划时刻，换入的线路名，或经由站名
   * @param chip 第一行后面的线路代码小牌（直通时）；底色同标签
   * @param secondary 第二行：取消班次终点的英文名，从哪站起直通，或经由站英文名
   */
  public record Note(
      Names tag, int color, String primary, Optional<String> chip, String secondary) {

    public Note {
      Objects.requireNonNull(tag, "tag");
      Objects.requireNonNull(primary, "primary");
      chip = chip == null ? Optional.empty() : chip;
      Objects.requireNonNull(secondary, "secondary");
    }
  }

  /** 停站表里一站的标记。 */
  public enum Kind {
    /** 下一站：小圆点用正文色。 */
    NEXT,
    /** 普通停车站。 */
    STOP,
    /** 经由站：琥珀色大圆点，站名后加“经由”标签。 */
    VIA,
    /** 换线站（直通）：圆点上半旧线路色、下半新线路色，站名后加“直通”标签。 */
    CHANGE,
    /** 终点：空心大圆点。 */
    TERMINAL
  }

  /**
   * 停站表里的一站。
   *
   * @param names 站名
   * @param kind 标记
   * @param color 列车到这一站时所属线路的颜色（竖线与圆点）
   * @param after 离开这一站时所属线路的颜色；只有换线站与 {@code color} 不同
   * @param transfers 可换乘的其他线路颜色（站名右侧的小方块），不含列车所属的线路
   */
  public record Stop(Names names, Kind kind, int color, int after, List<Integer> transfers) {

    public Stop {
      Objects.requireNonNull(names, "names");
      Objects.requireNonNull(kind, "kind");
      transfers = List.copyOf(transfers);
    }
  }

  /**
   * 固定文案。
   *
   * @param minutes “分 / min”
   * @param noMoreTrains “暂无后续列车 / No further trains”
   * @param via 站名后的“经由”标签
   * @param through 站名后的“直通”标签
   */
  public record Labels(Names minutes, Names noMoreTrains, Names via, Names through) {}
}
