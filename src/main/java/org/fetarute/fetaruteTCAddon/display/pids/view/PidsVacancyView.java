package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Arrival;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Badge;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 空位页：站台屏轮播里的一页（与宣传页同一套轮播），画本站台下一班列车的示意图——每节车厢按座位情况着色并写空位数， 标出多久到达、车头朝向对上现场，再附一句提示与图例。
 *
 * <p>是纯数据，{@code equals} 即是否需要重绘。
 *
 * @param theme 配色
 * @param badge 线路色牌
 * @param destination 终点
 * @param arrival 多久到达（与主页首行相同：分钟数，或进站、停靠中）
 * @param cars 各节车厢，车头在前
 * @param front 车头朝屏幕哪一侧；屏幕不顺着轨道、或位置数据不全时为空，此时车头画在左侧
 * @param labels 文案
 * @param bandColors 线路色带（与主页相同）
 */
public record PidsVacancyView(
    PidsTheme theme,
    Badge badge,
    Names destination,
    Arrival arrival,
    List<Car> cars,
    Optional<Front> front,
    Labels labels,
    List<Integer> bandColors) {

  public PidsVacancyView {
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(badge, "badge");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(arrival, "arrival");
    cars = List.copyOf(cars);
    front = front == null ? Optional.empty() : front;
    Objects.requireNonNull(labels, "labels");
    bandColors = List.copyOf(bandColors);
  }

  /** 一节车厢的座位情况。 */
  public enum Level {
    /** 座位充足：在座不到一半。 */
    MANY,
    /** 座位较少：在座一半到八成。 */
    SOME,
    /** 座位紧张：在座八成以上（含坐满）。 */
    FEW,
    /** 这节车没有座位。 */
    NONE
  }

  /**
   * 一节车厢。
   *
   * @param level 座位情况
   * @param vacant 空位数
   */
  public record Car(Level level, int vacant) {

    public Car {
      Objects.requireNonNull(level, "level");
      vacant = Math.max(0, vacant);
    }
  }

  /** 车头朝屏幕哪一侧（列车往那边开）。 */
  public enum Front {
    LEFT,
    RIGHT
  }

  /**
   * 空位页文案（中英文）。
   *
   * @param minutes “分 / min”
   * @param advice 提示（“请优先考虑较空的车厢”）
   * @param many 图例：座位充足
   * @param some 图例：座位较少
   * @param few 图例：座位紧张
   */
  public record Labels(Names minutes, Names advice, Names many, Names some, Names few) {

    public Labels {
      Objects.requireNonNull(minutes, "minutes");
      Objects.requireNonNull(advice, "advice");
      Objects.requireNonNull(many, "many");
      Objects.requireNonNull(some, "some");
      Objects.requireNonNull(few, "few");
    }
  }
}
