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
 * @param theme 配色
 * @param badge 线路色牌（车厢实心段也用它的颜色）
 * @param title 标题：在哪一站分开（“本次列车在 XX 分行”，本站分行时“本次列车在本站分行”）
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
   * @param destination 这一段开往哪里（“开往 XX / To XX”）
   * @param detached 在分开站解下另走（画成空心）；否则留下继续本交路
   */
  public record Segment(int cars, Names destination, boolean detached) {

    public Segment {
      cars = Math.max(1, cars);
      Objects.requireNonNull(destination, "destination");
    }
  }
}
