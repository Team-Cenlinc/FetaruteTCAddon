package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.display.Lateness;

/**
 * 站场图此刻要画的东西：几何（随调度图缓存）、各区间的占用标记、站场内的列车。
 *
 * <p>值相等即画面相同：几何按对象身份比较，标记与列车按值比较，站台屏据此判断要不要重画。
 *
 * @param geometry 几何；画不出来时为空
 * @param marks 有占用的区间，按区间排序；没列出的区间为空闲
 * @param trains 站场内的列车，按车次排序
 * @param problem 画不出来的原因
 */
public record YardPicture(
    Optional<YardGeometry> geometry,
    List<EdgeMark> marks,
    List<TrainMark> trains,
    Optional<Problem> problem) {

  public YardPicture {
    geometry = geometry == null ? Optional.empty() : geometry;
    marks = List.copyOf(marks);
    trains = List.copyOf(trains);
    problem = problem == null ? Optional.empty() : problem;
  }

  /** 画不出来。 */
  public static YardPicture unavailable(Problem problem) {
    return new YardPicture(Optional.empty(), List.of(), List.of(), Optional.of(problem));
  }

  /** 画不出来的原因。 */
  public enum Problem {
    /** 车站所在世界还没有调度图。 */
    GRAPH_NOT_READY,
    /** 调度图里没有本站（控制区成员）的站台、股道或咽喉节点。 */
    NOT_IN_GRAPH,
    /** 屏幕绑的控制区不存在（已删除或代码有误）。 */
    ZONE_NOT_FOUND,
    /** 第一次画这片站场，几何还在后台计算。 */
    BUILDING
  }

  /** 区间的占用标记。 */
  public enum Mark {
    /** 已授予行车（MOVEMENT_REQUIRED）：进路锁闭。 */
    ROUTE,
    /** 尾部保护、停车保持或交接中的旧进路（PROTECTIVE_RETAIN、HOLD_ONLY、PHYSICAL_FOOTPRINT）：还没出清。 */
    HELD
  }

  /** 一条区间的标记。 */
  public record EdgeMark(EdgeId edge, Mark mark) {
    public EdgeMark {
      Objects.requireNonNull(edge, "edge");
      Objects.requireNonNull(mark, "mark");
    }
  }

  /**
   * 站场内的一列车。
   *
   * @param name 列车名（信号员控制台按它认选中的车、点车次窗时回传）
   * @param label 车次窗文字：车次号，没有时为列车名
   * @param lineColor 线路色；查不到时为空
   * @param lateness 晚点程度
   * @param runs 连续落在站场图上的车厢，按编组顺序分段：段内相邻车厢连成车身，不在图上的车厢处断开；第一段的第一节是车次窗的锚点
   */
  public record TrainMark(
      String name,
      String label,
      Optional<Integer> lineColor,
      Lateness lateness,
      List<List<YardGeometry.Point>> runs) {

    /** 列车名与车次窗文字相同（不按表运行的车）。 */
    public TrainMark(
        String label,
        Optional<Integer> lineColor,
        Lateness lateness,
        List<List<YardGeometry.Point>> runs) {
      this(label, label, lineColor, lateness, runs);
    }

    public TrainMark {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(label, "label");
      lineColor = lineColor == null ? Optional.empty() : lineColor;
      Objects.requireNonNull(lateness, "lateness");
      runs = List.copyOf(runs.stream().filter(run -> !run.isEmpty()).map(List::copyOf).toList());
      if (runs.isEmpty()) {
        throw new IllegalArgumentException("站场内的列车至少要有一节车厢在图上");
      }
    }

    /** 车次窗的锚点：第一节落在图上的车厢。 */
    public YardGeometry.Point anchor() {
      return runs.get(0).get(0);
    }
  }
}
