package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Arrival;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Badge;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 解编页：站台屏轮播里的一页（与空位页同一位置），本站台下一班列车在前方某站（或本站）分行（分开运行）时，画出整列车， 按去向分段标出哪几节开往哪里，让乘客在站台上就站到对的车厢前。
 *
 * <p>车厢按车头起算的顺序分段；留下继续本交路的一段实心，解下另走的一段空心（不靠颜色区分，深浅主题、任何线路色都分得清）。 车头朝向与空位页相同，对上现场。是纯数据，{@code
 * equals} 即是否需要重绘。
 *
 * <p>同一几何也用于刚在本站拆开以后的车厢页（两段之间留空，不再载客的一段灰色虚线）与联挂页（还没到的几节虚线），见 {@link Style}。
 *
 * @param theme 配色
 * @param badge 线路色牌（车厢实心段也用它的颜色）
 * @param title 标题：在哪一站分开（“本次列车在 XX 分行”，本站分行时“本次列车在本站分行”）；拆开以后、联挂页另有写法
 * @param arrival 多久到达（与主页首行相同）
 * @param segments 各段车厢，车头在前
 * @param front 车头朝屏幕哪一侧；不知道时车头画在左侧
 * @param minutes “分 / min”
 * @param bandColors 线路色带（与主页相同）
 */
public record PidsDivisionView(
    PidsTheme theme,
    Badge badge,
    Names title,
    Arrival arrival,
    List<Segment> segments,
    Optional<PidsVacancyView.Front> front,
    Names minutes,
    List<Integer> bandColors) {

  public PidsDivisionView {
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(badge, "badge");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(arrival, "arrival");
    segments = List.copyOf(segments);
    front = front == null ? Optional.empty() : front;
    Objects.requireNonNull(minutes, "minutes");
    bandColors = List.copyOf(bandColors);
  }

  /** 列车一共几节。 */
  public int cars() {
    return segments.stream().mapToInt(Segment::cars).sum();
  }

  /**
   * 一段车厢。
   *
   * @param cars 节数
   * @param destination 这一段的说明（“前 4 节开往 XX / Front 4 cars to XX”）
   * @param style 画法
   * @param gapBefore 与前一段之间留空（已经拆开的两段）
   */
  public record Segment(int cars, Names destination, Style style, boolean gapBefore) {

    public Segment {
      cars = Math.max(1, cars);
      Objects.requireNonNull(destination, "destination");
      Objects.requireNonNull(style, "style");
    }

    /**
     * 解编页的一段。
     *
     * @param detached 在分开站解下另走（画成空心）；否则留下继续本交路（实心）
     */
    public Segment(int cars, Names destination, boolean detached) {
      this(cars, destination, detached ? Style.HOLLOW : Style.SOLID, false);
    }

    /** 解下另走的一段（画成空心）。 */
    public boolean detached() {
      return style == Style.HOLLOW;
    }
  }

  /** 一段车厢的画法：不靠颜色区分，深浅主题、任何线路色都分得清。 */
  public enum Style {
    /** 线路色实心：留下继续本交路的一段、联挂页上本列车（或已在站台）的几节。 */
    SOLID,
    /** 描线路色的空心：解下另走的一段。 */
    HOLLOW,
    /** 线路色虚线描边：还没到的几节（联挂时要挂上、正在进站的）。 */
    DASHED,
    /** 灰色虚线描边：拆开以后不再载客的一段。 */
    INACTIVE
  }
}
